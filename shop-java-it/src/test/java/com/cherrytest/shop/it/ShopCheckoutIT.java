package com.cherrytest.shop.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.cherrytest.junit.CherryTest;
import com.cherrytest.junit.HttpRecord;
import com.cherrytest.junit.TestEnv;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;

class ShopCheckoutIT {
    private static String openCart(TestEnv env) {
        HttpRecord response = env.project("shop").service("api").post("/v1/carts").send();
        response.assertStatus(201);
        return response.responseBodyJson().get("id").stringValue();
    }

    private static HttpRecord addItem(TestEnv env, String cartId, String sku, long quantity) {
        return env.project("shop")
                .service("api")
                .post("/v1/carts/" + cartId + "/items")
                .jsonBody(new TreeMap<>(Map.of("sku", sku, "quantity", quantity)))
                .send();
    }

    private static long stockOf(TestEnv env, String sku) {
        HttpRecord response = env.project("shop").service("api").get("/v1/products/" + sku).send();
        response.assertStatus(200);
        return response.responseBodyJson().get("stock").longValue();
    }

    private static String errorCode(HttpRecord response) {
        return response.responseBodyJson().get("error").get("code").stringValue();
    }

    @CherryTest(scope = "shop_checkout")
    void shop_project_catalog_suite_lists_seeded_products_test(TestEnv env) {
        HttpRecord response = env.project("shop").service("api").get("/v1/products").send();
        response.assertStatus(200);
        JsonNode body = response.responseBodyJson();
        assertThat(body.get("total").intValue()).isEqualTo(4);
        assertThat(body.get("items").get(0).get("sku").stringValue()).isEqualTo("LAMP-DESK");
        assertThat(body.get("items").get(0).get("price_cents").longValue()).isEqualTo(12900);
        assertThat(body.get("items").get(0).get("discount_percent").intValue()).isZero();
        assertThat(body.get("items").get(1).get("sku").stringValue()).isEqualTo("MUG-STONE");
        assertThat(body.get("items").get(1).get("discount_percent").intValue()).isEqualTo(10);
    }

    @CherryTest(scope = "shop_checkout")
    void shop_project_catalog_suite_unknown_sku_not_found_test(TestEnv env) {
        HttpRecord response = env.project("shop").service("api").get("/v1/products/NOPE-0000").send();
        response.assertStatus(404);
        assertThat(errorCode(response)).isEqualTo("product_not_found");
    }

    @CherryTest(scope = "shop_checkout")
    void shop_project_cart_suite_rejects_zero_quantity_test(TestEnv env) {
        HttpRecord response = addItem(env, openCart(env), "MUG-STONE", 0);
        response.assertStatus(422);
        assertThat(errorCode(response)).isEqualTo("invalid_quantity");
    }

    @CherryTest(scope = "shop_checkout")
    void shop_project_cart_suite_rejects_quantity_beyond_stock_test(TestEnv env) {
        HttpRecord response = addItem(env, openCart(env), "LAMP-DESK", 3);
        response.assertStatus(409);
        assertThat(errorCode(response)).isEqualTo("out_of_stock");
    }

    @CherryTest(scope = "shop_checkout")
    void shop_project_cart_suite_adds_line_to_cart_test(TestEnv env) {
        String cartId = openCart(env);
        addItem(env, cartId, "MUG-STONE", 2).assertStatus(200);
        HttpRecord cart = env.project("shop").service("api").get("/v1/carts/" + cartId).send();
        cart.assertStatus(200);
        JsonNode body = cart.responseBodyJson();
        assertThat(body.get("status").stringValue()).isEqualTo("open");
        assertThat(body.get("lines").get(0).get("sku").stringValue()).isEqualTo("MUG-STONE");
        assertThat(body.get("lines").get(0).get("quantity").intValue()).isEqualTo(2);
    }

    @CherryTest(scope = "shop_checkout")
    void shop_project_checkout_suite_places_order_and_decrements_stock_test(TestEnv env) {
        long stockBefore = stockOf(env, "NB-DOT-A5");
        String cartId = openCart(env);
        addItem(env, cartId, "NB-DOT-A5", 3).assertStatus(200);
        HttpRecord response =
                env.project("shop").service("api").post("/v1/carts/" + cartId + "/checkout").send();
        response.assertStatus(201);
        JsonNode order = response.responseBodyJson();
        assertThat(order.get("total_cents").longValue()).isEqualTo(4200);
        assertThat(order.get("cart_id").stringValue()).isEqualTo(cartId);
        assertThat(stockOf(env, "NB-DOT-A5")).isEqualTo(stockBefore - 3);
        HttpRecord cart = env.project("shop").service("api").get("/v1/carts/" + cartId).send();
        cart.assertStatus(200);
        assertThat(cart.responseBodyJson().get("status").stringValue()).isEqualTo("checked_out");
    }

    @CherryTest(scope = "shop_checkout")
    void shop_project_checkout_suite_applies_the_mug_discount_test(TestEnv env) {
        String cartId = openCart(env);
        addItem(env, cartId, "MUG-STONE", 2).assertStatus(200);
        HttpRecord response =
                env.project("shop").service("api").post("/v1/carts/" + cartId + "/checkout").send();
        response.assertStatus(201);
        JsonNode order = response.responseBodyJson();
        assertThat(order.get("lines").get(0).get("unit_price_cents").longValue()).isEqualTo(1620);
        assertThat(order.get("total_cents").longValue()).isEqualTo(3240);
    }

    @CherryTest(scope = "shop_checkout")
    void shop_project_catalog_suite_rejects_invalid_sku_test(TestEnv env) {
        HttpRecord response = env.project("shop").service("api").get("/v1/products/no").send();
        response.assertStatus(422);
        assertThat(errorCode(response)).isEqualTo("invalid_sku");
    }

    @CherryTest(scope = "shop_checkout")
    void shop_project_catalog_suite_serves_openapi_test(TestEnv env) {
        HttpRecord response = env.project("shop").service("api").get("/openapi.json").send();
        response.assertStatus(200);
        assertThat(response.responseBodyText()).contains("shop-java-api");
    }

    @CherryTest(scope = "shop_checkout")
    void shop_project_cart_suite_unknown_cart_not_found_test(TestEnv env) {
        HttpRecord response = env.project("shop")
                .service("api")
                .get("/v1/carts/0b8f6c1e-4a2d-4e1f-9c3b-2d5e7f8a9b0d")
                .send();
        response.assertStatus(404);
        assertThat(errorCode(response)).isEqualTo("cart_not_found");
    }

    @CherryTest(scope = "shop_checkout")
    void shop_project_checkout_suite_rejects_empty_cart_test(TestEnv env) {
        String cartId = openCart(env);
        HttpRecord response =
                env.project("shop").service("api").post("/v1/carts/" + cartId + "/checkout").send();
        response.assertStatus(422);
        assertThat(errorCode(response)).isEqualTo("empty_cart");
    }

    @CherryTest(scope = "shop_checkout")
    void shop_project_checkout_suite_rejects_second_checkout_test(TestEnv env) {
        String cartId = openCart(env);
        addItem(env, cartId, "TEE-CREW-M", 1).assertStatus(200);
        String checkoutPath = "/v1/carts/" + cartId + "/checkout";
        env.project("shop").service("api").post(checkoutPath).send().assertStatus(201);
        HttpRecord response = env.project("shop").service("api").post(checkoutPath).send();
        response.assertStatus(409);
        assertThat(errorCode(response)).isEqualTo("cart_closed");
    }
}
