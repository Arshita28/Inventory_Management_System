package com.inventory.model.procurement;

import com.inventory.model.Product;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

/** A supplier's product quote. Existing orders retain their own quote snapshot. */
public record SupplierOffer(UUID id, String sku, String supplierName, BigDecimal unitCost,
                            int leadDays, int minimumQuantity) {
    public SupplierOffer {
        Objects.requireNonNull(id);
        sku = Product.normalizeSku(sku);
        if (supplierName == null || supplierName.isBlank() || supplierName.strip().length() > 255) {
            throw new IllegalArgumentException("Supplier name must contain 1–255 characters");
        }
        supplierName = supplierName.strip();
        Objects.requireNonNull(unitCost);
        unitCost = unitCost.stripTrailingZeros();
        if (unitCost.signum() < 0 || unitCost.scale() > 2 || (long) unitCost.precision() - unitCost.scale() > 17) {
            throw new IllegalArgumentException("Unit cost must be nonnegative, within 17 whole digits, and have at most two decimal places");
        }
        unitCost = unitCost.setScale(2, RoundingMode.UNNECESSARY);
        if (leadDays < 0 || leadDays > 365 || minimumQuantity <= 0) {
            throw new IllegalArgumentException("Lead days must be 0–365 and minimum quantity must be positive");
        }
    }
}
