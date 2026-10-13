package com.cherrytest.shop.domain.model;

import java.util.ArrayList;
import java.util.List;

public record Cart(CartId id, CartStatus status, List<CartLine> lines) {
    public Cart {
        lines = List.copyOf(lines);
    }

    public CartLine add(Product product, Quantity quantity) {
        ensureOpen();
        int existing = lines.stream()
                .filter(line -> line.sku().equals(product.sku()))
                .findFirst()
                .map(line -> line.quantity().value())
                .orElse(0);
        Quantity merged = Quantity.of((long) existing + quantity.value());
        product.ensureAvailable(merged);
        return new CartLine(product.sku(), merged);
    }

    public OrderDraft checkout(List<Product> products) {
        ensureOpen();
        if (lines.isEmpty()) {
            throw new DomainException(new DomainError.EmptyCart());
        }
        List<OrderLine> priced = new ArrayList<>();
        for (CartLine line : lines) {
            priced.add(priceLine(line, products));
        }
        Money total = Money.zero();
        for (OrderLine line : priced) {
            total = total.plus(line.unitPrice().times(line.quantity()));
        }
        return new OrderDraft(id, priced, total);
    }

    private void ensureOpen() {
        switch (status) {
            case OPEN -> {}
            case CHECKED_OUT -> throw new DomainException(new DomainError.CartClosed());
        }
    }

    private static OrderLine priceLine(CartLine line, List<Product> products) {
        Product product = products.stream()
                .filter(candidate -> candidate.sku().equals(line.sku()))
                .findFirst()
                .orElseThrow(() -> new DomainException(new DomainError.ProductNotFound(line.sku())));
        product.ensureAvailable(line.quantity());
        return new OrderLine(line.sku(), line.quantity(), product.price().discounted(product.discountPercent()));
    }
}
