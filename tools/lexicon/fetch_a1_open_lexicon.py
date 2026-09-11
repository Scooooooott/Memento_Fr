"""Fetch a compact, reproducible snapshot for the textbook A1 headwords.

This is an explicit network refresh tool. The normal content build never uses the
network. Responses are cached under .tools and a compact snapshot is written to
data/raw/open_lexicon for later offline curation.
"""
from __future__ import annotations

import csv
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import json
from pathlib import Path
import re
import time
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]
HEADWORDS = ROOT / "data/raw/textbook_a1_headwords.csv"
CACHE = ROOT / ".tools/lexicon-source/wiktapi"
OUTPUT = ROOT / "data/raw/open_lexicon/wiktapi_a1_snapshot.json"
KAIKKI_OUTPUT = ROOT / "data/raw/open_lexicon/kaikki_a1_snapshot.json"
BASE = "https://api.wiktapi.dev/v1"

QUERY_OVERRIDES = {
    "faire des / les courses": "faire les courses",
    "arrêter de faire qch.": "arrêter de",
    "faire attention à...": "faire attention à",
    "avoir envie de qch. / faire qch.": "avoir envie de",
    "essayer de faire qch.": "essayer de",
    "demander à qn de faire qch.": "demander à",
    "commencer à faire qch.": "commencer à",
    "parler de qn / qch.": "parler de",
    "parler à qn": "parler à",
    "parler de... à qn": "parler de",
    "téléphoner à qn": "téléphoner à",
    "quelque chose (=qch.)": "quelque chose",
    "petit(-)déjeuner": "petit-déjeuner",
    "serveur,se": "serveur",
    "Bonne année !": "bonne année",
}


def query_for(display: str) -> str:
    if display in QUERY_OVERRIDES:
        return QUERY_OVERRIDES[display]
    value = display.strip().rstrip(".!…")
    value = re.sub(r"\s*\((?:la|le)\)$", "", value, flags=re.I)
    value = re.sub(r"\s*\([^)]*(?:pl\.|pluriel|travaux|chevaux|cadeaux|yeux|œufs|toute|tous|toutes|vieille|nouvel|nouvelle|bel|belle|beaux|belles|grands-|mesdames)[^)]*\)", "", value, flags=re.I)
    value = re.sub(r"\([^)]*\)", "", value)
    value = value.replace("(-)", "-").strip()
    return re.sub(r"\s+", " ", value)


def cache_path(edition: str, query: str, resource: str) -> Path:
    key = hashlib.sha1(f"{edition}\0{query}\0{resource}".encode()).hexdigest()
    return CACHE / f"{key}.json"


def fetch(edition: str, query: str, resource: str) -> dict | None:
    target = cache_path(edition, query, resource)
    if target.exists():
        cached = json.loads(target.read_text(encoding="utf-8"))
        return cached.get("data")
    url = f"{BASE}/{edition}/word/{quote(query, safe='')}/{resource}?lang=fr"
    data = None
    status = 0
    error = ""
    for attempt in range(4):
        try:
            with urlopen(Request(url, headers={"User-Agent": "FrenchVocab/0.3 content-build"}), timeout=30) as response:
                status = response.status
                data = json.loads(response.read().decode("utf-8"))
            break
        except HTTPError as exc:
            status = exc.code
            if exc.code == 404:
                break
            error = str(exc)
        except (URLError, TimeoutError, json.JSONDecodeError) as exc:
            error = str(exc)
        time.sleep(0.5 * (attempt + 1))
    target.write_text(json.dumps({"url": url, "status": status, "error": error, "data": data}, ensure_ascii=False), encoding="utf-8")
    return data


