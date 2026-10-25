# Customer discovery and self-service

This release adds event information before ticket selection, city/date/budget filters, account-owned saved events, map navigation and customer support. The implementation is local until deployed; the hosted site may still run an earlier release.

## Event information and booking

`/events/{slug}` shows the event description, organizer, lineup, languages, ticket inclusions, accessibility, arrival instructions and FAQs when supplied. `/events/{slug}/book` opens ticket selection. Existing numeric hash links still resolve directly to booking. Both readable paths support a page refresh.

Organizers edit optional plain-text information in the event workspace. Fields are bounded and escaped on display. Existing events do not acquire invented information. Missing/broken artwork uses a neutral placeholder.

Discovery supports category/text, city, event-local date range, maximum starting price and currency. Sorting supports date, name and price; prices are grouped by currency when no currency is selected. Non-sensitive filter preferences persist in the browser. Filtering runs against the currently loaded catalog; a larger catalog will need server pagination.

Assigned-seat maps have fit/zoom controls and price highlighting. Highlighting changes presentation only. Mobile selection summaries link to the existing checkout; selection does not reserve inventory.

## My tickets

History filters distinguish upcoming, past and cancelled/inactive admissions. Event snapshots preserve details after catalog removal. Calendar downloads use UTC event instants; directions open a fixed Maps search URL. Received admissions appear separately with their current entry status.

Saved events are private to the authenticated account, capped at 100. They are interests, not reservations or notifications.

Support requests reference an account-owned order. Customers review replies in My tickets; authorized developers reply in the support inbox. A maximum of five new requests per account per hour is enforced. Do not include passwords, payment card data or QR credentials in messages.

Confirmation resends create a new durable notification intent, at most three per account/hour and once per order/five minutes. They use the account's verified email. Queued does not mean delivered. Historical held notifications remain untouched.

## Admission transfers

The current holder can offer one unused admission to another existing verified TickIt account before event start. The recipient accepts or declines through My tickets; the sender can cancel a pending offer. Offers expire at 24 hours or event start, whichever is sooner.

Acceptance checks the age requirement and account ticket limit, changes the holder and rotates the random QR credential. A donor's previously downloaded QR becomes invalid. Transfer, check-in and cancellation serialize on the event row. Transfer records never contain QR credentials.

Payment receipts and refund rights stay with the purchaser. Organizer guest lists use the current holder. Incoming admissions count toward the recipient's event limit; the purchaser's original purchase still counts toward their purchase limit. Transfer does not enable repeated purchasing beyond the cap.

## Customer cancellation

Default: disabled. An organizer can offer **full-order/full-amount** customer cancellations until 1–720 hours before event start. The checkout snapshots the absolute deadline. Later policy edits do not remove an existing order's deadline; existing orders without a deadline do not retroactively gain one.

Cancellation requires the original purchaser, a future published event, an unexpired purchase-time deadline, no existing refund and no used/transferred-away ticket. It immediately makes the order's admissions inactive and queues one durable full-refund job. Retrying the customer request is idempotent. Provider failures remain visible for operator follow-up.

Cancelled inventory is not automatically resold. Paid booking records and purchase caps are retained; inventory release/resale needs a separate audited lifecycle. Partial cancellation, partial refunds and cancellation fees are not offered.

## API surface

All account routes require authentication and derive the account from the session.

| Route | Operation |
|---|---|
| `GET /api/account/saved-events` | Own saved event IDs |
| `PUT /api/account/saved-events/{event}` | Save published event |
| `DELETE /api/account/saved-events/{event}` | Unsave event |
| `GET, POST /api/account/support` | Own requests / create request |
| `POST /api/account/orders/{order}/resend` | Queue confirmation resend |
| `POST /api/account/orders/{order}/cancel` | Request eligible full cancellation |
| `POST /api/tickets/{ticket}/transfer` | Offer admission to verified account email |
| `GET /api/account/transfers` | Own incoming/outgoing offers |
| `POST /api/account/transfers/{id}` | Accept, decline or cancel offer |
| `GET /api/account/received-tickets` | Own received admissions, without credentials |
| `GET /api/developer/support` | Authorized developer support inbox |
| `POST /api/developer/support/{id}/reply` | Authorized reply and resolution |

## Live organizer revenue

The current Razorpay integration is **single-merchant test mode**. It does not pay organizers. Before live payments, TickIt needs approved organizer merchant/linked-account onboarding, beneficiary snapshots, an organizer settlement ledger, idempotent transfers, webhook reconciliation and refund reversals. Provider eligibility and fees must be established before choosing a live flow. Razorpay Route is a candidate, not an activated integration. No customer UI should imply an organizer has received settlement just because a test payment was captured.
