package com.cherrytest.shop.application;

import com.cherrytest.shop.domain.model.DomainError;

public sealed interface ShopError {
    record Domain(DomainError error) implements ShopError {}

    record Unavailable(String reason) implements ShopError {}

    default String message() {
        return switch (this) {
            case Domain domain -> domain.error().message();
            case Unavailable unavailable -> "storage unavailable: " + unavailable.reason();
        };
    }
}
