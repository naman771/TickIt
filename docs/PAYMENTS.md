# Razorpay test checkout

Status: Razorpay test credentials verified on 23 October 2026. A real unpaid test order was created and fetched through the application on a disposable Neon branch; amount and receipt matched, no payment was captured, and the hold was released. Provider-hosted Checkout completion and delivery from Razorpay's webhook infrastructure still require Dashboard setup and a user-completed test payment.

## Account and cost

Razorpay test transactions use no real money. The gateway advertises zero setup and annual maintenance charges; live payment processing has transaction fees and applicable taxes. TickIt's payment integration accepts only `rzp_test_` keys, regardless of environment. It rejects enabled configuration using live keys.

Official references: [pricing](https://razorpay.com/pricing/), [account setup](https://razorpay.com/docs/payments/quickstart), [web integration](https://razorpay.com/docs/payments/payment-gateway/web-integration/standard/integration-steps/), [webhook signatures](https://razorpay.com/docs/webhooks/validate-test/).

If onboarding requires PAN or account verification, follow the provider's genuine onboarding or leave activation pending. Never fabricate identity or business details. An account is not required to run this repository's simulated payment tests.

## Connect a test account later

1. Sign up on the official Razorpay Dashboard and choose Test Mode. Dashboard onboarding requirements may vary by account; live activation is separate.
2. Generate Test Mode API keys under Account & Settings → API Keys. Store credentials locally or in hosting secrets, never in Git or browser code.
3. Set automatic capture in the Razorpay test account. TickIt will not confirm a merely authorized payment.
4. Configure a Test Mode webhook at `https://<backend-host>/api/payments/webhook`, subscribed to `payment.captured` and `order.paid`. It must reach the backend rather than the organizer static site.
5. Choose a separate, random webhook secret of at least 24 characters. Save exactly the same secret in the Dashboard and backend configuration. Webhook URL must be publicly reachable; localhost alone cannot receive provider delivery.
6. Configure the following, restart the backend, and exercise the provider's official test payment methods. No real card or real payment is necessary.

```dotenv
PAYMENTS_ENABLED=true
RAZORPAY_KEY_ID=rzp_test_<your-key-id>
RAZORPAY_KEY_SECRET=<your-test-key-secret>
RAZORPAY_WEBHOOK_SECRET=<separate-random-webhook-secret>
```

Leave `PAYMENTS_ENABLED=false` while the account is unavailable. Signup, catalog browsing, organizer workflows and developer diagnostics continue without payment credentials. Never publish the secret key or webhook secret. Only the provider's public key ID is returned to the owner of a checkout.

## Booking lifecycle

The server resolves the account from the verified session, locks the event and validates its publication state, sales window, minimum-age declaration and total per-account ticket allowance. It prices immutable checkout lines from database inventory in integer paise. Checkout supports assigned seats, standing admission or a combination, with a maximum of three tickets and an event's lower limit. Current checkout currency is INR; accepted totals are ₹1–₹10,00,000.

Redis holds are bound to a unique checkout generation. A delayed callback cannot release or consume a newer reservation belonging to the same account. Standing holds and shared pool quotas remain in PostgreSQL. Holds last up to 120 seconds; reusing an existing hold does not extend its life. Near-expiry holds are rejected before creating a payment order.

The database reservation transaction commits before the provider order is created. An account-scoped UUID idempotency key returns the same local order for identical retries. It rejects changed selections. An uncertain provider create is marked `CREATE_UNKNOWN` and is never automatically repeated. A valid provider webhook can recover the original order by its server-generated receipt UUID. A user can retain the reference, check status, or explicitly release the reservation; do not start duplicate payment attempts when payment status is uncertain.

A verified Checkout callback triggers an authoritative payment fetch. Signed raw-body webhooks fetch both payment and provider order. Capture status, amount, currency, receipt and order identity are checked server-side. Only a captured payment with still-valid inventory confirms the order. All seated booking rows, standing pool/type sold counters, standing hold conversion and the order's confirmation commit in one database transaction. WebSocket notifications and Redis cleanup happen after commit and cannot turn a committed booking into a failure.

Concurrent and repeated callback/webhook deliveries serialize on the event. They never insert duplicate bookings or increment standing sold counters twice. Unsupported webhook events are acknowledged without changing an order; failed attempts do not downgrade a confirmed payment. Signature failures return 401 and malformed payloads return 400. Infrastructure failures remain retryable rather than being acknowledged as confirmed.

| State | Meaning |
|---|---|
| `CREATING` | Local reservation exists; provider create is in progress |
| `CREATE_UNKNOWN` | Provider creation was uncertain; never automatically create another order for that key |
| `PENDING` | A provider order is available; payment is not yet confirmed |
| `EXPIRED` | Hold deadline passed or the customer explicitly released it; not a cancellation of a confirmed booking |
| `CONFIRMED` | Captured payment and all inventory committed together |
| `REVIEW_REQUIRED` | Capture was verified but inventory expired, was released or became unavailable; no partial ticket confirmation |

Late captures are retained with their provider payment reference and exposed in a developer-only review queue. Automatic full refunds are queued for verified captures that could not issue tickets, and for paid cancelled/deleted events. See [refund behavior and retries](REFUNDS.md). Live acceptance still requires operational resolution, broader failure testing and the production account rollout. Payment review is a distinct state; it must never be presented as a booked ticket.

## Recovery and customer behavior

Checkout uses Razorpay's hosted payment window, loaded only when a configured account starts checkout. No card details are processed or stored by TickIt. Closing the window leaves the temporary reservation pending, and does not claim success. Failed verification offers status reconciliation rather than instructing a second payment. Session storage preserves the current order or idempotency intent for a signed-in account; reload fetches its authoritative status. Account changes clear in-memory order state.

A bounded background job checks up to 10 pending/expired provider orders per run, with a 60-second delay between runs, rotating by last-check time. It scans orders created in the last day. Webhooks and owner-initiated reconciliation remain available for older known orders. Missing provider IDs from uncertain creation rely on a later signed webhook or operational investigation. The scheduler pool has three threads so provider calls do not block hold-expiry notifications. The job neither creates charges nor performs refunds. An operator should monitor the review queue; it is not a finished payment-support dashboard.

The account checkout-history endpoint retains pending/review/confirmed order information, including standing lines. My tickets displays confirmed orders alongside historical seated bookings, without duplicating the same assigned seat. Pending/review orders can also be reopened from My tickets, without creating another payment order. QR issuance and entry validation remain separate work.

## Verification

Run with isolated PostgreSQL `tickit_test` and Redis test databases:

```bash
DB_USER="$(whoami)" mvn -B verify
npm run test:browser
npm run build
node scripts/build-organizer.mjs
```

The complete local backend run: **152 tests, 0 failures, 0 errors, 2 optional provider tests skipped** (150 passing). Payment tests cover test-only configuration, server pricing, account isolation, age/quantity/currency validation, all-or-nothing holds, idempotent creates, raw-body signatures, captured versus authorized status, amount/order mismatch, duplicate and concurrent webhooks, mixed inventory, transaction rollback, uncertain-create recovery, expiry and stale same-account callbacks. The HTTP adapter is tested with a simulated client, including fixed provider origin, Basic authentication, timeouts and sanitized failures.

Desktop/mobile Playwright run: **54 checks passed**, including successful verification, modal dismissal/release and recovery after a failed verification response. Payment screens and network responses are explicit fixtures. A user-completed hosted test checkout was subsequently verified read-only: Razorpay reported captured and TickIt retained one matching confirmed assigned-seat booking. Automated UI checks do not operate the provider-hosted payment modal.

Six critical payment cases also passed against a disposable Neon branch with a three-connection pool. Flyway applied V5 on that branch; the checks exercised three-seat capture, mixed admission, concurrent webhook replay, transactional rollback, uncertain-create recovery and stale same-account callbacks. The temporary branch was deleted and CLI context restored to production. The production database was not migrated during these checks.

The packaged application also started successfully against local PostgreSQL/Redis under hosted JVM memory limits; health was UP and checkout remained disabled with no provider keys. Local Docker verification remains unavailable; GitHub Actions validates container builds after publication. Real Razorpay order creation/fetch passed in the separate provider check. This implementation uses one merchant account; organizer payouts/settlements are future work.

## Real provider verification

`RazorpayServicesE2ETest` is opt-in (`RAZORPAY_E2E=true`) and requires an explicitly supplied disposable database hostname. It creates one application checkout using a synthetic test principal, calls the real Razorpay Test Mode Orders API, fetches the resulting order and payments, verifies the exact receipt UUID / INR 100 paise / uncaptured state, then expires the local order and removes its database fixtures. It does not submit payment details or impersonate a customer account. The provider may retain the unpaid test order in its sandbox Dashboard.

```text
RAZORPAY_E2E: real provider order create/fetch PASS; INR 100 paise and receipt match PASS; no captured payment PASS; local hold release PASS
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
```

The disposable database branch was removed and production CLI context restored. No production catalog or booking fixture was created. The separate user-completed hosted payment confirmed the provider capture and matching application booking. Webhook delivery diagnostics are distinct from that payment-state verification.

## Organizer changes during checkout

Create requests include the current public event `version` as `eventVersion`. Stale selections receive 409 and must be refreshed. Checkout lines store that revision and retain their original prices. Rescheduling, cancellation and deletion expire pending checkouts under the same event lock used for settlement. Captures arriving after such changes enter `REVIEW_REQUIRED` rather than issuing tickets against obsolete inventory. Confirmed receipts remain confirmed and include the latest event status/schedule in account history.

Seat removal retires an unsold position while preserving older checkout references. Booked seats cannot be deleted or relabelled. Cancellation/deletion retains financial records and exposes confirmed payments in the developer review queue with `refundRequired: true`. Eligible orders now queue automatic full refunds. Failed or mismatched provider responses remain visible for operational follow-up; processed refunds are excluded from the unresolved review list.
