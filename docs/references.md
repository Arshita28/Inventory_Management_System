# Reference systems and workflow mapping

Reviewed official documentation on 9 November 2026. These systems inform behavior and terminology; the project uses its own Java implementation and page layout. No source code, branding, or assets were imported.

## ERPNext: item setup

Source: [ERPNext Item documentation](https://docs.frappe.io/erpnext/item).

ERPNext separates item setup from stock transactions and supports item-level reorder configuration. Our equivalent is a product catalogue with a unique SKU, name, category, unit price, active state, and reorder threshold. Creation starts at zero stock. Editing metadata does not rewrite existing stock movements.

Implemented: create/edit catalogue entries, search by name/SKU, filter by category or stock status, flag low stock, and deactivate zero-stock products without deleting history.

Still planned: richer units, variants, batch/serial tracking, supplier relationships, and automatic purchase requests. Our low-stock flag is not ERPNext's complete automatic reorder workflow.

## ERPNext: stock entry purposes

Source: [ERPNext Stock Entry Purpose documentation](https://docs.frappe.io/erpnext/stock-entry-purpose).

ERPNext distinguishes inward and outward stock movements. Our smaller version exposes Receive stock and Record sale forms. A submitted operation validates quantity and reason, checks the actor's permission, and atomically updates both balance and movement history.

Implemented: positive whole-unit input, stock-in/stock-out directions, mandatory reason, overselling prevention, request retry protection, and immediate ledger updates after submission.

Still planned: warehouses, transfers, approval states, purchase receipts tied to orders, complete invoices, and transaction reversal. Our Record sale operation records quantity only; it does not reproduce ERPNext accounting or invoicing.

## InvenTree: traceable history

Source: [InvenTree Stock Tracking documentation](https://docs.inventree.org/en/stable/stock/tracking/).

InvenTree exposes per-item stock history. Our selected-product panel shows a chronological record of signed quantity changes, resulting balance, actor ID, timestamp, and reason, with the newest movement first.

Implemented: persisted stock movement records, immutable snapshots, and inspection of history alongside current inventory.

Still planned: batch/serial traceability, metadata edit auditing, richer event types, and reversal links. Catalogue edits currently preserve stock history but do not create metadata audit events.

## Additional project behavior

CSV export, inline validation, POST/redirect/GET, form-size limits, HTML escaping, and loopback-only hosting support the local demonstration. They are our implementation choices rather than claims of exact parity with either reference system.

Core business logic remains Java. The small server-rendered HTML adapter makes the workflows testable in a browser; it does not replace the planned Java desktop frontend decision.

## Manual walkthrough

1. Open `http://localhost:8081` and create a product with a unique SKU.
2. Select the product, open Receive stock, and enter a quantity and delivery reason.
3. Open Record sale and sell some of that quantity with a sale reference/reason.
4. Observe the updated balance and the two movement records.
5. Attempt a sale above the available quantity; the form retains its values and displays a conflict without changing stock.
6. Edit the product price or reorder threshold and verify that stock history remains unchanged.
7. Search/filter the catalogue or download the CSV report.
8. Sell the remaining units before deactivating a product; its history stays available.

The browser uses a trusted demo manager. Password login, independently authenticated roles, and procurement approvals are future work.

## Video reference applied in the application

The user supplied [Inventory Management in 11 minutes](https://www.youtube.com/watch?v=0NOER-Lle-0). Transcript export was unavailable, so implementation used the creator's [accompanying notes](https://thinkeduca.com/inventory-management/) as the primary reference. The added analytics, supplier quotes, explained replenishment, independently reviewed purchase orders, and atomic partial receipts make a connected application workflow. The source is inspiration for the business flow; all calculations, state transitions, storage, rendering, and tests are this project's Java implementation.

See [implementation scope and verified behavior](analytics-procurement-plan.md). The application does not claim cost-layer accounting, full invoicing, or every technique discussed by the creator is complete.
