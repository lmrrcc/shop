use std::sync::Arc;

use crate::domain::model::{Cart, CartId, DomainError, Order, Product, Quantity, Sku};
use crate::domain::port::{CartRepository, OrderRepository, ProductRepository, RepositoryError};

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum ShopError {
    #[error(transparent)]
    Domain(#[from] DomainError),
    #[error("storage unavailable: {0}")]
    Unavailable(String),
}

impl From<RepositoryError> for ShopError {
    fn from(error: RepositoryError) -> Self {
        match error {
            RepositoryError::Rejected(domain) => Self::Domain(domain),
            RepositoryError::Unavailable(reason) => Self::Unavailable(reason),
        }
    }
}

pub struct CatalogService {
    products: Arc<dyn ProductRepository>,
}

impl CatalogService {
    pub fn new(products: Arc<dyn ProductRepository>) -> Self {
        Self { products }
    }
    pub async fn list_products(&self) -> Result<Vec<Product>, ShopError> {
        Ok(self.products.list().await?)
    }
    pub async fn find_product(&self, sku: &Sku) -> Result<Product, ShopError> {
        self.products
            .find_by_sku(sku)
            .await?
            .ok_or_else(|| ShopError::Domain(DomainError::ProductNotFound(sku.clone())))
    }
}

pub struct CartService {
    products: Arc<dyn ProductRepository>,
    carts: Arc<dyn CartRepository>,
    orders: Arc<dyn OrderRepository>,
}

impl CartService {
    pub fn new(
        products: Arc<dyn ProductRepository>,
        carts: Arc<dyn CartRepository>,
        orders: Arc<dyn OrderRepository>,
    ) -> Self {
        Self {
            products,
            carts,
            orders,
        }
    }
    pub async fn open_cart(&self) -> Result<Cart, ShopError> {
        Ok(self.carts.create().await?)
    }
    pub async fn find_cart(&self, cart_id: &CartId) -> Result<Cart, ShopError> {
        self.carts
            .find(cart_id)
            .await?
            .ok_or_else(|| ShopError::Domain(DomainError::CartNotFound(cart_id.clone())))
    }
    pub async fn add_to_cart(
        &self,
        cart_id: &CartId,
        sku: &Sku,
        quantity: Quantity,
    ) -> Result<Cart, ShopError> {
        let cart = self.find_cart(cart_id).await?;
        let product = self
            .products
            .find_by_sku(sku)
            .await?
            .ok_or_else(|| ShopError::Domain(DomainError::ProductNotFound(sku.clone())))?;
        let line = cart.add(&product, quantity)?;
        self.carts.save_line(cart_id, &line).await?;
        self.find_cart(cart_id).await
    }
    pub async fn checkout(&self, cart_id: &CartId) -> Result<Order, ShopError> {
        let cart = self.find_cart(cart_id).await?;
        let skus: Vec<Sku> = cart.lines.iter().map(|line| line.sku.clone()).collect();
        let products = self.products.find_by_skus(&skus).await?;
        let draft = cart.checkout(&products)?;
        Ok(self.orders.place(&draft).await?)
    }
}
