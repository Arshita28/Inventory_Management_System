package com.inventory.model.details;

import com.inventory.model.ProductCategory;
import java.util.Map;

public record ChargingDetails(String connector, int wattage, String compatibility) implements ProductDetails {
    public ChargingDetails {
        // An unconfigured product needs no technical specifications to join the catalogue.
        if (wattage == 0 && (connector == null || connector.isBlank()) && (compatibility == null || compatibility.isBlank())) {
            connector = "";
            compatibility = "";
        } else {
            connector = DetailValidation.text(connector, "Connector");
            DetailValidation.positive(wattage, "Supported wattage");
            compatibility = DetailValidation.text(compatibility, "Device compatibility");
        }
    }

    @Override public ProductCategory category() { return ProductCategory.CHARGING; }
    @Override public Map<String, String> attributes() {
        return Map.of("connector", connector, "wattage", String.valueOf(wattage), "compatibility", compatibility);
    }
    @Override public String summary() { return wattage == 0 ? "Charging" : connector + " · " + wattage + " W · " + compatibility; }
}
