package com.inventory.service;

import com.inventory.exception.InventoryException;
import com.inventory.model.MovementType;
import com.inventory.model.Product;
import com.inventory.model.StockMovement;
import com.inventory.model.StockSummary;
import com.inventory.model.details.ProductDetails;
import com.inventory.model.details.LegacyDetails;
import com.inventory.model.ProductCategory;
import com.inventory.repository.InventoryRepository;
import com.inventory.security.AuthorizationService;
import com.inventory.security.Permission;
import com.inventory.security.User;

import java.time.Clock;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** UI-independent use cases. Every public operation checks the actor's permissions. */
public final class InventoryService {
    private final InventoryRepository repository;
    private final AuthorizationService authorization;
    private final Clock clock;

    public InventoryService(InventoryRepository repository, AuthorizationService authorization, Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.authorization = Objects.requireNonNull(authorization);
        this.clock = Objects.requireNonNull(clock);
    }

    public String storageLabel() {return repository instanceof com.inventory.repository.jdbc.JdbcInventoryRepository jdbc?jdbc.database().label():"Memory";}
    public boolean ready() {return !(repository instanceof com.inventory.repository.jdbc.JdbcInventoryRepository jdbc) || jdbc.database().ready();}

    public BusinessService business() {
        return repository instanceof com.inventory.repository.jdbc.JdbcInventoryRepository jdbc
                ? new BusinessService(jdbc.database(), authorization, clock) : null;
    }

    public OperationsService operations() {
        return new OperationsService(repository, authorization, clock, java.time.ZoneId.of("Asia/Kolkata"));
    }

    public void addProduct(User actor, Product product) {
        authorization.require(actor, Permission.MANAGE_PRODUCTS);
        repository.add(Objects.requireNonNull(product));
    }

    public void deactivateProduct(User actor, String sku) {
        authorization.require(actor, Permission.MANAGE_PRODUCTS);
        repository.deactivate(Product.normalizeSku(sku));
    }

    public void updateProduct(User actor, String sku, String name, String category,
                              BigDecimal price, int reorderLevel) {
        authorization.require(actor, Permission.MANAGE_PRODUCTS);
        Product current = getStock(actor, sku).product();
        if (current.details().category() != ProductCategory.LEGACY && !current.category().equals(category)) {
            throw new IllegalArgumentException("Category changes require matching electronics specifications");
        }
        ProductDetails details = current.details().category() == ProductCategory.LEGACY ? new LegacyDetails(category) : current.details();
        repository.update(new Product(current.sku(), name, category, price, reorderLevel, current.active(), details));
    }

    public void updateProduct(User actor, String sku, String name, BigDecimal price,
                              int reorderLevel, ProductDetails details) {
        authorization.require(actor, Permission.MANAGE_PRODUCTS);
        Product current = getStock(actor, sku).product();
        if (current.details().category() != ProductCategory.LEGACY && details.category() == ProductCategory.LEGACY) {
            throw new IllegalArgumentException("An electronics product cannot be changed back to a legacy record");
        }
        repository.update(new Product(current.sku(), name, price, reorderLevel, current.active(), details));
    }

    public StockSummary getStock(User actor, String sku) {
        authorization.require(actor, Permission.VIEW_INVENTORY);
        String normalized = Product.normalizeSku(sku);
        return repository.find(normalized)
                .orElseThrow(() -> new InventoryException("Unknown product: " + normalized));
    }

    public List<StockSummary> listProducts(User actor) {
        authorization.require(actor, Permission.VIEW_INVENTORY);
        return repository.findAll();
    }

    public List<StockSummary> lowStock(User actor) {
        return listProducts(actor).stream().filter(StockSummary::isLowStock).toList();
    }

    public List<StockMovement> history(User actor, String sku) {
        authorization.require(actor, Permission.VIEW_HISTORY);
        return repository.history(Product.normalizeSku(sku));
    }

    public StockMovement receiveStock(User actor, UUID requestId, String sku, int quantity, String reason) {
        authorization.require(actor, Permission.RECEIVE_STOCK);
        return changeStock(actor, requestId, sku, quantity, reason, MovementType.RECEIPT);
    }

    public StockMovement sellStock(User actor, UUID requestId, String sku, int quantity, String reason) {
        authorization.require(actor, Permission.SELL_STOCK);
        return changeStock(actor, requestId, sku, quantity, reason, MovementType.SALE);
    }

    private StockMovement changeStock(User actor, UUID requestId, String sku, int quantity,
                                      String reason, MovementType type) {
        Objects.requireNonNull(requestId, "Request ID is required");
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A reason is required");
        }
        String normalized = Product.normalizeSku(sku);
        String cleanReason = reason.strip();
        if (cleanReason.length() > 2000) {
            throw new IllegalArgumentException("Reason must be at most 2000 characters");
        }
        int delta = type == MovementType.RECEIPT ? quantity : -quantity;
        return repository.changeStock(requestId, normalized, delta, actor.id(), cleanReason,
                balance -> new StockMovement(requestId, normalized, type, delta, balance,
                        actor.id(), cleanReason, clock.instant()));
    }
}
