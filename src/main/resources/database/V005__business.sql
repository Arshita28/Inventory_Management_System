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
