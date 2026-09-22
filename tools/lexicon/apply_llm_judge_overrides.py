"""Apply high-confidence LLM-as-judge content corrections to curated JSON and SQLite.

The judge reports are semantic review artifacts; this command only applies entries
explicitly marked high-confidence and not unresolved.  It keeps a durable registry
under data/curated/review-overrides so later rebuilds can reproduce the edit.
"""
from __future__ import annotations

from contextlib import closing
import json
from pathlib import Path
import shutil
import sqlite3
import tempfile


ROOT = Path(__file__).resolve().parents[2]
REPORT_DIR = ROOT / "outputs/llm-judge"
CURATED_DIR = ROOT / "data/curated"
DB_PATH = ROOT / "app/src/main/assets/french_content.db"
REGISTRY_PATH = CURATED_DIR / "review-overrides/french_llm_judge_2026-09-16.json"
UID_ALIASES = {
    "fr:CV:proper:1": "fr:cv:proper_noun:1",
    "fr:dépense:verb:1": "fr:dépenser:verb:1",
}


def load_candidates() -> list[dict]:
    rows: list[dict] = []
    for path in sorted(REPORT_DIR.glob("judge_*.jsonl")):
        for line in path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                item = json.loads(line)
                if item.get("confidence") == "high" and item.get("unresolved") is not True:
                    rows.append(item)
    return rows


def source_index() -> dict[str, tuple[Path, dict]]:
    result: dict[str, tuple[Path, dict]] = {}
    for path in sorted(CURATED_DIR.glob("*.json")):
        if not path.name.startswith("flelex_"):
            continue
        payload = json.loads(path.read_text(encoding="utf-8"))
        for word in payload.get("words", []):
            if isinstance(word, dict) and word.get("uid"):
                result[word["uid"]] = (path, word)
    return result


def merge_candidates(rows: list[dict], valid_uids: set[str]) -> dict[str, dict[str, dict[str, str]]]:
    merged: dict[str, dict[str, dict[str, str]]] = {}
    for row in rows:
        uid = UID_ALIASES.get(row.get("uid"), row.get("uid"))
        if uid not in valid_uids:
            continue
        sense = row.get("proposed_sense_fields") or {}
        example = row.get("proposed_example_fields") or {}
        if not sense and not example:
            continue
        # Do not persist an explicitly meta/discussion filler sentence.
        french = str(example.get("french", "")).casefold()
        if "nous en parlons" in french or "ce mot" in french or "ce terme" in french:
            example = {k: v for k, v in example.items() if k != "french"}
        entry = merged.setdefault(uid, {"sense": {}, "example": {}})
        for key, value in sense.items():
            if key in {"english", "spanish", "chinese"} and isinstance(value, str) and value.strip():
                entry["sense"][key] = value
        for key, value in example.items():
            if key in {"french", "english", "spanish", "chinese"} and isinstance(value, str) and value.strip():
                entry["example"][key] = value
    if "fr:actuellement:adjective:1" in merged:
        merged["fr:actuellement:adjective:1"]["example"] = {
            "french": "Le musée est actuellement fermé pour travaux.",
            "english": "The museum is currently closed for renovations.",
            "spanish": "El museo está cerrado actualmente por obras.",
            "chinese": "博物馆目前因装修而关闭。",
        }
    if "fr:formule:noun:1" in merged:
        merged["fr:formule:noun:1"]["example"] = {
            "french": "La formule chimique empirique du méthane est CH₄.",
            "english": "The empirical chemical formula of methane is CH₄.",
            "spanish": "La fórmula química empírica del metano es CH₄.",
            "chinese": "甲烷的实验化学式是 CH₄。",
        }
    return {uid: value for uid, value in merged.items() if value["sense"] or value["example"]}


def apply_sources(overrides: dict[str, dict[str, dict[str, str]]], index: dict[str, tuple[Path, dict]]) -> int:
    changed_files: set[Path] = set()
    changed_words = 0
    for uid, fields in overrides.items():
        path, word = index[uid]
        changed = False
        senses = word.get("senses") or []
        if senses:
            for key, value in fields["sense"].items():
                if senses[0].get(key) != value:
                    senses[0][key] = value
                    changed = True
        examples = word.get("examples") or []
        if examples:
            for key, value in fields["example"].items():
                if examples[0].get(key) != value:
                    examples[0][key] = value
                    changed = True
        if changed:
            changed_words += 1
            changed_files.add(path)
    for path in sorted(changed_files):
        payload = json.loads(path.read_text(encoding="utf-8"))
        for uid, fields in overrides.items():
            if index[uid][0] != path:
                continue
            for word in payload.get("words", []):
                if word.get("uid") != uid:
                    continue
                if word.get("senses"):
                    word["senses"][0].update(fields["sense"])
                if word.get("examples"):
                    word["examples"][0].update(fields["example"])
        tmp = path.with_suffix(path.suffix + ".llm-review-building")
        tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        tmp.replace(path)
    return changed_words


def apply_db(overrides: dict[str, dict[str, dict[str, str]]]) -> int:
    with tempfile.NamedTemporaryFile(suffix=".db", prefix=".french-content-llm-", dir=DB_PATH.parent, delete=False) as handle:
        tmp = Path(handle.name)
    try:
        shutil.copy2(DB_PATH, tmp)
        with closing(sqlite3.connect(tmp)) as db:
            db.execute("PRAGMA foreign_keys=ON")
            changed = 0
            for uid, fields in overrides.items():
                for key, value in fields["sense"].items():
                    changed += db.execute(f"UPDATE sense SET {key}=? WHERE lexeme_uid=? AND sort_order=0", (value, uid)).rowcount
                for key, value in fields["example"].items():
                    changed += db.execute(f"UPDATE example SET {key}=? WHERE sense_id=(SELECT sense_id FROM sense WHERE lexeme_uid=? AND sort_order=0) AND sort_order=0", (value, uid)).rowcount
            db.execute("INSERT OR REPLACE INTO content_meta(key,value) VALUES (?,?)", ("french_llm_judge_review_2026-09-16", json.dumps({"uids": len(overrides)}, ensure_ascii=False)))
            db.commit()
            if db.execute("PRAGMA integrity_check").fetchone()[0] != "ok" or db.execute("PRAGMA foreign_key_check").fetchall():
                raise RuntimeError("SQLite integrity check failed")
        tmp.replace(DB_PATH)
        return changed
    finally:
        tmp.unlink(missing_ok=True)


def main() -> None:
    rows = load_candidates()
    index = source_index()
    overrides = merge_candidates(rows, set(index))
    REGISTRY_PATH.write_text(json.dumps({"schema_version": 1, "review_date": "2026-09-16", "scope": "llm-as-judge-high-confidence-partial", "source_reports": [p.name for p in sorted(REPORT_DIR.glob("judge_*.jsonl"))], "candidate_rows": len(rows), "applied_uids": len(overrides), "overrides": overrides}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    words = apply_sources(overrides, index)
    fields = apply_db(overrides)
    print(json.dumps({"candidate_rows": len(rows), "applied_uids": len(overrides), "source_words_changed": words, "db_field_updates": fields}, ensure_ascii=False))


if __name__ == "__main__":
    main()
