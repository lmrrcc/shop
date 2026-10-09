use std::fmt;

use async_trait::async_trait;

use super::model::{Cart, CartId, CartLine, DomainError, Order, OrderDraft, Product, Sku};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RepositoryError {
    Rejected(DomainError),
    Unavailable(String),
}

impl fmt::Display for RepositoryError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Rejected(error) => write!(formatter, "{error}"),
            Self::Unavailable(reason) => write!(formatter, "storage unavailable: {reason}"),
        }
    }
}

impl std::error::Error for RepositoryError {}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::domain::model::DomainError;

    #[test]
    fn display_includes_the_domain_message_or_the_storage_reason() {
        let rejected = RepositoryError::Rejected(DomainError::EmptyCart);
        let unavailable = RepositoryError::Unavailable("connect".to_owned());
        assert_eq!(rejected.to_string(), DomainError::EmptyCart.to_string());
        assert_eq!(unavailable.to_string(), "storage unavailable: connect");
        let as_error: &dyn std::error::Error = &rejected;
        assert!(!as_error.to_string().is_empty());
    }
}

#[async_trait]
pub trait ProductRepository: Send + Sync {
    async fn list(&self) -> Result<Vec<Product>, RepositoryError>;
    async fn find_by_sku(&self, sku: &Sku) -> Result<Option<Product>, RepositoryError>;
    async fn find_by_skus(&self, skus: &[Sku]) -> Result<Vec<Product>, RepositoryError>;
}

#[async_trait]
pub trait CartRepository: Send + Sync {
    async fn create(&self) -> Result<Cart, RepositoryError>;
    async fn find(&self, cart_id: &CartId) -> Result<Option<Cart>, RepositoryError>;
    async fn save_line(&self, cart_id: &CartId, line: &CartLine) -> Result<(), RepositoryError>;
}

#[async_trait]
pub trait OrderRepository: Send + Sync {
    async fn place(&self, draft: &OrderDraft) -> Result<Order, RepositoryError>;
}
