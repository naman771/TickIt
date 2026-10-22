# Roadmap

The original delivery phases remain the structure for further work. The current release prioritizes a usable, demonstrable booking flow.

| Phase | Status | Delivered / next step |
|---|---|---|
| 1. Bootstrap | MVP complete | Java 21, Spring Boot, Maven, Dockerfile. Module separation deferred. |
| 2. Domain and schema | Complete | JPA entities, PostgreSQL schema, Flyway, demo seed data. |
| 3. Seat holds | Complete | Atomic Redis acquisition, owner-checked release, 120-second TTL. |
| 4. Booking transaction | Complete | Hold validation, unique booking constraint, `@Version`, post-commit side effects. |
| 5. Live updates | MVP complete | STOMP seat updates and reconciliation. Shared broker needed for scaling. |
| 6. Rate limiting | MVP complete | Atomic per-IP token bucket; optional demo-user bucket. Authenticated identity pending. |
| 7. Email | Optional | Async SMTP implementation. Durable outbox and retries pending. |
| 8. Expired holds | Complete | Bounded Redis expiry-index worker and live status notifications. |
| 9. API and website | MVP complete | Discovery, search/filter, seat selection, confirmation, ticket history. |
| 10. Authentication | Planned | JWT, trusted user identity, authorization, password handling. |
| 11. Testing | Partial | Integration/concurrency regression suite. Testcontainers and k6 remain. |
| 12. CI/CD | Partial | GitHub Actions tests and container build. Registry publication/deployment pending. |
| 13. Deployment | Local ready | Docker Compose. Hosted staging and Kubernetes remain. |

Before a public launch: authentication, payment integration and reconciliation, request idempotency, durable notification delivery, deployment observability, dependency upgrades, and a broader failure/load-testing programme.
