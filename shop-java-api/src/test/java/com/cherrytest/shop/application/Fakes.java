package com.cherrytest.shop.application;

import com.cherrytest.shop.domain.model.Cart;
import com.cherrytest.shop.domain.model.CartId;
import com.cherrytest.shop.domain.model.CartLine;
import com.cherrytest.shop.domain.model.CartStatus;
import com.cherrytest.shop.domain.model.DiscountPercent;
import com.cherrytest.shop.domain.model.DomainError;
import com.cherrytest.shop.domain.model.Money;
import com.cherrytest.shop.domain.model.Order;
import com.cherrytest.shop.domain.model.OrderDraft;
import com.cherrytest.shop.domain.model.Product;
import com.cherrytest.shop.domain.model.Sku;
import com.cherrytest.shop.domain.port.CartRepository;
import com.cherrytest.shop.domain.port.OrderRepository;
import com.cherrytest.shop.domain.port.ProductRepository;
import com.cherrytest.shop.domain.port.RepositoryException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

public final class Fakes {
    public static final String CART_ID = "0b8f6c1e-4a2d-4e1f-9c3b-2d5e7f8a9b0c";

    private Fakes() {}

    public record InMemoryProducts(List<Product> products) implements ProductRepository {
        @Override
        public List<Product> list() {
            return products;
        }

        @Override
        public Optional<Product> findBySku(Sku sku) {
            return products.stream().filter(product -> product.sku().equals(sku)).findFirst();
        }

        @Override
        public List<Product> findBySkus(List<Sku> skus) {
            return products.stream().filter(product -> skus.contains(product.sku())).toList();
        }
    }

    public record FailingProducts(RepositoryException failure) implements ProductRepository {
        @Override
        public List<Product> list() {
            throw failure;
        }

        @Override
        public Optional<Product> findBySku(Sku sku) {
            throw failure;
        }

        @Override
        public List<Product> findBySkus(List<Sku> skus) {
            throw failure;
        }
    }

    public static final class InMemoryCarts implements CartRepository {
        private final Map<String, Cart> carts = new TreeMap<>();
        private final @Nullable RepositoryException failure;

        public InMemoryCarts(List<Cart> seeded, @Nullable RepositoryException failure) {
            seeded.forEach(cart -> carts.put(cart.id().value(), cart));
            this.failure = failure;
        }

        public InMemoryCarts() {
            this(List.of(), null);
        }

        private void failIfConfigured() {
            if (failure != null) {
                throw failure;
            }
        }

        @Override
        public synchronized Cart create() {
            failIfConfigured();
            Cart cart = new Cart(CartId.parse(CART_ID), CartStatus.OPEN, List.of());
            carts.put(CART_ID, cart);
            return cart;
        }

        @Override
        public synchronized Optional<Cart> find(CartId cartId) {
            failIfConfigured();
            return Optional.ofNullable(carts.get(cartId.value()));
        }

        @Override
        public synchronized void saveLine(CartId cartId, CartLine line) {
            failIfConfigured();
            Cart cart = Objects.requireNonNull(carts.get(cartId.value()));
            List<CartLine> lines = new ArrayList<>(cart.lines());
            lines.removeIf(existing -> existing.sku().equals(line.sku()));
            lines.add(line);
            carts.put(cartId.value(), new Cart(cart.id(), cart.status(), lines));
        }
    }

    public static final class RecordingOrders implements OrderRepository {
        public final List<OrderDraft> placed = new ArrayList<>();
        private final @Nullable RepositoryException failure;

        public RecordingOrders(@Nullable RepositoryException failure) {
            this.failure = failure;
        }

        public RecordingOrders() {
            this(null);
        }

        @Override
        public synchronized Order place(OrderDraft draft) {
            if (failure != null) {
                throw failure;
            }
            placed.add(draft);
            return new Order(1, draft.cartId(), draft.lines(), draft.total());
        }
    }

    public static Product product(String sku, long priceCents, long stock) {
        return new Product(Sku.parse(sku), sku, Money.fromCents(priceCents), DiscountPercent.none(), stock);
    }

    public static List<Product> catalog() {
        return List.of(
                new Product(Sku.parse("MUG-STONE"), "Stoneware mug", Money.fromCents(1800), DiscountPercent.none(), 10),
                new Product(
                        Sku.parse("LAMP-DESK"), "Brass desk lamp", Money.fromCents(12900), DiscountPercent.none(), 2));
    }

    public static RepositoryException.Rejected rejected(DomainError error) {
        return new RepositoryException.Rejected(error);
    }
}
