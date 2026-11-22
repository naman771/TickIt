# Hosted environment

Verified 22 November 2026. Azure is the primary demo deployment; both Render sites remain available as backups.

- [Customer website](https://tickit-trial-naman771.azurewebsites.net/)
- [Organizer website](https://tickit-trial-naman771.azurewebsites.net/organizer/)
- [Entry workspace](https://tickit-trial-naman771.azurewebsites.net/organizer/entry.html)
- [Restricted developer workspace](https://tickit-trial-naman771.azurewebsites.net/developer/)
- [Health endpoint](https://tickit-trial-naman771.azurewebsites.net/actuator/health)

## Hosting and budget

Azure App Service runs Java 21 on one Linux Basic B1 instance in UAE North, with Always On, HTTPS, TLS 1.2 or newer, and WebSockets enabled. The Azure for Students spending cap remains on. Hosting uses the owner's existing student credits; no pay-as-you-go conversion, live payment keys, paid monitoring, or additional database resource was enabled.

Always On prevents idle unloading and addresses the slow first visit caused by Render Free sleeping. Deployments, deliberate restarts, platform maintenance, dependencies and provider outages can still affect availability. This is a demo deployment, not an uptime guarantee.

PostgreSQL 16 and private workspace object storage remain on Neon Free; the shared Redis-compatible service remains Render Free over TLS. Student credits and free-service quotas are finite. Neon Free includes 100 CU-hours per project per month; uninterrupted database polling can exceed that allowance. Monitor usage before leaving the demo running for extended periods. [Neon free-plan limits](https://neon.com/blog/how-to-make-the-most-of-neons-free-plan).

## Accounts and integrations

Clerk's development instance supplies real Google/email authentication. Allowed origins and developer subjects remain explicitly configured. Organizer/customer registration does not grant developer access; production identity remains separate.

Razorpay **test mode** is connected and live keys remain blocked. The owner updated the test webhook destination to `https://tickit-trial-naman771.azurewebsites.net/api/payments/webhook`, retaining the existing secret and event selection. Invalid signatures are rejected. Refund jobs retain their original request/idempotency keys. A historical provider/account refund exception remains in review; it was not retried or marked successful during migration. See [refund operations](REFUNDS.md).

Brevo HTTPS delivery is enabled on Azure. The owner authorized Azure's outbound addresses in Brevo while retaining unknown-IP blocking. New confirmation/refund messages link to Azure My tickets. Inbox delivery was confirmed for the acceptance messages; this does not constitute delivery/bounce tracking for every future message. Historical held notifications remain held. See [notifications](NOTIFICATIONS.md).

## Background processing and backups

Azure owns notification delivery, refund processing, payment reconciliation and ticket recovery. The corresponding Render consumers and email delivery were disabled, and that configuration was deployed successfully before Azure consumers were enabled. Shared data and historical review/held records were preserved.

- [Render customer backup](https://tickit-mpy2.onrender.com/)
- [Render organizer backup](https://tickit-organizers.onrender.com/)
- [Render entry backup](https://tickit-organizers.onrender.com/entry.html)

Render Free can still sleep. Restoring worker ownership to Render requires an explicit handoff; do not enable both sets of consumers as an unreviewed failover. Shared Redis does not distribute the application's in-memory STOMP broker across hosts. Use one host consistently for a demo.

Azure serves organizer and entry pages under `/organizer/` on the same origin as the secured Java API. The retained Render organizer remains an independent static build. Developer access uses `/developer/`; no custom domain has been purchased.

## Live acceptance

The following were exercised against the real Azure deployment on 22 November:

- All 15 public asset/configuration and anonymous-access checks passed.
- Ten desktop/mobile checks across customer, organizer, organizer index, developer and entry pages passed; sign-in dialogs loaded without JavaScript errors or horizontal overflow.
- An authenticated 40-request burst returned 25 successes and 15 rate-limit responses with valid retry headers. Separate real-client IP attribution remains outside this single-account check.
- A private asset uploaded, downloaded with identical bytes, and was deleted; its subsequent download returned 404.
- One approved INR 100 Razorpay test wallet booking was confirmed and issued a valid ticket.
- Customer cancellation invalidated that ticket. Its actual QR image returned `INACTIVE` at check-in.
- The provider reported the full INR 100 refund as `processed`, with a matching receipt. Both booking and refund emails reached the owner's inbox and contained Azure ticket links.
- The temporary acceptance event was cancelled and disappeared from Explore. The Grand Prix demo draft remained private.
- The earlier real demo booking also verified first admission and repeated-scan rejection.

These checks do not certify launch capacity, arbitrary third-party inbox delivery, offline scanning, or live-money readiness. Physical QR acceptance was separately confirmed by the owner.

## Source and deployment

Azure currently runs commit `ec31a430b72155f1128568fffe2fa5e18b462699`, whose workspace/return-URL changes are tracked in [PR #1](https://github.com/naman771/TickIt/pull/1). Publishing these documentation links does not merge that draft PR or redeploy Azure.

The Render backup uses the existing main-branch Docker/static builds. Its worker-disable environment must be preserved during future deployments. Flyway owns additive schema migrations; never replace the connected Clerk/Neon/payment configuration with catalog-only defaults.

The standalone Render-origin release check remains:

```bash
node scripts/verify-release.mjs https://tickit-mpy2.onrender.com https://tickit-organizers.onrender.com
```

That script expects origin-root organizer assets. Azure's nested `/organizer/` paths require the corresponding path-aware checks; passing Azure twice to the origin-only script is not a valid verification.

See [ticket operations](TICKETS_AND_ENTRY.md), [validation](VALIDATION.md) and [remaining work](CONTINUATION.md).
