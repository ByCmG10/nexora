package com.nexora.product;

import java.math.BigDecimal;

/** What the HTTP API returns for a product; the {@link Product} entity itself never leaves the service layer. */
public record ProductResponse(Long id, String name, String sku, BigDecimal price, Integer stock,
        Integer minimumStock) {

    public static ProductResponse from(Product product) {
        return new ProductResponse(product.getId(), product.getName(), product.getSku(), product.getPrice(),
                product.getStock(), product.getMinimumStock());
    }
}
