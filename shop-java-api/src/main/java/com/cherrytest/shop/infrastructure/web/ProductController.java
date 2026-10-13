package com.cherrytest.shop.infrastructure.web;

import com.cherrytest.shop.application.CatalogService;
import com.cherrytest.shop.domain.model.Sku;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Catalog")
class ProductController {
    private final CatalogService catalog;

    ProductController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/v1/products")
    @Operation(summary = "Lists the catalog")
    Dtos.ProductListResponse listProducts() {
        List<Dtos.ProductResponse> items =
                catalog.listProducts().stream().map(Dtos.ProductResponse::from).toList();
        return new Dtos.ProductListResponse(items, items.size());
    }

    @GetMapping("/v1/products/{sku}")
    @Operation(summary = "Finds a product by SKU, e.g. MUG-STONE")
    Dtos.ProductResponse findProduct(@PathVariable String sku) {
        return Dtos.ProductResponse.from(catalog.findProduct(Sku.parse(sku)));
    }
}
