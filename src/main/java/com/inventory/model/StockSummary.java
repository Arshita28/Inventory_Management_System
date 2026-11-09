package com.inventory.model;

import java.util.Objects;

public record StockSummary(Product product, int quantity) {
    public StockSummary {
        Objects.requireNonNull(product);
        if (quantity < 0) {
            throw new IllegalArgumentException("Stock cannot be negative");
        }
    }

    public boolean isLowStock() {
        return product.active() && quantity <= product.reorderLevel();
    }
}
