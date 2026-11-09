package com.inventory.repository;

import com.inventory.model.Product;
import com.inventory.model.StockMovement;
import com.inventory.model.StockSummary;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntFunction;
import com.inventory.model.InventorySnapshot;
import com.inventory.model.procurement.OrderAction;
import com.inventory.model.procurement.SupplierOffer;
import com.inventory.model.procurement.PurchaseOrder;
import java.time.Instant;

/**
 * Persistence boundary. Implementations must return immutable snapshots and make
 * each write atomic. A JDBC implementation must lock and commit balance/history together.
 */
public interface InventoryRepository {
    void add(Product product);

    /** Replace catalogue metadata without changing SKU, active state, or stock. */
    void update(Product product);

    void deactivate(String sku);

    Optional<StockSummary> find(String sku);

    List<StockSummary> findAll();

    List<StockMovement> history(String sku);

    /**
     * Check active product and stock, then persist balance and history together.
     * Request IDs are globally unique. An identical retry returns its original result;
     * reuse with different data fails. Failed requests do not consume their ID.
     * The factory must be side-effect-free; it receives the proposed resulting balance.
     */
    StockMovement changeStock(UUID requestId, String sku, int delta, UUID actorId,
                              String reason, IntFunction<StockMovement> movementFactory);

    InventorySnapshot snapshot();
    SupplierOffer addOffer(SupplierOffer offer);
    PurchaseOrder createOrder(PurchaseOrder draft);
    PurchaseOrder transitionOrder(UUID orderId, OrderAction action, UUID actorId, String note, Instant occurredAt);
    StockMovement receiveOrder(UUID orderId, UUID requestId, int quantity, UUID actorId, String note, Instant occurredAt);
}
