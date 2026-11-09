package com.inventory.exception;

public final class InsufficientStockException extends InventoryException {
    public InsufficientStockException(String sku, int available, int requested) {
        super("Insufficient stock for " + sku + ": available " + available + ", requested " + requested);
    }
}
