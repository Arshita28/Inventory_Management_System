package com.inventory.exception;

/** Storage failure, kept separate from expected domain conflicts. */
public final class PersistenceException extends RuntimeException {
    public PersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
