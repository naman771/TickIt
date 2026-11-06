# Spring Boot startup — 7 November 2026

The Docker image now launches Spring Boot's extracted application JAR with its libraries in `lib/`, instead of loading nested dependency JARs from the executable archive. Extraction happens during the build, not on every boot. The unused Thymeleaf starter is removed; customer, organizer and developer pages use static HTML and MVC forwards.

Normal tiered compilation, eager initialization, Flyway migration validation and Hibernate schema validation remain enabled. No frontend hosting, billing plan, provider credential, production data or rate-limit change is part of this checkpoint. The cancelled Sentry dependency and integration were removed before publication.

## Local comparison

Two starts per variant, each with the existing Render memory flags: heap 192 MiB, metaspace 144 MiB, code cache 32 MiB, direct memory 16 MiB and thread stacks 512 KiB. Both variants use G1 and `-XX:ActiveProcessorCount=1` for a consistent local CPU count. This does not reproduce Render CPU quotas. Local Java is 21.0.9 on macOS; the previously observed hosted runtime was Java 21.0.13 on Linux.

Each variant uses its own disposable PostgreSQL database and Redis process. First runs migrate an empty database; second runs validate the existing schema. Email, payments, monitoring, asset storage, seed data and outbound job workers are disabled. No production credentials or `.env` file are loaded.

| Measurement | Original nested JAR | Extracted JAR, unused template engine removed |
| --- | --- | --- |
| Health UP, first run | 7.059 s | 4.212 s |
| Health UP, second run | 6.173 s | 3.823 s |
| Mean health UP | 6.616 s | 4.018 s |
| Spring startup, first run | 6.169 s | 3.768 s |
| Spring startup, second run | 5.374 s | 3.386 s |
| First catalog request | 74–79 ms | 47–60 ms |

The combined change improved mean local readiness by approximately 39%. Two runs are a small comparison, not a statistical capacity claim. The individual contributions of dependency removal and packaging were not isolated. The first original run also overlapped briefly with local browser screenshot capture; hosted improvement must be measured separately. The baseline artifact also includes the previously prepared but disabled monitoring dependency; the live `17f5fd4` revision does not.

An experiment using `-XX:TieredStopAtLevel=1` with the trimmed nested JAR took 8.375 / 8.275 s to health UP and was rejected. No compiler restriction is added to the image.

Health reported UP and `/`, `/api/events`, `/api/public/config` returned 200 for each measured start. Anonymous order history and developer readiness remained 401. The startup checkpoint's full backend verification passed: 234 tests, 231 passed, 3 optional provider skips, zero failures or errors. A booked-seat regression fixture had aged out; its test now temporarily opens the seed event inside a rolled-back transaction. Production event dates were not changed. The extracted Java launch was tested locally; Docker is unavailable on this machine, so the complete Alpine image build has not been exercised locally.

## Deployment acceptance

Render Free idle sleep remains a separate platform behavior; this change cannot eliminate it. After an authorized release, compare the hosted application-start and health-ready times with the previous startup logs. Check customer HTML, organizer/developer MVC forwards, static resources, catalog, authentication rejection and paid test checkout. Keep Razorpay test mode and all existing free resources. Do not claim the local four-second result as the hosted cold-start time.

Official reference: [Spring Boot 4.0 efficient deployments](https://docs.spring.io/spring-boot/4.0/reference/packaging/efficient.html).
