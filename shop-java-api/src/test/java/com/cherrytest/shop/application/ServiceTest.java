package com.cherrytest.shop.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cherrytest.shop.domain.model.Cart;
import com.cherrytest.shop.domain.model.CartId;
import com.cherrytest.shop.domain.model.CartLine;
import com.cherrytest.shop.domain.model.DomainError;
import com.cherrytest.shop.domain.model.Order;
import com.cherrytest.shop.domain.model.Product;
import com.cherrytest.shop.domain.model.Quantity;
import com.cherrytest.shop.domain.model.Sku;
import com.cherrytest.shop.domain.port.RepositoryException;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ServiceTest {
    private final Fakes.RecordingOrders orders = new Fakes.RecordingOrders();
    private final CartService service =
            new CartService(new Fakes.InMemoryProducts(Fakes.catalog()), new Fakes.InMemoryCarts(), orders);

    private static Sku sku(String raw) {
        return Sku.parse(raw);
    }

    private static Quantity quantity(long value) {
        return Quantity.of(value);
    }

    private static void assertShopError(ThrowingCallable action, ShopError expected) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(ShopException.class, error -> assertThat(error.error()).isEqualTo(expected));
    }

    @Nested
    class catalog_service {
        @Test
        void returns_not_found_for_unknown_sku() {
            CatalogService catalog = new CatalogService(new Fakes.InMemoryProducts(Fakes.catalog()));
            assertShopError(
                    () -> catalog.findProduct(sku("NOPE-1")),
                    new ShopError.Domain(new DomainError.ProductNotFound(sku("NOPE-1"))));
        }

        @Test
        void keeps_a_rejected_domain_error() {
            CatalogService catalog =
                    new CatalogService(new Fakes.FailingProducts(Fakes.rejected(new DomainError.InvalidSku())));
            assertThatThrownBy(catalog::listProducts)
                    .isInstanceOfSatisfying(ShopException.class, error -> {
                        assertThat(error.error()).isEqualTo(new ShopError.Domain(new DomainError.InvalidSku()));
                        assertThat(error.getMessage()).isEqualTo(new DomainError.InvalidSku().message());
                    });
        }

        @Test
        void surfaces_unavailable_storage() {
            CatalogService catalog = new CatalogService(
                    new Fakes.FailingProducts(new RepositoryException.Unavailable("connection refused")));
            assertThatThrownBy(catalog::listProducts)
                    .isInstanceOfSatisfying(ShopException.class, error -> {
                        assertThat(error.error()).isEqualTo(new ShopError.Unavailable("connection refused"));
                        assertThat(error.getMessage()).isEqualTo("storage unavailable: connection refused");
                    });
        }

        @Test
        void reports_a_price_mismatch() {
            CatalogService catalog = new CatalogService(new Fakes.InMemoryProducts(Fakes.catalog()));
            Product mug = catalog.listProducts().stream()
                    .filter(product -> product.sku().equals(sku("MUG-STONE")))
                    .findFirst()
                    .orElseThrow();
            assertThat(mug.price().cents())
                    .as("listed price did not match the expected 1500")
                    .isEqualTo(1500);
        }
    }

    @Nested
    class add_to_cart {
        @Test
        void persists_merged_line() {
            Cart cart = service.openCart();
            service.addToCart(cart.id(), sku("MUG-STONE"), quantity(2));
            Cart updated = service.addToCart(cart.id(), sku("MUG-STONE"), quantity(1));
            assertThat(updated.lines()).containsExactly(new CartLine(sku("MUG-STONE"), quantity(3)));
        }

        @Test
        void rejects_unknown_cart() {
            CartId cartId = CartId.parse(Fakes.CART_ID);
            assertShopError(
                    () -> service.addToCart(cartId, sku("MUG-STONE"), quantity(1)),
                    new ShopError.Domain(new DomainError.CartNotFound(cartId)));
        }

        @Test
        void rejects_quantity_beyond_stock() {
            Cart cart = service.openCart();
            assertShopError(
                    () -> service.addToCart(cart.id(), sku("LAMP-DESK"), quantity(3)),
                    new ShopError.Domain(new DomainError.OutOfStock(sku("LAMP-DESK"))));
        }
    }

    @Nested
    class checkout {
        @Test
        void places_order_with_priced_total() {
            Cart cart = service.openCart();
            service.addToCart(cart.id(), sku("MUG-STONE"), quantity(2));
            service.addToCart(cart.id(), sku("LAMP-DESK"), quantity(1));
            Order order = service.checkout(cart.id());
            assertThat(order.total().cents()).isEqualTo(16500);
        }

        @Test
        void does_not_place_order_for_empty_cart() {
            Cart cart = service.openCart();
            assertShopError(() -> service.checkout(cart.id()), new ShopError.Domain(new DomainError.EmptyCart()));
            assertThat(orders.placed).isEqualTo(List.of());
        }
    }
}
