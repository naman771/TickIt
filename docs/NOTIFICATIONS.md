# Durable notifications

Booking confirmation, event cancellation/rescheduling and final/referral refund updates use a PostgreSQL outbox. Booking and event-change jobs commit with the business transaction. A bounded repair sweep reconstructs missing booking/refund intents. Unique deduplication keys prevent repeated callbacks from creating another logical message.

The worker claims jobs with PostgreSQL row locking and leases, performs external I/O outside the claim statement, and records acceptance or a sanitized retry. Eight failed attempts stop at `FAILED`. Credentials, addresses and provider response bodies are not stored in error messages. Disabled delivery leaves jobs queued.

Configure privately:

```dotenv
EMAIL_ENABLED=false
EMAIL_TRANSPORT=brevo
BREVO_API_KEY=
EMAIL_FROM=
EMAIL_ALLOWED_RECIPIENTS=verified-owner@example.com
APP_PUBLIC_URL=https://tickit-mpy2.onrender.com
SMTP_HEALTH_ENABLED=false
```

Brevo's HTTPS transport is implemented for the current host because [Render Free blocks the usual SMTP ports](https://render.com/docs/free). Brevo currently lists a [free allowance of 300 emails/day](https://help.brevo.com/hc/en-us/articles/208589409-About-Brevo-s-pricing-plans); account approval, sender verification and current limits still need checking before activation. No account, paid service or sender has been provisioned by this change. See [the transactional endpoint](https://developers.brevo.com/docs/send-a-transactional-email).

`EMAIL_TRANSPORT=smtp` remains available for local or compatible hosting with `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER` and `SMTP_PASS`. SMTP has bounded connection/read/write timeouts. Enabling delivery requires sender configuration; keep it disabled until a real send has been authorized and validated.

Developer **Email delivery** displays queue counts and recent sanitized outcomes through `GET /api/developer/notifications`. `SENT` means the transport accepted the message; inbox delivery, bounces and delivery-event webhooks are not verified. A lost acceptance acknowledgement can produce duplicate emails. There is no exactly-once delivery claim. Failed messages can be retried through an audited developer review action. Ticket emails link to the authenticated My tickets page; bearer QR credentials are not embedded in email or public URLs.

## Controlled activation and queue review

V11 links jobs to their order or event change and adds an operator audit trail. V12 moves the existing unleased backlog into `HELD`; enabling delivery cannot silently drain those historical messages. The developer portal lets an authorized developer release an individually reviewed held message, retry a failed message, or suppress an unsent message. Each action requires a reason and records the authenticated actor. Accepted messages and actively leased jobs cannot be changed through review.

`POST /api/developer/notifications/{id}/review` accepts `action` (`RELEASE`, `RETRY`, `SUPPRESS`) and `reason`. Review changes queue state; it does not send synchronously or modify bookings, tickets or refunds.

Set `EMAIL_ALLOWED_RECIPIENTS` to a comma-separated list during initial acceptance. Both queue claiming and transport enforce this scope; other recipients remain queued. An empty value permits all recipients. Keep `EMAIL_ENABLED=false` until the verified sender and one owner-address test have been accepted, then confirm inbox receipt separately. Review historical held jobs before releasing them; remove the recipient restriction only when broader delivery is intended.

Immediately before sending, the worker checks current business state. Cancelled/refunded booking confirmations, superseded event updates and obsolete refund outcomes are suppressed. An event can still change after this check while the external request is in progress; emails direct customers to their account for current status.

The optional `BrevoServicesE2ETest` requires `BREVO_E2E=true`, valid private provider configuration, and matching `EMAIL_TEST_RECIPIENT` / `EMAIL_ALLOWED_RECIPIENTS`. It sends one explicitly scoped verification email and reports provider acceptance. Ordinary tests skip this external send. Delivery/bounce webhooks remain future work.
