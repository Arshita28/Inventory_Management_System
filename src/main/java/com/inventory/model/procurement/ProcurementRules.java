package com.inventory.model.procurement;

import com.inventory.exception.InventoryException;
import com.inventory.model.StockMovement;
import com.inventory.model.StockSummary;
import java.util.List;
import java.util.UUID;

/** Shared checks used inside both repository transaction boundaries. */
public final class ProcurementRules {
    private ProcurementRules() { }

    public static void validateDraft(PurchaseOrder draft, SupplierOffer offer, StockSummary stock, List<PurchaseOrder> orders) {
        if (!stock.product().active() || draft.status() != OrderStatus.DRAFT || draft.approvedBy() != null || !offer.sku().equals(draft.sku())
                || !offer.supplierName().equals(draft.supplierName()) || !offer.unitCost().equals(draft.unitCost())
                || draft.quantity() < offer.minimumQuantity()) {
            throw new InventoryException("Order must use an active product and a matching supplier quote");
        }
        if (orders.stream().anyMatch(order -> order.sku().equals(draft.sku()) && order.status().isOpen())) {
            throw new InventoryException("An open purchase order already exists for this product; review it before ordering again");
        }
    }

    public static void validateReceiptRetry(StockMovement previous, UUID linkedOrder, UUID orderId,
                                           int quantity, UUID actor, String note) {
        if (!orderId.equals(linkedOrder) || previous.quantity() != quantity
                || !previous.actorId().equals(actor) || !previous.reason().equals(note)) {
            throw new InventoryException("Receipt request ID has already been used with different data");
        }
    }
}
