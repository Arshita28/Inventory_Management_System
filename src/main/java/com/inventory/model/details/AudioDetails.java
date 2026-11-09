package com.inventory.model.details;

import com.inventory.model.ProductCategory;
import java.util.Map;
import java.util.Objects;

public record AudioDetails(Connection connection, boolean microphone, int batteryHours) implements ProductDetails {
    public enum Connection { WIRED, WIRELESS }

    public AudioDetails {
        Objects.requireNonNull(connection, "Audio connection is required");
        if ((connection == Connection.WIRED && batteryHours != 0)
                || (connection == Connection.WIRELESS && batteryHours <= 0)) {
            throw new IllegalArgumentException("Wired audio uses zero battery hours; wireless audio needs positive battery hours");
        }
    }

    @Override public ProductCategory category() { return ProductCategory.AUDIO; }
    @Override public Map<String, String> attributes() {
        return Map.of("connection", connection.name(), "microphone", String.valueOf(microphone), "batteryHours", String.valueOf(batteryHours));
    }
    @Override public String summary() {
        return connection + " · " + (microphone ? "Microphone" : "No microphone")
                + (connection == Connection.WIRELESS ? " · " + batteryHours + " h battery" : "");
    }
}
