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

Current catalog prices are in USD. No currency conversion is inferred from the customer's location.

## Accounts and reservations

| Method | Endpoint | Purpose |
|---|---|---|
| GET | `/api/me` | Verified account profile; creates the local identity mapping if needed |
| GET | `/api/bookings/me` | The signed-in account's booking history |
| POST | `/api/holds` | Acquire a short-lived seat hold |
| DELETE | `/api/holds/{eventId}/{seatId}` | Release the signed-in account's hold |
| POST | `/api/bookings` | Unavailable outside automated tests until paid checkout is implemented |

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
| GET / PUT | `/api/organizer/events/{id}` | Read an owned event / replace an owned draft with its current revision. |
| POST | `/api/organizer/events/{id}/publish` | Publish the saved draft using `{version}` after validation. |
| GET | `/api/events/{id}/admission` | Published-event zones, shared capacity and ticket prices/availability. |
| POST | `/api/events/{id}/admission-holds` | Authenticated quantity hold: `{ticketTypeId,quantity}`; whole quantity or 409. |
| DELETE | `/api/events/admission-holds/{id}` | Release a quantity hold owned by the authenticated account. |

`GET /api/events` returns published events only. Event metadata includes category, currency, timezone/start instant, admission mode, capacity, ticket availability and stage coordinates. `totalSeats` remains the count of assigned seats; general-admission capacity is separate. Seat responses include section, row/number, x/y position and currency. New organizer events are priced in INR. See [organizer guide](ORGANIZERS.md) for inventory limits and publication behavior.
