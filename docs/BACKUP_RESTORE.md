# Backup and restore exercise

`scripts/verify-backup-restore.py` verifies a logical snapshot of TickIt's `public` schema against a disposable local PostgreSQL restore. It uses the existing database and local tools; it creates no cloud resource or paid service.

## Run locally

Install Python 3 and the PostgreSQL client tools (`psql`, `pg_dump`, `pg_restore`, `createdb`, `dropdb`). The dump client must support the source server version; use the same client version for dump and restore and a compatible local server. The verified environment uses PostgreSQL 18 tools and local server with a hosted PostgreSQL 16 source.

Start local PostgreSQL on the `/tmp` Unix socket, port 5432, with the current OS user permitted to create databases. Supply the existing **direct** TLS connection URL privately as `DATABASE_URL_UNPOOLED` (preferred) or `DATABASE_URL`; never place it in a command argument, Git, a screenshot or a report. The tool does not load `.env` itself. Neon pooled URLs are rejected because snapshot export and session settings need a direct connection. See [Neon connection pooling](https://neon.com/docs/connect/connection-pooling).

```bash
python3 scripts/verify-backup-restore.py
```

The tool exports a repeatable-read, read-only snapshot and uses it for both the row manifest and `pg_dump`. It dumps only the public schema, creates a randomly named `tickit_restore_<uuid>` local database from `template0`, and restores schema/data with owner and privilege reassignment disabled. It checks the table inventory, row counts and sorted whole-row fingerprints, including Flyway history, bookings, payment/refund records, tickets, staff, scan records and notification queues. It prints aggregate counts and an archive SHA-256 checksum without row contents or credentials.

No application process is started against the restored database. Copied refund and email queues therefore cannot dispatch. The production connection defaults to read-only, and all mutation commands use the generated database on the fixed local Unix socket. Inherited PostgreSQL service/host settings cannot redirect the local restore.

Temporary archives are stored in a private temporary directory. The archive and local database are removed on success and ordinary failure, including restore failure. A force-killed process or machine shutdown can interrupt cleanup; inspect only the tool's generated local databases and temporary directories before removing leftovers. Never apply cleanup commands to the hosted source or ordinary development databases.

## Verification

```bash
python3 -m unittest discover -s tests/operations -v
RUN_BACKUP_RESTORE_TESTS=true python3 -m unittest discover -s tests/operations -v
```

The ordinary tests verify rejection of existing database names, insecure/pooled source URLs and inherited connection redirection. The opt-in local integration tests restore synthetic Unicode, null, duplicate and binary rows with foreign keys and quoted identifiers, inject a restore failure to verify cleanup, and corrupt a restored row to verify fingerprint rejection. They create and remove only local disposable databases.

## Recovery limits

This is a restoration exercise, not a retained or scheduled backup. The temporary archive is deleted. No recovery-point or recovery-time commitment follows from one small snapshot. A continuing backup policy, approved encrypted retention destination and independent recovery drill remain operational work.

The public-schema archive excludes PostgreSQL roles/grants, provider configuration and secrets, Clerk identities, private object-storage files, Redis holds, and the external Razorpay/Brevo state. Sequence values are restored by `pg_restore`; sequence advancement during concurrent production activity is not covered by the row fingerprint comparison. No application-startup or authenticated business-flow acceptance is claimed for the restored copy. Restore into a separate isolated environment first; reconnecting external services or replacing production requires a separate recovery decision. Keep Razorpay test mode and outbound workers disabled during recovery validation.
