# Organizer event management

Organizers can select any number of seats within an event's 1,500-seat layout, bulk price them, move the selected group or remove unsold positions. The customer limit remains three tickets per account/event, or the event's lower limit.

Published updates preserve seat IDs. Booked seats cannot be deleted or relabelled. Unsold removals retire database rows so historic checkout references remain intact. Confirmed checkout prices remain unchanged. Active reservations block layout changes; published standing pools and admission format remain fixed.

Owners can move an event earlier or later with a reason and a future start time. Cancellation removes it from public sale; deletion archives it from both the public catalog and organizer list. All operations use owner authorization, the current event revision and an event row lock. Changes are audited. Confirmed ticket history retains event details and shows cancellation or rescheduling even after catalog removal.

Pending checkouts expire on rescheduling, cancellation or deletion. Captures against obsolete revisions enter payment review rather than assigning inventory. Cancelled confirmed orders appear in the developer review API for refund follow-up. Full refunds are now automatic for cancelled/deleted events and verified captures without tickets; see [refund behavior](REFUNDS.md). Customer email notifications remain pending.

## Verification

- Full local backend verification: 134 tests, 0 failures, 0 errors, 2 opt-in provider tests skipped (132 passing).
- Desktop/mobile browser suite: 38 passing checks, including selecting eight seats, shared pricing/group movement, bulk removal, rescheduling, cancellation, archive and retained customer ticket history.
- Eight critical organizer/payment cases also passed against a disposable Neon branch after applying V6, using a three-connection pool. The branch was deleted and CLI context restored to production.
- Application and organizer static builds pass.
- New database migration: `V6__organizer_event_management.sql`; prior applied migrations remain unchanged.

Tests use separate local test storage and explicit browser fixtures. Hosted payment keys and customer records are not used by the regression suite. Browser fixtures verify interface behavior; backend integration tests verify ownership, inventory and payment state transitions.
