package com.cherrytest.shop.application;

import com.cherrytest.shop.domain.model.Cart;
import com.cherrytest.shop.domain.model.CartId;
import com.cherrytest.shop.domain.model.CartLine;
import com.cherrytest.shop.domain.model.DomainError;
import com.cherrytest.shop.domain.model.Order;
import com.cherrytest.shop.domain.model.OrderDraft;
import com.cherrytest.shop.domain.model.Product;
import com.cherrytest.shop.domain.model.Quantity;
import com.cherrytest.shop.domain.model.Sku;
import com.cherrytest.shop.domain.port.CartRepository;
import com.cherrytest.shop.domain.port.OrderRepository;
import com.cherrytest.shop.domain.port.ProductRepository;
import java.util.List;

public final class CartService {
    private final ProductRepository products;
    private final CartRepository carts;
    private final OrderRepository orders;

    public CartService(ProductRepository products, CartRepository carts, OrderRepository orders) {
        this.products = products;
        this.carts = carts;
        this.orders = orders;
    }

    public Cart openCart() {
        return ShopException.translated(carts::create);
    }

    public Cart findCart(CartId cartId) {
        return ShopException.translated(() -> carts.find(cartId))
                .orElseThrow(() -> ShopException.domain(new DomainError.CartNotFound(cartId)));
    }

    public Cart addToCart(CartId cartId, Sku sku, Quantity quantity) {
        Cart cart = findCart(cartId);
        Product product = ShopException.translated(() -> products.findBySku(sku))
                .orElseThrow(() -> ShopException.domain(new DomainError.ProductNotFound(sku)));
        CartLine line = ShopException.translated(() -> cart.add(product, quantity));
        ShopException.translated(() -> {
            carts.saveLine(cartId, line);
            return line;
        });
        return findCart(cartId);
    }

    public Order checkout(CartId cartId) {
        Cart cart = findCart(cartId);
        List<Sku> skus = cart.lines().stream().map(CartLine::sku).toList();
        List<Product> found = ShopException.translated(() -> products.findBySkus(skus));
        OrderDraft draft = ShopException.translated(() -> cart.checkout(found));
        return ShopException.translated(() -> orders.place(draft));
    }
}
