"""Read-only baseline audit; writes reports only under the Memento workspace.

Run from Memento with python -B tools/audit_reference_projects.py.
No old build scripts or application tests are executed. SQLite assets must have
no live sidecars: immutable mode is suitable only for these frozen bundle files.
"""
from __future__ import annotations

import hashlib
import argparse
import json
from pathlib import Path
import re
import sqlite3
import subprocess
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]
PROJECTS = {"fr": Path("L:/Words"), "es": Path("L:/Words_esp")}
ASSETS = {"fr": "french_content.db", "es": "spanish_content.db"}
PACKAGES = {"fr": "frenchvocab", "es": "spanishvocab"}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def quote_identifier(name: str) -> str:
    return '"' + name.replace('"', '""') + '"'


def git_state(project: Path) -> dict:
    result = subprocess.run(
        ["git", "--no-optional-locks", "-c", "core.fsmonitor=false", "-C", str(project),
         "status", "--porcelain=v1", "--untracked-files=normal"],
        capture_output=True, text=True, check=False,
    )
    return {"exit_code": result.returncode, "porcelain": result.stdout, "stderr": result.stderr}


def audit_database(path: Path) -> dict:
    sidecars = [str(Path(str(path) + suffix)) for suffix in ("-wal", "-shm", "-journal")
                if Path(str(path) + suffix).exists()]
    if sidecars:
        raise RuntimeError(f"Bundle has SQLite sidecars; freeze a consistent input first: {sidecars}")
    initial_hash = sha256(path)
    connection = sqlite3.connect(path.as_uri() + "?mode=ro&immutable=1", uri=True)
    try:
        connection.execute("PRAGMA query_only=ON")
        schemas = dict(connection.execute(
            "SELECT name, sql FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name"
        ))
        counts = {name: connection.execute(f"SELECT COUNT(*) FROM {quote_identifier(name)}").fetchone()[0]
                  for name in schemas}
        coverage = {}
        for table in ("sense", "example"):
            columns = {row[1] for row in connection.execute(f"PRAGMA table_info({quote_identifier(table)})")}
            coverage[table] = {
                name: connection.execute(
                    f"SELECT COUNT(*) FROM {quote_identifier(table)} WHERE "
                    f"{quote_identifier(name)} IS NULL OR length(trim({quote_identifier(name)}))=0"
                ).fetchone()[0]
                for name in ("chinese", "english", "french", "spanish") if name in columns
            }
        uid_digest = hashlib.sha256()
        for (uid,) in connection.execute("SELECT lexeme_uid FROM lexeme ORDER BY lexeme_uid"):
            uid_digest.update((uid + "\n").encode("utf-8"))
        membership_digest = hashlib.sha256()
        for row in connection.execute("SELECT book_id, lexeme_uid, sort_order FROM book_lexeme ORDER BY book_id,sort_order,lexeme_uid"):
            membership_digest.update((json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8"))
        report = {
            "path": str(path), "bytes": path.stat().st_size, "sha256": initial_hash,
            "sqlite_user_version": connection.execute("PRAGMA user_version").fetchone()[0],
            "integrity_check": [row[0] for row in connection.execute("PRAGMA integrity_check")],
            "foreign_key_check": list(connection.execute("PRAGMA foreign_key_check")),
            "content_meta": dict(connection.execute("SELECT key,value FROM content_meta ORDER BY key")),
            "counts": counts, "schema": schemas, "empty_translation_fields": coverage,
            "uid_prefix_counts": dict(connection.execute("SELECT substr(lexeme_uid,1,instr(lexeme_uid,':')),COUNT(*) FROM lexeme GROUP BY 1")),
            "lexeme_languages": dict(connection.execute("SELECT language,COUNT(*) FROM lexeme GROUP BY language")),
            "uid_set_sha256": uid_digest.hexdigest(),
            "ordered_book_membership_sha256": membership_digest.hexdigest(),
            "lexemes_without_sense": connection.execute("SELECT COUNT(*) FROM lexeme l WHERE NOT EXISTS (SELECT 1 FROM sense s WHERE s.lexeme_uid=l.lexeme_uid)").fetchone()[0],
            "lexemes_without_pronunciation": connection.execute("SELECT COUNT(*) FROM lexeme l WHERE NOT EXISTS (SELECT 1 FROM pronunciation p WHERE p.lexeme_uid=l.lexeme_uid)").fetchone()[0],
            "audio_asset_count": connection.execute("SELECT COUNT(*) FROM pronunciation WHERE audio_asset IS NOT NULL AND length(trim(audio_asset))>0").fetchone()[0],
            "books": [{"id": row[0], "title": row[1], "member_count": row[2]}
                      for row in connection.execute("SELECT b.book_id,b.title,COUNT(m.lexeme_uid) FROM vocabulary_book b LEFT JOIN book_lexeme m ON m.book_id=b.book_id GROUP BY b.book_id,b.title,b.sort_order ORDER BY b.sort_order")],
        }
    finally:
        connection.close()
    report["unchanged_after_read"] = sha256(path) == initial_hash
    return report


def baseline_files(project: Path) -> list[Path]:
    files = set()
    for folder in ("app/src", "gradle/wrapper"):
        files.update(path for path in (project / folder).rglob("*") if path.is_file())
    for name in ("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "local.properties",
                 "gradlew", "gradlew.bat", "app/build.gradle.kts", "app/proguard-rules.pro",
                 "tools/fsrs-reference/generate-fixtures.cjs", "tools/fsrs-reference/package/LICENSE"):
        if (project / name).is_file():
            files.add(project / name)
    for folder in ("tools", "tools/lexicon"):
        for pattern in ("*.ps1", "*.py", "*.sql"):
            files.update((project / folder).glob(pattern))
    return sorted(files)


def normalized_code(path: Path) -> str:
    source = path.read_text(encoding="utf-8").replace("\r\n", "\n")
    return re.sub(r"com\.scott\.(frenchvocab|spanishvocab)", "com.scott.memento", source)


def verify_baseline() -> None:
    baseline = json.loads((ROOT / "docs/qa/baseline/reference-audit.json").read_text(encoding="utf-8"))
    checks = {}
    for language, record in baseline["projects"].items():
        project = PROJECTS[language]
        changed = [row["relative_path"] for row in record["file_manifest"]
                   if not (project / row["relative_path"]).is_file()
                   or sha256(project / row["relative_path"]) != row["sha256"]]
        prior_names = {row["relative_path"] for row in record["file_manifest"]}
        current_names = {str(path.relative_to(project)) for path in baseline_files(project)}
        checks[language] = {"changed_files": changed,
                            "added_input_files": sorted(current_names - prior_names),
                            "git_unchanged": git_state(project) == record["git_before"]}
    passed = all(not row["changed_files"] and not row["added_input_files"] and row["git_unchanged"]
                 for row in checks.values())
    result = {"checked_at_utc": datetime.now(timezone.utc).isoformat(),
              "scope": "manifested input files and git porcelain; ignored caches are not exhaustively audited",
              "passed": passed, "projects": checks}
    (ROOT / "docs/qa/baseline/reference-preservation.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if not passed:
        raise SystemExit(1)


def main() -> None:
    if ROOT != Path("L:/Memento").resolve():
        raise RuntimeError("This audit must reside in L:/Memento")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify-baseline", action="store_true", help="Verify recorded inputs without rebuilding the baseline")
    args = parser.parse_args()
    if args.verify_baseline:
        verify_baseline()
        return
    report = {"generated_at_utc": datetime.now(timezone.utc).isoformat(),
              "kind": "live_read_only_reference_audit", "application_tests_executed": False,
              "projects": {}}
    for language, project in PROJECTS.items():
        before = git_state(project)
        manifest = [{"relative_path": str(path.relative_to(project)), "bytes": path.stat().st_size,
                     "sha256": sha256(path)} for path in baseline_files(project)]
        tests = {}
        for source_set in ("test", "androidTest"):
            tests[source_set] = [{"path": str(path.relative_to(project)),
                                  "methods": re.findall(r"@Test\s+fun\s+(\w+)", path.read_text(encoding="utf-8"))}
                                 for path in sorted((project / "app/src" / source_set).rglob("*.kt"))]
        database = audit_database(project / "app/src/main/assets" / ASSETS[language])
        report["projects"][language] = {
            "root": str(project), "git_before": before, "git_after": git_state(project),
            "file_manifest": manifest, "tests_static_inventory": tests, "formal_content": database,
            "curated_json_inventory": [{"name": p.name, "bytes": p.stat().st_size}
                                       for p in sorted((project / "data/curated").glob("*.json"))],
        }
    report["shared_code_comparison_after_package_normalization"] = {}
    for relative in ("domain/fsrs/FsrsScheduler.kt", "data/user/SessionPlanner.kt"):
        paths = [PROJECTS[lang] / f"app/src/main/java/com/scott/{PACKAGES[lang]}" / relative for lang in PROJECTS]
        report["shared_code_comparison_after_package_normalization"][relative] = normalized_code(paths[0]) == normalized_code(paths[1])
    plan = ROOT / "MULTILINGUAL_REFACTOR_PLAN.md"
    references = re.findall(r"^- `(L:\\Words(?:_esp)?\\[^`]+)`", plan.read_text(encoding="utf-8"), re.MULTILINE)
    report["plan_reference_checks"] = [{"path": path, "exists": Path(path).is_file()} for path in references]
    checks = []
    for lang, project in report["projects"].items():
        db = project["formal_content"]
        checks.extend((db["integrity_check"] == ["ok"], not db["foreign_key_check"], db["unchanged_after_read"],
                       not db["lexemes_without_sense"], not db["lexemes_without_pronunciation"],
                       project["git_before"]["exit_code"] == 0,
                       project["git_before"] == project["git_after"]))
    report["audit_checks_passed"] = all(checks)
    destination = ROOT / "docs/qa/baseline/reference-audit.json"
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"report": str(destination), "audit_checks_passed": report["audit_checks_passed"],
                      "projects": {lang: {"counts": item["formal_content"]["counts"],
                                          "sha256": item["formal_content"]["sha256"],
                                          "source_files": len(item["file_manifest"]),
                                          "tests": {key: sum(len(test["methods"]) for test in tests)
                                                    for key, tests in item["tests_static_inventory"].items()}}
                                   for lang, item in report["projects"].items()},
                      "missing_plan_references": [row["path"] for row in report["plan_reference_checks"] if not row["exists"]]},
                     ensure_ascii=False, indent=2))
    if not report["audit_checks_passed"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
