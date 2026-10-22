# Validation

Validated on **23 October 2026** with Java 21, PostgreSQL 18.6, and local Redis. Tests use a separate `tickit_test` database and Redis database 15.

## Backend and access control

Command: `DB_USER=<local-postgres-user> mvn -B verify`

```text
Tests run: 41, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

| Suite | Tests | Coverage |
|---|---:|---|
| SeatHoldServiceTest | 3 | Acquisition, exclusion, same-owner retry, release |
| RateLimiterServiceTest | 3 | Capacity, rejection, refill |
| BookingFlowIntegrationTest | 7 | REST behavior and health without optional SMTP |
| BookingConcurrencyTest | 1 | 50 contenders; one booking and one database row |
| BookingSafetyTest | 5 | Rollback, notification outage, owner release, expiry, database uniqueness |
| AccountSecurityTest | 16 | Protected APIs, spoofed identities, account isolation, developer authorization, separate host routing, safe provisioning and no unpaid checkout |
| SessionTokenValidationTest | 6 | Actual RSA signatures, issuer, expiry, allowed origin and pending-session rejection |

Authorization tests inject authenticated principals locally to test account boundaries. Cryptographic tests sign real JWTs with a generated RSA key and verify/reject them through Nimbus. The supplied Clerk development credentials were authenticated against the Backend API and matched to the public signing keys. The real sign-in UI loads and offers Google plus email/password. Interactive account signup and developer authorization still require the account owner to complete sign-in.

## Website validation

Browser checks cover customer discovery, search/filtering, responsive seat selection, unauthenticated account entry states, and the separate developer portal shell. Public pages use locally served photography and WebSocket clients. Real identity SDKs require the configured Clerk service.

Earlier booking-flow checks exercised holds, release, expiration and confirmation before direct public confirmation was closed for the payment integration. Current deployed checkout is intentionally unavailable; there is no claim of successful real payment testing.

## CI and remaining external validation

CI runs Maven tests, syntax-checks all three frontend scripts and the authenticated probe, validates Compose, and builds the container.

Still required: live Google/email signup with the user's Clerk application, authorized developer login, Razorpay test/live payments, signed payment webhooks, durable ticket delivery, domain ownership/DNS, and hosted staging validation. Details are in [production setup](PRODUCTION_SETUP.md).
