package com.cherrytest.shop.domain.model;

public record Product(Sku sku, String name, Money price, DiscountPercent discountPercent, long stock) {
    public void ensureAvailable(Quantity quantity) {
        if (quantity.value() > stock) {
            throw new DomainException(new DomainError.OutOfStock(sku));
        }
    }
}
