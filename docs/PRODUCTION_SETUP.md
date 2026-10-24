# Production setup

The current connected environment uses separate customer and organizer frontends backed by one Spring Boot API, plus a protected developer workspace. See [actual hosted configuration](HOSTED_ENVIRONMENT.md), [free deployment](FREE_DEPLOYMENT.md) and [release scope](CONTINUATION.md).

## Budget and domains

The budget is strictly zero. Use the existing free Render, Neon and Clerk resources. No paid upgrade, domain purchase, real refund funding or live payment fees are authorized. `.site` domains were discussed but none is owned/configured for this project; the working URLs are the assigned Render addresses. Custom domains and production DNS must only be configured after ownership and the budget decision are resolved.

## Identity

Clerk Hobby is connected with email/password and Google sign-in through the current development instance. The server validates signed sessions, issuer, expiry and exact application origin, then provisions a verified identity profile. Client-supplied user IDs do not grant access to another account.

For public-launch identity configuration, follow Clerk's production-instance and Google OAuth setup, set the real origin allowlist, and verify signup, sign-in, recovery and account isolation on each actual origin. Keep secrets server-side. `DEVELOPER_SUBJECTS` remains a restricted developer allowlist; it must not contain every organizer or customer.

## Payments and refunds

Razorpay test checkout, captured-payment verification, signed webhooks, duplicate handling, recovery and full-refund jobs are implemented and connected. Live keys remain rejected by application settings. See [payment configuration](PAYMENTS.md) and [refund recovery](REFUNDS.md).

Live merchant activation and full live-money acceptance are separate work. Do not switch to live keys merely to make a demonstration work. Resolve the outstanding provider refund-balance exception without changing the customer's full refund amount or fabricating success.

## Tickets and notifications

The ticket lifecycle release issues credentials with confirmed paid bookings and supplies online, single-use entry validation. Cancellation/refund state is checked at entry. See [tickets and check-in](TICKETS_AND_ENTRY.md).

Notification intent commits durably; Brevo HTTPS delivery is enabled after provider acceptance and owner-confirmed receipt of a hosted booking email. Delivery to all customer addresses is authorized; historical held messages still require individual review. See [notification setup](NOTIFICATIONS.md). Provider acceptance does not establish inbox delivery for subsequent messages; delivery/bounce tracking remains pending.

## Operational launch work

Before public live-money sales, finish production identity, merchant activation, payment/refund end-to-end acceptance, monitoring/alerts, a retained backup policy and broader load/failure testing. The [backup/restore exercise](BACKUP_RESTORE.md) verifies a read-only public-schema snapshot in an isolated local database; it does not provide retained backups or a recovery-time guarantee. The current single-instance WebSocket broker needs shared delivery before horizontal scaling. Free-host cold starts and inactivity remain limits, not a tested ticket-sale capacity guarantee. Marketplace organizer payouts/settlements and customer-requested partial cancellations are not implemented.
