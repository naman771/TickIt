# Neon integration

TickIt keeps its Spring Boot, PostgreSQL JDBC, JPA and Flyway architecture. Node.js is used for Neon infrastructure tooling and npm command entry points; it does not replace the Java server.

## Local setup

Run `neon login`, then `neon config plan` to inspect the configuration for the linked project. Confirm its region supports Object Storage and the project is on Free before `neon deploy`. Deployment provisions the private `assets` bucket declared in `neon.ts` and pulls provider variables into the ignored `.env` file. Keep existing Clerk settings.

The local OAuth MCP configuration is in `.codex/config.toml`; it contains the Neon MCP URL and requires its own first-use authorization. Installed skills and MCP client configuration stay local.

## Database environment

- `DATABASE_URL`: normal application connection, typically Neon pooled PostgreSQL.
- `DATABASE_URL_UNPOOLED`: optional direct connection for Flyway. If omitted for a Neon pooled hostname, the application uses the corresponding direct Neon endpoint for migrations.

The environment adapter translates provider URLs into JDBC settings, decodes credentials, and maps `channel_binding` to the JDBC `channelBinding` property. Credentials are supplied separately from the JDBC URL. Explicit Spring datasource settings take precedence. The destructive local `test` profile ignores provider URLs so it cannot be redirected to the Neon production database by an inherited `DATABASE_URL`.

`npm start` safely loads local dotenv files without shell execution and launches Java. The Docker container uses environment variables configured in the host dashboard; it does not copy local `.env` files.

```sh
npm ci
npm run build
npm start
```

Node 22+ and JDK 21 / Maven are required locally. `npm test` runs Maven verify. To run on another port: `npm start -- --server.port=8082`.

## Current verification

The supplied Neon PostgreSQL 16 database was reached by the running app. Flyway applied V1 and V2, `/actuator/health` returned `UP`, and `/api/events` returned an empty array from the new database. No sample data was seeded.

Validation: `npm run build` returned `BUILD SUCCESS`. The local regression suite passed 48 tests with zero failures or errors; one live-cloud test is intentionally skipped unless explicitly enabled. The opt-in live-cloud test also passed separately.

## Private asset storage

`neon deploy` provisioned the private `assets` bucket on the production branch in Frankfurt. `neon config plan` reported no remaining changes. The existing `uploads` bucket was preserved. No paid plan was enabled.

Set `APP_STORAGE_ENABLED=true` alongside the branch's injected `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_ENDPOINT_URL_S3` and `AWS_REGION`. The native AWS Java SDK uses the Neon endpoint, path-style addressing and SigV4. Credential values stay server-side. Missing/disabled storage returns HTTP 503; it never falls back to an unrelated AWS account.

Authorized developers can use **Asset storage** in `/developer/` to upload, list, download and delete private workspace files. Every route under `/api/developer/assets` checks the developer allowlist. Downloads are attachments with `nosniff` and no-store. Files are bounded to 5 MB; generated object IDs avoid filename/path injection. The workspace lists up to 100 files. Files are not yet attached to event records or exposed as public event posters.

Live-provider verification exercised the application controllers with real Neon JDBC and S3 calls. Spring Security supplied a test developer principal; this does not replace validation of a real browser sign-in. The test uploaded a unique payload, listed it, downloaded identical bytes, verified anonymous object access was denied, deleted it and verified a subsequent 404. Test objects were cleaned up.

To repeat the optional provider check, inject the local Neon environment securely and set `NEON_STORAGE_E2E=true`, then run `mvn -B -Dtest=NeonServicesE2ETest test`. It must not use the destructive local booking test profile; the test selects its separate `neon-e2e` profile and disables seeding.

OAuth MCP is configured locally; its first-use authorization remains a separate step from CLI login. CLI service provisioning and live service verification succeeded without MCP.

Neon supplies the database and storage backend; public Java application hosting remains a separate Render deployment. No paid upgrade is authorized.
