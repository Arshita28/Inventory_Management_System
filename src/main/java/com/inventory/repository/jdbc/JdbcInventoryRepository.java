package com.inventory.repository.jdbc;

import com.inventory.exception.InsufficientStockException;
import com.inventory.exception.InventoryException;
import com.inventory.exception.PersistenceException;
import com.inventory.model.MovementType;
import com.inventory.model.Product;
import com.inventory.model.StockMovement;
import com.inventory.model.StockSummary;
import com.inventory.model.InventorySnapshot;
import com.inventory.model.procurement.OrderStatus;
import com.inventory.model.procurement.OrderAction;
import com.inventory.model.procurement.SupplierOffer;
import com.inventory.model.procurement.OrderEvent;
import com.inventory.model.procurement.PurchaseOrder;
import com.inventory.model.procurement.ProcurementRules;
import com.inventory.repository.InventoryRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntFunction;

/**
 * H2-backed JDBC storage. Each operation owns its connection. A database mutex row
 * serializes writes across repository instances, including request-ID checks.
 * This intentionally favors a clear correctness model over write throughput.
 */
public final class JdbcInventoryRepository implements InventoryRepository {
    private final com.inventory.database.Database database;

    public JdbcInventoryRepository(String jdbcUrl) {
        this(com.inventory.database.Database.h2(jdbcUrl));
    }

    public JdbcInventoryRepository(com.inventory.database.Database database) {
        this.database=Objects.requireNonNull(database);
        initializeSchema();
    }

    public com.inventory.database.Database database() {return database;}

    public String jdbcUrl() { return database.jdbcUrl(); }

