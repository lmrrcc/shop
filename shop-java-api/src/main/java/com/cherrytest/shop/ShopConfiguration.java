package com.cherrytest.shop;

import com.cherrytest.shop.application.CartService;
import com.cherrytest.shop.application.CatalogService;
import com.cherrytest.shop.domain.port.CartRepository;
import com.cherrytest.shop.domain.port.OrderRepository;
import com.cherrytest.shop.domain.port.ProductRepository;
import com.cherrytest.shop.infrastructure.postgres.PostgresCartRepository;
import com.cherrytest.shop.infrastructure.postgres.PostgresOrderRepository;
import com.cherrytest.shop.infrastructure.postgres.PostgresProductRepository;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
class ShopConfiguration {
    @Bean
    JdbcClient jdbcClient(DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    @Bean
    TransactionTemplate transactionTemplate(DataSource dataSource) {
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Bean
    ProductRepository productRepository(JdbcClient jdbc) {
        return new PostgresProductRepository(jdbc);
    }

    @Bean
    CartRepository cartRepository(JdbcClient jdbc) {
        return new PostgresCartRepository(jdbc);
    }

    @Bean
    OrderRepository orderRepository(JdbcClient jdbc, TransactionTemplate transactions) {
        return new PostgresOrderRepository(jdbc, transactions);
    }

    @Bean
    CatalogService catalogService(ProductRepository products) {
        return new CatalogService(products);
    }

    @Bean
    CartService cartService(ProductRepository products, CartRepository carts, OrderRepository orders) {
        return new CartService(products, carts, orders);
    }
}
