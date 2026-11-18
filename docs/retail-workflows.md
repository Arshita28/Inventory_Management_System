# Retail workspace

The local Java application now includes customer invoices, returns, stock counts,
supplier contacts, quote editing, purchase documents,
and database backup/recovery.

## Try the application

Start the executable with:

```sh
java -jar target/inventory-management-system-0.1.0-SNAPSHOT.jar --serve --demo-personas --port=8081
```

Open http://localhost:8081 and use a persona card to sign in. Existing inventory
and accounts are preserved. Test transactions change the local database.

### Invoice and return

1. Open **Sales & returns**. Enter a customer name or counter-sale reference.
2. Choose products and quantities; use **Add line** for additional products.
3. Review the displayed subtotal, invoice discount, and total. Save the invoice.
4. The saved invoice has a sequential `INV-000001` reference. Product names and
   prices are snapshots, so future catalogue edits do not change issued invoices.
5. Use **Print invoice** to print or save a PDF through the browser print dialog.
6. As a Manager/Admin, select a saved invoice and record a return. Resellable units
   increase available stock. Damaged units appear in the return register and do
   not increase available stock. A line cannot be returned beyond its sold quantity.

Invoices record sales, not payments. Payment collection, refunds, tax calculations,
and credit notes are not implemented. The discount is an invoice-level amount.
Existing quick stock sales remain quantity-only and cannot be returned through
an invoice because they have no invoice link.

### Physical stock counts

1. A Manager/Admin opens **Stocktaking**, selects a product, and records its
   physically counted quantity and reason.
2. A different Manager/Admin approves or rejects the count.
3. Approval records the difference as an adjustment. The old balance is preserved
   in the count record. If stock changed after the count, approval is blocked;
   reject and recount instead.

### Supplier records and purchase documents

- **Supplier directory** stores contact person, email, phone and address. Reusing
  an exact supplier name updates that record. Existing quote names remain valid.
- Edit a quote's price, lead time and minimum quantity here. Existing orders keep
  their original agreed price.
- **Purchase documents** creates up to five product lines from one supplier.
  Creation and group submission/review are atomic. A second Manager/Admin must
  review the document. **Purchase lines** provides delivery and event history for
  each line, with partial receipts and outstanding quantities.
- Purchase documents are internal records. The app does not send supplier emails
  or make payments.

### Backup and recovery

Admins can download a full SQL backup in **Backup & recovery**. It includes
inventory, accounts and password hashes; store it privately. The backup represents
one consistent database snapshot.

Stop the application, then restore a trusted backup into a new database:

```sh
java -jar target/inventory-management-system-0.1.0-SNAPSHOT.jar --restore=/path/to/voltedge-backup.sql --database=data/restored
java -jar target/inventory-management-system-0.1.0-SNAPSHOT.jar --serve --database=data/restored --port=8081
```

The restore refuses an existing destination database. SQL backups execute SQL;
restore only backups from a trusted installation. Restore uses the accounts and
passwords contained in the backup. Sessions are not restored.

## Data and implementation

- V005 adds retail tables without deleting existing inventory or authentication
  data. Returns and stock adjustments use distinct movement types and do not
  inflate sales/receipt analytics.
- All document, balance and movement writes use the existing database
  write lock and one transaction. Failed invoice lines roll back the whole sale.
- Invoice, return, count and purchase-create request IDs prevent duplicate writes.
- Services enforce permissions independently of the rendered screens. CSRF and
  local-origin validation apply to every business form.
- The workspace retains its charcoal-and-lime theme with consistent forms, compact tables, plain labels,
  keyboard focus indicators, responsive layouts and a print-specific invoice view.

## Dashboard and navigation

The dashboard includes quick actions, eight live summary cards, a sales/receipts
unit chart, category distribution and current stock. Billed sales are invoice totals
before refunds; they are not payment receipts or profit. No comparative percentages
are shown without supporting data. Menus group Inventory, Sales, Purchasing,
Suppliers, Reports and Settings, and preserve role restrictions.

Numeric controls explain their input format. Java validates nonnegative integers
within the int range and decimal amounts with at most two places. The browser
blocks letters, signs, decimal points in integer fields and exponent notation.
Serial and warranty workflows have been removed; old stored records are preserved
for backup compatibility and no longer constrain stock operations.

Barcode lookup and assignment have been removed from the workspace. Existing identifier records remain in backups for compatibility. Sidebar selection follows the exact destination, including New sale and Add product.
