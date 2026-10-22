# MVP validation

Validated on **23 October 2026** with Java 21, PostgreSQL 18.6, and local Redis. Tests ran against a separate `tickit_test` database and Redis database 15; browser checks used an isolated `tickit_demo` database and Redis database 14.

## Backend

Command: `DB_USER=<local-postgres-user> mvn -B verify`

```text
Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

| Suite | Tests | Coverage |
|---|---:|---|
| SeatHoldServiceTest | 3 | Acquisition, exclusion, same-owner retries, release |
| RateLimiterServiceTest | 3 | Capacity, rejection, refill |
| BookingFlowIntegrationTest | 6 | Events, seat map, booked-seat conflict, missing hold, mismatched event, required release owner |
| BookingConcurrencyTest | 1 | 50 concurrent contenders; one booking and one database row |
| BookingSafetyTest | 5 | Rollback, notification outage, owner-only release, expiration broadcast, database uniqueness without Redis |

```text
Total Competing Threads: 50
Successful Bookings:     1
Rejected Attempts:      49
```

This is a bounded contention test, not a throughput measurement or proof of behavior under every failure condition.

## Browser walkthrough

Headless Chrome checked two browser windows and a 390px mobile viewport:

- Application assets loaded while non-local network requests were blocked.
- Hold acquisition and release updated the second window.
- Successful checkout updated both windows to `BOOKED`.
- A short, 10-second test hold expired and returned to `FREE`.
- No browser page errors were observed.
- The mobile page had no document-level horizontal overflow; the seat map scrolls within its panel.

The shipped hold default remains **120 seconds**. Screenshots are in [images/](images/).

## Live HTTP demonstration

Command: `./scripts/concurrency_benchmark.sh http://localhost:8081 1 50`

```text
HTTP response counts: {'200': 7, '409': 13, '429': 30}
PASS: exactly one distinct hold owner (same-owner retries are idempotent).
PASS: winning booking confirmed; seat is BOOKED.
```

Seven successful hold responses belonged to one owner retrying the same reservation. Response counts depend on timing and bucket state.

## Other checks and limits

- JavaScript syntax, shell syntax, and Git whitespace checks passed.
- Maven produced the executable application JAR.
- Docker was unavailable locally. The GitHub Actions workflow provisions PostgreSQL/Redis, repeats `mvn verify`, validates Compose configuration, and builds the container; its remote result is tracked in GitHub Actions.
- Real SMTP delivery, payments, JWT, multi-instance messaging, and production deployment were not validated and are outside this MVP.
