"""Synchronize generated curated JSON content with the bundled SQLite corpus.

The app database is the assembled source of truth after content QA.  Several
curated files contain overlapping UID sets, so this utility updates only the
matching ``senses`` and ``examples`` fields while preserving each file's
metadata, word order, forms, and provenance.  Legacy files without a dict
shaped ``words`` list are intentionally skipped.
"""
from __future__ import annotations

import argparse
from contextlib import closing
import json
from pathlib import Path
import sqlite3
import tempfile


ROOT = Path(__file__).resolve().parents[2]
DB_PATH = ROOT / "app/src/main/assets/french_content.db"
CURATED_DIR = ROOT / "data/curated"


def load_database() -> dict[str, tuple[list[dict], list[dict]]]:
    result: dict[str, tuple[list[dict], list[dict]]] = {}
    with closing(sqlite3.connect(DB_PATH)) as connection:
        connection.row_factory = sqlite3.Row
        senses: dict[str, list[dict]] = {}
        examples: dict[str, list[dict]] = {}
        for row in connection.execute(
            "SELECT lexeme_uid, sort_order, english, spanish, chinese "
            "FROM sense ORDER BY lexeme_uid, sort_order"
        ):
            senses.setdefault(row["lexeme_uid"], []).append(
                {
                    "english": row["english"],
                    "spanish": row["spanish"],
                    "chinese": row["chinese"],
                }
            )
        for row in connection.execute(
            "SELECT s.lexeme_uid, s.sort_order AS sense_sort_order, "
            "e.sort_order, e.french, e.english, e.spanish, e.chinese "
            "FROM example e JOIN sense s ON s.sense_id = e.sense_id "
            "ORDER BY s.lexeme_uid, s.sort_order, e.sort_order"
        ):
            examples.setdefault(row["lexeme_uid"], []).append(
                {
                    "sense_index": row["sense_sort_order"],
                    "french": row["french"],
                    "english": row["english"],
                    "spanish": row["spanish"],
                    "chinese": row["chinese"],
                }
            )
    for uid in senses:
        result[uid] = (senses[uid], examples.get(uid, []))
    return result


def sync_file(path: Path, db_content: dict[str, tuple[list[dict], list[dict]]], dry_run: bool) -> tuple[int, int]:
    # The 40-word french_dev file is a legacy editorial fixture with a
    # different list-shaped sense/example schema; it is intentionally not an
    # assembled-source mirror of the FLELex database.
    if not path.name.startswith("flelex_"):
        return 0, 0
    payload = json.loads(path.read_text(encoding="utf-8"))
    words = payload.get("words") if isinstance(payload, dict) else None
    if not isinstance(words, list) or not all(isinstance(word, dict) for word in words):
        return 0, 0
    changed = 0
    matched = 0
    for word in words:
        uid = word.get("uid")
        if uid not in db_content:
            continue
        matched += 1
        senses, examples = db_content[uid]
        if word.get("senses") != senses or word.get("examples") != examples:
            changed += 1
            if not dry_run:
                word["senses"] = senses
                word["examples"] = examples
    if changed and not dry_run:
        temp_path: Path | None = None
        try:
            with tempfile.NamedTemporaryFile(
                "w", encoding="utf-8", newline="\n", dir=path.parent,
                prefix=f".{path.stem}.", suffix=".sync-building", delete=False
            ) as handle:
                temp_path = Path(handle.name)
                json.dump(payload, handle, ensure_ascii=False, indent=2)
                handle.write("\n")
            temp_path.replace(path)
        finally:
            if temp_path and temp_path.exists():
                temp_path.unlink()
    return matched, changed


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    db_content = load_database()
    files = sorted(CURATED_DIR.glob("*.json"))
    matched = changed = changed_files = 0
    for path in files:
        file_matched, file_changed = sync_file(path, db_content, args.dry_run)
        matched += file_matched
        changed += file_changed
        changed_files += bool(file_changed)
    print(json.dumps({
        "db_uids": len(db_content),
        "curated_files": len(files),
        "matched_word_entries": matched,
        "changed_word_entries": changed,
        "changed_files": changed_files,
        "dry_run": args.dry_run,
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
