# Smart Inventory and Procurement System

A Java desktop application for managing inventory, sales, purchasing, suppliers, and stock accountability. The working foundation explains replenishment suggestions, enforces independent purchase approvals, supports partial deliveries, and preserves the history behind stock changes. Batch and expiry tracking remain planned.

**Business context:** VoltEdge Electronics, a fictional retailer of phone and computer accessories. VoltEdge is the company using this application; the project and repository names remain unchanged. See the [electronics domain and class design](docs/electronics-domain.md) for the agreed categories and Java OOP design.

> **Project status:** Java foundation with JDBC persistence and a working localhost demo implemented (0.1). Product creation/editing/deactivation, service-level permissions, stock receipts and sales, low-stock detection, immutable movement history, and request retry protection work with both in-memory and H2 repositories. Six electronics detail classes implement `ProductDetails` with category-specific validation and JDBC persistence. The localhost workspace also supports search, category/status filters, category-specific forms, legacy-record views, CSV export, date-filtered analytics, supplier quotes, purchase orders, independent approvals, partial receipts, and purchase event history. The remaining sections describe the full roadmap unless explicitly marked implemented. See [foundation details](docs/foundation.md).

## Run the current foundation

Prerequisites: JDK 21 and Maven 3.9 or later. H2 is bundled into the executable JAR; no database server is needed for this version.

```sh
mvn clean verify
java -jar target/inventory-management-system-0.1.0-SNAPSHOT.jar
```

The scripted console demo registers an electronics charger, receives 10 units, sells 6, retries the same sale safely, reports 4 units remaining, and demonstrates permission and overselling checks. It does not provide an interactive menu or login. Data resets when the process exits. The frontend will be built after the core workflows.

### Open the persistent localhost workspace

```sh
java -jar target/inventory-management-system-0.1.0-SNAPSHOT.jar --serve --seed-demo --demo-personas --port=8081
```

