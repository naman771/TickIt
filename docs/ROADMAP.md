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
| 7. Email | Implemented locally; activation pending | Durable outbox, leased retries, SMTP/HTTPS adapters; real sender and delivery verification pending. |
| 8. Expired holds | Complete | Bounded Redis expiry-index worker and live status notifications. |
| 9. API and website | MVP complete | Discovery, search/filter, seat selection, confirmation, ticket history. |
| 10. Authentication | Connected; production instance pending | Clerk signup/Google sign-in integration, signed-session verification, account isolation, separate developer authorization. |
| 11. Testing | Partial | 185 local regression tests, real WebSocket transport, a separately passing Neon storage test, and 86 browser checks. Testcontainers and broad k6 capacity testing remain. |
| 12. CI/CD | Partial | GitHub Actions backend/browser tests and container build; connected free Render deployments. Optional registry publication remains. |
| 13. Deployment | Hosted interim environment | Separate Render customer/organizer sites, private Redis and Neon. Free-host limits and production identity/operations work remain; Kubernetes is optional. |

Before paid public launch: production identity configuration, real-provider payment validation and activation, durable notification delivery, deployment observability, a broader failure/load-testing programme.

## Organizer catalog update

Organizer-owned drafts, event-specific seating, shared-capacity standing ticket types and validated publishing are implemented. The customer frontend renders custom layouts and GA quantity selection. Razorpay test checkout and confirmed GA order lines are connected on the hosted environment. Published-seat bulk editing, event cancellation/archive and audited rescheduling are implemented, with payment history preserved. Automatic full refunds now handle cancelled/deleted events and captured payments without issued tickets, with durable retries and visible provider outcomes. QR issuance and online entry check-in are implemented in the ticket lifecycle release. Activating real notification delivery, public poster uploads and reusable verified named-venue libraries remain future work.

## Framework migration

The codebase now uses Spring Boot 4.0.9 / Framework 7.0.10, Jackson 3, Hibernate 7 and Tomcat 11. See [migration verification](FRAMEWORK_MIGRATION.md). Publication is authorized. The migration verification records local results; GitHub Actions and Render track container validation and deployment status.

Payment implementation and account prerequisites are documented in [PAYMENTS.md](PAYMENTS.md). Live payment acceptance remains disabled.

## Next delivery stages

1. QR tickets: one unguessable credential per admission, issued only after confirmed payment; customer display/download and organizer/staff scanning. Check-in must be atomic and single-use, reject other events and invalidate cancelled/refunded tickets.
2. Reliable notifications: a durable email outbox for tickets, receipts, refunds, cancellations and rescheduling, with retries and delivery status.
3. Organizer operations: booking/refund reports, guest lists, scoped entry-staff accounts and check-in reporting. Multi-organizer payouts/settlement remain separate from the current single merchant account.
4. Public media and venue tools: safe poster uploads, reusable verified venue layouts and richer published standing-tier management.
5. Launch readiness: production identity/OAuth configuration, live merchant activation, payment/refund end-to-end acceptance, monitoring, tested backups/restoration and broader load/failure tests. Existing free hosting remains the interim environment.

## 24 October ticket lifecycle release

QR issuance, private downloads/print, online single-use check-in, event staff access, organizer reports, durable notifications and refund operator recovery are implemented. The previous ticket lifecycle release is deployed; the entry/email activation checkpoint has separate validation. See [continuation status](CONTINUATION.md) for evidence, external blockers and the exact next steps; older future-work lists above reflect the earlier hosted release.
