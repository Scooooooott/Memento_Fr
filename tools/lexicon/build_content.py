"""Build the bundled, read-only French content database using only Python stdlib.

Run from anywhere: python -B tools/lexicon/build_content.py [--output PATH].
All writes, including SQLite temporary files, are restricted to this project.
Source data is editorial input: no network, OCR, random data, or timestamps.
"""
from __future__ import annotations

import argparse
from contextlib import closing
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import unicodedata

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "data/curated/french_a1.json"
PROVENANCE = ROOT / "data/curated/provenance.json"
SCHEMA = Path(__file__).with_name("schema.sql")
DEFAULT_OUTPUT = ROOT / "app/src/main/assets/french_content.db"
LEVEL = "A1"
BOOK_TITLE = "你好！法语 1 · A1 词表"
PERSONS = ("je", "tu", "il / elle / on", "nous", "vous", "ils / elles")
TENSES = ("présent", "passé composé", "imparfait")
AVOIR = ("ai", "as", "a", "avons", "avez", "ont")
POS = {"noun", "verb", "adjective", "adverb", "interjection", "preposition",
       "conjunction", "pronoun", "determiner", "proper_noun", "expression", "numeral"}
EXAMPLE_FIELDS = {"french", "english", "spanish", "chinese"}


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def project_path(path: Path) -> Path:
    resolved = path.resolve()
    require(resolved.is_relative_to(ROOT), f"Writes must stay inside {ROOT}: {path}")
    return resolved


def text_value(value: object, context: str) -> None:
    require(isinstance(value, str) and bool(value.strip()), f"Empty text: {context}")
    require(value == value.strip(), f"Surrounding whitespace: {context}")
    require(value == unicodedata.normalize("NFC", value), f"Non-NFC text: {context}")
    require(not any(ord(c) < 32 for c in value), f"Control character: {context}")
    require("TODO" not in value and not any(marker in value.lower() for marker in ("placeholder", "lorem ipsum")),
            f"Placeholder text: {context}")


def uid_lemma(lemma: str) -> str:
    return unicodedata.normalize("NFC", lemma).replace("’", "'").casefold()


def examples_by_sense(word: dict, sense_count: int) -> list[list[dict]]:
    """Map legacy positional examples and explicit sense_index examples."""
    groups: list[list[dict]] = [[] for _ in range(sense_count)]
    for position, example in enumerate(word.get("examples", [])):
        sense_index = example.get("sense_index", position)
        require(isinstance(sense_index, int) and 0 <= sense_index < sense_count,
                f"Example sense_index is out of range: {word.get('uid')} / {sense_index}")
        groups[sense_index].append(example)
    return groups


def load_source() -> tuple[dict, dict]:
    source = json.loads(SOURCE.read_text(encoding="utf-8"))
    provenance = json.loads(PROVENANCE.read_text(encoding="utf-8"))
    validate_source(source, provenance)
    return source, provenance


