use cherry_test_core::prelude::*;

async fn open_cart(env: &TestEnv) -> anyhow::Result<String> {
    let response = env
        .project("shop")
        .service("api")?
        .post("/v1/carts")
        .send()
        .await;
    response.assert_status(201);
    let body = response.response_body_json()?;
    let cart_id = body["id"]
        .as_str()
        .ok_or_else(|| anyhow::anyhow!("cart id missing"))?;
    Ok(cart_id.to_owned())
}

async fn add_item(
    env: &TestEnv,
    cart_id: &str,
    sku: &str,
    quantity: i64,
) -> anyhow::Result<HttpRecord> {
    let body = serde_json::json!({ "sku": sku, "quantity": quantity });
    Ok(env
        .project("shop")
        .service("api")?
        .post(&format!("/v1/carts/{cart_id}/items"))
        .json_body(&body)?
        .send()
        .await)
}

async fn stock_of(env: &TestEnv, sku: &str) -> anyhow::Result<i64> {
    let response = env
        .project("shop")
        .service("api")?
        .get(&format!("/v1/products/{sku}"))
        .send()
        .await;
    response.assert_status(200);
    response.response_body_json()?["stock"]
        .as_i64()
        .ok_or_else(|| anyhow::anyhow!("stock missing for {sku}"))
}

#[cherry_test(scope = "shop_checkout")]
async fn shop_project_catalog_suite_lists_seeded_products_test(env: TestEnv) {
    let response = env
        .project("shop")
        .service("api")?
        .get("/v1/products")
        .send()
        .await;
    response.assert_status(200);
    let body = response.response_body_json()?;
    assert_eq!(body["total"], 4);
    assert_eq!(body["items"][0]["sku"], "LAMP-DESK");
    assert_eq!(body["items"][0]["price_cents"], 12900);
    assert_eq!(body["items"][0]["discount_percent"], 0);
    assert_eq!(body["items"][1]["sku"], "MUG-STONE");
    assert_eq!(body["items"][1]["discount_percent"], 10);
}

#[cherry_test(scope = "shop_checkout")]
async fn shop_project_catalog_suite_unknown_sku_not_found_test(env: TestEnv) {
    let response = env
        .project("shop")
        .service("api")?
        .get("/v1/products/NOPE-0000")
        .send()
        .await;
    response.assert_status(404);
    assert_eq!(
        response.response_body_json()?["error"]["code"],
        "product_not_found"
    );
}

#[cherry_test(scope = "shop_checkout")]
async fn shop_project_cart_suite_rejects_zero_quantity_test(env: TestEnv) {
    let cart_id = open_cart(&env).await?;
    let response = add_item(&env, &cart_id, "MUG-STONE", 0).await?;
    response.assert_status(422);
    assert_eq!(
        response.response_body_json()?["error"]["code"],
        "invalid_quantity"
    );
}

#[cherry_test(scope = "shop_checkout")]
async fn shop_project_cart_suite_rejects_quantity_beyond_stock_test(env: TestEnv) {
    let cart_id = open_cart(&env).await?;
    let response = add_item(&env, &cart_id, "LAMP-DESK", 3).await?;
    response.assert_status(409);
    assert_eq!(
        response.response_body_json()?["error"]["code"],
        "out_of_stock"
    );
}

#[cherry_test(scope = "shop_checkout")]
async fn shop_project_cart_suite_adds_line_to_cart_test(env: TestEnv) {
    let cart_id = open_cart(&env).await?;
    let response = add_item(&env, &cart_id, "MUG-STONE", 2).await?;
    response.assert_status(200);
    let cart = env
        .project("shop")
        .service("api")?
        .get(&format!("/v1/carts/{cart_id}"))
        .send()
        .await;
    cart.assert_status(200);
    let body = cart.response_body_json()?;
    assert_eq!(body["status"], "open");
    assert_eq!(body["lines"][0]["sku"], "MUG-STONE");
    assert_eq!(body["lines"][0]["quantity"], 2);
}

