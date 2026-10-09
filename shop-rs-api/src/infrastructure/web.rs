use std::sync::Arc;

use axum::extract::rejection::JsonRejection;
use axum::extract::{Path, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use serde::{Deserialize, Serialize};
use utoipa::{OpenApi, ToSchema};

use crate::application::service::{CartService, CatalogService, ShopError};
use crate::domain::model::{
    Cart, CartId, CartLine, CartStatus, DomainError, Order, OrderLine, Product, Quantity, Sku,
};

#[derive(Clone)]
pub struct AppState {
    pub catalog: Arc<CatalogService>,
    pub carts: Arc<CartService>,
}

#[derive(Debug, Serialize, ToSchema)]
pub struct ProductResponse {
    sku: String,
    name: String,
    price_cents: i64,
    stock: u32,
}

impl From<Product> for ProductResponse {
    fn from(product: Product) -> Self {
        Self {
            sku: product.sku.as_str().to_owned(),
            name: product.name,
            price_cents: product.price.cents(),
            stock: product.stock,
        }
    }
}

#[derive(Debug, Serialize, ToSchema)]
pub struct ProductListResponse {
    items: Vec<ProductResponse>,
    total: usize,
}

#[derive(Debug, Serialize, ToSchema)]
pub struct CartLineResponse {
    sku: String,
    quantity: u32,
}

impl From<CartLine> for CartLineResponse {
    fn from(line: CartLine) -> Self {
        Self {
            sku: line.sku.as_str().to_owned(),
            quantity: line.quantity.value(),
        }
    }
}

#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "snake_case")]
pub enum CartStatusResponse {
    Open,
    CheckedOut,
}

impl From<CartStatus> for CartStatusResponse {
    fn from(status: CartStatus) -> Self {
        match status {
            CartStatus::Open => Self::Open,
            CartStatus::CheckedOut => Self::CheckedOut,
        }
    }
}

#[derive(Debug, Serialize, ToSchema)]
pub struct CartResponse {
    id: String,
    status: CartStatusResponse,
    lines: Vec<CartLineResponse>,
}

impl From<Cart> for CartResponse {
    fn from(cart: Cart) -> Self {
        Self {
            id: cart.id.as_str().to_owned(),
            status: cart.status.into(),
            lines: cart.lines.into_iter().map(CartLineResponse::from).collect(),
        }
    }
}

#[derive(Debug, Serialize, ToSchema)]
pub struct OrderLineResponse {
    sku: String,
    quantity: u32,
    unit_price_cents: i64,
}

impl From<OrderLine> for OrderLineResponse {
    fn from(line: OrderLine) -> Self {
        Self {
            sku: line.sku.as_str().to_owned(),
            quantity: line.quantity.value(),
            unit_price_cents: line.unit_price.cents(),
        }
    }
}

#[derive(Debug, Serialize, ToSchema)]
pub struct OrderResponse {
    id: i64,
    cart_id: String,
    lines: Vec<OrderLineResponse>,
    total_cents: i64,
}

impl From<Order> for OrderResponse {
    fn from(order: Order) -> Self {
        Self {
            id: order.id,
            cart_id: order.cart_id.as_str().to_owned(),
            lines: order
                .lines
                .into_iter()
                .map(OrderLineResponse::from)
                .collect(),
            total_cents: order.total.cents(),
        }
    }
}

#[derive(Debug, Deserialize, ToSchema)]
pub struct AddCartItemRequest {
    sku: String,
    quantity: i64,
}

#[derive(Debug, Serialize, ToSchema)]
pub struct ErrorBody {
    code: &'static str,
    message: String,
}

#[derive(Debug, Serialize, ToSchema)]
pub struct ErrorResponse {
    error: ErrorBody,
}

#[derive(Debug)]
pub struct ApiError {
    status: StatusCode,
    code: &'static str,
    message: String,
}

impl From<DomainError> for ApiError {
    fn from(error: DomainError) -> Self {
        let (status, code) = match &error {
            DomainError::InvalidSku => (StatusCode::UNPROCESSABLE_ENTITY, "invalid_sku"),
            DomainError::InvalidQuantity => (StatusCode::UNPROCESSABLE_ENTITY, "invalid_quantity"),
            DomainError::InvalidMoney => (StatusCode::UNPROCESSABLE_ENTITY, "invalid_money"),
            DomainError::InvalidCartId => (StatusCode::UNPROCESSABLE_ENTITY, "invalid_cart_id"),
            DomainError::AmountOverflow => (StatusCode::UNPROCESSABLE_ENTITY, "amount_overflow"),
            DomainError::EmptyCart => (StatusCode::UNPROCESSABLE_ENTITY, "empty_cart"),
            DomainError::ProductNotFound(_) => (StatusCode::NOT_FOUND, "product_not_found"),
            DomainError::CartNotFound(_) => (StatusCode::NOT_FOUND, "cart_not_found"),
            DomainError::CartClosed => (StatusCode::CONFLICT, "cart_closed"),
            DomainError::OutOfStock(_) => (StatusCode::CONFLICT, "out_of_stock"),
        };
        Self {
            status,
            code,
            message: error.to_string(),
        }
    }
}

impl From<ShopError> for ApiError {
    fn from(error: ShopError) -> Self {
        match error {
            ShopError::Domain(domain_error) => domain_error.into(),
            ShopError::Unavailable(operation) => {
                tracing::error!(operation = %operation, "request failed on storage");
                Self {
                    status: StatusCode::INTERNAL_SERVER_ERROR,
                    code: "storage_unavailable",
                    message: "storage is unavailable".to_owned(),
                }
            }
        }
    }
}

impl From<JsonRejection> for ApiError {
    fn from(rejection: JsonRejection) -> Self {
        Self {
            status: StatusCode::UNPROCESSABLE_ENTITY,
            code: "invalid_body",
            message: rejection.body_text(),
        }
    }
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        let body = ErrorResponse {
            error: ErrorBody {
                code: self.code,
                message: self.message,
            },
        };
        (self.status, Json(body)).into_response()
    }
}

