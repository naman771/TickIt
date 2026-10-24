#!/usr/bin/env python3
"""Read-only public-schema snapshot -> disposable local PostgreSQL restore.

DATABASE_URL is private input. No application, email or payment worker is started.
Only sanitized counts and table fingerprints are printed; temporary data is deleted.
"""
import getpass
import hashlib
import json
import os
from pathlib import Path
import re
import select
import subprocess
import tempfile
from urllib.parse import parse_qs, unquote, urlsplit
import uuid


class DrillError(Exception):
    pass


def source_environment(url):
    try:
        parsed = urlsplit(url)
        if (parsed.scheme not in ("postgres", "postgresql") or not parsed.hostname
                or not parsed.username or not parsed.path.strip("/")):
            raise ValueError()
        options = parse_qs(parsed.query, strict_parsing=True)
        if set(options) - {"sslmode", "channel_binding"}:
            raise ValueError()
        sslmode = options.get("sslmode", ["require"])[0]
        if sslmode not in ("require", "verify-ca", "verify-full"):
            raise ValueError()
        if parsed.hostname.endswith(".neon.tech") and "-pooler." in parsed.hostname:
            raise DrillError("Use DATABASE_URL_UNPOOLED: snapshot export and pg_dump require a direct Neon connection")
        result = clean_environment()
        result.update(PGHOST=parsed.hostname, PGPORT=str(parsed.port or 5432),
                      PGUSER=unquote(parsed.username),
                      PGPASSWORD=unquote(parsed.password or ""),
                      PGDATABASE=unquote(parsed.path[1:]), PGSSLMODE=sslmode,
                      PGOPTIONS="-c default_transaction_read_only=on -c statement_timeout=120000 -c timezone=UTC")
        if "channel_binding" in options:
            result["PGCHANNELBINDING"] = options["channel_binding"][0]
        return result
    except (ValueError, TypeError):
        raise DrillError("DATABASE_URL must be a PostgreSQL URL with TLS; unsupported connection options") from None


def clean_environment():
    # Do not let inherited PGHOST/PGSERVICE/PGOPTIONS redirect the local restore.
    env = {k: v for k, v in os.environ.items() if not k.startswith("PG")}
    env.update(PGCONNECT_TIMEOUT="15", PGPASSFILE=os.devnull, PSQLRC=os.devnull)
    return env


def local_environment(database):
    if not re.fullmatch(r"tickit_restore_[0-9a-f]{32}", database):
        raise DrillError("Refusing a restore target outside the generated disposable namespace")
    env = clean_environment()
    env.update(PGHOST="/tmp", PGPORT="5432", PGUSER=getpass.getuser(),
               PGDATABASE=database, PGSSLMODE="disable", PGOPTIONS="-c timezone=UTC")
    return env


def run(command, env, label, sql=None):
    try:
        result = subprocess.run(command, env=env, input=sql, text=True,
                                capture_output=True, timeout=180)
    except (OSError, subprocess.TimeoutExpired):
        raise DrillError(label + " could not complete; credentials and provider output withheld") from None
    if result.returncode:
        raise DrillError(label + " failed; credentials and provider output withheld")
    return result.stdout.strip()


def query(env, sql, snapshot=None):
    start = "BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;\n"
    if snapshot:
        if not re.fullmatch(r"[0-9A-Fa-f]+-[0-9A-Fa-f]+-[0-9]+", snapshot):
            raise DrillError("Unexpected exported snapshot identifier")
        start += "SET TRANSACTION SNAPSHOT '" + snapshot + "';\n"
    return run(["psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1"], env,
               "Read-only manifest query", start + sql + ";\nCOMMIT;\n")


def identifier(value):
    return '"' + value.replace('"', '""') + '"'


TABLES = """SELECT COALESCE(json_agg(tablename ORDER BY tablename), '[]'::json)
FROM pg_tables WHERE schemaname='public'"""


