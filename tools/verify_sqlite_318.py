"""Exercise current user schemas and five UPDATE/INSERT sites on official SQLite 3.18.

This uses the explicit Windows x64 DLL via ctypes, never Python's sqlite3 module.
It executes the SQL equivalent of SqliteWrites, not Android's binding implementation.
All databases are isolated :memory: instances; only the JSON report is written.
"""
from __future__ import annotations

import argparse
import ctypes as C
import hashlib
import json
import platform
import re
import struct
import sys
import traceback
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DOWNLOAD_URL = "https://www.sqlite.org/2017/sqlite-dll-win64-x64-3180000.zip"
RELEASE_URL = "https://www.sqlite.org/releaselog/3_18_0.html"
ARCHIVE_SHA256 = "8d399f403de99dcb85bd6ca1d4e1b00c7ac10bbcfac0de4ba7b9d52568c968cf"
SOURCE_ID = "2017-03-28 18:48:43 424a0d380332858ee55bdebc4af3789f74e70a2b3ba1cf29d84b9b4bcf3e2e37"
SOURCE_PATHS = [
    ROOT / "app/src/main/java/com/scott/memento/data/user/StudyRepository.kt",
    ROOT / "app/src/main/java/com/scott/memento/data/migration/LegacyImporter.kt",
    ROOT / "app/src/main/java/com/scott/memento/data/user/SqliteWrites.kt",
]
SCHEMAS = [ROOT / "schemas" / name for name in
           ("user-v1.sql", "user-v2-upgrade.sql", "user-v3-upgrade.sql")]
REPORT: dict = {"checks": [], "sites": [], "scope": {
    "engine": "official SQLite 3.18.0 Windows x64 DLL loaded by ctypes",
    "android_26_device_or_emulator_verified": False,
    "android_binding_verified_by_this_script": False,
    "databases": "isolated :memory: instances only",
    "limitation": "SQL/schema evidence; does not execute Kotlin, Android bindings, SQLiteOpenHelper, or Android lifecycle",
}}


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def require(name: str, condition: bool, detail=None) -> None:
    REPORT["checks"].append({"name": name, "passed": bool(condition), "detail": detail})
    if not condition:
        raise AssertionError(name)


class SqlError(RuntimeError):
    def __init__(self, code: int, message: str, sql: str):
        super().__init__(f"SQLite rc={code}: {message}")
        self.code, self.message, self.sql = code, message, sql

    def record(self) -> dict:
        return {"code": self.code, "message": self.message, "sql": self.sql}


class Engine:
    def __init__(self, dll: Path):
        self.lib = C.CDLL(str(dll.resolve()))
        declarations = {
            "sqlite3_libversion": ([], C.c_char_p),
            "sqlite3_sourceid": ([], C.c_char_p),
            "sqlite3_open": ([C.c_char_p, C.POINTER(C.c_void_p)], C.c_int),
            "sqlite3_close": ([C.c_void_p], C.c_int),
            "sqlite3_errmsg": ([C.c_void_p], C.c_char_p),
            "sqlite3_exec": ([C.c_void_p, C.c_char_p, C.c_void_p, C.c_void_p, C.c_void_p], C.c_int),
            "sqlite3_prepare_v2": ([C.c_void_p, C.c_char_p, C.c_int, C.POINTER(C.c_void_p), C.POINTER(C.c_char_p)], C.c_int),
            "sqlite3_bind_parameter_count": ([C.c_void_p], C.c_int),
            "sqlite3_bind_null": ([C.c_void_p, C.c_int], C.c_int),
            "sqlite3_bind_int64": ([C.c_void_p, C.c_int, C.c_int64], C.c_int),
            "sqlite3_bind_double": ([C.c_void_p, C.c_int, C.c_double], C.c_int),
            "sqlite3_bind_text": ([C.c_void_p, C.c_int, C.c_char_p, C.c_int, C.c_void_p], C.c_int),
            "sqlite3_step": ([C.c_void_p], C.c_int),
            "sqlite3_finalize": ([C.c_void_p], C.c_int),
            "sqlite3_changes": ([C.c_void_p], C.c_int),
            "sqlite3_get_autocommit": ([C.c_void_p], C.c_int),
            "sqlite3_column_count": ([C.c_void_p], C.c_int),
            "sqlite3_column_name": ([C.c_void_p, C.c_int], C.c_char_p),
            "sqlite3_column_type": ([C.c_void_p, C.c_int], C.c_int),
            "sqlite3_column_int64": ([C.c_void_p, C.c_int], C.c_int64),
            "sqlite3_column_double": ([C.c_void_p, C.c_int], C.c_double),
            "sqlite3_column_text": ([C.c_void_p, C.c_int], C.c_void_p),
        }
        for name, (args, result) in declarations.items():
            function = getattr(self.lib, name)
            function.argtypes, function.restype = args, result


