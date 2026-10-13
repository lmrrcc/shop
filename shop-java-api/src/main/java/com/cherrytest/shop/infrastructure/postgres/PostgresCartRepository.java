package com.cherrytest.shop.infrastructure.postgres;

import com.cherrytest.shop.domain.model.Cart;
import com.cherrytest.shop.domain.model.CartId;
import com.cherrytest.shop.domain.model.CartLine;
import com.cherrytest.shop.domain.model.CartStatus;
import com.cherrytest.shop.domain.model.DomainException;
import com.cherrytest.shop.domain.port.CartRepository;
import com.cherrytest.shop.domain.port.RepositoryException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class PostgresCartRepository implements CartRepository {
    private final JdbcClient jdbc;

    public PostgresCartRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Cart create() {
        String id = Storage.checked(
                "create_cart",
                () -> jdbc.sql("INSERT INTO cart (STATUS) VALUES (?) RETURNING ID::text AS id")
                        .param(Rows.CART_STATUS_OPEN)
                        .query(String.class)
                        .single());
        try {
            return new Cart(CartId.parse(id), CartStatus.OPEN, List.of());
        } catch (DomainException exception) {
            throw new RepositoryException.Rejected(exception.error());
        }
    }

    @Override
    public Optional<Cart> find(CartId cartId) {
        Optional<String> status = Storage.checked(
                "find_cart",
                () -> jdbc.sql("SELECT STATUS FROM cart WHERE ID = ?::uuid")
                        .param(cartId.value())
                        .query(String.class)
                        .optional());
        if (status.isEmpty()) {
            return Optional.empty();
        }
        List<Rows.CartLineRow> rows = Storage.checked(
                "find_cart_lines",
                () -> jdbc.sql("SELECT SKU, QUANTITY FROM cart_line WHERE CART_ID = ?::uuid ORDER BY SKU")
                        .param(cartId.value())
                        .query(Rows.CartLineRow.class)
                        .list());
        List<CartLine> lines = rows.stream()
                .map(row -> Rows.cartLineFromParts(row.sku(), row.quantity()))
                .toList();
        return Optional.of(new Cart(cartId, Rows.cartStatus(status.get()), lines));
    }

    @Override
    public void saveLine(CartId cartId, CartLine line) {
        int quantity = Rows.quantityColumn(line.quantity().value());
        Storage.checked(
                "save_cart_line",
                () -> jdbc.sql("INSERT INTO cart_line (CART_ID, SKU, QUANTITY) VALUES (?::uuid, ?, ?)"
                                + " ON CONFLICT (CART_ID, SKU) DO UPDATE SET QUANTITY = EXCLUDED.QUANTITY")
                        .param(cartId.value())
                        .param(line.sku().value())
                        .param(quantity)
                        .update());
    }
}
