package com.inventory.service.procurement;

import com.inventory.exception.AccessDeniedException;
import com.inventory.exception.InventoryException;
import com.inventory.exception.PersistenceException;
import com.inventory.model.Product;
import com.inventory.model.details.ChargingDetails;
import com.inventory.model.procurement.OrderStatus;
import com.inventory.model.procurement.OrderAction;
import com.inventory.model.procurement.SupplierOffer;
import com.inventory.model.procurement.PurchaseOrder;
import com.inventory.repository.InventoryRepository;
import com.inventory.repository.jdbc.JdbcInventoryRepository;
import com.inventory.repository.memory.InMemoryInventoryRepository;
import com.inventory.security.Role;
import com.inventory.security.User;
import com.inventory.security.AuthorizationService;
import com.inventory.service.InventoryService;
import com.inventory.service.OperationsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ProcurementTest {
    @TempDir Path temporary;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-09T08:00:00Z"), ZoneOffset.UTC);
    private final User manager = new User(UUID.randomUUID(), "manager", Role.MANAGER, true);
    private final User reviewer = new User(UUID.randomUUID(), "reviewer", Role.MANAGER, true);
    private final User cashier = new User(UUID.randomUUID(), "cashier", Role.STAFF, true);

    @TestFactory
    Stream<DynamicTest> bothAdaptersEnforceTheSameWorkflow() {
        return Stream.of(false, true).flatMap(jdbc -> Stream.of("lifecycle", "permissions", "retries", "bounds", "deactivation", "concurrent")
                .map(name -> DynamicTest.dynamicTest((jdbc ? "JDBC " : "Memory ") + name, () -> {
                    Fixture fixture = fixture(jdbc);
                    switch (name) {
                        case "lifecycle" -> lifecycle(fixture);
                        case "permissions" -> permissions(fixture);
                        case "retries" -> retries(fixture);
                        case "bounds" -> bounds(fixture);
                        case "deactivation" -> deactivation(fixture);
                        case "concurrent" -> concurrent(fixture);
                    }
                })));
    }

    private void lifecycle(Fixture fixture) {
        PurchaseOrder order = fixture.create();
        assertEquals(OrderStatus.DRAFT, order.status());
        assertEquals(4, fixture.quantity());
        assertThrows(InventoryException.class, () -> fixture.operations.receive(manager, order.id(), UUID.randomUUID(), 1, "Premature receipt"));
        fixture.approve(order);
        UUID firstReceipt = UUID.randomUUID();
        fixture.operations.receive(manager, order.id(), firstReceipt, 2, "First delivery");
        assertEquals(OrderStatus.PARTIALLY_RECEIVED, fixture.order(order.id()).status());
        assertEquals(6, fixture.quantity());
        fixture.operations.receive(manager, order.id(), UUID.randomUUID(), 3, "Final delivery");
        assertEquals(OrderStatus.RECEIVED, fixture.order(order.id()).status());
        assertEquals(9, fixture.quantity());
        assertEquals(5, fixture.operations.snapshot(manager).events().size());
        assertEquals(new BigDecimal("50.00"), order.totalCost());
        assertEquals(3, fixture.inventory.history(manager, "ITEM").size());
    }

    private void permissions(Fixture fixture) {
        PurchaseOrder order = fixture.create();
        fixture.operations.transition(manager, order.id(), OrderAction.SUBMIT, "Submit");
        assertThrows(InventoryException.class, () -> fixture.operations.transition(manager, order.id(), OrderAction.APPROVE, "Own approval"));
        assertThrows(AccessDeniedException.class, () -> fixture.operations.createOrder(cashier, UUID.randomUUID(), fixture.offer.id(), 5, "Unauthorized"));
        assertThrows(AccessDeniedException.class, () -> fixture.operations.snapshot(cashier));
        assertThrows(AccessDeniedException.class, () -> fixture.operations.transition(cashier, order.id(), OrderAction.APPROVE, "Unauthorized"));
        assertThrows(AccessDeniedException.class, () -> fixture.operations.receive(cashier, order.id(), UUID.randomUUID(), 1, "Unauthorized"));
        fixture.operations.transition(reviewer, order.id(), OrderAction.REJECT, "Reject quote");
        assertEquals(OrderStatus.REJECTED, fixture.order(order.id()).status());
        assertNotNull(fixture.create());
    }

    private void retries(Fixture fixture) {
        UUID request = UUID.randomUUID();
        PurchaseOrder order = fixture.operations.createOrder(manager, request, fixture.offer.id(), 5, "Replenish");
        assertEquals(order, fixture.operations.createOrder(manager, request, fixture.offer.id(), 5, "Replenish"));
        assertThrows(InventoryException.class, () -> fixture.operations.createOrder(manager, request, fixture.offer.id(), 6, "Replenish"));
        assertThrows(InventoryException.class, fixture::create);
        fixture.approve(order);
        UUID receipt = UUID.randomUUID();
        var original = fixture.operations.receive(manager, order.id(), receipt, 5, "Delivery");
        assertEquals(original, fixture.operations.receive(manager, order.id(), receipt, 5, "Delivery"));
        assertThrows(InventoryException.class, () -> fixture.operations.receive(manager, order.id(), receipt, 4, "Delivery"));
        assertThrows(InventoryException.class, () -> fixture.operations.receive(reviewer, order.id(), receipt, 5, "Delivery"));
        assertEquals(9, fixture.quantity());
        assertEquals(4, fixture.operations.snapshot(manager).events().size());
        PurchaseOrder another = fixture.create();
        assertThrows(InventoryException.class, () -> fixture.operations.receive(manager, another.id(), receipt, 5, "Delivery"));
    }

    private void bounds(Fixture fixture) {
        assertThrows(IllegalArgumentException.class, () -> fixture.operations.createOrder(manager, UUID.randomUUID(), fixture.offer.id(), 1, "Below MOQ"));
        PurchaseOrder order = fixture.create();
        fixture.approve(order);
        assertThrows(InventoryException.class, () -> fixture.operations.receive(manager, order.id(), UUID.randomUUID(), 6, "Too much"));
        fixture.operations.receive(manager, order.id(), UUID.randomUUID(), 2, "Partial");
        assertThrows(InventoryException.class, () -> fixture.operations.transition(manager, order.id(), OrderAction.CANCEL, "After receipt"));
        assertEquals(2, fixture.order(order.id()).receivedQuantity());
        assertEquals(6, fixture.quantity());
        assertThrows(InventoryException.class, () -> fixture.operations.transition(reviewer, order.id(), OrderAction.APPROVE, "Already approved"));
    }

    private void deactivation(Fixture fixture) {
        fixture.inventory.sellStock(manager, UUID.randomUUID(), "ITEM", 4, "Sell remaining");
        PurchaseOrder order = fixture.create();
        assertThrows(InventoryException.class, () -> fixture.inventory.deactivateProduct(manager, "ITEM"));
        fixture.operations.transition(manager, order.id(), OrderAction.CANCEL, "Cancel request");
        fixture.inventory.deactivateProduct(manager, "ITEM");
        assertThrows(InventoryException.class, fixture::create);
        assertFalse(fixture.inventory.getStock(manager, "ITEM").product().active());
    }

    private void concurrent(Fixture fixture) throws Exception {
        PurchaseOrder order = fixture.create();
        fixture.approve(order);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> receiveOnce(fixture, order, start));
            var two = executor.submit(() -> receiveOnce(fixture, order, start));
            start.countDown();
            assertNotEquals(one.get(15, TimeUnit.SECONDS), two.get(15, TimeUnit.SECONDS));
        }
        assertEquals(9, fixture.quantity());
        assertEquals(5, fixture.order(order.id()).receivedQuantity());
        assertEquals(2, fixture.inventory.history(manager, "ITEM").size());
    }

    @Test
    void failedReceiptRollsBackBalanceMovementOrderAndAuditTogether() throws Exception {
        Fixture fixture = fixture(true);
        PurchaseOrder order = fixture.create();
        fixture.approve(order);
        UUID request = UUID.randomUUID();
        try (var connection = java.sql.DriverManager.getConnection(fixture.url, "sa", ""); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE purchase_receipts ADD CONSTRAINT reject_test CHECK (request_id = 'never')");
        }
        assertThrows(PersistenceException.class, () -> fixture.operations.receive(manager, order.id(), request, 5, "Delivery"));
        assertEquals(4, fixture.quantity());
        assertEquals(OrderStatus.APPROVED, fixture.order(order.id()).status());
        assertEquals(0, fixture.order(order.id()).receivedQuantity());
        assertEquals(1, fixture.inventory.history(manager, "ITEM").size());
        assertEquals(3, fixture.operations.snapshot(manager).events().size());
        try (var connection = java.sql.DriverManager.getConnection(fixture.url, "sa", ""); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE purchase_receipts DROP CONSTRAINT reject_test");
        }
        fixture.operations.receive(manager, order.id(), request, 5, "Delivery");
        var reopened = new InventoryService(new JdbcInventoryRepository(fixture.url), new AuthorizationService(), CLOCK);
        assertEquals(9, reopened.getStock(manager, "ITEM").quantity());
        assertEquals(OrderStatus.RECEIVED, reopened.operations().snapshot(manager).orders().getFirst().status());
        assertEquals(4, reopened.operations().snapshot(manager).events().size());
        reopened.operations().receive(manager, order.id(), request, 5, "Delivery");
        assertEquals(9, reopened.getStock(manager, "ITEM").quantity());
    }

    @Test
    void independentRepositoriesPreventConcurrentDuplicateOrders() throws Exception {
        Fixture fixture = fixture(true);
        var second = new InventoryService(new JdbcInventoryRepository(fixture.url), new AuthorizationService(), CLOCK).operations();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> createOnce(fixture.operations, fixture.offer, start));
            var two = executor.submit(() -> createOnce(second, fixture.offer, start));
            start.countDown();
            assertNotEquals(one.get(15, TimeUnit.SECONDS), two.get(15, TimeUnit.SECONDS));
        }
        assertEquals(1, fixture.operations.snapshot(manager).orders().size());
    }

    private boolean receiveOnce(Fixture fixture, PurchaseOrder order, CountDownLatch start) throws Exception {
        assertTrue(start.await(5, TimeUnit.SECONDS));
        try { fixture.operations.receive(manager, order.id(), UUID.randomUUID(), 5, "Delivery"); return true; }
        catch (InventoryException expected) { return false; }
    }

    private boolean createOnce(OperationsService service, SupplierOffer offer, CountDownLatch start) throws Exception {
        assertTrue(start.await(5, TimeUnit.SECONDS));
        try { service.createOrder(manager, UUID.randomUUID(), offer.id(), 5, "Replenish"); return true; }
        catch (InventoryException expected) { return false; }
    }

    private Fixture fixture(boolean jdbc) {
        String url = "jdbc:h2:file:" + temporary.resolve(UUID.randomUUID().toString()) + ";LOCK_TIMEOUT=10000";
        InventoryRepository repository = jdbc ? new JdbcInventoryRepository(url) : new InMemoryInventoryRepository();
        InventoryService inventory = new InventoryService(repository, new AuthorizationService(), CLOCK);
        inventory.addProduct(manager, new Product("ITEM", "Charger", new BigDecimal("20.00"), 5, true, new ChargingDetails("USB-C", 65, "Laptop")));
        inventory.receiveStock(manager, UUID.randomUUID(), "ITEM", 4, "Opening");
        SupplierOffer offer = new SupplierOffer(UUID.randomUUID(), "ITEM", "Test supplier", new BigDecimal("10"), 4, 2);
        inventory.operations().addOffer(manager, offer);
        return new Fixture(url, inventory, inventory.operations(), offer);
    }

    private final class Fixture {
        final String url;
        final InventoryService inventory;
        final OperationsService operations;
        final SupplierOffer offer;

        Fixture(String url, InventoryService inventory, OperationsService operations, SupplierOffer offer) {
            this.url = url;
            this.inventory = inventory;
            this.operations = operations;
            this.offer = offer;
        }

        PurchaseOrder create() { return operations.createOrder(manager, UUID.randomUUID(), offer.id(), 5, "Replenish"); }
        int quantity() { return inventory.getStock(manager, "ITEM").quantity(); }
        PurchaseOrder order(UUID id) { return operations.snapshot(manager).orders().stream().filter(order -> order.id().equals(id)).findFirst().orElseThrow(); }
        void approve(PurchaseOrder order) {
            operations.transition(manager, order.id(), OrderAction.SUBMIT, "Submit");
            operations.transition(reviewer, order.id(), OrderAction.APPROVE, "Approved");
        }
    }
}
