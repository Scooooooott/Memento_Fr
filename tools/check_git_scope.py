"""Prepare and verify a local candidate file inventory, without touching Git history.

Uses an empty bare repository under .tools solely for Git's own ignore/attribute
matching and non-writing hash-object checks. It never initializes the project's
.git, stages files, commits, pushes, publishes, or reads the legacy Git indexes.
Existing source, assets and QA evidence are only read. Generated reports stay in
docs/repository/local, which is excluded from the candidate source set.
"""
from collections import Counter
from datetime import datetime, timezone
from hashlib import sha1, sha256
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "docs/repository/local"
SCRATCH = ROOT / ".tools/git-scope-check"
GIT_DIR = SCRATCH / "matcher.git"
SKIP_DIRECTORIES = {".tools", ".gradle", ".kotlin", ".git", "build", "__pycache__", ".agents", ".codex", ".aws"}
REQUIRED = (
    ".gitignore", ".gitattributes", "README.md",
    "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "app/build.gradle.kts",
    "gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties",
    "schemas/content-v1.sql", "schemas/content-intermediate-v1.schema.json", "schemas/user-v1.sql", "schemas/user-v2-upgrade.sql", "schemas/user-v3-upgrade.sql",
    "app/src/main/assets/content/manifest.json", "app/src/main/assets/content/fr.db", "app/src/main/assets/content/es.db",
    "data/manifests/content-packages.json", "data/content-packages/fr.db", "data/content-packages/es.db",
    "data/source-snapshots/french_content.db", "data/source-snapshots/spanish_content.db",
    "data/normalized/fr.content-v1.json", "data/normalized/es.content-v1.json",
    "docs/qa/baseline/reference-audit.json", "docs/qa/p4/fr-content-diff.json", "docs/qa/p4/es-content-diff.json",
    "app/src/test/resources/fsrs-5.4.2-reference.csv", "app/src/main/assets/licenses/fr-content.md",
    "app/src/main/assets/licenses/es-content.md", "app/src/main/assets/licenses/ts-fsrs.txt",
    "app/src/main/assets/licenses/APERTIUM-COPYING", "tools/check_git_scope.py",
)
FIXTURES = tuple("data/fixtures/p7/generated/" + name for name in (
    "de.db", "de.json", "nl.db", "nl.json", "ja.db", "ja.json", "ko.db", "ko.json", "manifest.json", "search-vectors.json"))
REQUIRED += FIXTURES
FORMAL_DATABASES = {path for path in REQUIRED if path.endswith(".db")}
IGNORE_PROBES = (
    ".tools/android-sdk/platform-tools/adb.exe", ".gradle/cache.bin", ".kotlin/session.dat", "app/build/example.txt",
    ".agents/local-context.md", ".codex/local-settings.json", ".aws/credentials", "nested/.tools/tool.bin",
    "local.properties", ".idea/workspace.xml", ".env", ".env.production", "signing.properties", "keys/release.jks",
    "keys/release.keystore", "user-data/memento-user.db", "backups/export.sqlite3", "data/backups/legacy.db",
    "memento-user.db", "memento-qa-ui7.db", "unexpected.sqlite", "app/src/main/assets/content/fr.db-wal",
    "data/memento_user.db", "data/memento-test-migration.db", "docs/memento_user.db",
    "data/fixtures/p7/generated/de.db-shm", "tmp/research-german-20261012/A1_SD1_Wortliste_02.pdf",
    "releases/Memento-0.2.0-debug.apk", "other/location/candidate.apk", "提升GHFind评分建议.pdf",
    "data/manifests/copied-inputs.json", "data/manifests/resolved-build-artifacts.json",
    "data/source-snapshots/german-research-2026-10-12/goethe-a1-wordlist.pdf",
    "data/source-snapshots/german-research-2026-10-12/daflex-home.html", "data/source-snapshots/new-research/raw.json",
    "docs/qa/ui-restoration/ui7/matrix/w320-f1_0/screenshots/home.png", "docs/qa/p8/instrumentation.log",
    "docs/qa/ui-restoration/ui7/delivery.json", "docs/audits/2026-10-12/Memento_Fr-issues-current.json",
    "docs/repository/local/commit-candidates.json",
    "AGENTS.md", "MULTILINGUAL_REFACTOR_PLAN.md", "CONTRIBUTING.md", "docs/repository/GIT_SCOPE.md",
    "docs/DEVELOPMENT_PHASES.md", "docs/decisions/PRODUCT_RULES.md", "docs/content/CONVERSION.md",
    "docs/ui/LEGACY_LAYOUT_RESTORATION_PLAN.md", "docs/research/GERMAN_LANGUAGE_RESEARCH_2026-10-12.md",
    "docs/qa/ui-restoration/ui7/ACCEPTANCE.md", "docs/qa/ui-restoration/ui7/sqlite-318/README.md",
    "docs/audits/2026-10-12/IMPLEMENTATION_AND_ISSUES_AUDIT.md",
    "docs/qa/ui-restoration/ui1/reference-preservation.json", "docs/qa/ui-restoration/ui6/source-manifest.json",
    "docs/qa/ui-restoration/ui7/baseline-source/StudyRepository.kt", "docs/qa/ui-restoration/ui7/sql-compatibility.patch",
    "app/src/main/java/com/scott/memento/domain/fsrs/README.md",
)
KEEP_PROBES = REQUIRED + (
    "app/src/main/java/com/scott/memento/MainActivity.kt", "tools/build.ps1", "tools/prepare_content_packages.py",
    "app/src/main/java/com/scott/memento/domain/fsrs/THIRD_PARTY_LICENSE.txt", ".env.example",
)


