# Login and access management

The Java server now requires authentication for the workspace, inventory JSON, CSV exports and all business writes. The landing intro and its public assets remain accessible. Individual accounts have no default credentials. The explicitly enabled `--demo-personas` mode instead provisions six fictional, public demo credentials for the persona picker.

## Demo personas (current local preview)

Start with `--serve --demo-personas --port=8081`. Choose a role and click a card; the normal login form fills its role, username and password. Only clicking **Login** submits it. Java still verifies the account, password, active status and role, creates a session, and enforces permissions.

| Role | Persona | Username |
| --- | --- | --- |
| Admin | Maya Shah | `demo.admin.maya` |
| Admin | Arjun Mehta | `demo.admin.arjun` |
| Manager | Neha Rao | `demo.manager.neha` |
| Manager | Kabir Sethi | `demo.manager.kabir` |
| Staff | Riya Patel | `demo.staff.riya` |
| Staff | Aman Verma | `demo.staff.aman` |

`DemoPersonas` defines these public fixtures. Passwords are intentionally supplied to the browser for autofill, and stored in the database as salted hashes. Seeding is repeatable and preserves account IDs and existing accounts. It never resets changed passwords, reactivates disabled accounts or changes roles. Cards are omitted if a persona's credentials/role/status no longer match. Two independent Manager/Admin accounts support demonstrating the self-approval restriction. Without the flag the picker is hidden, and normal first-Admin setup remains available on an empty database.

## Individual accounts (without demo mode)

1. Open the landing page and choose **Login**. The form replaces the welcome text/buttons in the black area below the truck.
2. On an empty installation, create the first **Admin** account. The Manager and Staff choices are visible but disabled during this one-time setup.
3. In the application, open **People & access**. Add individual Manager and Staff accounts, or another Admin.
4. Use **Logout** at the bottom left. The session is revoked on the server, the cookie is removed, and the login form opens.
5. Later logins offer Admin, Manager and Staff. The chosen role must match the stored account role; choosing Admin does not grant access.

Usernames are case-insensitive and contain 3–40 letters, digits, dots, underscores or hyphens. New/reset passwords require 15–128 characters. They are never trimmed, reflected into forms, written to documentation, or stored as plaintext. Account administration supports role changes, disabling/reactivation, and password reset. The last active Admin cannot be disabled or demoted. Changes revoke the target account's sessions; changing your own account requires a fresh login.

## Permissions

| Action | Admin | Manager | Staff |
| --- | --- | --- | --- |
| View products and stock; record quantity-based sales | Yes | Yes | Yes |
| Manage products, suppliers and purchase orders | Yes | Yes | No |
| Analytics and movement history | Yes | Yes | No |
| Independent purchase review and receiving deliveries | Yes | Yes | No |
| Create, reset, disable and assign accounts | Yes | No | No |

Purchase creators cannot approve their own order, including Admins. Existing Java service checks remain authoritative; sidebar visibility is only presentation. Staff is represented by the `Role.STAFF` enum. Old inventory/demo movement UUIDs and records remain intact.

## Java implementation

- `PasswordHasher`: JDK PBKDF2-HMAC-SHA256, 600,000 iterations, independent 128-bit random salts and 256-bit outputs; constant-time hash comparison.
- `JdbcAccountRepository`: prepared statements, additive V004 migration, unique usernames, account-change audit records, and serialized first/last-admin invariants.
- `AccessService`: verifies credentials, active status and selected role; enforces account administration using the current persisted actor; generic login failures. Five failed attempts for a username block attempts for ten minutes; a server-wide limit of 30 attempts per minute limits bursts. Rate limits are in memory and reset when the server restarts.
- `SessionManager`: server-side sessions using independent 256-bit random session and CSRF tokens. Anonymous form sessions expire after ten idle minutes; authenticated sessions after thirty idle minutes or an eight-hour absolute lifetime. Sessions do not survive server restarts; accounts do.
- `LocalPreviewServer`: checks loopback Host/Origin, validates session-bound CSRF on writes, rotates the session after login/setup, rejects unsigned actor cookies, and protects API/export endpoints. Login/logout never use GET for credential/session changes. Anonymous workspace requests redirect to login; API/export requests return HTTP 401.
- `AccountPage`, `LoginPage`: escaped server-rendered HTML with no password echo. Password fields support autofill, pasting and Show/Hide. Password reset/creation occurs only after Admin authorization.

The runtime entry point always uses the authenticated server constructor. The explicit actor constructors remain isolated adapters for domain/legacy HTTP tests; no URL, cookie or runtime flag enables them in the running application.

## Storage and scope

Accounts and access-change events are saved in the existing ignored `data/inventory.mv.db` H2 database. Inventory tables are preserved. `app_users` stores only password hashes; the `access_events` table records actor, target, action and time for account changes. Logged-in user UUIDs are used for new stock and purchase events.

This installation remains bound to loopback HTTP. Cookies use `HttpOnly`, `SameSite=Strict` and `Path=/`; Secure cookies and HTTPS configuration are required before any future network deployment. Real email recovery, MFA, external identity providers and forced first-login password changes are not part of this local version. Admins issue account passwords and perform resets; there is no public registration.

## References

- [GOV.UK password input](https://design-system.service.gov.uk/components/password-input/): visible labels, password-manager attributes, and an accessible Show/Hide control.
- [OWASP Password Storage](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html): salted adaptive password hashing and PBKDF2 work factors.
- [OWASP Authentication](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html): generic errors and login throttling.
- [OWASP Session Management](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html): opaque sessions, rotation, expiry and invalidation.

The visual treatment retains the existing charcoal, olive and lime theme rather than copying a separate product's branding.

## Verification

All 91 Java tests pass. New coverage verifies persistent salted hashes, incorrect-role rejection, forged actors, disabled accounts, password resets, concurrent first-admin setup, last-admin protection, idle/absolute session expiry, CSRF binding, session rotation, cookie replay after logout, anonymous API/export/write denial, cross-origin login rejection, and first-admin creation through HTTP. Test credentials exist only in isolated test databases. Browser review confirmed anonymous `/app` access redirects to the landing form, with the supplied truck still and the one-time Admin setup controls. No real account or password was created by automated UI actions.

Persona verification: all six role-specific credentials authenticate through the HTTP login endpoint; each produces the permitted sidebar and logs out normally. Tests cover repeatable provisioning, stable IDs, preservation of existing users, and withholding edited personas instead of overwriting their credentials.

Latest verification: all 94 tests pass after adding persona provisioning and role-specific login coverage. Browser checks confirmed Admin account administration, Manager analytics without user administration, and Staff inventory/sale access without restricted management actions. Persona selection fills both fields and leaves submission to the Login button.
