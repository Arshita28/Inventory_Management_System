package com.inventory.exception;

/** A business conflict that can be presented to a user. */
public class InventoryException extends RuntimeException {
    public InventoryException(String message) {
        super(message);
    }
}
