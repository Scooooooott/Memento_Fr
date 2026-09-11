from __future__ import annotations

import csv
import json
from pathlib import Path
import sys
import unicodedata
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).parent))
from fetch_a1_open_lexicon import query_for


def visible(node: ET.Element) -> str:
    out = node.text or ""
    for child in node:
        if child.tag == "b":
            out += " "
        elif child.tag != "s":
            out += visible(child)
        out += child.tail or ""
    return " ".join(out.split())


def key(value: str) -> str:
    return unicodedata.normalize("NFC", value).casefold().replace("’", "'").strip()


def apertium_map() -> dict[str, list[dict]]:
    root = ET.parse(ROOT / "data/raw/open_lexicon/apertium-fra-spa.fra-spa.dix").getroot()
    result: dict[str, list[dict]] = {}
    for entry in root.findall(".//e"):
        if entry.get("r") == "RL":
            continue
        pair = entry.find("p")
        if pair is None:
            continue
        left, right = pair.find("l"), pair.find("r")
        if left is None or right is None:
            continue
        source, target = visible(left), visible(right)
        if not source or not target:
            continue
        result.setdefault(key(source), []).append({
            "spanish": target,
            "source_tags": [item.get("n") for item in left.findall(".//s")],
            "target_tags": [item.get("n") for item in right.findall(".//s")],
        })
    return result


def main() -> None:
    with (ROOT / "data/raw/textbook_a1_headwords.csv").open(encoding="utf-8-sig", newline="") as stream:
        rows = list(csv.DictReader(stream))
    snapshot = json.loads((ROOT / "data/raw/open_lexicon/wiktapi_a1_snapshot.json").read_text(encoding="utf-8"))["entries"]
    apertium = apertium_map()
    queries = sorted({query_for(row["法语词条"]) for row in rows})
    missing_en, missing_es, missing_ipa = [], [], []
    both_es = 0
    for query in queries:
        data = snapshot[query]
        en = bool((data.get("en_definitions") or {}).get("definitions"))
        es_wikt = bool((data.get("es_definitions") or {}).get("definitions"))
        es_ap = bool(apertium.get(key(query)))
        ipa = bool((data.get("en_pronunciations") or {}).get("pronunciations"))
        if not en: missing_en.append(query)
        if not (es_wikt or es_ap): missing_es.append(query)
        if not ipa: missing_ipa.append(query)
        if es_wikt and es_ap: both_es += 1
    print(json.dumps({
        "queries": len(queries), "apertium_keys": len(apertium),
        "english_covered": len(queries)-len(missing_en),
        "spanish_covered": len(queries)-len(missing_es),
        "spanish_both_sources": both_es,
        "ipa_covered": len(queries)-len(missing_ipa),
        "missing_english": missing_en,
        "missing_spanish": missing_es,
        "missing_ipa": missing_ipa,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
