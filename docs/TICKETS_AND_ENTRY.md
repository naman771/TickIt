# Admission tickets and event entry

See [release notes](CONTINUATION.md) for verification and remaining operational work.

A verified captured checkout creates one admission per immutable item/ordinal. A standing order of three tickets therefore produces three independent credentials, not a shared three-person QR. Credentials use 32 cryptographically random bytes. The account owner retrieves the ticket and its QR through authenticated, non-cacheable endpoints. Downloads can be retained and printed; the server always revalidates admission at entry.

The event editor's **Bookings & entry** view contains the guest list, financial/refund totals, staff access and recent entry outcomes. The separate entry page supports camera frames, uploaded PNG/JPEG QR images and manual credentials. Staff accounts require explicit event grants; financial reports remain owner-only. Revocation and scans serialize on the event row, as do cancellation and rescheduling.

Entry is online and single-use. It opens six hours before the current start and closes at the current end, or eight hours after the start if no end was configured. Rescheduling retains ticket credentials and changes the validated window. Cancelled/deleted events and any refund job invalidate admission. Already-used tickets cannot be presented as unused; audit records retain the actor and outcome without the credential. QR image decoding is limited to one raster allocation at a time, 2 MB uploads and 2048 × 2048 pixels.

| Endpoint | Access |
|---|---|
| `GET /api/tickets/{ticketId}` | Ticket purchaser |
| `GET /api/tickets/{ticketId}/qr` | Purchaser; valid unused tickets only |
| `GET /api/entry/events` | Signed-in user's owned/assigned events |
| `POST /api/organizer/events/{id}/check-in` | Owner or assigned staff; credential in JSON body |
| `POST /api/organizer/events/{id}/check-in/image` | Owner or assigned staff; multipart `file` |
| `GET /api/organizer/events/{id}/operations?page=0` | Owner only |
| `GET /api/organizer/events/{id}/guests.csv?page=0` | Owner only; 100 admissions/page |
| `POST /api/organizer/events/{id}/staff` | Owner; existing verified email |
| `DELETE /api/organizer/events/{id}/staff/{userId}` | Owner |

Outcomes: `ADMITTED`, `ALREADY_USED`, `INVALID`, `INACTIVE`, `TOO_EARLY`, `ENDED`. A network error is never an entry confirmation. There is no offline admission or manual bypass.

## Removing cancelled event tickets

My tickets uses the event's own poster, including after removal from discovery. Missing or unavailable artwork uses a neutral placeholder.

For cancelled or deleted events, **Remove event tickets** asks for confirmation and hides all of that event's admissions and checkout cards from the current account. The preference persists across devices. Other purchasers' dashboards are unaffected. Active-event tickets cannot be removed this way.

`DELETE /api/bookings/events/{eventId}/dashboard` requires the signed-in purchaser and returns `{"removed":true}`. Repeated removal is idempotent. It records a customer visibility preference; booking rows, admission credentials, paid-order receipts, scan history, notification jobs and refund processing are preserved. An owned order remains readable through its private order URL for support and refund checks.
