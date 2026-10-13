package com.cherrytest.shop.application;

import com.cherrytest.shop.domain.model.DomainError;
import com.cherrytest.shop.domain.model.Product;
import com.cherrytest.shop.domain.model.Sku;
import com.cherrytest.shop.domain.port.ProductRepository;
import java.util.List;

public final class CatalogService {
    private final ProductRepository products;

    public CatalogService(ProductRepository products) {
        this.products = products;
    }

    public List<Product> listProducts() {
        return ShopException.translated(products::list);
    }

    public Product findProduct(Sku sku) {
        return ShopException.translated(() -> products.findBySku(sku))
                .orElseThrow(() -> ShopException.domain(new DomainError.ProductNotFound(sku)));
    }
}