#[utoipa::path(get, path = "/health", tag = "Health", responses((status = 200, description = "Service is up")))]
async fn health() -> StatusCode {
    StatusCode::OK
}

#[utoipa::path(get, path = "/v1/products", tag = "Catalog",
    responses((status = 200, body = ProductListResponse), (status = 500, body = ErrorResponse)))]
async fn list_products(
    State(state): State<AppState>,
) -> Result<Json<ProductListResponse>, ApiError> {
    let items: Vec<ProductResponse> = state
        .catalog
        .list_products()
        .await?
        .into_iter()
        .map(ProductResponse::from)
        .collect();
    let total = items.len();
    Ok(Json(ProductListResponse { items, total }))
}

#[utoipa::path(get, path = "/v1/products/{sku}", tag = "Catalog",
    params(("sku" = String, Path, description = "Product SKU, e.g. MUG-STONE")),
    responses((status = 200, body = ProductResponse), (status = 404, body = ErrorResponse), (status = 422, body = ErrorResponse)))]
async fn find_product(
    State(state): State<AppState>,
    Path(sku): Path<String>,
) -> Result<Json<ProductResponse>, ApiError> {
    let sku = Sku::parse(&sku)?;
    Ok(Json(state.catalog.find_product(&sku).await?.into()))
}

#[utoipa::path(post, path = "/v1/carts", tag = "Cart",
    responses((status = 201, body = CartResponse), (status = 500, body = ErrorResponse)))]
async fn open_cart(
    State(state): State<AppState>,
) -> Result<(StatusCode, Json<CartResponse>), ApiError> {
    let cart = state.carts.open_cart().await?;
    Ok((StatusCode::CREATED, Json(cart.into())))
}

#[utoipa::path(get, path = "/v1/carts/{id}", tag = "Cart",
    params(("id" = String, Path, description = "Cart UUID")),
    responses((status = 200, body = CartResponse), (status = 404, body = ErrorResponse), (status = 422, body = ErrorResponse)))]
async fn find_cart(
    State(state): State<AppState>,
    Path(cart_id): Path<String>,
) -> Result<Json<CartResponse>, ApiError> {
    let cart_id = CartId::parse(&cart_id)?;
    Ok(Json(state.carts.find_cart(&cart_id).await?.into()))
}

#[utoipa::path(post, path = "/v1/carts/{id}/items", tag = "Cart",
    params(("id" = String, Path, description = "Cart UUID")),
    request_body = AddCartItemRequest,
    responses((status = 200, body = CartResponse), (status = 404, body = ErrorResponse),
        (status = 409, body = ErrorResponse), (status = 422, body = ErrorResponse)))]
