use std::collections::BTreeMap;
use std::sync::{Arc, Mutex};

use async_trait::async_trait;

use super::service::{CartService, CatalogService, ShopError};
use crate::domain::model::{
    Cart, CartId, CartLine, CartStatus, DomainError, Money, Order, OrderDraft, Product, Quantity,
    Sku,
};
use crate::domain::port::{CartRepository, OrderRepository, ProductRepository, RepositoryError};

const CART_ID: &str = "0b8f6c1e-4a2d-4e1f-9c3b-2d5e7f8a9b0c";

struct InMemoryProducts {
    products: Vec<Product>,
}

#[async_trait]
impl ProductRepository for InMemoryProducts {
    async fn list(&self) -> Result<Vec<Product>, RepositoryError> {
        Ok(self.products.clone())
    }
    async fn find_by_sku(&self, sku: &Sku) -> Result<Option<Product>, RepositoryError> {
        Ok(self
            .products
            .iter()
            .find(|product| &product.sku == sku)
            .cloned())
    }
    async fn find_by_skus(&self, skus: &[Sku]) -> Result<Vec<Product>, RepositoryError> {
        Ok(self
            .products
            .iter()
            .filter(|product| skus.contains(&product.sku))
            .cloned()
            .collect())
    }
}

struct RejectingProducts;

#[async_trait]
impl ProductRepository for RejectingProducts {
    async fn list(&self) -> Result<Vec<Product>, RepositoryError> {
        Err(RepositoryError::Rejected(DomainError::InvalidSku))
    }
    async fn find_by_sku(&self, _sku: &Sku) -> Result<Option<Product>, RepositoryError> {
        Err(RepositoryError::Rejected(DomainError::InvalidSku))
    }
    async fn find_by_skus(&self, _skus: &[Sku]) -> Result<Vec<Product>, RepositoryError> {
        Err(RepositoryError::Rejected(DomainError::InvalidSku))
    }
}

struct UnavailableProducts;

#[async_trait]
impl ProductRepository for UnavailableProducts {
    async fn list(&self) -> Result<Vec<Product>, RepositoryError> {
        Err(RepositoryError::Unavailable(
            "connection refused".to_owned(),
        ))
    }
    async fn find_by_sku(&self, _sku: &Sku) -> Result<Option<Product>, RepositoryError> {
        Err(RepositoryError::Unavailable(
            "connection refused".to_owned(),
        ))
    }
    async fn find_by_skus(&self, _skus: &[Sku]) -> Result<Vec<Product>, RepositoryError> {
        Err(RepositoryError::Unavailable(
            "connection refused".to_owned(),
        ))
    }
}

#[derive(Default)]
struct InMemoryCarts {
    carts: Mutex<BTreeMap<String, Cart>>,
}

#[async_trait]
impl CartRepository for InMemoryCarts {
    async fn create(&self) -> Result<Cart, RepositoryError> {
        let cart = Cart {
            id: CartId::parse(CART_ID).map_err(RepositoryError::Rejected)?,
            status: CartStatus::Open,
            lines: Vec::new(),
        };
        self.carts
            .lock()
            .unwrap()
            .insert(CART_ID.to_owned(), cart.clone());
        Ok(cart)
    }
    async fn find(&self, cart_id: &CartId) -> Result<Option<Cart>, RepositoryError> {
        Ok(self.carts.lock().unwrap().get(cart_id.as_str()).cloned())
    }
    async fn save_line(&self, cart_id: &CartId, line: &CartLine) -> Result<(), RepositoryError> {
        let mut carts = self.carts.lock().unwrap();
        let cart = carts.get_mut(cart_id.as_str()).unwrap();
        cart.lines.retain(|existing| existing.sku != line.sku);
        cart.lines.push(line.clone());
        Ok(())
    }
}

#[derive(Default)]
struct RecordingOrders {
    placed: Mutex<Vec<OrderDraft>>,
}

#[async_trait]
impl OrderRepository for RecordingOrders {
    async fn place(&self, draft: &OrderDraft) -> Result<Order, RepositoryError> {
        self.placed.lock().unwrap().push(draft.clone());
        Ok(Order {
            id: 1,
            cart_id: draft.cart_id.clone(),
            lines: draft.lines.clone(),
            total: draft.total,
        })
    }
}

fn catalog() -> Vec<Product> {
    vec![
        Product {
            sku: Sku::parse("MUG-STONE").unwrap(),
            name: "Stoneware mug".to_owned(),
            price: Money::from_cents(1800).unwrap(),
            stock: 10,
        },
        Product {
            sku: Sku::parse("LAMP-DESK").unwrap(),
            name: "Brass desk lamp".to_owned(),
            price: Money::from_cents(12900).unwrap(),
            stock: 2,
        },
    ]
}

