package com.cherrytest.shop.domain.model;

import java.util.List;

public record OrderDraft(CartId cartId, List<OrderLine> lines, Money total) {
    public OrderDraft {
        lines = List.copyOf(lines);
    }
}
