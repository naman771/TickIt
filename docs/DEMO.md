# Five-minute demonstration

## Before presenting

1. Start with `docker compose up --build -d`, or run the local Java setup from the README.
2. Open the app in two windows. Use **Find tickets** to open the same event in both windows. Pick Alex in one and Sam in the other.
3. Confirm `curl -f http://localhost:8080/actuator/health` returns `UP`.
4. Keep a terminal ready at the repository root. Use a free seat, not seeded seat A1.

## Walkthrough

**0:00 — Explain the problem.** Many users can try the same seat. A hold gives one user time to check out; a database uniqueness constraint prevents duplicate confirmed bookings.

**0:30 — Show live holds.** Open an event, choose a seat, then select **Reserve this seat** as Alex. Sam sees the same seat become reserved. Point out the two-minute countdown.

**1:15 — Show release or expiration.** Release Alex's hold and watch it become available in Sam's window. For a quicker expiration demo, start the local app with `APP_SEAT_HOLD_TTL_SECONDS=10 mvn spring-boot:run` before presenting.

**2:00 — Confirm a booking.** Hold a free seat and select **Confirm reservation**. Both windows show it as booked. No payment is taken.

**2:45 — Demonstrate rate limiting.** Open **The project → Developer demonstration** and run the 25-request burst. Repeated requests by that simulated user can succeed idempotently until the bucket is exhausted; observe `429` responses. Give the bucket four seconds to refill before continuing normal checkout.

**3:30 — Show contention proof.**

```bash
mvn test -Dtest=BookingConcurrencyTest
```

Expected invariant: 50 competing users, 1 successful booking, 49 rejections, 1 database row. This exercises the service directly; HTTP rate limiting is demonstrated separately.

**4:15 — Show the architecture.** Explain Redis holds, PostgreSQL uniqueness, and post-commit notifications. `@Version` protects updates to existing rows; uniqueness protects competing booking inserts.

## Optional terminal demo

```bash
./scripts/concurrency_benchmark.sh http://localhost:8080 1 50
```

This consumes one free seat. It reports actual HTTP results, verifies one distinct hold owner, and books using that owner. Do not promise fixed response counts: refill timing and prior requests affect them.

## Troubleshooting

- **Port occupied:** use `APP_PORT=8081 POSTGRES_PORT=5433 REDIS_PORT=6380 docker compose up --build -d`, then open port 8081.
- **429 during checkout:** wait a few seconds for token refill; the hold remains active until its original deadline.
- **Database connection failure:** check `DB_USER`, `DB_PASS`, and that the database exists.
- **No free seats:** choose another event. Reset disposable Compose data only when you intend to delete all demo bookings.
- **A missed live update:** the browser refreshes the seat map within five seconds; check the browser's `/ws` connection if updates remain delayed.

## Honest project positioning

Present this as a concurrency-focused ticket-booking MVP. JWT, payments, durable notifications, distributed WebSocket delivery, and production hardening remain follow-up work. See the README for the exact boundaries.
