# Ticket lifecycle release notes — 24 October 2026

This document records the ticket lifecycle implementation and remaining operational work. Publication is authorized. GitHub CI and Render deployment status determine which revision is live; local test results alone do not confirm a hosted rollout.

## Implemented in this checkpoint

1. **Refund exception handling:** distinguish permanent provider HTTP rejections from transient errors; preserve the original full amount/body/idempotency key. Permanent failures enter `REVIEW`. Developer tools inspect provider state without creating a refund, reconcile a matching refund, and explicitly retry a reviewed failure with a required reason and durable audit row. Processed refunds cannot regress.
2. **Admission tickets:** one independent 256-bit opaque credential per paid admission, including each guest in a standing order. Paid settlement commits tickets and notification intent with the booking. Unique item/ordinal keys make issuance idempotent; a bounded recovery task handles existing eligible paid orders.
3. **Customer ticket viewer:** separate admission cards, private authenticated PNG QR, download, and printable ticket/save-as-PDF. Credentials are absent from navigation URLs, order history, organizer guest lists and scan audit rows. Sign-out clears the private viewer.
4. **Entry workspace:** `organizer/entry.html` locally, `/entry.html` in the standalone organizer build. Supports camera frames, image upload and credential entry. PostgreSQL serializes scans: one admission, then `ALREADY_USED`. Current event/refund status is checked at scan time. Other-event credentials are invalid; unauthorized accounts cannot scan. No offline admission mode.
5. **Organizer operations:** event editor → **Bookings & entry**, owner-only guest lists, 100-row paginated CSV exports, captured/refunded/outstanding amounts, staff grants/revocation and recent scan audit. Staff must already have a verified TickIt account and can scan only explicitly assigned events; they cannot access financials or guest exports.
6. **Durable notifications:** booking, cancellation/reschedule and refund intents persist in PostgreSQL. Bounded leased worker, retries, sanitized failures, recovery sweep and developer queue visibility. Disabled delivery leaves jobs queued. SMTP and a Brevo HTTPS adapter are implemented; no actual emails were sent.
7. **Release preparation:** public read-only `scripts/verify-release.mjs`, developer readiness API, and CI syntax checks for new scripts.

Flyway additions: **V9** (admissions, staff, scans, outbox) and **V10** (refund operator audit). Flyway applies them during the backend deployment.

## Verification completed

```text
DB_USER=naman mvn -B verify
Tests run: 174, Failures: 0, Errors: 0, Skipped: 2
BUILD SUCCESS
```

That is **172 passing backend tests**; the optional external Neon and Razorpay tests are skipped in this ordinary run. The packaged executable JAR was built successfully.

The browser suite passed **68 desktop/mobile checks**. It uses explicit identity/API fixtures, not impersonated Clerk sessions. Tests cover ticket display/download/print, sign-out during loading, scanner results and network errors, reports/staff access controls, and notification visibility. Backend tests decode an actual generated PNG QR, submit it through the image scanner, and race real PostgreSQL check-in transactions.

`node scripts/build-organizer.mjs` succeeds. New dependency: ZXing Core 3.5.4. The actual mobile camera permission/hardware path, real email acceptance/delivery, container/live deployments require separate release verification.

## External refund issue

The existing ₹500 test payment was captured and had no provider refund in the last read-only inspection. Two other refunds were previously processed. The failed API request returns HTTP 400. The owner checked the Razorpay dashboard and reported:

> Your account does not have sufficient balance to instantly refund this payment.

The dashboard offers no speed selector. TickIt's existing request already uses `speed: normal`. Do **not** add real funds, reduce the customer's refund, generate a new key, or mark it processed. This remains a provider/account exception requiring resolution or support. The new code makes it visible and stops blind permanent-error retries; it does not claim the outstanding refund succeeded. The hosted worker uses these controls after the backend deployment.

## Email activation remains pending

Render Free blocks SMTP ports 25/465/587. Use `EMAIL_TRANSPORT=brevo` for the implemented HTTPS transport after an appropriate free account and sender are approved. Set `BREVO_API_KEY`, `EMAIL_FROM` and `APP_PUBLIC_URL` privately. Keep `EMAIL_ENABLED=false` until configuration and an explicitly authorized real send are validated. No paid upgrade, domain purchase, real funding or paid email service is authorized.

A provider acceptance response is not proof of inbox delivery. Delivery/bounce webhooks and notification dead-letter replay tooling are not implemented. SMTP/HTTP acknowledgement loss can cause duplicate delivery; the outbox prevents duplicate job creation, not mathematically exactly-once external email.

## Remaining operational work

1. Confirm the deployed revision and release checks; avoid starting a second implementation of these features.
2. Add remaining boundary acceptance tests: wrong-event QR for an owner of both events, oversized/corrupt image metadata, staff revoke/scan race, reschedule check-in window, and delayed operations navigation. Existing coverage is substantial, not exhaustive.
3. Inspect ticket/operations/entry layouts visually and validate the actual mobile camera flow with the owner.
4. Apply V9/V10 and a small critical regression selection to a **disposable Neon branch**; isolate inherited operational refund/notification queues in that copy only. Restore production CLI context and delete the branch afterward. Never clear production queues.
5. Run container/advisory checks for the new dependency, then publish/deploy the reviewed checkpoint when authorized. Verify current build assets, authentication boundaries, CORS, private tickets, organizer staff and public deep links on the actual origins. `node scripts/verify-release.mjs CUSTOMER_ORIGIN ORGANIZER_ORIGIN` only verifies public assets and anonymous rejections; it does not replace authenticated acceptance.
6. Configure/validate free HTTPS email delivery with the owner's approved sender, then verify one real notification before enabling unattended delivery. Check whether queued historical notifications need review first.
7. Resolve the provider refund-balance exception and verify actual full refund status. Never substitute a partial refund to make a test pass.
8. Continue launch preparation: production identity/OAuth, live merchant decision, backup/restore exercise, monitoring/alerts and bounded full-pipeline load/failure testing. Live keys remain blocked. No public-launch capacity certification is claimed.

Original scope still deferred: customer-requested partial cancellations/refunds, marketplace organizer settlements, reusable verified named venues/public media management, configurable entry windows, offline scanning, and horizontal broker scaling. The current entry window is six hours before start through the configured end (or eight hours after start if no end exists).

## Evidence locations

From the repository, local logs are in `../../work/`: `stage1-refunds-tests.log` (initial fixture environment failure), `stage2-payment-tests.log` (52 passed), `stage5-targeted.log` (71 passed), `stage6-full-backend.log` (174 total / 172 passing), `stage4-browser.log` and `stage6-final-browser.log`. Logs are local and gitignored; never commit `.env` or cloud credentials.

## Pre-publication cloud verification

On 24 October, V9 and V10 applied successfully to a disposable Neon branch. Nine critical ticket/check-in, staff authorization, outbox and refund recovery tests passed with zero failures/errors. The production CLI context was restored and the disposable branch removed. Runtime advisory scanning checked 178 coordinates with zero flagged packages at scan time. Physical-camera and real email delivery acceptance remain separate.
