# Project walkthrough

The public website presents the customer product. Engineering diagnostics live in a separate restricted developer workspace.

## Customer walkthrough

1. Open the customer website and browse the event catalog. Search by artist/city or filter by category.
2. Open an event to view live seat availability and ticket prices.
3. Sign up or sign in through Clerk. Enable Google in your Clerk application to offer Google sign-in alongside email/password.
4. View My tickets while signed in. A customer can access only their own bookings.

Without provider configuration, browsing remains available and sign-in reports temporary unavailability. Payment checkout remains disabled until the paid-order flow is implemented. There is no simulated account picker or fake successful purchase.

## Developer walkthrough

1. Open `/developer/`, or the configured developer domain after DNS is connected.
2. Sign in with a Clerk account whose user ID is in `DEVELOPER_SUBJECTS`.
3. Inspect real infrastructure status, catalog counts, and identity/payment/email integration status.
4. A normal customer session is rejected by developer APIs.

## Automated proof

Use an isolated test database and Redis database 15:

```bash
DB_USER="$(whoami)" mvn verify
DB_USER="$(whoami)" mvn test -Dtest=BookingConcurrencyTest
```

The service-level concurrency test competes 50 users for one seat and checks exactly one booking and one database row. The test-only direct booking path is not available in a deployed application.

For a controlled hold/rate-limit probe with a real authenticated account:

```bash
# Set TICKIT_ACCESS_TOKEN in your environment first.
./scripts/rate_limit_probe.sh http://localhost:8080 1 25
```

This briefly reserves one free seat and releases it. It does not attempt payment or produce tickets.

See [production setup](PRODUCTION_SETUP.md) for provider accounts and the remaining payment work.
