package com.inventory.service.analytics;

import com.inventory.model.Product;
import com.inventory.model.details.StorageDetails;
import com.inventory.model.procurement.OrderAction;
import com.inventory.model.procurement.SupplierOffer;
import com.inventory.model.procurement.PurchaseOrder;
import com.inventory.repository.memory.InMemoryInventoryRepository;
import com.inventory.security.Role;
import com.inventory.security.User;
import com.inventory.security.AuthorizationService;
import com.inventory.service.InventoryService;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class AnalyticsTest {
    private final User manager = new User(UUID.randomUUID(), "manager", Role.MANAGER, true);
    private final User reviewer = new User(UUID.randomUUID(), "reviewer", Role.MANAGER, true);
    private final InMemoryInventoryRepository repository = new InMemoryInventoryRepository();

    @Test
    void usesInclusiveBusinessDatesAndCurrentValueWithoutInventingRevenue() {
        InventoryService start = at("2026-11-08T18:30:00Z"); // Nov 9, midnight in India.
        start.addProduct(manager, product());
        start.receiveStock(manager, UUID.randomUUID(), "SSD", 10, "Opening");
        at("2026-11-09T18:29:59.999999Z").sellStock(manager, UUID.randomUUID(), "SSD", 6, "Included");
        at("2026-11-09T18:30:00Z").sellStock(manager, UUID.randomUUID(), "SSD", 2, "Next day, excluded");
        var report = start.operations().analytics(manager, LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 9));
        assertEquals(6, report.soldUnits());
        assertEquals(10, report.receivedUnits());
        assertEquals(new BigDecimal("200.00"), report.currentRetailValue());
        assertEquals(1, report.daily().size());
        assertEquals(6, report.daily().getFirst().sold());
        assertNull(report.products().getFirst().daysCover());
    }

    @Test
    void restockSuggestionsAccountForLeadTimeMinimumsAndOpenOrders() {
        InventoryService opening = at("2026-10-11T08:00:00Z");
        opening.addProduct(manager, product());
        opening.receiveStock(manager, UUID.randomUUID(), "SSD", 10, "Opening");
        InventoryService inventory = at("2026-11-09T08:00:00Z");
        inventory.sellStock(manager, UUID.randomUUID(), "SSD", 6, "Sale");
        SupplierOffer offer = new SupplierOffer(UUID.randomUUID(), "SSD", "Supplier", new BigDecimal("50"), 4, 2);
        inventory.operations().addOffer(manager, offer);
        var first = report(inventory).restock().getFirst();
        assertEquals(6, first.trigger());
        assertEquals(9, first.target());
        assertEquals(5, first.suggestedQuantity());
        assertEquals(new BigDecimal("20.0"), report(inventory).products().getFirst().daysCover());
        PurchaseOrder order = inventory.operations().createOrder(manager, UUID.randomUUID(), offer.id(), 5, "Replenish");
        var draft = report(inventory).restock().getFirst();
        assertEquals(5, draft.openCommitments());
        assertEquals(0, draft.approvedInbound());
        assertEquals(0, draft.suggestedQuantity());
        inventory.operations().transition(manager, order.id(), OrderAction.SUBMIT, "Submit");
        inventory.operations().transition(reviewer, order.id(), OrderAction.APPROVE, "Approved");
        assertEquals(5, report(inventory).restock().getFirst().approvedInbound());
        inventory.operations().receive(manager, order.id(), UUID.randomUUID(), 2, "Partial");
        assertEquals(3, report(inventory).restock().getFirst().openCommitments());
        assertEquals(0, report(inventory).restock().getFirst().suggestedQuantity());
        inventory.operations().receive(manager, order.id(), UUID.randomUUID(), 3, "Final");
        assertTrue(report(inventory).restock().isEmpty());
    }

    @Test
    void handlesNoHistoryAndNoSupplierWithoutFabricatedForecasts() {
        InventoryService inventory = at("2026-11-09T08:00:00Z");
        inventory.addProduct(manager, product());
        var report = report(inventory);
        assertEquals(30, report.daily().size());
        assertEquals(0, report.soldUnits());
        assertNull(report.products().getFirst().daysCover());
        assertNull(report.restock().getFirst().offer());
        assertTrue(report.restock().getFirst().explanation().contains("threshold-based fallback"));
        assertTrue(report.daily().stream().allMatch(day -> day.sold() == 0 && day.received() == 0));
    }

    @Test
    void cheapestQuoteAndMinimumOrderAreAppliedExplicitly() {
        InventoryService inventory = at("2026-11-09T08:00:00Z");
        inventory.addProduct(manager, product());
        inventory.operations().addOffer(manager, new SupplierOffer(UUID.randomUUID(), "SSD", "Expensive", new BigDecimal("60"), 4, 2));
        inventory.operations().addOffer(manager, new SupplierOffer(UUID.randomUUID(), "SSD", "Cheaper", new BigDecimal("50"), 3, 20));
        var suggestion = report(inventory).restock().getFirst();
        assertEquals("Cheaper", suggestion.offer().supplierName());
        assertEquals(20, suggestion.suggestedQuantity());
    }

    @Test
    void excludesLegacyAndDoesNotRecommendInactiveProducts() {
        InventoryService inventory = at("2026-11-09T08:00:00Z");
        inventory.addProduct(manager, product());
        inventory.addProduct(manager, new Product("OLD", "Legacy", "Old", BigDecimal.TEN, 5, true));
        inventory.receiveStock(manager, UUID.randomUUID(), "SSD", 1, "Opening");
        inventory.sellStock(manager, UUID.randomUUID(), "SSD", 1, "Sale");
        inventory.deactivateProduct(manager, "SSD");
        assertEquals(1, report(inventory).products().size());
        assertEquals(1, report(inventory).soldUnits());
        assertTrue(report(inventory).restock().isEmpty());
    }

    @Test
    void rejectsReversedOrExcessiveDateRanges() {
        InventoryService inventory = at("2026-11-09T08:00:00Z");
        assertThrows(IllegalArgumentException.class, () -> inventory.operations().analytics(manager, LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 9)));
        assertThrows(IllegalArgumentException.class, () -> inventory.operations().analytics(manager, LocalDate.of(2024, 1, 1), LocalDate.of(2026, 11, 9)));
    }

    private AnalyticsReport report(InventoryService inventory) {
        return inventory.operations().analytics(manager, LocalDate.of(2026, 10, 11), LocalDate.of(2026, 11, 9));
    }

    private Product product() { return new Product("SSD", "Portable SSD", new BigDecimal("100"), 5, true, new StorageDetails(512, "USB", "Laptop")); }
    private InventoryService at(String instant) { return new InventoryService(repository, new AuthorizationService(), Clock.fixed(Instant.parse(instant), ZoneOffset.UTC)); }
}