Open [http://localhost:8081](http://localhost:8081). The intro plays once, holds its final truck frame and reveals **Login** and **How it works**. Login replaces that welcome area with the account form. For the current local demo, choose Admin, Manager or Staff, click one of the two persona cards, then click **Login**. The card fills the username and password without submitting the form. **People & access** remains available to Admins for individual account administration.

After login, the sidebar switches between the views permitted for that role. **Logout** is at the bottom left. Java services enforce permissions independently of the interface. See [authentication and account setup](docs/authentication.md) for role permissions, session behavior and implementation details.

The workspace supports product management, stock receipts/sales, movement history, filtering, exports, analytics, replenishment and independently reviewed purchase orders. All workspace/API/export access now requires a valid session.

- Data is saved in `data/inventory.mv.db`, relative to the directory where the command is run.
- `--demo-personas` provisions two fictional login accounts per role and enables credential autofill cards. Existing accounts/passwords are preserved. Omit it to use individual accounts and first-Admin setup instead.
- `--seed-demo` loads six VoltEdge electronics products and their receipts/sales (one per category). Stable request IDs make repeated seeding safe.
- Omit `--seed-demo` to leave existing data unchanged or start with an empty database.
- `--serve` defaults to port 8080; use `--port=8081` if 8080 is occupied.
- Stop the server with Ctrl+C. Restarting preserves products, history, and retry protection.
- The server listens only on `127.0.0.1`. Only one application process should open this H2 file at a time.
- Read endpoints: `/` (landing), `/app` (workspace), `/app?sku=VE-CHG-001`, `/api/inventory`, `/export/inventory.csv`, and `/health`. Writes use validated POST forms with a form token and request IDs for stock changes.

Electronics appear by default. Use **Catalogue view → Legacy records** to inspect older products. The additive V002 migration preserves their original data. Choose a product category and click **Show category fields** before entering a new product; the form then shows that category’s specifications.

### Analytics and low-stock ordering

Use **Analytics** to select an inclusive date range (up to 366 days, Asia/Kolkata). It shows recorded sales/receipt units, daily trends, best sellers, coverage estimates when enough history exists, and current retail stock value. Retail stock value is not revenue, profit, or cost valuation.

Use **Replenishment** to review a low-stock suggestion and its calculation. Add a supplier quote if none exists, then choose quantity and create a purchase draft. An open order prevents a second active order for that SKU. Fewer than seven observed days use a threshold-based fallback rather than a forecast.

1. Create a purchase order from a replenishment card.
2. Submit it with a reason.
3. Log out and log in as another Manager or Admin to review it. Self-approval is blocked.
4. Approve or reject with a reason.
5. Receive an approved delivery, partially or fully. Stock, order quantities, receipt links, and event history commit together.
6. Inspect the order history. Cancellation is allowed only before any receipt.

Password login replaces demo identity switching in the runnable server. Orders and supplier quotes are local records; no supplier messages or payments occur. See the [implementation and verification plan](docs/analytics-procurement-plan.md) for calculations and limits.

The original console demo remains in-memory; only `--serve` uses persistent storage. MySQL integration is planned and has not been tested or implemented.

The local dashboard now follows the supplied conveyor-and-charcoal visual reference. See [design reference and generated asset prompt](docs/design-reference.md).

See [reference systems and workflow mapping](docs/references.md) for how ERPNext and InvenTree informed the current implementation.

## 1. Project objectives

- Build a readable, well-structured Java application that demonstrates core Java and object-oriented programming.
- Support a complete business workflow from selling stock to recommending, approving, and receiving a replenishment order.
- Prevent unauthorized actions, overselling, invalid workflow transitions, and unexplained stock changes.
- Keep business rules independent of the desktop interface and database implementation.
- Deliver an understandable college project with meaningful demonstrations, documentation, and tests.

## 2. Technology plan

| Area | Planned choice | Purpose |
| --- | --- | --- |
| Language | Java 21 | Application code and business logic |
| Desktop interface | Java Swing | Forms, tables, dashboards, and dialogs |
| Build tool | Maven | Dependencies, compilation, and tests |
| Database | H2 implemented locally; MySQL planned | Persistent, relational business data |
| Database access | JDBC with prepared statements | Explicit SQL and transaction handling |
| Testing | JUnit 5 | Business-rule and integration verification |
| Logging | Java logging API | Application diagnostics |
| Money and dates | `BigDecimal`, `java.time` | Accurate monetary calculations and date handling |

Java is the primary implementation language. SQL will support persistence; this is not a web project. Spring Boot, a JavaScript frontend, and an ORM are outside the initial scope so that the Java logic and JDBC remain visible and understandable. Current dependency and main build-plugin versions are pinned in `pom.xml`.

## 3. Users and access management

Java access modifiers and application permissions solve separate problems. Both will be demonstrated.

### Application roles

| Action | Admin | Manager | Staff |
| --- | --- | --- | --- |
| View active products and available stock | Yes | Yes | Yes |
| Manage products, categories, and suppliers | Yes | Yes | No |
| Create sales and reserve stock | Yes | Yes | Yes |
| Create and submit purchase orders | Yes | Yes | No |
| Approve or reject purchases | Yes | Yes* | No |
| Receive approved purchases | Yes | Yes | No |
| Request stock adjustments | Yes | Yes | No |
| Approve stock adjustments and sales reversals | Yes | Yes* | No |
| View operational reports and audit history | Yes | Yes | No |
| Manage users, roles, and system settings | Yes | No | No |

*A user cannot approve their own purchase or adjustment request. Another authorized user must approve it. Admin access does not silently bypass this rule; sample data will include two approvers for demonstration.

- Represent roles and permissions with enums and explicit permission mappings.
- Check permissions in services, even when the UI hides a restricted action.
- Keep the authenticated user in a session context and clear it at logout.
- Store salted password hashes using an established password-hashing implementation; never store plaintext passwords or log credentials.
- Prevent deactivation of the last active admin account.

### Java access control

- Keep object fields `private` and protect invariants through methods.
- Expose only the necessary service and repository contracts as `public`.
- Use package-private helpers for implementation details.
- Use `protected` only where a deliberate inheritance design needs it.
- Do not add public setters that allow callers to bypass stock or approval rules.

## 4. Functional scope

### 4.1 Products, categories, and suppliers

- Create, edit, search, filter, and deactivate products.
- Maintain a unique SKU, name, category, unit, selling price, and reorder settings.
- Use whole-number quantities in the first version; fractional units are a future extension.
- Maintain supplier contact details and active status.
- Associate multiple suppliers with a product, including quoted price and expected delivery days.
- Deactivate records referenced by transactions instead of deleting their history.

### 4.2 Inventory, batches, and expiry

- Track stock by product and batch, with optional expiry dates.
- Record receipts, sales, returns, damage, adjustments, and reversals as stock movements.
- Calculate available quantity as on-hand quantity minus active reservations.
- Suggest first-expiry-first-out allocation for expiring products; use receipt order for non-expiring stock.
- Exclude expired or blocked batches from saleable stock.
- Show low stock, out-of-stock products, and batches approaching expiry.
- Require a reason for damage, adjustments, blocking, and reversals.

### 4.3 Sales, billing, reservations, and returns

- Create a sale with multiple products and a unique bill number.
- Calculate line totals, discounts, and the final amount with `BigDecimal`.
- Start with percentage discounts and no tax calculation; tax rules require a separately defined scope.
- Validate quantities and prices before committing a sale.
- Reserve stock for a pending order and release reservations on cancellation or timeout.
- Commit the sale, stock allocation, and stock movements in one database transaction.
- Record returns against the original sale and prevent returns exceeding the quantity sold.
- Route returned units to saleable or damaged stock after inspection.
- Display a printable bill; PDF export is an optional enhancement.

### 4.4 Purchase orders and approvals

- Create draft orders containing a supplier and product lines.
- Submit orders for independent approval and record approval or rejection reasons.
- Allow partial receipts and track outstanding quantities.
- Prevent receipt of more than the approved quantity.
- Add received units to batches and record stock movements atomically.
- Record promised and actual delivery dates for supplier performance reporting.

Planned purchase states:

```text
DRAFT -> PENDING_APPROVAL -> APPROVED -> PARTIALLY_RECEIVED -> RECEIVED
                         -> REJECTED
DRAFT / PENDING_APPROVAL / APPROVED -> CANCELLED (only before any receipt)
```

An approved order can also move directly to `RECEIVED` when delivered in full. Rejected orders remain historical records; a corrected order starts as a new draft. Orders with any receipt cannot be cancelled in the initial version.

### 4.5 Explainable reorder recommendations

- Calculate average daily demand from completed sales over a configurable window, initially 30 days.
- Consider supplier lead time, safety stock, available inventory, and approved inbound purchases.
- Explain the inputs and calculation for every recommendation.
- Let a manager review a recommendation before creating a draft purchase order.
- Use a configured minimum level when insufficient sales history exists.

Initial calculation:

```text
reorder point = ceil(average daily demand × lead time days) + safety stock
inventory position = available stock + outstanding approved inbound quantity
target stock = ceil(average daily demand × (lead time days + review period days))
               + safety stock
suggested quantity = max(0, target stock - inventory position)
```

Recommend replenishment when inventory position is at or below the reorder point. Approved inbound quantities include only unreceived quantities from approved or partially received orders. Demand initially uses gross completed sales; returns are reported separately. Recommendations are rule-based, not machine learning.

### 4.6 Supplier comparison

- Compare active suppliers using unit price, lead time, and on-time delivery percentage.
- Provide simple selectable policies: lowest price, shortest lead time, or best delivery reliability.
- Show the ranking inputs and mark suppliers with no delivery history as unrated.
- Allow a manager to override the recommendation with a recorded reason.

### 4.7 Reconciliation, audit history, and reversal

- Capture a physical stock count and compare it with recorded stock.
- Submit discrepancies for independent approval before changing inventory.
- Preserve an append-only application history of stock movements and important user actions.
- Link entries to the user, timestamp, reason, and source transaction.
- Reverse eligible mistakes through compensating transactions; preserve the original record.
- Prevent duplicate reversals and validate that reversing a receipt will not create negative stock.

The audit log is application-controlled history, not a claim of tamper-proof storage against database administrators.

### 4.8 Dashboard, reports, and import/export

- Dashboard: active products, saleable units, low-stock products, expiring batches, and recent sales.
- Reports: sales, purchases, stock movements, stock valuation, supplier performance, and adjustments.
- Calculate inventory value from remaining batch quantities and their purchase unit costs.
- Filter reports by date range, product, category, or supplier where relevant.
- Export reports to CSV.
- Preview product CSV imports, flag invalid rows and duplicate SKUs, and require an explicit import action.
- Import validated rows in a single transaction and present rejected rows separately.
- Perform expensive report and import work in the background without freezing Swing.

## 5. Core Java learning map

| Concept | Planned application |
| --- | --- |
| Classes and objects | Products, batches, users, sales, and orders |
| Constructors and validation | Create valid domain objects and inject dependencies |
| Encapsulation and access modifiers | Private state and controlled operations |
| Abstraction and interfaces | Repository, report export, and reorder policy contracts |
| Inheritance and polymorphism | Small stock-operation hierarchy with specialized validation |
| Composition | Orders contain lines; services depend on repository interfaces |
| Method overriding | Policy and repository implementations |
| Method overloading | Small, useful report/filter convenience methods |
| Enums and switch expressions | Permissions, order states, and movement types |
| Collections | Lists of order lines, SKU maps, and permission sets |
| Generics | Typed collections and a reusable `ValidationResult<T>` |
| Exceptions | Insufficient stock, invalid transitions, and access denial |
| Streams, lambdas, comparators | Summaries, filtering, and supplier ranking |
| File I/O and try-with-resources | CSV import/export and resource cleanup |
| JDBC and transactions | Persistent data and atomic stock changes |
| Concurrency and executors | Scheduled alerts and background calculations |
| Swing event handling | Forms, dialogs, tables, and user actions |
| `equals` and `hashCode` | Stable value objects such as SKU and permission keys |
| `BigDecimal` and `java.time` | Money, expiry dates, timestamps, and report periods |
| Immutability | Recommendation results and audit-event data |

Concepts will be used where they improve the design. Unrelated features such as reflection, custom class loaders, or Java object serialization will not be added just to claim coverage. Database constraints and transactions handle cross-session stock correctness; Java synchronization alone is insufficient.

## 6. Architecture and directory plan

Use a layered architecture with constructor injection:

```text
Swing UI -> Application services -> Domain models and policies
                       |
                 Repository interfaces
                       |
                JDBC implementations -> H2 now / MySQL planned
```

```text
inventory-management-system/
├── README.md
├── pom.xml
├── docs/
│   ├── requirements.md
│   ├── database-design.md
│   └── demo-guide.md
├── database/
│   ├── schema.sql
│   └── sample-data.sql
├── src/main/java/com/inventory/
│   ├── App.java
│   ├── config/           # Configuration and dependency construction
│   ├── model/            # Domain entities and value objects
│   ├── security/         # Authentication, roles, and permissions
│   ├── service/          # Use cases and transaction boundaries
│   ├── repository/       # Persistence interfaces
│   │   └── jdbc/         # SQL implementations
│   ├── strategy/         # Reordering and supplier-selection policies
│   ├── exception/        # Meaningful application exceptions
│   ├── validation/       # Input validation and import results
│   ├── reporting/        # Report data and CSV export
│   └── ui/               # Swing screens and controllers
├── src/main/resources/
│   └── application.example.properties
└── src/test/java/com/inventory/
    ├── service/
    ├── strategy/
    ├── security/
    └── repository/
```

This is the target structure. The current implementation contains `App`, models, security, services, exceptions, in-memory and JDBC repositories, a local browser adapter in the `preview` package, and unit/integration tests. The implemented H2 schema is at `src/main/resources/database/schema-h2.sql`. The full MySQL schema and UI packages will be added later. Create packages as they become necessary rather than filling them with empty classes.

### Responsibility rules

- UI classes collect input and present results; they do not execute SQL or calculate stock rules.
- Services authorize actions, validate business rules, and coordinate transactions.
- Repositories load and save data without owning UI or permission logic.
- Domain objects protect their own invariants.
- Policies implement interchangeable business decisions without long conditional chains.
- JDBC operations participating in one use case share the same connection and transaction.
- Construct dependencies in one application entry point; avoid global mutable state.

## 7. Database plan

The table below is the target design. Current V003 purchasing uses one SKU per purchase order, supplier quotes, receipt-to-movement links, and purchase events. Multi-line orders and a separate supplier/contact master remain later extensions. V002 and V003 preserve existing data.


| Table | Main responsibility |
| --- | --- |
| `users` | Login identity, password hash, role, and active status |
| `categories` | Product classifications |
| `products` | SKU, name, unit, price, and replenishment settings |
| `suppliers` | Supplier identity and contact details |
| `product_suppliers` | Product-specific supplier prices and lead times |
| `stock_batches` | Receipt source, expiry, unit cost, and current quantities |
| `purchase_orders` | Supplier, approval state, creator, and approver |
| `purchase_order_items` | Requested quantities and agreed prices |
| `goods_receipts` / `goods_receipt_items` | Partial deliveries and received batches |
| `sales` / `sale_items` | Bills, quantities, discounts, and price snapshots |
| `sale_batch_allocations` | Which batches supplied each sale line |
| `reservations` / `reservation_items` | Pending allocations and expiry time |
| `sales_returns` / `sales_return_items` | Returned quantities, condition, and original sale links |
| `stock_adjustments` / `stock_adjustment_items` | Count discrepancies and approval decisions |
| `stock_movements` | Quantity deltas, source links, and reversal links |
| `audit_events` | User actions, reasons, and timestamps |

Use primary keys, foreign keys, unique SKU and username constraints, appropriate indexes, and `DECIMAL` columns for money. Preserve transaction price snapshots when catalogue prices change. Stock movements are the history; batch balances are updated in the same transaction and must reconcile with that history. Final columns and constraints will be documented before schema implementation.

## 8. Business rules and reliability

- Quantities must be positive for transaction lines; stock cannot become negative.
- Selling price and purchase cost cannot be negative; discounts must stay within permitted limits.
- Lock relevant batch rows and recheck availability during stock-changing transactions.
- Commit all related writes together or roll them all back on failure.
- Prevent repeated submission from creating duplicate sales, receipts, or reversals.
- Reject invalid order transitions in services, even if invoked outside the UI.
- Persist reservation expiry and check it during allocation; use a scheduled task for cleanup.
- Update Swing components on the Event Dispatch Thread; run blocking work off that thread.
- Use prepared statements and close connections, statements, and result sets reliably.
- Load database credentials from environment variables or an ignored local configuration file.
- Log diagnostic details without passwords; show useful, nontechnical errors to users.
- Use a consistent timestamp convention and a configured business timezone for display and reporting.

## 9. Coding standards

- Use meaningful names such as `approvePurchaseOrder` and `availableQuantity`.
- Keep classes focused and methods short enough to understand without excessive comments.
- Follow Java naming conventions: `PascalCase` classes, `camelCase` methods, `UPPER_SNAKE_CASE` constants.
- Explain non-obvious decisions with comments; avoid restating the code.
- Document public contracts and important business rules with Javadoc.
- Avoid magic numbers, duplicated rules, empty exception handlers, and catch-all error suppression.
- Prefer composition; keep inheritance small and justified.
- Use specific types and exceptions instead of loosely typed maps or generic `Exception` contracts.
- Keep SQL in JDBC repositories and reuse connection/transaction infrastructure.
- Add abstractions only when they clarify responsibilities or support a real variation.

## 10. Development roadmap

| Phase | Deliverable | Completion criteria |
| --- | --- | --- |
| 1. Foundation | Maven setup, domain rules, database design | Project compiles; schema and package responsibilities are reviewed |
| 2. Access and catalogue | Login, permissions, products, categories, suppliers | Restricted actions fail at service level; catalogue persists |
| 3. Inventory | Receipts, batches, movements, expiry | Balances reconcile; expired stock is excluded from sale |
| 4. Sales | Billing, allocation, reservations, returns | Sales update stock atomically; overselling is prevented |
| 5. Procurement | Orders, approvals, partial receipts | Own approvals and over-receipts are rejected |
| 6. Differentiating features | Reorder explanations, supplier ranking, reconciliation, reversals | Recommendations are reproducible and changes remain traceable |
| 7. Reporting and polish | Dashboard, reports, CSV preview/export, background tasks | Reports agree with transactions; UI stays responsive |
| 8. Delivery | Sample data, verification, setup instructions, demo guide | A new local installation can reproduce the demonstration |

Complete one working vertical flow before expanding screens. Build a small interface around tested services instead of designing every screen first.

## 11. Verification plan

- Unit tests for reorder calculations, discount boundaries, permission checks, expiry allocation, and workflow transitions.
- Integration tests against an isolated MySQL test database for rollback, constraints, receipt limits, and concurrent stock changes.
- Verify that two simultaneous sales cannot consume the same final unit.
- Verify rejection of self-approval, duplicate reversal, excessive returns, and invalid CSV rows.
- Verify stock balances and report totals against a small, hand-calculated dataset.
- Manually check Swing navigation, validation messages, logout, table filtering, and background-task responsiveness.

Test business outcomes and failure paths rather than getters, setters, or code that simply repeats an implementation.

## 12. Planned demonstration

1. Sign in as a manager and inspect products, supplier offers, and batches.
2. Sign in as a cashier and sell stock; show the batch allocation and generated bill.
3. Show the resulting low-stock alert and explain the reorder calculation.
4. Compare suppliers and create a draft purchase order.
5. Submit it, then sign in as another authorized user to approve it.
6. Receive part of the order and show updated stock and outstanding quantities.
7. Demonstrate a rejected unauthorized action and an invalid receipt quantity.
8. Show expiry warnings, a reconciliation request, and the stock history.
9. Export a report and trace a transaction reversal without deleting its original record.

## 13. Local setup status

The current runnable foundation requires JDK 21 and Maven 3.9 or later; use the commands near the top of this README. H2 provides embedded storage and JUnit provides tests. The executable JAR bundles H2. Local preview storage uses H2's local `sa` account with no password and is never exposed as a database server. MySQL setup and credential configuration will be added with that integration. Password authentication, role-based sessions and account administration are implemented. Complete invoices and batches remain planned. Purchase approvals use the logged-in account.

## 14. Future extensions

- Barcode scanner input and label generation.
- Multiple stores or warehouses with transfer approval.
- Fractional quantities and unit conversion.
- PDF invoices and richer printable reports.
- Supplier purchase returns and purchase amendments after partial receipt.
- Configurable tax rules, backup/restore tooling, and demand seasonality.

These are optional extensions, not requirements for the first complete version.

## 15. Local development policy

Keep source code, documentation, sample data, and build artifacts local unless the project owner explicitly requests otherwise. Do not create a remote repository, publish the project, or push changes without that instruction. Never place real passwords or sensitive business data in shared files.
