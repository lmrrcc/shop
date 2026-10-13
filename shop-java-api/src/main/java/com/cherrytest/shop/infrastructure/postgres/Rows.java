package com.cherrytest.shop.infrastructure.postgres;

import com.cherrytest.shop.domain.model.CartLine;
import com.cherrytest.shop.domain.model.CartStatus;
import com.cherrytest.shop.domain.model.DiscountPercent;
import com.cherrytest.shop.domain.model.DomainError;
import com.cherrytest.shop.domain.model.DomainException;
import com.cherrytest.shop.domain.model.Money;
import com.cherrytest.shop.domain.model.Product;
import com.cherrytest.shop.domain.model.Quantity;
import com.cherrytest.shop.domain.model.Sku;
import com.cherrytest.shop.domain.port.RepositoryException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class Rows {
    static final String CART_STATUS_OPEN = "open";
    static final String CART_STATUS_CHECKED_OUT = "checked_out";
    private static final Logger LOG = LoggerFactory.getLogger(Rows.class);

    private Rows() {}

    record ProductRow(String sku, String name, long priceCents, long discountPercent, int stock) {}

    record CartLineRow(String sku, int quantity) {}

    private static <T> T rejected(Supplier<T> parse) {
        try {
            return parse.get();
        } catch (DomainException exception) {
            throw new RepositoryException.Rejected(exception.error());
        }
    }

    static Product productFromParts(String sku, String name, long priceCents, long discountPercent, int stock) {
        Sku parsedSku = rejected(() -> Sku.parse(sku));
        Money price = rejected(() -> Money.fromCents(priceCents));
        DiscountPercent discount = rejected(() -> DiscountPercent.parse(discountPercent));
        if (stock < 0) {
            throw new RepositoryException.Rejected(new DomainError.InvalidQuantity());
        }
        return new Product(parsedSku, name, price, discount, stock);
    }

    static Product product(ProductRow row) {
        return productFromParts(row.sku(), row.name(), row.priceCents(), row.discountPercent(), row.stock());
    }

    static CartLine cartLineFromParts(String sku, int quantity) {
        return new CartLine(rejected(() -> Sku.parse(sku)), rejected(() -> Quantity.of(quantity)));
    }

    static CartStatus cartStatus(String raw) {
        return switch (raw) {
            case CART_STATUS_OPEN -> CartStatus.OPEN;
            case CART_STATUS_CHECKED_OUT -> CartStatus.CHECKED_OUT;
            default -> {
                LOG.atError()
                        .addKeyValue("upstream", "postgres")
                        .addKeyValue("status", raw)
                        .log("unknown cart status");
                throw new RepositoryException.Unavailable("decode_cart");
            }
        };
    }

    static int quantityColumn(long quantity) {
        if (quantity > Integer.MAX_VALUE) {
            throw new RepositoryException.Rejected(new DomainError.InvalidQuantity());
        }
        return (int) quantity;
    }

    static void closeOutcome(int rowsAffected) {
        if (rowsAffected == 0) {
            throw new RepositoryException.Rejected(new DomainError.CartClosed());
        }
    }

    static void reserveOutcome(int rowsAffected, Sku sku) {
        if (rowsAffected == 0) {
            throw new RepositoryException.Rejected(new DomainError.OutOfStock(sku));
        }
    }
}
