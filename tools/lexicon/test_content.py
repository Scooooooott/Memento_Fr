"""Content release checks; temporary data stays under L:/Words/.tools."""
from contextlib import closing
import copy
import hashlib
from pathlib import Path
import sqlite3
import tempfile
import unittest

import build_content as content


class ContentReleaseTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source, cls.provenance = content.load_source()
        local_tmp = content.ROOT / ".tools/lexicon-tests"
        local_tmp.mkdir(parents=True, exist_ok=True)
        cls.temp = tempfile.TemporaryDirectory(dir=local_tmp)
        cls.folder = Path(cls.temp.name)
        cls.db_path = cls.folder / "content.db"
        cls.counts = content.build(cls.db_path)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def connection(self):
        return closing(sqlite3.connect(self.db_path.as_uri() + "?mode=ro", uri=True))

    def test_release_counts_and_integrity(self):
        with self.connection() as db:
            counts = content.validate_database(db)
            self.assertEqual(db.execute("PRAGMA user_version").fetchone(), (2,))
        expected_verbs = sum(word["pos"] == "verb" for word in self.source["words"])
        self.assertEqual(counts["lexeme"], len(self.source["words"]))
        self.assertEqual(counts["verb_info"], expected_verbs)
        self.assertEqual(counts["conjugation_form"], expected_verbs * 18)
        self.assertEqual(counts["sense"], counts["example"])

    def test_chinese_contract_is_complete_and_aligned(self):
        with self.connection() as db:
            sense_columns = {row[1] for row in db.execute("PRAGMA table_info(sense)")}
            example_columns = {row[1] for row in db.execute("PRAGMA table_info(example)")}
            self.assertIn("chinese", sense_columns)
            self.assertIn("chinese", example_columns)
            self.assertEqual(db.execute("SELECT count(*) FROM sense WHERE chinese='' OR chinese IS NULL").fetchone(), (0,))
            self.assertEqual(db.execute("SELECT count(*) FROM example WHERE chinese='' OR chinese IS NULL").fetchone(), (0,))
            self.assertEqual(
                db.execute("SELECT english,spanish,chinese FROM sense WHERE sense_id='fr:avoir:verb:1:sense:2'").fetchone(),
                ("auxiliary for compound tenses", "haber (auxiliar)", "用于构成复合时态的助动词"),
            )
            self.assertEqual(
                db.execute("SELECT french,chinese FROM example WHERE example_id='fr:être:verb:1:sense:1:example:1'").fetchone(),
                ("Je suis à la maison.", "我在家。"),
            )

    def test_reproducible_asset_matches_source(self):
        second = self.folder / "second.db"
        content.build(second)
        self.assertEqual(hashlib.sha256(self.db_path.read_bytes()).digest(), hashlib.sha256(second.read_bytes()).digest())
        source_hash = hashlib.sha256(content.SOURCE.read_bytes()).hexdigest()
        with closing(sqlite3.connect(content.DEFAULT_OUTPUT.as_uri() + "?mode=ro", uri=True)) as bundled:
            bundled_source = bundled.execute(
                "SELECT value FROM content_meta WHERE key='source_sha256'"
            ).fetchone()
        # The production asset may be a later consolidated FLELex build. Only
        # a bundle that declares this exact single source must byte-match it.
        if bundled_source == (source_hash,):
            self.assertEqual(
                self.db_path.read_bytes(),
                content.DEFAULT_OUTPUT.read_bytes(),
                "Bundled single-source database is stale; run build_content.py",
            )

    def test_six_persons_each_tense_and_safe_readonly(self):
        with self.connection() as db:
            rows = db.execute("SELECT lexeme_uid, tense, count(*), min(person), max(person) FROM conjugation_form GROUP BY lexeme_uid, tense").fetchall()
            self.assertEqual(len(rows), self.counts["verb_info"] * 3)
            self.assertTrue(all(row[2:] == (6, 1, 6) for row in rows))
            with self.assertRaises(sqlite3.OperationalError):
                db.execute("DELETE FROM lexeme")

    def test_grammar_regressions(self):
        expected = [
            ("être", "présent", 4, "nous", "sommes"),
            ("être", "passé composé", 1, "j’", "ai été"),
            ("être", "imparfait", 4, "nous", "étions"),
            ("avoir", "présent", 1, "j’", "ai"),
            ("avoir", "passé composé", 6, "ils / elles", "ont eu"),
            ("aller", "présent", 6, "ils / elles", "vont"),
            ("aller", "passé composé", 4, "nous", "sommes allé(e)s"),
            ("aller", "passé composé", 5, "vous", "êtes allé(e)(s)"),
            ("aller", "imparfait", 1, "j’", "allais"),
            ("faire", "présent", 5, "vous", "faites"),
            ("faire", "imparfait", 4, "nous", "faisions"),
            ("habiter", "présent", 1, "j’", "habite"),
            ("travailler", "imparfait", 4, "nous", "travaillions"),
            ("manger", "présent", 4, "nous", "mangeons"),
            ("manger", "imparfait", 1, "je", "mangeais"),
            ("manger", "imparfait", 4, "nous", "mangions"),
            ("manger", "imparfait", 5, "vous", "mangiez"),
            ("prendre", "présent", 6, "ils / elles", "prennent"),
            ("prendre", "passé composé", 1, "j’", "ai pris"),
        ]
        with self.connection() as db:
            for lemma, tense, person, pronoun, form in expected:
                actual = db.execute("SELECT pronoun, form FROM conjugation_form WHERE lexeme_uid=? AND tense=? AND person=?",
                                    (f"fr:{lemma}:verb:1", tense, person)).fetchone()
                self.assertEqual(actual, (pronoun, form), f"{lemma} {tense} {person}")
            self.assertEqual(db.execute("SELECT gender FROM lexeme WHERE lemma='eau'").fetchone(), ("f.",))
            self.assertEqual(db.execute("SELECT form FROM word_form WHERE lexeme_uid='fr:pays:noun:1' AND label='pluriel'").fetchone(), ("des pays",))
            self.assertEqual(db.execute("SELECT count(*) FROM pronunciation WHERE audio_asset IS NOT NULL").fetchone(), (0,))

    def test_stable_uid_mapping_and_book_membership(self):
        with self.connection() as db:
            self.assertEqual(db.execute("SELECT lexeme_uid FROM lexeme WHERE lemma=?", ("aujourd'hui",)).fetchone(), ("fr:aujourd'hui:adverb:1",))
            self.assertEqual(db.execute("SELECT count(*) FROM book_lexeme b JOIN lexeme l USING(lexeme_uid) WHERE b.book_id='verbs-fr' AND l.part_of_speech!='verb'").fetchone(), (0,))
            self.assertEqual(db.execute("SELECT count(*) FROM lexeme l LEFT JOIN book_lexeme b ON b.lexeme_uid=l.lexeme_uid AND b.book_id='essential-fr' WHERE b.lexeme_uid IS NULL").fetchone(), (0,))

    def test_textbook_coverage_extended_pos_and_morphology(self):
        occurrences = [item for word in self.source["words"] for item in word.get("source_entries", [])]
        self.assertEqual(sorted(item["number"] for item in occurrences), list(range(1, 892)))
        by_uid = {word["uid"]: word for word in self.source["words"]}
        self.assertIn("fr:à:preposition:1", by_uid)
        self.assertIn("fr:paris:proper_noun:1", by_uid)
        self.assertEqual(by_uid["fr:livre:noun:1"]["gender"], "m.")
        self.assertEqual(by_uid["fr:livre:noun:2"]["gender"], "f.")
        self.assertEqual(by_uid["fr:allumer:verb:1"]["verb"]["auxiliary"], "avoir")
        self.assertEqual(by_uid["fr:partir:verb:1"]["verb"]["auxiliary"], "être")
        self.assertEqual(by_uid["fr:pleuvoir:verb:1"]["verb"]["present"][2], "pleut")
        self.assertFalse(any("editorial-fallback" in word.get("references", []) for word in self.source["words"]))

    def test_bad_editorial_inputs_are_rejected(self):
        def rejected(mutator):
            modified = copy.deepcopy(self.source)
            mutator(modified)
            with self.assertRaises(ValueError):
                content.validate_source(modified, self.provenance)
        rejected(lambda d: d["words"].append(copy.deepcopy(d["words"][0])))
        rejected(lambda d: d["words"][0]["senses"][0].__setitem__("spanish", ""))
        rejected(lambda d: d["words"][0]["senses"][0].__setitem__("chinese", ""))
        rejected(lambda d: d["words"][0]["examples"].__setitem__(0, {
            "french": "Le terme figure dans le vocabulaire de cette leçon.",
            "english": "The term appears in this lesson's vocabulary.",
            "spanish": "El término aparece en el vocabulario de esta lección.",
            "chinese": "这个词出现在本课词汇表中。",
        }))
        verb_index = next(i for i, word in enumerate(self.source["words"]) if word["pos"] == "verb")
        noun_index = next(i for i, word in enumerate(self.source["words"]) if word["pos"] == "noun")
        etre_verb_index = next(i for i, word in enumerate(self.source["words"])
                                if word["pos"] == "verb" and word["verb"]["auxiliary"] == "être")
        rejected(lambda d: d["words"][verb_index]["verb"]["present"].pop())
        rejected(lambda d: d["words"][0].__setitem__("uid", "fr:changed:verb:1"))
        rejected(lambda d: d["words"][0].__setitem__("ipa", ""))
        rejected(lambda d: d["words"][noun_index].__setitem__("gender", ""))
        rejected(lambda d: d["words"][etre_verb_index]["verb"].pop("compound"))
        rejected(lambda d: d["words"][etre_verb_index].__setitem__("references", ["unknown-source"]))

    def test_multiple_senses_and_optional_examples_are_valid_editorial_shapes(self):
        modified = copy.deepcopy(self.source)
        word = modified["words"][0]
        word["senses"] += [
            {"english": "toward", "spanish": "hacia", "chinese": "朝向"},
            {"english": "at a rate of", "spanish": "a razón de", "chinese": "以某种速率"},
        ]
        word["examples"] = []
        content.validate_source(modified, self.provenance)

    def test_invalid_database_is_rejected(self):
        broken = self.folder / "broken.db"
        broken.write_bytes(self.db_path.read_bytes())
        with closing(sqlite3.connect(broken)) as db:
            db.execute("DELETE FROM conjugation_form WHERE lexeme_uid='fr:avoir:verb:1' AND tense='présent' AND person=2")
            with self.assertRaisesRegex(ValueError, "coverage is incomplete"):
                content.validate_database(db)

    def test_paths_outside_project_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "Writes must stay"):
            content.project_path(content.ROOT.parent / "outside-content.db")


if __name__ == "__main__":
    unittest.main(verbosity=2)
