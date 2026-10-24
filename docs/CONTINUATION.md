# Ticket lifecycle release notes — 24 October 2026

This document records the ticket lifecycle implementation and remaining operational work. Publication is authorized. GitHub CI and Render deployment status determine which revision is live; local test results alone do not confirm a hosted rollout.

## Current verified state — 24 October 2026

Verified before continuing: local `main`, GitHub `main`, successful [GitHub CI run 40073015952](https://github.com/naman771/TickIt/actions/runs/40073015952), and both live Render deployments agree on `07e2b28e38849a8b9255fc83b1258270be3482d5`. The working tree was clean. Deployed customer/organizer assets match that revision; all ten public health, identity, asset and anonymous-rejection checks pass. Organizer CORS passes. V9–V13 are applied, ticket credential lengths are valid, and the historical paid-order/booking snapshot remains preserved.

Private hosted configuration confirms Razorpay test keys, Brevo HTTPS delivery enabled and the recipient restriction removed. All eight historical held notification jobs remain held. Refund state remains one `REVIEW` and two `PROCESSED`; this verification does not retry or resolve the provider balance exception. Render backend and cache plans remain `free`; the organizer remains the existing static site. No provider mutation, plan change, live key or queue release was performed.

Continued with the [backup/restore exercise](BACKUP_RESTORE.md): a consistent, read-only hosted PostgreSQL 16 snapshot restored successfully into disposable local PostgreSQL 18. All **22 public tables / 321 rows** matched whole-row fingerprints. The archive SHA-256 was `35dd66e5c82ba54a0292e66d88585e0c26be4b245fe10aa6819973cb56d0b672`. The temporary database and archive were removed; no application or outbound worker ran against the copy. This proves one logical restore, not retained backups, provider-state recovery or a launch-capacity guarantee.

The reusable tool rejects pooled Neon URLs, insecure source connections and existing database targets. Safety tests cover connection redirection, a real local round trip, restore failure cleanup and corrupted-row detection. Hosted verification logs are in `target/customer-ticket-release-verification.json` and `target/backup-restore-drill.log` (gitignored). Runtime application code is unchanged by this operational checkpoint. Later Git commits containing the tool/docs require their own CI/deployment check; the exact live revision above records the initial verification.

Next independent work: monitoring/alerts and bounded pipeline load/failure testing; define retained encrypted backup storage under the zero-budget constraint. Physical-device admission acceptance still needs the owner. Production identity, delivery/bounce tracking and the provider refund exception remain open. Keep all resources free and Razorpay test-only. Earlier sections below are historical checkpoints, including their then-current pending work.

## Latest customer dashboard and email checkpoint

Cancelled/deleted event tickets can now be removed from the current customer's My tickets view with confirmation. V13 stores a per-account event visibility preference; bookings, admissions, refund jobs and private order receipts remain intact. Event summaries now include their actual image URL; unavailable artwork uses a neutral placeholder.

Verification: **185 backend tests passed**, three opt-in external-provider tests skipped (188 total, zero failures/errors); **86 desktop/mobile browser checks passed**. Four removal/artwork acceptance cases passed on a disposable Neon branch with V13; production CLI context was restored and the branch deleted. The packaged build passed.

Brevo API acceptance and a real hosted booking confirmation were both received in the owner's inbox. The owner explicitly approved transactional emails to all customer addresses after testing. The recipient restriction is removed for this release; eight historical jobs remain held for individual review. Free-plan quota, sender-address rewriting and free-host sleep remain practical limits. Provider delivery/bounce tracking is still pending. Earlier checkpoints below describe their own rollout state; verify this release's live revision through CI and hosting checks.

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
DB_USER=naman TZ=UTC mvn -B -DargLine=-Duser.timezone=UTC verify
Tests run: 185, Failures: 0, Errors: 0, Skipped: 3
BUILD SUCCESS
```

That is **182 passing backend tests**; the optional external Neon, Razorpay and Brevo tests are skipped in this ordinary run. The packaged executable JAR was built successfully.

The browser suite passed **74 desktop/mobile checks**. It uses explicit identity/API fixtures, not impersonated Clerk sessions. Tests cover ticket display/download/print, sign-out during loading, scanner results and network errors, reports/staff access controls, and notification visibility. Backend tests decode an actual generated PNG QR, submit it through the image scanner, and race real PostgreSQL check-in transactions.

`node scripts/build-organizer.mjs` succeeds. New dependency: ZXing Core 3.5.4. The actual mobile camera hardware path and real email acceptance/delivery require separate acceptance. GitHub CI and both Render services verified the previous ticket release; this checkpoint requires its own release checks.

## External refund issue

The existing ₹500 test payment was captured and had no provider refund in the last read-only inspection. Two other refunds were previously processed. The failed API request returns HTTP 400. The owner checked the Razorpay dashboard and reported:

> Your account does not have sufficient balance to instantly refund this payment.

The dashboard offers no speed selector. TickIt's existing request already uses `speed: normal`. Do **not** add real funds, reduce the customer's refund, generate a new key, or mark it processed. This remains a provider/account exception requiring resolution or support. The new code makes it visible and stops blind permanent-error retries; it does not claim the outstanding refund succeeded. The hosted worker uses these controls after the backend deployment.

## Email activation remains pending

Render Free blocks SMTP ports 25/465/587. Use `EMAIL_TRANSPORT=brevo` for the implemented HTTPS transport after an appropriate free account and sender are approved. Set `BREVO_API_KEY`, `EMAIL_FROM` and `APP_PUBLIC_URL` privately. Keep `EMAIL_ENABLED=false` until configuration and an explicitly authorized real send are validated. No paid upgrade, domain purchase, real funding or paid email service is authorized.

A provider acceptance response is not proof of inbox delivery. Delivery/bounce webhooks are not implemented. Audited failed-message retry and held-message release are implemented. SMTP/HTTP acknowledgement loss can cause duplicate delivery; the outbox prevents duplicate job creation, not mathematically exactly-once external email.

## Historical operational checklist (see current verified state above)

1. Confirm the deployed revision and release checks; avoid starting a second implementation of these features.
2. Boundary acceptance tests now cover wrong-event QR for an owner of both events, oversized/corrupt images, the staff revoke/scan race, rescheduled entry windows, delayed operations navigation and camera permission completing after sign-out. Full physical-device acceptance remains separate.
3. Inspect ticket/operations/entry layouts visually and validate the actual mobile camera flow with the owner.
4. Apply V9/V10 and a small critical regression selection to a **disposable Neon branch**; isolate inherited operational refund/notification queues in that copy only. Restore production CLI context and delete the branch afterward. Never clear production queues.
5. Run container/advisory checks for the new dependency, then publish/deploy the reviewed checkpoint when authorized. Verify current build assets, authentication boundaries, CORS, private tickets, organizer staff and public deep links on the actual origins. `node scripts/verify-release.mjs CUSTOMER_ORIGIN ORGANIZER_ORIGIN` only verifies public assets and anonymous rejections; it does not replace authenticated acceptance.
6. Configure/validate free HTTPS email delivery with the owner's approved sender, then verify one real notification before enabling unattended delivery. Check whether queued historical notifications need review first.
7. Resolve the provider refund-balance exception and verify actual full refund status. Never substitute a partial refund to make a test pass.
8. Continue launch preparation: production identity/OAuth, live merchant decision, backup/restore exercise, monitoring/alerts and bounded full-pipeline load/failure testing. Live keys remain blocked. No public-launch capacity certification is claimed.

Original scope still deferred: customer-requested partial cancellations/refunds, marketplace organizer settlements, reusable verified named venues/public media management, configurable entry windows, offline scanning, and horizontal broker scaling. The current entry window is six hours before start through the configured end (or eight hours after start if no end exists).

## Evidence locations

From the repository, local logs are in `../../work/`: `stage1-refunds-tests.log` (initial fixture environment failure), `stage2-payment-tests.log` (52 passed), `stage5-targeted.log` (71 passed), `email-entry-full-tests.log` (185 total / 182 passing), `email-entry-final-browser.log` (74 passing browser checks). Logs are local and gitignored; never commit `.env` or cloud credentials.

## Pre-publication cloud verification

On 24 October, V9 and V10 applied successfully to a disposable Neon branch. Nine critical ticket/check-in, staff authorization, outbox and refund recovery tests passed with zero failures/errors. The production CLI context was restored and the disposable branch removed. Runtime advisory scanning checked 178 coordinates with zero flagged packages at scan time. Physical-camera and real email delivery acceptance remain separate.

## Entry and email activation checkpoint

The previous ticket release (`dd4835b`) passed GitHub CI, its Docker build and both Render deployments. Additional entry boundaries and audited notification review are implemented locally with 182 passing backend tests and 74 passing browser checks. V11/V12 add message references, operator audit and a held historical backlog; stale business notifications are suppressed before sending. Recipient scoping is enforced at both the worker and transport. A late staff-access response no longer replaces a newer organizer page.

The owner confirmed a downloaded QR is readable by a phone camera. That does not establish an authorized TickIt admission or duplicate-scan rejection on physical hardware. No live QR was consumed by automated acceptance.

The owner is setting up Brevo Free. Real provider acceptance and inbox confirmation are pending; hosted delivery remains disabled. See [notification activation](NOTIFICATIONS.md). The new checkpoint's cloud validation and hosted rollout must be recorded after completion, separately from the previous release.
