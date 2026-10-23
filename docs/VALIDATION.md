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

## Neon integration validation

The expanded local suite now has 48 passing tests, including provider URL conversion, production/test database isolation, developer asset authorization and upload limits. One additional live-cloud test is skipped in ordinary CI and passes separately when explicitly enabled. It verifies real Neon database reads plus private storage upload, list, byte-identical download, anonymous denial, delete and subsequent 404 through application controllers. Browser checks cover the developer file controls and mobile layout with explicit UI fixtures; they do not claim a real browser OAuth session was exercised.

## Organizer catalog validation

The local suite now passes 61 tests; one optional live-storage test is skipped by default. Thirteen organizer integration tests validate owner-only access, private drafts, custom map persistence, publication locks, revisions, admission-mode/capacity validation, sales windows, exact-origin CORS, and concurrent reservations across ticket tiers. The V3 migration and all thirteen organizer tests also passed on an isolated Neon branch, which was then deleted. Browser fixtures verified organizer creation/editing, CSV import/export, preview and publishing controls, custom customer layouts, INR pricing, standing quantities and mobile layout. No test events were published into the production catalog.

## Multi-selection, eligibility and diagnostics

The regression suite now has 66 passing tests and one optional cloud-storage test skipped by default. Added coverage includes parallel attempts for different seats capped at three per account, owner-only release freeing allowance, optional age persistence and enforcement for seated/standing admission, mixed-mode allowance accounting, invalid age rejection, developer-only diagnostic APIs, bounded request validation, run cooldown, measured real Redis/PostgreSQL contention and diagnostic cleanup without catalog/booking changes.

Chrome fixture checks cover organizer three-seat selection and bulk pricing, customer three-seat selection/totals/deselection, minimum-age controls and declarations, standing quantities capped at three, and developer test controls/results. All three pages were checked for mobile overflow and JavaScript errors.

The V4 migration plus all 17 organizer/diagnostic integration tests also passed on a disposable Neon branch of production. Production context was restored and the branch deleted after verification.

## Bug-audit regression expansion

84 local backend tests now pass, with the optional real-Neon storage test passing separately. Twenty-two committed desktop/mobile Playwright checks run in CI. Added coverage includes real socket delivery and forged-message denial, identity provisioning races, mixed-mode concurrent limits, natural expiry, database version conflicts, broker outages, malformed requests, layout validation, availability/pricing, stale routes/saves and sign-out responses. The runtime is upgraded to Spring Boot 3.5.16 with compatible library patches. Two advisories for unused MVC features remain explicitly tracked in [the audit report](BUG_AUDIT.md).
