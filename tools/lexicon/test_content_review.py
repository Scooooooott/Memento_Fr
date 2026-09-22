"""Regression tests for approved F4b review batches and their formal integration."""
from __future__ import annotations

from contextlib import closing
import json
from pathlib import Path
import random
import sqlite3
import unittest

from content_review import ROOT, apply_review_registry


REGISTRY = ROOT / "data/curated/review-overrides/flelex_a1.json"
SOURCE = ROOT / "data/curated/flelex_a1.json"
DATABASE = ROOT / "app/src/main/assets/french_content.db"
SPOT_CHECK = ROOT / "docs/content-review/spot-check-0001-0050.json"


class ContentReviewTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.source = json.loads(SOURCE.read_text(encoding="utf-8"))
        cls.words = {word["uid"]: word for word in cls.source["words"]}
        cls.reviewed, cls.report = apply_review_registry(
            cls.source["words"], REGISTRY, require_original_hash=True
        )

    def test_registered_review_is_complete_and_idempotent(self) -> None:
        self.assertEqual(self.report["reviewed_words"], 50)
        self.assertEqual(self.report["reviewed_senses"], 110)
        self.assertEqual(self.report["reviewed_examples"], 110)
        self.assertEqual(self.report["changed_words"], 0)
        self.assertEqual(self.reviewed, self.source["words"])
        for uid in self.report["target_uids"]:
            word = self.words[uid]
            self.assertEqual(len(word["senses"]), len(word["examples"]), uid)
            self.assertEqual(
                [example.get("sense_index", index) for index, example in enumerate(word["examples"])],
                list(range(len(word["senses"]))),
                uid,
            )

    def test_random_twenty_percent_sample_is_reproducible(self) -> None:
        spot = json.loads(SPOT_CHECK.read_text(encoding="utf-8"))
        selected = set(random.Random(spot["seed"]).sample(self.report["target_uids"], spot["sample_words"]))
        recorded = {row["uid"] for row in spot["sample"]}
        self.assertEqual(spot["sample_words"], 10)
        self.assertEqual(spot["sample_rate"], 0.2)
        self.assertEqual(selected, recorded)
        self.assertEqual(spot["final_failed_words"], 0)

    def test_bundled_database_matches_all_reviewed_senses_and_examples(self) -> None:
        with closing(sqlite3.connect(DATABASE.as_uri() + "?mode=ro", uri=True)) as db:
            self.assertEqual(db.execute("PRAGMA integrity_check").fetchone(), ("ok",))
            self.assertEqual(db.execute("PRAGMA foreign_key_check").fetchall(), [])
            for uid in self.report["target_uids"]:
                word = self.words[uid]
                senses = db.execute(
                    "SELECT english,spanish,chinese FROM sense WHERE lexeme_uid=? ORDER BY sort_order", (uid,)
                ).fetchall()
                self.assertEqual(
                    senses,
                    [(sense["english"], sense["spanish"], sense["chinese"]) for sense in word["senses"]],
                    uid,
                )
                examples = db.execute(
                    "SELECT e.french,e.english,e.spanish,e.chinese FROM example e "
                    "JOIN sense s USING(sense_id) WHERE s.lexeme_uid=? ORDER BY s.sort_order,e.sort_order",
                    (uid,),
                ).fetchall()
                self.assertEqual(
                    examples,
                    [(example["french"], example["english"], example["spanish"], example["chinese"]) for example in word["examples"]],
                    uid,
                )
            metadata = db.execute(
                "SELECT value FROM content_meta WHERE key='flelex_a1_f4b_review'"
            ).fetchone()
            self.assertIsNotNone(metadata)
            self.assertEqual(json.loads(metadata[0])["reviewed_words"], 50)


if __name__ == "__main__":
    unittest.main()
