# Azure trial and redirect acceptance

Azure is a parallel trial. Keep the existing Render backend and organizer site deployed until authenticated acceptance succeeds and the owner authorizes a cutover.

The trial uses Linux App Service B1 (Java 21), one worker, Always On, HTTPS and WebSockets. It uses authorized Azure for Students credits with the subscription spending limit retained. Do not upgrade to pay-as-you-go or remove that limit. The existing Neon database and shared Render Redis are retained. Limit Redis external access to the Azure app's exact outbound IP addresses, using TLS.

The combined Java deployment serves customer `/`, organizer `/organizer/`, developer `/developer/`, and organizer entry `/organizer/entry.html`. Canonical workspace redirects preserve the query string so authentication callbacks survive. The organizer hostname redirects to its directory before rendering, allowing its relative assets to resolve. The separate Render organizer site still uses its existing root path and backend origin. On Azure, the organizer API origin is the current origin.

The identity client keeps the current workspace and deep link through sign-in, sign-up switching and sign-out. Temporary `__clerk_*` query values must be consumed by Clerk during initialization, but must not be copied into future return destinations. Do not manually decode handshake credentials, reproduce tokens from browser URLs, or store real session URLs in diagnostic output.

## Verification

- Exercise the actual HTTP entry routes, following canonical redirects. A static test server serving directory indexes does not verify Spring's controller mappings.
- Check desktop and mobile customer, organizer and developer pages, their assets, reloads and deep links.
- Check Clerk loads, sign-in/sign-up modals work, and account switching returns to the same origin and workspace. A mocked SDK verifies client options only; it does not establish real OAuth/OTP completion.
- With the owner's session, verify sign-in return, `/api/me`, organizer ownership, developer allowlist, test checkout, ticket visibility and QR entry. Do not automate production bookings, refunds or messages as a read-only deployment check.
- Keep Razorpay test-only. The trial disables email delivery, notification scheduling, refund scheduling, checkout reconciliation and ticket recovery. Existing Render worker ownership remains in place. Media uploads are disabled on the trial pending integration verification.
- Verify real-client-IP attribution for rate limiting behind Azure's proxy. Quota response headers alone do not establish separate visitor buckets.
- The STOMP broker is in memory; shared Redis does not distribute live notifications between Azure and Render. A trial is not a certification of horizontal scaling.

Do not claim the whole application works from health checks or anonymous access checks alone. Idle sleep prevention does not eliminate startup delay after a deployment or platform restart.