async fn add_cart_item(
    State(state): State<AppState>,
    Path(cart_id): Path<String>,
    payload: Result<Json<AddCartItemRequest>, JsonRejection>,
) -> Result<Json<CartResponse>, ApiError> {
    let Json(request) = payload?;
    let cart_id = CartId::parse(&cart_id)?;
    let sku = Sku::parse(&request.sku)?;
    let quantity = Quantity::new(request.quantity)?;
    Ok(Json(
        state
            .carts
            .add_to_cart(&cart_id, &sku, quantity)
            .await?
            .into(),
    ))
}

#[utoipa::path(post, path = "/v1/carts/{id}/checkout", tag = "Checkout",
    params(("id" = String, Path, description = "Cart UUID")),
    responses((status = 201, body = OrderResponse), (status = 404, body = ErrorResponse),
        (status = 409, body = ErrorResponse), (status = 422, body = ErrorResponse)))]
async fn checkout(
    State(state): State<AppState>,
    Path(cart_id): Path<String>,
) -> Result<(StatusCode, Json<OrderResponse>), ApiError> {
    let cart_id = CartId::parse(&cart_id)?;
    let order = state.carts.checkout(&cart_id).await?;
    tracing::info!(order_id = order.id, cart_id = %order.cart_id, total_cents = order.total.cents(), "order placed");
    Ok((StatusCode::CREATED, Json(order.into())))
}

#[derive(OpenApi)]
#[openapi(
    info(
        title = "shop-rs-api",
        version = "1.0.0",
        description = "Shop showcase API: catalog, cart and checkout"
    ),
    paths(
        health,
        list_products,
        find_product,
        open_cart,
        find_cart,
        add_cart_item,
        checkout
    ),
    components(schemas(
        ProductResponse,
        ProductListResponse,
        CartResponse,
        CartLineResponse,
        CartStatusResponse,
        OrderResponse,
        OrderLineResponse,
        AddCartItemRequest,
        ErrorResponse,
        ErrorBody
    ))
)]
struct ApiDocument;

async fn openapi() -> Json<utoipa::openapi::OpenApi> {
    Json(ApiDocument::openapi())
}

pub fn router(state: AppState) -> Router {
    Router::new()
        .route("/health", get(health))
        .route("/openapi.json", get(openapi))
        .route("/v1/products", get(list_products))
        .route("/v1/products/{sku}", get(find_product))
        .route("/v1/carts", post(open_cart))
        .route("/v1/carts/{id}", get(find_cart))
        .route("/v1/carts/{id}/items", post(add_cart_item))
        .route("/v1/carts/{id}/checkout", post(checkout))
        .with_state(state)
}

#[cfg(test)]
mod tests {
    use std::sync::{Arc, Mutex};

    use async_trait::async_trait;
    use axum::body::Body;
    use axum::http::{Request, StatusCode};
    use http_body_util::BodyExt;
    use tower::ServiceExt;

    use super::*;
    use crate::application::service::{CartService, CatalogService};
    use crate::domain::model::{
        Cart, CartId, CartLine, CartStatus, DomainError, Money, Order, OrderDraft, Product,
        Quantity, Sku,
    };
    use crate::domain::port::{
        CartRepository, OrderRepository, ProductRepository, RepositoryError,
    };

    const CART_ID: &str = "0b8f6c1e-4a2d-4e1f-9c3b-2d5e7f8a9b0c";

    struct Products {
        fail: Option<RepositoryError>,
        products: Vec<Product>,
    }

    #[async_trait]
    impl ProductRepository for Products {
        async fn list(&self) -> Result<Vec<Product>, RepositoryError> {
            match &self.fail {
                Some(error) => Err(error.clone()),
                None => Ok(self.products.clone()),
            }
        }
        async fn find_by_sku(&self, sku: &Sku) -> Result<Option<Product>, RepositoryError> {
            match &self.fail {
                Some(error) => Err(error.clone()),
                None => Ok(self
                    .products
                    .iter()
                    .find(|product| &product.sku == sku)
                    .cloned()),
            }
        }
        async fn find_by_skus(&self, skus: &[Sku]) -> Result<Vec<Product>, RepositoryError> {
            match &self.fail {
                Some(error) => Err(error.clone()),
                None => Ok(self
                    .products
                    .iter()
                    .filter(|product| skus.contains(&product.sku))
                    .cloned()
                    .collect()),
            }
        }
    }

    struct Carts {
        carts: Mutex<Vec<Cart>>,
        fail: Option<RepositoryError>,
    }

