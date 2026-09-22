"""Apply approved content-review batches to the formal source and bundled DB."""
from __future__ import annotations

import argparse
from contextlib import closing
import json
from pathlib import Path
import shutil
import sqlite3
import sys

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

from content_review import ROOT, apply_review_registry, file_sha256, load_review_registry, word_sha256


DEFAULT_REGISTRY = ROOT / "data/curated/review-overrides/flelex_a1.json"
DEFAULT_SOURCE = ROOT / "data/curated/flelex_a1.json"
DEFAULT_DB = ROOT / "app/src/main/assets/french_content.db"


def replace_reviewed_content(db: sqlite3.Connection, words: list[dict], target_uids: list[str], report: dict) -> None:
    by_uid = {word["uid"]: word for word in words}
    before_counts = {
        table: db.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
        for table in ("lexeme", "pronunciation", "word_form", "verb_info", "conjugation_form", "vocabulary_book", "book_lexeme")
    }
    for uid in target_uids:
        if db.execute("SELECT COUNT(*) FROM lexeme WHERE lexeme_uid=?", (uid,)).fetchone()[0] != 1:
            raise RuntimeError(f"Reviewed UID is absent or duplicated in the bundled DB: {uid}")
        sense_ids = [row[0] for row in db.execute("SELECT sense_id FROM sense WHERE lexeme_uid=?", (uid,))]
        db.executemany("DELETE FROM example WHERE sense_id=?", ((sense_id,) for sense_id in sense_ids))
        db.execute("DELETE FROM sense WHERE lexeme_uid=?", (uid,))
        word = by_uid[uid]
        examples_by_sense: dict[int, list[dict]] = {}
        for position, example in enumerate(word["examples"]):
            examples_by_sense.setdefault(example.get("sense_index", position), []).append(example)
        for sense_order, sense in enumerate(word["senses"]):
            sense_id = f"{uid}:sense:{sense_order + 1}"
            db.execute(
                "INSERT INTO sense VALUES (?,?,?,?,?,?,?)",
                (sense_id, uid, sense_order, sense["english"], sense["spanish"], sense["chinese"], 1),
            )
            for example_order, example in enumerate(examples_by_sense.get(sense_order, [])):
                db.execute(
                    "INSERT INTO example VALUES (?,?,?,?,?,?,?)",
                    (
                        f"{sense_id}:example:{example_order + 1}", sense_id, example_order,
                        example["french"], example["english"], example["spanish"], example["chinese"],
                    ),
                )

    after_counts = {
        table: db.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
        for table in before_counts
    }
    if before_counts != after_counts:
        raise RuntimeError(f"Review changed non-content table counts: {before_counts} != {after_counts}")
    db.execute(
        "INSERT OR REPLACE INTO content_meta(key,value) VALUES (?,?)",
        ("flelex_a1_f4b_review", json.dumps(report, ensure_ascii=False, sort_keys=True)),
    )
    if db.execute("PRAGMA foreign_key_check").fetchall():
        raise RuntimeError("SQLite foreign_key_check failed after content review")
    if db.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
        raise RuntimeError("SQLite integrity_check failed after content review")

    for uid in target_uids:
        word = by_uid[uid]
        senses = db.execute(
            "SELECT english,spanish,chinese FROM sense WHERE lexeme_uid=? ORDER BY sort_order", (uid,)
        ).fetchall()
        expected_senses = [(sense["english"], sense["spanish"], sense["chinese"]) for sense in word["senses"]]
        if senses != expected_senses:
            raise RuntimeError(f"Bundled sense mismatch after review: {uid}")
        examples = db.execute(
            "SELECT e.french,e.english,e.spanish,e.chinese FROM example e "
            "JOIN sense s USING(sense_id) WHERE s.lexeme_uid=? ORDER BY s.sort_order,e.sort_order",
            (uid,),
        ).fetchall()
        expected_examples = [
            (example["french"], example["english"], example["spanish"], example["chinese"])
            for example in word["examples"]
        ]
        if examples != expected_examples:
            raise RuntimeError(f"Bundled example mismatch after review: {uid}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--registry", type=Path, default=DEFAULT_REGISTRY)
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    parser.add_argument("--db", type=Path, default=DEFAULT_DB)
    parser.add_argument("--allow-source-drift", action="store_true")
    args = parser.parse_args()

    source = json.loads(args.source.read_text(encoding="utf-8"))
    original_uids = [word["uid"] for word in source["words"]]
    original_hashes = {word["uid"]: word_sha256(word) for word in source["words"]}
    reviewed_words, report = apply_review_registry(
        source["words"], args.registry, require_original_hash=not args.allow_source_drift
    )
    registry = load_review_registry(args.registry)
    source["words"] = reviewed_words
    source["content_version"] = registry["content_version"]
    source["review_date"] = registry["review_date"]
    if [word["uid"] for word in reviewed_words] != original_uids:
        raise RuntimeError("Content review changed source UID order")
    target_uids = report["target_uids"]
    if any(
        word_sha256(word) != original_hashes[word["uid"]]
        for word in reviewed_words if word["uid"] not in target_uids
    ):
        raise RuntimeError("Content review changed a word outside the approved batches")

    source_staging = args.source.with_suffix(args.source.suffix + ".review-building")
    db_staging = args.db.with_suffix(args.db.suffix + ".review-building")
    source_staging.unlink(missing_ok=True)
    db_staging.unlink(missing_ok=True)
    try:
        source_staging.write_text(
            json.dumps(source, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        report["formal_source_sha256"] = file_sha256(source_staging)
        shutil.copyfile(args.db, db_staging)
        with closing(sqlite3.connect(db_staging)) as db:
            db.execute("PRAGMA foreign_keys=ON")
            replace_reviewed_content(db, reviewed_words, target_uids, report)
            db.commit()
        source_staging.replace(args.source)
        db_staging.replace(args.db)
    finally:
        source_staging.unlink(missing_ok=True)
        db_staging.unlink(missing_ok=True)
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
