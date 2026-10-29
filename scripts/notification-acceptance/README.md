# Notification acceptance

This harness uses an isolated Docker project `pams-notify-acceptance`, MySQL 8.0,
the repository Nginx configuration, and loopback ports 13316, 18085 and 18086.
It never points application fixtures at `pams_db` or the existing deployment.

From the repository root, with Docker running:

```sh
node scripts/notification-acceptance/run.mjs start
node scripts/notification-acceptance/run.mjs test
```

The opt-in Maven suite uses the real MySQL driver and production Flyway locations.
`NotificationMySqlMigrationTest` recreates only `notification_upgrade_acceptance`
and verifies V49 → V56 historical IDs, personal reads, and archived shared history.
Normal Maven tests skip this class. `test --reset` additionally recreates both
isolated fixture schemas; it requires the expected Docker project and refuses to
run while the acceptance backend is active. It does not reset a developer database.

After the database tests, package the backend with the configured Maven/JDK and
build the frontend if its sources changed:

```sh
cd pams-backend
mvn -DskipTests package
cd ..
node scripts/notification-acceptance/run.mjs serve
node scripts/notification-acceptance/http.mjs
```

The HTTP suite uses synthetic teacher/staff accounts from test seeding, without
mocking HTTP. It checks Nginx streaming/heartbeat, Bearer authentication, inbox
isolation, business confirmation, disconnected REST reconciliation, revocation,
account disablement, durable recovery after restart, and expired-lease idempotency.
It temporarily changes only its isolated database and restores its permission and
account-status fixtures in `finally`.

Run the opt-in real-browser test from `pams-web` with
`PAMS_NOTIFICATION_ACCEPTANCE_LIVE=1 npm run test:e2e -- e2e/notification-live.spec.ts`.
It uses only `http://127.0.0.1:18086`, with no mocked API. The normal browser suite
skips it. On PowerShell, set this variable for the command and remove it afterward.

```sh
node scripts/notification-acceptance/run.mjs stop
node scripts/notification-acceptance/package.mjs
```

`stop` removes only acceptance containers/network and preserves its database volume.
Runtime credentials, logs, reports and release candidates stay under the ignored
`.backups/notification-acceptance/` directory. Packaging includes only the backend
JAR, frontend assets, Nginx configuration and hashes; it excludes environment files
and database snapshots. Each invocation creates a fresh candidate directory so
obsolete frontend assets cannot leak into a later candidate.

For a server-side snapshot rehearsal, `NotificationAcceptanceMigration` can run
through Spring Boot's `PropertiesLauncher`. It requires the explicit marker
`PAMS_NOTIFICATION_ACCEPTANCE=ISOLATED`, a whitelisted isolated URL/schema, and
environment credentials. Its `permissions` argument produces a review report
without applying migration approval. This tool never bypasses the existing owner
approval gate, never runs `repair`, and rejects production schema URLs.

Snapshot restoration, production migration and live cutover must use a reviewed
maintenance plan. Do not treat an isolated success as approval to modify the live
database, and do not roll back only the application image after changing the schema.

Before a production snapshot or permission report, run `production-preflight.sh`
on the deployment host with `PAMS_EXPECTED_SCHEMA` set to the reviewed database.
It reads the running backend's explicit datasource URL, rejects a mismatched
schema or separate Flyway target, and queries that schema. A database named by
`MYSQL_DATABASE` or a repository default is not evidence of the application's
actual target. Credentials and full runtime URLs are never printed.

The internal hostname is `concerto.yuanlab.work`. Install `concerto-domain.conf`
as the host Nginx vhost forwarding port 80 to the existing 8081 service; both
proxy layers explicitly disable buffering/compression for SSE. Internal DNS
must publish `concerto.yuanlab.work A 172.18.38.131`. This private address is
for the LAN; the HTTP binding does not provision DNS or an HTTPS certificate.
