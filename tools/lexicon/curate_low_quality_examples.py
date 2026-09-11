"""Replace template-like example sentences in the bundled content database.

The database currently contains a mixture of textbook, Wiktionary and FLELex
content.  Some FLELex rows fell back to generic POS templates such as
``J'ai choisi ... pour le dîner`` or ``Cette idée est ...``.  This utility
finds those rows, prefers a French example from the local Wiktionary snapshot,
and otherwise writes a short editorial example.  New sentences are translated
in batches and the database is replaced atomically after validation.

Run from the project root, for example:

    python tools/lexicon/curate_low_quality_examples.py --dry-run
    python tools/lexicon/curate_low_quality_examples.py --allow-network
"""
from __future__ import annotations

import argparse
from collections import Counter
import json
from pathlib import Path
import re
import shutil
import sqlite3
import sys
import time
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")


ROOT = Path(__file__).resolve().parents[2]
DB_PATH = ROOT / "app/src/main/assets/french_content.db"
RAW_PATHS = (
    ROOT / "data/raw/open_lexicon/flelex_a1_wiktionary.json",
    ROOT / "data/raw/open_lexicon/flelex_a2_wiktionary.json",
)
CACHE_PATH = ROOT / ".tools/example-quality-translation-cache.json"
AUDIT_PATH = ROOT / "outputs/example-quality-review.json"
BATCH_SIZE = 24
LEGACY_CACHE_PATHS = (
    ROOT / "tmp/flelex-content/translation_cache.json",
    ROOT / "tmp/a2-content/translation_cache.json",
    ROOT / "tmp/a2-content/translation_cache_b1.json",
)


def norm(value: str) -> str:
    return " ".join(value.replace("’", "'").split()).casefold()


def surface(lemma: str) -> str:
    """Return a usable headword from FLELex's occasional editorial markers."""
    value = re.sub(r"\([^)]*\)", "", lemma).strip()
    value = value.split(",", 1)[0].strip()
    value = re.sub(r"\s*=.*$", "", value).strip()
    value = re.sub(r"\s*-\s*", "-", value)
    value = re.sub(r"\s*([’'])\s*", r"\1", value)
    return value


def base_without_article(lemma: str) -> str:
    value = surface(lemma)
    return re.sub(r"^(?:le|la|les|un|une|des)\s+", "", value, flags=re.I)


def raw_lookup(raw: dict[str, dict]) -> dict[str, dict]:
    result: dict[str, dict] = {}
    for key, page in raw.items():
        result.setdefault(norm(key), page)
    return result


def page_for(lemma: str, pages: dict[str, dict]) -> dict | None:
    candidates = [lemma, surface(lemma), base_without_article(lemma)]
    for value in candidates:
        page = pages.get(norm(value))
        if page:
            return page
    return None


def french_block(text: str) -> str:
    marker = "== {{langue|fr}} =="
    if marker in text:
        return text.split(marker, 1)[1].split("\n== {{langue|", 1)[0]
    return text


def balanced_templates(text: str, name: str) -> list[str]:
    """Extract balanced ``{{name ...}}`` templates, including nested ones."""
    result: list[str] = []
    needle = "{{" + name
    cursor = 0
    while True:
        start = text.find(needle, cursor)
        if start < 0:
            break
        depth = 0
        index = start
        while index < len(text) - 1:
            if text.startswith("{{", index):
                depth += 1
                index += 2
                continue
            if text.startswith("}}", index):
                depth -= 1
                index += 2
                if depth == 0:
                    result.append(text[start:index])
                    cursor = index
                    break
                continue
            index += 1
        else:
            break
    return result


def replace_inner_templates(value: str) -> str:
    """Keep visible text from simple inline templates and drop metadata."""
    pattern = re.compile(r"\{\{([^{}]*)\}\}")
    visible = {"pc", "lien", "l", "lang", "guil", "em", "italique", "m"}
    while pattern.search(value):
        def replacement(match: re.Match[str]) -> str:
            parts = match.group(1).split("|")
            if parts and parts[0].strip().casefold() in visible and len(parts) > 1:
                return parts[1].strip()
            return ""
        value = pattern.sub(replacement, value)
    return value