def digest(path):
    check = sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            check.update(block)
    return check.hexdigest()


def git_blob_sha1(path):
    check = sha1()
    check.update(b"blob " + str(path.stat().st_size).encode("ascii") + b"\0")
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            check.update(block)
    return check.hexdigest()


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    SCRATCH.mkdir(parents=True, exist_ok=True)
    (SCRATCH / "empty-template").mkdir(exist_ok=True)
    empty = SCRATCH / "empty-config"
    empty.write_text("", encoding="utf-8")
    # Ignore inherited Git repository/index/object locations, config overrides,
    # trace output paths and other caller-specific settings.
    env = {key: value for key, value in os.environ.items() if not key.upper().startswith("GIT_")}
    env.update(GIT_CONFIG_GLOBAL=str(empty), GIT_CONFIG_SYSTEM=str(empty),
               GIT_OPTIONAL_LOCKS="0", PYTHONDONTWRITEBYTECODE="1")
    if not (GIT_DIR / "HEAD").is_file():
        subprocess.run(["git", "-c", "init.defaultBranch=scope-check", "init", "--bare", "--template=" + str(SCRATCH / "empty-template"), str(GIT_DIR)],
                       cwd=ROOT, env=env, check=True, capture_output=True)
    base = ["git", "--git-dir=" + str(GIT_DIR), "--work-tree=" + str(ROOT),
            "-c", "core.bare=false", "-c", "core.excludesFile=" + str(empty), "-c", "core.attributesFile=" + str(empty)]

    def git(args, data=None, accepted=(0,)):
        result = subprocess.run(base + args, input=data, cwd=ROOT, env=env, capture_output=True)
        if result.returncode not in accepted:
            raise RuntimeError(result.stderr.decode("utf-8", errors="replace"))
        return result.stdout

    files = []
    skipped = []
    for parent, directories, names in os.walk(ROOT):
        for name in list(directories):
            directory = Path(parent) / name
            if directory.is_symlink() or directory.is_junction():
                raise ValueError("Review linked directory before preparing source scope: " + str(directory))
            if name in SKIP_DIRECTORIES:
                skipped.append(directory.relative_to(ROOT).as_posix())
        directories[:] = [name for name in directories if name not in SKIP_DIRECTORIES]
        for name in names:
            path = Path(parent) / name
            if path.is_symlink():
                raise ValueError("Review symlink before preparing source scope: " + str(path))
            files.append(path.relative_to(ROOT).as_posix())
    files.sort()
    skipped_cache_paths = [path + "/" for path in skipped if Path(path).name != ".git"]
    all_names = sorted(set(files) | set(IGNORE_PROBES) | set(KEEP_PROBES) | set(skipped_cache_paths))
    raw = git(["check-ignore", "--no-index", "--stdin", "-z"], "\0".join(all_names).encode("utf-8") + b"\0", accepted=(0, 1))
    ignored = set(raw.decode("utf-8").strip("\0").split("\0")) if raw else set()
    failures = [{"path": path, "reason": "expected excluded"} for path in IGNORE_PROBES if path not in ignored]
    failures += [{"path": path, "reason": "expected retained"} for path in KEEP_PROBES if path in ignored]
    failures += [{"path": path, "reason": "required input missing"} for path in REQUIRED if not (ROOT / path).is_file()]
    failures += [{"path": path, "reason": "skipped cache directory is not actually ignored"} for path in skipped_cache_paths if path not in ignored]

    candidates = []
    exclusions = []
    for relative in files:
        path = ROOT / relative
        if relative in ignored:
            exclusions.append({"path": relative, "bytes": path.stat().st_size})
            continue
        if path.suffix.lower() in {".db", ".sqlite", ".sqlite3"} and relative not in FORMAL_DATABASES:
            failures.append({"path": relative, "reason": "unapproved database: review as possible user data"})
        before = path.stat()
        content_sha = digest(path)
        after = path.stat()
        if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
            failures.append({"path": relative, "reason": "file changed during inventory; rerun after edits settle"})
        candidates.append({"path": relative, "bytes": after.st_size, "sha256": content_sha})

    immutable = sorted({row["path"] for row in candidates if row["path"].startswith((
        "app/src/main/assets/", "app/src/test/resources/", "data/", "schemas/", "docs/qa/"))})
    attribute_raw = git(["check-attr", "-z", "text", "--stdin"], "\0".join(immutable).encode("utf-8") + b"\0")
    values = attribute_raw.decode("utf-8").strip("\0").split("\0")
    attributes = [dict(zip(("path", "attribute", "value"), values[i:i + 3])) for i in range(0, len(values), 3)]
    failures += [{"path": row["path"], "reason": "hash-bound input may undergo text conversion"} for row in attributes if row["value"] != "unset"]

    # Exercise Git's actual clean path with autocrlf=true, without writing blobs/index.
    byte_probe_paths = (
        "app/src/main/assets/content/fr.db", "app/src/main/assets/content/manifest.json",
        "app/src/main/assets/licenses/fr-content.md", "app/src/main/assets/licenses/es-content.md",
        "data/normalized/fr.content-v1.json", "data/manifests/content-packages.json",
        "data/fixtures/p7/generated/search-vectors.json", "schemas/content-v1.sql",
        "docs/qa/p4/fr-content-diff.json", "app/src/test/resources/fsrs-5.4.2-reference.csv",
    )
    hashes = git(["-c", "core.autocrlf=true", "hash-object", "--stdin-paths"],
                 "\n".join(byte_probe_paths).encode("utf-8") + b"\n").decode("ascii").splitlines()
    byte_probes = []
    for path, actual in zip(byte_probe_paths, hashes):
        raw_hash = git_blob_sha1(ROOT / path)
        byte_probes.append({"path": path, "git_clean_blob": actual, "raw_blob": raw_hash, "unchanged": actual == raw_hash})
    if len(hashes) != len(byte_probe_paths):
        failures.append({"reason": "incomplete Git byte-preservation probe"})
    failures += [{"path": row["path"], "reason": "Git clean changed hash-bound bytes"} for row in byte_probes if not row["unchanged"]]

    def group(rows):
        counts = Counter()
        sizes = Counter()
        for row in rows:
            top = row["path"].split("/", 1)[0]
            counts[top] += 1
            sizes[top] += row["bytes"]
        return {key: {"files": counts[key], "bytes": sizes[key]} for key in sorted(counts)}

    version = subprocess.run(["git", "--version"], cwd=ROOT, env=env, check=True, capture_output=True).stdout.decode("utf-8").strip()
    report = {"generated_at_utc": datetime.now(timezone.utc).isoformat(), "root": str(ROOT), "git_version": version,
              "scope": "Local candidate inventory only; not a commit, staged index, upload, or license clearance",
              "project_git_metadata_exists": (ROOT / ".git").exists(), "project_git_metadata_modified": False,
              "isolated_matcher_git_dir": str(GIT_DIR), "skipped_cache_or_metadata_directories": sorted(skipped),
              "candidate_files": len(candidates), "candidate_bytes": sum(row["bytes"] for row in candidates),
              "candidates_by_top_level": group(candidates), "excluded_enumerated_files": len(exclusions),
              "excluded_enumerated_bytes": sum(row["bytes"] for row in exclusions), "excluded_by_top_level": group(exclusions),
              "required_input_checks": len(REQUIRED), "ignore_probe_checks": len(IGNORE_PROBES), "keep_probe_checks": len(KEEP_PROBES),
              "immutable_attributes_checked": len(attributes), "git_byte_preservation_probes": byte_probes,
              "largest_candidates": sorted(candidates, key=lambda row: row["bytes"], reverse=True)[:12],
              "failures": failures, "passed": not failures}
    (OUT / "commit-candidates.json").write_text(json.dumps({"generated_at_utc": report["generated_at_utc"], "files": candidates}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (OUT / "excluded-files.json").write_text(json.dumps({"scope": "Enumerated files only; skipped tool/cache directories are listed in verification", "files": exclusions}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (OUT / "verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (OUT / "commit-candidates.txt").write_text("\n".join(row["path"] for row in candidates) + "\n", encoding="utf-8")
    print(json.dumps({"passed": report["passed"], "candidate_files": len(candidates), "candidate_bytes": report["candidate_bytes"],
                      "excluded_enumerated_files": len(exclusions), "excluded_enumerated_bytes": report["excluded_enumerated_bytes"],
                      "immutable_attributes_checked": len(attributes), "git_byte_preservation_probes": len(byte_probes),
                      "failures": failures, "project_git_metadata_modified": False}, ensure_ascii=False))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
