package com.cherrytest.shop.domain.model;

public record Sku(String value) implements Comparable<Sku> {
    public Sku {
        boolean lengthValid =
                value.length() >= DomainError.MINIMUM_SKU_LENGTH && value.length() <= DomainError.MAXIMUM_SKU_LENGTH;
        boolean charactersValid = value.chars()
                .allMatch(character -> (character >= 'A' && character <= 'Z')
                        || (character >= '0' && character <= '9')
                        || character == '-');
        if (!lengthValid || !charactersValid) {
            throw new DomainException(new DomainError.InvalidSku());
        }
    }

    public static Sku parse(String raw) {
        return new Sku(raw);
    }

    @Override
    public int compareTo(Sku other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