#[cherry_test(scope = "shop_checkout")]
async fn shop_project_checkout_suite_places_order_and_decrements_stock_test(env: TestEnv) {
    let stock_before = stock_of(&env, "NB-DOT-A5").await?;
    let cart_id = open_cart(&env).await?;
    add_item(&env, &cart_id, "NB-DOT-A5", 3)
        .await?
        .assert_status(200);
    let response = env
        .project("shop")
        .service("api")?
        .post(&format!("/v1/carts/{cart_id}/checkout"))
        .send()
        .await;
    response.assert_status(201);
    let order = response.response_body_json()?;
    assert_eq!(order["total_cents"], 4200);
    assert_eq!(order["cart_id"], cart_id.as_str());
    assert_eq!(stock_of(&env, "NB-DOT-A5").await?, stock_before - 3);
    let cart = env
        .project("shop")
        .service("api")?
        .get(&format!("/v1/carts/{cart_id}"))
        .send()
        .await;
    cart.assert_status(200);
    assert_eq!(cart.response_body_json()?["status"], "checked_out");
}

#[cherry_test(scope = "shop_checkout")]
async fn shop_project_checkout_suite_applies_the_mug_discount_test(env: TestEnv) {
    let cart_id = open_cart(&env).await?;
    add_item(&env, &cart_id, "MUG-STONE", 2)
        .await?
        .assert_status(200);
    let response = env
        .project("shop")
        .service("api")?
        .post(&format!("/v1/carts/{cart_id}/checkout"))
        .send()
        .await;
    response.assert_status(201);
    let order = response.response_body_json()?;
    assert_eq!(order["lines"][0]["unit_price_cents"], 1620);
    assert_eq!(order["total_cents"], 3240);
}

#[cherry_test(scope = "shop_checkout")]
async fn shop_project_catalog_suite_rejects_invalid_sku_test(env: TestEnv) {
    let response = env
        .project("shop")
        .service("api")?
        .get("/v1/products/no")
        .send()
        .await;
    response.assert_status(422);
    assert_eq!(
        response.response_body_json()?["error"]["code"],
        "invalid_sku"
    );
}

#[cherry_test(scope = "shop_checkout")]
async fn shop_project_catalog_suite_serves_openapi_test(env: TestEnv) {
    let response = env
        .project("shop")
        .service("api")?
        .get("/openapi.json")
        .send()
        .await;
    response.assert_status(200);
    assert!(
        response
            .response_body_text()
            .contains("\"title\":\"shop-rs-api\"")
            || response.response_body_text().contains("shop-rs-api")
    );
}

#[cherry_test(scope = "shop_checkout")]
async fn shop_project_cart_suite_unknown_cart_not_found_test(env: TestEnv) {
    let response = env
        .project("shop")
        .service("api")?
        .get("/v1/carts/0b8f6c1e-4a2d-4e1f-9c3b-2d5e7f8a9b0d")
        .send()
        .await;
    response.assert_status(404);
    assert_eq!(
        response.response_body_json()?["error"]["code"],
        "cart_not_found"
    );
}

#[cherry_test(scope = "shop_checkout")]
async fn shop_project_checkout_suite_rejects_empty_cart_test(env: TestEnv) {
    let cart_id = open_cart(&env).await?;
    let response = env
        .project("shop")
        .service("api")?
        .post(&format!("/v1/carts/{cart_id}/checkout"))
        .send()
        .await;
    response.assert_status(422);
    assert_eq!(
        response.response_body_json()?["error"]["code"],
        "empty_cart"
    );
}

#[cherry_test(scope = "shop_checkout")]
async fn shop_project_checkout_suite_rejects_second_checkout_test(env: TestEnv) {
    let cart_id = open_cart(&env).await?;
    add_item(&env, &cart_id, "TEE-CREW-M", 1)
        .await?
        .assert_status(200);
    let checkout_path = format!("/v1/carts/{cart_id}/checkout");
    env.project("shop")
        .service("api")?
        .post(&checkout_path)
        .send()
        .await
        .assert_status(201);
    let response = env
        .project("shop")
        .service("api")?
        .post(&checkout_path)
        .send()
        .await;
    response.assert_status(409);
    assert_eq!(
        response.response_body_json()?["error"]["code"],
        "cart_closed"
    );
}
