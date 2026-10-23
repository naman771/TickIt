# Spring Boot 4 migration verification

Verification date: 23 October 2026. Publication was authorized after local verification. The results below were collected before publication; GitHub Actions and Render provide subsequent CI and deployment status.

## Changes

- Spring Boot 3.5.16 → 4.0.9, with Framework 7.0.10, Tomcat 11.0.27, Hibernate ORM 7.2.25 and Jackson 3.1.7. Java remains 21.
- Adopted the modular MVC, OAuth resource-server, Flyway and test starters. Updated test injection to `@MockitoBean` and Boot's MVC test package.
- Migrated Clerk response parsing and STOMP transport tests to Jackson 3. Retained strict rejection of fractional integer request fields.
- Registered the Neon datasource adapter against Boot's current `EnvironmentPostProcessor` contract. A regression verifies discovery through `spring.factories`.
- Added an HTTP regression for ISO date/time strings, numeric prices and explicit nullable age policies, protecting frontend compatibility during JSON upgrades.
- Removed obsolete Tomcat 10 / Netty 4.1 overrides. Retained compatible HttpComponents and Jackson 2 security patches for transitive consumers. API routes, account authorization, inventory rules and Flyway V1–V4 remain intact; no schema migration was added.

The upgrade follows the [official Spring Boot 4 migration guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide). The 4.0 maintenance line was selected to keep this security migration focused rather than combine it with another feature-line upgrade.

## Local results

```text
DB_USER=naman mvn -B -Dsurefire.runOrder=random -Dsurefire.runOrder.random.seed=20261023 verify
Tests run: 90, Failures: 0, Errors: 0, Skipped: 1
BUILD SUCCESS

npm run test:browser
22 passed (11.2s)

python3 scripts/audit_dependencies.py
packagesChecked: 177
flaggedPackages: 0
findings: []

npm run build
BUILD SUCCESS

node scripts/build-organizer.mjs
Organizer static frontend built: dist/organizer
```

The ordinary backend run contains 89 passing tests; the optional provider case is skipped unless explicitly enabled. It was run separately and passed with real Neon reads and private storage upload/list/download byte comparison, anonymous-access denial, deletion and subsequent 404. Its synthetic principal exercises server authorization without impersonating a human OAuth session. Only its own temporary object is written and removed.

The previous Framework 6 findings, GHSA-j9f9-w8pj-32f8 and GHSA-pc63-qcmh-9cmg, no longer appear in the upgraded OSV scan. Zero known matches is a point-in-time result, not a guarantee against unknown or future vulnerabilities.

## Cloud database validation

A disposable Neon branch passed all 22 critical integration cases with the three-connection pool used by the hosted app. This covers concurrent first-login transactions, HTTP error semantics, organizer ownership, draft publishing, custom inventory, age restrictions and diagnostics. The CLI context was restored to production and the temporary branch was deleted. Production catalog and booking rows were not used as mutable test fixtures.

```text
Tests run: 22, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Neon validation test exit code: 0
Production context restored; disposable validation branch deleted
```

## Runtime checks

The packaged JAR starts locally on port 8081 under the existing Render JVM caps: heap 192 MB, metaspace 144 MB, code cache 32 MB, direct memory 16 MB, stack 512 KB. Startup completed in 17.195 seconds. Health returned 200 / UP; public catalog returned 200 JSON; unauthenticated developer and organizer API requests returned 401. Flyway validated all four existing migrations and required no schema change.

The real-catalog browser check selected three seats, rejected a fourth and displayed the total. All API writes were blocked during that check; zero writes were attempted.

## Limits

Docker is not installed on this machine, so a fresh container build has not been performed for this migration. Container validation must run in GitHub Actions after publication. Google OAuth was not completed as another user, and payment/email delivery remain disabled. Public load, payment/webhook/refund lifecycle and production identity rollout are separate work.

The migration is grouped into one commit. Existing commit-history cleanup is deferred; no history was rewritten.
