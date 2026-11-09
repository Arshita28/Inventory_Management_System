CREATE TABLE IF NOT EXISTS supplier_offers (
    id VARCHAR(36) PRIMARY KEY,
    sku VARCHAR(40) NOT NULL REFERENCES products(sku),
    supplier_name VARCHAR(255) NOT NULL,
    unit_cost DECIMAL(19,2) NOT NULL CHECK (unit_cost >= 0),
    lead_days INTEGER NOT NULL CHECK (lead_days BETWEEN 0 AND 365),
    minimum_quantity INTEGER NOT NULL CHECK (minimum_quantity > 0)
);
CREATE TABLE IF NOT EXISTS purchase_orders (
    id VARCHAR(36) PRIMARY KEY,
    sku VARCHAR(40) NOT NULL REFERENCES products(sku),
    offer_id VARCHAR(36) NOT NULL REFERENCES supplier_offers(id),
    supplier_name VARCHAR(255) NOT NULL,
    unit_cost DECIMAL(19,2) NOT NULL CHECK (unit_cost >= 0),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    received_quantity INTEGER NOT NULL CHECK (received_quantity >= 0 AND received_quantity <= quantity),
    status VARCHAR(30) NOT NULL CHECK (status IN ('DRAFT','PENDING_APPROVAL','APPROVED','PARTIALLY_RECEIVED','RECEIVED','REJECTED','CANCELLED')),
    created_by VARCHAR(36) NOT NULL,
    approved_by VARCHAR(36),
    created_at VARCHAR(40) NOT NULL,
    expected_on DATE NOT NULL,
    note VARCHAR(2000) NOT NULL,
    CHECK (approved_by IS NULL OR approved_by <> created_by)
);
CREATE INDEX IF NOT EXISTS idx_orders_sku ON purchase_orders(sku);
CREATE TABLE IF NOT EXISTS purchase_receipts (
    request_id VARCHAR(36) PRIMARY KEY REFERENCES stock_movements(request_id),
    order_id VARCHAR(36) NOT NULL REFERENCES purchase_orders(id)
);
CREATE TABLE IF NOT EXISTS purchase_events (
    event_sequence BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id VARCHAR(36) NOT NULL REFERENCES purchase_orders(id),
    action VARCHAR(40) NOT NULL,
    actor_id VARCHAR(36) NOT NULL,
    note VARCHAR(2000) NOT NULL,
    occurred_at VARCHAR(40) NOT NULL
);
INSERT INTO schema_migrations (version, description)
SELECT 3, 'Supplier quotes and purchase order lifecycle'
WHERE NOT EXISTS (SELECT 1 FROM schema_migrations WHERE version = 3);
