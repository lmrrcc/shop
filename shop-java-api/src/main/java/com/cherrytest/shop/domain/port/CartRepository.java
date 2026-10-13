package com.cherrytest.shop.domain.port;

import com.cherrytest.shop.domain.model.Cart;
import com.cherrytest.shop.domain.model.CartId;
import com.cherrytest.shop.domain.model.CartLine;
import java.util.Optional;

public interface CartRepository {
    Cart create();

    Optional<Cart> find(CartId cartId);

    void saveLine(CartId cartId, CartLine line);
}
