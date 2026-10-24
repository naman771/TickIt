# Roadmap

The original delivery phases remain the structure for further work. The current release prioritizes a usable, demonstrable booking flow.

| Phase | Status | Delivered / next step |
|---|---|---|
| 1. Bootstrap | MVP complete | Java 21, Spring Boot, Maven, Dockerfile. Module separation deferred. |
| 2. Domain and schema | Complete | JPA entities, PostgreSQL schema, Flyway, opt-in development fixtures. |
| 3. Seat holds | Complete | Atomic Redis acquisition, owner-checked release, 120-second TTL. |
| 4. Booking transaction | Complete | Hold validation, unique booking constraint, `@Version`, post-commit side effects. |
| 5. Live updates | MVP complete | STOMP seat updates and reconciliation. Shared broker needed for scaling. |
| 6. Rate limiting | MVP complete | Atomic per-IP token bucket; verified session-subject bucket. |
| 7. Email | Hosted delivery enabled; event tracking pending | Durable outbox, leased retries, Brevo HTTPS provider acceptance and hosted booking/inbox receipt verified; held backlog requires review. |
| 8. Expired holds | Complete | Bounded Redis expiry-index worker and live status notifications. |
| 9. API and website | MVP complete | Discovery, search/filter, seat selection, confirmation, ticket history. |
| 10. Authentication | Connected; production instance pending | Clerk signup/Google sign-in integration, signed-session verification, account isolation, separate developer authorization. |
| 11. Testing | Partial | 185 local regression tests, real WebSocket transport, a separately passing Neon storage test, and 86 browser checks. Testcontainers and broad k6 capacity testing remain. |
| 12. CI/CD | Partial | GitHub Actions backend/browser tests and container build; connected free Render deployments. Optional registry publication remains. |
| 13. Deployment | Hosted interim environment | Separate Render customer/organizer sites, private Redis and Neon. Free-host limits and production identity/operations work remain; Kubernetes is optional. |

Before paid public launch: production identity configuration, real-provider payment validation and activation, durable notification delivery, deployment observability, a broader failure/load-testing programme.

## Organizer catalog update

Organizer-owned drafts, event-specific seating, shared-capacity standing ticket types and validated publishing are implemented. The customer frontend renders custom layouts and GA quantity selection. Razorpay test checkout and confirmed GA order lines are connected on the hosted environment. Published-seat bulk editing, event cancellation/archive and audited rescheduling are implemented, with payment history preserved. Automatic full refunds now handle cancelled/deleted events and captured payments without issued tickets, with durable retries and visible provider outcomes. QR issuance and online entry check-in are implemented in the ticket lifecycle release. Brevo delivery is enabled after hosted acceptance; provider delivery/bounce tracking, public poster uploads and reusable verified named-venue libraries remain future work.

## Framework migration

The codebase now uses Spring Boot 4.0.9 / Framework 7.0.10, Jackson 3, Hibernate 7 and Tomcat 11. See [migration verification](FRAMEWORK_MIGRATION.md). Publication is authorized. The migration verification records local results; GitHub Actions and Render track container validation and deployment status.

Payment implementation and account prerequisites are documented in [PAYMENTS.md](PAYMENTS.md). Live payment acceptance remains disabled.

## Delivery stages and remaining work

1. QR tickets are deployed. Physical-device camera/admission acceptance remains separate from automated tests and a phone reading a downloaded QR.
2. Durable notifications and hosted sending are deployed. Provider delivery/bounce events and review of the held historical backlog remain.
3. Organizer reports and scoped entry staff are deployed. Multi-organizer payouts/settlement remain separate from the current single merchant account.
4. Public media and venue tools: safe poster uploads, reusable verified venue layouts and richer published standing-tier management.
5. Launch readiness: production identity/OAuth configuration, payment/refund acceptance, monitoring, retained backups and broader load/failure tests. See the [isolated restore exercise](BACKUP_RESTORE.md). Existing free hosting and Razorpay test mode remain; live merchant activation is blocked under the current constraints.

## 24 October ticket lifecycle release

QR issuance, private downloads/print, online single-use check-in, event staff access, organizer reports, durable notifications and refund operator recovery are deployed. On 24 October, GitHub CI and both Render deployments were verified at `07e2b28`, including the customer ticket visibility/artwork update. See [continuation status](CONTINUATION.md) for evidence, external blockers and current next steps.
