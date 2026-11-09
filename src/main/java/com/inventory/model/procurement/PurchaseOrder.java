package com.inventory.model.procurement;

import com.inventory.exception.InventoryException;
import com.inventory.model.Product;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** Immutable order aggregate. Transition rules apply equally to every storage adapter. */
public record PurchaseOrder(UUID id, String sku, UUID offerId, String supplierName, BigDecimal unitCost,
                            int quantity, int receivedQuantity, OrderStatus status, UUID createdBy,
                            UUID approvedBy, Instant createdAt, LocalDate expectedOn, String note) {
    public PurchaseOrder {
        Objects.requireNonNull(id);
        sku = Product.normalizeSku(sku);
        Objects.requireNonNull(offerId);
        Objects.requireNonNull(createdBy);
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(expectedOn);
        Objects.requireNonNull(status);
        unitCost = new SupplierOffer(offerId, sku, supplierName, unitCost, 0, 1).unitCost();
        supplierName = supplierName.strip();
        note = validNote(note);
        if (quantity <= 0 || receivedQuantity < 0 || receivedQuantity > quantity) {
            throw new IllegalArgumentException("Order quantities are invalid");
        }
        if (approvedBy != null && approvedBy.equals(createdBy)) {
            throw new InventoryException("An order creator cannot approve their own order");
        }
        boolean approvedState = status == OrderStatus.APPROVED || status == OrderStatus.PARTIALLY_RECEIVED || status == OrderStatus.RECEIVED;
        if (approvedState && approvedBy == null) {
            throw new IllegalArgumentException("Approved orders require an independent approver");
        }
        if ((status == OrderStatus.RECEIVED && receivedQuantity != quantity)
                || (status == OrderStatus.PARTIALLY_RECEIVED && (receivedQuantity == 0 || receivedQuantity == quantity))
                || (status != OrderStatus.RECEIVED && status != OrderStatus.PARTIALLY_RECEIVED && receivedQuantity != 0)) {
            throw new IllegalArgumentException("Received quantity does not match order status");
        }
    }

    public static PurchaseOrder draft(UUID requestId, SupplierOffer offer, int quantity, UUID creator,
                                      Instant createdAt, LocalDate expectedOn, String note) {
        if (quantity < offer.minimumQuantity()) {
            throw new IllegalArgumentException("Order quantity must meet supplier minimum: " + offer.minimumQuantity());
        }
        return new PurchaseOrder(requestId, offer.sku(), offer.id(), offer.supplierName(), offer.unitCost(),
                quantity, 0, OrderStatus.DRAFT, creator, null, createdAt, expectedOn, note);
    }

    public PurchaseOrder transition(OrderAction action, UUID actor) {
        return switch (action) {
            case SUBMIT -> {
                require(status == OrderStatus.DRAFT, "Only draft orders can be submitted");
                yield withState(OrderStatus.PENDING_APPROVAL, approvedBy, 0);
            }
            case APPROVE, REJECT -> {
                require(status == OrderStatus.PENDING_APPROVAL, "Only pending orders can be approved or rejected");
                require(!createdBy.equals(actor), "Another authorized user must review this order");
                yield withState(action == OrderAction.APPROVE ? OrderStatus.APPROVED : OrderStatus.REJECTED,
                        action == OrderAction.APPROVE ? actor : null, 0);
            }
            case CANCEL -> {
                require(status.isOpen() && receivedQuantity == 0, "Only open orders without receipts can be cancelled");
                yield withState(OrderStatus.CANCELLED, approvedBy, 0);
            }
        };
    }

    public PurchaseOrder receive(int units) {
        require(status == OrderStatus.APPROVED || status == OrderStatus.PARTIALLY_RECEIVED, "Approve the order before receiving stock");
        require(units > 0 && units <= outstanding(), "Receipt must be positive and cannot exceed outstanding quantity: " + outstanding());
        int total = receivedQuantity + units;
        return withState(total == quantity ? OrderStatus.RECEIVED : OrderStatus.PARTIALLY_RECEIVED, approvedBy, total);
    }

    public int outstanding() { return quantity - receivedQuantity; }
    public String number() { return "PO-" + id.toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT); }
    public BigDecimal totalCost() { return unitCost.multiply(BigDecimal.valueOf(quantity)); }

    public boolean sameRequest(PurchaseOrder candidate) {
        return id.equals(candidate.id) && sku.equals(candidate.sku) && offerId.equals(candidate.offerId)
                && quantity == candidate.quantity && createdBy.equals(candidate.createdBy) && note.equals(candidate.note);
    }

    public static String validNote(String note) {
        if (note == null || note.isBlank() || note.strip().length() > 2000) {
            throw new IllegalArgumentException("A reason containing 1–2000 characters is required");
        }
        return note.strip();
    }

    private PurchaseOrder withState(OrderStatus next, UUID approver, int received) {
        return new PurchaseOrder(id, sku, offerId, supplierName, unitCost, quantity, received,
                next, createdBy, approver, createdAt, expectedOn, note);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new InventoryException(message);
    }
}
