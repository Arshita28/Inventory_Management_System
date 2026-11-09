package com.inventory.model.details;

import com.inventory.model.ProductCategory;
import java.util.Map;

public record ComputerAccessoryDetails(String accessoryType, String connection, String supportedOs) implements ProductDetails {
    public ComputerAccessoryDetails {
        accessoryType = DetailValidation.text(accessoryType, "Accessory type");
        connection = DetailValidation.text(connection, "Connection");
        supportedOs = DetailValidation.text(supportedOs, "Supported operating systems");
    }

    @Override public ProductCategory category() { return ProductCategory.COMPUTER_ACCESSORIES; }
    @Override public Map<String, String> attributes() {
        return Map.of("accessoryType", accessoryType, "connection", connection, "supportedOs", supportedOs);
    }
    @Override public String summary() { return accessoryType + " · " + connection + " · " + supportedOs; }
}
