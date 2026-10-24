"""Safety checks plus an opt-in real PostgreSQL restore and failure cleanup test."""
import contextlib
import importlib.util
import io
import os
from pathlib import Path
import unittest
from unittest.mock import patch
import uuid

SPEC = importlib.util.spec_from_file_location(
    "backup_restore", Path(__file__).resolve().parents[2] / "scripts/verify-backup-restore.py")
drill = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(drill)


class SafetyTests(unittest.TestCase):
    def test_restore_cannot_use_an_existing_database(self):
        for name in ("postgres", "tickit", "tickit_test", "tickit_restore_existing"):
            with self.assertRaises(drill.DrillError):
                drill.local_environment(name)

    def test_inherited_pg_settings_cannot_redirect_restore(self):
        with patch.dict(os.environ, {"PGHOST": "production.example", "PGSERVICE": "production",
                                    "PGOPTIONS": "-c session_replication_role=replica"}):
            env = drill.local_environment("tickit_restore_" + uuid.uuid4().hex)
        self.assertEqual(env["PGHOST"], "/tmp")
        self.assertNotIn("PGSERVICE", env)
        self.assertNotIn("replica", env["PGOPTIONS"])

    def test_source_rejects_insecure_or_redirecting_options(self):
        for value in ("", "https://example.com/db", "postgresql://u:p@host/db?sslmode=disable",
                      "postgresql://u:p@host/db?service=production",
                      "postgresql://u:p@ep-test-pooler.region.aws.neon.tech/db?sslmode=require"):
            with self.assertRaises(drill.DrillError):
                drill.source_environment(value)


@unittest.skipUnless(os.environ.get("RUN_BACKUP_RESTORE_TESTS") == "true",
                     "requires disposable local PostgreSQL databases")
class RestoreTests(unittest.TestCase):
    def setUp(self):
        self.database = "tickit_restore_" + uuid.uuid4().hex
        self.env = drill.local_environment(self.database)
        self.admin = {**self.env, "PGDATABASE": "postgres"}
        drill.run(["createdb", "--maintenance-db=postgres", self.database], self.admin, "Fixture creation")
        drill.run(["psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1"], self.env, "Fixture schema", """
            CREATE TABLE flyway_schema_history(version text PRIMARY KEY);
            INSERT INTO flyway_schema_history VALUES ('13');
            CREATE TABLE parent(id bigserial PRIMARY KEY, value text);
            INSERT INTO parent(value) VALUES (NULL), ('test ünicode'), ('same'), ('same');
            CREATE TABLE "quoted'table"(id int PRIMARY KEY REFERENCES parent(id), secret bytea);
            INSERT INTO "quoted'table" VALUES (1, decode('00ff', 'hex'));
        """)

    def tearDown(self):
        drill.run(["dropdb", "--maintenance-db=postgres", self.database], self.admin, "Fixture cleanup")

    def databases(self):
        return drill.query(self.admin, "SELECT json_agg(datname ORDER BY datname) FROM pg_database")

    def test_round_trip_matches_all_rows_and_leaves_no_database(self):
        before = self.databases()
        source = {**self.env, "PGOPTIONS": "-c default_transaction_read_only=on -c timezone=UTC"}
        output = io.StringIO()
        with patch.object(drill, "source_environment", return_value=source), contextlib.redirect_stdout(output):
            drill.drill()
        self.assertIn('"allRowFingerprintsMatch": true', output.getvalue())
        self.assertIn('"rows": 6', output.getvalue())
        self.assertEqual(before, self.databases())
        self.assertNotIn("ünicode", output.getvalue())

    def test_failed_restore_still_removes_database_and_archive(self):
        before = self.databases()
        original = drill.run
        archives = []

        def fail_restore(command, *args, **kwargs):
            if command[0] == "pg_restore":
                archives.append(Path(command[-1]))
                raise drill.DrillError("Injected restore failure")
            return original(command, *args, **kwargs)

        source = {**self.env, "PGOPTIONS": "-c default_transaction_read_only=on -c timezone=UTC"}
        with patch.object(drill, "source_environment", return_value=source), \
                patch.object(drill, "run", side_effect=fail_restore), \
                contextlib.redirect_stdout(io.StringIO()), self.assertRaises(drill.DrillError):
            drill.drill()
        self.assertEqual(before, self.databases())
        self.assertEqual(len(archives), 1)
        self.assertFalse(archives[0].exists())

    def test_modified_restored_row_is_rejected_and_cleaned_up(self):
        before = self.databases()
        original = drill.run

        def corrupt_restore(command, env, label, sql=None):
            result = original(command, env, label, sql)
            if command[0] == "pg_restore":
                original(["psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1", "-c",
                          "UPDATE parent SET value='changed' WHERE id=2"], env, "Fixture corruption")
            return result

        source = {**self.env, "PGOPTIONS": "-c default_transaction_read_only=on -c timezone=UTC"}
        with patch.object(drill, "source_environment", return_value=source), \
                patch.object(drill, "run", side_effect=corrupt_restore), \
                contextlib.redirect_stdout(io.StringIO()), self.assertRaisesRegex(drill.DrillError, "fingerprints differ"):
            drill.drill()
        self.assertEqual(before, self.databases())


if __name__ == "__main__":
    unittest.main()
