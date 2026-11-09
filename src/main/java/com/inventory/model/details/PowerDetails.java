package com.inventory.model.details;

import com.inventory.model.ProductCategory;
import java.util.Map;
import java.util.Objects;

public record PowerDetails(Type deviceType, int batteryMah, int socketCount, int ratedLoadWatts) implements ProductDetails {
    public enum Type { POWER_BANK, POWER_STRIP }

    public PowerDetails {
        Objects.requireNonNull(deviceType, "Power device type is required");
        if (deviceType == Type.POWER_BANK) {
            DetailValidation.positive(batteryMah, "Battery capacity");
            if (socketCount != 0 || ratedLoadWatts != 0) {
                throw new IllegalArgumentException("A power bank must have zero socket count and rated load");
            }
        } else {
            DetailValidation.positive(socketCount, "Socket count");
            DetailValidation.positive(ratedLoadWatts, "Rated load");
            if (batteryMah != 0) {
                throw new IllegalArgumentException("A power strip must have zero battery capacity");
            }
        }
    }

    @Override public ProductCategory category() { return ProductCategory.POWER; }
    @Override public Map<String, String> attributes() {
        return Map.of("deviceType", deviceType.name(), "batteryMah", String.valueOf(batteryMah),
                "socketCount", String.valueOf(socketCount), "ratedLoadWatts", String.valueOf(ratedLoadWatts));
    }
    @Override public String summary() {
        return deviceType == Type.POWER_BANK ? "Power bank · " + batteryMah + " mAh"
                : "Power strip · " + socketCount + " sockets · " + ratedLoadWatts + " W";
    }
}
