# Hosted environment

- [Customer website](https://tickit-mpy2.onrender.com)
- [Organizer website](https://tickit-organizers.onrender.com)
- [Entry workspace](https://tickit-organizers.onrender.com/entry.html)
- [Restricted developer workspace](https://tickit-mpy2.onrender.com/developer/)
- [Health endpoint](https://tickit-mpy2.onrender.com/actuator/health)

## Connected infrastructure

The Spring Boot Docker service and private Redis-compatible store use the existing Render Free resources in Frankfurt. PostgreSQL 16 and the private `assets` bucket use Neon Free in the same region. No paid upgrade, domain purchase, Render disk or additional paid worker is authorized. Render Free can sleep when idle; cold starts and suspended background processing can delay tickets, notifications and refund reconciliation. Confirmed business records belong in PostgreSQL; the free Redis store is not durable.

Clerk's development instance supplies real Google/email authentication. Both public frontend origins are permitted explicitly. Production identity/OAuth setup remains separate. Developer authorization uses an independent subject allowlist; organizer or customer signup does not grant developer access.

Razorpay **Test Mode** is connected. Live keys are blocked. Organizer-created events populate the catalog; synthetic catalog fixtures are disabled. Automatic full refunds use durable jobs and retain their original request keys. The outstanding test refund has a provider/account balance restriction, not a recorded successful refund. See [refund operations](REFUNDS.md).

Brevo HTTPS transactional email is configured after verified sender setup, provider acceptance and owner-confirmed receipt of a hosted booking email. Customer delivery is explicitly authorized; historical held messages require review. The queue is durable and visible to developers. Render Free blocks common SMTP ports; the release supports a Brevo HTTPS adapter. A SENT job means provider acceptance, with inbox receipt confirmed for the acceptance messages only. See [notifications](NOTIFICATIONS.md).

## Release scope

The ticket lifecycle release adds private per-admission QR tickets, downloadable/printable tickets, online single-use check-in, event-scoped staff access, guest/financial reports and notification jobs. See [ticket operations](TICKETS_AND_ENTRY.md), [validation](VALIDATION.md) and [remaining work](CONTINUATION.md).

The organizer frontend is an independent static site built with `scripts/build-organizer.mjs`; it calls the same secured Java API through exact-origin CORS. The event editor links to **Bookings & entry**. Staff can use `/entry.html` without enrolling as an organizer, but must be explicitly assigned to an event. The developer frontend currently uses the backend's `/developer/` path rather than a separate public subdomain.

## Deployment and verification

The backend builds `main` with its Dockerfile; the organizer builds the static artifact. Deploy the same verified commit to both existing resources, preserving the current private environment and free plans. Flyway applies additive schema migrations on startup. Never replace connected Clerk/Neon/payment configuration with the Blueprint's initial catalog-only defaults.

After both deployments are live:

```bash
node scripts/verify-release.mjs https://tickit-mpy2.onrender.com https://tickit-organizers.onrender.com
```

This read-only check verifies public assets, health, identity configuration and anonymous API rejection. Release validation additionally compares deployed assets to the commit, verifies migrations and preserved financial history, and checks organizer CORS. Authenticated account acceptance and physical-camera checks are separate; no user session is impersonated. GitHub CI and Render's exact commit/status are the deployment record.
