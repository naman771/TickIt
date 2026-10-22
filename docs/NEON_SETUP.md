# Neon integration

TickIt keeps its Spring Boot, PostgreSQL JDBC, JPA and Flyway architecture. Node.js is used for Neon infrastructure tooling and npm command entry points; it does not replace the Java server.

## Local setup

Run `neon login`, then `neon config plan` to inspect the configuration for the linked project. Confirm its region supports Object Storage and the project is on Free before `neon deploy`. Deployment provisions the private `assets` bucket declared in `neon.ts` and pulls provider variables into the ignored `.env` file. Keep existing Clerk settings.

The local OAuth MCP configuration is in `.codex/config.toml`; it contains the Neon MCP URL and requires its own first-use authorization. Installed skills and MCP client configuration stay local.

## Database environment

- `DATABASE_URL`: normal application connection, typically Neon pooled PostgreSQL.
- `DATABASE_URL_UNPOOLED`: optional direct connection for Flyway. If omitted for a Neon pooled hostname, the application uses the corresponding direct Neon endpoint for migrations.

The environment adapter translates provider URLs into JDBC settings, decodes credentials, and maps `channel_binding` to the JDBC `channelBinding` property. Credentials are supplied separately from the JDBC URL. Explicit Spring datasource settings take precedence.

`npm start` safely loads local dotenv files without shell execution and launches Java. The Docker container uses environment variables configured in the host dashboard; it does not copy local `.env` files.

```sh
npm ci
npm run build
npm start
```

Node 22+ and JDK 21 / Maven are required locally. `npm test` runs Maven verify. To run on another port: `npm start -- --server.port=8082`.

## Current verification

The supplied Neon PostgreSQL 16 database was reached by the running app. Flyway applied V1 and V2, `/actuator/health` returned `UP`, and `/api/events` returned an empty array from the new database. No sample data was seeded.

Validation: `npm run build` returned `BUILD SUCCESS`; `DB_USER=naman mvn -B verify` passed 44 tests with zero failures or errors. The initial regression run failed because it used the CI-only `tickit` database role, which does not exist on this local machine.

Object Storage is declared but not yet provisioned or connected to an application upload/download flow. CLI login is required before deployment, retrieving branch-specific storage credentials, and end-to-end upload/download verification. MCP authorization has not been tested. Neither the configuration file nor an offline project link proves remote provisioning.

Neon supplies the database and storage backend; public Java application hosting remains a separate Render deployment. No paid upgrade is authorized.
