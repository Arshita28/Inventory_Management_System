package com.inventory.model;

import java.util.Arrays;
import java.util.List;

public enum ProductCategory {
    CHARGING("Charging"), AUDIO("Audio"), COMPUTER_ACCESSORIES("Computer accessories"),
    STORAGE("Storage"), PHONE_ACCESSORIES("Phone accessories"), POWER("Power"),
    LEGACY("Legacy items");

    private final String label;

    ProductCategory(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Six categories for new electronics; LEGACY is only for existing records. */
    public static List<ProductCategory> electronics() {
        return Arrays.stream(values()).filter(category -> category != LEGACY).toList();
    }
}
