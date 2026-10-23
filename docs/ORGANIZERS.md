# Organizer workspace

Live organizer site: https://tickit-organizers.onrender.com

The organizer frontend is a separate static website. Customer pages contain no organizer navigation. It shares the existing authenticated Spring Boot API, Neon database and Clerk identity provider; it does not grant developer privileges.

## Create and publish an event

1. Sign in or sign up with Clerk, then create an organizer profile using your display/organization name.
2. Create a private event draft. Set category, admission format, venue/address, city, date/time, timezone, guest capacity, cover image HTTPS URL, optional sales dates, a ticket limit of 1–3 per account and an optional minimum guest age. Blank minimum age means all ages.
3. For assigned seating, add and position sections or import a seat CSV. For standing admission, create shared-capacity zones and their ticket types. Mixed events support both.
4. Save and preview. Drafts are excluded from every public event, seat-map and admission endpoint.
5. Publish when ready. Publishing validates the description, future event time and appropriate inventory. Published events become discoverable and remain editable by their owner. Duplicate a published event to create an independent draft for another edition.

New organizer events use INR. Existing legacy event prices retain their recorded currency; values are never relabelled.

## Seating layouts

Each event stores its own seats, sections, row labels, coordinates and prices. Templates provide straight rows, fan-shaped rows and a centre aisle; they are editable starting points, not verified plans for named halls. Select any number of seats, or use Select all, to apply prices or remove them together. Switch to Move seats to drag a selected group while retaining its spacing. The three-ticket customer limit does not apply to the organizer editor. Seats can also be moved or removed individually, and the stage position/label can be changed. Zoom controls support detailed editing on smaller screens.

CSV header: `section,row,number,x,y,price`. The canvas is 1000 × 700; seat origins are bounded to x=0–970, y=0–670. Imports replace the current map after confirmation; unchanged seat labels preserve their database IDs on published events. Unique section/row/number labels and positions are checked server-side. Up to 1,500 assigned seats, 12 standing zones and 8 ticket types per zone are supported.

Official venue factsheets can serve as references, but a capacity/floor diagram is not an authoritative list of bookable seats. Event owners must verify their actual configuration. Published layouts are not silently shared or changed across events.

## Standing and mixed inventory

A zone is the capacity pool. Early Bird and Regular ticket types share that zone's capacity while maintaining separate quotas and prices. Ticket quotas do not create extra guest capacity. Assigned seats plus zone capacities cannot exceed the event's maximum guest capacity.

General admission has its own durable PostgreSQL reservations; it does not create fake seats or reuse the seat-specific booking uniqueness constraint. Reservations hold a complete quantity for 120 seconds. A row lock on the event serializes competing reservations across tiers, and active quantities are counted against both the zone and ticket quota. Expired/released holds immediately stop consuming availability. PostgreSQL time controls expiry; the scheduler removes old expired records after a day. Releasing a hold requires its owner.

The customer frontend offers zone/type/quantity selection with live availability reconciliation. Connected Razorpay test checkout confirms paid GA order lines and assigned seats atomically. QR ticket issuance remains future work. Checkout stays disabled in unconfigured environments.

## Authentication and deployment

The static site builds with `node scripts/build-organizer.mjs` into `dist/organizer`. Only the public API origin is embedded in its config. Secrets and all authorization decisions remain on the Java server. Configure both the customer and organizer origins in `AUTH_ALLOWED_ORIGINS`; CORS allows only these exact origins, with bearer authorization and no credentialed cross-origin cookies.

For local development, allow `http://localhost:8081,http://localhost:8083` in the backend auth configuration, run Java on 8081, and build with `ORGANIZER_API_BASE=http://localhost:8081 node scripts/build-organizer.mjs`. Serve `dist/organizer` on port 8083. Clerk login is performed separately on the organizer origin using the same account.

## Validation

Twenty-three organizer integration tests cover ownership, role separation, draft privacy, custom layouts, stale revisions, publication, bulk edits, booked-seat protection, cancellation, deletion, date changes, invalid inputs, sales windows and concurrent reservations across shared-price tiers. The V3 migration and these flows also passed on a temporary Neon copy of production; that branch was removed afterward. Browser checks use explicit UI fixtures and cover CSV import/export, custom seating, mixed admission, preview, publishing, INR pricing, GA quantities and mobile layout. No sample organizer events were inserted into the public catalog.

The live organizer origin was tested with real Clerk scripts and the production API: Google sign-in UI loaded, cross-origin requests reached protected routes, and no browser errors occurred. Completing OAuth and creating a real event uses the organizer's own session; automated flow data stayed in isolated tests.

## Customer limits and age eligibility

Customers can select and deselect up to three assigned seats with itemized pricing and a combined total. An organizer may set a lower per-account limit. The reservation APIs enforce the same ceiling across active seat holds, standing holds and existing assigned-seat bookings for an event. Redis atomically prevents parallel seat requests from exceeding the available allowance; the event transaction serializes mixed-mode allocation. A person with multiple accounts is not automatically identified as the same person.

An optional minimum age is persisted with the event, displayed to customers, and enforced on seat and standing hold requests via `attendeeAge` (the youngest guest's declared age). This is self-declaration, not verified age or identity-document checking. Organizers remain responsible for any required entry checks. The hosted checkout uses Razorpay test mode; live payment acceptance remains disabled.

## Managing published events

Owners can save new seat prices and positions, add seats, or remove unsold seats. Selection has no three-seat cap; the layout supports up to 1,500 assigned seats. Existing seat IDs remain stable. Booked seats cannot be deleted or relabelled; their paid checkout amounts remain unchanged when future seat prices change. Removed unsold seats are retired so older checkout references remain valid.

Active reservations block inventory edits. Retry after those holds expire. Published admission format, standing zones and ticket tiers cannot be replaced in this release. Every update requires the current event revision; stale edits return 409 rather than overwriting another tab.

Change the date/time and provide a reason to postpone or bring an event forward. The new start must be in the future. Rescheduling expires pending checkouts and preserves confirmed tickets; customers see the updated schedule and reason in My tickets. When reservations exist, reschedule without changing seats first.

Cancel event closes sales and removes the event from discovery. Delete event archives it and removes it from the organizer list as well. Both require a reason and the current revision. Neither destroys bookings, payment references or the event audit trail. Existing customers retain their ticket history, with a cancellation/refund-pending indicator. The developer payment review queue includes captured payments for cancelled/deleted events. Full refunds are queued automatically for captured payments after cancellation/deletion. Rescheduling keeps confirmed tickets valid. Refund status appears in customer history and the developer Payments panel; provider failures require follow-up. Direct rescheduling email notifications remain pending.

See [organizer management verification](ORGANIZER_MANAGEMENT.md).