    #[async_trait]
    impl CartRepository for Carts {
        async fn create(&self) -> Result<Cart, RepositoryError> {
            if let Some(error) = &self.fail {
                return Err(error.clone());
            }
            let cart = Cart {
                id: CartId::parse(CART_ID).unwrap(),
                status: CartStatus::Open,
                lines: Vec::new(),
            };
            self.carts.lock().unwrap().push(cart.clone());
            Ok(cart)
        }
        async fn find(&self, cart_id: &CartId) -> Result<Option<Cart>, RepositoryError> {
            if let Some(error) = &self.fail {
                return Err(error.clone());
            }
            Ok(self
                .carts
                .lock()
                .unwrap()
                .iter()
                .find(|cart| &cart.id == cart_id)
                .cloned())
        }
        async fn save_line(
            &self,
            cart_id: &CartId,
            line: &CartLine,
        ) -> Result<(), RepositoryError> {
            if let Some(error) = &self.fail {
                return Err(error.clone());
            }
            let mut carts = self.carts.lock().unwrap();
            let cart = carts.iter_mut().find(|cart| &cart.id == cart_id).unwrap();
            cart.lines.retain(|existing| existing.sku != line.sku);
            cart.lines.push(line.clone());
            Ok(())
        }
    }

    struct Orders {
        fail: Option<RepositoryError>,
    }

    #[async_trait]
    impl OrderRepository for Orders {
        async fn place(&self, draft: &OrderDraft) -> Result<Order, RepositoryError> {
            if let Some(error) = &self.fail {
                return Err(error.clone());
            }
            Ok(Order {
                id: 1,
                cart_id: draft.cart_id.clone(),
                lines: draft.lines.clone(),
                total: draft.total,
            })
        }
    }

    fn sku(raw: &str) -> Sku {
        Sku::parse(raw).unwrap()
    }

    fn product(raw: &str, price: i64, stock: u32) -> Product {
        Product {
            sku: sku(raw),
            name: raw.to_owned(),
            price: Money::from_cents(price).unwrap(),
            stock,
        }
    }

    fn state(
        products: Vec<Product>,
        carts: Vec<Cart>,
        product_fail: Option<RepositoryError>,
        cart_fail: Option<RepositoryError>,
        order_fail: Option<RepositoryError>,
    ) -> AppState {
        AppState {
            catalog: Arc::new(CatalogService::new(Arc::new(Products {
                fail: product_fail,
                products: products.clone(),
            }))),
            carts: Arc::new(CartService::new(
                Arc::new(Products {
                    fail: None,
                    products,
                }),
                Arc::new(Carts {
                    carts: Mutex::new(carts),
                    fail: cart_fail,
                }),
                Arc::new(Orders { fail: order_fail }),
            )),
        }
    }

    fn open_cart_model() -> Cart {
        Cart {
            id: CartId::parse(CART_ID).unwrap(),
            status: CartStatus::Open,
            lines: Vec::new(),
        }
    }

    async fn send(
        state: AppState,
        method: &str,
        path: &str,
        body: Option<&str>,
    ) -> (StatusCode, String) {
        let mut builder = Request::builder().method(method).uri(path);
        if body.is_some() {
            builder = builder.header("content-type", "application/json");
        }
        let request = builder
            .body(Body::from(body.unwrap_or("").to_owned()))
            .unwrap();
        let response = router(state).oneshot(request).await.unwrap();
        let status = response.status();
        let bytes = response.into_body().collect().await.unwrap().to_bytes();
        (status, String::from_utf8(bytes.to_vec()).unwrap())
    }

    #[test]
    fn every_domain_error_has_an_http_status() {
        let cases = [
            (
                DomainError::InvalidSku,
                StatusCode::UNPROCESSABLE_ENTITY,
                "invalid_sku",
            ),
            (
                DomainError::InvalidQuantity,
                StatusCode::UNPROCESSABLE_ENTITY,
                "invalid_quantity",
            ),
            (
                DomainError::InvalidMoney,
                StatusCode::UNPROCESSABLE_ENTITY,
                "invalid_money",
            ),
            (
                DomainError::InvalidCartId,
                StatusCode::UNPROCESSABLE_ENTITY,
                "invalid_cart_id",
            ),
            (
                DomainError::AmountOverflow,
                StatusCode::UNPROCESSABLE_ENTITY,
                "amount_overflow",
            ),
            (
                DomainError::EmptyCart,
                StatusCode::UNPROCESSABLE_ENTITY,
                "empty_cart",
            ),
            (
                DomainError::ProductNotFound(sku("MUG-STONE")),
                StatusCode::NOT_FOUND,
                "product_not_found",
            ),
            (
                DomainError::CartNotFound(CartId::parse(CART_ID).unwrap()),
                StatusCode::NOT_FOUND,
                "cart_not_found",
            ),
            (DomainError::CartClosed, StatusCode::CONFLICT, "cart_closed"),
            (
                DomainError::OutOfStock(sku("LAMP-DESK")),
                StatusCode::CONFLICT,
                "out_of_stock",
            ),
        ];
        for (error, status, code) in cases {
            let api = ApiError::from(error);
            assert_eq!(api.status, status);
            assert_eq!(api.code, code);
            let response = api.into_response();
            assert_eq!(response.status(), status);
        }
        let unavailable = ApiError::from(ShopError::Unavailable("connect".to_owned()));
        assert_eq!(unavailable.status, StatusCode::INTERNAL_SERVER_ERROR);
        assert_eq!(unavailable.code, "storage_unavailable");
    }

