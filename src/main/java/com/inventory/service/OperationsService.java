package com.inventory.service;

import com.inventory.exception.InventoryException;
import com.inventory.model.InventorySnapshot;
import com.inventory.model.StockMovement;
import com.inventory.model.procurement.OrderAction;
import com.inventory.model.procurement.SupplierOffer;
import com.inventory.model.procurement.PurchaseOrder;
import com.inventory.repository.InventoryRepository;
import com.inventory.security.AuthorizationService;
import com.inventory.security.Permission;
import com.inventory.security.User;
import com.inventory.service.analytics.AnalyticsCalculator;
import com.inventory.service.analytics.AnalyticsReport;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/** Analytics and purchasing use cases, independent of the HTTP adapter. */
public final class OperationsService {
    private final InventoryRepository repository;
    private final AuthorizationService authorization;
    private final Clock clock;
    private final ZoneId zone;

    public OperationsService(InventoryRepository repository, AuthorizationService authorization, Clock clock, ZoneId zone) {
        this.repository = repository;
        this.authorization = authorization;
        this.clock = clock;
        this.zone = zone;
    }

    public ZoneId zone() { return zone; }
    public LocalDate today() { return LocalDate.now(clock.withZone(zone)); }

    public InventorySnapshot snapshot(User actor) {
        authorization.require(actor, Permission.VIEW_ANALYTICS);
        return repository.snapshot();
    }

    public AnalyticsReport analytics(User actor, LocalDate from, LocalDate to) {
        return new AnalyticsCalculator().calculate(snapshot(actor), from, to, zone);
    }

    public Dashboard dashboard(User actor, LocalDate from, LocalDate to) {
        InventorySnapshot snapshot = snapshot(actor);
        return new Dashboard(snapshot, new AnalyticsCalculator().calculate(snapshot, from, to, zone));
    }

    public record Dashboard(InventorySnapshot snapshot, AnalyticsReport analytics) { }

    public SupplierOffer addOffer(User actor, SupplierOffer offer) {
        authorization.require(actor, Permission.MANAGE_PURCHASES);
        return repository.addOffer(offer);
    }

    public PurchaseOrder createOrder(User actor, UUID requestId, UUID offerId, int quantity, String note) {
        authorization.require(actor, Permission.MANAGE_PURCHASES);
        SupplierOffer offer = repository.snapshot().offers().stream().filter(value -> value.id().equals(offerId)).findFirst()
                .orElseThrow(() -> new InventoryException("Unknown supplier quote"));
        return repository.createOrder(PurchaseOrder.draft(requestId, offer, quantity, actor.id(), clock.instant(), today().plusDays(offer.leadDays()), note));
    }

    public PurchaseOrder transition(User actor, UUID orderId, OrderAction action, String note) {
        authorization.require(actor, action == OrderAction.APPROVE || action == OrderAction.REJECT
                ? Permission.APPROVE_PURCHASES : Permission.MANAGE_PURCHASES);
        return repository.transitionOrder(orderId, action, actor.id(), PurchaseOrder.validNote(note), clock.instant());
    }

    public StockMovement receive(User actor, UUID orderId, UUID requestId, int quantity, String note) {
        authorization.require(actor, Permission.RECEIVE_STOCK);
        authorization.require(actor, Permission.MANAGE_PURCHASES);
        return repository.receiveOrder(orderId, requestId, quantity, actor.id(), PurchaseOrder.validNote(note), clock.instant());
    }
}
