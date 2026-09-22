"""Apply the checked full-corpus meaning overrides to SQLite and curated JSON."""
from __future__ import annotations

import argparse
from contextlib import closing
import copy
import json
from pathlib import Path
import shutil
import sqlite3
import tempfile


ROOT = Path(__file__).resolve().parents[2]
DB_PATH = ROOT / "app/src/main/assets/french_content.db"
OVERRIDE_PATHS = (
    ROOT / "data/curated/review-overrides/french_meanings_full.json",
    ROOT / "data/curated/review-overrides/french_meanings_additional.json",
    ROOT / "data/curated/review-overrides/french_meanings_residual.json",
    ROOT / "data/curated/review-overrides/french_meanings_followup.json",
)
CURATED_DIR = ROOT / "data/curated"
FIELDS = ("english", "spanish", "chinese")


def load_overrides() -> dict[str, dict[str, str]]:
    merged: dict[str, dict[str, str]] = {}
    for path in OVERRIDE_PATHS:
        payload = json.loads(path.read_text(encoding="utf-8"))
        overrides = payload.get("overrides")
        if not isinstance(overrides, dict):
            raise ValueError(f"{path} must contain an overrides object")
        for uid, fields in overrides.items():
            if not isinstance(uid, str) or not isinstance(fields, dict) or not fields:
                raise ValueError(f"invalid override: {uid!r}")
            if set(fields) - set(FIELDS):
                raise ValueError(f"invalid fields for {uid}: {set(fields) - set(FIELDS)}")
            if any(not isinstance(value, str) or not value.strip() for value in fields.values()):
                raise ValueError(f"blank override for {uid}")
            if uid in merged:
                merged[uid].update(fields)
            else:
                merged[uid] = dict(fields)
    return merged


def apply_db(overrides: dict[str, dict[str, str]], dry_run: bool) -> int:
    temp_path: Path | None = None
    changed = 0
    with tempfile.NamedTemporaryFile(
        suffix=".db", prefix=".french-content-meaning-", dir=DB_PATH.parent, delete=False
    ) as handle:
        temp_path = Path(handle.name)
    shutil.copy2(DB_PATH, temp_path)
    try:
        with closing(sqlite3.connect(temp_path)) as db:
            db.execute("PRAGMA foreign_keys=ON")
            for uid, fields in overrides.items():
                for field, value in fields.items():
                    result = db.execute(
                        f"UPDATE sense SET {field}=? WHERE lexeme_uid=? AND sort_order=0",
                        (value, uid),
                    )
                    changed += result.rowcount
            db.commit()
            if db.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
                raise RuntimeError("SQLite integrity check failed")
        if not dry_run:
            temp_path.replace(DB_PATH)
    finally:
        if temp_path and temp_path.exists():
            temp_path.unlink()
    return changed


def apply_sources(overrides: dict[str, dict[str, str]], dry_run: bool) -> tuple[int, int]:
    changed_entries = 0
    changed_files = 0
    for path in sorted(CURATED_DIR.glob("*.json")):
        payload = json.loads(path.read_text(encoding="utf-8"))
        words = payload.get("words") if isinstance(payload, dict) else None
        if not isinstance(words, list):
            continue
        changed = False
        for word in words:
            uid = word.get("uid") if isinstance(word, dict) else None
            fields = overrides.get(uid)
            if not fields:
                continue
            senses = word.get("senses")
            if not isinstance(senses, list) or not senses:
                continue
            target = senses[0]
            before = dict(target)
            for field, value in fields.items():
                target[field] = value
            if target != before:
                changed_entries += 1
                changed = True
        if changed:
            changed_files += 1
            if not dry_run:
                temp_path: Path | None = None
                try:
                    with tempfile.NamedTemporaryFile(
                        "w", encoding="utf-8", newline="\n", dir=path.parent,
                        prefix=f".{path.stem}.", suffix=".meaning-building", delete=False
                    ) as handle:
                        temp_path = Path(handle.name)
                        json.dump(payload, handle, ensure_ascii=False, indent=2)
                        handle.write("\n")
                    temp_path.replace(path)
                finally:
                    if temp_path and temp_path.exists():
                        temp_path.unlink()
    return changed_entries, changed_files


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    overrides = load_overrides()
    db_changed = apply_db(overrides, args.dry_run)
    source_changed, source_files = apply_sources(overrides, args.dry_run)
    print(json.dumps({
        "override_uids": len(overrides),
        "db_field_updates": db_changed,
        "source_word_updates": source_changed,
        "source_files": source_files,
        "dry_run": args.dry_run,
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
