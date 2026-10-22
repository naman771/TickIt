# Production setup

The customer website and developer workspace are separate frontends backed by the same Spring Boot API. Customer sessions do not grant developer access.

## Domains

Intended deployment:

- `https://tickit.site` — customer website.
- `https://developers.tickit.site` — restricted developer workspace.

The `.site` registry returned `tickit.site` as registered with `pending delete` status on 23 October 2026. This is **not** a confirmation that the domain can be bought. Ownership must be secured before configuring production DNS and OAuth. [Registry lookup](https://rdap.radix.host/rdap/domain/tickit.site).

The application selects the developer frontend at the configured `DEVELOPER_HOST`. Locally, the same frontend is available at `/developer/`. Both custom domains can point at the same backend service while serving independent frontends. API requests stay on the calling frontend's origin.

## Recommended services

| Purpose | Service | Account setup |
|---|---|---|
| Customer identity | [Clerk](https://clerk.com/docs/js-frontend/getting-started/quickstart) | Create an application; enable email/password and Google. |
| Google sign-in | [Google OAuth through Clerk](https://clerk.com/docs/guides/configure/auth-strategies/social-connections/google) | Development credentials are provided by Clerk; production requires your Google Cloud OAuth credentials and configured domain. |
| Indian payments | [Razorpay Standard Checkout](https://razorpay.com/docs/payments/payment-gateway/web-integration/standard/integration-steps/) | Complete merchant onboarding; obtain test keys first, then approved live access. Payment integration is the next implementation phase. |
| Transactional email | [Resend SMTP](https://resend.com/docs/send-with-smtp) | Verify your sending domain, configure DNS, create a server-side API key. |
| Application and data | [Railway](https://docs.railway.com/guides/spring-boot) | Deploy the Docker application, PostgreSQL, and Redis services; configure domains, volumes, backups and monitoring. |

Stripe's [current India onboarding is invite-only](https://support.stripe.com/questions/stripe-accounts-are-invite-only-in-india), so it is not the default payment recommendation here.

## Authentication budget

Use **Clerk Hobby**, not a paid trial or Pro plan. As checked on 23 October 2026, [Hobby pricing](https://clerk.com/pricing) is free with no credit card and a 50,000 monthly retained-user limit per app. Email/password and up to three social providers (including Google) are included. Clerk branding remains; removing it and some advanced features require a paid plan. No paid authentication features are required by this integration.

Stay on Hobby and do not enable paid upgrades. Domain registration, hosting, email delivery beyond provider free limits, and payment processing have separate costs; the free-auth decision does not imply that all business infrastructure is free.

## Connect Clerk

1. Create a Clerk **Hobby** application (free, no credit card) and enable email/password signup and Google sign-in.
2. Copy the publishable key, issuer URL and secret key from the dashboard into local environment variables. Use the exact issuer from Clerk; do not infer it from the website domain.
3. Set `APP_AUTH_MODE=clerk` and `AUTH_ALLOWED_ORIGINS=http://localhost:8081` for local testing.
4. Set `DEVELOPER_SUBJECTS` to your Clerk user ID (`user_...`) to authorize your developer account. Customers must not be added to this list.
5. Start Spring Boot with those variables and visit the website. Clerk renders registration, email verification, password recovery and Google sign-in according to the application settings.
6. In production, create the Clerk production instance, configure its DNS records, and supply your own Google OAuth credentials. Copy the precise redirect URI from Clerk into Google Cloud.
7. Set `AUTH_ALLOWED_ORIGINS=https://tickit.site,https://developers.tickit.site` only after securing those domains. Set `DEVELOPER_HOST=developers.tickit.site`.

The browser receives only the publishable key. It sends short-lived bearer tokens to the API. The backend verifies signature, issuer, expiry, authorized origin and session status; it obtains verified account details from Clerk's Backend API. Existing accounts are never linked by email alone.

`catalog` mode is the default when providers are not configured. It permits browsing and rejects protected operations. `clerk` mode fails startup when required configuration is missing. Test authentication is restricted to the `test` profile.

Copy `.env.example` and fill it locally. Spring does not automatically load a shell `.env` file; export the variables in your terminal, configure your IDE, or set them directly in the hosting platform. Do not paste secret keys into chat or commit them.

## Payment implementation still required

The public site does not label itself as a demonstration. It also does not fabricate a successful checkout: checkout is disabled until the real payment flow is implemented and connected. The existing direct confirmation endpoint is restricted to automated tests.

The next phase must include:

1. A server-priced payment order linked to a durable checkout attempt and idempotency key.
2. Razorpay checkout using the server-created provider order.
3. Server-side signature and captured-payment verification, plus signed webhooks.
4. Idempotent handling of retries and duplicate/out-of-order webhook deliveries.
5. Confirmation only after verified payment, with database-enforced seat uniqueness.
6. Recovery/refunds when payment completes after the seat expires or a booking cannot be committed.
7. Persistent email jobs and ticket delivery after the paid transaction commits.

The existing sample catalog is denominated in USD. Prices must not be relabelled as INR. Publish authoritative INR prices or explicitly configure supported international payment currencies before enabling checkout.

## Developer workspace

The portal contains authenticated infrastructure checks, real database counts, and integration status. API authorization is enforced server-side, not by hiding a URL. No credentials or customer lists are returned. The portal has no production traffic generator or simulated metrics.

Public frontend shell: `/developer/`. Protected endpoint: `GET /api/developer/overview`.

## Launch prerequisites

Secure the domain, connect real identity accounts, complete payment integration and merchant approval, configure transactional delivery, publish genuine event inventory, update the supported dependency baseline, and validate the complete paid flow in staging before turning on checkout.
