package com.cherrytest.shop.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.cherrytest.shop.application.CartService;
import com.cherrytest.shop.application.CatalogService;
import com.cherrytest.shop.application.Fakes;
import com.cherrytest.shop.application.ShopError;
import com.cherrytest.shop.domain.model.Cart;
import com.cherrytest.shop.domain.model.CartId;
import com.cherrytest.shop.domain.model.CartLine;
import com.cherrytest.shop.domain.model.CartStatus;
import com.cherrytest.shop.domain.model.DomainError;
import com.cherrytest.shop.domain.model.Product;
import com.cherrytest.shop.domain.model.Quantity;
import com.cherrytest.shop.domain.model.Sku;
import com.cherrytest.shop.domain.port.RepositoryException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WebTest {
    private static final String CART_ID = Fakes.CART_ID;

    private record Response(int status, String body) {}

    private static MockMvc app(
            List<Product> products,
            List<Cart> carts,
            @Nullable RepositoryException productFailure,
            @Nullable RepositoryException cartFailure,
            @Nullable RepositoryException orderFailure) {
        CatalogService catalog = new CatalogService(
                productFailure == null
                        ? new Fakes.InMemoryProducts(products)
                        : new Fakes.FailingProducts(productFailure));
        CartService cartService = new CartService(
                new Fakes.InMemoryProducts(products),
                new Fakes.InMemoryCarts(carts, cartFailure),
                new Fakes.RecordingOrders(orderFailure));
        return MockMvcBuilders.standaloneSetup(
                        new HealthController(), new ProductController(catalog), new CartController(cartService))
                .setControllerAdvice(new ApiErrorHandler())
                .build();
    }

    private static Response send(MockMvc app, String method, String path, @Nullable String body) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), path);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        MvcResult result = app.perform(request).andReturn();
        return new Response(
                result.getResponse().getStatus(),
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static Cart openCartModel() {
        return new Cart(CartId.parse(CART_ID), CartStatus.OPEN, List.of());
    }

    @Test
    void every_domain_error_has_an_http_status() {
        Map<DomainError, String> cases = Map.ofEntries(
                Map.entry(new DomainError.InvalidSku(), "422 invalid_sku"),
                Map.entry(new DomainError.InvalidQuantity(), "422 invalid_quantity"),
                Map.entry(new DomainError.InvalidMoney(), "422 invalid_money"),
                Map.entry(new DomainError.InvalidDiscount(), "422 invalid_discount"),
                Map.entry(new DomainError.InvalidCartId(), "422 invalid_cart_id"),
                Map.entry(new DomainError.AmountOverflow(), "422 amount_overflow"),
                Map.entry(new DomainError.EmptyCart(), "422 empty_cart"),
                Map.entry(new DomainError.ProductNotFound(Sku.parse("MUG-STONE")), "404 product_not_found"),
                Map.entry(new DomainError.CartNotFound(CartId.parse(CART_ID)), "404 cart_not_found"),
                Map.entry(new DomainError.CartClosed(), "409 cart_closed"),
                Map.entry(new DomainError.OutOfStock(Sku.parse("LAMP-DESK")), "409 out_of_stock"));
        cases.forEach((error, expected) -> {
            ApiError api = ApiError.from(error);
            assertThat(api.status().value() + " " + api.code()).isEqualTo(expected);
            assertThat(api.response().getStatusCode().value()).isEqualTo(api.status().value());
        });
        ApiError unavailable = ApiError.from(new ShopError.Unavailable("connect"));
        assertThat(unavailable.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(unavailable.code()).isEqualTo("storage_unavailable");
    }

    @Test
    void catalog_lists_and_finds_products() throws Exception {
        MockMvc app = app(List.of(Fakes.product("MUG-STONE", 1800, 10)), List.of(), null, null, null);
        Response list = send(app, "GET", "/v1/products", null);
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.body()).contains("MUG-STONE");
        Response found = send(app, "GET", "/v1/products/MUG-STONE", null);
        assertThat(found.status()).isEqualTo(200);
        assertThat(found.body()).contains("1800");
        Response invalid = send(app, "GET", "/v1/products/no", null);
        assertThat(invalid.status()).isEqualTo(422);
        assertThat(invalid.body()).contains("invalid_sku");
        Response missing = send(app, "GET", "/v1/products/NOPE-1", null);
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.body()).contains("product_not_found");
    }

    @Test
    void catalog_reports_storage_failure() throws Exception {
        MockMvc app = app(List.of(), List.of(), new RepositoryException.Unavailable("connect"), null, null);
        Response response = send(app, "GET", "/v1/products", null);
        assertThat(response.status()).isEqualTo(500);
        assertThat(response.body()).contains("storage_unavailable");
    }

    @Test
    void cart_opens_adds_and_checks_out() throws Exception {
        MockMvc app = app(List.of(Fakes.product("MUG-STONE", 1800, 10)), List.of(), null, null, null);
        Response opened = send(app, "POST", "/v1/carts", null);
        assertThat(opened.status()).isEqualTo(201);
        assertThat(opened.body()).contains(CART_ID);
        Response added = send(app, "POST", "/v1/carts/" + CART_ID + "/items", "{\"sku\":\"MUG-STONE\",\"quantity\":2}");
        assertThat(added.status()).isEqualTo(200);
        assertThat(added.body()).contains("MUG-STONE");
        Response merged = send(app, "POST", "/v1/carts/" + CART_ID + "/items", "{\"sku\":\"MUG-STONE\",\"quantity\":1}");
        assertThat(merged.status()).isEqualTo(200);
        assertThat(merged.body()).contains("\"quantity\":3");
        Response read = send(app, "GET", "/v1/carts/" + CART_ID, null);
        assertThat(read.status()).isEqualTo(200);
        assertThat(read.body()).contains("open");
        Response checkout = send(app, "POST", "/v1/carts/" + CART_ID + "/checkout", null);
        assertThat(checkout.status()).isEqualTo(201);
        assertThat(checkout.body()).contains("5400");
    }

    @Test
    void cart_rejects_bad_input_and_conflicts() throws Exception {
        Cart closed = new Cart(
                CartId.parse(CART_ID),
                CartStatus.CHECKED_OUT,
                List.of(new CartLine(Sku.parse("MUG-STONE"), Quantity.of(1))));
        MockMvc app = app(
                List.of(Fakes.product("MUG-STONE", 1800, 10), Fakes.product("LAMP-DESK", 12900, 1)),
                List.of(closed),
                null,
                null,
                null);
        assertThat(send(app, "GET", "/v1/carts/not-a-uuid", null).status()).isEqualTo(422);
        assertThat(send(app, "GET", "/v1/carts/0b8f6c1e-4a2d-4e1f-9c3b-2d5e7f8a9b0d", null).status())
                .isEqualTo(404);
        Response read = send(app, "GET", "/v1/carts/" + CART_ID, null);
        assertThat(read.status()).isEqualTo(200);
        assertThat(read.body()).contains("checked_out");
        assertThat(send(app, "POST", "/v1/carts/" + CART_ID + "/items", "not-json").status()).isEqualTo(422);
        Response badSku = send(app, "POST", "/v1/carts/" + CART_ID + "/items", "{\"sku\":\"NO\",\"quantity\":1}");
        assertThat(badSku.status()).isEqualTo(422);
        assertThat(badSku.body()).contains("invalid_sku");
        Response zero = send(app, "POST", "/v1/carts/" + CART_ID + "/items", "{\"sku\":\"MUG-STONE\",\"quantity\":0}");
        assertThat(zero.status()).isEqualTo(422);
        assertThat(zero.body()).contains("invalid_quantity");
        assertThat(send(app, "POST", "/v1/carts/0b8f6c1e-4a2d-4e1f-9c3b-2d5e7f8a9b0d/checkout", null).status())
                .isEqualTo(404);
        Response second = send(app, "POST", "/v1/carts/" + CART_ID + "/checkout", null);
        assertThat(second.status()).isEqualTo(409);
        assertThat(second.body()).contains("cart_closed");
    }

    @Test
    void checkout_reports_an_empty_cart_stock_conflict_and_overflow() throws Exception {
        MockMvc app = app(
                List.of(Fakes.product("MUG-STONE", 1800, 10), Fakes.product("LAMP-DESK", Long.MAX_VALUE, 10)),
                List.of(openCartModel()),
                null,
                null,
                null);
        Response empty = send(app, "POST", "/v1/carts/" + CART_ID + "/checkout", null);
        assertThat(empty.status()).isEqualTo(422);
        assertThat(empty.body()).contains("empty_cart");
        assertThat(send(app, "POST", "/v1/carts/" + CART_ID + "/items", "{\"sku\":\"LAMP-DESK\",\"quantity\":2}")
                        .status())
                .isEqualTo(200);
        Response overflow = send(app, "POST", "/v1/carts/" + CART_ID + "/checkout", null);
        assertThat(overflow.status()).isEqualTo(422);
        assertThat(overflow.body()).contains("amount_overflow");
        MockMvc stock = app(List.of(Fakes.product("LAMP-DESK", 12900, 1)), List.of(openCartModel()), null, null, null);
        assertThat(send(stock, "POST", "/v1/carts/" + CART_ID + "/items", "{\"sku\":\"LAMP-DESK\",\"quantity\":3}")
                        .status())
                .isEqualTo(409);
        MockMvc missing = app(List.of(Fakes.product("MUG-STONE", 1800, 10)), List.of(), null, null, null);
        assertThat(send(missing, "POST", "/v1/carts/" + CART_ID + "/items", "{\"sku\":\"MUG-STONE\",\"quantity\":1}")
                        .status())
                .isEqualTo(404);
    }

    @Test
    void cart_storage_failure_is_a_server_error() throws Exception {
        MockMvc app = app(
                List.of(Fakes.product("MUG-STONE", 1800, 10)),
                List.of(),
                null,
                new RepositoryException.Unavailable("connect"),
                null);
        Response response = send(app, "POST", "/v1/carts", null);
        assertThat(response.status()).isEqualTo(500);
        assertThat(response.body()).contains("storage_unavailable");
    }
}
