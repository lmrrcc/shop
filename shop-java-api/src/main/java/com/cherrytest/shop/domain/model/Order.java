package com.cherrytest.shop.domain.model;

import java.util.List;

public record Order(long id, CartId cartId, List<OrderLine> lines, Money total) {
    public Order {
        lines = List.copyOf(lines);
    }
}
