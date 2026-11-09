package com.inventory.repository.jdbc;

import com.inventory.exception.InsufficientStockException;
import com.inventory.exception.InventoryException;
import com.inventory.exception.PersistenceException;
import com.inventory.model.Product;
import com.inventory.model.ProductCategory;
import com.inventory.model.details.ChargingDetails;
import com.inventory.model.details.LegacyDetails;
import com.inventory.model.details.StorageDetails;
import com.inventory.preview.DemoData;
import com.inventory.security.AuthorizationService;
import com.inventory.security.Role;
import com.inventory.security.User;
import com.inventory.service.InventoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class JdbcInventoryRepositoryTest {
    @TempDir
    Path temporary;
    private final User manager = new User(UUID.randomUUID(), "manager", Role.MANAGER, true);

    @Test
    void fileStorageAndRetryProtectionSurviveReopening() {
        String url = fileUrl();
        InventoryService first = service(url);
        addProduct(first, "PEN");
        UUID request = UUID.randomUUID();
        var original = first.receiveStock(manager, request, "PEN", 10, "Receipt");
        InventoryService reopened = service(url);
        assertEquals(10, reopened.getStock(manager, "PEN").quantity());
        assertEquals(original, reopened.receiveStock(manager, request, "PEN", 10, "Receipt"));
        assertEquals(1, reopened.history(manager, "PEN").size());
        assertThrows(InventoryException.class,
                () -> reopened.receiveStock(manager, request, "PEN", 11, "Receipt"));
    }

    @Test
    void movementInsertFailureRollsBackTheAlreadyUpdatedBalance() throws Exception {
        String url = fileUrl();
        InventoryService inventory = service(url);
        addProduct(inventory, "PEN");
        UUID request = UUID.randomUUID();
        // Inject a database constraint to fail INSERT after UPDATE products.
        try (var connection = java.sql.DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE stock_movements ADD CONSTRAINT test_failure CHECK (quantity < 5)");
        }
        assertThrows(PersistenceException.class, () -> inventory.receiveStock(
                manager, request, "PEN", 10, "Receipt"));
        assertEquals(0, inventory.getStock(manager, "PEN").quantity());
        assertTrue(inventory.history(manager, "PEN").isEmpty());
        try (var connection = java.sql.DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE stock_movements DROP CONSTRAINT test_failure");
        }
        inventory.receiveStock(manager, request, "PEN", 10, "Valid retry");
        assertEquals(10, inventory.getStock(manager, "PEN").quantity());
    }

    @Test
    void independentRepositoryInstancesCannotOversellLastUnit() throws Exception {
        String url = fileUrl();
        InventoryService first = service(url);
        InventoryService second = service(url);
        addProduct(first, "PEN");
        first.receiveStock(manager, UUID.randomUUID(), "PEN", 1, "Receipt");
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> sellLastUnit(first, start));
            var two = executor.submit(() -> sellLastUnit(second, start));
            start.countDown();
            assertNotEquals(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS));
        }
        assertEquals(0, first.getStock(manager, "PEN").quantity());
        assertEquals(2, second.history(manager, "PEN").size());
    }

    @Test
    void concurrentIdenticalRetriesCreateExactlyOneMovement() throws Exception {
        String url = fileUrl();
        InventoryService first = service(url);
        InventoryService second = service(url);
        addProduct(first, "PEN");
        UUID request = UUID.randomUUID();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return first.receiveStock(manager, request, "PEN", 3, "Receipt");
            });
            var two = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return second.receiveStock(manager, request, "PEN", 3, "Receipt");
            });
            start.countDown();
            assertEquals(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS));
        }
        assertEquals(3, first.getStock(manager, "PEN").quantity());
        assertEquals(1, first.history(manager, "PEN").size());
    }

    @Test
    void requestIdsAreUniqueAcrossProducts() {
        InventoryService inventory = service(fileUrl());
        addProduct(inventory, "PEN");
        addProduct(inventory, "NOTE");
        UUID request = UUID.randomUUID();
        inventory.receiveStock(manager, request, "PEN", 3, "Receipt");
        assertThrows(InventoryException.class, () -> inventory.receiveStock(
                manager, request, "NOTE", 3, "Receipt"));
        assertEquals(0, inventory.getStock(manager, "NOTE").quantity());
    }

    @Test
    void catalogueConstraintsAndDeactivationPreserveHistory() {
        InventoryService inventory = service(fileUrl());
        addProduct(inventory, "PEN");
        assertThrows(InventoryException.class, () -> addProduct(inventory, "PEN"));
        inventory.receiveStock(manager, UUID.randomUUID(), "PEN", 1, "Receipt");
        assertThrows(InventoryException.class, () -> inventory.deactivateProduct(manager, "PEN"));
        inventory.sellStock(manager, UUID.randomUUID(), "PEN", 1, "Sale");
        inventory.deactivateProduct(manager, "PEN");
        assertFalse(inventory.getStock(manager, "PEN").product().active());
        assertTrue(inventory.lowStock(manager).isEmpty());
        assertEquals(2, inventory.history(manager, "PEN").size());
        assertThrows(InventoryException.class, () -> inventory.receiveStock(
                manager, UUID.randomUUID(), "PEN", 1, "Receipt"));
    }

    @Test
    void rejectsOverflowAndUnknownStockWithoutHistoryChanges() {
        InventoryService inventory = service(fileUrl());
        addProduct(inventory, "PEN");
        inventory.receiveStock(manager, UUID.randomUUID(), "PEN", Integer.MAX_VALUE, "Receipt");
        assertThrows(InventoryException.class, () -> inventory.receiveStock(
                manager, UUID.randomUUID(), "PEN", 1, "Overflow"));
        assertThrows(InventoryException.class, () -> inventory.history(manager, "MISSING"));
        assertEquals(Integer.MAX_VALUE, inventory.getStock(manager, "PEN").quantity());
        assertEquals(1, inventory.history(manager, "PEN").size());
    }

    private boolean sellLastUnit(InventoryService inventory, CountDownLatch start) throws Exception {
        assertTrue(start.await(5, TimeUnit.SECONDS));
        try {
            inventory.sellStock(manager, UUID.randomUUID(), "PEN", 1, "Concurrent sale");
            return true;
        } catch (InsufficientStockException expected) {
            return false;
        }
    }

    @Test
    void catalogueEditsPersistWithoutChangingStockOrHistory() {
        String url = fileUrl();
        InventoryService inventory = service(url);
        addProduct(inventory, "PEN");
        inventory.receiveStock(manager, UUID.randomUUID(), "PEN", 3, "Receipt");
        inventory.updateProduct(manager, "PEN", "New name", "New category", new BigDecimal("22.50"), 4);
        InventoryService reopened = service(url);
        assertEquals("New name", reopened.getStock(manager, "PEN").product().name());
        assertEquals(3, reopened.getStock(manager, "PEN").quantity());
        assertEquals(1, reopened.history(manager, "PEN").size());
        assertTrue(reopened.getStock(manager, "PEN").isLowStock());
    }

    @Test
    void allElectronicsDetailTypesSurviveDatabaseReopen() {
        String url = fileUrl();
        InventoryService first = service(url);
        DemoData.seed(first);
        InventoryService reopened = service(url);
        assertEquals(6, reopened.listProducts(DemoData.MANAGER).size());
        first.listProducts(DemoData.MANAGER).forEach(stock -> {
            Product original = stock.product();
            Product restored = reopened.getStock(DemoData.MANAGER, original.sku()).product();
            assertEquals(original, restored);
            assertEquals(original.details().getClass(), restored.details().getClass());
        });
        DemoData.seed(reopened);
        assertEquals(4, reopened.getStock(DemoData.MANAGER, "VE-CHG-001").quantity());
        assertEquals(2, reopened.history(DemoData.MANAGER, "VE-CHG-001").size());
    }

    @Test
    void migrationPreservesOldCatalogueBalancesAndMovements() throws Exception {
        String url = fileUrl();
        // Construct the pre-electronics schema and data directly, as an old installation would.
        try (var connection = java.sql.DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement();
             var input = getClass().getResourceAsStream("/database/schema-h2.sql")) {
            assertNotNull(input);
            for (String sql : new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split(";")) {
                if (!sql.isBlank()) statement.execute(sql);
            }
            statement.execute("INSERT INTO products VALUES ('OLD', 'Old notebook', 'Stationery', 65.00, 2, TRUE, 7)");
            statement.execute("INSERT INTO stock_movements (request_id, sku, movement_type, quantity, balance_after, actor_id, reason, occurred_at) VALUES ('"
                    + UUID.randomUUID() + "', 'OLD', 'RECEIPT', 7, 7, '" + manager.id() + "', 'Original receipt', '2026-11-09T08:00:00Z')");
        }
        InventoryService migrated = service(url);
        assertEquals(7, migrated.getStock(manager, "OLD").quantity());
        assertEquals("Stationery", migrated.getStock(manager, "OLD").product().category());
        assertInstanceOf(LegacyDetails.class, migrated.getStock(manager, "OLD").product().details());
        assertEquals("Original receipt", migrated.history(manager, "OLD").getFirst().reason());
        service(url); // Reapplying the migration must be harmless.
        migrated.updateProduct(manager, "OLD", "Reclassified SSD", BigDecimal.TEN, 2, new StorageDetails(128, "USB", "Computers"));
        assertEquals(ProductCategory.STORAGE, service(url).getStock(manager, "OLD").product().details().category());
        assertEquals(7, migrated.getStock(manager, "OLD").quantity());
        assertEquals(1, migrated.history(manager, "OLD").size());
    }

    @Test
    void specificationEditsPersistAndCannotDowngradeToLegacy() {
        String url = fileUrl();
        InventoryService inventory = service(url);
        inventory.addProduct(manager, new Product("CHG", "Charger", BigDecimal.TEN, 2, true, new ChargingDetails("USB-C", 30, "Phones")));
        inventory.receiveStock(manager, UUID.randomUUID(), "CHG", 5, "Receipt");
        inventory.updateProduct(manager, "CHG", "Charger", BigDecimal.TEN, 2, new ChargingDetails("USB-C", 65, "Laptops"));
        assertEquals(65, ((ChargingDetails) service(url).getStock(manager, "CHG").product().details()).wattage());
        assertThrows(IllegalArgumentException.class, () -> inventory.updateProduct(manager, "CHG", "Charger", BigDecimal.TEN, 2, new LegacyDetails("Test")));
        assertEquals(5, inventory.getStock(manager, "CHG").quantity());
        assertEquals(1, inventory.history(manager, "CHG").size());
    }

    private String fileUrl() {
        return "jdbc:h2:file:" + temporary.resolve("inventory") + ";LOCK_TIMEOUT=5000";
    }

    private InventoryService service(String url) {
        return new InventoryService(new JdbcInventoryRepository(url), new AuthorizationService(), Clock.systemUTC());
    }

    private void addProduct(InventoryService inventory, String sku) {
        inventory.addProduct(manager, new Product(sku, "Blue pen", "Stationery", new BigDecimal("15.00"), 5, true));
    }
}
