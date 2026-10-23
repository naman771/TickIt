# API reference

Base URL: `http://localhost:8080`. Public event browsing does not require an account. Protected operations require a Clerk session token in `Authorization: Bearer <token>`.

## Public catalog

| Method | Endpoint | Purpose |
|---|---|---|
| GET | `/api/events` | Event list |
| GET | `/api/events/{id}` | Event details |
| GET | `/api/events/{id}/seats` | Seat map and ticket prices |
| GET | `/api/public/config` | Public identity configuration and checkout availability |
| GET | `/actuator/health` | Application health |

Catalog prices use each event's configured currency. Razorpay test checkout currently supports INR only; currency conversion is never inferred from customer location.

## Accounts and reservations

| Method | Endpoint | Purpose |
|---|---|---|
| GET | `/api/me` | Verified account profile; creates the local identity mapping if needed |
| GET | `/api/bookings/me` | The signed-in account's booking history |
| POST | `/api/holds` | Acquire a short-lived seat hold |
| DELETE | `/api/holds/{eventId}/{seatId}` | Release the signed-in account's hold |
| POST | `/api/bookings` | Direct confirmation is disabled outside automated tests; use verified payment checkout |

A hold request contains `{"eventId":1,"seatId":3}`. The server resolves the user from the signed session. A supplied `userId` is accepted only when it matches that account; it never overrides the session identity. The older history route `/api/bookings/user/{id}` is retained with the same owner check.

```bash
curl -s http://localhost:8080/api/events
curl -s http://localhost:8080/api/me \
  -H "Authorization: Bearer $TICKIT_ACCESS_TOKEN"
curl -i -X POST http://localhost:8080/api/holds \
  -H "Authorization: Bearer $TICKIT_ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"eventId":1,"seatId":3}'
```

Same-owner hold retries do not extend the original expiry. IP limits and authenticated-subject limits are enforced in Redis; `X-User-Id` is not a trusted identity.

## Developer workspace

`GET /api/developer/overview` requires a verified session whose subject is explicitly listed in `DEVELOPER_SUBJECTS`. A normal customer account receives `403`.

It returns infrastructure availability, integration flags, aggregate catalog/account/booking counts, and the check timestamp. It does not return secrets or customer records.

## HTTP status codes

| Status | Meaning |
|---|---|
| 200 | Successful read or hold operation |
| 400 | Invalid request |
| 401 | Sign-in required or invalid token |
| 403 | Another account's resource, unverified email, or missing developer access |
| 404 | Resource not found |
| 409 | Reservation/account conflict |
| 410 | Missing or expired hold in the booking service |
| 429 | Rate limit exceeded; retry after the supplied delay |
| 503 | Checkout or account provider unavailable |

## Live updates

The public read-only seat map uses SockJS `/ws` and STOMP `/topic/seats/{eventId}`. Clients reconcile with the seat API after reconnecting and every five seconds. Messages are advisory; PostgreSQL remains authoritative for confirmed bookings.

## Developer assets

All routes below require a verified session whose subject is in `DEVELOPER_SUBJECTS`. Anonymous requests return 401; customer accounts return 403. Disabled or unavailable storage returns 503.

| Method | Route | Behavior |
|---|---|---|
| GET | `/api/developer/assets` | List up to 100 workspace objects as `{id, size}`. |
| POST | `/api/developer/assets` | Multipart field `file`, 1 byte–5 MB; returns 201 with `{id, size}`. |
| GET | `/api/developer/assets/{id}` | Download a private object as an attachment; unknown object returns 404. |
| DELETE | `/api/developer/assets/{id}` | Delete a workspace object; returns 204. |

## Organizer catalog

All `/api/organizer/**` routes require authentication. Every event lookup/update/publish checks the current organizer owner; unrelated IDs return 404. Organizer enrollment does not grant developer access.

| Method | Route | Behavior |
|---|---|---|
| GET / POST | `/api/organizer/profile` | Read/create the authenticated user's organizer workspace. |
| GET / POST | `/api/organizer/events` | List owned events / create a private draft. |
| GET / PUT | `/api/organizer/events/{id}` | Read an owned event / save a draft or amend a published event with its current revision. |
| POST | `/api/organizer/events/{id}/publish` | Publish the saved draft using `{version}` after validation. |
| GET | `/api/events/{id}/admission` | Published-event zones, shared capacity and ticket prices/availability. |
| POST | `/api/events/{id}/admission-holds` | Authenticated quantity hold: `{ticketTypeId,quantity}`; whole quantity or 409. |
| DELETE | `/api/events/admission-holds/{id}` | Release a quantity hold owned by the authenticated account. |

