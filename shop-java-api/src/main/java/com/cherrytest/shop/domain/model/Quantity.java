package com.cherrytest.shop.domain.model;

public record Quantity(int value) {
    public Quantity {
        if (value < 1 || value > DomainError.MAXIMUM_LINE_QUANTITY) {
            throw new DomainException(new DomainError.InvalidQuantity());
        }
    }

    public static Quantity of(long value) {
        if (value < 1 || value > DomainError.MAXIMUM_LINE_QUANTITY) {
            throw new DomainException(new DomainError.InvalidQuantity());
        }
        return new Quantity((int) value);
    }
}
