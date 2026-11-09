package com.inventory.model.procurement;

public enum OrderStatus {
    DRAFT, PENDING_APPROVAL, APPROVED, PARTIALLY_RECEIVED, RECEIVED, REJECTED, CANCELLED;

    public boolean isOpen() {
        return this == DRAFT || this == PENDING_APPROVAL || this == APPROVED || this == PARTIALLY_RECEIVED;
    }
}
