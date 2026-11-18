-- PostgreSQL baseline includes retained historical tables for lossless H2 imports.
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
    movement_type VARCHAR(20) NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity <> 0),
    balance_after INTEGER NOT NULL CHECK (balance_after >= 0),
    actor_id VARCHAR(36) NOT NULL,
    reason VARCHAR(2000) NOT NULL,
    occurred_at VARCHAR(40) NOT NULL,
    CONSTRAINT valid_movement_type CHECK ((movement_type='RECEIPT' AND quantity>0) OR (movement_type='SALE' AND quantity<0) OR (movement_type='RETURN' AND quantity>0) OR movement_type='ADJUSTMENT')
);
CREATE INDEX IF NOT EXISTS idx_stock_movements_sku ON stock_movements(sku, movement_sequence);

CREATE TABLE IF NOT EXISTS schema_migrations (
    version INTEGER PRIMARY KEY,
    description VARCHAR(255) NOT NULL
);
ALTER TABLE products ADD COLUMN IF NOT EXISTS details_type VARCHAR(40) NOT NULL DEFAULT 'LEGACY';
ALTER TABLE products ADD COLUMN IF NOT EXISTS details_data TEXT NOT NULL DEFAULT '';
INSERT INTO schema_migrations (version, description)
SELECT 2, 'Electronics category details, preserve existing rows as legacy'
WHERE NOT EXISTS (SELECT 1 FROM schema_migrations WHERE version = 2);

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

CREATE TABLE IF NOT EXISTS access_write_lock (id INTEGER PRIMARY KEY CHECK (id = 1));
INSERT INTO access_write_lock (id) SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM access_write_lock WHERE id = 1);
CREATE TABLE IF NOT EXISTS app_users (
    id VARCHAR(36) PRIMARY KEY,
    username VARCHAR(40) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL CHECK (role IN ('ADMIN', 'MANAGER', 'STAFF')),
    active BOOLEAN NOT NULL,
    created_at VARCHAR(40) NOT NULL
);
CREATE TABLE IF NOT EXISTS access_events (
    event_sequence BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_id VARCHAR(36) NOT NULL,
    target_id VARCHAR(36) NOT NULL,
    action VARCHAR(80) NOT NULL,
    occurred_at VARCHAR(40) NOT NULL
);

CREATE TABLE IF NOT EXISTS invoices (
 id VARCHAR(36) PRIMARY KEY, invoice_number BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
 customer VARCHAR(255) NOT NULL, discount DECIMAL(19,2) NOT NULL, subtotal DECIMAL(19,2) NOT NULL,
 total DECIMAL(19,2) NOT NULL CHECK(total>=0), actor_id VARCHAR(36) NOT NULL, created_at VARCHAR(40) NOT NULL,
 request_data VARCHAR(12000) NOT NULL
);
CREATE TABLE IF NOT EXISTS invoice_lines (
 invoice_id VARCHAR(36) REFERENCES invoices(id), line_no INTEGER NOT NULL, sku VARCHAR(40) REFERENCES products(sku),
 product_name VARCHAR(255) NOT NULL, quantity INTEGER NOT NULL CHECK(quantity>0), unit_price DECIMAL(19,2) NOT NULL,
 returned INTEGER DEFAULT 0 NOT NULL CHECK(returned>=0 AND returned<=quantity), PRIMARY KEY(invoice_id,line_no)
);
CREATE TABLE IF NOT EXISTS sales_returns (
 id VARCHAR(36) PRIMARY KEY, invoice_id VARCHAR(36) NOT NULL, line_no INTEGER NOT NULL,
 quantity INTEGER NOT NULL CHECK(quantity>0), disposition VARCHAR(20) NOT NULL CHECK(disposition IN ('RESELLABLE','DAMAGED')),
 reason VARCHAR(2000) NOT NULL, actor_id VARCHAR(36) NOT NULL, created_at VARCHAR(40) NOT NULL,
 FOREIGN KEY(invoice_id,line_no) REFERENCES invoice_lines(invoice_id,line_no)
);
CREATE TABLE IF NOT EXISTS stock_counts (
 id VARCHAR(36) PRIMARY KEY, sku VARCHAR(40) REFERENCES products(sku), expected INTEGER NOT NULL, counted INTEGER NOT NULL CHECK(counted>=0),
 reason VARCHAR(2000) NOT NULL, created_by VARCHAR(36) NOT NULL, reviewed_by VARCHAR(36), status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
 created_at VARCHAR(40) NOT NULL
);
CREATE TABLE IF NOT EXISTS product_identifiers (
 barcode VARCHAR(80) PRIMARY KEY, sku VARCHAR(40) NOT NULL REFERENCES products(sku)
);
CREATE TABLE IF NOT EXISTS serial_units (
 serial VARCHAR(100) PRIMARY KEY, sku VARCHAR(40) NOT NULL REFERENCES products(sku), warranty_months INTEGER NOT NULL CHECK(warranty_months BETWEEN 0 AND 120),
 status VARCHAR(20) NOT NULL CHECK(status IN ('AVAILABLE','SOLD','DAMAGED')), invoice_id VARCHAR(36) REFERENCES invoices(id), sold_on DATE
);
CREATE TABLE IF NOT EXISTS supplier_contacts (
 name VARCHAR(255) PRIMARY KEY, contact VARCHAR(255) NOT NULL, email VARCHAR(255) NOT NULL, phone VARCHAR(60) NOT NULL, address VARCHAR(1000) NOT NULL
);
INSERT INTO schema_migrations(version,description) SELECT 5,'Billing, returns, counts, serials and supplier contacts' WHERE NOT EXISTS(SELECT 1 FROM schema_migrations WHERE version=5);

ALTER TABLE purchase_orders ADD COLUMN IF NOT EXISTS group_id VARCHAR(36);

CREATE TABLE IF NOT EXISTS invoice_serials (
 invoice_id VARCHAR(36) REFERENCES invoices(id), serial VARCHAR(100) REFERENCES serial_units(serial),
 PRIMARY KEY(invoice_id,serial)
);
ALTER TABLE sales_returns ADD COLUMN IF NOT EXISTS serial_data VARCHAR(12000) NOT NULL DEFAULT '';