def validate_source(source: dict, provenance: dict) -> None:
    require(source.get("language") == "fr-FR", "Only fr-FR content is supported")
    for key in ("content_version", "scope", "review_date"):
        text_value(source.get(key), key)
    words = source.get("words", [])
    require(bool(words), "Content release has no lexemes")
    seen = set()
    source_numbers = set()
    all_occurrences = [occurrence for word in words for occurrence in word.get("source_entries", [])]
    max_source_number = max((occurrence.get("number", 0) for occurrence in all_occurrences), default=0)
    source_pages = [occurrence.get("page") for occurrence in all_occurrences if isinstance(occurrence.get("page"), int)]
    min_source_page = min(source_pages) if source_pages else 0
    max_source_page = max(source_pages) if source_pages else 0
    require(max_source_number > 0 and min_source_page > 0, "Content release has no textbook source rows")
    for word in words:
        uid, lemma, pos = word.get("uid"), word.get("lemma"), word.get("pos")
        text_value(lemma, "lemma")
        require(pos in POS, f"Unknown part of speech: {uid}")
        require(isinstance(uid, str) and re.fullmatch(r"fr:[^:]+:[a-z_]+:[1-9][0-9]*", uid) is not None,
                f"Invalid stable UID: {uid}")
        require(uid.rsplit(":", 2)[0] == f"fr:{uid_lemma(lemma)}" and uid.rsplit(":", 2)[1] == pos,
                f"UID does not match lemma/POS: {uid}")
        require(uid not in seen, f"Duplicate UID: {uid}")
        seen.add(uid)
        # Display capitalization is editorial (Paris, Français, Belge); the UID
        # carries the case-folded identity used for stable matching.
        ipa = word.get("ipa", "")
        text_value(ipa, f"{uid} IPA")
        require(ipa.startswith("/") and ipa.endswith("/") and len(ipa) > 2,
                f"Missing broad IPA delimiters: {uid}")
        senses, examples = word.get("senses", []), word.get("examples", [])
        require(len(senses) >= 1, f"Expected at least one core sense: {uid}")
        seen_senses = set()
        for index, sense in enumerate(senses):
            require(isinstance(sense, dict) and set(sense) == {"english", "spanish", "chinese"},
                    f"Sense EN/ES/ZH object required: {uid}")
            for language, value in sense.items():
                text_value(value, f"{uid} sense {index} {language}")
            require(re.search(r"[\u3400-\u9fff]", sense["chinese"]) is not None,
                    f"Chinese sense lacks Han text: {uid}")
            require(sense["chinese"] not in (sense["english"], sense["spanish"]),
                    f"Chinese sense duplicates another language: {uid}")
            identity = tuple(sense[key].casefold() for key in ("english", "spanish", "chinese"))
            require(identity not in seen_senses, f"Duplicate core sense: {uid} / {index}")
            seen_senses.add(identity)
        for index, example in enumerate(examples):
            require(isinstance(example, dict) and set(example) in (EXAMPLE_FIELDS, EXAMPLE_FIELDS | {"sense_index"}),
                    f"Example FR/EN/ES/ZH object required: {uid}")
            for language in EXAMPLE_FIELDS:
                value = example[language]
                text_value(value, f"{uid} example {index} {language}")
            require(re.search(r"[\u3400-\u9fff]", example["chinese"]) is not None,
                    f"Chinese example lacks Han text: {uid}")
            require(example["chinese"] not in (example["french"], example["english"], example["spanish"]),
                    f"Chinese example duplicates another language: {uid}")
            joined = " ".join(example[key] for key in EXAMPLE_FIELDS).casefold()
            require(not any(marker in joined for marker in (
                "vocabulaire de cette leçon", "lesson's vocabulary", "vocabulario de esta lección",
                "on utilise souvent cette expression", "this expression is often used",
            )), f"Meta example is not a usage example: {uid}")
        example_groups = examples_by_sense(word, len(senses))
        for sense_index, group in enumerate(example_groups):
            french_examples = [example["french"].casefold() for example in group]
            require(len(french_examples) == len(set(french_examples)),
                    f"Duplicate example for sense: {uid} / {sense_index}")
        forms = word.get("forms", [])
        for form in forms:
            require(isinstance(form, list) and len(form) == 2, f"Form label/value required: {uid}")
            for value in form:
                text_value(value, f"{uid} word form")
        if pos == "noun":
            require(word.get("gender") in ("m.", "f.", "m. / f."), f"Noun gender missing: {uid}")
            require(any("pluriel" in label or " / des " in value for label, value in forms),
                    f"Noun plural missing: {uid}")
        if pos == "adjective":
            require(any("féminin" in label for label, _ in forms), f"Adjective feminine missing: {uid}")
            require(any("pluriel" in label for label, _ in forms), f"Adjective plural missing: {uid}")
        refs = word.get("references", [])
        require(all(ref in provenance["sources"] for ref in refs), f"Unknown provenance reference: {uid}")
        for occurrence in word.get("source_entries", []):
            require(isinstance(occurrence, dict), f"Invalid textbook occurrence: {uid}")
            number, page = occurrence.get("number"), occurrence.get("page")
            require(isinstance(number, int) and 1 <= number <= max_source_number, f"Invalid textbook row: {uid}")
            require(isinstance(page, int) and min_source_page <= page <= max_source_page, f"Invalid textbook page: {uid}")
            text_value(occurrence.get("display"), f"{uid} textbook display")
            require(number not in source_numbers, f"Duplicate textbook row mapping: {number}")
            source_numbers.add(number)
        if pos == "verb":
            verb = word.get("verb", {})
            require(bool(refs), f"Verb reference required: {uid}")
            require(verb.get("auxiliary") in ("avoir", "être"), f"Verb auxiliary required: {uid}")
            for key in ("group", "past_participle", "present_participle"):
                text_value(verb.get(key), f"{uid} {key}")
            for key in ("present", "imperfect"):
                require(isinstance(verb.get(key), list) and len(verb[key]) == 6,
                        f"Exactly six persons required for {uid} {key}")
                for value in verb[key]:
                    text_value(value, f"{uid} {key}")
            if verb["auxiliary"] == "être":
                require(len(verb.get("compound", [])) == 6, f"Explicit être agreement required: {uid}")
                require(any(label == "accord du participe" for label, _ in forms), f"Agreement note required: {uid}")
            for value in verb.get("compound", []):
                text_value(value, f"{uid} compound")
        else:
            require("verb" not in word, f"Non-verb has conjugation data: {uid}")
    if source_numbers:
        require(source_numbers.issubset(set(range(1, max_source_number + 1))), "Textbook row mapping is out of range")