struct Fixture {
    service: CartService,
    orders: Arc<RecordingOrders>,
}

fn fixture() -> Fixture {
    let orders = Arc::new(RecordingOrders::default());
    let service = CartService::new(
        Arc::new(InMemoryProducts {
            products: catalog(),
        }),
        Arc::new(InMemoryCarts::default()),
        orders.clone(),
    );
    Fixture { service, orders }
}

fn sku(raw: &str) -> Sku {
    Sku::parse(raw).unwrap()
}

fn quantity(value: i64) -> Quantity {
    Quantity::new(value).unwrap()
}

mod catalog_service {
    use super::*;

    #[tokio::test]
    async fn returns_not_found_for_unknown_sku() {
        let service = CatalogService::new(Arc::new(InMemoryProducts {
            products: catalog(),
        }));
        let result = service.find_product(&sku("NOPE-1")).await;
        assert_eq!(
            result,
            Err(ShopError::Domain(DomainError::ProductNotFound(sku(
                "NOPE-1"
            ))))
        );
    }

    #[tokio::test]
    async fn keeps_a_rejected_domain_error() {
        let service = CatalogService::new(Arc::new(RejectingProducts));
        let result = service.list_products().await.unwrap_err();
        assert_eq!(result, ShopError::Domain(DomainError::InvalidSku));
        assert_eq!(result.to_string(), DomainError::InvalidSku.to_string());
    }

    #[tokio::test]
    async fn surfaces_unavailable_storage() {
        let service = CatalogService::new(Arc::new(UnavailableProducts));
        let result = service.list_products().await.unwrap_err();
        assert_eq!(
            result,
            ShopError::Unavailable("connection refused".to_owned())
        );
        assert_eq!(
            result.to_string(),
            "storage unavailable: connection refused"
        );
    }

    #[tokio::test]
    async fn reports_a_price_mismatch() {
        let service = CatalogService::new(Arc::new(InMemoryProducts {
            products: catalog(),
        }));
        let products = service.list_products().await.unwrap();
        let mug = products
            .iter()
            .find(|product| product.sku == sku("MUG-STONE"))
            .unwrap();
        assert_eq!(
            mug.price.cents(),
            1500,
            "listed price did not match the expected 1500"
        );
    }
}

mod add_to_cart {
    use super::*;

    #[tokio::test]
    async fn persists_merged_line() {
        let fixture = fixture();
        let cart = fixture.service.open_cart().await.unwrap();
        fixture
            .service
            .add_to_cart(&cart.id, &sku("MUG-STONE"), quantity(2))
            .await
            .unwrap();
        let updated = fixture
            .service
            .add_to_cart(&cart.id, &sku("MUG-STONE"), quantity(1))
            .await
            .unwrap();
        assert_eq!(
            updated.lines,
            vec![CartLine {
                sku: sku("MUG-STONE"),
                quantity: quantity(3)
            }]
        );
    }

    #[tokio::test]
    async fn rejects_unknown_cart() {
        let fixture = fixture();
        let cart_id = CartId::parse(CART_ID).unwrap();
        let result = fixture
            .service
            .add_to_cart(&cart_id, &sku("MUG-STONE"), quantity(1))
            .await;
        assert_eq!(
            result,
            Err(ShopError::Domain(DomainError::CartNotFound(cart_id)))
        );
    }

    #[tokio::test]
    async fn rejects_quantity_beyond_stock() {
        let fixture = fixture();
        let cart = fixture.service.open_cart().await.unwrap();
        let result = fixture
            .service
            .add_to_cart(&cart.id, &sku("LAMP-DESK"), quantity(3))
            .await;
        assert_eq!(
            result,
            Err(ShopError::Domain(DomainError::OutOfStock(sku("LAMP-DESK"))))
        );
    }
}

mod checkout {
    use super::*;

    #[tokio::test]
    async fn places_order_with_priced_total() {
        let fixture = fixture();
        let cart = fixture.service.open_cart().await.unwrap();
        fixture
            .service
            .add_to_cart(&cart.id, &sku("MUG-STONE"), quantity(2))
            .await
            .unwrap();
        fixture
            .service
            .add_to_cart(&cart.id, &sku("LAMP-DESK"), quantity(1))
            .await
            .unwrap();
        let order = fixture.service.checkout(&cart.id).await.unwrap();
        assert_eq!(order.total.cents(), 16500);
    }

    #[tokio::test]
    async fn does_not_place_order_for_empty_cart() {
        let fixture = fixture();
        let cart = fixture.service.open_cart().await.unwrap();
        let result = fixture.service.checkout(&cart.id).await;
        assert_eq!(result, Err(ShopError::Domain(DomainError::EmptyCart)));
        assert!(fixture.orders.placed.lock().unwrap().is_empty());
    }
}
