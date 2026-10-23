# Hosted environment

- Customer website: https://tickit-mpy2.onrender.com
- Restricted developer workspace: https://tickit-mpy2.onrender.com/developer/
- Health endpoint: https://tickit-mpy2.onrender.com/actuator/health

## Connected infrastructure

The Spring Boot Docker service and private Key Value instance run on Render Free in Frankfurt. PostgreSQL 16 and the private `assets` bucket run on the existing Neon Free project in the same region. No custom domain, paid instance, persistent Render disk, or paid plan upgrade was provisioned.

Service configuration is stored in Render environment variables. The database uses the pooled `DATABASE_URL`; Flyway uses `DATABASE_URL_UNPOOLED`. Private Redis is supplied through `SPRING_DATA_REDIS_URL`. Native Java S3 storage uses branch-scoped AWS-standard environment variables. Credentials are never committed.

The deployment uses the existing Clerk development instance and test-prefixed keys. Google/email authentication is real provider-backed authentication, but this is an interim hosted environment, not a completed production identity deployment. Clerk production domain/OAuth configuration remains separate. The allowed JWT application origin is the exact public Render URL. Developer access remains limited to the configured provider subjects.

## Operational limits

Render Free sleeps when idle; cold starts on this Spring Boot application can take several minutes. Its Redis-compatible store is non-durable. Neon Free has compute and storage quotas. No paid upgrades are authorized.

The new Neon catalog is empty. The previous local sample catalog was not migrated. Paid checkout and transactional email remain disabled. Existing users can sign in with the same Clerk account; the backend provisions the corresponding local account record on first use against Neon.

## Updating the app

The service builds the repository's `main` branch from its Dockerfile. Documentation-only changes (`docs/**` and `README.md`) are excluded from rebuilds to preserve the free build allowance. Auto-deploy is configured for passing repository checks; confirm the GitHub connection in Render if future commits do not trigger a build. Apply provider environment changes directly in Render and redeploy the existing build when code is unchanged. Never replace the working Clerk environment with the Blueprint's initial catalog-only defaults.

Before announcing an update, verify health, the public identity configuration, the sign-in UI, and anonymous developer API rejection. Authenticated developer storage operations were tested against real Neon services locally; public browser operations require signing in on the hosted origin.

## Deployment verification

Verified on the public origin after the final connected-auth rollout:

| Check | Result |
|---|---|
| Actuator health | HTTP 200, `UP` |
| Public identity configuration | `authProvider: clerk`, checkout disabled |
| Database-backed events | HTTP 200, empty catalog |
| Developer frontend | HTTP 200 |
| Anonymous developer overview and asset APIs | HTTP 401 |
| Clerk sign-in modal | Loaded, Google option present, no browser errors |
| Private provider configuration | Matches the tested local Neon/Clerk configuration |
| Render web and Redis plans | Free |

The browser check waited for Clerk to finish initialization before clicking sign-in. Completing Google OAuth as a real user and authenticated storage actions on the public origin remain user-session checks; no account session was impersonated for verification.
