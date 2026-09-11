"""Create a reviewed-input snapshot of Chinese translations for existing examples.

This is a refresh utility, not part of the offline content build.  It sends only
the already-published French example sentences to the translation endpoint and
stores a one-to-one local snapshot consumed by ``build_a1_source.py``.
"""
from __future__ import annotations

import json
from pathlib import Path
import re
import time
from urllib.parse import urlencode
from urllib.request import Request, urlopen


ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "data/curated/french_a1.json"
OUTPUT = ROOT / "data/raw/example_zh_translations.json"
ENDPOINT = "https://translate.googleapis.com/translate_a/single"
BATCH_SIZE = 24


def translate_batch(sentences: list[str]) -> list[str]:
    numbered = "\n".join(f"[{index}] {sentence}" for index, sentence in enumerate(sentences))
    body = urlencode({"client": "gtx", "sl": "fr", "tl": "zh-CN", "dt": "t", "q": numbered}).encode()
    request = Request(ENDPOINT, data=body, headers={"User-Agent": "FrenchVocabularyContentBuilder/1.0"})
    with urlopen(request, timeout=45) as response:
        payload = json.loads(response.read().decode("utf-8"))
    translated = "".join(segment[0] for segment in payload[0] if segment and segment[0])
    matches = list(re.finditer(r"^\[(\d+)]\s*", translated, flags=re.MULTILINE))
    result = []
    for position, match in enumerate(matches):
        index = int(match.group(1))
        if index != position:
            raise ValueError(f"Translation batch markers changed: expected {position}, got {index}")
        end = matches[position + 1].start() if position + 1 < len(matches) else len(translated)
        result.append(translated[match.end():end].strip())
    if len(result) != len(sentences) or any(not value for value in result):
        raise ValueError(f"Translation batch returned {len(result)} of {len(sentences)} entries: {translated!r}")
    return result


def main() -> None:
    source = json.loads(SOURCE.read_text(encoding="utf-8"))
    french = list(dict.fromkeys(
        example["french"] if isinstance(example, dict) else example[0]
        for word in source["words"] for example in word["examples"]
    ))
    translations: dict[str, str] = {}
    if OUTPUT.exists():
        previous = json.loads(OUTPUT.read_text(encoding="utf-8"))
        translations.update({item["french"]: item["chinese"] for item in previous.get("translations", [])})
    pending = [sentence for sentence in french if sentence not in translations]
    for offset in range(0, len(pending), BATCH_SIZE):
        batch = pending[offset:offset + BATCH_SIZE]
        for attempt in range(4):
            try:
                translated = translate_batch(batch)
                break
            except Exception:
                if attempt == 3:
                    raise
                time.sleep(1.5 * (attempt + 1))
        translations.update(zip(batch, translated))
        snapshot = {
            "source": "Google Translate public web endpoint",
            "source_language": "fr",
            "target_language": "zh-CN",
            "source_content_version": source["content_version"],
            "translations": [{"french": sentence, "chinese": translations[sentence]} for sentence in french if sentence in translations],
        }
        OUTPUT.write_text(json.dumps(snapshot, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"translated {min(offset + len(batch), len(pending))}/{len(pending)} pending; {len(translations)}/{len(french)} total", flush=True)


if __name__ == "__main__":
    main()
