# Deploy without paid resources

This deployment provisions a Render Free Docker web service and Free Key Value instance. PostgreSQL runs on Neon Free. No custom domain, paid disk, background worker, or paid database is provisioned. Keep both workspaces on their free plans; do not enable paid upgrades. If the provider requires a paid plan, stop rather than upgrading.

## Connect Neon

1. Create a Neon **Free** project named `tickit`, preferably in Frankfurt to match the app. Use its PostgreSQL 16 option and an empty database.
2. In **Connect**, choose Java/JDBC and the direct (non-pooled) endpoint for this small application. The application manages its own three-connection pool; Flyway runs on this connection.
3. Copy the JDBC URL, username and password directly into Render when prompted. The URL should look like `jdbc:postgresql://ep-example.eu-central-1.aws.neon.tech/neondb?sslmode=require`. Keep the password in `DB_PASS`, not in the URL or repository. Use the exact hostname and database shown by Neon.

## Deploy Render

1. Choose **New → Blueprint**, connect `naman771/TickIt`, and select `main` with root `render.yaml`.
2. Supply `SPRING_DATASOURCE_URL` (the JDBC URL), `DB_USER`, and `DB_PASS` from Neon. Do not paste credentials into chat.
3. Confirm both services show **Free / $0** before deploying. The Blueprint creates no Render Postgres instance because its free databases expire after 30 days.
4. After the deployment succeeds, use the actual `https://<assigned-name>.onrender.com` address shown by Render. The developer frontend is at `/developer/`; its API remains restricted.
5. Confirm `/actuator/health` is UP and `/api/events` returns successfully. An empty new database has no events: seed data is disabled intentionally. The existing local catalog is not migrated by this deployment.

## Authentication activation is a separate check

The Blueprint starts in `catalog` mode, with protected operations disabled. This prevents publishing local Clerk development credentials as a completed production identity setup. The current local Google signup remains configured locally.

Clerk production setup requires DNS configuration. A Render-assigned subdomain does not give control of its parent DNS zone. Resolve a supported free identity setup for the hosted origin before claiming that production signup works; do not purchase a domain or enable a paid proxy to bypass the zero-budget constraint.

Once identity is configured, set the provider variables directly in Render, including the exact hosted origin and the verified developer subject. Never place secrets in `render.yaml`. Re-check anonymous API rejection, customer account ownership, developer authorization, and Google signup on the actual hosted URL.

## Optional private storage

After the Neon bucket is provisioned, add `APP_STORAGE_ENABLED=true`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_ENDPOINT_URL_S3` and `AWS_REGION` directly to the Render service environment from your local ignored Neon environment file. Keep the bucket private. The Java application uses these variables directly; Node tooling is not needed inside the Docker runtime. Do not put credential values in the Blueprint.

## Free-tier limits

Render Free services sleep when idle and have monthly usage limits. Key Value Free is not durable: active holds and rate-limit buckets can be lost on restart. Confirmed records belong in PostgreSQL. Neon Free also has storage and compute limits. Exceeding the free allowance must lead to a limit or suspension, not an authorized paid upgrade.

Paid checkout remains disabled. Payment-provider test mode can be integrated without moving money; live transactions incur provider fees and need a separate decision.

Sources: [Render Free](https://render.com/docs/free), [Render Blueprints](https://render.com/docs/blueprint-spec), [Neon plans](https://neon.com/pricing), [Clerk production setup](https://clerk.com/docs/guides/development/deployment/production).
