package com.inventory.model.details;

import com.inventory.model.ProductCategory;
import java.util.Map;

/** Preserves pre-electronics records without guessing specifications or deleting history. */
public record LegacyDetails(String originalCategory) implements ProductDetails {
    public LegacyDetails {
        originalCategory = DetailValidation.text(originalCategory, "Original category");
    }

    @Override public ProductCategory category() { return ProductCategory.LEGACY; }
    @Override public String categoryLabel() { return originalCategory; }
    @Override public Map<String, String> attributes() { return Map.of(); }
    @Override public String summary() { return "Legacy record · specifications not yet classified"; }
}
