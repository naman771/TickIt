# TickIt

**Real-time ticket reservations, built for the rush.**

A ticketing application with live seat selection, temporary reservations, and a checkout backed by PostgreSQL and Redis. Built with Java 21 and Spring Boot.

[Getting started](#getting-started) · [Architecture](docs/ARCHITECTURE.md) · [API](docs/API.md) · [Demo guide](docs/DEMO.md) · [CI](https://github.com/naman771/TickIt/actions)

![TickIt — event discovery](docs/images/tickit-desktop.png)

## The experience

Discover events, filter by category, and search by artist or city. Choose a seat on the live map, reserve it for two minutes, then confirm. Bookings appear in **My tickets**. Other connected browsers see reservations, releases, and confirmations as they happen.

[See the seat-selection and checkout screen →](docs/images/tickit-booking.png)

## Getting started

With Docker and Docker Compose installed:

```bash
git clone https://github.com/naman771/TickIt.git
cd TickIt
docker compose up --build -d
```

Open [localhost:8080](http://localhost:8080). The application creates its schema and seeds three events, 132 seats, and three demo accounts. The first build downloads dependencies.

For a machine already using the default ports:

```bash
APP_PORT=8081 POSTGRES_PORT=5433 REDIS_PORT=6380 docker compose up --build -d
```

Prefer running Java locally? See [local development and configuration](docs/DEVELOPMENT.md).

## How it holds up under contention

| Layer | Responsibility |
|---|---|
| Redis holds | Atomic acquisition, owner-checked release, and a 120-second TTL |
| PostgreSQL | `UNIQUE (seat_id, event_id)` prevents duplicate confirmed bookings |
| Transaction lifecycle | Hold release, live updates, and email dispatch run after commit |
| Redis token bucket | Atomic per-IP limits: 20-token burst, 5 tokens/second refill |
| WebSockets | Seat updates over STOMP, with periodic API reconciliation |

`@Version` protects updates to existing bookings. The database uniqueness constraint protects competing inserts. [Read the architecture notes →](docs/ARCHITECTURE.md)

## Verification

**19 tests passing**, including a 50-thread contention test, rollback safety, expiry updates, and direct database contention without Redis. The contention test asserts one confirmed booking and one database row.

CI runs the suite against PostgreSQL and Redis, validates Docker Compose, and builds the container. [Validation details →](docs/VALIDATION.md)

```bash
# Requires a separate tickit_test database and local PostgreSQL/Redis.
DB_USER="$(whoami)" mvn verify

# Against a running application; reserves and books one available seat.
./scripts/concurrency_benchmark.sh http://localhost:8080 1 50
```

## Project notes

This is a working MVP with simulated accounts. It does not collect payments or provide authentication. SMTP is optional and disabled by default. JWT, durable notifications, and multi-instance messaging are documented in the [roadmap](docs/ROADMAP.md).

[API reference](docs/API.md) · [Demo walkthrough](docs/DEMO.md) · [Development](docs/DEVELOPMENT.md) · [Contributing](CONTRIBUTING.md) · [Asset credits](docs/ASSETS.md)
