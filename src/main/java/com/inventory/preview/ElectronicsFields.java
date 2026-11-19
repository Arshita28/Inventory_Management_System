package com.inventory.preview;

import com.inventory.model.ProductCategory;
import java.util.Map;

/** Category-specific HTML inputs. Domain constructors remain the validation authority. */
final class ElectronicsFields {
    private ElectronicsFields() { }

    static String render(ProductCategory category, Map<String, String> fields) {
        return switch (category) {
            case CHARGING -> text("Connector", "connector", fields, true)
                    + number("Supported wattage (W)", "wattage", fields, 1)
                    + text("Device compatibility", "compatibility", fields, true);
            case AUDIO -> select("Audio connection", "connection", fields, "WIRED", "WIRELESS")
                    + select("Microphone", "microphone", fields, "false", "true")
                    + number("Battery hours (0 for wired)", "batteryHours", fields, 0);
            case COMPUTER_ACCESSORIES -> "<label>Product type<input required type='text' name='accessoryType' maxlength='255' placeholder='e.g. Monitor, keyboard, mouse' value='"
                    + InventoryPage.html(fields.getOrDefault("accessoryType", "")) + "'></label>"
                    + "<label>Ports / connection (optional)<input type='text' name='connection' maxlength='255' placeholder='e.g. HDMI, DisplayPort, USB-C' value='"
                    + InventoryPage.html(fields.getOrDefault("connection", "")) + "'></label>"
                    + "<input type='hidden' name='supportedOs' value='" + InventoryPage.html(fields.getOrDefault("supportedOs", "")) + "'>";
            case STORAGE -> number("Capacity (GB)", "capacityGb", fields, 1)
                    + text("Storage interface", "storageInterface", fields, true)
                    + text("Supported devices", "supportedDevices", fields, true);
            case PHONE_ACCESSORIES -> select("Phone accessory type", "accessoryType", fields, "CASE", "SCREEN_PROTECTOR", "STAND")
                    + select("Universal (stands only)", "universal", fields, "false", "true")
                    + text("Supported phone models (optional for universal stand)", "supportedModels", fields, false);
            case POWER -> select("Power device type", "deviceType", fields, "POWER_BANK", "POWER_STRIP")
                    + number("Battery capacity (mAh; 0 for strip)", "batteryMah", fields, 0)
                    + number("Socket count (0 for bank)", "socketCount", fields, 0)
                    + number("Rated load (W; 0 for bank)", "ratedLoadWatts", fields, 0);
            case LEGACY -> "";
        };
    }

    private static String text(String label, String key, Map<String, String> fields, boolean required) {
        return "<label>" + label + "<input " + (required ? "required " : "") + "type='text' name='" + key
                + "' maxlength='255' value='" + InventoryPage.html(fields.getOrDefault(key, "")) + "'></label>";
    }

    private static String number(String label, String key, Map<String, String> fields, int minimum) {
        return NumericFields.render(label,key,fields.getOrDefault(key,minimum==0?"0":""),"min='"+minimum+"' max='2147483647' step='1'");
    }

    private static String select(String label, String key, Map<String, String> fields, String... options) {
        StringBuilder input = new StringBuilder("<label>" + label + "<select required name='" + key + "'>");
        for (String value : options) {
            input.append("<option value='").append(value).append("'")
                    .append(value.equals(fields.getOrDefault(key, options[0])) ? " selected" : "")
                    .append('>').append(value.replace('_', ' ')).append("</option>");
        }
        return input.append("</select></label>").toString();
    }
}
