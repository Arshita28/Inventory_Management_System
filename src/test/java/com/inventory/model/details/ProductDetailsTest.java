package com.inventory.model.details;

import com.inventory.model.Product;
import com.inventory.model.ProductCategory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProductDetailsTest {
    @Test
    void sixDetailClassesExposeTheirCategoryAndPolymorphicSummary() {
        List<ProductDetails> details = List.of(
                new ChargingDetails("USB-C", 65, "Laptops"),
                new AudioDetails(AudioDetails.Connection.WIRELESS, true, 30),
                new ComputerAccessoryDetails("Mouse", "USB", "Windows"),
                new StorageDetails(512, "USB-C", "Computers"),
                new PhoneAccessoryDetails(PhoneAccessoryDetails.Type.CASE, false, "Pixel 9"),
                new PowerDetails(PowerDetails.Type.POWER_BANK, 20000, 0, 0));
        assertEquals(6, ProductCategory.electronics().size());
        for (ProductDetails value : details) {
            Product product = new Product("ITEM", "Item", BigDecimal.TEN, 2, true, value);
            assertEquals(value.category().label(), product.category());
            assertFalse(product.details().summary().isBlank());
            assertEquals(value, ProductDetailsFactory.create(value.category(), value.attributes()));
            assertEquals(value, product.deactivate().details());
            assertThrows(UnsupportedOperationException.class, () -> value.attributes().put("x", "y"));
        }
    }

    @Test
    void rejectsInvalidChargingStorageAndComputerDetails() {
        assertThrows(IllegalArgumentException.class, () -> new ChargingDetails("USB-C", 0, "Laptop"));
        assertThrows(IllegalArgumentException.class, () -> new ChargingDetails(" ", 65, "Laptop"));
        assertThrows(IllegalArgumentException.class, () -> new StorageDetails(-1, "USB", "Laptop"));
        assertThrows(IllegalArgumentException.class, () -> new ComputerAccessoryDetails("", "USB", ""));
        assertThrows(IllegalArgumentException.class, () -> new StorageDetails(512, "x".repeat(256), "Laptop"));
    }

    @Test
    void monitorCanBeCreatedWithoutOperatingSystemOrConnection() {
        ProductDetails monitor = ProductDetailsFactory.create(ProductCategory.COMPUTER_ACCESSORIES,
                Map.of("accessoryType", "Monitor"));
        assertEquals("Monitor", monitor.summary());
        assertEquals(monitor, ProductDetailsFactory.create(monitor.category(), monitor.attributes()));
        assertThrows(IllegalArgumentException.class,
                () -> new ComputerAccessoryDetails("Monitor", "x".repeat(256), ""));
    }

    @Test
    void validatesAudioBatteryAgainstConnectionType() {
        assertThrows(IllegalArgumentException.class, () -> new AudioDetails(AudioDetails.Connection.WIRED, true, 10));
        assertThrows(IllegalArgumentException.class, () -> new AudioDetails(AudioDetails.Connection.WIRELESS, true, 0));
        assertEquals(0, new AudioDetails(AudioDetails.Connection.WIRED, true, 0).batteryHours());
    }

    @Test
    void onlyStandsCanHaveUniversalPhoneCompatibility() {
        assertThrows(IllegalArgumentException.class, () -> new PhoneAccessoryDetails(PhoneAccessoryDetails.Type.CASE, true, ""));
        assertThrows(IllegalArgumentException.class, () -> new PhoneAccessoryDetails(PhoneAccessoryDetails.Type.CASE, false, ""));
        assertEquals("Universal", new PhoneAccessoryDetails(PhoneAccessoryDetails.Type.STAND, true, "").supportedModels());
    }

    @Test
    void validatesPowerBankAndPowerStripFieldsSeparately() {
        assertThrows(IllegalArgumentException.class, () -> new PowerDetails(PowerDetails.Type.POWER_BANK, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new PowerDetails(PowerDetails.Type.POWER_BANK, 10000, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new PowerDetails(PowerDetails.Type.POWER_STRIP, 10000, 4, 2000));
        assertThrows(IllegalArgumentException.class, () -> new PowerDetails(PowerDetails.Type.POWER_STRIP, 0, 0, 2000));
        assertTrue(new PowerDetails(PowerDetails.Type.POWER_STRIP, 0, 4, 2000).summary().contains("4 sockets"));
    }

    @Test
    void factoryRejectsMissingMalformedAndLegacyDetails() {
        ProductDetails charging = ProductDetailsFactory.create(ProductCategory.CHARGING, Map.of());
        assertEquals("Charging", charging.summary());
        assertEquals(charging, ProductDetailsFactory.create(charging.category(), charging.attributes()));
        assertThrows(IllegalArgumentException.class, () -> ProductDetailsFactory.create(ProductCategory.LEGACY, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> ProductDetailsFactory.create(ProductCategory.AUDIO,
                Map.of("connection", "WIRED", "microphone", "yes", "batteryHours", "0")));
        assertThrows(IllegalArgumentException.class, () -> new Product("SKU", "Item", "Audio", BigDecimal.TEN,
                0, true, new ChargingDetails("USB-C", 65, "Laptop")));
    }
}
