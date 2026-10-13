package com.cherrytest.shop.infrastructure.postgres;

import com.cherrytest.shop.domain.model.Product;
import com.cherrytest.shop.domain.model.Sku;
import com.cherrytest.shop.domain.port.ProductRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class PostgresProductRepository implements ProductRepository {
    private static final String COLUMNS = "SELECT SKU, NAME, PRICE_CENTS, DISCOUNT_PERCENT, STOCK FROM product";
    private final JdbcClient jdbc;

    public PostgresProductRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Product> list() {
        return Storage.checked(
                        "list_products",
                        () -> jdbc.sql(COLUMNS + " ORDER BY SKU").query(Rows.ProductRow.class).list())
                .stream()
                .map(Rows::product)
                .toList();
    }

    @Override
    public Optional<Product> findBySku(Sku sku) {
        return Storage.checked(
                        "find_product",
                        () -> jdbc.sql(COLUMNS + " WHERE SKU = ?")
                                .param(sku.value())
                                .query(Rows.ProductRow.class)
                                .optional())
                .map(Rows::product);
    }

    @Override
    public List<Product> findBySkus(List<Sku> skus) {
        String[] values = skus.stream().map(Sku::value).toArray(String[]::new);
        return Storage.checked(
                        "find_products",
                        () -> jdbc.sql(COLUMNS + " WHERE SKU = ANY(?)")
                                .param(values)
                                .query(Rows.ProductRow.class)
                                .list())
                .stream()
                .map(Rows::product)
                .toList();
    }
}