def clean_example(value: str) -> str:
    value = re.sub(r"<!--.*?-->", "", value, flags=re.S)
    value = replace_inner_templates(value)
    value = re.sub(r"\[\[([^\]|]+)\|([^\]]+)\]\]", r"\2", value)
    value = re.sub(r"\[\[([^\]]+)\]\]", r"\1", value)
    value = re.sub(r"'''|''", "", value)
    value = re.sub(r"<[^>]+>", "", value)
    value = re.sub(r"\s+", " ", value).strip()
    value = value.replace(" ,", ",").replace(" .", ".")
    value = re.sub(r"^\s*[#*]+\s*", "", value)
    return value


def examples_from_page(page: dict | None) -> list[str]:
    if not page:
        return []
    block = french_block(str(page.get("wikitext", "")))
    result: list[str] = []
    for template in balanced_templates(block, "exemple"):
        body = template[len("{{exemple"):-2]
        values: list[str] = []
        for part in body.split("|"):
            part = part.strip()
            if not part:
                continue
            key = re.match(r"^([A-Za-zÀ-ÿ_-]+)\s*=", part)
            if key:
                if key.group(1).casefold() == "source":
                    break
                continue
            values.append(part)
        candidate = clean_example(" ".join(values))
        if 8 <= len(candidate) <= 220 and candidate not in result:
            if not any(marker in candidate.casefold() for marker in (
                "vocabulaire de cette leçon", "lesson's vocabulary",
                "vocabulario de esta lección", "exemple d’utilisation manquant",
                "exemple d'utilisation manquant", "http://", "https://",
            )):
                result.append(candidate)
    return result


def candidate_score(candidate: str, lemma: str) -> int:
    text = norm(candidate)
    base = norm(base_without_article(lemma))
    score = 0
    if base and base in text:
        score += 10
    root = base.split()[0].strip("'‘’") if base else ""
    if len(root) >= 4 and root in text:
        score += 3
    words = re.findall(r"[\wÀ-ÿ'-]+", candidate, flags=re.UNICODE)
    if len(words) >= 5:
        score += 4
    elif len(words) < 3:
        score -= 3
    if 20 <= len(candidate) <= 140:
        score += 4
    if candidate[-1:] in ".!?»:" or candidate.endswith("…"):
        score += 2
    if ";" in candidate or candidate.count(",") >= 3:
        score -= 1
    if any(marker in text for marker in (" point ", " led it ", "icelui", "ci-devant")):
        score -= 2
    return score


def pick_wiktionary_example(lemma: str, page: dict | None) -> str | None:
    examples = examples_from_page(page)
    if not examples:
        return None
    ranked = sorted(examples, key=lambda value: (-candidate_score(value, lemma), len(value), value))
    return ranked[0] if candidate_score(ranked[0], lemma) >= 10 else None


PREPOSITION_EXAMPLES = {
    "à": ("Je vais à la gare.",),
    "de": ("Je parle de mon travail.",),
    "dans": ("Le livre est dans le sac.",),
    "sur": ("Le livre est sur la table.",),
    "sous": ("Le chat dort sous la table.",),
    "chez": ("Je dîne chez mes parents.",),
    "pour": ("Ce cadeau est pour toi.",),
    "par": ("Nous passons par le parc.",),
    "en": ("Je voyage en train.",),
    "entre": ("Le café est entre la banque et la poste.",),
    "avec": ("Je travaille avec mes collègues.",),
    "sans": ("Il est parti sans son téléphone.",),
    "avant": ("Je pars avant midi.",),
    "après": ("Je rentre après le travail.",),
    "pendant": ("Je lis pendant le voyage.",),
    "depuis": ("J'habite ici depuis deux ans.",),
    "vers": ("Nous marchons vers la gare.",),
    "autour de": ("Les enfants courent autour de la table.",),
    "près de": ("L'hôtel est près de la gare.",),
    "loin de": ("Il habite loin de Paris.",),
    "devant": ("La voiture est devant la maison.",),
    "derrière": ("Le jardin est derrière la maison.",),
    "à côté de": ("La pharmacie est à côté de la boulangerie.",),
    "en face de": ("La banque est en face de la gare.",),
    "grâce à": ("Grâce à vous, j'ai compris.",),
    "malgré": ("Malgré la pluie, nous sortons.",),
    "selon": ("Selon le médecin, tout va bien.",),
    "parmi": ("Il est parmi ses amis.",),
}