class Database:
    def __init__(self, engine: Engine):
        self.api = engine.lib
        self.handle = C.c_void_p()
        self._ok(self.api.sqlite3_open(b":memory:", C.byref(self.handle)), "open :memory:")

    def _ok(self, rc: int, sql: str) -> None:
        if rc != 0:
            raise SqlError(rc, self.api.sqlite3_errmsg(self.handle).decode("utf-8"), sql)

    def script(self, sql: str) -> None:
        self._ok(self.api.sqlite3_exec(self.handle, sql.encode("utf-8"), None, None, None), sql)

    def execute(self, sql: str, args=()) -> tuple[list[dict], int]:
        statement = C.c_void_p()
        tail = C.c_char_p()
        encoded_sql = sql.encode("utf-8")  # Keep the tail pointer's input buffer alive.
        self._ok(self.api.sqlite3_prepare_v2(self.handle, encoded_sql, -1,
                                           C.byref(statement), C.byref(tail)), sql)
        try:
            assert not tail.value or not tail.value.strip(), "execute accepts one statement"
            assert self.api.sqlite3_bind_parameter_count(statement) == len(args), "binding count mismatch"
            for i, value in enumerate(args, 1):
                if value is None:
                    rc = self.api.sqlite3_bind_null(statement, i)
                elif isinstance(value, int):
                    rc = self.api.sqlite3_bind_int64(statement, i, value)
                elif isinstance(value, float):
                    rc = self.api.sqlite3_bind_double(statement, i, value)
                elif isinstance(value, str):
                    encoded = value.encode("utf-8")
                    rc = self.api.sqlite3_bind_text(statement, i, encoded, len(encoded), C.c_void_p(-1))
                else:
                    raise TypeError(type(value))
                self._ok(rc, sql)
            rows = []
            while True:
                rc = self.api.sqlite3_step(statement)
                if rc == 101:  # SQLITE_DONE
                    return rows, self.api.sqlite3_changes(self.handle)
                if rc != 100:  # SQLITE_ROW
                    self._ok(rc, sql)
                row = {}
                for i in range(self.api.sqlite3_column_count(statement)):
                    name = self.api.sqlite3_column_name(statement, i).decode("utf-8")
                    kind = self.api.sqlite3_column_type(statement, i)
                    if kind == 5:
                        value = None
                    elif kind == 1:
                        value = self.api.sqlite3_column_int64(statement, i)
                    elif kind == 2:
                        value = self.api.sqlite3_column_double(statement, i)
                    elif kind == 3:
                        value = C.string_at(self.api.sqlite3_column_text(statement, i)).decode("utf-8")
                    else:
                        raise TypeError(f"Unexpected SQLite column kind {kind}")
                    row[name] = value
                rows.append(row)
        finally:
            self.api.sqlite3_finalize(statement)

    def close(self) -> None:
        self._ok(self.api.sqlite3_close(self.handle), "close")


def sites_from_source() -> list[dict]:
    pattern = re.compile(
        r'SqliteWrites\.updateOrInsert\(db,"(?P<table>\w+)",ContentValues\(\)\.apply \{'
        r'(?P<values>.*?)\},(?P<sql>"""UPDATE.*?"""|"UPDATE[^\"]*")', re.S)
    sites = []
    for path in SOURCE_PATHS[:2]:
        source = path.read_text(encoding="utf-8")
        for match in pattern.finditer(source):
            literal = match.group("sql")
            sql = literal[3:-3] if literal.startswith('"""') else literal[1:-1]
            sql = " ".join(sql.split())
            table = match.group("table")
            columns = re.findall(r'put\("(\w+)"', match.group("values"))
            assert len(columns) == len(set(columns)), "duplicate insert column"
            assert re.fullmatch(r'UPDATE \w+ SET [\w=?,+ ]+ WHERE [\w=? ]+', sql), sql
            bindings = re.findall(r'(\w+)\s*=\s*\?', sql)
            assert sql.count("?") == len(bindings)
            label = "configure" if path.name == "StudyRepository.kt" else {
                "legacy_session_history": "import_session", "migration_ledger": "import_ledger",
                "language_settings": "import_preferences", "plan_refresh_request": "import_refresh",
            }[table]
            sites.append({"name": label, "table": table, "update_sql": sql,
                          "insert_columns": columns, "bound_columns": bindings,
                          "source": str(path.relative_to(ROOT)).replace("\\", "/"),
                          "source_line": source.count("\n", 0, match.start()) + 1,
                          "insert_sql": f"INSERT INTO {table}({','.join(columns)}) VALUES({','.join('?' for _ in columns)})"})
    require("five_current_source_sites_extracted", len(sites) == 5 and
            {s["name"] for s in sites} == {"configure", "import_session", "import_ledger", "import_preferences", "import_refresh"})
    REPORT["sites"] = sites
    return sites


