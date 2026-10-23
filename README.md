# TickIt

**Real-time ticket reservations, built for the rush.**

A ticketing application with live seat selection, temporary reservations, and a checkout backed by PostgreSQL and Redis. Built with Java 21 and Spring Boot.

[Live website](https://tickit-mpy2.onrender.com) · [Organizers](https://tickit-organizers.onrender.com) · [Developer workspace](https://tickit-mpy2.onrender.com/developer/) · [Deployment notes](docs/HOSTED_ENVIRONMENT.md)

[Getting started](#getting-started) · [Architecture](docs/ARCHITECTURE.md) · [API](docs/API.md) · [Walkthrough](docs/DEMO.md) · [CI](https://github.com/naman771/TickIt/actions)

![TickIt — event discovery](docs/images/tickit-desktop.png)

## The experience

Discover events, filter by category, and search by artist or city. Choose a seat on the live map, reserve it for two minutes, then complete checkout when payments are enabled. Bookings appear in **My tickets**. Other connected browsers see reservations, releases, and confirmations as they happen.

[See the seat-selection and checkout screen →](docs/images/tickit-booking.png)

## Getting started

With Docker and Docker Compose installed:

```bash
git clone https://github.com/naman771/TickIt.git
cd TickIt
docker compose up --build -d
```

Open [localhost:8080](http://localhost:8080). The application creates its schema with an empty catalog. The first build downloads dependencies.

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

**66 local tests passing**, including a 50-thread contention test, rollback safety, expiry updates, and direct database contention without Redis. The contention test asserts one confirmed booking and one database row.

An additional opt-in live Neon test verifies database reads and private storage upload, listing, download integrity, anonymous denial and deletion. CI runs the local suite against PostgreSQL and Redis, validates Docker Compose, and builds the container. [Validation details →](docs/VALIDATION.md)

```bash
# Requires a separate tickit_test database and local PostgreSQL/Redis.
DB_USER="$(whoami)" mvn verify

# Set TICKIT_ACCESS_TOKEN locally for an authenticated probe; one seat is briefly held.
./scripts/rate_limit_probe.sh http://localhost:8080 1 25
```

## Accounts and deployment

Customer signup and Google sign-in use Clerk. The API verifies signed sessions and isolates each account's bookings. A separate [developer workspace](docs/PRODUCTION_SETUP.md#developer-workspace) uses a server-side access allowlist.

The [organizer workspace](docs/ORGANIZERS.md) manages private event drafts, custom seat layouts and standing-admission ticket pools. Organizers publish their own events to the customer catalog; each event retains its own layout and prices. [See the organizer workspace →](docs/images/tickit-organizers.png)

Without provider configuration the website permits browsing and keeps protected operations closed. Real payment checkout is the next phase; the API cannot issue unpaid tickets. Sample catalog seeding is opt-in for local development.

[Neon setup](docs/NEON_SETUP.md) connects PostgreSQL and private workspace file storage. A [free deployment Blueprint](docs/FREE_DEPLOYMENT.md) targets Render and Neon free plans; custom domain registration and paid services are outside the current budget. See [production setup and account requirements](docs/PRODUCTION_SETUP.md).

[API reference](docs/API.md) · [Architecture](docs/ARCHITECTURE.md) · [Development](docs/DEVELOPMENT.md) · [Roadmap](docs/ROADMAP.md) · [Contributing](CONTRIBUTING.md) · [Asset credits](docs/ASSETS.md)
