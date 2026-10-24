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
