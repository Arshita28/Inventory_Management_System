# PostgreSQL and hosted deployment

The same Java services now support local H2 and managed PostgreSQL. This change
prepares the connection; it does not provision a cloud instance, change DNS, deploy
the app, or copy local data automatically. The provider and credentials still need
to be configured for the chosen account.

## Choose storage

With no `INVENTORY_JDBC_URL`, development continues to use `data/inventory.mv.db`.
Setting that variable selects PostgreSQL. It never falls back to H2 when a cloud
connection fails. Production requires PostgreSQL configuration explicitly.

Set the following using your hosting provider's secret/environment controls:

| Variable | Value |
| --- | --- |
| `INVENTORY_JDBC_URL` | `jdbc:postgresql://HOST:5432/DATABASE?sslmode=verify-full&sslrootcert=/path/to/provider-ca.pem` |
| `INVENTORY_DB_USER` | Database user from your provider |
| `INVENTORY_DB_PASSWORD` | Password stored as a hosting secret |
| `INVENTORY_DB_SCHEMA` | Dedicated private application schema, default `inventory` |
| `INVENTORY_DB_POOL_SIZE` | Optional, default `5`, permitted `1`–`20` |
| `INVENTORY_ENV` | `production` when hosted |
| `INVENTORY_PUBLIC_ORIGIN` | Exact HTTPS origin, e.g. `https://inventory.example.com`, without trailing slash or path |
| `INVENTORY_BIND_ADDRESS` | `0.0.0.0` inside the hosted container; local default `127.0.0.1` |
| `PORT` | Host-assigned internal HTTP port, default `8080` |

`.env.example` documents the variables but is not loaded automatically. Configure
variables in the host or explicitly export them in your shell. Do not paste
passwords into chat, put them in JDBC URLs, commit `.env`, or pass them as command
arguments. The executable includes the PostgreSQL JDBC driver.

Cloud connections require `sslmode=verify-full`: the driver checks both the
certificate chain and host name. Obtain the correct CA certificate and connection
hostname from the chosen provider. Do not use a nonvalidating SSL factory.
See [pgJDBC TLS documentation](https://jdbc.postgresql.org/documentation/ssl/).
Use a direct database endpoint for migrations, not a transaction-pooling proxy.

The runtime shares one HikariCP pool between inventory, invoices and account
repositories. Startup applies checksummed PostgreSQL migrations in a transaction
protected by an advisory lock. Runtime stock writes keep the existing row-lock
transaction boundary. Use a dedicated database/schema controlled by this app and
a database role permitted to create and alter its tables during startup.

## First Admin and demo accounts

Production rejects demo seeding/persona flags and refuses to start without an
existing Admin account. Before opening a new installation publicly:

1. Set PostgreSQL variables locally, leaving `INVENTORY_ENV`,
   `INVENTORY_PUBLIC_ORIGIN` and `INVENTORY_BIND_ADDRESS` unset.
2. Run `java -jar target/inventory-management-system-0.1.0-SNAPSHOT.jar --serve --port=8081`.
3. Open localhost and create your individual first Admin. This stores its salted
   password hash in PostgreSQL.
4. Stop the local process. Deploy with production variables and no demo flags.

Migrating the current demo database also migrates demo accounts. Before public
launch, create individual accounts, then disable all `demo.*` accounts through
Staff & access. Do not leave public demo credentials active on a hosted database.

## Import the existing H2 data

The importer preserves products, quantities, immutable movement IDs, supplier
quotes, purchase approvals and events, invoice numbers/lines, returns, counts,
accounts/password hashes, and access audit events. Retired serial/barcode tables
are retained only to avoid losing historical data; the UI features stay removed.

1. Stop the local application and make a private copy of `data/inventory.mv.db`.
2. Select a **new, empty PostgreSQL database** using the environment variables above.
3. Run:

```sh
java -jar target/inventory-management-system-0.1.0-SNAPSHOT.jar --migrate-h2=data/inventory
```

The source path omits `.mv.db`. The source opens read-only. Schema setup is
transactional; data copy runs in one PostgreSQL transaction. The importer refuses
any nonempty business table, checks copied row counts and stock/movement
reconciliation, preserves identity values, and advances identity sequences.
If import fails, copied rows roll back. The source H2 database remains unchanged.
Do not run the old app while importing. Read-only H2 access does not upgrade an
old schema: first run the current app against an older source database locally.

After import, test login, balances, history, invoices, pending orders and approval
separation before switching the real deployment connection. Never import over
an existing production database. Repeating import into a populated target fails.

## Host the Java application

A multi-stage `Dockerfile` builds and runs the Java 21 app as an unprivileged user.
Build with `docker build -t voltedge-inventory .`. Select a container-capable Java
host; this app cannot run on a static-only website host.

Terminate HTTPS at the host's reverse proxy and route it to the internal HTTP port.
The app validates the exact configured Host and Origin and sets Secure session
cookies for hosted mode. It does not trust arbitrary forwarded headers. Configure
the proxy to preserve the public Host. Use `/health` for process liveness and
`/ready` for database readiness. The database remains accessible only to Java,
never directly to browser clients.

Run **one app instance** initially. Sessions and login throttling are in memory,
so restarts require login; multiple replicas need shared session/rate-limit storage
before scaling horizontally. TLS certificate mounting, provider firewall rules,
backups, domain and reverse-proxy setup depend on the selected host.

## Backups

PostgreSQL mode shows provider-backup instructions instead of the H2 SQL download.
Use provider snapshots or `pg_dump`/`pg_restore` into a separate recovery database.
A local H2 `SCRIPT` backup cannot be restored directly into PostgreSQL. Test a
restore and keep retention according to the business's needs.

## Verification

`mvn verify` runs the normal H2, service, authentication and HTTP tests.
`mvn -Ppostgres-tests verify` additionally starts an isolated real PostgreSQL test
17 test server from Maven Central and tests migrations, accounts, supplier upsert,
invoicing, return limits, counts, concurrency, purchase approvals/partial receipts,
and H2 import with identity preservation. No cloud credentials are used by tests.

For manual development PostgreSQL, `compose.postgres.yml` binds port 5432 only to
localhost. Set `LOCAL_POSTGRES_PASSWORD`, then run
`docker compose -f compose.postgres.yml up -d`. Locally use `sslmode=disable` only
with the loopback hostname; cloud endpoints require verified TLS.

Supabase setup uses the private `inventory` schema, not `public`. It does not expose tables through the Data API or alter existing CreatorOS tables. The local helper `scripts/supabase.py` prompts for the password without echo and stores local connection settings with owner-only permissions in ignored `data/cloud/`.

### This workspace's Supabase project

Project `pleggnxdtkyjoitrtyla` (Inventorymanagement) is in Mumbai. The session pooler
is `aws-1-ap-south-1.pooler.supabase.com:5432`, database `postgres`, user
`postgres.pleggnxdtkyjoitrtyla`. No password is committed.

Run `python3 scripts/supabase.py configure` in your own interactive Terminal. It
prompts without echo, verifies TLS and credentials without creating records, then
stores the connection in `data/cloud/supabase.json` with owner-only permissions.
On macOS it uses the system CA bundle for certificate validation.

After stopping the H2 app and making a backup, `python3 scripts/supabase.py migrate`
imports into the empty private `inventory` schema. Start the cloud-backed local
app with `python3 scripts/supabase.py serve`. Credentials stay outside process
arguments and source files. No API key is needed for this JDBC connection.