def conjugations(word: dict) -> list[tuple[str, int, int, str, str]]:
    verb = word["verb"]
    compound = verb.get("compound") or [f"{aux} {verb['past_participle']}" for aux in AVOIR]
    result = []
    for tense_order, (tense, values) in enumerate(zip(TENSES, (verb["present"], compound, verb["imperfect"]))):
        for person, (pronoun, value) in enumerate(zip(PERSONS, values), start=1):
            if person == 1 and value[0].lower() in "aeiouyàâäéèêëîïôöùûüœæh":
                pronoun = "j’"
            result.append((tense, tense_order, person, pronoun, value))
    return result


def key_forms(word: dict) -> list[list[str]]:
    forms = []
    if word["pos"] == "verb":
        verb = word["verb"]
        present = conjugations(word)[:6]
        for i in (0, 3, 5):
            _, _, _, pronoun, value = present[i]
            forms.append(["présent · " + pronoun, value])
        forms += [["participe passé", verb["past_participle"]],
                  ["participe présent", verb["present_participle"]],
                  ["auxiliaire", verb["auxiliary"]]]
    return forms + word.get("forms", [])


def validate_database(db: sqlite3.Connection) -> dict[str, int]:
    require(db.execute("PRAGMA integrity_check").fetchall() == [("ok",)], "SQLite integrity check failed")
    require(db.execute("PRAGMA foreign_key_check").fetchall() == [], "Dangling content references")
    require(db.execute("PRAGMA user_version").fetchone()[0] == 2, "Unsupported content schema")
    counts = {table: db.execute(f"SELECT count(*) FROM {table}").fetchone()[0]
              for table in ("lexeme", "sense", "example", "word_form", "verb_info", "conjugation_form", "pronunciation", "vocabulary_book")}
    require(counts["pronunciation"] == counts["lexeme"], "Every lexeme needs pronunciation")
    require(counts["conjugation_form"] == counts["verb_info"] * 18, "Verb tense coverage is incomplete")
    require(not db.execute("SELECT lexeme_uid FROM conjugation_form GROUP BY lexeme_uid, tense HAVING count(*) != 6").fetchall(),
            "A conjugation tense lacks six distinct persons")
    require(not db.execute("SELECT l.lexeme_uid FROM lexeme l LEFT JOIN sense s USING(lexeme_uid) GROUP BY l.lexeme_uid HAVING count(s.sense_id) < 1").fetchall(),
            "Core sense coverage is invalid")
    require(db.execute("SELECT count(*) FROM sense WHERE length(trim(chinese)) > 0").fetchone()[0] == counts["sense"],
            "Chinese sense coverage is incomplete")
    require(db.execute("SELECT count(*) FROM example WHERE length(trim(chinese)) > 0").fetchone()[0] == counts["example"],
            "Chinese example coverage is incomplete")
    require(db.execute("SELECT count(*) FROM book_lexeme WHERE book_id='essential-fr'").fetchone()[0] == counts["lexeme"],
            "Default book must contain every development lexeme")
    require(db.execute("SELECT count(*) FROM book_lexeme WHERE book_id='verbs-fr'").fetchone()[0] == counts["verb_info"],
            "Verb book coverage is incorrect")
    return counts