def fetch_kaikki(query: str) -> list[dict]:
    target = cache_path("en-kaikki", query, "entry")
    if target.exists():
        return json.loads(target.read_text(encoding="utf-8")).get("data") or []
    first, first_two = query[:1], query[:2]
    url = "https://kaikki.org/dictionary/French/meaning/{}/{}/{}.jsonl".format(
        quote(first, safe=""), quote(first_two, safe=""), quote(query, safe=""))
    data: list[dict] = []
    status = 0
    error = ""
    for attempt in range(4):
        try:
            with urlopen(Request(url, headers={"User-Agent": "FrenchVocab/0.3 content-build"}), timeout=45) as response:
                status = response.status
                text = response.read().decode("utf-8")
                data = [json.loads(line) for line in text.splitlines() if line.strip()]
            break
        except HTTPError as exc:
            status = exc.code
            if exc.code == 404:
                break
            error = str(exc)
        except (URLError, TimeoutError, json.JSONDecodeError) as exc:
            error = str(exc)
        time.sleep(0.5 * (attempt + 1))
    target.write_text(json.dumps({"url": url, "status": status, "error": error, "data": data}, ensure_ascii=False), encoding="utf-8")
    return data


def main() -> None:
    CACHE.mkdir(parents=True, exist_ok=True)
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    with HEADWORDS.open(encoding="utf-8-sig", newline="") as stream:
        source = list(csv.DictReader(stream))
    queries = sorted({query_for(row["法语词条"]) for row in source})
    results: dict[str, dict] = {query: {} for query in queries}
    jobs = [(query, edition, resource) for query in queries for edition, resource in (
        ("en", "definitions"), ("es", "definitions"), ("en", "pronunciations"))]
    with ThreadPoolExecutor(max_workers=12) as pool:
        futures = {pool.submit(fetch, edition, query, resource): (query, edition, resource) for query, edition, resource in jobs}
        for done, future in enumerate(as_completed(futures), start=1):
            query, edition, resource = futures[future]
            results[query][f"{edition}_{resource}"] = future.result()
            if done % 250 == 0:
                print(f"Fetched {done}/{len(jobs)} resources", flush=True)
    verb_queries = []
    for query, value in results.items():
        definitions = (value.get("en_definitions") or {}).get("definitions", [])
        if any(item.get("pos") == "verb" for item in definitions):
            verb_queries.append(query)
    with ThreadPoolExecutor(max_workers=8) as pool:
        futures = {pool.submit(fetch, "en", query, "forms"): query for query in verb_queries}
        for done, future in enumerate(as_completed(futures), start=1):
            query = futures[future]
            results[query]["en_forms"] = future.result()
            if done % 50 == 0:
                print(f"Fetched verb forms {done}/{len(verb_queries)}", flush=True)
    snapshot = {
        "source": "WiktApi structured extraction of English and Spanish Wiktionary editions",
        "api": BASE,
        "license": "CC BY-SA; see https://foundation.wikimedia.org/wiki/Policy:Terms_of_Use",
        "headword_count": len(source),
        "query_count": len(queries),
        "query_overrides": QUERY_OVERRIDES,
        "entries": results,
    }
    OUTPUT.write_text(json.dumps(snapshot, ensure_ascii=False, indent=2), encoding="utf-8")
    kaikki: dict[str, list[dict]] = {}
    with ThreadPoolExecutor(max_workers=10) as pool:
        futures = {pool.submit(fetch_kaikki, query): query for query in queries}
        for done, future in enumerate(as_completed(futures), start=1):
            kaikki[futures[future]] = future.result()
            if done % 100 == 0:
                print(f"Fetched Kaikki {done}/{len(queries)} entries", flush=True)
    KAIKKI_OUTPUT.write_text(json.dumps({
        "source": "Kaikki.org postprocessed extraction of English Wiktionary",
        "license": "CC BY-SA; see https://foundation.wikimedia.org/wiki/Policy:Terms_of_Use",
        "entries": kaikki,
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"headwords": len(source), "queries": len(queries), "verbs": len(verb_queries), "wiktapi": str(OUTPUT), "kaikki": str(KAIKKI_OUTPUT)}, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
