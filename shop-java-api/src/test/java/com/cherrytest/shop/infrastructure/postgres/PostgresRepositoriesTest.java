package com.cherrytest.shop.infrastructure.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cherrytest.shop.domain.model.Cart;
import com.cherrytest.shop.domain.model.CartLine;
import com.cherrytest.shop.domain.model.CartStatus;
import com.cherrytest.shop.domain.model.DomainError;
import com.cherrytest.shop.domain.model.Order;
import com.cherrytest.shop.domain.model.Quantity;
import com.cherrytest.shop.domain.model.Sku;
import com.cherrytest.shop.domain.port.RepositoryException;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

class PostgresRepositoriesTest {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    private static PostgresProductRepository products;
    private static PostgresCartRepository carts;
    private static PostgresOrderRepository orders;

    @BeforeAll
    static void start_postgres_and_migrate() {
        POSTGRES.start();
        DataSource pool = PostgresConnection.connect("postgres://" + POSTGRES.getUsername() + ":"
                + POSTGRES.getPassword() + "@" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/"
                + POSTGRES.getDatabaseName());
        JdbcClient jdbc = JdbcClient.create(pool);
        products = new PostgresProductRepository(jdbc);
        carts = new PostgresCartRepository(jdbc);
        orders = new PostgresOrderRepository(jdbc, new TransactionTemplate(new DataSourceTransactionManager(pool)));
    }

    @AfterAll
    static void stop_postgres() {
        POSTGRES.stop();
    }

    @Test
    void migrations_seed_the_discounted_catalog_in_sku_order() {
        assertThat(products.list())
                .extracting(product -> product.sku().value() + ":" + product.discountPercent().value())
                .containsExactly("LAMP-DESK:0", "MUG-STONE:10", "NB-DOT-A5:0", "TEE-CREW-M:0");
    }

    @Test
    void a_saved_line_is_read_back_with_the_cart() {
        Cart cart = carts.create();
        carts.saveLine(cart.id(), new CartLine(Sku.parse("NB-DOT-A5"), Quantity.of(2)));
        assertThat(carts.find(cart.id()).orElseThrow().lines())
                .containsExactly(new CartLine(Sku.parse("NB-DOT-A5"), Quantity.of(2)));
    }

    @Test
    void placing_an_order_closes_the_cart_and_reserves_stock() {
        Cart cart = carts.create();
        carts.saveLine(cart.id(), new CartLine(Sku.parse("TEE-CREW-M"), Quantity.of(3)));
        long before = products.findBySku(Sku.parse("TEE-CREW-M")).orElseThrow().stock();
        Cart open = carts.find(cart.id()).orElseThrow();
        Order order = orders.place(open.checkout(products.findBySkus(List.of(Sku.parse("TEE-CREW-M")))));
        assertThat(order.total().cents()).isEqualTo(8700);
        assertThat(carts.find(cart.id()).orElseThrow().status()).isEqualTo(CartStatus.CHECKED_OUT);
        assertThat(products.findBySku(Sku.parse("TEE-CREW-M")).orElseThrow().stock()).isEqualTo(before - 3);
    }

    @Test
    void a_second_order_for_the_same_cart_is_rejected_and_rolled_back() {
        Cart cart = carts.create();
        carts.saveLine(cart.id(), new CartLine(Sku.parse("MUG-STONE"), Quantity.of(1)));
        Cart open = carts.find(cart.id()).orElseThrow();
        var draft = open.checkout(products.findBySkus(List.of(Sku.parse("MUG-STONE"))));
        orders.place(draft);
        long stock = products.findBySku(Sku.parse("MUG-STONE")).orElseThrow().stock();
        assertThatThrownBy(() -> orders.place(draft))
                .isInstanceOfSatisfying(
                        RepositoryException.Rejected.class,
                        error -> assertThat(error.error()).isEqualTo(new DomainError.CartClosed()));
        assertThat(products.findBySku(Sku.parse("MUG-STONE")).orElseThrow().stock()).isEqualTo(stock);
    }
}
