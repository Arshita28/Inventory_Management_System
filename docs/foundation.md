> Current update: the runtime now requires password login and has persistent Admin/Manager/Staff accounts, session-bound CSRF, session expiry and bottom-left logout. See [authentication](authentication.md). Trusted demo actors described below remain test fixtures or historical foundation notes.

# Foundation: version 0.1

See [VoltEdge electronics domain](electronics-domain.md) for the implemented category classes and legacy migration.

## Implemented flow

An authorized manager registers a product and receives stock. A cashier sells a quantity. Each successful change stores a signed movement, actor ID, reason, timestamp, and resulting balance. The service reports low stock and returns immutable history snapshots.

The executable supports two modes:

- No arguments: a scripted in-memory console demo. Data resets on exit.
- `--serve`: a working localhost demo backed by a file-based H2 database through JDBC. Data persists across restarts. `--seed-demo` optionally loads repeatable fixtures.

Demo users are trusted fixtures. Service permission checks exist, but password authentication and user administration do not yet exist. The browser adapter supports real operations, while the final frontend remains a later phase.

## Responsibilities

| Package | Responsibility |
| --- | --- |
| `model` | Immutable products, summaries, stock movements, and six category-specific detail classes |
| `security` | Role-to-permission mapping and service-level access checks |
| `service` | Application operations, input rules, and movement creation |
| `repository` | Persistence contract, including atomic updates and retries |
| `repository.memory` | Single-process maps guarded by a shared Java lock |
| `repository.jdbc` | Prepared statements, schema initialization, database locking, commit/rollback |
| `preview` | Java HTTP server, validated forms, HTML/JSON rendering, and optional sample data |
| `reporting` | CSV inventory export with quoting and spreadsheet formula protection |
| `exception` | Expected business conflicts, access denial, and storage failures |

`InventoryService` receives its repository, authorization service, and clock through its constructor. Tests supply fixed clocks where needed. Business services do not depend on HTTP or H2 classes.

## Current rules

- SKUs are normalized to uppercase; duplicates are rejected.
- Prices are nonnegative with at most two meaningful decimal places. Excess precision is rejected rather than silently rounded.
- Products start at zero stock; opening quantities require a receipt.
- Transaction quantities must be positive whole numbers. Stock cannot become negative or exceed the supported integer range.
- Products can be deactivated only at zero stock; their history remains available.
- Active products at or below their reorder level appear in the low-stock list.
- Cashiers can view stock and sell but cannot manage products, receive stock, or view history.
- Missing or inactive actors are denied. Every public service operation authorizes its caller.
- Callers provide a UUID per intended stock change and reuse it for a retry. Identical retries return the original result; conflicting reuse fails.
- Failed stock changes leave neither a balance change nor a movement behind.

## JDBC persistence

The H2 schema is bundled at `src/main/resources/database/schema-h2.sql`. It contains products, stock movements, and a single database mutex row. Balances and movements are written in one transaction. Prepared statements bind all dynamic values.

Before a write, the repository locks the mutex row with `SELECT ... FOR UPDATE`. This intentionally serializes writes, including request-ID lookup, across repository instances sharing the database. It favors a clear correctness model over write throughput. A future design can replace it with finer-grained locks while retaining the contract tests.

The database enforces unique SKUs/request IDs, movement-to-product references, nonnegative balances, and movement direction constraints. SQL errors roll back all related writes and are wrapped as `PersistenceException`. Application code can distinguish storage failures from expected inventory conflicts.

The H2 file closes when its last connection closes. Request IDs and history remain on disk. The local application uses one process and opens connections per operation. H2 local-file storage is implemented; MySQL compatibility is not claimed. The baseline is followed by the idempotent V002 electronics-details and V003 procurement migrations. A schema-migrations table records versions 2 and 3; this remains a small project-specific migration setup.

Storage limits are enforced before persistence: 255 characters for names/categories, 2,000 characters for movement reasons, and `DECIMAL(19,2)` for prices. Product edits preserve stock balances, active state, SKU, and movement history.

