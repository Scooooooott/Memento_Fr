"""Add the user-curated FLELex high-frequency books to the bundled database."""

from __future__ import annotations

import csv
import shutil
import sqlite3
import unicodedata
from contextlib import closing
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
DB = ROOT / "app/src/main/assets/french_content.db"
SELECTION_DIR = ROOT / "outputs/flelex-frequency-selection"

POS_MAP = {
    "ADJ": "adjective",
    "ADV": "adverb",
    "CONJ": "conjunction",
    "DET": "determiner",
    "INT": "interjection",
    "NOUN": "noun",
    "PREP": "preposition",
    "PREPDET": "preposition",
    "PRON": "pronoun",
    "VERB": "verb",
    "X": "expression",
}

BOOKS = (
    ("A1", 1000, "flelex-a1-high-1000", "FLELex_A1高频1000词", "flelex_A1_frequency_selected.csv"),
    ("A2", 800, "flelex-a2-high-800", "FLELex_A2高频800词", "flelex_A2_frequency_selected.csv"),
    ("B1", 1200, "flelex-b1-high-1200", "FLELex_B1高频1200词", "flelex_B1_frequency_selected.csv"),
    ("B2", 2000, "flelex-b2-high-2000", "FLELex_B2高频2000词", "flelex_B2_frequency_selected.csv"),
    ("C1", 1500, "flelex-c1-high-1500", "FLELex_C1高频1500词", "flelex_C1_frequency_selected.csv"),
)


def key(word: str, pos: str) -> tuple[str, str]:
    normalized = unicodedata.normalize("NFC", word).strip().lower()
    return " ".join(normalized.split()), pos


def read_selected(level: str, filename: str) -> list[tuple[str, str]]:
    path = SELECTION_DIR / filename
    with path.open(encoding="utf-8-sig", newline="") as stream:
        rows = csv.DictReader(stream)
        selected: list[tuple[str, str]] = []
        for row in rows:
            raw_pos = row["pos"].strip().upper()
            if raw_pos not in POS_MAP:
                raise ValueError(f"{level}: unsupported POS {raw_pos!r}")
            selected.append((row["word"].strip(), POS_MAP[raw_pos]))
    return selected


def main() -> None:
    staging = DB.with_suffix(DB.suffix + ".frequency-books-building")
    shutil.copyfile(DB, staging)
    report: list[tuple[str, int, int]] = []
    try:
        with closing(sqlite3.connect(staging)) as db:
            with db:
                db.execute("PRAGMA foreign_keys=ON")
                lexemes = {
                    key(lemma, pos): uid
                    for uid, lemma, pos in db.execute(
                        "SELECT lexeme_uid, lemma, part_of_speech FROM lexeme"
                    )
                }
                next_book_order = db.execute(
                    "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM vocabulary_book"
                ).fetchone()[0]

                for offset, (level, requested_rows, book_id, title, filename) in enumerate(BOOKS):
                    selected = read_selected(level, filename)
                    unique_uids: list[str] = []
                    seen: set[str] = set()
                    for word, pos in selected:
                        uid = lexemes.get(key(word, pos))
                        if uid is None:
                            raise ValueError(f"{level}: no lexeme match for {word!r}/{pos}")
                        if uid not in seen:
                            seen.add(uid)
                            unique_uids.append(uid)

                    db.execute("DELETE FROM book_lexeme WHERE book_id = ?", (book_id,))
                    db.execute("DELETE FROM vocabulary_book WHERE book_id = ?", (book_id,))
                    db.execute(
                        "INSERT INTO vocabulary_book VALUES (?, ?, ?, ?)",
                        (
                            book_id,
                            title,
                            f"根据 {filename} 筛选的 FLELex {level} 高频词条，共 {len(unique_uids)} 个。",
                            next_book_order + offset,
                        ),
                    )
                    db.executemany(
                        "INSERT INTO book_lexeme VALUES (?, ?, ?)",
                        ((book_id, uid, order) for order, uid in enumerate(unique_uids)),
                    )
                    report.append((book_id, requested_rows, len(unique_uids)))

                if db.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
                    raise RuntimeError("SQLite integrity_check failed")
                if db.execute("PRAGMA foreign_key_check").fetchall():
                    raise RuntimeError("SQLite foreign_key_check failed")
                db.commit()
                db.execute("VACUUM")
        staging.replace(DB)
    finally:
        staging.unlink(missing_ok=True)

    for book_id, requested_rows, inserted in report:
        print(f"{book_id}: source_rows={requested_rows}, inserted_members={inserted}")


if __name__ == "__main__":
    main()
