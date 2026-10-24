# Durable notifications

Booking confirmation, event cancellation/rescheduling and final/referral refund updates use a PostgreSQL outbox. Booking and event-change jobs commit with the business transaction. A bounded repair sweep reconstructs missing booking/refund intents. Unique deduplication keys prevent repeated callbacks from creating another logical message.

The worker claims jobs with PostgreSQL row locking and leases, performs external I/O outside the claim statement, and records acceptance or a sanitized retry. Eight failed attempts stop at `FAILED`. Credentials, addresses and provider response bodies are not stored in error messages. Disabled delivery leaves jobs queued.

Configure privately:

```dotenv
EMAIL_ENABLED=false
EMAIL_TRANSPORT=brevo
BREVO_API_KEY=
EMAIL_FROM=
APP_PUBLIC_URL=https://tickit-mpy2.onrender.com
SMTP_HEALTH_ENABLED=false
```

Brevo's HTTPS transport is implemented for the current host because [Render Free blocks the usual SMTP ports](https://render.com/docs/free). Brevo currently lists a [free allowance of 300 emails/day](https://help.brevo.com/hc/en-us/articles/208589409-About-Brevo-s-pricing-plans); account approval, sender verification and current limits still need checking before activation. No account, paid service or sender has been provisioned by this change. See [the transactional endpoint](https://developers.brevo.com/docs/send-a-transactional-email).

`EMAIL_TRANSPORT=smtp` remains available for local or compatible hosting with `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER` and `SMTP_PASS`. SMTP has bounded connection/read/write timeouts. Enabling delivery requires sender configuration; keep it disabled until a real send has been authorized and validated.

Developer **Email delivery** displays queue counts and recent sanitized outcomes through `GET /api/developer/notifications`. `SENT` means the transport accepted the message; inbox delivery, bounces and delivery-event webhooks are not verified. A lost acceptance acknowledgement can produce duplicate emails. There is no exactly-once delivery claim or dead-letter replay interface yet. Ticket emails link to the authenticated My tickets page; bearer QR credentials are not embedded in email or public URLs.
