use async_trait::async_trait;
use sqlx::postgres::{PgPool, PgPoolOptions, PgRow};
use sqlx::Row;

use crate::domain::model::{
    Cart, CartId, CartLine, CartStatus, DomainError, Money, Order, OrderDraft, Product, Quantity,
    Sku,
};
use crate::domain::port::{CartRepository, OrderRepository, ProductRepository, RepositoryError};

const MAXIMUM_POOL_CONNECTIONS: u32 = 5;
const CART_STATUS_OPEN: &str = "open";
const CART_STATUS_CHECKED_OUT: &str = "checked_out";

pub async fn connect(database_url: &str) -> Result<PgPool, RepositoryError> {
    let pool = checked(
        PgPoolOptions::new()
            .max_connections(MAXIMUM_POOL_CONNECTIONS)
            .connect(database_url)
            .await,
        "connect",
    )?;
    checked(sqlx::migrate!("./migrations").run(&pool).await, "migrate")?;
    Ok(pool)
}

fn unavailable(operation: &'static str, error: &dyn std::error::Error) -> RepositoryError {
    tracing::error!(upstream = "postgres", operation, error = %error, "storage operation failed");
    RepositoryError::Unavailable(operation.to_owned())
}

fn checked<T, E>(result: Result<T, E>, operation: &'static str) -> Result<T, RepositoryError>
where
    E: std::error::Error,
{
    result.map_err(|error| unavailable(operation, &error))
}

fn product_from_row(row: &PgRow) -> Result<Product, RepositoryError> {
    let sku: String = checked(row.try_get("sku"), "decode_product")?;
    let name: String = checked(row.try_get("name"), "decode_product")?;
    let price_cents: i64 = checked(row.try_get("price_cents"), "decode_product")?;
    let stock: i32 = checked(row.try_get("stock"), "decode_product")?;
    product_from_parts(&sku, name, price_cents, stock)
}

fn product_from_parts(
    sku: &str,
    name: String,
    price_cents: i64,
    stock: i32,
) -> Result<Product, RepositoryError> {
    Ok(Product {
        sku: Sku::parse(sku).map_err(RepositoryError::Rejected)?,
        name,
        price: Money::from_cents(price_cents).map_err(RepositoryError::Rejected)?,
        stock: u32::try_from(stock)
            .map_err(|_| RepositoryError::Rejected(DomainError::InvalidQuantity))?,
    })
}

pub struct PostgresProductRepository {
    pool: PgPool,
}

impl PostgresProductRepository {
    pub fn new(pool: PgPool) -> Self {
        Self { pool }
    }
}

#[async_trait]
impl ProductRepository for PostgresProductRepository {
    async fn list(&self) -> Result<Vec<Product>, RepositoryError> {
        let rows = checked(
            sqlx::query(r#"SELECT SKU, NAME, PRICE_CENTS, STOCK FROM product ORDER BY SKU"#)
                .fetch_all(&self.pool)
                .await,
            "list_products",
        )?;
        rows.iter().map(product_from_row).collect()
    }
    async fn find_by_sku(&self, sku: &Sku) -> Result<Option<Product>, RepositoryError> {
        let row = checked(
            sqlx::query(r#"SELECT SKU, NAME, PRICE_CENTS, STOCK FROM product WHERE SKU = $1"#)
                .bind(sku.as_str())
                .fetch_optional(&self.pool)
                .await,
            "find_product",
        )?;
        row.as_ref().map(product_from_row).transpose()
    }
    async fn find_by_skus(&self, skus: &[Sku]) -> Result<Vec<Product>, RepositoryError> {
        let values: Vec<String> = skus.iter().map(|sku| sku.as_str().to_owned()).collect();
        let rows = checked(
            sqlx::query(r#"SELECT SKU, NAME, PRICE_CENTS, STOCK FROM product WHERE SKU = ANY($1)"#)
                .bind(&values)
                .fetch_all(&self.pool)
                .await,
            "find_products",
        )?;
        rows.iter().map(product_from_row).collect()
    }
}

pub struct PostgresCartRepository {
    pool: PgPool,
}

impl PostgresCartRepository {
    pub fn new(pool: PgPool) -> Self {
        Self { pool }
    }
}

fn cart_status(raw: &str) -> Result<CartStatus, RepositoryError> {
    match raw {
        CART_STATUS_OPEN => Ok(CartStatus::Open),
        CART_STATUS_CHECKED_OUT => Ok(CartStatus::CheckedOut),
        other => {
            tracing::error!(upstream = "postgres", status = other, "unknown cart status");
            Err(RepositoryError::Unavailable("decode_cart".to_owned()))
        }
    }
}

fn cart_line_from_row(row: &PgRow) -> Result<CartLine, RepositoryError> {
    let sku: String = checked(row.try_get("sku"), "decode_cart_line")?;
    let quantity: i32 = checked(row.try_get("quantity"), "decode_cart_line")?;
    cart_line_from_parts(&sku, quantity)
}

fn cart_line_from_parts(sku: &str, quantity: i32) -> Result<CartLine, RepositoryError> {
    Ok(CartLine {
        sku: Sku::parse(sku).map_err(RepositoryError::Rejected)?,
        quantity: Quantity::new(i64::from(quantity)).map_err(RepositoryError::Rejected)?,
    })
}

fn quantity_column(quantity: u32) -> Result<i32, RepositoryError> {
    i32::try_from(quantity).map_err(|_| RepositoryError::Rejected(DomainError::InvalidQuantity))
}

fn close_outcome(rows_affected: u64) -> Result<(), RepositoryError> {
    if rows_affected == 0 {
        Err(RepositoryError::Rejected(DomainError::CartClosed))
    } else {
        Ok(())
    }
}

fn reserve_outcome(rows_affected: u64, sku: &Sku) -> Result<(), RepositoryError> {
    if rows_affected == 0 {
        Err(RepositoryError::Rejected(DomainError::OutOfStock(
            sku.clone(),
        )))
    } else {
        Ok(())
    }
}

#[async_trait]
impl CartRepository for PostgresCartRepository {
    async fn create(&self) -> Result<Cart, RepositoryError> {
        let row = checked(
            sqlx::query(r#"INSERT INTO cart (STATUS) VALUES ($1) RETURNING ID::text AS id"#)
                .bind(CART_STATUS_OPEN)
                .fetch_one(&self.pool)
                .await,
            "create_cart",
        )?;
        let id: String = checked(row.try_get("id"), "decode_cart")?;
        Ok(Cart {
            id: CartId::parse(&id).map_err(RepositoryError::Rejected)?,
            status: CartStatus::Open,
            lines: Vec::new(),
        })
    }
    async fn find(&self, cart_id: &CartId) -> Result<Option<Cart>, RepositoryError> {
        let Some(row) = checked(
            sqlx::query(r#"SELECT STATUS FROM cart WHERE ID = $1::uuid"#)
                .bind(cart_id.as_str())
                .fetch_optional(&self.pool)
                .await,
            "find_cart",
        )?
        else {
            return Ok(None);
        };
        let status: String = checked(row.try_get("status"), "decode_cart")?;
        let line_rows = checked(
            sqlx::query(
                r#"SELECT SKU, QUANTITY FROM cart_line WHERE CART_ID = $1::uuid ORDER BY SKU"#,
            )
            .bind(cart_id.as_str())
            .fetch_all(&self.pool)
            .await,
            "find_cart_lines",
        )?;
        Ok(Some(Cart {
            id: cart_id.clone(),
            status: cart_status(&status)?,
            lines: line_rows
                .iter()
                .map(cart_line_from_row)
                .collect::<Result<_, _>>()?,
        }))
    }
    async fn save_line(&self, cart_id: &CartId, line: &CartLine) -> Result<(), RepositoryError> {
        let quantity = quantity_column(line.quantity.value())?;
        checked(
            sqlx::query(
                r#"INSERT INTO cart_line (CART_ID, SKU, QUANTITY) VALUES ($1::uuid, $2, $3)
                   ON CONFLICT (CART_ID, SKU) DO UPDATE SET QUANTITY = EXCLUDED.QUANTITY"#,
            )
            .bind(cart_id.as_str())
            .bind(line.sku.as_str())
            .bind(quantity)
            .execute(&self.pool)
            .await,
            "save_cart_line",
        )?;
        Ok(())
    }
}

pub struct PostgresOrderRepository {
    pool: PgPool,
}

impl PostgresOrderRepository {
    pub fn new(pool: PgPool) -> Self {
        Self { pool }
    }
}

#[async_trait]
impl OrderRepository for PostgresOrderRepository {
    async fn place(&self, draft: &OrderDraft) -> Result<Order, RepositoryError> {
        let mut transaction = checked(self.pool.begin().await, "begin_order")?;
        let closed = checked(
            sqlx::query(r#"UPDATE cart SET STATUS = $2 WHERE ID = $1::uuid AND STATUS = $3"#)
                .bind(draft.cart_id.as_str())
                .bind(CART_STATUS_CHECKED_OUT)
                .bind(CART_STATUS_OPEN)
                .execute(&mut *transaction)
                .await,
            "close_cart",
        )?;
        close_outcome(closed.rows_affected())?;
        for line in &draft.lines {
            let quantity = quantity_column(line.quantity.value())?;
            let reserved = checked(
                sqlx::query(
                    r#"UPDATE product SET STOCK = STOCK - $2 WHERE SKU = $1 AND STOCK >= $2"#,
                )
                .bind(line.sku.as_str())
                .bind(quantity)
                .execute(&mut *transaction)
                .await,
                "reserve_stock",
            )?;
            reserve_outcome(reserved.rows_affected(), &line.sku)?;
        }
        let row = checked(
            sqlx::query(r#"INSERT INTO customer_order (CART_ID, TOTAL_CENTS) VALUES ($1::uuid, $2) RETURNING ID"#)
                .bind(draft.cart_id.as_str())
                .bind(draft.total.cents())
                .fetch_one(&mut *transaction)
                .await,
            "insert_order",
        )?;
        let order_id: i64 = checked(row.try_get("id"), "decode_order")?;
        for line in &draft.lines {
            let quantity = quantity_column(line.quantity.value())?;
            checked(
                sqlx::query(
                    r#"INSERT INTO customer_order_line (ORDER_ID, SKU, QUANTITY, UNIT_PRICE_CENTS) VALUES ($1, $2, $3, $4)"#,
                )
                .bind(order_id)
                .bind(line.sku.as_str())
                .bind(quantity)
                .bind(line.unit_price.cents())
                .execute(&mut *transaction)
                .await,
                "insert_order_line",
            )?;
        }
        checked(transaction.commit().await, "commit_order")?;
        Ok(Order {
            id: order_id,
            cart_id: draft.cart_id.clone(),
            lines: draft.lines.clone(),
            total: draft.total,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn connect_reports_unavailable_when_the_server_refuses() {
        let error = connect("postgres://shop:shop@127.0.0.1:1/shop")
            .await
            .unwrap_err();
        assert_eq!(error, RepositoryError::Unavailable("connect".to_owned()));
        assert!(error.to_string().contains("connect"));
    }

    #[test]
    fn cart_status_accepts_both_stored_values_and_rejects_anything_else() {
        assert_eq!(cart_status(CART_STATUS_OPEN).unwrap(), CartStatus::Open);
        assert_eq!(
            cart_status(CART_STATUS_CHECKED_OUT).unwrap(),
            CartStatus::CheckedOut
        );
        assert_eq!(
            cart_status("archived").unwrap_err(),
            RepositoryError::Unavailable("decode_cart".to_owned())
        );
    }

    #[test]
    fn product_parts_reject_values_the_schema_also_forbids() {
        assert!(product_from_parts("MUG-STONE", "Mug".to_owned(), 1800, 4).is_ok());
        assert!(matches!(
            product_from_parts("no", "Mug".to_owned(), 1800, 4),
            Err(RepositoryError::Rejected(DomainError::InvalidSku))
        ));
        assert!(matches!(
            product_from_parts("MUG-STONE", "Mug".to_owned(), -1, 4),
            Err(RepositoryError::Rejected(DomainError::InvalidMoney))
        ));
        assert!(matches!(
            product_from_parts("MUG-STONE", "Mug".to_owned(), 1800, -1),
            Err(RepositoryError::Rejected(DomainError::InvalidQuantity))
        ));
    }

    #[test]
    fn cart_line_parts_reject_a_bad_sku_or_quantity() {
        assert_eq!(
            cart_line_from_parts("MUG-STONE", 2)
                .unwrap()
                .quantity
                .value(),
            2
        );
        assert!(matches!(
            cart_line_from_parts("no", 2),
            Err(RepositoryError::Rejected(DomainError::InvalidSku))
        ));
        assert!(matches!(
            cart_line_from_parts("MUG-STONE", 0),
            Err(RepositoryError::Rejected(DomainError::InvalidQuantity))
        ));
    }

    #[test]
    fn quantity_column_rejects_a_value_that_does_not_fit_the_database() {
        assert_eq!(quantity_column(2).unwrap(), 2);
        assert!(matches!(
            quantity_column(u32::MAX),
            Err(RepositoryError::Rejected(DomainError::InvalidQuantity))
        ));
    }

    #[test]
    fn order_outcomes_distinguish_a_closed_cart_and_a_missed_reservation() {
        let sku = Sku::parse("LAMP-DESK").unwrap();
        assert!(close_outcome(1).is_ok());
        assert_eq!(
            close_outcome(0).unwrap_err(),
            RepositoryError::Rejected(DomainError::CartClosed)
        );
        assert!(reserve_outcome(1, &sku).is_ok());
        assert_eq!(
            reserve_outcome(0, &sku).unwrap_err(),
            RepositoryError::Rejected(DomainError::OutOfStock(sku))
        );
    }
}
