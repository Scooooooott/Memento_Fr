"""Fetch French Wiktionary pages for the FLELex A1 headwords.

The result is a checked-in/reusable local snapshot for the content builder;
the normal SQLite build never needs network access.
"""
from __future__ import annotations

import argparse
import csv
import json
import time
from pathlib import Path
from urllib.parse import quote
from urllib.error import HTTPError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CSV = ROOT / "outputs/flelex-cefr-sorted/flelex_A1.csv"
DEFAULT_OUTPUT = ROOT / "data/raw/open_lexicon/flelex_a1_wiktionary.json"


def candidates(word: str) -> list[str]:
    values = [word, word.replace("’", "'")]
    stripped = " ".join(word.split())
    for value in (stripped, stripped.replace("’", "'")):
        if value not in values:
            values.append(value)
    no_markers = " ".join(part for part in stripped.split() if not (part.startswith("(") and part.endswith(")")))
    if no_markers and no_markers not in values:
        values.append(no_markers)
    if " / " in word:
        values.append(word.split(" / ", 1)[0].strip())
    return list(dict.fromkeys(value for value in values if value))


def fetch(titles: list[str]) -> list[dict]:
    url = (
        "https://fr.wiktionary.org/w/api.php?action=query&titles="
        f"{quote('|'.join(titles))}&prop=revisions&rvprop=content&rvslots=main&format=json&origin=*"
    )
    request = Request(url, headers={"User-Agent": "Codex-language-content/1.0"})
    with urlopen(request, timeout=45) as response:
        payload = json.load(response)
    return [
        {
            "title": page.get("title"),
            "pageid": page.get("pageid"),
            "wikitext": page.get("revisions", [{}])[0].get("slots", {}).get("main", {}).get("*"),
        }
        for page in payload.get("query", {}).get("pages", {}).values()
    ]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--csv", type=Path, default=DEFAULT_CSV)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--batch-size", type=int, default=50)
    args = parser.parse_args()

    with args.csv.open(encoding="utf-8-sig", newline="") as stream:
        words = list(dict.fromkeys(row["word"].strip() for row in csv.DictReader(stream) if row["word"].strip()))
    cache: dict[str, dict | None] = {}
    if args.output.exists():
        cache = json.loads(args.output.read_text(encoding="utf-8"))

    pending = [word for word in words if not cache.get(word, {}).get("wikitext")]
    title_to_words: dict[str, list[str]] = {}
    for word in pending:
        for title in candidates(word):
            title_to_words.setdefault(title, []).append(word)
    titles = list(title_to_words)
    found = 0
    for start in range(0, len(titles), args.batch_size):
        batch = titles[start:start + args.batch_size]
        for attempt in range(4):
            try:
                pages = fetch(batch)
                break
            except HTTPError as error:
                if error.code != 429 or attempt == 3:
                    raise
                retry_after = error.headers.get("Retry-After")
                delay = max(10, int(retry_after)) if retry_after and retry_after.isdigit() else 20 * (attempt + 1)
                print(f"rate limited; waiting {delay}s", flush=True)
                time.sleep(delay)
            except Exception:
                if attempt == 3:
                    raise
                time.sleep(3 * (attempt + 1))
        for page in pages:
            if not page.get("wikitext"):
                continue
            for word in title_to_words.get(page["title"], []):
                if not cache.get(word, {}).get("wikitext"):
                    cache[word] = page
                    found += 1
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(cache, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"batch {min(start + len(batch), len(titles))}/{len(titles)}; added {found}", flush=True)

    hit = sum(bool(cache.get(word, {}).get("wikitext")) for word in words)
    print(json.dumps({"words": len(words), "added": found, "hit": hit, "missing": len(words) - hit}, ensure_ascii=False))


if __name__ == "__main__":
    main()
