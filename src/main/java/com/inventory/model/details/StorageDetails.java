package com.inventory.model.details;

import com.inventory.model.ProductCategory;
import java.util.Map;

public record StorageDetails(int capacityGb, String storageInterface, String supportedDevices) implements ProductDetails {
    public StorageDetails {
        DetailValidation.positive(capacityGb, "Storage capacity");
        storageInterface = DetailValidation.text(storageInterface, "Storage interface");
        supportedDevices = DetailValidation.text(supportedDevices, "Supported devices");
    }

    @Override public ProductCategory category() { return ProductCategory.STORAGE; }
    @Override public Map<String, String> attributes() {
        return Map.of("capacityGb", String.valueOf(capacityGb), "storageInterface", storageInterface, "supportedDevices", supportedDevices);
    }
    @Override public String summary() { return capacityGb + " GB · " + storageInterface + " · " + supportedDevices; }
}
