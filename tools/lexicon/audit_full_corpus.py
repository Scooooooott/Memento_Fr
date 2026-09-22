"""Run a deterministic full-corpus content audit and write a compact report."""
from __future__ import annotations

from collections import Counter
import json
from pathlib import Path
import random
import re
import sqlite3
import sys

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")


ROOT = Path(__file__).resolve().parents[2]
DB_PATH = ROOT / "app/src/main/assets/french_content.db"
OVERRIDE_PATHS = (
    ROOT / "data/curated/review-overrides/french_meanings_full.json",
    ROOT / "data/curated/review-overrides/french_meanings_additional.json",
    ROOT / "data/curated/review-overrides/french_meanings_residual.json",
    ROOT / "data/curated/review-overrides/french_meanings_followup.json",
)
REPORT_JSON = ROOT / "outputs/full-corpus-quality-2026-09-14.json"
REPORT_MD = ROOT / "outputs/full-corpus-quality-2026-09-14.md"
HAN = re.compile(r"[\u3400-\u9fff]")
TEMPLATE_PATTERNS = (
    r"^Nous avons parlé de .+ pendant le dîner\.$",
    r"^Nous allons .+ demain matin\.$",
    r"^Je vais .+ demain matin\.$",
    r"^Cette idée est .+\.$",
    r"^Le résultat est .+\.$",
    r"^.+ est au centre de la discussion\.$",
    r"^Elle répond .+ à la question\.$",
    r"^Le livre est .+ sur la table\.$",
    r"^Il dit « .+ » en souriant\.$",
    r"^On utilise « .+ » dans cette phrase\.$",
    r"^Le pronom « .+ » apparaît dans cette phrase\.$",
    r"^Le déterminant « .+ » accompagne un nom dans cette phrase\.$",
    r"^Le mot « .+ » accompagne un nom dans cette phrase : .+ projet avance rapidement\.$",
    r"^Cette situation paraît .+\.$",
    r"^Ce mot est un masculin\.$",
    r"^Voici le mot\.$",
    r"^Faut faire sortir le méchant faire sortir le méchant\.$",
    r"^Un ciel de lit ciel de lit\.$",
    r"^faire la paix Faire la paix\.$",
    r"<br|\]\]",
)


def main() -> None:
    with sqlite3.connect(DB_PATH) as db:
        db.row_factory = sqlite3.Row
        senses = db.execute(
            "SELECT l.sort_order AS lexeme_order, l.lexeme_uid, l.lemma, "
            "l.part_of_speech, s.sort_order AS sense_index, s.english, "
            "s.spanish, s.chinese, s.sense_id FROM lexeme l JOIN sense s "
            "ON s.lexeme_uid=l.lexeme_uid ORDER BY l.sort_order, s.sort_order"
        ).fetchall()
        examples = db.execute(
            "SELECT sense_id, french, english, spanish, chinese FROM example "
            "ORDER BY sense_id, sort_order"
        ).fetchall()
    examples_by_sense: dict[str, list[sqlite3.Row]] = {}
    for row in examples:
        examples_by_sense.setdefault(row["sense_id"], []).append(row)

    rng = random.Random(20260914)
    sample_size = round(len(senses) * 0.20)
    sample = rng.sample(list(senses), sample_size)
    sample_ids = {row["sense_id"] for row in sample}
    template_hits = []
    empty_fields = []
    language_pollution = []
    no_han = []
    for row in senses:
        if not all(str(row[field]).strip() for field in ("english", "spanish", "chinese")):
            empty_fields.append(row["lexeme_uid"])
        if not HAN.search(row["chinese"] or ""):
            no_han.append({"uid": row["lexeme_uid"], "lemma": row["lemma"], "sense_index": row["sense_index"], "chinese": row["chinese"]})
        for example in examples_by_sense.get(row["sense_id"], []):
            if any(re.match(pattern, example["french"]) for pattern in TEMPLATE_PATTERNS):
                template_hits.append({"uid": row["lexeme_uid"], "french": example["french"]})
            if HAN.search(example["english"] or "") or HAN.search(example["spanish"] or "") or not HAN.search(example["chinese"] or ""):
                language_pollution.append({"uid": row["lexeme_uid"], "french": example["french"]})

    sample_meaning_issues = []
    sample_example_issues = []
    for row in sample:
        if not HAN.search(row["chinese"] or ""):
            sample_meaning_issues.append({"uid": row["lexeme_uid"], "lemma": row["lemma"], "issue": "chinese_without_han"})
        for example in examples_by_sense.get(row["sense_id"], []):
            if any(re.match(pattern, example["french"]) for pattern in TEMPLATE_PATTERNS):
                sample_example_issues.append({"uid": row["lexeme_uid"], "french": example["french"], "issue": "template_example"})
            if HAN.search(example["english"] or "") or HAN.search(example["spanish"] or "") or not HAN.search(example["chinese"] or ""):
                sample_example_issues.append({"uid": row["lexeme_uid"], "french": example["french"], "issue": "language_script_mismatch"})

    overrides = {}
    for path in OVERRIDE_PATHS:
        overrides.update(json.loads(path.read_text(encoding="utf-8"))["overrides"])
    report = {
        "date": "2026-09-14",
        "database": str(DB_PATH.relative_to(ROOT)).replace("\\", "/"),
        "lexemes": len({row["lexeme_uid"] for row in senses}),
        "senses": len(senses),
        "examples": len(examples),
        "deterministic_sample": {"seed": 20260914, "ratio": 0.20, "senses": sample_size},
        "full_scan": {
            "empty_meaning_fields": len(empty_fields),
            "chinese_senses_without_han": len(no_han),
            "template_examples": len(template_hits),
            "example_language_script_mismatches": len(language_pollution),
        },
        "sample_scan": {
            "meaning_issues": len(sample_meaning_issues),
            "example_issues": len(sample_example_issues),
        },
        "manual_meaning_overrides": len(overrides),
        "remaining_no_han_sample": no_han[:100],
        "sample_issue_examples": {"meanings": sample_meaning_issues[:100], "examples": sample_example_issues[:100]},
    }
    REPORT_JSON.parent.mkdir(parents=True, exist_ok=True)
    REPORT_JSON.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    REPORT_MD.write_text(
        "# 法语词库全量内容审计\n\n"
        f"日期：{report['date']}。数据库包含 {report['lexemes']} 个词条、{report['senses']} 个义项、{report['examples']} 条例句。\n\n"
        f"固定随机种子 `20260914` 抽查义项的 20%：{sample_size} 条。\n\n"
        "## 全量结果\n\n"
        f"- 空释义字段：{report['full_scan']['empty_meaning_fields']}。\n"
        f"- 例句模板残留：{report['full_scan']['template_examples']}。\n"
        f"- 例句跨语种/脚本污染：{report['full_scan']['example_language_script_mismatches']}。\n"
        f"- 中文释义无汉字：{report['full_scan']['chinese_senses_without_han']}；主要是缩写、专名和原始词条缺失，详见 JSON 清单。\n"
        f"- 已落盘的高置信度人工释义修复：{report['manual_meaning_overrides']} 个 UID。\n\n"
        "## 抽查结果\n\n"
        f"- 抽查释义异常（结构/脚本规则）：{report['sample_scan']['meaning_issues']}。\n"
        f"- 抽查例句异常（模板/脚本规则）：{report['sample_scan']['example_issues']}。\n\n"
        "语义覆盖仍需人工判断的缩写、专名和源数据缺失条目没有用模型臆造；它们列在 JSON 的 `remaining_no_han_sample` 中。\n",
        encoding="utf-8",
    )
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
