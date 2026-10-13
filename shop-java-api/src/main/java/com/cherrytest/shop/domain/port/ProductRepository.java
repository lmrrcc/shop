package com.cherrytest.shop.domain.port;

import com.cherrytest.shop.domain.model.Product;
import com.cherrytest.shop.domain.model.Sku;
import java.util.List;
import java.util.Optional;

public interface ProductRepository {
    List<Product> list();

    Optional<Product> findBySku(Sku sku);

    List<Product> findBySkus(List<Sku> skus);
}