def build(output: Path = DEFAULT_OUTPUT) -> dict[str, int]:
    source, provenance = load_source()
    output = project_path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    temp_dir = project_path(ROOT / ".tools/lexicon-tmp")
    temp_dir.mkdir(parents=True, exist_ok=True)
    os.environ["SQLITE_TMPDIR"] = str(temp_dir)
    os.environ["TMPDIR"] = str(temp_dir)
    staging = project_path(output.with_suffix(output.suffix + ".building"))
    staging.unlink(missing_ok=True)
    try:
        with closing(sqlite3.connect(staging)) as db:
            db.execute("PRAGMA temp_store=MEMORY")
            db.executescript(SCHEMA.read_text(encoding="utf-8"))
            metadata = {
                "schema_version": "2", "content_version": source["content_version"],
                "language": source["language"], "scope": source["scope"],
                "source_sha256": hashlib.sha256(SOURCE.read_bytes()).hexdigest(),
                "provenance_json": json.dumps(provenance, ensure_ascii=False, sort_keys=True),
            }
            db.executemany("INSERT INTO content_meta VALUES (?,?)", sorted(metadata.items()))
            db.executemany("INSERT INTO vocabulary_book VALUES (?,?,?,?)", [
                ("essential-fr", BOOK_TITLE, source["scope"], 0),
                ("verbs-fr", f"Verbes {LEVEL} · {LEVEL} 动词", f"{sum(w['pos'] == 'verb' for w in source['words'])} 个动词；收录直陈式现在时、复合过去时、未完成过去时。", 1),
            ])
            verb_index = 0
            for index, word in enumerate(source["words"]):
                uid = word["uid"]
                refs = {"authorship": "mixed-curated-and-open-lexicon-content", "review_date": source["review_date"],
                        "references": word.get("references", []), "level_assignment": f"textbook-{LEVEL}-glossary",
                        "source_entries": word.get("source_entries", [])}
                db.execute("INSERT INTO lexeme VALUES (?,?,?,?,?,?,?,?,?)", (
                    uid, "fr-FR", word["lemma"], word["pos"], int(uid.rsplit(":", 1)[1]), LEVEL,
                    word.get("gender", ""), index, json.dumps(refs, ensure_ascii=False, sort_keys=True)
                ))
                db.execute("INSERT INTO pronunciation VALUES (?,?,?,?)", (uid, "fr-FR", word["ipa"], None))
                example_groups = examples_by_sense(word, len(word["senses"]))
                for order, sense in enumerate(word["senses"]):
                    sense_id = f"{uid}:sense:{order + 1}"
                    db.execute(
                        "INSERT INTO sense (sense_id,lexeme_uid,sort_order,english,spanish,chinese,is_core) "
                        "VALUES (?,?,?,?,?,?,?)",
                        (sense_id, uid, order, sense["english"], sense["spanish"], sense["chinese"], 1),
                    )
                    for example_order, example in enumerate(example_groups[order]):
                        db.execute(
                            "INSERT INTO example (example_id,sense_id,sort_order,french,english,spanish,chinese) "
                            "VALUES (?,?,?,?,?,?,?)",
                            (f"{sense_id}:example:{example_order + 1}", sense_id, example_order,
                             example["french"], example["english"], example["spanish"], example["chinese"]),
                        )
                for order, (label, value) in enumerate(key_forms(word)):
                    db.execute("INSERT INTO word_form VALUES (?,?,?,?)", (uid, order, label, value))
                db.execute("INSERT INTO book_lexeme VALUES (?,?,?)", ("essential-fr", uid, index))
                if word["pos"] == "verb":
                    verb = word["verb"]
                    db.execute("INSERT INTO verb_info VALUES (?,?,?,?,?)", (
                        uid, verb["group"], verb["auxiliary"], verb["past_participle"], verb["present_participle"]))
                    for tense, tense_order, person, pronoun, value in conjugations(word):
                        db.execute("INSERT INTO conjugation_form VALUES (?,?,?,?,?,?,?)", (
                            uid, "indicatif", tense, tense_order, person, pronoun, value))
                    db.execute("INSERT INTO book_lexeme VALUES (?,?,?)", ("verbs-fr", uid, verb_index))
                    verb_index += 1
            counts = validate_database(db)
            db.commit()
            db.execute("VACUUM")
        # A completed, validated asset replaces the previous content atomically.
        staging.replace(output)
        return counts
    finally:
        staging.unlink(missing_ok=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--source", type=Path, default=SOURCE)
    parser.add_argument("--provenance", type=Path, default=PROVENANCE)
    parser.add_argument("--level", default=LEVEL)
    parser.add_argument("--book-title", default=BOOK_TITLE)
    args = parser.parse_args()
    SOURCE = project_path(args.source)
    PROVENANCE = project_path(args.provenance)
    LEVEL = args.level
    BOOK_TITLE = args.book_title
    print(json.dumps(build(args.output), ensure_ascii=False, indent=2))
