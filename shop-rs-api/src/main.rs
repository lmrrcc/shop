use std::sync::Arc;

use shop_rs_api::application::service::{CartService, CatalogService};
use shop_rs_api::infrastructure::postgres::{
    connect, PostgresCartRepository, PostgresOrderRepository, PostgresProductRepository,
};
use shop_rs_api::infrastructure::web::{router, AppState};
use tokio::net::TcpListener;
use tracing::error;
use tracing_subscriber::EnvFilter;

const DEFAULT_ADDRESS: &str = "0.0.0.0:3000";

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::from_default_env())
        .init();
    if let Err(message) = run().await {
        error!(error = %message, "shop-rs-api failed");
        std::process::exit(1);
    }
}

async fn run() -> Result<(), String> {
    let address = std::env::var("SHOP_API_ADDR").unwrap_or_else(|_| DEFAULT_ADDRESS.to_owned());
    let database_url =
        std::env::var("DATABASE_URL").map_err(|_| "DATABASE_URL is not set".to_owned())?;
    let pool = connect(&database_url)
        .await
        .map_err(|error| format!("database: {error:?}"))?;
    let products = Arc::new(PostgresProductRepository::new(pool.clone()));
    let state = AppState {
        catalog: Arc::new(CatalogService::new(products.clone())),
        carts: Arc::new(CartService::new(
            products,
            Arc::new(PostgresCartRepository::new(pool.clone())),
            Arc::new(PostgresOrderRepository::new(pool)),
        )),
    };
    let listener = TcpListener::bind(&address)
        .await
        .map_err(|error| bind_failure(&address, &error))?;
    tracing::info!(%address, "shop-rs-api listening");
    axum::serve(listener, router(state))
        .with_graceful_shutdown(wait_for_shutdown())
        .await
        .map_err(|error| format!("serve: {error}"))
}

fn bind_failure(address: &str, error: &std::io::Error) -> String {
    error!(error = %error, %address, "bind failed");
    format!("bind {address}: {error}")
}

#[rustfmt::skip]
async fn wait_for_shutdown() {
    #[cfg(unix)]
    if let Ok(mut terminate) = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate()) { terminate.recv().await; }
}
