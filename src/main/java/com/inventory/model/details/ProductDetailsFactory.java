package com.inventory.model.details;

import com.inventory.model.ProductCategory;
import java.util.Map;

/** Explicit construction keeps HTTP parsing and persistence free of reflection. */
public final class ProductDetailsFactory {
    private ProductDetailsFactory() { }

    public static ProductDetails create(ProductCategory category, Map<String, String> fields) {
        return switch (category) {
            case CHARGING -> new ChargingDetails(text(fields, "connector"), number(fields, "wattage"), text(fields, "compatibility"));
            case AUDIO -> new AudioDetails(AudioDetails.Connection.valueOf(text(fields, "connection")),
                    bool(fields, "microphone"), number(fields, "batteryHours"));
            case COMPUTER_ACCESSORIES -> new ComputerAccessoryDetails(text(fields, "accessoryType"), text(fields, "connection"), text(fields, "supportedOs"));
            case STORAGE -> new StorageDetails(number(fields, "capacityGb"), text(fields, "storageInterface"), text(fields, "supportedDevices"));
            case PHONE_ACCESSORIES -> new PhoneAccessoryDetails(PhoneAccessoryDetails.Type.valueOf(text(fields, "accessoryType")),
                    bool(fields, "universal"), fields.getOrDefault("supportedModels", ""));
            case POWER -> new PowerDetails(PowerDetails.Type.valueOf(text(fields, "deviceType")),
                    number(fields, "batteryMah"), number(fields, "socketCount"), number(fields, "ratedLoadWatts"));
            case LEGACY -> throw new IllegalArgumentException("Choose one of the six electronics categories");
        };
    }

    private static String text(Map<String, String> fields, String name) {
        return DetailValidation.text(fields.get(name), name);
    }

    private static int number(Map<String, String> fields, String name) {
        return com.inventory.validation.FieldTypes.integer(text(fields,name),com.inventory.validation.FieldTypes.label(name));
    }

    private static boolean bool(Map<String, String> fields, String name) {
        String value = text(fields, name);
        if (!value.equals("true") && !value.equals("false")) {
            throw new IllegalArgumentException(name + " must be true or false");
        }
        return Boolean.parseBoolean(value);
    }
}
