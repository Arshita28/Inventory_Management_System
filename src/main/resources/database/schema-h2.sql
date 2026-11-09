CREATE TABLE IF NOT EXISTS inventory_write_lock (
    id INTEGER PRIMARY KEY CHECK (id = 1)
);
INSERT INTO inventory_write_lock (id)
SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM inventory_write_lock WHERE id = 1);

CREATE TABLE IF NOT EXISTS products (
    sku VARCHAR(40) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    category VARCHAR(255) NOT NULL,
    selling_price DECIMAL(19, 2) NOT NULL CHECK (selling_price >= 0),
    reorder_level INTEGER NOT NULL CHECK (reorder_level >= 0),
    active BOOLEAN NOT NULL,
    quantity INTEGER NOT NULL DEFAULT 0 CHECK (quantity >= 0)
);

CREATE TABLE IF NOT EXISTS stock_movements (
    movement_sequence BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    request_id VARCHAR(36) NOT NULL UNIQUE,
    sku VARCHAR(40) NOT NULL REFERENCES products(sku),
    movement_type VARCHAR(20) NOT NULL CHECK (movement_type IN ('RECEIPT', 'SALE')),
    quantity INTEGER NOT NULL CHECK (quantity <> 0),
    balance_after INTEGER NOT NULL CHECK (balance_after >= 0),
    actor_id VARCHAR(36) NOT NULL,
    reason VARCHAR(2000) NOT NULL,
    occurred_at VARCHAR(40) NOT NULL,
    CHECK ((movement_type = 'RECEIPT' AND quantity > 0)
        OR (movement_type = 'SALE' AND quantity < 0))
);
CREATE INDEX IF NOT EXISTS idx_stock_movements_sku ON stock_movements(sku, movement_sequence);
