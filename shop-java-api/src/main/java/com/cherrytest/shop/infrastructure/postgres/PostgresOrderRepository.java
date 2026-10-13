package com.cherrytest.shop.infrastructure.postgres;

import com.cherrytest.shop.domain.model.Order;
import com.cherrytest.shop.domain.model.OrderDraft;
import com.cherrytest.shop.domain.model.OrderLine;
import com.cherrytest.shop.domain.port.OrderRepository;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

public final class PostgresOrderRepository implements OrderRepository {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public PostgresOrderRepository(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public Order place(OrderDraft draft) {
        try {
            return Objects.requireNonNull(transactions.execute(status -> placeInTransaction(draft)));
        } catch (TransactionException error) {
            throw Storage.unavailable("commit_order", error);
        }
    }

    private Order placeInTransaction(OrderDraft draft) {
        int closed = Storage.checked(
                "close_cart",
                () -> jdbc.sql("UPDATE cart SET STATUS = ? WHERE ID = ?::uuid AND STATUS = ?")
                        .param(Rows.CART_STATUS_CHECKED_OUT)
                        .param(draft.cartId().value())
                        .param(Rows.CART_STATUS_OPEN)
                        .update());
        Rows.closeOutcome(closed);
        for (OrderLine line : draft.lines()) {
            int quantity = Rows.quantityColumn(line.quantity().value());
            int reserved = Storage.checked(
                    "reserve_stock",
                    () -> jdbc.sql("UPDATE product SET STOCK = STOCK - ? WHERE SKU = ? AND STOCK >= ?")
                            .param(quantity)
                            .param(line.sku().value())
                            .param(quantity)
                            .update());
            Rows.reserveOutcome(reserved, line.sku());
        }
        long orderId = Storage.checked(
                "insert_order",
                () -> jdbc.sql("INSERT INTO customer_order (CART_ID, TOTAL_CENTS) VALUES (?::uuid, ?) RETURNING ID")
                        .param(draft.cartId().value())
                        .param(draft.total().cents())
                        .query(Long.class)
                        .single());
        for (OrderLine line : draft.lines()) {
            int quantity = Rows.quantityColumn(line.quantity().value());
            Storage.checked(
                    "insert_order_line",
                    () -> jdbc.sql("INSERT INTO customer_order_line (ORDER_ID, SKU, QUANTITY, UNIT_PRICE_CENTS)"
                                    + " VALUES (?, ?, ?, ?)")
                            .param(orderId)
                            .param(line.sku().value())
                            .param(quantity)
                            .param(line.unitPrice().cents())
                            .update());
        }
        return new Order(orderId, draft.cartId(), draft.lines(), draft.total());
    }
}
