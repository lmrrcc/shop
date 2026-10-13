package com.cherrytest.shop.domain.model;

public record DiscountPercent(int value) {
    static final int MAXIMUM = 100;

    public DiscountPercent {
        if (value < 0 || value > MAXIMUM) {
            throw new DomainException(new DomainError.InvalidDiscount());
        }
    }

    public static DiscountPercent none() {
        return new DiscountPercent(0);
    }

    public static DiscountPercent parse(long percent) {
        if (percent < 0 || percent > MAXIMUM) {
            throw new DomainException(new DomainError.InvalidDiscount());
        }
        return new DiscountPercent((int) percent);
    }
}