    @Override
    public void add(Product product) {
        transaction(connection -> {
            lockWrites(connection);
            if (find(connection, product.sku()).isPresent()) {
                throw new InventoryException("SKU already exists: " + product.sku());
            }
            try (var statement = connection.prepareStatement("""
                    INSERT INTO products (sku, name, category, selling_price, reorder_level, active, quantity, details_type, details_data)
                    VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?)
                    """)) {
                statement.setString(1, product.sku());
                statement.setString(2, product.name());
                statement.setString(3, product.category());
                statement.setBigDecimal(4, product.sellingPrice());
                statement.setInt(5, product.reorderLevel());
                statement.setBoolean(6, product.active());
                statement.setString(7, product.details().category().name());
                statement.setString(8, ProductDetailsCodec.encode(product.details()));
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public void update(Product product) {
        transaction(connection -> {
            lockWrites(connection);
            StockSummary current = requireStock(connection, product.sku());
            if (product.active() != current.product().active()) {
                throw new InventoryException("Active state cannot be changed by a catalogue edit");
            }
            try (var statement = connection.prepareStatement("""
                    UPDATE products SET name = ?, category = ?, selling_price = ?, reorder_level = ?, details_type = ?, details_data = ? WHERE sku = ?
                    """)) {
                statement.setString(1, product.name());
                statement.setString(2, product.category());
                statement.setBigDecimal(3, product.sellingPrice());
                statement.setInt(4, product.reorderLevel());
                statement.setString(5, product.details().category().name());
                statement.setString(6, ProductDetailsCodec.encode(product.details()));
                statement.setString(7, product.sku());
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public void deactivate(String sku) {
        transaction(connection -> {
            lockWrites(connection);
            StockSummary stock = requireStock(connection, sku);
            if (stock.quantity() != 0) {
                throw new InventoryException("Product must have zero stock before deactivation");
            }
            if (loadOrders(connection).stream().anyMatch(order -> order.sku().equals(sku) && order.status().isOpen())) {
                throw new InventoryException("Close the product's open purchase order before deactivation");
            }
            try (var statement = connection.prepareStatement("UPDATE products SET active = FALSE WHERE sku = ?")) {
                statement.setString(1, sku);
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public Optional<StockSummary> find(String sku) {
        return transaction(connection -> find(connection, sku));
    }

    @Override
    public List<StockSummary> findAll() {
        return transaction(connection -> {
            List<StockSummary> results = new ArrayList<>();
            try (var statement = connection.prepareStatement("SELECT * FROM products ORDER BY sku");
                 var rows = statement.executeQuery()) {
                while (rows.next()) {
                    results.add(readStock(rows));
                }
            }
            return List.copyOf(results);
        });
    }

    @Override
    public List<StockMovement> history(String sku) {
        return transaction(connection -> {
            requireStock(connection, sku);
            List<StockMovement> results = new ArrayList<>();
            try (var statement = connection.prepareStatement(
                    "SELECT * FROM stock_movements WHERE sku = ? ORDER BY movement_sequence")) {
                statement.setString(1, sku);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        results.add(readMovement(rows));
                    }
                }
            }
            return List.copyOf(results);
        });
    }

    @Override
    public StockMovement changeStock(UUID requestId, String sku, int delta, UUID actorId,
                                     String reason, IntFunction<StockMovement> factory) {
        return transaction(connection -> {
            lockWrites(connection);
            Optional<StockMovement> previous = findMovement(connection, requestId);
            if (previous.isPresent()) {
                StockMovement movement = previous.get();
                if (!movement.sku().equals(sku) || movement.quantity() != delta
                        || !movement.actorId().equals(actorId) || !movement.reason().equals(reason)) {
                    throw new InventoryException("Request ID already used with different stock-change data");
                }
                return movement;
            }
            if (delta == 0) {
                throw new IllegalArgumentException("Stock change cannot be zero");
            }
            StockSummary stock = requireStock(connection, sku);
            if (!stock.product().active()) {
                throw new InventoryException("Product is inactive: " + sku);
            }
            long proposed = (long) stock.quantity() + delta;
            if (proposed < 0) {
                throw new InsufficientStockException(sku, stock.quantity(), Math.negateExact(delta));
            }
            if (proposed > Integer.MAX_VALUE) {
                throw new InventoryException("Stock quantity exceeds supported maximum");
            }
            StockMovement movement = factory.apply((int) proposed);
            if (!movement.id().equals(requestId) || !movement.sku().equals(sku)
                    || movement.quantity() != delta || movement.balanceAfter() != proposed
                    || !movement.actorId().equals(actorId) || !movement.reason().equals(reason)) {
                throw new IllegalArgumentException("Movement does not match the requested stock change");
            }
            try (var statement = connection.prepareStatement("UPDATE products SET quantity = ? WHERE sku = ?")) {
                statement.setInt(1, (int) proposed);
                statement.setString(2, sku);
                statement.executeUpdate();
            }
            insertMovement(connection, movement);
            return movement;
        });
    }

    private void insertMovement(Connection connection, StockMovement movement) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO stock_movements
                (request_id, sku, movement_type, quantity, balance_after, actor_id, reason, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, movement.id().toString());
            statement.setString(2, movement.sku());
            statement.setString(3, movement.type().name());
            statement.setInt(4, movement.quantity());
            statement.setInt(5, movement.balanceAfter());
            statement.setString(6, movement.actorId().toString());
            statement.setString(7, movement.reason());
            statement.setString(8, movement.occurredAt().toString());
            statement.executeUpdate();
        }
    }

    private Optional<StockMovement> findMovement(Connection connection, UUID id) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM stock_movements WHERE request_id = ?")) {
            statement.setString(1, id.toString());
            try (var rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(readMovement(rows)) : Optional.empty();
            }
        }
    }

    private Optional<StockSummary> find(Connection connection, String sku) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM products WHERE sku = ?")) {
            statement.setString(1, sku);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(readStock(rows)) : Optional.empty();
            }
        }
    }

    private StockSummary requireStock(Connection connection, String sku) throws SQLException {
        return find(connection, sku).orElseThrow(() -> new InventoryException("Unknown product: " + sku));
    }

    private StockSummary readStock(ResultSet rows) throws SQLException {
        Product product = new Product(rows.getString("sku"), rows.getString("name"),
                rows.getString("category"), rows.getBigDecimal("selling_price"),
                rows.getInt("reorder_level"), rows.getBoolean("active"),
                ProductDetailsCodec.decode(rows.getString("details_type"), rows.getString("details_data"), rows.getString("category")));
        return new StockSummary(product, rows.getInt("quantity"));
    }

    private StockMovement readMovement(ResultSet rows) throws SQLException {
        return new StockMovement(UUID.fromString(rows.getString("request_id")), rows.getString("sku"),
                MovementType.valueOf(rows.getString("movement_type")), rows.getInt("quantity"),
                rows.getInt("balance_after"), UUID.fromString(rows.getString("actor_id")),
                rows.getString("reason"), Instant.parse(rows.getString("occurred_at")));
    }

    private void lockWrites(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT id FROM inventory_write_lock WHERE id = 1 FOR UPDATE");
             var rows = statement.executeQuery()) {
            if (!rows.next()) {
                throw new SQLException("Inventory write lock is missing");
            }
        }
    }

    private void initializeSchema() {
        if(database.postgres()) {com.inventory.database.PostgresSchema.initialize(database);return;}
        try (Connection connection = connect()) {
            executeScript(connection, "/database/schema-h2.sql");
            executeScript(connection, "/database/V002__electronics_details.sql");
            executeScript(connection, "/database/V003__procurement.sql");
            executeScript(connection, "/database/V005__business.sql");
            java.util.List<String> checks = new java.util.ArrayList<>();
            try (var query = connection.prepareStatement("SELECT tc.CONSTRAINT_NAME, cc.CHECK_CLAUSE FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc JOIN INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc ON tc.CONSTRAINT_NAME=cc.CONSTRAINT_NAME AND tc.CONSTRAINT_SCHEMA=cc.CONSTRAINT_SCHEMA WHERE tc.TABLE_NAME='STOCK_MOVEMENTS'"); var rows=query.executeQuery()) {
                while(rows.next()) if(rows.getString(2).contains("MOVEMENT_TYPE")) checks.add(rows.getString(1));
            }
            try(var statement=connection.createStatement()) {
                for(String name:checks) statement.execute("ALTER TABLE stock_movements DROP CONSTRAINT \""+name.replace("\"","\"\"")+"\"");
                statement.execute("ALTER TABLE stock_movements ADD CONSTRAINT valid_movement_type CHECK ((movement_type='RECEIPT' AND quantity>0) OR (movement_type='SALE' AND quantity<0) OR (movement_type='RETURN' AND quantity>0) OR movement_type='ADJUSTMENT')");
            }
        } catch (IOException | SQLException exception) {
            throw new PersistenceException("Unable to initialize inventory storage", exception);
        }
    }

    private void executeScript(Connection connection, String resource) throws IOException, SQLException {
        try (var input = JdbcInventoryRepository.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Database schema resource is missing");
            }
            String schema = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            try (var statement = connection.createStatement()) {
                for (String sql : schema.split(";")) {
                    if (!sql.isBlank()) {
                        statement.execute(sql);
                    }
                }
            }
        }
    }

    private Connection connect() throws SQLException {
        return database.connect();
    }

    @Override
    public InventorySnapshot snapshot() {
        return transaction(connection -> {
            lockWrites(connection);
            List<StockSummary> stock = new ArrayList<>();
            List<StockMovement> movements = new ArrayList<>();
            List<OrderEvent> events = new ArrayList<>();
            try (var statement = connection.prepareStatement("SELECT * FROM products ORDER BY sku"); var rows = statement.executeQuery()) {
                while (rows.next()) stock.add(readStock(rows));
            }
            try (var statement = connection.prepareStatement("SELECT * FROM stock_movements ORDER BY movement_sequence"); var rows = statement.executeQuery()) {
                while (rows.next()) movements.add(readMovement(rows));
            }
            try (var statement = connection.prepareStatement("SELECT * FROM purchase_events ORDER BY event_sequence"); var rows = statement.executeQuery()) {
                while (rows.next()) events.add(new OrderEvent(UUID.fromString(rows.getString("order_id")), rows.getString("action"),
                        UUID.fromString(rows.getString("actor_id")), rows.getString("note"), Instant.parse(rows.getString("occurred_at"))));
            }
            return new InventorySnapshot(stock, movements, loadOffers(connection), loadOrders(connection), events);
        });
    }

    @Override
    public SupplierOffer addOffer(SupplierOffer offer) {
        return transaction(connection -> {
            lockWrites(connection);
            var previous = loadOffers(connection).stream().filter(value -> value.id().equals(offer.id())).findFirst();
            if (previous.isPresent()) {
                if (!previous.get().equals(offer)) throw new InventoryException("Quote request ID already used");
                return previous.get();
            }
            if (!requireStock(connection, offer.sku()).product().active()) throw new InventoryException("Product is inactive");
            try (var statement = connection.prepareStatement("INSERT INTO supplier_offers VALUES (?, ?, ?, ?, ?, ?)")) {
                statement.setString(1, offer.id().toString());
                statement.setString(2, offer.sku());
                statement.setString(3, offer.supplierName());
                statement.setBigDecimal(4, offer.unitCost());
                statement.setInt(5, offer.leadDays());
                statement.setInt(6, offer.minimumQuantity());
                statement.executeUpdate();
            }
            return offer;
        });
    }

    @Override
    public PurchaseOrder createOrder(PurchaseOrder draft) {
        return transaction(connection -> { lockWrites(connection); return createOrder(connection,draft); });
    }

    public java.util.List<PurchaseOrder> createOrderBatch(UUID groupId,java.util.List<PurchaseOrder> drafts) {
        return transaction(connection -> {
            lockWrites(connection);
            if(drafts.isEmpty() || drafts.size()>10) throw new IllegalArgumentException("Choose between 1 and 10 products");
            java.util.Set<UUID> existingIds=new java.util.HashSet<>();
            try(var statement=connection.prepareStatement("SELECT id FROM purchase_orders WHERE group_id=?")) {
                statement.setString(1,groupId.toString());try(var rows=statement.executeQuery()){while(rows.next()) existingIds.add(UUID.fromString(rows.getString(1)));}
            }
            if(!existingIds.isEmpty() && !existingIds.equals(drafts.stream().map(PurchaseOrder::id).collect(java.util.stream.Collectors.toSet()))) throw new InventoryException("Purchase request already used with different lines");
            String supplier=drafts.getFirst().supplierName();
            java.util.Set<String> skus=new java.util.HashSet<>();
            for(var draft:drafts) if(!draft.supplierName().equals(supplier) || !skus.add(draft.sku())) throw new IllegalArgumentException("Choose distinct products from the same supplier");
            java.util.List<PurchaseOrder> result=new java.util.ArrayList<>();
            for(var draft:drafts) {
                result.add(createOrder(connection,draft));
                try(var statement=connection.prepareStatement("UPDATE purchase_orders SET group_id=? WHERE id=? AND (group_id IS NULL OR group_id=?)")) {
                    statement.setString(1,groupId.toString());statement.setString(2,draft.id().toString());statement.setString(3,groupId.toString());
                    if(statement.executeUpdate()!=1) throw new InventoryException("Purchase line belongs to another order");
                }
            }
            return java.util.List.copyOf(result);
        });
    }

    public void transitionBatch(UUID groupId, OrderAction action, UUID actor, String note, Instant at) {
        transaction(connection -> {
            lockWrites(connection);
            java.util.List<UUID> ids=new java.util.ArrayList<>();
            try(var statement=connection.prepareStatement("SELECT id FROM purchase_orders WHERE group_id=?")) {
                statement.setString(1,groupId.toString());try(var rows=statement.executeQuery()){while(rows.next()) ids.add(UUID.fromString(rows.getString(1)));}
            }
            if(ids.isEmpty()) throw new InventoryException("Purchase order not found");
            for(UUID id:ids) {
                PurchaseOrder next=requireOrder(connection,id).transition(action,actor);
                updateOrderState(connection,next);
                addEvent(connection,new OrderEvent(id,action.name(),actor,PurchaseOrder.validNote(note),at));
            }
            return null;
        });
    }

    private PurchaseOrder createOrder(Connection connection,PurchaseOrder draft) throws SQLException {

            List<PurchaseOrder> orders = loadOrders(connection);
            var previous = orders.stream().filter(order -> order.id().equals(draft.id())).findFirst();
            if (previous.isPresent()) {
                if (!previous.get().sameRequest(draft)) throw new InventoryException("Order request ID already used with different data");
                return previous.get();
            }
            SupplierOffer offer = loadOffers(connection).stream().filter(value -> value.id().equals(draft.offerId())).findFirst()
                    .orElseThrow(() -> new InventoryException("Unknown supplier quote"));
            ProcurementRules.validateDraft(draft, offer, requireStock(connection, draft.sku()), orders);
            try (var statement = connection.prepareStatement("""
                    INSERT INTO purchase_orders (id, sku, offer_id, supplier_name, unit_cost, quantity,
                    received_quantity, status, created_by, approved_by, created_at, expected_on, note)
                    VALUES (?, ?, ?, ?, ?, ?, 0, 'DRAFT', ?, NULL, ?, ?, ?)
                    """)) {
                statement.setString(1, draft.id().toString());
                statement.setString(2, draft.sku());
                statement.setString(3, draft.offerId().toString());
                statement.setString(4, draft.supplierName());
                statement.setBigDecimal(5, draft.unitCost());
                statement.setInt(6, draft.quantity());
                statement.setString(7, draft.createdBy().toString());
                statement.setString(8, draft.createdAt().toString());
                statement.setDate(9, java.sql.Date.valueOf(draft.expectedOn()));
                statement.setString(10, draft.note());
                statement.executeUpdate();
            }
            addEvent(connection, new OrderEvent(draft.id(), "CREATED", draft.createdBy(), draft.note(), draft.createdAt()));
            return draft;
    }

    @Override
    public PurchaseOrder transitionOrder(UUID id, OrderAction action, UUID actor, String note, Instant at) {
        return transaction(connection -> {
            lockWrites(connection);
            String reason = PurchaseOrder.validNote(note);
            PurchaseOrder next = requireOrder(connection, id).transition(action, actor);
            updateOrderState(connection, next);
            addEvent(connection, new OrderEvent(id, action.name(), actor, reason, at));
            return next;
        });
    }

    @Override
    public StockMovement receiveOrder(UUID orderId, UUID requestId, int quantity, UUID actor, String note, Instant at) {
        return transaction(connection -> {
            lockWrites(connection);
            String reason = PurchaseOrder.validNote(note);
            var previous = findMovement(connection, requestId);
            if (previous.isPresent()) {
                UUID linkedOrder = null;
                try (var statement = connection.prepareStatement("SELECT order_id FROM purchase_receipts WHERE request_id = ?")) {
                    statement.setString(1, requestId.toString());
                    try (var rows = statement.executeQuery()) {
                        if (rows.next()) linkedOrder = UUID.fromString(rows.getString(1));
                    }
                }
                ProcurementRules.validateReceiptRetry(previous.get(), linkedOrder, orderId, quantity, actor, reason);
                return previous.get();
            }
            PurchaseOrder next = requireOrder(connection, orderId).receive(quantity);
            StockSummary stock = requireStock(connection, next.sku());
            if (!stock.product().active()) throw new InventoryException("Product is inactive");
            long balance = (long) stock.quantity() + quantity;
            if (balance > Integer.MAX_VALUE) throw new InventoryException("Stock quantity exceeds supported maximum");
            StockMovement movement = new StockMovement(requestId, next.sku(), MovementType.RECEIPT, quantity, (int) balance, actor, reason, at);
            try (var statement = connection.prepareStatement("UPDATE products SET quantity = ? WHERE sku = ?")) {
                statement.setInt(1, (int) balance);
                statement.setString(2, next.sku());
                statement.executeUpdate();
            }
            insertMovement(connection, movement);
            updateOrderState(connection, next);
            try (var statement = connection.prepareStatement("INSERT INTO purchase_receipts VALUES (?, ?)")) {
                statement.setString(1, requestId.toString());
                statement.setString(2, orderId.toString());
                statement.executeUpdate();
            }
            addEvent(connection, new OrderEvent(orderId, "RECEIVED " + quantity, actor, reason, at));
            return movement;
        });
    }

    private List<SupplierOffer> loadOffers(Connection connection) throws SQLException {
        List<SupplierOffer> offers = new ArrayList<>();
        try (var statement = connection.prepareStatement("SELECT * FROM supplier_offers ORDER BY supplier_name, id"); var rows = statement.executeQuery()) {
            while (rows.next()) offers.add(new SupplierOffer(UUID.fromString(rows.getString("id")), rows.getString("sku"),
                    rows.getString("supplier_name"), rows.getBigDecimal("unit_cost"), rows.getInt("lead_days"), rows.getInt("minimum_quantity")));
        }
        return offers;
    }

    private List<PurchaseOrder> loadOrders(Connection connection) throws SQLException {
        List<PurchaseOrder> orders = new ArrayList<>();
        try (var statement = connection.prepareStatement("SELECT * FROM purchase_orders ORDER BY created_at, id"); var rows = statement.executeQuery()) {
            while (rows.next()) orders.add(new PurchaseOrder(UUID.fromString(rows.getString("id")), rows.getString("sku"),
                    UUID.fromString(rows.getString("offer_id")), rows.getString("supplier_name"), rows.getBigDecimal("unit_cost"),
                    rows.getInt("quantity"), rows.getInt("received_quantity"), OrderStatus.valueOf(rows.getString("status")),
                    UUID.fromString(rows.getString("created_by")), rows.getString("approved_by") == null ? null : UUID.fromString(rows.getString("approved_by")),
                    Instant.parse(rows.getString("created_at")), rows.getDate("expected_on").toLocalDate(), rows.getString("note")));
        }
        return orders;
    }

    private PurchaseOrder requireOrder(Connection connection, UUID id) throws SQLException {
        return loadOrders(connection).stream().filter(order -> order.id().equals(id)).findFirst()
                .orElseThrow(() -> new InventoryException("Unknown purchase order"));
    }

    private void updateOrderState(Connection connection, PurchaseOrder order) throws SQLException {
        try (var statement = connection.prepareStatement("UPDATE purchase_orders SET status = ?, approved_by = ?, received_quantity = ? WHERE id = ?")) {
            statement.setString(1, order.status().name());
            statement.setString(2, order.approvedBy() == null ? null : order.approvedBy().toString());
            statement.setInt(3, order.receivedQuantity());
            statement.setString(4, order.id().toString());
            statement.executeUpdate();
        }
    }

    private void addEvent(Connection connection, OrderEvent event) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO purchase_events (order_id, action, actor_id, note, occurred_at) VALUES (?, ?, ?, ?, ?)")) {
            statement.setString(1, event.orderId().toString());
            statement.setString(2, event.action());
            statement.setString(3, event.actorId().toString());
            statement.setString(4, event.note());
            statement.setString(5, event.occurredAt().toString());
            statement.executeUpdate();
        }
    }

    private <T> T transaction(SqlOperation<T> operation) {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                T result = operation.execute(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                throw exception;
            }
        } catch (SQLException exception) {
            throw new PersistenceException("Inventory storage operation failed", exception);
        }
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T execute(Connection connection) throws SQLException;
    }
}
