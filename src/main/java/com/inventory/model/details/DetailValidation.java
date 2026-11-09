package com.inventory.model.details;

final class DetailValidation {
    private DetailValidation() { }

    static String text(String value, String field) {
        if (value == null || value.isBlank() || value.strip().length() > 255) {
            throw new IllegalArgumentException(field + " must contain 1–255 characters");
        }
        return value.strip();
    }

    static int positive(int value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }
}
