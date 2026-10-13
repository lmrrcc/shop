package com.cherrytest.shop.domain.model;

public sealed interface DomainError {
    int MINIMUM_SKU_LENGTH = 3;
    int MAXIMUM_SKU_LENGTH = 32;
    int MAXIMUM_LINE_QUANTITY = 99;

    record InvalidSku() implements DomainError {}

    record InvalidQuantity() implements DomainError {}

    record InvalidMoney() implements DomainError {}

    record InvalidDiscount() implements DomainError {}

    record InvalidCartId() implements DomainError {}

    record AmountOverflow() implements DomainError {}

    record ProductNotFound(Sku sku) implements DomainError {}

    record CartNotFound(CartId cartId) implements DomainError {}

    record CartClosed() implements DomainError {}

    record EmptyCart() implements DomainError {}

    record OutOfStock(Sku sku) implements DomainError {}

    default String message() {
        return switch (this) {
            case InvalidSku _ ->
                "sku must be " + MINIMUM_SKU_LENGTH + "-" + MAXIMUM_SKU_LENGTH
                        + " uppercase letters, digits or dashes";
            case InvalidQuantity _ -> "quantity must be between 1 and " + MAXIMUM_LINE_QUANTITY;
            case InvalidMoney _ -> "amount must not be negative";
            case InvalidDiscount _ -> "discount percent must be an integer from 0 through 100";
            case InvalidCartId _ -> "cart id must be a UUID";
            case AmountOverflow _ -> "order total exceeds the supported range";
            case ProductNotFound notFound -> "product " + notFound.sku().value() + " does not exist";
            case CartNotFound notFound -> "cart " + notFound.cartId().value() + " does not exist";
            case CartClosed _ -> "cart is already checked out";
            case EmptyCart _ -> "cart has no items";
            case OutOfStock outOfStock -> "not enough stock for " + outOfStock.sku().value();
        };
    }
}