def manifest(env, tables, snapshot=None):
    selects = []
    for table in tables:
        # Hash every column, including credentials, without printing private row data.
        selects.append("SELECT json_build_object('table', '" + table.replace("'", "''")
                       + "', 'rows', count(*), 'digest', md5(COALESCE("
                       + "string_agg(row_hash, '' ORDER BY row_hash), ''))) AS item FROM "
                       + "(SELECT md5(row_to_json(t)::text) AS row_hash FROM public."
                       + identifier(table) + " t) hashes")
    if not selects:
        raise DrillError("Source has no public tables")
    sql = "SELECT json_agg(item ORDER BY item->>'table') FROM (" + " UNION ALL ".join(selects) + ") items"
    return json.loads(query(env, sql, snapshot))


def drill():
    source = source_environment(os.environ.get("DATABASE_URL_UNPOOLED") or os.environ.get("DATABASE_URL", ""))
    database = "tickit_restore_" + uuid.uuid4().hex
    target = local_environment(database)
    admin = {**target, "PGDATABASE": "postgres"}
    created = False
    session = None
    try:
        run(["psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1", "-c", "SELECT 1"],
            admin, "Local PostgreSQL preflight")
        with tempfile.TemporaryDirectory(prefix="tickit-restore-") as directory:
            archive = Path(directory) / "snapshot.dump"
            session = subprocess.Popen(["psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1"],
                                       env=source, stdin=subprocess.PIPE,
                                       stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                       text=True)
            session.stdin.write("BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;\n"
                                "SET LOCAL idle_in_transaction_session_timeout='240s';\n"
                                "SELECT pg_export_snapshot();\n")
            session.stdin.flush()
            # libpq connect timeout and a bounded read prevent a stalled snapshot export.
            if not select.select([session.stdout], [], [], 30)[0]:
                raise DrillError("Snapshot export timed out")
            snapshot = session.stdout.readline().strip()
            if not re.fullmatch(r"[0-9A-Fa-f]+-[0-9A-Fa-f]+-[0-9]+", snapshot):
                raise DrillError("Snapshot export failed")
            tables = json.loads(query(source, TABLES, snapshot))
            if "flyway_schema_history" not in tables:
                raise DrillError("Source is missing TickIt's Flyway history")
            before = manifest(source, tables, snapshot)
            print("PASS consistent read-only source manifest", flush=True)
            run(["pg_dump", "--format=custom", "--schema=public", "--no-owner",
                 "--no-privileges", "--lock-wait-timeout=10000", "--snapshot=" + snapshot,
                 "--file=" + str(archive)], source, "Read-only snapshot dump")
            session.stdin.write("ROLLBACK;\n\\q\n")
            session.stdin.flush()
            session.wait(timeout=15)
            checksum = hashlib.sha256(archive.read_bytes()).hexdigest()
            run(["createdb", "--maintenance-db=postgres", "--template=template0", database],
                admin, "Disposable local database creation")
            created = True
            # template0 contains an empty public schema; the archive recreates it.
            run(["psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1", "-c", "DROP SCHEMA public"],
                target, "Disposable local schema preparation")
            run(["pg_restore", "--exit-on-error", "--no-owner", "--no-privileges",
                 "--dbname=" + database, str(archive)], target, "Disposable local restore")
            restored_tables = json.loads(query(target, TABLES))
            after = manifest(target, restored_tables)
            if tables != restored_tables or before != after:
                raise DrillError("Restored table inventory or row fingerprints differ from the snapshot")
            print(json.dumps({"result": "PASS", "scope": "public schema",
                              "tables": len(tables), "rows": sum(t["rows"] for t in before),
                              "allRowFingerprintsMatch": True, "archiveSha256": checksum,
                              "applicationStarted": False, "sourceReadOnly": True}), flush=True)
    finally:
        if session:
            if session.poll() is None:
                session.terminate()
                try:
                    session.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    session.kill()
                    session.wait()
            session.stdin.close()
            session.stdout.close()
        if created:
            run(["dropdb", "--maintenance-db=postgres", database], admin,
                "Disposable local database cleanup")
            print("PASS disposable local database and temporary archive removed", flush=True)


if __name__ == "__main__":
    try:
        drill()
    except DrillError as error:
        print("FAIL " + str(error))
        raise SystemExit(1)
    except (OSError, subprocess.SubprocessError):
        # Never echo a DSN, subprocess environment, SQL data or provider error body.
        print("FAIL backup/restore drill; private connection details withheld")
        raise SystemExit(1)