VERB_EXAMPLES = {
    "être": "Je suis à la maison.",
    "avoir": "J'ai le temps de répondre.",
    "aller": "Nous allons au marché demain.",
    "faire": "Nous faisons un exercice ensemble.",
    "venir": "Elle vient ce soir.",
    "pouvoir": "Je peux vous aider.",
    "vouloir": "Je veux un café, s'il vous plaît.",
    "devoir": "Nous devons partir maintenant.",
    "savoir": "Je sais la réponse.",
    "dire": "Il dit la vérité.",
    "prendre": "Je prends le train à huit heures.",
    "voir": "Nous voyons la mer depuis la fenêtre.",
    "mettre": "Elle met son manteau avant de sortir.",
    "lire": "Je lis un livre dans le train.",
    "écrire": "Il écrit un message à sa sœur.",
}


def article_for(lemma: str, gender: str, definite: bool = False) -> str:
    value = base_without_article(lemma)
    if not value:
        return ""
    if value[0].lower() in "aeiouyàâäéèêëîïôöùûüœæh":
        return "l'" + value
    if definite:
        return ("la " if gender == "f." else "le ") + value
    return ("une " if gender == "f." else "un ") + value


def editorial_example(lemma: str, pos: str, gender: str, sense: str) -> str:
    value = surface(lemma)
    base = base_without_article(lemma)
    key = norm(base)
    if pos == "verb":
        if key in VERB_EXAMPLES:
            return VERB_EXAMPLES[key]
        if key == "avoir besoin":
            return "J'ai besoin de votre aide demain matin."
        if key in {"prendre rendez-vous", "donner rendez-vous"}:
            return "Je vais prendre rendez-vous chez le médecin."
        if key == "faire partir":
            return "Le conducteur fait partir le train à huit heures."
        if value.startswith(("s'", "s’", "se ")):
            reflexive = re.sub(r"^(?:s'|s’|se\s+)", "me ", value)
            return f"Je vais {reflexive} demain matin."
        return f"Je vais {value} demain matin."
    if pos == "preposition":
        if key in PREPOSITION_EXAMPLES:
            return PREPOSITION_EXAMPLES[key][0]
        return {
            "du": "Je reviens du marché.", "au": "Je vais au marché.",
            "des": "Je parle des vacances.", "aux": "Je pense aux enfants.",
            "comme": "Il travaille comme ingénieur.",
            "il y a": "Il y a un café près d'ici.",
        }.get(key, f"Le sac est {value} la chaise.")
    if pos == "adjective":
        adjective = value
        if key == "population active":
            return "La population active travaille dans différents secteurs."
        if key == "circuit touristique":
            return "Le circuit touristique commence à huit heures."
        if key == "événement heureux":
            return "La naissance est un événement heureux pour la famille."
        if key == "parthénon":
            return "Le Parthénon attire de nombreux visiteurs."
        if key == "pyrénées":
            return "Les Pyrénées attirent les amateurs de randonnée."
        if " " in adjective:
            return f"Le terme « {adjective} » apparaît dans les journaux."
        if key in {"belle", "jolie", "heureuse", "contente", "fatiguee", "fatiguée"}:
            return f"La ville est {adjective}."
        return f"Le résultat est {adjective}."
    if pos == "adverb":
        phrase_examples = {
            "d'abord": "D'abord, lisez la question.", "peut-être": "Peut-être viendra-t-il demain.",
            "là-bas": "Mes amis habitent là-bas.", "tu vas": "Tu vas au marché ce matin.",
            "ah oui": "Ah oui, je m'en souviens.", "eh oui": "Eh oui, c'est bien vrai.",
            "oh oui": "Oh oui, cette idée me plaît.", "d'accord": "D'accord, nous pouvons commencer.",
            "c'est vrai": "C'est vrai, le train arrive bientôt.", "tu vas bien": "J'espère que tu vas bien.",
            "à l'étranger": "Elle travaille à l'étranger.", "tout à l'heure": "Je l'ai vu tout à l'heure.",
            "pas du tout": "Je ne suis pas fatigué du tout.", "pour l'instant": "Pour l'instant, tout va bien.",
            "après-midi": "Nous travaillerons cet après-midi.", "à peu": "La douleur diminue peu à peu.",
        }
        if key in phrase_examples:
            return phrase_examples[key]
        return {
            "ne": "Je ne comprends pas.", "pas": "Il ne vient pas aujourd'hui.",
            "jamais": "Elle ne ment jamais.", "rien": "Je ne vois rien.",
            "plus": "Nous n'attendons plus.", "encore": "Il dort encore.",
            "très": "Ce livre est très intéressant.", "trop": "Il parle trop vite.",
            "déjà": "J'ai déjà fini.", "toujours": "Elle arrive toujours à l'heure.",
        }.get(key, f"Elle répond « {value} » dans cette conversation.")
    if pos == "proper_noun":
        if key == "eurostar":
            return "Nous prenons l'Eurostar pour Paris."
        if key == "jungle":
            return "Nous écoutons Jungle dans la voiture."
        return f"Nous arriverons à {value} avant midi."
    if pos == "determiner":
        return f"{value.capitalize()} livre est sur la table."
    if pos == "pronoun":
        return {"moi": "C'est pour moi.", "toi": "Je pense à toi.", "qui": "Qui vient avec nous?", "que": "Je sais que tu as raison."}.get(key, f"Je parle avec {value}.")
    if pos == "conjunction":
        return {"et": "Paul et Marie arrivent ce soir.", "mais": "Je voudrais venir, mais je travaille.", "ou": "Tu préfères le thé ou le café?", "que": "Je pense que tu as raison.", "si": "Si tu veux, nous pouvons partir."}.get(key, f"Je reste ici, {value} tu peux revenir plus tard.")
    if pos == "interjection":
        if key == "allons":
            return "— Allons, rentrons à la maison !"
        if key == "bah":
            return "— Bah, ce n'est pas grave !"
        return f"— {value} ! s'exclame-t-il en souriant."
    if pos == "noun":
        sense_key = norm(sense)
        definite = article_for(lemma, gender, definite=True)
        phrase = article_for(lemma, gender)
        if any(word in sense_key for word in ("person", "man", "woman", "child", "friend", "teacher", "student", "worker")):
            return f"Je connais {phrase} du quartier."
        if any(word in sense_key for word in ("food", "drink", "meal", "fruit", "vegetable", "bread", "meat", "dessert")):
            return f"Nous mangeons {phrase} ce soir."
        if any(word in sense_key for word in ("city", "town", "country", "station", "airport", "restaurant", "hotel", "museum", "place")):
            return f"Nous visitons {base} pendant les vacances."
        return f"{definite.capitalize()} est au centre de la discussion."
    return f"On utilise « {value} » dans cette phrase."