def compatible_write(db: Database, site: dict, values: dict) -> str:
    """SQL equivalent of helper; Android DatabaseUtils binding is outside this test."""
    assert db.api.sqlite3_get_autocommit(db.handle) == 0, "requires caller transaction"
    _, count = db.execute(site["update_sql"], [values[col] for col in site["bound_columns"]])
    assert count in (0, 1), "requires unique row key"
    if count == 0:
        db.execute(site["insert_sql"], [values[col] for col in site["insert_columns"]])
        return "inserted"
    return "updated"


def fresh_database(engine: Engine, record_schema_checks=False) -> Database:
    db = Database(engine)
    for version, path in enumerate(SCHEMAS, 1):
        db.script(path.read_text(encoding="utf-8"))
        if record_schema_checks:
            rows, _ = db.execute("PRAGMA user_version")
            require(f"schema_v{version}_applied", rows == [{"user_version": version}])
    db.script("INSERT INTO target_language_registry VALUES('fr'); INSERT INTO target_language_registry VALUES('es');"
              "INSERT INTO app_settings(id,study_zone_id) VALUES(1,'Asia/Shanghai');"
              "INSERT INTO migration_batch(source_identity,input_sha256,input_schema_version,imported_at,report_json)"
              " VALUES('legacy:fr:sqlite318-test','input-one',2,1000,'{}');"
              "INSERT INTO migration_batch(source_identity,input_sha256,input_schema_version,imported_at,report_json)"
              " VALUES('legacy:fr:sqlite318-test','input-two',2,2000,'{}');")
    if record_schema_checks:
        require("foreign_keys_enabled", db.execute("PRAGMA foreign_keys")[0] == [{"foreign_keys": 1}])
    return db


def fixture(name: str, updated=False) -> dict:
    base = {"target_language": "fr"}
    if name in ("configure", "import_preferences"):
        base.update(study_mode="REVIEW_ALL" if updated else "STANDARD",
                    daily_target=None if updated else 20, book_id="fr:book-b" if updated else "fr:book-a")
        if name == "import_preferences":
            base.update(auto_play="ALL" if updated else "NEW_ONLY", tts_fallback=0 if updated else 1,
                        show_reading=0 if updated else 1)
    elif name == "import_session":
        base.update(source_identity="legacy:fr:sqlite318-test", source_session_id="7",
                    payload_json='{"position":2,"note":"é词"}' if updated else '{"position":1}',
                    batch_id=2 if updated else 1)
    elif name == "import_ledger":
        base.update(source_identity="legacy:fr:sqlite318-test", record_type="learning_card", source_record_id="fr:1",
                    target_record_id="fr:new" if updated else "fr:old", record_sha256="hash-two" if updated else "hash-one",
                    batch_id=2 if updated else 1)
    elif name == "import_refresh":
        base.update(requested_at=2000 if updated else 1000)
    else:
        raise KeyError(name)
    return base


def invalid_values(name: str) -> dict:
    values = fixture(name, True)
    if name == "configure":
        values.update(study_mode="STANDARD", daily_target=0)
    elif name in ("import_session", "import_ledger"):
        values["batch_id"] = 999999
    elif name == "import_preferences":
        values["tts_fallback"] = 2
    else:
        values["requested_at"] = None
    return values


def table_rows(db: Database, table: str) -> list[dict]:
    return db.execute(f"SELECT rowid AS preserved_rowid,* FROM {table} ORDER BY rowid")[0]


def whole_snapshot(db: Database, sites: list[dict]) -> dict:
    return {table: table_rows(db, table) for table in sorted({"app_settings"} | {s["table"] for s in sites})}


