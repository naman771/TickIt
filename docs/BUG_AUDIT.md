# Bug audit — 23 October 2026

Scope: implemented customer, organizer and developer functionality, API authorization, Redis holds and account limits, PostgreSQL consistency, live updates, private storage, browser behavior, dependency hygiene and deployment. Payment processing, paid ticket issuance and transactional delivery are not implemented and were not presented as tested features.

## Findings corrected

| Area | Defect and correction | Regression evidence |
|---|---|---|
| WebSocket trust | Clients could publish directly to broker topics. Client SEND frames are now rejected; subscriptions are restricted to published event seat topics. Transport buffers, per-session subscription count and subscription-frame bursts are bounded. | `WebSocketBoundaryTest`, real network `WebSocketDeliveryTest` |
| Hold notifications | A broker outage could return an error after a Redis hold/release succeeded. Notifications now fail independently, and maps reconcile through the API. Hold broadcasts no longer include account IDs. | `BookingSafetyTest.holdAndReleaseRemainSuccessfulDuringBrokerFailure` |
| First login | Concurrent provisioning inside a business transaction could mark that transaction rollback-only after a unique-key race. PostgreSQL `ON CONFLICT DO NOTHING` now arbitrates identity creation on the existing transaction connection. This avoids both rollback poisoning and pool exhaustion from suspended transactions; identities are never linked by email. | `AccountSecurityTest.concurrentFirstLoginDoesNotPoisonOuterBusinessTransactions` |
| Inventory | Public availability included held seats and zone spaces with no remaining ticket quota. Starting prices included unavailable inventory. Counts and prices now use selectable inventory. | `OrganizerFlowTest.eventCountsAndStartingPriceExcludeHeldSeatsAndExhaustedTicketTiers` |
| Layout validation | Null list elements could cause server errors; nearby seat coordinates could produce overlapping buttons. Nested elements and 22 × 22 positions are validated consistently. | Organizer API and browser tests |
| HTTP failures | Unsupported methods/media types and missing resources could become generic 500 errors. They now preserve 405/415/404 semantics; invalid JSON/fractional integer fields return 400. Infrastructure connection failures return 503 without private provider details. | Account-security and rate-limit boundary tests |
| Customer quantities | Fractional/out-of-range GA input displayed a different quantity from the computed total. Quantity is now integral and clamped; sold-out selection shows zero. | Desktop/mobile browser checks |
| Customer interaction | Availability polling replaced focused age inputs; category highlighting was lost after navigation; a stale failed event load could overwrite a newer route. Focus/state and route ownership are preserved. Non-JSON gateway errors now show a recoverable service message instead of a parser exception. | Browser checks and route guards |
| Organizer async operations | Delayed event loads or saves could replace a newly opened event. Requests now check route generations before updating the current editor. Forms cannot be edited during save. | Slow-load and slow-save browser regressions |
| Organizer form and preview | Hidden admission fields blocked seated saves. Preview keyboard interactions could reach editor handlers. Inactive inventory fields are disabled; preview handlers are read-only; cancelled drags restore positions. | Browser regressions |
| Authentication UI | An older account response could restore stale user state after sign-out. Session synchronization now discards superseded responses. | Browser sign-out race regression |
| Diagnostics | Failure-path behavior needed explicit verification. Database failures are reported as failed runs, never successful metrics. | `DiagnosticsFailureTest`, developer browser failure check |
| Runtime dependencies | The old Spring Boot 3.2.5 baseline had published dependency advisories. Upgraded to 3.5.16 plus compatible patches for Tomcat, Jackson, Netty, PostgreSQL JDBC, HttpComponents and Log4j API. Added the PostgreSQL Flyway module. | Complete regression suite, provider checks and OSV recheck |

## Verification

- Local backend suite: **84 passed**, one optional provider test skipped in ordinary CI.
- That optional provider test was run separately: **1 passed**, using real Neon database reads and private storage upload/list/download/anonymous denial/delete/404, with cleanup.
- **22 Playwright checks passed** across desktop and mobile. They are committed and run in GitHub Actions, using explicit fixture identities and API responses. They do not impersonate a live account.
- **22 critical integration cases also passed on a disposable Neon branch** with a three-connection pool. This repeated subset included identity races, organizer ownership/publishing/inventory and diagnostics. The branch was deleted afterward.
- Real WebSocket transport delivery, publication rejection, PostgreSQL version conflicts, 50-thread booking contention, mixed seated/standing account limits, natural expiry restoring allowance, owner-only release, rollback and notification failure are covered.
- Runtime advisory scan: **147 Maven packages** checked through OSV. See remaining findings below. Package/version matching is not proof of exploitability or a complete security assessment.
- A secret-pattern scan of the 25 existing Git commits found no matching secret-key files. This is a bounded pattern check, not a guarantee that every possible secret format is absent.

Repeatable commands:

```bash
DB_USER="$(whoami)" mvn -B verify
npm ci
npx playwright install chromium
npm run test:browser
npm run build
node scripts/build-organizer.mjs
python3 scripts/audit_dependencies.py
```

The advisory command exits 1 when findings remain (including the two documented findings), and 2 if the scan could not complete; it never treats an unavailable advisory service as a clean scan.

Backend tests require isolated PostgreSQL and Redis test databases. Cloud database tests use a disposable Neon branch. Never point destructive fixture tests at the production database. The opt-in storage check loads credentials locally and removes only its own object.

## Remaining dependency findings

OSV still flags `spring-webmvc:6.2.19` for two advisories:

- [GHSA-j9f9-w8pj-32f8](https://github.com/advisories/GHSA-j9f9-w8pj-32f8): SSE stream rendering with view fragments.
- [GHSA-pc63-qcmh-9cmg](https://github.com/advisories/GHSA-pc63-qcmh-9cmg): `XsltView` rendering with a catch-all implicit view mapping.

The reviewed application uses JSON REST responses, static HTML and STOMP WebSockets. It has no SSE fragment-rendering endpoints or configured XSLT views. Those triggering features were not found in the implementation; this is a source-based applicability assessment, not a claim that the library has no vulnerabilities. The advisories list Framework 7.0.9 as patched. A separately verified Spring Boot 4 / Framework 7 migration remains required to eliminate these dependency findings; do not introduce the affected features in the meantime.

## Limits of this audit

Passing checks cannot establish that every possible bug, input, network failure or future dependency advisory has been eliminated. Real user Google OAuth uses the account owner's session; automated browser regressions use explicit fixtures. The bounded developer stress runner is a component contention check, not a certification of production throughput. Long-duration public load testing, disaster recovery and the future payment/webhook/refund lifecycle remain outside this release's verified scope. Existing free-host cold starts and quotas also remain.
