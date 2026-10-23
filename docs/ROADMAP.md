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
| 7. Email | Optional | Async SMTP implementation. Durable outbox and retries pending. |
| 8. Expired holds | Complete | Bounded Redis expiry-index worker and live status notifications. |
| 9. API and website | MVP complete | Discovery, search/filter, seat selection, confirmation, ticket history. |
| 10. Authentication | Connected; production instance pending | Clerk signup/Google sign-in integration, signed-session verification, account isolation, separate developer authorization. |
| 11. Testing | Partial | 89 local regression tests, real WebSocket transport, a separately passing Neon storage test, and 22 CI browser checks. Testcontainers and broad k6 capacity testing remain. |
| 12. CI/CD | Partial | GitHub Actions backend/browser tests and container build; connected free Render deployments. Optional registry publication remains. |
| 13. Deployment | Hosted interim environment | Separate Render customer/organizer sites, private Redis and Neon. Free-host limits and production identity/operations work remain; Kubernetes is optional. |

Before paid public launch: production identity configuration, payment integration and reconciliation, request idempotency, durable notification delivery, deployment observability, a broader failure/load-testing programme.

## Organizer catalog update

Organizer-owned drafts, event-specific seating, shared-capacity standing ticket types and validated publishing are implemented. The customer frontend renders custom layouts and GA quantity selection. Paid checkout, GA confirmed order lines, ticket issuance, organizer cancellations/rescheduling, public poster uploads, reusable verified named-venue libraries and edits to published layouts remain future work.

## Framework migration

The codebase now uses Spring Boot 4.0.9 / Framework 7.0.10, Jackson 3, Hibernate 7 and Tomcat 11. See [migration verification](FRAMEWORK_MIGRATION.md). Publication is authorized. The migration verification records local results; GitHub Actions and Render track container validation and deployment status.
