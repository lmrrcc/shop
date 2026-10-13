package com.cherrytest.shop.domain.model;

import java.math.BigInteger;

public record Money(long cents) implements Comparable<Money> {
    public Money {
        if (cents < 0) {
            throw new DomainException(new DomainError.InvalidMoney());
        }
    }

    public static Money fromCents(long cents) {
        return new Money(cents);
    }

    public static Money zero() {
        return new Money(0);
    }

    public Money times(Quantity quantity) {
        try {
            return new Money(Math.multiplyExact(cents, (long) quantity.value()));
        } catch (ArithmeticException overflow) {
            throw new DomainException(new DomainError.AmountOverflow());
        }
    }

    public Money plus(Money other) {
        try {
            return new Money(Math.addExact(cents, other.cents));
        } catch (ArithmeticException overflow) {
            throw new DomainException(new DomainError.AmountOverflow());
        }
    }

    public Money discounted(DiscountPercent percent) {
        long reduction = BigInteger.valueOf(cents)
                .multiply(BigInteger.valueOf(percent.value()))
                .divide(BigInteger.valueOf(DiscountPercent.MAXIMUM))
                .longValueExact();
        return new Money(cents - reduction);
    }

    @Override
    public int compareTo(Money other) {
        return Long.compare(cents, other.cents);
    }
}
