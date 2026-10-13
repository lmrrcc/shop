package com.cherrytest.shop.domain.model;

import java.util.Locale;
import java.util.Set;

public record CartId(String value) {
    private static final int LENGTH = 36;
    private static final Set<Integer> DASH_POSITIONS = Set.of(8, 13, 18, 23);

    public CartId {
        if (!shapeValid(value)) {
            throw new DomainException(new DomainError.InvalidCartId());
        }
        value = value.toLowerCase(Locale.ROOT);
    }

    public static CartId parse(String raw) {
        return new CartId(raw);
    }

    private static boolean shapeValid(String raw) {
        if (raw.length() != LENGTH) {
            return false;
        }
        for (int position = 0; position < raw.length(); position++) {
            char character = raw.charAt(position);
            boolean valid = DASH_POSITIONS.contains(position)
                    ? character == '-'
                    : Character.digit(character, 16) >= 0 && character < 128;
            if (!valid) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return value;
    }
}
