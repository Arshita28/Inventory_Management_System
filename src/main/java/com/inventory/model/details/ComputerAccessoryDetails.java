package com.inventory.model.details;

import com.inventory.model.ProductCategory;
import java.util.Map;

public record ComputerAccessoryDetails(String accessoryType, String connection, String supportedOs) implements ProductDetails {
    public ComputerAccessoryDetails {
        accessoryType = DetailValidation.text(accessoryType, "Accessory type");
        connection = optionalText(connection, "Ports / connection");
        supportedOs = optionalText(supportedOs, "Supported operating systems");
    }

    private static String optionalText(String value, String label) {
        return value == null || value.isBlank() ? "" : DetailValidation.text(value, label);
    }

    @Override public ProductCategory category() { return ProductCategory.COMPUTER_ACCESSORIES; }
    @Override public Map<String, String> attributes() {
        return Map.of("accessoryType", accessoryType, "connection", connection, "supportedOs", supportedOs);
    }
    @Override public String summary() { return java.util.stream.Stream.of(accessoryType, connection, supportedOs)
            .filter(value -> !value.isBlank()).collect(java.util.stream.Collectors.joining(" · ")); }
}