    #[tokio::test]
    async fn health_and_openapi_respond() {
        let app = state(
            vec![product("MUG-STONE", 1800, 10)],
            vec![],
            None,
            None,
            None,
        );
        let (status, _) = send(app.clone(), "GET", "/health", None).await;
        assert_eq!(status, StatusCode::OK);
        let (status, body) = send(app, "GET", "/openapi.json", None).await;
        assert_eq!(status, StatusCode::OK);
        assert!(body.contains("shop-rs-api"));
    }

    #[tokio::test]
    async fn catalog_lists_and_finds_products() {
        let app = state(
            vec![product("MUG-STONE", 1800, 10)],
            vec![],
            None,
            None,
            None,
        );
        let (status, body) = send(app.clone(), "GET", "/v1/products", None).await;
        assert_eq!(status, StatusCode::OK);
        assert!(body.contains("MUG-STONE"));
        let (status, body) = send(app.clone(), "GET", "/v1/products/MUG-STONE", None).await;
        assert_eq!(status, StatusCode::OK);
        assert!(body.contains("1800"));
        let (status, body) = send(app.clone(), "GET", "/v1/products/no", None).await;
        assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY);
        assert!(body.contains("invalid_sku"));
        let (status, body) = send(app, "GET", "/v1/products/NOPE-1", None).await;
        assert_eq!(status, StatusCode::NOT_FOUND);
        assert!(body.contains("product_not_found"));
    }

    #[tokio::test]
    async fn catalog_reports_storage_failure() {
        let app = state(
            vec![],
            vec![],
            Some(RepositoryError::Unavailable("connect".to_owned())),
            None,
            None,
        );
        let (status, body) = send(app, "GET", "/v1/products", None).await;
        assert_eq!(status, StatusCode::INTERNAL_SERVER_ERROR);
        assert!(body.contains("storage_unavailable"));
    }

    #[tokio::test]
    async fn cart_opens_adds_and_checks_out() {
        let app = state(
            vec![product("MUG-STONE", 1800, 10)],
            vec![],
            None,
            None,
            None,
        );
        let (status, body) = send(app.clone(), "POST", "/v1/carts", None).await;
        assert_eq!(status, StatusCode::CREATED);
        assert!(body.contains(CART_ID));
        let (status, body) = send(
            app.clone(),
            "POST",
            &format!("/v1/carts/{CART_ID}/items"),
            Some(r#"{"sku":"MUG-STONE","quantity":2}"#),
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        assert!(body.contains("MUG-STONE"));
        let (status, body) = send(
            app.clone(),
            "POST",
            &format!("/v1/carts/{CART_ID}/items"),
            Some(r#"{"sku":"MUG-STONE","quantity":1}"#),
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        assert!(body.contains("\"quantity\":3"));
        let (status, body) = send(app.clone(), "GET", &format!("/v1/carts/{CART_ID}"), None).await;
        assert_eq!(status, StatusCode::OK);
        assert!(body.contains("open"));
        let (status, body) =
            send(app, "POST", &format!("/v1/carts/{CART_ID}/checkout"), None).await;
        assert_eq!(status, StatusCode::CREATED);
        assert!(body.contains("5400"));
    }

    #[tokio::test]
    async fn cart_rejects_bad_input_and_conflicts() {
        let closed = Cart {
            id: CartId::parse(CART_ID).unwrap(),
            status: CartStatus::CheckedOut,
            lines: vec![CartLine {
                sku: sku("MUG-STONE"),
                quantity: Quantity::new(1).unwrap(),
            }],
        };
        let app = state(
            vec![
                product("MUG-STONE", 1800, 10),
                product("LAMP-DESK", 12900, 1),
            ],
            vec![closed.clone()],
            None,
            None,
            None,
        );
        let (status, _) = send(app.clone(), "GET", "/v1/carts/not-a-uuid", None).await;
        assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY);
        let (status, _) = send(
            app.clone(),
            "GET",
            "/v1/carts/0b8f6c1e-4a2d-4e1f-9c3b-2d5e7f8a9b0d",
            None,
        )
        .await;
        assert_eq!(status, StatusCode::NOT_FOUND);
        let (status, body) = send(app.clone(), "GET", &format!("/v1/carts/{CART_ID}"), None).await;
        assert_eq!(status, StatusCode::OK);
        assert!(body.contains("checked_out"));
        let (status, _) = send(
            app.clone(),
            "POST",
            &format!("/v1/carts/{CART_ID}/items"),
            Some("not-json"),
        )
        .await;
        assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY);
        let (status, body) = send(
            app.clone(),
            "POST",
            &format!("/v1/carts/{CART_ID}/items"),
            Some(r#"{"sku":"NO","quantity":1}"#),
        )
        .await;
        assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY);
        assert!(body.contains("invalid_sku"));
        let (status, body) = send(
            app.clone(),
            "POST",
            &format!("/v1/carts/{CART_ID}/items"),
            Some(r#"{"sku":"MUG-STONE","quantity":0}"#),
        )
        .await;
        assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY);
        assert!(body.contains("invalid_quantity"));
        let (status, _) = send(
            app.clone(),
            "POST",
            "/v1/carts/0b8f6c1e-4a2d-4e1f-9c3b-2d5e7f8a9b0d/checkout",
            None,
        )
        .await;
        assert_eq!(status, StatusCode::NOT_FOUND);
        let (status, body) =
            send(app, "POST", &format!("/v1/carts/{CART_ID}/checkout"), None).await;
        assert_eq!(status, StatusCode::CONFLICT);
        assert!(body.contains("cart_closed"));
    }

    #[tokio::test]
    async fn checkout_reports_an_empty_cart_stock_conflict_and_overflow() {
        let empty = open_cart_model();
        let app = state(
            vec![
                product("MUG-STONE", 1800, 10),
                product("LAMP-DESK", i64::MAX, 10),
            ],
            vec![empty],
            None,
            None,
            None,
        );
        let (status, body) = send(
            app.clone(),
            "POST",
            &format!("/v1/carts/{CART_ID}/checkout"),
            None,
        )
        .await;
        assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY);
        assert!(body.contains("empty_cart"));
        let (status, _) = send(
            app.clone(),
            "POST",
            &format!("/v1/carts/{CART_ID}/items"),
            Some(r#"{"sku":"LAMP-DESK","quantity":2}"#),
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        let (status, body) = send(
            app.clone(),
            "POST",
            &format!("/v1/carts/{CART_ID}/checkout"),
            None,
        )
        .await;
        assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY);
        assert!(body.contains("amount_overflow"));
        let stock = state(
            vec![product("LAMP-DESK", 12900, 1)],
            vec![open_cart_model()],
            None,
            None,
            None,
        );
        let (status, _) = send(
            stock.clone(),
            "POST",
            &format!("/v1/carts/{CART_ID}/items"),
            Some(r#"{"sku":"LAMP-DESK","quantity":3}"#),
        )
        .await;
        assert_eq!(status, StatusCode::CONFLICT);
        let missing = state(
            vec![product("MUG-STONE", 1800, 10)],
            vec![],
            None,
            None,
            None,
        );
        let (status, _) = send(
            missing,
            "POST",
            &format!("/v1/carts/{CART_ID}/items"),
            Some(r#"{"sku":"MUG-STONE","quantity":1}"#),
        )
        .await;
        assert_eq!(status, StatusCode::NOT_FOUND);
    }

    #[tokio::test]
    async fn cart_storage_failure_is_a_server_error() {
        let app = state(
            vec![product("MUG-STONE", 1800, 10)],
            vec![],
            None,
            Some(RepositoryError::Unavailable("connect".to_owned())),
            None,
        );
        let (status, body) = send(app, "POST", "/v1/carts", None).await;
        assert_eq!(status, StatusCode::INTERNAL_SERVER_ERROR);
        assert!(body.contains("storage_unavailable"));
    }
}
