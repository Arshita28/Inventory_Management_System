package com.inventory.preview;

import com.inventory.model.Product;
import com.inventory.model.details.ProductDetails;
import com.inventory.model.details.ChargingDetails;
import com.inventory.model.details.AudioDetails;
import com.inventory.model.details.ComputerAccessoryDetails;
import com.inventory.model.details.StorageDetails;
import com.inventory.model.details.PhoneAccessoryDetails;
import com.inventory.model.details.PowerDetails;
import com.inventory.security.Role;
import com.inventory.security.User;
import com.inventory.service.InventoryService;
import com.inventory.model.procurement.SupplierOffer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Repeatable VoltEdge fixtures, kept separate from company and project identity. */
public final class DemoData {
    public static final User MANAGER = new User(id("manager"), "demo-manager", Role.MANAGER, true);
    public static final User CASHIER = new User(id("cashier"), "demo-cashier", Role.STAFF, true);
    public static final User APPROVER = new User(id("approver"), "demo-approver", Role.MANAGER, true);

    private DemoData() { }

    public static void seed(InventoryService inventory) {
        seedProduct(inventory, "VE-CHG-001", "65W USB-C fast charger", "1499.00", 5, 10, 6,
                new ChargingDetails("USB-C", 65, "USB Power Delivery phones and laptops"));
        seedProduct(inventory, "VE-AUD-001", "Wireless over-ear headphones", "2499.00", 4, 20, 5,
                new AudioDetails(AudioDetails.Connection.WIRELESS, true, 30));
        seedProduct(inventory, "VE-CMP-001", "Wireless compact keyboard", "1299.00", 6, 30, 12,
                new ComputerAccessoryDetails("Keyboard", "Bluetooth", "Windows, macOS, Linux"));
        seedProduct(inventory, "VE-STO-001", "512 GB portable SSD", "3999.00", 3, 12, 4,
                new StorageDetails(512, "USB-C / USB 3.2", "Windows and macOS computers"));
        seedProduct(inventory, "VE-PHN-001", "Clear phone case", "399.00", 8, 25, 20,
                new PhoneAccessoryDetails(PhoneAccessoryDetails.Type.CASE, false, "Pixel 9"));
        seedProduct(inventory, "VE-PWR-001", "20000 mAh power bank", "1999.00", 4, 15, 5,
                new PowerDetails(PowerDetails.Type.POWER_BANK, 20000, 0, 0));
        String[] skus = {"VE-CHG-001", "VE-AUD-001", "VE-CMP-001", "VE-STO-001", "VE-PHN-001", "VE-PWR-001"};
        String[] costs = {"900.00", "1600.00", "800.00", "2800.00", "180.00", "1300.00"};
        for (int index = 0; index < skus.length; index++) {
            if (!inventory.getStock(MANAGER, skus[index]).product().active()) continue;
            inventory.operations().addOffer(MANAGER, new SupplierOffer(id(skus[index] + ":offer"), skus[index],
                    index % 2 == 0 ? "CircuitLink Distributors (demo)" : "NovaTech Wholesale (demo)", new BigDecimal(costs[index]), 4, 2));
        }
    }

    public static User actor(String key) {
        return switch (key) {
            case "approver" -> APPROVER;
            case "cashier" -> CASHIER;
            default -> MANAGER;
        };
    }

    public static String actorName(UUID id) {
        for (User actor : java.util.List.of(MANAGER, APPROVER, CASHIER)) {
            if (actor.id().equals(id)) return actor.username();
        }
        return id.toString().substring(0, 8);
    }

    private static void seedProduct(InventoryService inventory, String sku, String name,
                                    String price, int reorderLevel, int received, int sold, ProductDetails details) {
        boolean exists = inventory.listProducts(MANAGER).stream()
                .anyMatch(stock -> stock.product().sku().equals(sku));
        if (!exists) {
            inventory.addProduct(MANAGER, new Product(sku, name, new BigDecimal(price), reorderLevel, true, details));
        }
        inventory.receiveStock(MANAGER, id(sku + ":receipt"), sku, received, "Demo: opening delivery");
        inventory.sellStock(CASHIER, id(sku + ":sale"), sku, sold, "Demo: counter sale");
    }

    private static UUID id(String key) {
        return UUID.nameUUIDFromBytes(("inventory-demo:" + key).getBytes(StandardCharsets.UTF_8));
    }
}
