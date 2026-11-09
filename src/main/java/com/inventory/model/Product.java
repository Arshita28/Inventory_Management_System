package com.inventory.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Objects;
import com.inventory.model.details.ProductDetails;
import com.inventory.model.details.LegacyDetails;

/** Immutable catalogue entry. Stock changes belong to InventoryService. */
public record Product(String sku, String name, String category,
                      BigDecimal sellingPrice, int reorderLevel, boolean active, ProductDetails details) {
    /** Compatibility constructor for pre-electronics records and the foundation demo. */
    public Product(String sku, String name, String category, BigDecimal sellingPrice, int reorderLevel, boolean active) {
        this(sku, name, category, sellingPrice, reorderLevel, active, new LegacyDetails(category));
    }

    public Product(String sku, String name, BigDecimal sellingPrice, int reorderLevel, boolean active, ProductDetails details) {
        this(sku, name, details.categoryLabel(), sellingPrice, reorderLevel, active, details);
    }

    public Product {
        sku = normalizeSku(sku);
        name = requireText(name, "Product name");
        category = requireText(category, "Category");
        Objects.requireNonNull(details, "Product details are required");
        if (!category.equals(details.categoryLabel())) {
            throw new IllegalArgumentException("Category must match the product detail class");
        }
        if (name.length() > 255 || category.length() > 255) {
            throw new IllegalArgumentException("Product name and category must be at most 255 characters");
        }
        Objects.requireNonNull(sellingPrice, "Selling price is required");
        if (sellingPrice.signum() < 0) {
            throw new IllegalArgumentException("Selling price cannot be negative");
        }
        sellingPrice = sellingPrice.stripTrailingZeros();
        if (sellingPrice.scale() > 2) {
            throw new ArithmeticException("Selling price must have at most two decimal places");
        }
        if ((long) sellingPrice.precision() - sellingPrice.scale() > 17) {
            throw new IllegalArgumentException("Selling price is too large");
        }
        sellingPrice = sellingPrice.setScale(2, RoundingMode.UNNECESSARY);
        if (reorderLevel < 0) {
            throw new IllegalArgumentException("Reorder level cannot be negative");
        }
    }

    public Product deactivate() {
        return new Product(sku, name, category, sellingPrice, reorderLevel, false, details);
    }

    public static String normalizeSku(String sku) {
        String normalized = requireText(sku, "SKU").toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9][A-Z0-9_-]{0,39}")) {
            throw new IllegalArgumentException("SKU must contain 1–40 letters, digits, hyphens or underscores");
        }
        return normalized;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.strip();
    }
}
