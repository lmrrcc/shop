package com.cherrytest.shop.infrastructure.web;

import com.cherrytest.shop.application.CartService;
import com.cherrytest.shop.domain.model.CartId;
import com.cherrytest.shop.domain.model.Order;
import com.cherrytest.shop.domain.model.Quantity;
import com.cherrytest.shop.domain.model.Sku;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Cart")
class CartController {
    private static final Logger LOG = LoggerFactory.getLogger(CartController.class);
    private final CartService carts;

    CartController(CartService carts) {
        this.carts = carts;
    }

    @PostMapping("/v1/carts")
    @Operation(summary = "Opens an empty cart")
    ResponseEntity<Dtos.CartResponse> openCart() {
        return ResponseEntity.status(HttpStatus.CREATED).body(Dtos.CartResponse.from(carts.openCart()));
    }

    @GetMapping("/v1/carts/{id}")
    @Operation(summary = "Reads a cart by UUID")
    Dtos.CartResponse findCart(@PathVariable String id) {
        return Dtos.CartResponse.from(carts.findCart(CartId.parse(id)));
    }

    @PostMapping("/v1/carts/{id}/items")
    @Operation(summary = "Adds a quantity of a product to a cart")
    Dtos.CartResponse addCartItem(@PathVariable String id, @RequestBody Dtos.AddCartItemRequest request) {
        CartId cartId = CartId.parse(id);
        Sku sku = Sku.parse(request.sku());
        Quantity quantity = Quantity.of(request.quantity());
        return Dtos.CartResponse.from(carts.addToCart(cartId, sku, quantity));
    }

    @PostMapping("/v1/carts/{id}/checkout")
    @Tag(name = "Checkout")
    @Operation(summary = "Places an order for every line of a cart")
    ResponseEntity<Dtos.OrderResponse> checkout(@PathVariable String id) {
        Order order = carts.checkout(CartId.parse(id));
        LOG.atInfo()
                .addKeyValue("order_id", order.id())
                .addKeyValue("cart_id", order.cartId().value())
                .addKeyValue("total_cents", order.total().cents())
                .log("order placed");
        return ResponseEntity.status(HttpStatus.CREATED).body(Dtos.OrderResponse.from(order));
    }
}
