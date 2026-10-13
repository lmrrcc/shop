package com.cherrytest.shop.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.LongStream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ModelTest {
    private static Sku sku(String raw) {
        return Sku.parse(raw);
    }

    private static Product product(String rawSku, long priceCents, long stock) {
        return productWithDiscount(rawSku, priceCents, 0, stock);
    }

    private static Product productWithDiscount(String rawSku, long priceCents, long discountPercent, long stock) {
        return new Product(
                sku(rawSku), rawSku, Money.fromCents(priceCents), DiscountPercent.parse(discountPercent), stock);
    }

    private static Cart cart(CartStatus status, List<CartLine> lines) {
        return new Cart(CartId.parse("0b8f6c1e-4a2d-4e1f-9c3b-2d5e7f8a9b0c"), status, lines);
    }

    private static CartLine line(String rawSku, long quantity) {
        return new CartLine(sku(rawSku), Quantity.of(quantity));
    }

    private static void assertRejected(ThrowingCallable action, DomainError expected) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.error()).isEqualTo(expected));
    }

    @Nested
    class sku_parsing {
        @Test
        void accepts_uppercase_digits_and_dashes() {
            assertThat(Sku.parse("MUG-001").toString()).isEqualTo("MUG-001");
        }

        @Test
        void rejects_lowercase() {
            assertRejected(() -> Sku.parse("mug-001"), new DomainError.InvalidSku());
        }

        @Test
        void rejects_too_short() {
            assertRejected(() -> Sku.parse("AB"), new DomainError.InvalidSku());
        }
    }

    @Nested
    class quantity_bounds {
        @Test
        void rejects_zero() {
            assertRejected(() -> Quantity.of(0), new DomainError.InvalidQuantity());
        }

        @Test
        void rejects_above_line_maximum() {
            assertRejected(() -> Quantity.of(100), new DomainError.InvalidQuantity());
        }

        @Test
        void accepts_every_value_in_range() {
            assertThat(LongStream.rangeClosed(1, 99).mapToObj(Quantity::of)).hasSize(99);
        }
    }

    @Nested
    class cart_id_parsing {
        @Test
        void normalizes_to_lowercase() {
            assertThat(CartId.parse("0B8F6C1E-4A2D-4E1F-9C3B-2D5E7F8A9B0C").value())
                    .isEqualTo("0b8f6c1e-4a2d-4e1f-9c3b-2d5e7f8a9b0c");
        }

        @Test
        void rejects_non_uuid() {
            assertRejected(() -> CartId.parse("42"), new DomainError.InvalidCartId());
        }
    }

    @Nested
    class add_to_cart {
        @Test
        void merges_quantity_with_existing_line() {
            CartLine merged = cart(CartStatus.OPEN, List.of(line("MUG-001", 2)))
                    .add(product("MUG-001", 1800, 10), Quantity.of(3));
            assertThat(merged).isEqualTo(line("MUG-001", 5));
        }

        @Test
        void rejects_quantity_beyond_stock() {
            assertRejected(
                    () -> cart(CartStatus.OPEN, List.of()).add(product("LAMP-1", 12900, 2), Quantity.of(3)),
                    new DomainError.OutOfStock(sku("LAMP-1")));
        }

        @Test
        void rejects_merged_quantity_beyond_line_maximum() {
            assertRejected(
                    () -> cart(CartStatus.OPEN, List.of(line("MUG-001", 98)))
                            .add(product("MUG-001", 1800, 1000), Quantity.of(2)),
                    new DomainError.InvalidQuantity());
        }

        @Test
        void rejects_checked_out_cart() {
            assertRejected(
                    () -> cart(CartStatus.CHECKED_OUT, List.of()).add(product("MUG-001", 1800, 10), Quantity.of(1)),
                    new DomainError.CartClosed());
        }
    }

    @Nested
    class checkout {
        @Test
        void totals_every_line_in_cents() {
            OrderDraft draft = cart(CartStatus.OPEN, List.of(line("MUG-001", 2), line("TEE-M", 1)))
                    .checkout(List.of(product("MUG-001", 1800, 10), product("TEE-M", 2900, 10)));
            assertThat(draft.total().cents()).isEqualTo(6500);
        }

        @Test
        void applies_a_discount_percent_before_the_quantity() {
            OrderDraft draft = cart(CartStatus.OPEN, List.of(line("MUG-001", 2)))
                    .checkout(List.of(productWithDiscount("MUG-001", 1800, 10, 10)));
            assertThat(draft.lines().getFirst().unitPrice().cents()).isEqualTo(1620);
            assertThat(draft.total().cents()).isEqualTo(3240);
        }

        @Test
        void rejects_empty_cart() {
            assertRejected(() -> cart(CartStatus.OPEN, List.of()).checkout(List.of()), new DomainError.EmptyCart());
        }

        @Test
        void rejects_line_that_exceeds_current_stock() {
            assertRejected(
                    () -> cart(CartStatus.OPEN, List.of(line("LAMP-1", 2))).checkout(List.of(product("LAMP-1", 12900, 1))),
                    new DomainError.OutOfStock(sku("LAMP-1")));
        }

        @Test
        void rejects_checked_out_cart() {
            assertRejected(
                    () -> cart(CartStatus.CHECKED_OUT, List.of(line("MUG-001", 1)))
                            .checkout(List.of(product("MUG-001", 1800, 10))),
                    new DomainError.CartClosed());
        }

        @Test
        void rejects_a_line_whose_product_is_missing() {
            assertRejected(
                    () -> cart(CartStatus.OPEN, List.of(line("MUG-001", 1))).checkout(List.of()),
                    new DomainError.ProductNotFound(sku("MUG-001")));
        }

        @Test
        void rejects_a_total_that_overflows() {
            assertRejected(
                    () -> cart(CartStatus.OPEN, List.of(line("MUG-001", 2)))
                            .checkout(List.of(product("MUG-001", Long.MAX_VALUE, 10))),
                    new DomainError.AmountOverflow());
        }
    }

    @Test
    void money_rejects_a_negative_amount_and_an_overflowing_sum() {
        assertRejected(() -> Money.fromCents(-1), new DomainError.InvalidMoney());
        Money max = Money.fromCents(Long.MAX_VALUE);
        assertRejected(() -> max.plus(Money.fromCents(1)), new DomainError.AmountOverflow());
        assertRejected(() -> max.times(Quantity.of(2)), new DomainError.AmountOverflow());
    }

    @Test
    void sku_accepts_the_longest_code_and_exposes_it() {
        String longest = "A".repeat(32);
        assertThat(Sku.parse(longest).value()).isEqualTo(longest);
        assertRejected(() -> Sku.parse("A".repeat(33)), new DomainError.InvalidSku());
    }

    @Test
    void cart_id_rejects_a_dash_in_the_wrong_place() {
        assertRejected(() -> CartId.parse("0b8f6c1e4a2d-4e1f-9c3b-2d5e7f8a9b0c"), new DomainError.InvalidCartId());
    }

    @Test
    void every_domain_error_names_the_problem() {
        Sku mug = sku("MUG-001");
        CartId cartId = CartId.parse("0b8f6c1e-4a2d-4e1f-9c3b-2d5e7f8a9b0c");
        List<DomainError> errors = List.of(
                new DomainError.InvalidSku(),
                new DomainError.InvalidQuantity(),
                new DomainError.InvalidMoney(),
                new DomainError.InvalidDiscount(),
                new DomainError.InvalidCartId(),
                new DomainError.AmountOverflow(),
                new DomainError.ProductNotFound(mug),
                new DomainError.CartNotFound(cartId),
                new DomainError.CartClosed(),
                new DomainError.EmptyCart(),
                new DomainError.OutOfStock(mug));
        assertThat(errors).allSatisfy(error -> assertThat(error.message()).isNotEmpty());
        assertThat(cartId.toString()).isNotEmpty();
        assertThat(new DomainException(new DomainError.InvalidSku()).getMessage())
                .isEqualTo(new DomainError.InvalidSku().message());
    }
}
