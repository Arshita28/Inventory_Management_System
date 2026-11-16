package com.inventory;

import com.inventory.exception.AccessDeniedException;
import com.inventory.exception.InsufficientStockException;
import com.inventory.model.Product;
import com.inventory.model.details.ChargingDetails;
import com.inventory.repository.memory.InMemoryInventoryRepository;
import com.inventory.repository.jdbc.JdbcInventoryRepository;
import com.inventory.preview.DemoData;
import com.inventory.preview.LocalPreviewServer;
import com.inventory.security.AuthorizationService;
import com.inventory.security.Role;
import com.inventory.security.User;
import com.inventory.security.auth.AccessService;
import com.inventory.security.auth.JdbcAccountRepository;
import com.inventory.service.InventoryService;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;

/** Executable foundation demo; actors are fixtures, not an authentication system. */
public final class App {
    private App() { }

    public static void main(String[] args) throws Exception {
        if (Arrays.asList(args).contains("--serve")) {
            serve(args);
            return;
        }
        if (args.length != 0) {
            throw new IllegalArgumentException("Usage: java -jar inventory.jar [--serve [--seed-demo] [--demo-personas] [--port=8080]]");
        }
        InventoryService inventory = new InventoryService(new InMemoryInventoryRepository(),
                new AuthorizationService(), Clock.systemUTC());
        User manager = new User(UUID.randomUUID(), "demo-manager", Role.MANAGER, true);
        User cashier = new User(UUID.randomUUID(), "demo-cashier", Role.STAFF, true);

        System.out.println("Smart Inventory — Java foundation demo (in-memory, no login)");
        System.out.println("Company: VoltEdge Electronics");
        inventory.addProduct(manager, new Product("VE-CHG-DEMO", "65W USB-C fast charger",
                new BigDecimal("1499.00"), 5, true, new ChargingDetails("USB-C", 65, "USB Power Delivery devices")));
        inventory.receiveStock(manager, UUID.randomUUID(), "VE-CHG-DEMO", 10, "Opening stock");
        UUID saleRequest = UUID.randomUUID();
        inventory.sellStock(cashier, saleRequest, "VE-CHG-DEMO", 6, "Demo sale");
        inventory.sellStock(cashier, saleRequest, "VE-CHG-DEMO", 6, "Demo sale");
        System.out.println("Received 10, sold 6, retried same sale: "
                + inventory.getStock(manager, "VE-CHG-DEMO").quantity() + " units remain.");
        System.out.println("Low-stock products: " + inventory.lowStock(manager).size());

        try {
            inventory.receiveStock(cashier, UUID.randomUUID(), "VE-CHG-DEMO", 5, "Unauthorized receipt");
        } catch (AccessDeniedException exception) {
            System.out.println("Cashier receipt blocked: " + exception.getMessage());
        }
        try {
            inventory.sellStock(cashier, UUID.randomUUID(), "VE-CHG-DEMO", 5, "Oversale attempt");
        } catch (InsufficientStockException exception) {
            System.out.println("Overselling blocked: " + exception.getMessage());
        }
        inventory.history(manager, "VE-CHG-DEMO").forEach(movement ->
                System.out.printf("%s %+d -> balance %d (%s)%n", movement.type(),
                        movement.quantity(), movement.balanceAfter(), movement.reason()));
        System.out.println("Data is temporary and resets when the process exits.");
    }

    private static void serve(String[] args) throws Exception {
        int port = 8080;
        for (String argument : args) {
            if (argument.startsWith("--port=")) {
                port = Integer.parseInt(argument.substring("--port=".length()));
                if (port < 1 || port > 65535) {
                    throw new IllegalArgumentException("Port must be between 1 and 65535");
                }
            } else if (!argument.equals("--serve") && !argument.equals("--seed-demo") && !argument.equals("--demo-personas")) {
                throw new IllegalArgumentException("Unknown option: " + argument);
            }
        }
        Files.createDirectories(Path.of("data"));
        InventoryService inventory = new InventoryService(
                new JdbcInventoryRepository("jdbc:h2:file:./data/inventory;LOCK_TIMEOUT=10000"),
                new AuthorizationService(), Clock.systemUTC());
        if (Arrays.asList(args).contains("--seed-demo")) {
            DemoData.seed(inventory);
        }
        AccessService access = new AccessService(new JdbcAccountRepository("jdbc:h2:file:./data/inventory;LOCK_TIMEOUT=10000"), Clock.systemUTC());
        boolean demoPersonas = Arrays.asList(args).contains("--demo-personas");
        if (demoPersonas) access.installDemoPersonas();
        LocalPreviewServer preview = new LocalPreviewServer(inventory, access, port, demoPersonas);
        Runtime.getRuntime().addShutdownHook(new Thread(preview::close));
        preview.start();
        System.out.println("Inventory preview: http://localhost:" + preview.port());
        System.out.println("Authenticated local workspace; data saved in ./data/inventory.mv.db");
        new CountDownLatch(1).await();
    }
}
