package com.inventory.model;

import com.inventory.model.procurement.OrderEvent;
import com.inventory.model.procurement.PurchaseOrder;
import com.inventory.model.procurement.SupplierOffer;
import java.util.List;

/** Consistent read of operational data, taken under the repository's write lock. */
public record InventorySnapshot(List<StockSummary> stock, List<StockMovement> movements,
                                List<SupplierOffer> offers, List<PurchaseOrder> orders, List<OrderEvent> events) {
    public InventorySnapshot {
        stock = List.copyOf(stock);
        movements = List.copyOf(movements);
        offers = List.copyOf(offers);
        orders = List.copyOf(orders);
        events = List.copyOf(events);
    }
}
