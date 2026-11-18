package com.inventory.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A completed stock change; quantity is signed, balance is the resulting stock. */
public record StockMovement(UUID id, String sku, MovementType type, int quantity,
                            int balanceAfter, UUID actorId, String reason, Instant occurredAt) {
    public StockMovement {
        Objects.requireNonNull(id);
        sku = Product.normalizeSku(sku);
        Objects.requireNonNull(type);
        Objects.requireNonNull(actorId);
        Objects.requireNonNull(occurredAt);
        if (quantity == 0 || balanceAfter < 0) {
            throw new IllegalArgumentException("Movement quantity and balance are invalid");
        }
        if (((type == MovementType.RECEIPT || type == MovementType.RETURN) && quantity < 0)
                || (type == MovementType.SALE && quantity > 0)) {
            throw new IllegalArgumentException("Quantity direction must match movement type");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A stock movement reason is required");
        }
        reason = reason.strip();
    }
}
