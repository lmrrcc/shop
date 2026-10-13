package com.cherrytest.shop.infrastructure.web;

import com.cherrytest.shop.domain.model.Cart;
import com.cherrytest.shop.domain.model.CartLine;
import com.cherrytest.shop.domain.model.Order;
import com.cherrytest.shop.domain.model.OrderLine;
import com.cherrytest.shop.domain.model.Product;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

final class Dtos {
    private Dtos() {}

    record ProductResponse(
            String sku,
            String name,
            @JsonProperty("price_cents") long priceCents,
            @JsonProperty("discount_percent") int discountPercent,
            long stock) {
        static ProductResponse from(Product product) {
            return new ProductResponse(
                    product.sku().value(),
                    product.name(),
                    product.price().cents(),
                    product.discountPercent().value(),
                    product.stock());
        }
    }

    record ProductListResponse(List<ProductResponse> items, int total) {}

    record CartLineResponse(String sku, int quantity) {
        static CartLineResponse from(CartLine line) {
            return new CartLineResponse(line.sku().value(), line.quantity().value());
        }
    }

    record CartResponse(String id, String status, List<CartLineResponse> lines) {
        static CartResponse from(Cart cart) {
            String status = switch (cart.status()) {
                case OPEN -> "open";
                case CHECKED_OUT -> "checked_out";
            };
            return new CartResponse(
                    cart.id().value(), status, cart.lines().stream().map(CartLineResponse::from).toList());
        }
    }

    record OrderLineResponse(String sku, int quantity, @JsonProperty("unit_price_cents") long unitPriceCents) {
        static OrderLineResponse from(OrderLine line) {
            return new OrderLineResponse(line.sku().value(), line.quantity().value(), line.unitPrice().cents());
        }
    }

    record OrderResponse(
            long id,
            @JsonProperty("cart_id") String cartId,
            List<OrderLineResponse> lines,
            @JsonProperty("total_cents") long totalCents) {
        static OrderResponse from(Order order) {
            return new OrderResponse(
                    order.id(),
                    order.cartId().value(),
                    order.lines().stream().map(OrderLineResponse::from).toList(),
                    order.total().cents());
        }
    }

    record AddCartItemRequest(
            @JsonProperty(required = true) String sku, @JsonProperty(required = true) long quantity) {}

    record ErrorBody(String code, String message) {}

    record ErrorResponse(ErrorBody error) {}
}
