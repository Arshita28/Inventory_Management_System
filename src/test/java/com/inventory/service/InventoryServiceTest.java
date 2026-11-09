package com.inventory.service;

import com.inventory.exception.AccessDeniedException;
import com.inventory.exception.InsufficientStockException;
import com.inventory.exception.InventoryException;
import com.inventory.model.Product;
import com.inventory.model.StockMovement;
import com.inventory.repository.memory.InMemoryInventoryRepository;
import com.inventory.security.AuthorizationService;
import com.inventory.security.Role;
import com.inventory.security.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class InventoryServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-09T08:00:00Z");
    private final User manager = new User(UUID.randomUUID(), "manager", Role.MANAGER, true);
    private final User cashier = new User(UUID.randomUUID(), "cashier", Role.CASHIER, true);
    private InventoryService inventory;

    @BeforeEach
    void setUp() {
        inventory = new InventoryService(new InMemoryInventoryRepository(),
                new AuthorizationService(), Clock.fixed(NOW, ZoneOffset.UTC));
        inventory.addProduct(manager, product("PEN-001"));
    }

    @Test
    void receiptAndSaleRetainTraceableHistory() {
        receive(10);
        inventory.sellStock(cashier, UUID.randomUUID(), "pen-001", 6, "Sale 001");
        assertEquals(4, quantity());
        List<StockMovement> history = inventory.history(manager, "PEN-001");
        assertEquals(List.of(10, -6), history.stream().map(StockMovement::quantity).toList());
        assertEquals(cashier.id(), history.get(1).actorId());
        assertEquals(NOW, history.get(1).occurredAt());
        assertEquals(4, history.stream().mapToInt(StockMovement::quantity).sum());
        assertThrows(UnsupportedOperationException.class, history::clear);
    }

    @Test
    void unauthorizedOperationsLeaveStateUntouched() {
        assertThrows(AccessDeniedException.class, () -> inventory.addProduct(cashier, product("OTHER")));
        assertThrows(AccessDeniedException.class, () -> inventory.receiveStock(
                cashier, UUID.randomUUID(), "PEN-001", 1, "Receipt"));
        assertThrows(AccessDeniedException.class, () -> inventory.history(cashier, "PEN-001"));
        assertEquals(0, quantity());
        assertEquals(1, inventory.listProducts(manager).size());
    }

    @Test
    void missingAndInactiveActorsAreDenied() {
        User inactive = new User(UUID.randomUUID(), "inactive", Role.ADMIN, false);
        assertThrows(AccessDeniedException.class, () -> inventory.listProducts(null));
        assertThrows(AccessDeniedException.class, () -> inventory.listProducts(inactive));
    }

    @Test
    void oversellingDoesNotChangeBalanceOrHistory() {
        receive(2);
        assertThrows(InsufficientStockException.class, () -> inventory.sellStock(
                cashier, UUID.randomUUID(), "PEN-001", 3, "Oversale"));
        assertEquals(2, quantity());
        assertEquals(1, inventory.history(manager, "PEN-001").size());
    }

    @Test
    void identicalRequestRetryDoesNotApplyStockTwice() {
        UUID request = UUID.randomUUID();
        StockMovement original = inventory.receiveStock(manager, request, "PEN-001", 10, "Receipt");
        StockMovement retry = inventory.receiveStock(manager, request, " pen-001 ", 10, " Receipt ");
        assertEquals(original, retry);
        assertEquals(10, quantity());
        assertEquals(1, inventory.history(manager, "PEN-001").size());
    }

    @Test
    void reusedRequestWithDifferentDataIsRejected() {
        UUID request = UUID.randomUUID();
        inventory.receiveStock(manager, request, "PEN-001", 2, "Receipt");
        assertThrows(InventoryException.class, () -> inventory.receiveStock(
                manager, request, "PEN-001", 3, "Receipt"));
        assertThrows(InventoryException.class, () -> inventory.sellStock(
                cashier, request, "PEN-001", 2, "Receipt"));
        assertEquals(2, quantity());
    }

    @Test
    void failedRequestCanBeRetriedAfterReplenishment() {
        UUID request = UUID.randomUUID();
        assertThrows(InsufficientStockException.class, () -> inventory.sellStock(
                cashier, request, "PEN-001", 1, "Sale"));
        receive(1);
        inventory.sellStock(cashier, request, "PEN-001", 1, "Sale");
        assertEquals(0, quantity());
    }

    @Test
    void rejectsInvalidQuantitiesAndReasons() {
        for (int value : new int[]{0, -1, Integer.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> inventory.receiveStock(
                    manager, UUID.randomUUID(), "PEN-001", value, "Receipt"));
            assertThrows(IllegalArgumentException.class, () -> inventory.sellStock(
                    cashier, UUID.randomUUID(), "PEN-001", value, "Sale"));
        }
        assertThrows(IllegalArgumentException.class, () -> inventory.receiveStock(
                manager, UUID.randomUUID(), "PEN-001", 1, " "));
        assertEquals(0, quantity());
        assertTrue(inventory.history(manager, "PEN-001").isEmpty());
    }

    @Test
    void rejectsDuplicateSkuAndUnknownProducts() {
        assertThrows(InventoryException.class, () -> inventory.addProduct(manager, product("pen-001")));
        assertThrows(InventoryException.class, () -> inventory.getStock(manager, "MISSING"));
        assertThrows(InventoryException.class, () -> inventory.receiveStock(
                manager, UUID.randomUUID(), "MISSING", 1, "Receipt"));
    }

    @Test
    void deactivationRequiresZeroStockAndPreservesHistory() {
        receive(1);
        assertThrows(InventoryException.class, () -> inventory.deactivateProduct(manager, "PEN-001"));
        inventory.sellStock(cashier, UUID.randomUUID(), "PEN-001", 1, "Final sale");
        inventory.deactivateProduct(manager, "PEN-001");
        assertFalse(inventory.getStock(manager, "PEN-001").product().active());
        assertEquals(2, inventory.history(manager, "PEN-001").size());
        assertTrue(inventory.lowStock(manager).isEmpty());
        assertThrows(InventoryException.class, () -> receive(1));
    }

    @Test
    void lowStockIncludesThresholdAndExcludesReplenishedProducts() {
        receive(5);
        assertEquals(1, inventory.lowStock(manager).size());
        receive(1);
        assertTrue(inventory.lowStock(manager).isEmpty());
    }

    @Test
    void rejectsOverflowWithoutChangingStock() {
        receive(Integer.MAX_VALUE);
        assertThrows(InventoryException.class, () -> receive(1));
        assertEquals(Integer.MAX_VALUE, quantity());
        assertEquals(1, inventory.history(manager, "PEN-001").size());
    }

    @Test
    void validatesProductMoneyAndSku() {
        assertThrows(IllegalArgumentException.class, () -> product("bad sku"));
        assertThrows(IllegalArgumentException.class, () -> new Product(
                "BAD", "Bad price", "Test", new BigDecimal("-1"), 0, true));
        assertThrows(ArithmeticException.class, () -> new Product(
                "BAD", "Bad precision", "Test", new BigDecimal("1.001"), 0, true));
        assertThrows(IllegalArgumentException.class, () -> new Product(
                "BAD", " ", "Test", BigDecimal.ONE, 0, true));
    }

    @Test
    void simultaneousSalesCannotConsumeTheSameLastUnit() throws Exception {
        receive(1);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> sale = () -> {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test start timed out");
            }
            try {
                inventory.sellStock(cashier, UUID.randomUUID(), "PEN-001", 1, "Concurrent sale");
                return true;
            } catch (InsufficientStockException expected) {
                return false;
            }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(sale);
            var second = executor.submit(sale);
            start.countDown();
            assertNotEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
        }
        assertEquals(0, quantity());
        assertEquals(2, inventory.history(manager, "PEN-001").size());
    }

    private Product product(String sku) {
        return new Product(sku, "Blue pen", "Stationery", new BigDecimal("15.00"), 5, true);
    }

    @Test
    void editsPreserveStockAndRequireManagerPermission() {
        receive(4);
        inventory.updateProduct(manager, "pen-001", "Black pen", "Office", new BigDecimal("20"), 6);
        assertEquals(4, quantity());
        assertEquals("Black pen", inventory.getStock(manager, "PEN-001").product().name());
        assertEquals(1, inventory.history(manager, "PEN-001").size());
        assertTrue(inventory.getStock(manager, "PEN-001").isLowStock());
        assertThrows(AccessDeniedException.class, () -> inventory.updateProduct(
                cashier, "PEN-001", "No", "No", BigDecimal.ONE, 0));
        assertThrows(IllegalArgumentException.class, () -> inventory.receiveStock(
                manager, UUID.randomUUID(), "PEN-001", 1, "x".repeat(2001)));
    }

    @Test
    void validatesStorageBoundariesBeforeRepositoryWrites() {
        assertThrows(IllegalArgumentException.class, () -> new Product(
                "LONG", "x".repeat(256), "Test", BigDecimal.ONE, 0, true));
        assertThrows(IllegalArgumentException.class, () -> new Product(
                "HUGE", "Huge price", "Test", new BigDecimal("1E+1000000"), 0, true));
        assertThrows(ArithmeticException.class, () -> new Product(
                "TINY", "Tiny price", "Test", new BigDecimal("1E-1000000"), 0, true));
        assertEquals(new BigDecimal("1.20"), new Product(
                "VALID", "Valid price", "Test", new BigDecimal("1.2000"), 0, true).sellingPrice());
        assertEquals(1, inventory.listProducts(manager).size());
    }

    private void receive(int quantity) {
        inventory.receiveStock(manager, UUID.randomUUID(), "PEN-001", quantity, "Opening stock");
    }

    private int quantity() {
        return inventory.getStock(manager, "PEN-001").quantity();
    }
}
