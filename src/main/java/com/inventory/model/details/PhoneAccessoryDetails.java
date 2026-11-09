package com.inventory.model.details;

import com.inventory.model.ProductCategory;
import java.util.Map;
import java.util.Objects;

public record PhoneAccessoryDetails(Type accessoryType, boolean universal, String supportedModels) implements ProductDetails {
    public enum Type { CASE, SCREEN_PROTECTOR, STAND }

    public PhoneAccessoryDetails {
        Objects.requireNonNull(accessoryType, "Phone accessory type is required");
        if (universal && accessoryType != Type.STAND) {
            throw new IllegalArgumentException("Only a phone stand can be marked universal");
        }
        supportedModels = universal ? "Universal" : DetailValidation.text(supportedModels, "Supported phone models");
    }

    @Override public ProductCategory category() { return ProductCategory.PHONE_ACCESSORIES; }
    @Override public Map<String, String> attributes() {
        return Map.of("accessoryType", accessoryType.name(), "universal", String.valueOf(universal), "supportedModels", supportedModels);
    }
    @Override public String summary() { return accessoryType + " · " + supportedModels; }
}