## Local preview

Run the packaged JAR with `--serve --seed-demo --port=8081`, then open `http://localhost:8081`. It binds to `127.0.0.1`. Product create/edit/deactivate and stock receipt/sale actions use POST forms. The handler validates a process-local form token, local host/origin, content type, form size, and parameters; services still enforce business permissions. Successful POSTs redirect to GET to prevent refresh resubmission. Stock forms retain a request ID for safe retries. This is a local trusted-account demo, not authentication.

The page displays current products, quantities, reorder levels, low-stock states, and selected-product history, including actor IDs and UTC timestamps. It uses Java-generated HTML and CSS with no JavaScript frontend framework. Search supports SKU/name, category, and stock-status filters. Inventory CSV export is available at `/export/inventory.csv`; JSON is available at `/api/inventory`. `/health` checks the running HTTP process, not database health. Domain conflicts show inline messages and preserve entered form fields.

Initial VoltEdge sample data contains six electronics products, 60 total units remaining, and two low-stock products. Older generic records, including earlier browser-test products, remain preserved in the Legacy records view. The JSON/CSV exports include all records with explicit category codes. Stable sample request IDs avoid duplicate movements on restart. Use the sample-data option only with demonstration inventory.

## Deliberate limitations and next step

There is no login, user administration, complete billing, batch allocation, reservation, return, or full frontend yet. Independent purchase approval, supplier quotes, partial receiving, analytics, and order event history are implemented. Stock sales currently record quantities rather than complete invoices. Direct receipts will later be governed by procurement rules.

Next: persist users, add password authentication and session handling, and replace trusted demo actors with authenticated callers. The purchase workflow now works with explicit demo actors; future supplier management can add contacts and quote editing. MySQL integration requires its own schema/configuration and integration checks. Keep the full frontend after the core workflows.

## Verification

Verified with Temurin JDK 21 and Maven 3.9.9: `mvn verify` builds the executable JAR and passes 72 tests:

- 14 procurement tests across memory/JDBC for lifecycle rules, independent approval, receipt retries, duplicate orders, deactivation, rollback, restart persistence, and concurrency.
- 6 analytics tests for business-date boundaries, honest valuation, coverage, supplier minimums, insufficient history, and open-order commitments.
- 6 category-detail tests for polymorphic summaries, immutable specifications, constructor rules, and factory rejection.
- 16 service tests for business rules, permissions, retries, immutable history, and competing in-memory sales.
- 11 file-based JDBC tests for reopen persistence, durable retry protection, rollback after a failed movement insert, competing repository instances, concurrent retries, database-backed stock rules, six-category round trips, and legacy-schema migration.
- 19 HTTP/sample-data tests for HTML/JSON, history selection, escaping, form workflows, permissions, request retries, input preservation, search/filtering, CSV export, rejected writes, repeatable seeding, category-specific forms, and legacy reclassification.

Earlier browser verification created a product, received 10 units, and sold 8, confirming the resulting ledger. The electronics revision was checked in the browser: six categories and 60 sample units appeared, the Storage create form showed only storage fields, all four earlier records remained in the legacy view, and an SSD specification edit persisted after reload while its 8-unit balance and two stock movements stayed unchanged. A pre-migration database backup was retained locally under the ignored data/backups directory. The verification toolchain lives in a temporary directory and was not installed system-wide. A normal development setup still needs JDK 21 and Maven on its path.

The analytics/procurement revision was also verified through the complete browser order lifecycle. See [delivery evidence](analytics-procurement-plan.md#completion-evidence). The completed demo delivery increases the initial electronics seed from 60 to 66 remaining units; seeding again does not reset those transactions.

The dashboard visual revision uses a locally bundled generated conveyor background, translucent hero cards, charcoal reports, and lime accents. Static assets are served from an explicit allowlist; no arbitrary filesystem paths are exposed. See [design notes and asset prompt](design-reference.md).