def is_low_quality(row: sqlite3.Row) -> bool:
    french = row["french"]
    if re.match(r"^J['’]ai choisi .+ pour le dîner\.$", french):
        return True
    if re.match(r"^On parle (?:de|du|des|d['’]).+", french):
        return True
    if re.match(r"^Nous allons .+ demain matin\.$", french):
        return True
    if re.match(r"^Cette idée est .+\.$", french) or re.match(r"^Le nouveau projet est .+\.$", french):
        return True
    if re.match(r"^Elle répond .+ à la question\.$", french):
        return True
    if re.match(r"^Le livre est .+ la table\.$", french):
        return True
    if re.match(r"^Nous aimerions visiter .+ un jour\.$", french):
        return True
    if re.match(r"^Il dit « .+ » en souriant\.$", french):
        return True
    if re.match(r"^— .+ ! s'exclame-t-il en souriant\.$", french):
        return True
    return False


def translate_batch(sentences: list[str], target: str) -> list[str]:
    numbered = "\n".join(f"[{index}] {sentence}" for index, sentence in enumerate(sentences))
    body = urlencode({"client": "gtx", "sl": "fr", "tl": target, "dt": "t", "q": numbered}).encode()
    request = Request(
        "https://translate.googleapis.com/translate_a/single",
        data=body,
        headers={"User-Agent": "FrenchVocabularyContentBuilder/1.0"},
    )
    with urlopen(request, timeout=45) as response:
        payload = json.loads(response.read().decode("utf-8"))
    translated = "".join(segment[0] for segment in payload[0] if segment and segment[0])
    markers = list(re.finditer(r"^\[(\d+)\]\s*", translated, flags=re.MULTILINE))
    result: list[str] = []
    for position, marker in enumerate(markers):
        if int(marker.group(1)) != position:
            raise ValueError(f"Translation markers changed: expected {position}, got {marker.group(1)}")
        end = markers[position + 1].start() if position + 1 < len(markers) else len(translated)
        result.append(translated[marker.end():end].strip())
    if len(result) != len(sentences) or any(not value for value in result):
        raise ValueError(f"Translation returned {len(result)} of {len(sentences)} entries")
    return result


