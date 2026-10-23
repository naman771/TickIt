# Automatic full refunds

TickIt queues the full captured checkout amount when an organizer cancels or deletes an event, or when a verified captured payment cannot issue tickets. Cancellation preserves financial history. Rescheduling preserves confirmed tickets and does not automatically refund them; a capture against an expired pre-reschedule checkout is refunded because no ticket was issued.

The implementation remains test-mode only. Existing Razorpay test keys are used; no additional service, paid plan or dependency is required. No payment details are collected or stored by TickIt.

## Durable processing

V7 adds `refund_job`. The checkout UUID is the permanent idempotency key and receipt, and each payment has one refund job. Cancellation queues work inside the event transaction; rollback rolls it back. A bounded 30-second worker also derives missing jobs from durable order/event state, so a crash cannot lose an eligible refund. Provider requests run after the event transaction and outside database transactions.

Workers claim due jobs with a 90-second lease and PostgreSQL `SKIP LOCKED`. Every new refund re-fetches the payment and checks its ID, order, captured amount and currency. Requests use `speed: normal`, a fixed full amount and `X-Refund-Idempotency`. A lost response reuses exactly the same key/body. Known provider refund IDs are polled rather than posted again. Refund responses must match the original payment, amount, currency, receipt and known refund ID.

States:

| State | Meaning |
|---|---|
| QUEUED | Durable request awaiting processing |
| RETRY | Provider request was uncertain/unavailable; bounded exponential retry |
| PENDING | Provider accepted the refund; polling/webhooks track completion |
| PROCESSED | Provider reports the full refund processed |
| FAILED | Provider reported failure, or 12 transport failures exhausted retries |
| REVIEW | Identity/amount mismatch, unknown status or partial external refund needs follow-up |

A processed state cannot regress due to a stale worker or webhook. A provider-verified full external refund is recognized without another POST; a partial external refund is left for review to prevent over-refunding. Neither a timeout nor an unverified webhook claims success. A terminal failed refund is not resubmitted with a new key automatically. Operators handle provider rejection, insufficient balance or other exceptional cases using the retained references.

## Configuration and visibility

`PAYMENTS_ENABLED=true` enables the test gateway; the refund worker defaults on. `PAYMENT_REFUNDS_ENABLED=false` pauses automatic execution while retaining queued work, and is used for isolated provider tests. Do not disable it for normal connected operation.

The existing webhook endpoint additionally accepts `refund.created`, `refund.processed`, `refund.failed`. Enable these events on the same Razorpay Test Mode webhook, preserving its URL and secret. Signatures are verified over the raw body, then provider state is fetched. Polling also reconciles pending jobs, so adding webhook events is optional for correctness.

Customers see queued/processing/processed/attention states in My tickets and checkout history. Developer workspace → Payments shows recent refunds, including failures and sanitized errors. Refund initiation is automatic; credit to the customer's bank is asynchronous and depends on the payment method. Free-host inactivity may delay background work until the application wakes.

## Verification

Backend tests cover cancellation/deletion, unissued tickets, preserved receipts, rescheduling without refunds, unchanged retry keys, pending polling, signature rejection, provider re-fetching, response mismatch, rollback, competing workers, expired leases, failed refunds, retry exhaustion and external refund handling. Browser fixtures cover customer states and developer failure visibility. No existing valid customer booking is cancelled to test refunds.

Verification passed: 149 backend tests (147 passing, 2 optional provider tests skipped), 46 desktop/mobile browser checks, and six critical refund cases on a disposable Neon branch after applying V7. The branch was deleted and production CLI context restored.

These tests use a simulated gateway. After deployment, two automatically issued Razorpay Test Mode refunds were independently verified with read-only provider GET requests: both reported processed, and their payment IDs, full amounts, currencies and checkout receipts matched the stored jobs. These arose from cancelled/deleted events; no valid customer booking was cancelled by automated testing. Remaining retry/failure states are visible in the developer workspace. The disposable Neon check validates the migration and queue against managed PostgreSQL without touching the production catalog.

Sources: [Razorpay idempotent refund API](https://razorpay.com/docs/api/refunds/normal-refunds-idempotent/), [fetch refund](https://razorpay.com/docs/api/refunds/fetch-with-id/), [refund webhooks](https://razorpay.com/docs/webhooks/refunds/).