def constraint_rollback(db: Database, site: dict, inserting: bool) -> dict:
    before = table_rows(db, site["table"])
    app_before = table_rows(db, "app_settings")
    db.script("BEGIN IMMEDIATE; UPDATE app_settings SET revision=revision+1 WHERE id=1;")
    if inserting:
        db.execute(f"DELETE FROM {site['table']}")
    error = None
    try:
        compatible_write(db, site, invalid_values(site["name"]))
    except SqlError as caught:
        error = caught
    finally:
        db.script("ROLLBACK")
    label = "insert" if inserting else "update"
    require(f"{site['name']}_{label}_constraint_propagates", error is not None and error.code == 19,
            error.record() if error else None)
    require(f"{site['name']}_{label}_failure_rolls_back", table_rows(db, site["table"]) == before and
            table_rows(db, "app_settings") == app_before)
    return error.record()


def test_site(engine: Engine, site: dict) -> None:
    db = fresh_database(engine)
    try:
        db.script("BEGIN IMMEDIATE")
        inserted = compatible_write(db, site, fixture(site["name"]))
        db.script("COMMIT")
        before = table_rows(db, site["table"])
        require(f"{site['name']}_missing_row_insert", inserted == "inserted" and len(before) == 1)
        if site["name"] == "configure":
            db.script("UPDATE language_settings SET auto_play='ALL',tts_fallback=0,show_reading=0")
        updated_values = fixture(site["name"], True)
        db.script("BEGIN IMMEDIATE")
        branch = compatible_write(db, site, updated_values)
        db.script("COMMIT")
        after = table_rows(db, site["table"])
        require(f"{site['name']}_existing_row_update_preserves_rowid", branch == "updated" and len(after) == 1 and
                after[0]["preserved_rowid"] == before[0]["preserved_rowid"], {"before": before, "after": after})
        require(f"{site['name']}_updated_fields", all(after[0][col] == updated_values[col]
                for col in site["insert_columns"]))
        if site["table"] == "language_settings":
            require(f"{site['name']}_revision_increments_once", before[0]["revision"] == 1 and after[0]["revision"] == 2)
        if site["name"] == "configure":
            require("configure_retains_display_preferences", all(after[0][col] == value for col, value in
                    {"auto_play": "ALL", "tts_fallback": 0, "show_reading": 0}.items()))
        db.script("BEGIN IMMEDIATE")
        repeated_branch = compatible_write(db, site, updated_values)
        db.script("COMMIT")
        repeated = table_rows(db, site["table"])
        require(f"{site['name']}_matched_repeat_does_not_insert", repeated_branch == "updated" and len(repeated) == 1 and
                repeated[0]["preserved_rowid"] == before[0]["preserved_rowid"])
        if site["table"] == "language_settings":
            require(f"{site['name']}_repeated_update_revision_is_three", repeated[0]["revision"] == 3)
        site["constraint_errors"] = {
            "update": constraint_rollback(db, site, False), "insert": constraint_rollback(db, site, True)}
        require(f"{site['name']}_integrity_and_foreign_keys", db.execute("PRAGMA integrity_check")[0] == [{"integrity_check": "ok"}]
                and db.execute("PRAGMA foreign_key_check")[0] == [])
    finally:
        db.close()


def test_whole_transaction(engine: Engine, sites: list[dict], updating: bool) -> None:
    db = fresh_database(engine)
    try:
        if updating:
            db.script("BEGIN IMMEDIATE")
            for site in sites:
                compatible_write(db, site, fixture(site["name"]))
            db.script("COMMIT")
        before = whole_snapshot(db, sites)
        error = None
        db.script("BEGIN IMMEDIATE; UPDATE app_settings SET revision=revision+1 WHERE id=1;")
        try:
            for site in sites:
                compatible_write(db, site, fixture(site["name"], updating))
            refresh = next(s for s in sites if s["name"] == "import_refresh")
            compatible_write(db, refresh, invalid_values("import_refresh"))
        except SqlError as caught:
            error = caught
        finally:
            db.script("ROLLBACK")
        name = "updates" if updating else "inserts"
        require(f"all_five_{name}_roll_back_on_late_failure", error is not None and error.code == 19 and
                whole_snapshot(db, sites) == before, error.record() if error else None)
    finally:
        db.close()


