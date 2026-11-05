# Optional Sentry backend error monitoring

The initial integration reports unexpected HTTP 500 failures and dependency exceptions handled as HTTP 503. It uses Sentry Java SDK **8.62.0** directly, without automatic Spring, servlet, logging or browser instrumentation. This keeps explicit control over what leaves the booking application. The core SDK is compatible with this Spring Boot 4 application without importing a different Spring integration or changing the framework version.

## Activate the student benefit

1. Open the Sentry offer from the signed-in [GitHub Student Developer Pack](https://education.github.com/pack/offers), or follow [Sentry for Education](https://sentry.io/for/education/).
2. Redeem the education benefit in the owner's account and create a **Java** project named `tickit-backend`. Confirm the education/free allowance and disabled paid on-demand usage in that account. An offer being listed does not prove it has been redeemed.
3. Obtain the project's DSN from **Settings → Projects → tickit-backend → Client Keys (DSN)**. Keep configuration in the ignored `.env` for local acceptance and the existing Render service's private environment for hosting. No account authentication token is required by the running application.

```dotenv
SENTRY_ENABLED=false
SENTRY_DSN=
SENTRY_ENVIRONMENT=production
```

Keep delivery disabled until the project is configured and its data policy is reviewed. The project/account connection is separate from the code being ready. Activation requires `SENTRY_ENABLED=true` and a valid HTTPS DSN; malformed enabled configuration is rejected without echoing the supplied URL. `RENDER_GIT_COMMIT` supplies the release identifier automatically. Never put a configured DSN in Git or `render.yaml`.

## Data and volume

Only these explicit error fields are constructed: exception class/package, a fixed generic description, up to 40 application stack locations, failure category, timestamp, release and environment. Exception messages, causes, request URLs/paths, headers, cookies, request bodies, customer identity, booking IDs, ticket credentials, provider responses and variables are not copied. A final `beforeSend` allowlist rebuilds the event after SDK processors, removing enriched user/request/context, breadcrumbs, extras and unknown metadata. Stack filenames are restricted to simple source filenames, without filesystem paths.

No global Sentry SDK scope is initialized or bound to requests. Automatic integrations are not registered. Tracing, profiling, structured logs, metrics, attachments, client reports and disk caching are off. Browser error monitoring, replay, uptime probes and background-worker instrumentation are outside this first checkpoint.

The application submits at most one event every **300 seconds per running instance**, across all categories. The interval may be configured with `app.monitoring.min-interval-seconds`, between 60 and 3600 seconds. Suppression is intentionally coarse to protect the free allowance; new error types can also be suppressed during the cooldown. The SDK has an eight-item asynchronous transport queue, bounded network timeouts and bounded shutdown. Restarts reset the in-memory cooldown, and multiple instances/projects consume separate budgets; this is not a provider billing guarantee. Keep paid overages disabled in Sentry.

Sentry is best effort and is not a business outbox or durable audit log. Provider failures must not change the booking response. Expected client errors such as validation, seat conflict and rate exhaustion are not reported. Existing refund/notification operational queues remain authoritative.

## Verify and operate

The protected developer readiness API includes `errorMonitoring`: configuration state, minimum interval, locally submitted-to-SDK count, suppressed count and detected submission failures. It never returns the DSN. `CONFIGURED_REQUIRES_ACCEPTANCE` and `submittedToSdk` do **not** prove Sentry accepted or displayed an event. SDK transport retries/drop handling can also be invisible to these local counters.

After owner configuration, send one sanitized synthetic acceptance event from an isolated/local validation process, flush it, and confirm its release/category in the owner's Sentry project. Do not manufacture a public application failure or perform a booking/refund to test monitoring. Check the project's issue alert destination in the owner's account before calling notifications active. An SDK event ID alone is not proof of dashboard arrival. Hosted activation and live acceptance remain pending until this step is completed.

`BackendErrorMonitorTest` serializes a real SDK envelope through an in-memory transport, injects sensitive request/user/context data, and asserts its removal. It also covers concurrent cooldown, disabled delivery, provider failures and expected-error filtering. Account security tests enforce developer-only status access. Ordinary tests never submit telemetry to Sentry.

Official references: [Sentry Java data collection](https://docs.sentry.io/platforms/java/data-management/data-collected/), [Java SDK release](https://github.com/getsentry/sentry-java/releases/tag/8.62.0), [Spring Boot integration variants](https://docs.sentry.io/platforms/java/guides/spring-boot/).
