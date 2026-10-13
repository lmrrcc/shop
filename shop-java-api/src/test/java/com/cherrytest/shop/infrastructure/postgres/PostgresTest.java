package com.cherrytest.shop.infrastructure.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cherrytest.shop.domain.model.CartStatus;
import com.cherrytest.shop.domain.model.DomainError;
import com.cherrytest.shop.domain.model.Sku;
import com.cherrytest.shop.domain.port.RepositoryException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

class PostgresTest {
    private static void assertRejected(ThrowingCallable action, DomainError expected) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(
                        RepositoryException.Rejected.class, error -> assertThat(error.error()).isEqualTo(expected));
    }

    @Test
    void connect_reports_unavailable_when_the_server_refuses() {
        assertThatThrownBy(() -> PostgresConnection.connect("postgres://shop:shop@127.0.0.1:1/shop"))
                .isInstanceOfSatisfying(RepositoryException.Unavailable.class, error -> {
                    assertThat(error.reason()).isEqualTo("connect");
                    assertThat(error.getMessage()).contains("connect");
                });
    }

    @Test
    void cart_status_accepts_both_stored_values_and_rejects_anything_else() {
        assertThat(Rows.cartStatus(Rows.CART_STATUS_OPEN)).isEqualTo(CartStatus.OPEN);
        assertThat(Rows.cartStatus(Rows.CART_STATUS_CHECKED_OUT)).isEqualTo(CartStatus.CHECKED_OUT);
        assertThatThrownBy(() -> Rows.cartStatus("archived"))
                .isInstanceOfSatisfying(
                        RepositoryException.Unavailable.class,
                        error -> assertThat(error.reason()).isEqualTo("decode_cart"));
    }

    @Test
    void product_parts_reject_values_the_schema_also_forbids() {
        assertThat(Rows.productFromParts("MUG-STONE", "Mug", 1800, 10, 4).stock()).isEqualTo(4);
        assertRejected(() -> Rows.productFromParts("no", "Mug", 1800, 0, 4), new DomainError.InvalidSku());
        assertRejected(() -> Rows.productFromParts("MUG-STONE", "Mug", -1, 0, 4), new DomainError.InvalidMoney());
        assertRejected(
                () -> Rows.productFromParts("MUG-STONE", "Mug", 1800, 0, -1), new DomainError.InvalidQuantity());
    }

    @Test
    void cart_line_parts_reject_a_bad_sku_or_quantity() {
        assertThat(Rows.cartLineFromParts("MUG-STONE", 2).quantity().value()).isEqualTo(2);
        assertRejected(() -> Rows.cartLineFromParts("no", 2), new DomainError.InvalidSku());
        assertRejected(() -> Rows.cartLineFromParts("MUG-STONE", 0), new DomainError.InvalidQuantity());
    }

    @Test
    void quantity_column_rejects_a_value_that_does_not_fit_the_database() {
        assertThat(Rows.quantityColumn(2)).isEqualTo(2);
        assertRejected(() -> Rows.quantityColumn(4_294_967_295L), new DomainError.InvalidQuantity());
    }

    @Test
    void order_outcomes_distinguish_a_closed_cart_and_a_missed_reservation() {
        Sku sku = Sku.parse("LAMP-DESK");
        Rows.closeOutcome(1);
        assertRejected(() -> Rows.closeOutcome(0), new DomainError.CartClosed());
        Rows.reserveOutcome(1, sku);
        assertRejected(() -> Rows.reserveOutcome(0, sku), new DomainError.OutOfStock(sku));
    }
}