def run(dll: Path) -> None:
    require("python_x64", C.sizeof(C.c_void_p) == 8)
    archive = dll.parent / "sqlite-dll-win64-x64-3180000.zip"
    require("official_download_archive_matches_recorded_sha256", sha(archive) == ARCHIVE_SHA256)
    binary = dll.read_bytes()
    pe_offset = struct.unpack_from("<I", binary, 0x3C)[0]
    require("dll_pe_is_windows_x64", binary[pe_offset:pe_offset + 4] == b"PE\0\0" and
            struct.unpack_from("<H", binary, pe_offset + 4)[0] == 0x8664)
    engine = Engine(dll)
    version = engine.lib.sqlite3_libversion().decode("utf-8")
    source_id = engine.lib.sqlite3_sourceid().decode("utf-8")
    REPORT["provenance"] = {"download_url": DOWNLOAD_URL, "official_release_url": RELEASE_URL,
        "archive": str(archive), "archive_sha256": sha(archive), "dll": str(dll), "dll_sha256": sha(dll),
        "runtime_version": version, "runtime_source_id": source_id,
        "official_release_source_id": SOURCE_ID,
        "archive_hash_note": "Observed downloaded-byte SHA-256, not an independently published official archive checksum"}
    require("runtime_is_sqlite_3_18_0", version == "3.18.0")
    require("runtime_source_id_matches_official_release", source_id == SOURCE_ID)
    helper = SOURCE_PATHS[2].read_text(encoding="utf-8")
    require("helper_equivalent_path_guarded", all(token in helper for token in
            ("check(db.inTransaction())", "executeUpdateDelete()", "check(updated in 0..1)",
             "if(updated==0) db.insertOrThrow(table,null,insertValues)")))
    sites = sites_from_source()
    db = fresh_database(engine, record_schema_checks=True)
    try:
        error = None
        sql = "INSERT INTO plan_refresh_request(target_language,requested_at) VALUES('fr',1000) " \
              "ON CONFLICT(target_language) DO UPDATE SET requested_at=excluded.requested_at"
        try:
            db.execute(sql)
        except SqlError as caught:
            error = caught
        require("modern_upsert_rejected_negative_control", error is not None and error.code == 1 and
                "syntax error" in error.message.lower(), error.record() if error else None)
        require("negative_control_did_not_write", table_rows(db, "plan_refresh_request") == [])
    finally:
        db.close()
    for site in sites:
        test_site(engine, site)
    test_whole_transaction(engine, sites, False)
    test_whole_transaction(engine, sites, True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dll", type=Path, default=ROOT / ".tools/sqlite-3.18/sqlite3.dll")
    parser.add_argument("--output", type=Path, default=ROOT / "docs/qa/ui-restoration/ui7/sqlite-318/result.json")
    args = parser.parse_args()
    dll, output = args.dll.resolve(), args.output.resolve()
    if not dll.is_relative_to(ROOT) or not output.is_relative_to(ROOT):
        parser.error("DLL and output must stay inside the Memento workspace")
    paths = SCHEMAS + SOURCE_PATHS + [Path(__file__).resolve()]
    baseline = {str(p.relative_to(ROOT)).replace("\\", "/"): sha(p) for p in paths}
    REPORT.update(started_at_utc=datetime.now(timezone.utc).isoformat(), source_sha256=baseline,
                  execution={"python": sys.executable, "python_version": sys.version, "platform": platform.platform(),
                             "arguments": sys.argv, "working_directory": str(Path.cwd())})
    try:
        run(dll)
        require("source_files_unchanged_during_verification", all(sha(p) == baseline[str(p.relative_to(ROOT)).replace("\\", "/")]
                for p in paths))
        REPORT["passed"] = True
    except Exception as error:
        REPORT.update(passed=False, failure={"type": type(error).__name__, "message": str(error),
                                             "traceback": traceback.format_exc()})
    REPORT.update(finished_at_utc=datetime.now(timezone.utc).isoformat(),
                  checks_total=len(REPORT["checks"]), checks_passed=sum(c["passed"] for c in REPORT["checks"]))
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(REPORT, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"passed": REPORT["passed"], "checks_passed": REPORT["checks_passed"],
                      "checks_total": REPORT["checks_total"], "report": str(output),
                      "runtime": REPORT.get("provenance", {}).get("runtime_version"),
                      "failure": REPORT.get("failure", {}).get("message")}, ensure_ascii=False))
    return 0 if REPORT["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