`GET /api/events` returns published events only. Event metadata includes category, currency, timezone/start instant, admission mode, capacity, ticket availability and stage coordinates. `totalSeats` remains the count of assigned seats; general-admission capacity is separate. Seat responses include section, row/number, x/y position and currency. New organizer events are priced in INR. See [organizer guide](ORGANIZERS.md) for inventory limits and publication behavior.

## Eligibility and diagnostics

Organizer create/update payloads accept nullable `minimumAge` (1–120; null means all ages). `perOrderLimit` accepts 1–3. Public event metadata reports both. Seat and standing hold requests for restricted events require `attendeeAge` (0–120); it must meet the event minimum for every guest. Assigned-seat selection is limited to three, and reservation APIs combine the account's seat and standing holds when enforcing the effective limit.

Developer-only `POST /api/developer/diagnostics` accepts `{ "attempts": 16 }` (8–32), starts an asynchronous component run, and returns 202. `GET /api/developer/diagnostics` returns the latest run and measured outcomes. A second start within 60 seconds or while running returns 429. Tests exercise real Redis Lua and PostgreSQL operations on dedicated diagnostic keys/rows, with four workers, timeouts, cleanup, and no catalog/booking writes. Status includes accepted/rejected/error counts, p95 operation latency, duration and cleanup outcome. This is not a full HTTP/payment/WebSocket load test.

## Razorpay test checkout

All order operations require the signed account; the webhook uses its own raw-body HMAC authentication. Payments are off by default and live keys are rejected.

| Method | Endpoint | Purpose |
|---|---|---|
| POST | `/api/payments/orders` | Reserve one to three tickets and create a server-priced Razorpay test order |
| GET | `/api/payments/orders/me` | Latest 100 account-owned checkout orders, including standing lines and pending/review states |
| GET | `/api/payments/orders/{id}` | Read an account-owned checkout |
| POST | `/api/payments/orders/{id}/verify` | Validate Checkout HMAC and fetch authoritative captured payment details |
| POST | `/api/payments/orders/{id}/reconcile` | Recover status from provider order payments; never initiates another charge |
| DELETE | `/api/payments/orders/{id}` | Expire an unfinished order and release its reservations; cannot undo a confirmed booking |
| POST | `/api/payments/webhook` | Accept signed `payment.captured` and `order.paid`; repeats do not duplicate inventory |
| GET | `/api/developer/payments/review` | Developer-only queue of uncertain creates and payments requiring review |

```json
{
  "eventId": 42,
  "eventVersion": 0,
  "idempotencyKey": "11111111-1111-4111-8111-111111111111",
  "seatIds": [501, 502],
  "ticketTypeId": null,
  "quantity": 0,
  "attendeeAge": 25
}
```

For standing admission, use `seatIds: []`, a ticket type ID and a positive quantity. Mixed requests may include both, with three tickets total or the event's lower limit. Do not send a client-calculated price. Amounts returned by payment APIs and `unitAmount` are integer paise. Reuse the UUID key only for the exact same selection; changed requests return 409. Same-account/event concurrent order creation returns 409 while an earlier checkout is active. Existing account limits count confirmed standing and seated admission together.

Verification payload is the standard `razorpay_order_id`, `razorpay_payment_id`, `razorpay_signature` object. The server uses its stored order ID, not a client-provided substitute. Captured amounts/currency/order IDs must match. `authorized` is not confirmation. Webhooks require `X-Razorpay-Signature`, calculated over the unmodified body with a separate webhook secret. See [payment states and limitations](PAYMENTS.md).

## Published event management

Authenticated event owners use `PUT /api/organizer/events/{id}` with the full current layout and `version`. Include each existing seat's `id` to preserve identity. Price and position changes apply to future purchases; booked seats cannot be removed or relabelled. Active reservations block layout edits. Customer booking limits remain unchanged.

To reschedule, update `startTime`/`endTime`/`timezone` and supply `changeReason`. To cancel: `POST /api/organizer/events/{id}/cancel` with `{ "version": 1, "reason": "Venue unavailable" }`. To archive: `DELETE /api/organizer/events/{id}` with the same body shape. All require ownership and the current revision; stale requests return 409. Archival preserves payment and booking history.

Public event responses now include `version`, `status`, `changeNote` and `scheduleChanged`. Payment creation requires `eventVersion` alongside the selected event ID. Account booking/payment responses include an `event` summary even when the event is cancelled or deleted from the catalog.
