package com.inventory.repository.memory;

import com.inventory.exception.InsufficientStockException;
import com.inventory.exception.InventoryException;
import com.inventory.model.Product;
import com.inventory.model.StockMovement;
import com.inventory.model.StockSummary;
import com.inventory.model.InventorySnapshot;
import com.inventory.model.MovementType;
import com.inventory.model.procurement.OrderAction;
import com.inventory.model.procurement.SupplierOffer;
import com.inventory.model.procurement.OrderEvent;
import com.inventory.model.procurement.PurchaseOrder;
import com.inventory.model.procurement.ProcurementRules;
import java.time.Instant;
import java.util.ArrayList;
import com.inventory.repository.InventoryRepository;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntFunction;

/** Single-process implementation. All balance and history writes share one lock. */
public final class InMemoryInventoryRepository implements InventoryRepository {
    private final Map<String, StockSummary> stock = new HashMap<>();
    private final Map<UUID, StockMovement> movements = new LinkedHashMap<>();
    private final Map<UUID, SupplierOffer> offers = new LinkedHashMap<>();
    private final Map<UUID, PurchaseOrder> orders = new LinkedHashMap<>();
    private final Map<UUID, UUID> receiptOrders = new HashMap<>();
    private final List<OrderEvent> events = new ArrayList<>();

    @Override
    public synchronized void add(Product product) {
        if (stock.containsKey(product.sku())) {
            throw new InventoryException("SKU already exists: " + product.sku());
        }
        stock.put(product.sku(), new StockSummary(product, 0));
    }

    @Override
    public synchronized void update(Product product) {
        StockSummary current = requireStock(product.sku());
        if (product.active() != current.product().active()) {
            throw new InventoryException("Active state cannot be changed by a catalogue edit");
        }
        stock.put(product.sku(), new StockSummary(product, current.quantity()));
    }

    @Override
    public synchronized void deactivate(String sku) {
        StockSummary current = requireStock(sku);
        if (current.quantity() != 0) {
            throw new InventoryException("Product must have zero stock before deactivation");
        }
        if (orders.values().stream().anyMatch(order -> order.sku().equals(sku) && order.status().isOpen())) {
            throw new InventoryException("Close the product's open purchase order before deactivation");
        }
        stock.put(sku, new StockSummary(current.product().deactivate(), 0));
    }

    @Override
    public synchronized Optional<StockSummary> find(String sku) {
        return Optional.ofNullable(stock.get(sku));
    }

    @Override
    public synchronized List<StockSummary> findAll() {
        return stock.values().stream()
                .sorted(Comparator.comparing(summary -> summary.product().sku()))
                .toList();
    }

    @Override
    public synchronized List<StockMovement> history(String sku) {
        requireStock(sku);
        return movements.values().stream().filter(entry -> entry.sku().equals(sku)).toList();
    }

    @Override
    public synchronized StockMovement changeStock(UUID requestId, String sku, int delta,
            UUID actorId, String reason, IntFunction<StockMovement> movementFactory) {
        StockMovement previous = movements.get(requestId);
        if (previous != null) {
            if (!previous.sku().equals(sku) || previous.quantity() != delta
                    || !previous.actorId().equals(actorId) || !previous.reason().equals(reason)) {
                throw new InventoryException("Request ID already used with different stock-change data");
            }
            return previous;
        }
        if (delta == 0) {
            throw new IllegalArgumentException("Stock change cannot be zero");
        }
        StockSummary current = requireStock(sku);
        if (!current.product().active()) {
            throw new InventoryException("Product is inactive: " + sku);
        }
        long proposed = (long) current.quantity() + delta;
        if (proposed < 0) {
            throw new InsufficientStockException(sku, current.quantity(), Math.negateExact(delta));
        }
        if (proposed > Integer.MAX_VALUE) {
            throw new InventoryException("Stock quantity exceeds supported maximum");
        }
        StockMovement movement = movementFactory.apply((int) proposed);
        if (!movement.id().equals(requestId) || !movement.sku().equals(sku)
                || movement.quantity() != delta || movement.balanceAfter() != proposed
                || !movement.actorId().equals(actorId) || !movement.reason().equals(reason)) {
            throw new IllegalArgumentException("Movement does not match the requested stock change");
        }
        // Validation finishes before either collection is changed.
        stock.put(sku, new StockSummary(current.product(), (int) proposed));
        movements.put(requestId, movement);
        return movement;
    }

    private StockSummary requireStock(String sku) {
        StockSummary current = stock.get(sku);
        if (current == null) {
            throw new InventoryException("Unknown product: " + sku);
        }
        return current;
    }

    @Override
    public synchronized InventorySnapshot snapshot() {
        return new InventorySnapshot(findAll(), List.copyOf(movements.values()), List.copyOf(offers.values()), List.copyOf(orders.values()), events);
    }

    @Override
    public synchronized SupplierOffer addOffer(SupplierOffer offer) {
        SupplierOffer previous = offers.get(offer.id());
        if (previous != null && !previous.equals(offer)) throw new InventoryException("Quote request ID already used");
        if (previous != null) return previous;
        if (!requireStock(offer.sku()).product().active()) throw new InventoryException("Product is inactive");
        offers.putIfAbsent(offer.id(), offer);
        return offer;
    }

    @Override
    public synchronized PurchaseOrder createOrder(PurchaseOrder draft) {
        PurchaseOrder previous = orders.get(draft.id());
        if (previous != null) {
            if (!previous.sameRequest(draft)) throw new InventoryException("Order request ID already used with different data");
            return previous;
        }
        SupplierOffer offer = offers.get(draft.offerId());
        if (offer == null) throw new InventoryException("Unknown supplier quote");
        ProcurementRules.validateDraft(draft, offer, requireStock(draft.sku()), List.copyOf(orders.values()));
        orders.put(draft.id(), draft);
        events.add(new OrderEvent(draft.id(), "CREATED", draft.createdBy(), draft.note(), draft.createdAt()));
        return draft;
    }

    @Override
    public synchronized PurchaseOrder transitionOrder(UUID orderId, OrderAction action, UUID actor, String note, Instant at) {
        String reason = PurchaseOrder.validNote(note);
        PurchaseOrder next = requireOrder(orderId).transition(action, actor);
        orders.put(orderId, next);
        events.add(new OrderEvent(orderId, action.name(), actor, reason, at));
        return next;
    }

    @Override
    public synchronized StockMovement receiveOrder(UUID orderId, UUID requestId, int quantity, UUID actor, String note, Instant at) {
        String reason = PurchaseOrder.validNote(note);
        StockMovement previous = movements.get(requestId);
        if (previous != null) {
            ProcurementRules.validateReceiptRetry(previous, receiptOrders.get(requestId), orderId, quantity, actor, reason);
            return previous;
        }
        PurchaseOrder next = requireOrder(orderId).receive(quantity);
        StockMovement movement = changeStock(requestId, next.sku(), quantity, actor, reason,
                balance -> new StockMovement(requestId, next.sku(), MovementType.RECEIPT, quantity, balance, actor, reason, at));
        orders.put(orderId, next);
        receiptOrders.put(requestId, orderId);
        events.add(new OrderEvent(orderId, "RECEIVED " + quantity, actor, reason, at));
        return movement;
    }

    private PurchaseOrder requireOrder(UUID id) {
        PurchaseOrder order = orders.get(id);
        if (order == null) throw new InventoryException("Unknown purchase order");
        return order;
    }
}