def translate_local(sentences: list[str], cache: dict[str, str], model_dir: Path) -> None:
    """Fill missing translations with local French→English and English-target models."""
    import gc
    import torch
    from transformers import AutoModelForSeq2SeqLM, AutoTokenizer

    model_names = {"en": "opus-mt-fr-en", "es": "opus-mt-en-es", "zh-CN": "opus-mt-en-zh"}
    for target, model_name in model_names.items():
        missing = [sentence for sentence in sentences if f"{target}|{sentence}" not in cache]
        if not missing:
            continue
        model_path = model_dir / model_name
        print(f"loading local model {model_name} for {len(missing)} sentences", flush=True)
        tokenizer = AutoTokenizer.from_pretrained(model_path)
        model = AutoModelForSeq2SeqLM.from_pretrained(model_path)
        model.eval()
        for offset in range(0, len(missing), 32):
            batch = missing[offset:offset + 32]
            inputs = [sentence if target == "en" else cache.get(f"en|{sentence}", "") for sentence in batch]
            if any(not value for value in inputs):
                raise RuntimeError(f"English translation missing for local {target} batch")
            encoded = tokenizer(inputs, return_tensors="pt", padding=True, truncation=True)
            with torch.no_grad():
                generated = model.generate(**encoded, max_new_tokens=128, num_beams=4)
            values = [value.strip() for value in tokenizer.batch_decode(generated, skip_special_tokens=True)]
            if len(values) != len(batch) or any(not value for value in values):
                raise RuntimeError(f"Local {target} translation returned an incomplete batch")
            for sentence, value in zip(batch, values):
                cache[f"{target}|{sentence}"] = value
            CACHE_PATH.parent.mkdir(parents=True, exist_ok=True)
            CACHE_PATH.write_text(json.dumps(cache, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            print(f"translated local {target} {min(offset + len(batch), len(missing))}/{len(missing)}", flush=True)
        del model, tokenizer
        gc.collect()
        if torch.cuda.is_available():
            torch.cuda.empty_cache()


def translate_all(sentences: list[str], cache: dict[str, str], allow_network: bool, local_model_dir: Path | None = None) -> None:
    pending = [sentence for sentence in sentences if any(f"{target}|{sentence}" not in cache for target in ("en", "es", "zh-CN"))]
    if pending and not allow_network and local_model_dir is None:
        raise RuntimeError(f"{len(pending)} sentences need translation; rerun with --allow-network")
    if local_model_dir is not None:
        translate_local(sentences, cache, local_model_dir)
        pending = [sentence for sentence in sentences if any(f"{target}|{sentence}" not in cache for target in ("en", "es", "zh-CN"))]
        if pending and not allow_network:
            raise RuntimeError(f"Local models left {len(pending)} sentences without translation")
    targets = ("en", "es", "zh-CN")
    for target in targets:
        missing = [sentence for sentence in sentences if f"{target}|{sentence}" not in cache]
        for offset in range(0, len(missing), BATCH_SIZE):
            batch = missing[offset:offset + BATCH_SIZE]
            for attempt in range(4):
                try:
                    values = translate_batch(batch, target)
                    break
                except HTTPError as error:
                    if attempt == 3:
                        raise
                    delay = 20 * (attempt + 1) if error.code == 429 else 2 * (attempt + 1)
                    print(f"translation {target} batch rate-limited; retrying in {delay}s", flush=True)
                    time.sleep(delay)
                except Exception:
                    if attempt == 3:
                        raise
                    time.sleep(2 * (attempt + 1))
            for sentence, value in zip(batch, values):
                cache[f"{target}|{sentence}"] = value
            CACHE_PATH.parent.mkdir(parents=True, exist_ok=True)
            CACHE_PATH.write_text(json.dumps(cache, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            print(f"translated {target} {min(offset + len(batch), len(missing))}/{len(missing)}", flush=True)
            time.sleep(0.5)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--allow-network", action="store_true")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--local-model-dir", type=Path)
    args = parser.parse_args()

    raw: dict[str, dict] = {}
    for path in RAW_PATHS:
        if path.exists():
            raw.update(json.loads(path.read_text(encoding="utf-8")))
    pages = raw_lookup(raw)
    with sqlite3.connect(DB_PATH) as db:
        db.row_factory = sqlite3.Row
        rows = db.execute(
            "SELECT e.example_id,e.french,e.english,e.spanish,e.chinese, "
            "l.lemma,l.part_of_speech,l.gender,s.english AS sense_english "
            "FROM example e JOIN sense s ON e.sense_id=s.sense_id "
            "JOIN lexeme l ON s.lexeme_uid=l.lexeme_uid ORDER BY l.sort_order"
        ).fetchall()

    audit: list[dict[str, str]] = []
    for row in rows:
        if not is_low_quality(row):
            continue
        chosen = pick_wiktionary_example(row["lemma"], page_for(row["lemma"], pages))
        source = "wiktionary-fr" if chosen else "editorial-fallback"
        chosen = chosen or editorial_example(row["lemma"], row["part_of_speech"], row["gender"], row["sense_english"])
        audit.append({
            "example_id": row["example_id"], "lemma": row["lemma"], "part_of_speech": row["part_of_speech"],
            "source": source, "old_french": row["french"], "new_french": chosen,
        })

    print(json.dumps({"low_quality_rows": len(audit), "sources": Counter(item["source"] for item in audit)}, ensure_ascii=False))
    if args.dry_run:
        for item in audit[:25]:
            print(f"{item['lemma']}\t{item['source']}\t{item['old_french']}\t=>\t{item['new_french']}")
        return

    cache: dict[str, str] = {}
    for cache_path in (CACHE_PATH, *LEGACY_CACHE_PATHS):
        if cache_path.exists():
            for key, value in json.loads(cache_path.read_text(encoding="utf-8")).items():
                cache.setdefault(key, value)
    sentences = list(dict.fromkeys(item["new_french"] for item in audit))
    translate_all(sentences, cache, args.allow_network, args.local_model_dir)

    staging = DB_PATH.with_suffix(DB_PATH.suffix + ".example-quality-building")
    staging.unlink(missing_ok=True)
    shutil.copyfile(DB_PATH, staging)
    try:
        with sqlite3.connect(staging) as db:
            db.execute("PRAGMA foreign_keys=ON")
            for item in audit:
                db.execute(
                    "UPDATE example SET french=?,english=?,spanish=?,chinese=? WHERE example_id=?",
                    (item["new_french"], cache[f"en|{item['new_french']}"], cache[f"es|{item['new_french']}"], cache[f"zh-CN|{item['new_french']}"], item["example_id"]),
                )
            previous_review = db.execute("SELECT value FROM content_meta WHERE key='example_quality_review'").fetchone()
            review = json.loads(previous_review[0]) if previous_review else {"rows": 0, "sources": {}}
            sources = Counter(review.get("sources", {}))
            sources.update(item["source"] for item in audit)
            db.execute(
                "INSERT OR REPLACE INTO content_meta(key,value) VALUES (?,?)",
                ("example_quality_review", json.dumps({"date": "2026-09-11", "rows": int(review.get("rows", 0)) + len(audit), "sources": sources}, ensure_ascii=False, sort_keys=True)),
            )
            if db.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
                raise RuntimeError("SQLite integrity_check failed")
            if db.execute("PRAGMA foreign_key_check").fetchall():
                raise RuntimeError("SQLite foreign_key_check failed")
            db.commit()
            db.execute("VACUUM")
        staging.replace(DB_PATH)
    finally:
        staging.unlink(missing_ok=True)

    AUDIT_PATH.parent.mkdir(parents=True, exist_ok=True)
    AUDIT_PATH.write_text(json.dumps(audit, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"updated_database": str(DB_PATH), "audit": str(AUDIT_PATH), "translated_sentences": len(sentences)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
