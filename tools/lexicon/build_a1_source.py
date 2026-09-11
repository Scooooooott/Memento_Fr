"""Build the complete offline A1 editorial JSON from the textbook headwords.

The network snapshots are refreshable inputs, but this script itself is offline and
deterministic. Existing hand-reviewed development entries are retained verbatim.
"""
from __future__ import annotations

import csv
from collections import Counter, defaultdict
import copy
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import unicodedata

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).parent))
from analyze_a1_coverage import apertium_map, key  # noqa: E402
from fetch_a1_open_lexicon import query_for  # noqa: E402

HEADWORDS = ROOT / "data/raw/textbook_a1_headwords.csv"
CHINESE_GLOSSES = ROOT / "data/raw/textbook_a1_chinese_glosses.csv"
CHINESE_EXAMPLES = ROOT / "data/raw/example_zh_translations.json"
KAIKKI = ROOT / "data/raw/open_lexicon/kaikki_a1_snapshot.json"
WIKTAPI = ROOT / "data/raw/open_lexicon/wiktapi_a1_snapshot.json"
CURATED = ROOT / "data/curated/french_dev.json"
OUTPUT = ROOT / "data/curated/french_a1.json"
AUDIT_OUTPUT = ROOT / "data/curated/a1_content_audit.csv"
ESPEAK = Path(r"C:\Users\Scott\scoop\apps\espeak-ng\current\eSpeak NG\espeak-ng.exe")
ESPEAK_DATA = ESPEAK.parent / "espeak-ng-data"

SENSE_ZH_BY_UID = {
    "fr:être:verb:1": ["是；处于"],
    "fr:avoir:verb:1": ["有；拥有", "用于构成复合时态的助动词"],
    "fr:aimer:verb:1": ["喜欢", "爱"],
    "fr:café:noun:1": ["咖啡（饮料）", "咖啡馆"],
    "fr:faire:verb:1": ["做", "制作；制造"],
    "fr:femme:noun:1": ["女人；女性", "妻子"],
    "fr:grand:adjective:1": ["大的；宽广的", "高个的（指人）"],
    "fr:prendre:verb:1": ["拿；取；乘坐", "吃；喝"],
    "fr:content:adjective:1": ["高兴的；满意的"],
    "fr:jeune:adjective:1": ["年轻的"],
    "fr:bonjour:interjection:1": ["你好；早上好；下午好"],
    "fr:demain:adverb:1": ["明天"],
}

EXAMPLE_ZH_OVERRIDES = {
    "Le musée ouvre à partir de neuf heures.": "博物馆从九点起开放。",
    "Je vais adorer ce film.": "我会很喜欢这部电影。",
    "Nous allons à l’école.": "我们去学校。",
    "Je vais amuser les enfants.": "我会逗孩子们开心。",
    "C’est quelque chose d’ancien.": "这是件旧东西。",
    "Faites attention à la circulation.": "注意来往车辆。",
    "Nous avons parlé français.": "我们说了法语。",
    "C’est quelque chose de blessé.": "这是受伤的。",
    "C’est quelque chose de blond.": "这是金色的。",
    "C’est quelque chose de calme.": "这是平静的。",
    "Elle répond correctement.": "她回答得很正确。",
    "D’abord, lisez la question.": "首先，请读题目。",
    "J’ai déjà terminé.": "我已经完成了。",
    "C’est quelque chose de désolé.": "这是一件令人遗憾的事。",
    "Le taxi attend devant l’hôtel.": "出租车在酒店门前等候。",
    "Il est rapide et, en plus, très précis.": "他速度快，而且非常精准。",
    "C’est quelque chose d’espagnol.": "这是西班牙的东西。",
    "C’est quelque chose de fantastique.": "这是奇幻的东西。",
    "C’est quelque chose de fatigué.": "这是疲惫的状态。",
    "Je connais bien Hollande.": "我很了解荷兰。",
    "Je vais imaginer une solution.": "我要设想一个解决方案。",
    "Je vais contacter le propriétaire.": "我要联系房东。",
    "Nous partons en janvier.": "我们一月份出发。",
    "Je prends le plat du jour.": "我要点今日特餐。",
    "C’est quelque chose de libre.": "这是空闲的。",
    "C’est quelque chose de malade.": "这是生病的。",
    "Je vais marier ce couple.": "我要为这对新人主持婚礼。",
    "Je prends un café sans sucre.": "我喝一杯不加糖的咖啡。",
    "Je connais bien Chandeleur.": "我很了解圣蜡节。",
    "Je connais bien Saint-Valentin.": "我很了解情人节。",
    "Un café, s’il vous plaît.": "请给我一杯咖啡。",
    "Je connais bien SNCF.": "我很了解法国国家铁路公司。",
    "Je reste seulement deux jours.": "我只待两天。",
    "Je vais suivre le guide.": "我要跟着导游走。",
    "C’est quelque chose de terrifié.": "这是受惊吓的。",
    "Ils font des travaux dans la cuisine.": "他们正在翻修厨房。",
    "Ce livre est très facile.": "这本书非常简单。",
    "Paul va venir avec nous.": "保罗会和我们一起来。",
}

POS_MAP = {
    "noun": "noun", "verb": "verb", "adj": "adjective", "adv": "adverb",
    "intj": "interjection", "prep": "preposition", "conj": "conjunction",
    "pron": "pronoun", "det": "determiner", "name": "proper_noun",
    "num": "numeral", "phrase": "expression", "prep_phrase": "expression",
    "particle": "expression", "character": "expression",
}
WIKT_POS = {v: k for k, v in POS_MAP.items()}
WIKT_POS.update({"adjective": "adj", "adverb": "adv", "proper_noun": "name"})
DISFAVORED_TAGS = {"archaic", "obsolete", "historical", "vulgar", "derogatory", "rare"}

# Repeated spellings in the textbook represent distinct grammatical entries or senses.
DUPLICATE_POS = {
    "aller": ["verb", "noun"],
    "belge": ["noun", "adjective"],
    "chinois": ["noun", "adjective"],
    "espagnol": ["noun", "noun", "adjective"],
    "étranger": ["adjective", "noun"],
    "français": ["noun", "noun", "adjective"],
    "italien": ["noun", "noun", "adjective"],
    "japonais": ["noun", "adjective"],
    "livre": ["noun", "noun"],
    "parler de": ["verb", "verb"],
    "souvenir": ["noun", "verb"],
    "tableau": ["noun", "noun"],
}
DUPLICATE_SENSE = {"livre": [0, 1], "tableau": [0, 3]}
EXACT_DEDUPE = {"bien sûr"}

POS_OVERRIDES = {
    "à": "preposition", "à partir de": "preposition", "autour de": "preposition",
    "près de": "preposition", "loin de": "preposition", "en face de": "preposition",
    "au-dessus de": "preposition", "au-dessous de": "preposition",
    "à côté de": "preposition", "grâce à": "preposition", "jusqu'à": "preposition",
    "chez": "preposition", "contre": "preposition", "dans": "preposition",
    "de": "preposition", "depuis": "preposition", "derrière": "preposition",
    "devant": "preposition", "en": "preposition", "entre": "preposition",
    "pour": "preposition", "sans": "preposition", "sous": "preposition",
    "sur": "preposition", "vers": "preposition",
    "et": "conjunction", "mais": "conjunction", "ou": "conjunction",
    "parce que": "conjunction", "quand": "conjunction", "si": "conjunction",
    "il faut": "expression", "il y a": "expression", "voici": "expression", "voilà": "expression",
    "organisé": "adjective", "terrifié": "adjective", "général, généraux": "adjective",
    "bien": "adverb", "chocolat": "noun", "époque": "noun", "est": "noun",
    "gauche": "noun", "gymnastique": "noun", "journal": "noun", "pendant": "preposition",
    "plusieurs": "determiner", "portable": "noun", "voisin": "noun", "rochelle": "proper_noun",
}
VERB_PHRASES = {
    "arrêter de", "avoir envie de", "commencer à", "demander à", "essayer de",
    "faire attention à", "faire des travaux", "faire du camping", "faire les courses",
    "parler de", "parler à", "se passer", "se rappeler", "se rencontrer", "téléphoner à",
}
LEMMA_OVERRIDES = {"général, généraux": "général"}
AUX_ETRE = {"aller", "arriver", "descendre", "entrer", "monter", "partir", "rentrer",
             "rester", "retourner", "revenir", "sortir", "tomber", "venir"}
EN_OVERRIDES = {
    "rochelle": "La Rochelle",
    "agence immobilière": "real estate agency",
    "arrêter de": "to stop; to give up",
    "autour de": "around",
    "avoir envie de": "to want; to feel like",
    "beau fixe": "settled fine weather",
    "bulletin météo": "weather forecast",
    "commencer à": "to begin; to start",
    "demander à": "to ask someone to",
    "du jour": "of the day; today's",
    "espace vert": "green space; park",
    "essayer de": "to try to",
    "faire attention à": "to pay attention to",
    "faire des travaux": "to have renovation work done",
    "faire du camping": "to go camping",
    "général, généraux": "general",
    "il faut": "one must; it is necessary",
    "inspecteur de police": "police inspector; detective",
    "nouvel an": "New Year",
    "parler de": "to talk about",
    "parler à": "to talk to",
    "se passer": "to happen",
    "se rappeler": "to remember",
    "se rencontrer": "to meet each other",
    "sur rendez-vous": "by appointment",
    "téléphoner à": "to phone; to call",
    "organisé": "organized", "terrifié": "terrified",
    "céréales": "breakfast cereal; cereals", "cheveux": "hair", "légumes": "vegetables",
    "parents": "parents", "petit-déjeuner": "breakfast", "rollers": "inline skates",
    "salle de bains": "bathroom", "vacances": "holidays; vacation",
    "allumer": "to turn on; to light", "demander": "to ask for", "donner": "to give",
    "écouter": "to listen to", "endormir": "to put to sleep", "ennuyer": "to bore",
    "essayer": "to try on; to try", "gagner": "to win", "heurter": "to hit; to collide with",
    "inviter": "to invite", "lever": "to raise", "offrir": "to offer; to give",
    "penser": "to think", "poser": "to ask; to put down", "pouvoir": "can; to be able to",
    "présenter": "to present", "regarder": "to watch; to look at", "répondre": "to answer",
    "reposer": "to put down again", "réserver": "to book; to reserve", "retourner": "to turn over",
    "tenir": "to hold", "terminer": "to finish", "vouloir": "to want",
}
ES_OVERRIDES = {
    "chandeleur":"Candelaria", "saint-valentin":"día de San Valentín", "vendôme":"Vendôme",
    "agence de voyages":"agencia de viajes", "agence immobilière":"agencia inmobiliaria",
    "air conditionné":"aire acondicionado", "allô":"hola; diga", "arc de triomphe":"arco de triunfo",
    "archéologie":"arqueología", "acheter":"comprar", "arrêter":"parar; detener", "arrêter de":"dejar de", "avoir envie de":"tener ganas de",
    "avoir raison":"tener razón", "basket":"zapatillas de deporte; baloncesto", "beau fixe":"buen tiempo estable",
    "bise":"beso", "blouson":"cazadora; chaqueta", "bonne année":"feliz año nuevo",
    "bruyant":"ruidoso", "bulletin météo":"pronóstico meteorológico", "camping":"camping",
    "cheveux":"pelo; cabello", "coca":"refresco de cola", "commencer à":"empezar a",
    "conditionné":"acondicionado", "crêpe":"crepe", "crêperie":"crepería", "céréales":"cereales",
    "demander à":"pedir a", "deux-chevaux":"Citroën 2CV", "discothèque":"discoteca",
    "du jour":"del día", "désolé":"lo siento", "détendre":"relajar; relajarse",
    "eau minérale":"agua mineral", "en général":"en general", "en moyenne":"por término medio",
    "espace vert":"zona verde", "essayer de":"intentar", "fac":"facultad",
    "faire attention à":"prestar atención a", "faire des travaux":"hacer obras",
    "faire du camping":"ir de camping", "faire le ménage":"hacer la limpieza",
    "faire les courses":"hacer la compra", "fait divers":"suceso; noticia breve", "fatigué":"cansado",
    "feu d'artifice":"fuegos artificiales", "fini":"terminado", "félicitations":"felicitaciones; enhorabuena",
    "guyanais":"guayanés", "général, généraux":"general", "haute couture":"alta costura",
    "hum":"hum", "hôtel de ville":"ayuntamiento", "il faut":"hay que", "il y a":"hay",
    "inspecteur de police":"inspector de policía", "invité":"invitado", "lunettes":"gafas",
    "légumes":"verduras", "muguet":"lirio del valle", "mètre carré":"metro cuadrado",
    "môme":"crío", "organisé":"organizado", "parking":"aparcamiento", "parler de":"hablar de",
    "parler à":"hablar con", "pause-café":"pausa para el café", "pendule":"reloj de pared; péndulo",
    "petit ami":"novio", "petite annonce":"anuncio clasificado", "point de vue":"punto de vista",
    "pomme de terre":"patata", "portrait-robot":"retrato robot", "prof":"profe",
    "préféré":"favorito", "pull":"jersey", "pâtes":"pasta", "pâtisserie":"pastelería",
    "rez-de-chaussée":"planta baja", "rollers":"patines en línea", "s'appeler":"llamarse",
    "s'il vous plaît":"por favor", "salle de bains":"cuarto de baño", "se passer":"pasar; suceder",
    "se rappeler":"recordar", "se rencontrer":"encontrarse", "shopping":"compras",
    "slam":"poesía slam", "sur place":"en el lugar", "sur rendez-vous":"con cita previa",
    "sœur":"hermana", "tee-shirt":"camiseta", "terrifié":"aterrorizado",
    "téléphoner à":"llamar por teléfono a", "un peu":"un poco", "viennois":"vienés",
    "allumer":"encender", "demander":"pedir", "endormir":"dormir; adormecer", "essayer":"probar",
    "gagner":"ganar", "heurter":"chocar contra", "poser":"hacer; formular", "reposer":"volver a poner",
    "retourner":"dar la vuelta a", "tenir":"sostener", "terminer":"terminar", "waouh":"guau",
    "à droite":"a la derecha", "à gauche":"a la izquierda",
    "à l'étranger":"en el extranjero", "à partir de":"a partir de", "étage":"piso; planta",
    "œil":"ojo", "œuf":"huevo",
}


def clean(value: str) -> str:
    value = unicodedata.normalize("NFC", " ".join(str(value).split())).strip()
    return value.rstrip(" .")


def canonical(value: str) -> str:
    return clean(value).replace("’", "'").casefold()


def usable_senses(entry: dict) -> list[dict]:
    result = []
    for sense in entry.get("senses", []):
        tags = set(sense.get("tags") or [])
        if sense.get("form_of") or "form-of" in tags or tags & DISFAVORED_TAGS:
            continue
        if sense.get("glosses"):
            result.append(sense)
    return result


def candidate_entries(entries: list[dict], desired: str | None) -> list[dict]:
    valid = [e for e in entries if usable_senses(e)]
    if not valid:
        # Some textbook lemmas are conventionally used in the plural (céréales,
        # lunettes, pâtes). Wiktionary models these as inflected noun pages.
        valid = [e for e in entries if e.get("senses") and e.get("pos") != "verb"]
    if desired:
        matching = [e for e in valid if POS_MAP.get(e.get("pos")) == desired]
        if matching:
            return matching
    return valid


def desired_pos(display: str, query: str, duplicate_index: int, entries: list[dict]) -> str:
    k = canonical(query)
    if k in DUPLICATE_POS and duplicate_index < len(DUPLICATE_POS[k]):
        return DUPLICATE_POS[k][duplicate_index]
    if k in VERB_PHRASES:
        return "verb"
    if k in POS_OVERRIDES:
        return POS_OVERRIDES[k]
    valid = candidate_entries(entries, None)
    if display[:1].isupper() and any(e.get("pos") == "name" for e in valid):
        return "proper_noun"
    if valid:
        return POS_MAP.get(valid[0].get("pos"), "expression")
    return "expression"


def sense_gloss(entry: dict | None, sense_index: int) -> str | None:
    if not entry:
        return None
    senses = usable_senses(entry)
    if not senses:
        return None
    sense = senses[min(sense_index, len(senses) - 1)]
    glosses = [clean(g) for g in sense.get("glosses", []) if clean(g)]
    return "; ".join(glosses[:2])[:240] if glosses else None


def any_gloss(entry: dict | None, sense_index: int) -> str | None:
    if not entry:
        return None
    senses = entry.get("senses", [])
    if not senses:
        return None
    glosses = [clean(g) for g in senses[min(sense_index, len(senses) - 1)].get("glosses", []) if clean(g)]
    return "; ".join(glosses[:2])[:240] if glosses else None


def wikt_gloss(snapshot: dict, query: str, pos: str, entry_index: int = 0) -> str | None:
    definitions = ((snapshot.get(query) or {}).get("es_definitions") or {}).get("definitions", [])
    target = WIKT_POS.get(pos, pos)
    matches = [d for d in definitions if d.get("pos") == target]
    if not matches:
        matches = definitions
    if not matches:
        return None
    definition = matches[min(entry_index, len(matches) - 1)]
    for sense in definition.get("senses", []):
        if "form-of" in (sense.get("tags") or []):
            continue
        glosses = [clean(g) for g in sense.get("glosses", []) if clean(g)]
        if glosses:
            value = "; ".join(glosses[:2])
            return value[:1].lower() + value[1:] if value else value
    return None


def apertium_choices(items: list[dict], pos: str) -> list[dict]:
    tag_match = {
        "verb": lambda tags: any(str(t).startswith("vb") for t in tags),
        "noun": lambda tags: "n" in tags,
        "adjective": lambda tags: "adj" in tags,
        "adverb": lambda tags: "adv" in tags,
        "preposition": lambda tags: any(t in tags for t in ("pr", "prn")),
        "proper_noun": lambda tags: "np" in tags,
    }.get(pos)
    if not tag_match:
        return items
    matching = [item for item in items if tag_match(item.get("source_tags") or [])]
    return matching or items


def ipa_for(query: str, entry: dict | None) -> tuple[str, str]:
    if entry:
        for sound in entry.get("sounds", []):
            ipa = clean(sound.get("ipa", ""))
            if ipa.startswith("/") and ipa.endswith("/"):
                return ipa, "kaikki-enwiktionary"
    env = os.environ.copy()
    env["ESPEAK_DATA_PATH"] = str(ESPEAK_DATA)
    if ESPEAK.exists():
        run = subprocess.run([str(ESPEAK), "-q", "--ipa=3", "-v", "fr-fr", "-b", "1", "--stdin"],
                             input=(query + "\n").encode("utf-8"), capture_output=True, check=False, env=env)
        value = clean(run.stdout.decode("utf-8", errors="replace")).replace("_", " ")
        if value:
            return f"/{value}/", "espeak-ng-g2p"
    # This remains visibly approximate and is only reached when the local G2P is absent.
    return f"/{query.casefold()}/", "editorial-fallback"


def form_with_tags(entry: dict, required: set[str]) -> str | None:
    for form in entry.get("forms", []):
        tags = set(form.get("tags") or [])
        if required <= tags and "multiword-construction" not in tags:
            return clean(form.get("form", "")) or None
    return None


def feminine_and_plurals(lemma: str, entry: dict | None) -> tuple[str, str, str]:
    forms = entry.get("forms", []) if entry else []
    feminine = next((clean(f["form"]) for f in forms if "feminine" in (f.get("tags") or []) and "plural" not in (f.get("tags") or [])), "")
    masculine_plural = next((clean(f["form"]) for f in forms if {"masculine", "plural"} <= set(f.get("tags") or [])), "")
    plural_any = next((clean(f["form"]) for f in forms if "plural" in (f.get("tags") or [])), "")
    feminine_plural = next((clean(f["form"]) for f in forms if {"feminine", "plural"} <= set(f.get("tags") or [])), "")
    if not feminine:
        if lemma.endswith("e"): feminine = lemma
        elif lemma.endswith("f"): feminine = lemma[:-1] + "ve"
        elif lemma.endswith("eux"): feminine = lemma[:-3] + "euse"
        elif lemma.endswith("er"): feminine = lemma[:-2] + "ère"
        elif lemma.endswith("ien"): feminine = lemma[:-3] + "ienne"
        elif lemma.endswith("on"): feminine = lemma + "ne"
        else: feminine = lemma + "e"
    if not masculine_plural:
        masculine_plural = plural_any or (lemma if lemma.endswith(("s", "x")) else lemma + "s")
    if not feminine_plural:
        feminine_plural = feminine if feminine.endswith(("s", "x")) else feminine + "s"
    return feminine, masculine_plural, feminine_plural


def noun_data(lemma: str, entry: dict | None) -> tuple[str, list[list[str]]]:
    expansion = " ".join(t.get("expansion", "") for t in (entry or {}).get("head_templates", []))
    all_tags = {tag for s in (entry or {}).get("senses", []) for tag in (s.get("tags") or [])}
    if "m or f" in expansion or "m. or f." in expansion or {"masculine", "feminine"} <= all_tags:
        gender = "m. / f."
    elif re.search(r"\bf(?:\s|\(|$)", expansion) or "feminine" in all_tags:
        gender = "f."
    else:
        gender = "m."
    form_of = next((item.get("word", "") for s in (entry or {}).get("senses", [])
                    for item in (s.get("form_of") or []) if "plural" in (s.get("tags") or [])), "")
    singular = clean(form_of) or lemma
    plural = lemma if form_of else next((clean(f.get("form", "")) for f in (entry or {}).get("forms", []) if "plural" in (f.get("tags") or [])), "")
    if not plural:
        if lemma.endswith(("s", "x", "z")): plural = lemma
        elif lemma.endswith("al"): plural = lemma[:-2] + "aux"
        elif lemma.endswith(("eau", "au", "eu")): plural = lemma + "x"
        else: plural = lemma + "s"
    article = "un" if gender == "m." else "une" if gender == "f." else "un / une"
    return gender, [["singulier", f"{article} {singular}"], ["pluriel", f"des {plural}"]]


def verb_base(lemma: str) -> tuple[str, bool]:
    reflexive = lemma.startswith("se ") or lemma.startswith("s'")
    raw = lemma[3:] if lemma.startswith("se ") else lemma[2:] if lemma.startswith("s'") else lemma
    return raw.split()[0], reflexive


def regular_verb_forms(base: str) -> tuple[list[str], list[str], str, str]:
    if base.endswith("er"):
        stem = base[:-2]
        present = [stem + x for x in ("e", "es", "e", "ons", "ez", "ent")]
        imperfect_stem = present[3][:-3]
        imperfect = [imperfect_stem + x for x in ("ais", "ais", "ait", "ions", "iez", "aient")]
        return present, imperfect, stem + "é", stem + "ant"
    if base.endswith("ir"):
        stem = base[:-2]
        present = [stem + x for x in ("is", "is", "it", "issons", "issez", "issent")]
        imperfect = [stem + "iss" + x for x in ("ais", "ais", "ait", "ions", "iez", "aient")]
        return present, imperfect, stem + "i", stem + "issant"
    stem = base[:-2] if base.endswith("re") else base
    present = [stem + x for x in ("s", "s", "", "ons", "ez", "ent")]
    imperfect = [stem + x for x in ("ais", "ais", "ait", "ions", "iez", "aient")]
    return present, imperfect, stem + "u", stem + "ant"


def verb_data(lemma: str, entry: dict | None, curated_verbs: dict[str, dict]) -> tuple[dict, list[list[str]]]:
    base, reflexive = verb_base(lemma)
    if base in curated_verbs:
        original = curated_verbs[base]
        verb = copy.deepcopy(original["verb"])
    elif entry:
        persons = [({"first-person"}, {"second-person"}, {"third-person"})[i % 3] |
                   ({"singular"} if i < 3 else {"plural"}) for i in range(6)]
        present = [form_with_tags(entry, {"indicative", "present"} | p) for p in persons]
        imperfect = [form_with_tags(entry, {"indicative", "imperfect"} | p) for p in persons]
        past = form_with_tags(entry, {"participle", "past"})
        participle = form_with_tags(entry, {"participle", "present"})
        if not all(present) or not all(imperfect) or not past or not participle:
            present, imperfect, past, participle = regular_verb_forms(base)
        auxiliary = "être" if reflexive or base in AUX_ETRE else "avoir"
        if base.endswith("er") and base != "aller": group = "1er groupe"
        elif base.endswith("ir") and str(present[3]).endswith("issons"): group = "2e groupe"
        else: group = "3e groupe"
        verb = {"group": group, "auxiliary": auxiliary, "past_participle": past,
                "present_participle": participle, "present": present, "imperfect": imperfect}
    else:
        present, imperfect, past, participle = regular_verb_forms(base)
        verb = {"group": "1er groupe" if base.endswith("er") else "3e groupe", "auxiliary": "être" if reflexive else "avoir",
                "past_participle": past, "present_participle": participle, "present": present, "imperfect": imperfect}
    forms: list[list[str]] = []
    if reflexive:
        # Kaikki forms already include the reflexive clitic for singular/third plural.
        def clitic(short: str, full: str, value: str) -> str:
            return short if value[:1].lower() in "aeiouyàâäéèêëîïôöùûüœæh" else full
        prefixes = [clitic("m'", "me ", str(verb["present"][0])),
                    clitic("t'", "te ", str(verb["present"][1])),
                    clitic("s'", "se ", str(verb["present"][2])), "nous ", "vous ",
                    clitic("s'", "se ", str(verb["present"][5]))]
        if not any(str(v).startswith(("m'", "t'", "s'", "nous ", "vous ")) for v in verb["present"]):
            verb["present"] = [p + v for p, v in zip(prefixes, verb["present"])]
            verb["imperfect"] = [p + v for p, v in zip(prefixes, verb["imperfect"])]
        verb["auxiliary"] = "être"
    if verb["auxiliary"] == "être":
        pp = verb["past_participle"]
        if reflexive:
            verb["compound"] = [f"me suis {pp}(e)", f"t’es {pp}(e)", f"s’est {pp}(e)",
                                f"nous sommes {pp}(e)s", f"vous êtes {pp}(e)(s)", f"se sont {pp}(e)s"]
        else:
            verb["compound"] = [f"suis {pp}(e)", f"es {pp}(e)", f"est {pp}(e)",
                                f"sommes {pp}(e)s", f"êtes {pp}(e)(s)", f"sont {pp}(e)s"]
        note = ("Avec un verbe pronominal, l’accord du participe dépend de la fonction du pronom réfléchi "
                "et de la place d’un éventuel complément d’objet direct." if reflexive else
                "Le participe passé employé avec être s’accorde avec le sujet.")
        forms.append(["accord du participe", note])
    if base == "pleuvoir":
        verb["present"] = ["ne s’emploie pas", "ne s’emploie pas", "pleut", "ne s’emploie pas", "ne s’emploie pas", "rare : pleuvent"]
        verb["imperfect"] = ["ne s’emploie pas", "ne s’emploie pas", "pleuvait", "ne s’emploie pas", "ne s’emploie pas", "rare : pleuvaient"]
        forms.append(["emploi", "Verbe impersonnel : il s’emploie normalement à la troisième personne du singulier."])
    return verb, forms


ADVERB_EXAMPLES = {
    "alors": ["Alors, on commence ?", "So, shall we start?", "Entonces, ¿empezamos?"],
    "assez": ["Cette salle est assez grande.", "This room is big enough.", "Esta sala es bastante grande."],
    "au-dessus": ["La chambre est au-dessus.", "The bedroom is upstairs.", "El dormitorio está arriba."],
    "aujourd'hui": ["Aujourd’hui, je travaille à la maison.", "Today, I am working at home.", "Hoy trabajo en casa."],
    "aussi": ["Marie vient aussi.", "Marie is coming too.", "Marie también viene."],
    "avant": ["Je l’ai déjà vu avant.", "I have seen him before.", "Ya lo he visto antes."],
    "beaucoup": ["Elle voyage beaucoup.", "She travels a lot.", "Viaja mucho."],
    "bien": ["Elle parle bien français.", "She speaks French well.", "Habla bien francés."],
    "bien sûr": ["Bien sûr, je peux vous aider.", "Of course, I can help you.", "Claro, puedo ayudarle."],
    "certainement": ["Il viendra certainement demain.", "He will certainly come tomorrow.", "Seguramente vendrá mañana."],
    "combien": ["Combien coûte ce livre ?", "How much does this book cost?", "¿Cuánto cuesta este libro?"],
    "comment": ["Comment allez-vous ?", "How are you?", "¿Cómo está usted?"],
    "correctement": ["Elle répond correctement.", "She answers correctly.", "Responde correctamente."],
    "d'abord": ["D’abord, lisez la question.", "First, read the question.", "Primero, lea la pregunta."],
    "d'accord": ["D’accord, je viens avec toi.", "All right, I am coming with you.", "De acuerdo, voy contigo."],
    "déjà": ["J’ai déjà terminé.", "I have already finished.", "Ya he terminado."],
    "donc": ["Il pleut, donc je reste ici.", "It is raining, so I am staying here.", "Llueve, así que me quedo aquí."],
    "enfin": ["Le bus arrive enfin.", "The bus is finally arriving.", "Por fin llega el autobús."],
    "en plus": ["Il est rapide et, en plus, très précis.", "He is fast and, moreover, very precise.", "Es rápido y, además, muy preciso."],
    "ensemble": ["Nous travaillons ensemble.", "We work together.", "Trabajamos juntos."],
    "ensuite": ["Ensuite, tournez à gauche.", "Then turn left.", "Después, gire a la izquierda."],
    "à l'étranger": ["Elle travaille à l’étranger.", "She works abroad.", "Trabaja en el extranjero."],
    "exactement": ["C’est exactement ce que je cherche.", "That is exactly what I am looking for.", "Eso es exactamente lo que busco."],
    "facilement": ["On trouve facilement cette adresse.", "This address is easy to find.", "Esta dirección se encuentra fácilmente."],
    "finalement": ["Finalement, nous prenons le train.", "In the end, we are taking the train.", "Finalmente, tomamos el tren."],
    "en général": ["En général, je me lève tôt.", "I usually get up early.", "Por lo general, me levanto temprano."],
    "hier": ["Je l’ai rencontré hier.", "I met him yesterday.", "Lo encontré ayer."],
    "ici": ["Attendez-moi ici.", "Wait for me here.", "Espéreme aquí."],
    "là": ["Posez le sac là.", "Put the bag there.", "Ponga la bolsa ahí."],
    "là-bas": ["Mes amis habitent là-bas.", "My friends live over there.", "Mis amigos viven allí."],
    "loin": ["La gare est loin.", "The station is far away.", "La estación está lejos."],
    "longtemps": ["Nous avons attendu longtemps.", "We waited for a long time.", "Esperamos mucho tiempo."],
    "maintenant": ["Nous pouvons partir maintenant.", "We can leave now.", "Podemos salir ahora."],
    "même": ["Même Paul connaît la réponse.", "Even Paul knows the answer.", "Hasta Paul conoce la respuesta."],
    "en moyenne": ["Je marche une heure par jour en moyenne.", "I walk for an hour a day on average.", "Camino una hora al día de media."],
    "non": ["Non, je ne suis pas prêt.", "No, I am not ready.", "No, no estoy listo."],
    "où": ["Où habitez-vous ?", "Where do you live?", "¿Dónde vive usted?"],
    "oui": ["Oui, je comprends.", "Yes, I understand.", "Sí, entiendo."],
    "parfois": ["Nous mangeons parfois ici.", "We sometimes eat here.", "A veces comemos aquí."],
    "peu": ["Il dort peu en semaine.", "He sleeps little during the week.", "Duerme poco entre semana."],
    "un peu": ["Je parle un peu français.", "I speak a little French.", "Hablo un poco de francés."],
    "peut-être": ["Peut-être viendra-t-elle demain.", "Perhaps she will come tomorrow.", "Quizá venga mañana."],
    "sur place": ["Nous déjeunerons sur place.", "We will have lunch there.", "Almorzaremos allí mismo."],
    "pourquoi": ["Pourquoi êtes-vous en retard ?", "Why are you late?", "¿Por qué llega tarde?"],
    "principalement": ["Ce quartier est principalement résidentiel.", "This district is mainly residential.", "Este barrio es principalmente residencial."],
    "puis": ["Prenez cette rue, puis tournez à droite.", "Take this street, then turn right.", "Tome esta calle y luego gire a la derecha."],
    "seulement": ["Je reste seulement deux jours.", "I am staying for only two days.", "Solo me quedo dos días."],
    "souvent": ["Elle vient souvent ici.", "She often comes here.", "Viene aquí a menudo."],
    "tard": ["Il rentre tard le soir.", "He comes home late in the evening.", "Vuelve tarde por la noche."],
    "tôt": ["Je me lève tôt.", "I get up early.", "Me levanto temprano."],
    "toujours": ["Il arrive toujours à l’heure.", "He always arrives on time.", "Siempre llega a tiempo."],
    "tout": ["Tout va bien.", "Everything is fine.", "Todo va bien."],
    "tout de suite": ["J’arrive tout de suite.", "I am coming right away.", "Voy enseguida."],
    "très": ["Ce livre est très intéressant.", "This book is very interesting.", "Este libro es muy interesante."],
    "trop": ["Ce sac est trop lourd.", "This bag is too heavy.", "Esta bolsa pesa demasiado."],
    "vite": ["Le train va très vite.", "The train goes very fast.", "El tren va muy rápido."],
    "vivement": ["Elle a vivement réagi.", "She reacted strongly.", "Reaccionó enérgicamente."],
    "vraiment": ["C’est vraiment une bonne idée.", "It is really a good idea.", "Es realmente una buena idea."],
    "demain": ["Nous partons demain matin.", "We are leaving tomorrow morning.", "Nos vamos mañana por la mañana."],
}

PREPOSITION_EXAMPLES = {
    "à": ["Je vais à Paris.", "I am going to Paris.", "Voy a París."],
    "à partir de": ["Le musée ouvre à partir de neuf heures.", "The museum opens from nine o'clock.", "El museo abre a partir de las nueve."],
    "après": ["Nous partons après le déjeuner.", "We are leaving after lunch.", "Nos vamos después del almuerzo."],
    "au-dessous": ["Le parking est au-dessous de l’immeuble.", "The car park is below the building.", "El aparcamiento está debajo del edificio."],
    "au-dessus de": ["La lampe est au-dessus de la table.", "The lamp is above the table.", "La lámpara está encima de la mesa."],
    "autour de": ["Nous marchons autour du lac.", "We are walking around the lake.", "Caminamos alrededor del lago."],
    "avec": ["Je viens avec mes amis.", "I am coming with my friends.", "Voy con mis amigos."],
    "chez": ["Nous dînons chez Paul.", "We are having dinner at Paul's place.", "Cenamos en casa de Paul."],
    "contre": ["Le vélo est contre le mur.", "The bicycle is against the wall.", "La bicicleta está contra la pared."],
    "à côté": ["La pharmacie est juste à côté.", "The pharmacy is right next door.", "La farmacia está justo al lado."],
    "dans": ["Les clés sont dans le sac.", "The keys are in the bag.", "Las llaves están en el bolso."],
    "de": ["Je viens de Lyon.", "I come from Lyon.", "Vengo de Lyon."],
    "devant": ["Le taxi attend devant l’hôtel.", "The taxi is waiting in front of the hotel.", "El taxi espera delante del hotel."],
    "en": ["Elle habite en France.", "She lives in France.", "Vive en Francia."],
    "entre": ["La poste est entre la banque et le café.", "The post office is between the bank and the café.", "Correos está entre el banco y la cafetería."],
    "jusque": ["Nous marchons jusque chez lui.", "We are walking all the way to his place.", "Caminamos hasta su casa."],
    "par": ["Nous passons par Bordeaux.", "We are going through Bordeaux.", "Pasamos por Burdeos."],
    "pendant": ["Il a plu pendant la nuit.", "It rained during the night.", "Llovió durante la noche."],
    "pour": ["Ce cadeau est pour toi.", "This present is for you.", "Este regalo es para ti."],
    "sous": ["Le chat dort sous la chaise.", "The cat is sleeping under the chair.", "El gato duerme debajo de la silla."],
    "sur": ["Le livre est sur la table.", "The book is on the table.", "El libro está sobre la mesa."],
    "vers": ["Nous marchons vers la gare.", "We are walking toward the station.", "Caminamos hacia la estación."],
}

FIXED_EXAMPLES = {
    "ça": ["Ça va très bien.", "It is going very well.", "Va muy bien."],
    "quelque chose": ["Je voudrais quelque chose à boire.", "I would like something to drink.", "Quisiera algo de beber."],
    "comme": ["Elle travaille comme médecin.", "She works as a doctor.", "Trabaja como médica."],
    "et": ["Paul et Marie arrivent demain.", "Paul and Marie arrive tomorrow.", "Paul y Marie llegan mañana."],
    "mais": ["Il est fatigué, mais il continue.", "He is tired, but he carries on.", "Está cansado, pero continúa."],
    "ou": ["Vous préférez le thé ou le café ?", "Do you prefer tea or coffee?", "¿Prefiere té o café?"],
    "parce que": ["Je reste ici parce qu’il pleut.", "I am staying here because it is raining.", "Me quedo aquí porque llueve."],
    "quand": ["Appelez-moi quand vous arrivez.", "Call me when you arrive.", "Llámeme cuando llegue."],
    "plusieurs": ["J’ai plusieurs idées.", "I have several ideas.", "Tengo varias ideas."],
    "il faut": ["Il faut réserver à l’avance.", "You have to book in advance.", "Hay que reservar con antelación."],
    "il y a": ["Il y a un café près de la gare.", "There is a café near the station.", "Hay una cafetería cerca de la estación."],
    "à droite": ["Tournez à droite après la banque.", "Turn right after the bank.", "Gire a la derecha después del banco."],
    "à gauche": ["La poste est à gauche.", "The post office is on the left.", "Correos está a la izquierda."],
    "du jour": ["Je prends le plat du jour.", "I will have the dish of the day.", "Tomo el plato del día."],
    "sur rendez-vous": ["Le médecin reçoit sur rendez-vous.", "The doctor sees patients by appointment.", "El médico atiende con cita previa."],
    "s'il vous plaît": ["Un café, s’il vous plaît.", "A coffee, please.", "Un café, por favor."],
    "voici": ["Voici votre chambre.", "Here is your room.", "Aquí tiene su habitación."],
    "voilà": ["Voilà le bus !", "There is the bus!", "¡Ahí está el autobús!"],
    "agence immobilière": ["Nous cherchons un appartement dans une agence immobilière.", "We are looking for a flat through a real estate agency.", "Buscamos un piso en una agencia inmobiliaria."],
    "espace vert": ["Les enfants jouent dans cet espace vert.", "The children are playing in this green space.", "Los niños juegan en esta zona verde."],
    "inspecteur de police": ["L’inspecteur de police pose des questions.", "The police inspector is asking questions.", "El inspector de policía hace preguntas."],
    "bulletin météo": ["J’écoute le bulletin météo chaque matin.", "I listen to the weather forecast every morning.", "Escucho el pronóstico meteorológico cada mañana."],
    "nouvel an": ["Nous fêtons le nouvel an en famille.", "We celebrate New Year with the family.", "Celebramos el Año Nuevo en familia."],
    "beau fixe": ["Le temps reste au beau fixe.", "The fine weather is holding.", "El buen tiempo se mantiene estable."],
    "bonne année": ["Bonne année à toute la famille !", "Happy New Year to the whole family!", "¡Feliz Año Nuevo a toda la familia!"],
    "félicitations": ["Félicitations pour votre réussite !", "Congratulations on your success!", "¡Enhorabuena por su éxito!"],
    "merci": ["Merci pour votre aide.", "Thank you for your help.", "Gracias por su ayuda."],
    "pardon": ["Pardon, où est la gare ?", "Excuse me, where is the station?", "Perdone, ¿dónde está la estación?"],
    "allô": ["Allô, je voudrais parler à Marie.", "Hello, I would like to speak to Marie.", "Diga, quisiera hablar con Marie."],
    "salut": ["Salut, comment vas-tu ?", "Hi, how are you?", "Hola, ¿cómo estás?"],
    "bravo": ["Bravo, tu as réussi !", "Well done, you passed!", "¡Bravo, lo has conseguido!"],
    "waouh": ["Waouh, quelle belle vue !", "Wow, what a beautiful view!", "¡Guau, qué vista tan bonita!"],
    "ah": ["Ah, je comprends maintenant.", "Ah, I understand now.", "Ah, ahora entiendo."],
    "oh": ["Oh, j’ai oublié mes clés !", "Oh, I forgot my keys!", "¡Oh, he olvidado las llaves!"],
    "hum": ["Hum, je ne suis pas sûr.", "Hmm, I am not sure.", "Mmm, no estoy seguro."],
    "ouais": ["Ouais, on peut y aller.", "Yeah, we can go.", "Sí, podemos ir."],
    "ben": ["Ben, je ne sais pas.", "Well, I don't know.", "Pues, no lo sé."],
    "janvier": ["Nous partons en janvier.", "We are leaving in January.", "Nos vamos en enero."],
    "mai": ["Les examens commencent en mai.", "The exams start in May.", "Los exámenes empiezan en mayo."],
    "juin": ["Il fait chaud en juin.", "It is hot in June.", "Hace calor en junio."],
    "juillet": ["Je prends mes vacances en juillet.", "I take my holiday in July.", "Tomo mis vacaciones en julio."],
    "août": ["Paris est calme en août.", "Paris is quiet in August.", "París está tranquilo en agosto."],
    "septembre": ["Les cours reprennent en septembre.", "Classes start again in September.", "Las clases empiezan de nuevo en septiembre."],
    "octobre": ["Il pleut souvent en octobre.", "It often rains in October.", "Llueve a menudo en octubre."],
    "lundi": ["Le musée est fermé lundi.", "The museum is closed on Monday.", "El museo está cerrado el lunes."],
    "mardi": ["Nous avons cours mardi.", "We have class on Tuesday.", "Tenemos clase el martes."],
    "mercredi": ["Je travaille mercredi.", "I work on Wednesday.", "Trabajo el miércoles."],
    "jeudi": ["Le train part jeudi matin.", "The train leaves on Thursday morning.", "El tren sale el jueves por la mañana."],
    "vendredi": ["Nous dînons ensemble vendredi.", "We are having dinner together on Friday.", "Cenamos juntos el viernes."],
    "samedi": ["Je fais les courses samedi.", "I do the shopping on Saturday.", "Hago la compra el sábado."],
    "dimanche": ["Nous restons à la maison dimanche.", "We are staying home on Sunday.", "Nos quedamos en casa el domingo."],
    "matin": ["Je prends un café le matin.", "I have a coffee in the morning.", "Tomo un café por la mañana."],
    "midi": ["Nous déjeunons à midi.", "We have lunch at noon.", "Almorzamos al mediodía."],
    "soir": ["Elle rentre tard le soir.", "She comes home late in the evening.", "Vuelve tarde por la noche."],
}

VERB_EXAMPLES = {
    "arrêter de": ["Elle essaie d’arrêter de fumer.", "She is trying to stop smoking.", "Intenta dejar de fumar."],
    "avoir envie de": ["J’ai envie de voyager.", "I feel like travelling.", "Tengo ganas de viajar."],
    "commencer à": ["Il commence à pleuvoir.", "It is beginning to rain.", "Empieza a llover."],
    "demander à": ["Je demande à Paul de venir.", "I ask Paul to come.", "Le pido a Paul que venga."],
    "essayer de": ["Nous essayons de comprendre.", "We are trying to understand.", "Intentamos comprender."],
    "faire attention à": ["Faites attention à la circulation.", "Pay attention to the traffic.", "Preste atención al tráfico."],
    "faire des travaux": ["Ils font des travaux dans la cuisine.", "They are renovating the kitchen.", "Están haciendo obras en la cocina."],
    "faire du camping": ["Nous faisons du camping en été.", "We go camping in summer.", "Vamos de camping en verano."],
    "faire les courses": ["Je fais les courses le samedi.", "I do the shopping on Saturdays.", "Hago la compra los sábados."],
    "faire le ménage": ["Elle fait le ménage le matin.", "She does the cleaning in the morning.", "Hace la limpieza por la mañana."],
    "avoir raison": ["Tu as raison sur ce point.", "You are right about that point.", "Tienes razón en ese punto."],
    "parler de": ["Nous parlons de notre voyage.", "We are talking about our trip.", "Hablamos de nuestro viaje."],
    "parler à": ["Je parle à mon professeur.", "I am talking to my teacher.", "Hablo con mi profesor."],
    "se passer": ["Que s’est-il passé hier ?", "What happened yesterday?", "¿Qué pasó ayer?"],
    "se rappeler": ["Je me rappelle cette adresse.", "I remember this address.", "Recuerdo esta dirección."],
    "se rencontrer": ["Nous nous rencontrons devant la gare.", "We meet in front of the station.", "Nos encontramos delante de la estación."],
    "se souvenir": ["Elle se souvient de ce voyage.", "She remembers that trip.", "Se acuerda de aquel viaje."],
    "s'appeler": ["Je m’appelle Louise.", "My name is Louise.", "Me llamo Louise."],
    "téléphoner à": ["Je téléphone à ma sœur.", "I am calling my sister.", "Llamo por teléfono a mi hermana."],
    "pleuvoir": ["Il va pleuvoir cet après-midi.", "It is going to rain this afternoon.", "Va a llover esta tarde."],
    "coûter": ["Ce billet coûte dix euros.", "This ticket costs ten euros.", "Este billete cuesta diez euros."],
    "exister": ["Ce service existe encore.", "This service still exists.", "Este servicio todavía existe."],
    "briller": ["Le soleil brille ce matin.", "The sun is shining this morning.", "El sol brilla esta mañana."],
    "endormir": ["Elle va endormir le bébé.", "She is going to put the baby to sleep.", "Va a dormir al bebé."],
    "ennuyer": ["Ce bruit ennuie mes voisins.", "This noise bothers my neighbours.", "Este ruido molesta a mis vecinos."],
    "heurter": ["La voiture a heurté le mur.", "The car hit the wall.", "El coche chocó contra la pared."],
    "inquiéter": ["Mon retard va inquiéter mes parents.", "My lateness is going to worry my parents.", "Mi retraso va a preocupar a mis padres."],
    "pouvoir": ["Je pourrai venir demain.", "I will be able to come tomorrow.", "Podré venir mañana."],
    "vouloir": ["Je veux voyager cet été.", "I want to travel this summer.", "Quiero viajar este verano."],
    "reposer": ["Je repose le livre sur la table.", "I put the book back on the table.", "Vuelvo a poner el libro sobre la mesa."],
    "arrêter": ["Le bus va s’arrêter devant la gare.", "The bus is going to stop in front of the station.", "El autobús va a parar delante de la estación."],
    "arriver": ["Le train va arriver à midi.", "The train is going to arrive at noon.", "El tren va a llegar al mediodía."],
    "commencer": ["Le cours va commencer à neuf heures.", "The class is going to start at nine.", "La clase va a empezar a las nueve."],
    "déjeuner": ["Nous allons déjeuner au café.", "We are going to have lunch at the café.", "Vamos a almorzar en la cafetería."],
    "descendre": ["Je descends à la prochaine station.", "I am getting off at the next station.", "Bajo en la próxima estación."],
    "détendre": ["Cette musique détend l’atmosphère.", "This music relaxes the atmosphere.", "Esta música relaja el ambiente."],
    "dîner": ["Nous allons dîner au restaurant.", "We are going to have dinner at the restaurant.", "Vamos a cenar en el restaurante."],
    "dormir": ["Je vais dormir huit heures.", "I am going to sleep for eight hours.", "Voy a dormir ocho horas."],
    "entraîner": ["Elle entraîne une équipe de jeunes.", "She coaches a youth team.", "Entrena a un equipo juvenil."],
    "entrer": ["Vous pouvez entrer maintenant.", "You can come in now.", "Puede entrar ahora."],
    "marcher": ["Nous allons marcher jusqu’au village.", "We are going to walk to the village.", "Vamos a caminar hasta el pueblo."],
    "monter": ["Je monte au troisième étage.", "I am going up to the third floor.", "Subo a la tercera planta."],
    "nager": ["Elle va nager dans la piscine.", "She is going to swim in the pool.", "Va a nadar en la piscina."],
    "partir": ["Nous allons partir demain matin.", "We are going to leave tomorrow morning.", "Vamos a salir mañana por la mañana."],
    "passer": ["Je vais passer chez Paul ce soir.", "I am going to stop by Paul's place tonight.", "Voy a pasar por casa de Paul esta noche."],
    "rappeler": ["Je vais rappeler Marie demain.", "I am going to call Marie back tomorrow.", "Voy a volver a llamar a Marie mañana."],
    "recommencer": ["Nous allons recommencer l’exercice.", "We are going to start the exercise again.", "Vamos a repetir el ejercicio."],
    "rentrer": ["Elle va rentrer à la maison.", "She is going to go home.", "Va a volver a casa."],
    "rester": ["Je vais rester ici deux jours.", "I am going to stay here for two days.", "Voy a quedarme aquí dos días."],
    "réussir": ["Tu vas réussir cet examen.", "You are going to pass this exam.", "Vas a aprobar este examen."],
    "revenir": ["Il va revenir la semaine prochaine.", "He is going to come back next week.", "Va a volver la semana que viene."],
    "rêver": ["Elle rêve de faire le tour du monde.", "She dreams of travelling around the world.", "Sueña con dar la vuelta al mundo."],
    "rouler": ["Nous allons rouler jusqu’à Bordeaux.", "We are going to drive to Bordeaux.", "Vamos a conducir hasta Burdeos."],
    "savoir": ["Je sais nager.", "I know how to swim.", "Sé nadar."],
    "sortir": ["Nous allons sortir ce soir.", "We are going out tonight.", "Vamos a salir esta noche."],
    "téléphoner": ["Je vais téléphoner à ma sœur.", "I am going to call my sister.", "Voy a llamar por teléfono a mi hermana."],
    "tomber": ["La neige va tomber cette nuit.", "Snow is going to fall tonight.", "Va a nevar esta noche."],
    "venir": ["Paul va venir avec nous.", "Paul is going to come with us.", "Paul va a venir con nosotros."],
    "vivre": ["Ils vont vivre à Lyon.", "They are going to live in Lyon.", "Van a vivir en Lyon."],
    "voyager": ["Nous allons voyager en France.", "We are going to travel in France.", "Vamos a viajar por Francia."],
}

VERB_OBJECTS = {
    "acheter": ("du pain", "some bread", "pan"), "adorer": ("ce film", "this film", "esta película"),
    "allumer": ("la lumière", "the light", "la luz"), "amuser": ("les enfants", "the children", "a los niños"),
    "appeler": ("Marie", "Marie", "a Marie"), "apprendre": ("le français", "French", "francés"),
    "attendre": ("le bus", "the bus", "el autobús"), "baigner": ("le bébé", "the baby", "al bebé"),
    "boire": ("un café", "a coffee", "un café"),
    "calmer": ("le bébé", "the baby", "al bebé"), "chercher": ("une adresse", "an address", "una dirección"),
    "choisir": ("un livre", "a book", "un libro"), "comprendre": ("la question", "the question", "la pregunta"),
    "connaître": ("Paris", "Paris", "París"), "contacter": ("le propriétaire", "the owner", "al propietario"),
    "changer": ("de train", "trains", "de tren"), "continuer": ("ce travail", "this work", "este trabajo"),
    "coucher": ("le bébé", "the baby", "al bebé"),
    "croire": ("cette histoire", "this story", "esta historia"), "détester": ("le bruit", "the noise", "el ruido"),
    "dire": ("la vérité", "the truth", "la verdad"), "donner": ("un conseil", "some advice", "un consejo"),
    "demander": ("une information", "some information", "información"),
    "écouter": ("la radio", "the radio", "la radio"), "écrire": ("un message", "a message", "un mensaje"),
    "embrasser": ("ma mère", "my mother", "a mi madre"), "endormir": ("le bébé", "the baby", "al bebé"),
    "ennuyer": ("mes voisins", "my neighbours", "a mis vecinos"), "essayer": ("ce manteau", "this coat", "este abrigo"),
    "excuser": ("son retard", "his lateness", "su retraso"), "éviter": ("un accident", "an accident", "un accidente"),
    "fabriquer": ("une table", "a table", "una mesa"), "faire": ("un gâteau", "a cake", "un pastel"),
    "fêter": ("mon anniversaire", "my birthday", "mi cumpleaños"), "gagner": ("le match", "the match", "el partido"),
    "habiller": ("mon enfant", "my child", "a mi hijo"),
    "heurter": ("le mur", "the wall", "la pared"), "imaginer": ("une solution", "a solution", "una solución"),
    "inquiéter": ("mes parents", "my parents", "a mis padres"), "installer": ("cette application", "this app", "esta aplicación"),
    "inviter": ("mes amis", "my friends", "a mis amigos"), "jouer": ("au football", "football", "al fútbol"),
    "laisser": ("la porte ouverte", "the door open", "la puerta abierta"), "laver": ("la voiture", "the car", "el coche"),
    "lever": ("la main", "his hand", "la mano"), "lire": ("le journal", "the newspaper", "el periódico"),
    "louer": ("une voiture", "a car", "un coche"), "manger": ("une pomme", "an apple", "una manzana"),
    "marier": ("ce couple", "this couple", "a esta pareja"),
    "mettre": ("mon manteau", "my coat", "mi abrigo"), "offrir": ("des fleurs", "some flowers", "flores"),
    "ouvrir": ("la fenêtre", "the window", "la ventana"), "penser": ("à mes vacances", "about my holiday", "en mis vacaciones"),
    "porter": ("une valise", "a suitcase", "una maleta"), "poser": ("une question", "a question", "una pregunta"),
    "pouvoir": ("venir demain", "come tomorrow", "venir mañana"), "préférer": ("le train", "the train", "el tren"),
    "prendre": ("le bus", "the bus", "el autobús"), "préparer": ("le dîner", "dinner", "la cena"),
    "présenter": ("mon projet", "my project", "mi proyecto"), "promener": ("le chien", "the dog", "al perro"),
    "quitter": ("le bureau", "the office", "la oficina"), "raconter": ("une histoire", "a story", "una historia"),
    "rechercher": ("un emploi", "a job", "un empleo"), "regarder": ("ce film", "this film", "esta película"),
    "reposer": ("le livre sur la table", "the book on the table", "el libro sobre la mesa"),
    "reprendre": ("mon travail", "my work", "mi trabajo"), "retourner": ("la crêpe", "the crêpe", "la crepe"),
    "remercier": ("mon professeur", "my teacher", "a mi profesor"), "rencontrer": ("mes amis", "my friends", "a mis amigos"),
    "répondre": ("à la question", "the question", "a la pregunta"), "réserver": ("une chambre", "a room", "una habitación"),
    "retrouver": ("mes clés", "my keys", "mis llaves"), "savoir": ("nager", "swim", "nadar"),
    "suivre": ("le guide", "the guide", "al guía"), "supprimer": ("ce fichier", "this file", "este archivo"),
    "tenir": ("le sac", "the bag", "la bolsa"), "terminer": ("ce travail", "this work", "este trabajo"),
    "traverser": ("la rue", "the street", "la calle"), "trouver": ("une solution", "a solution", "una solución"),
    "utiliser": ("ce téléphone", "this phone", "este teléfono"), "vendre": ("ma maison", "my house", "mi casa"),
    "visiter": ("le musée", "the museum", "el museo"), "voir": ("mes amis", "my friends", "a mis amigos"),
    "vouloir": ("voyager", "travel", "viajar"),
}


def starts_with_vowel(value: str) -> bool:
    return value.casefold() not in {"haute couture"} and value[:1].casefold() in "aeiouyàâäéèêëîïôöùûüœæh"


def definite_noun(word: dict) -> str:
    lemma = word["lemma"]
    singular = next((v for label, v in word.get("forms", []) if label == "singulier"), "")
    if singular and clean(singular.split()[-1]).casefold() != lemma.casefold():
        return f"les {lemma}"
    if word.get("gender") == "m. / f.":
        plural = next((v for label, v in word.get("forms", []) if "pluriel" in label), "")
        if plural.startswith("des "):
            return "les " + plural[4:]
    if starts_with_vowel(lemma):
        return "l’" + lemma
    return ("la " if word.get("gender") == "f." else "le ") + lemma


def de_phrase(noun_phrase: str) -> str:
    if noun_phrase.startswith("le "): return "du " + noun_phrase[3:]
    if noun_phrase.startswith("la "): return "de la " + noun_phrase[3:]
    if noun_phrase.startswith("les "): return "des " + noun_phrase[4:]
    if noun_phrase.startswith("l’"): return "de l’" + noun_phrase[2:]
    return "de " + noun_phrase


def generated_example(word: dict, entry: dict | None = None) -> list[str]:
    lemma, pos = word["lemma"], word["pos"]
    k = canonical(lemma)
    if k in VERB_EXAMPLES:
        return VERB_EXAMPLES[k]
    if k in ADVERB_EXAMPLES:
        return ADVERB_EXAMPLES[k]
    if k in PREPOSITION_EXAMPLES:
        return PREPOSITION_EXAMPLES[k]
    if k in FIXED_EXAMPLES:
        return FIXED_EXAMPLES[k]
    if pos == "noun":
        phrase = definite_noun(word)
        if sum(ord(c) for c in word["uid"]) % 2:
            return [f"Voici {phrase}.", "Here it is.", "Aquí está."]
        return [f"On parle {de_phrase(phrase)}.", "We are talking about it.", "Estamos hablando de ello."]
    if pos == "proper_noun":
        en = clean(word["senses"][0][0].split(" (")[0].split(";")[0])
        es = clean(word["senses"][0][1].split(" (")[0].split(";")[0])
        es = es[:1].upper() + es[1:]
        return [f"Je connais bien {lemma}.", f"I know {en} well.", f"Conozco bien {es}."]
    if pos == "adjective":
        en = clean(word["senses"][0][0].split(";")[0])
        es = clean(word["senses"][0][1].split(";")[0])
        de = "d’" if starts_with_vowel(lemma) else "de "
        return [f"C’est quelque chose {de}{lemma}.", f"It is something {en}.", f"Es algo {es}."]
    if pos == "verb":
        infinitive = lemma
        if lemma.startswith("se "):
            infinitive = "me " + lemma[3:]
        elif lemma.startswith("s'"):
            infinitive = "m'" + lemma[2:]
        obj = VERB_OBJECTS.get(k)
        tags = set()
        if entry and usable_senses(entry):
            tags = set(usable_senses(entry)[0].get("tags") or [])
        if obj is None and "transitive" in tags:
            obj = ("quelque chose", "something", "algo")
        suffix_fr, suffix_en, suffix_es = obj or ("ici", "here", "aquí")
        en = clean(word["senses"][0][0].split(";")[0].split(" (")[0])
        if en.lower().startswith("to "): en = en[3:]
        en = en.split(",")[0]
        es = clean(word["senses"][0][1].split(";")[0].split(",")[0].split(" (")[0])
        es = es[:1].lower() + es[1:]
        if es.endswith("se") and infinitive.startswith(("m'", "me ")):
            es = es[:-2] + "me"
        return [f"Je vais {infinitive} {suffix_fr}.", f"I am going to {en} {suffix_en}.", f"Voy a {es} {suffix_es}."]
    if pos == "interjection":
        return [f"Elle s’écrie : « {lemma} ! »", "She exclaims loudly.", "Ella exclama en voz alta."]
    if pos == "expression":
        return [f"On utilise souvent cette expression : « {lemma} ».", "This expression is often used.", "Esta expresión se usa a menudo."]
    return [f"Je comprends mieux avec {lemma}.", "I understand better with that.", "Lo entiendo mejor así."]


def load_chinese_inputs() -> tuple[dict[int, str], dict[str, str]]:
    gloss_rows = list(csv.DictReader(CHINESE_GLOSSES.open(encoding="utf-8-sig", newline="")))
    glosses = {int(row["序号"]): clean(row["中文释义"]) for row in gloss_rows}
    if set(glosses) != set(range(1, 892)) or any(not value for value in glosses.values()):
        raise ValueError("Chinese textbook gloss snapshot must cover rows 1–891 exactly")
    example_document = json.loads(CHINESE_EXAMPLES.read_text(encoding="utf-8"))
    examples = {item["french"]: clean(item["chinese"]) for item in example_document["translations"]}
    if not examples or any(not value for value in examples.values()):
        raise ValueError("Chinese example translation snapshot is empty or incomplete")
    return glosses, examples


def short_chinese_gloss(value: str) -> str:
    value = value.removeprefix("《俗》")
    return re.split(r"[；，]", value, maxsplit=1)[0].strip()


def add_chinese_fields(words: list[dict], glosses: dict[int, str], example_zh: dict[str, str]) -> None:
    for word in words:
        uid = word["uid"]
        source_entries = word.get("source_entries", [])
        source_chinese = glosses[source_entries[0]["number"]] if source_entries else ""
        senses = word["senses"]
        chinese_senses = SENSE_ZH_BY_UID.get(uid)
        if chinese_senses is None:
            if len(senses) != 1 or not source_chinese:
                raise ValueError(f"Chinese sense mapping required: {uid}")
            chinese_senses = [source_chinese]
        if len(chinese_senses) != len(senses):
            raise ValueError(f"Chinese sense count does not match EN/ES senses: {uid}")

        translated_senses = []
        translated_examples = []
        for sense_index, (sense, example, chinese) in enumerate(zip(senses, word["examples"], chinese_senses)):
            if not isinstance(sense, list) or len(sense) != 2:
                raise ValueError(f"Legacy EN/ES source shape changed before Chinese migration: {uid}")
            if not isinstance(example, list) or len(example) != 3:
                raise ValueError(f"Legacy FR/EN/ES example shape changed before Chinese migration: {uid}")
            french = example[0]
            translated = EXAMPLE_ZH_OVERRIDES.get(french) or example_zh.get(french)
            if not translated:
                raise ValueError(f"Missing Chinese example translation: {uid} / {french}")
            translated = "".join(character for character in translated
                                 if unicodedata.category(character) != "Cf")
            # The generated noun frames are deliberately literal.  Supplying
            # the aligned lexical gloss avoids polysemy errors such as âge
            # ('age') being translated as époque ('era').
            if example[1] == "Here it is.":
                demonstrative = "这些是" if french.startswith("Voici les ") else "这是"
                translated = f"{demonstrative}{short_chinese_gloss(chinese)}。"
            elif example[1] == "We are talking about it.":
                translated = f"我们正在谈论{short_chinese_gloss(chinese)}。"
            translated_senses.append({"english": sense[0], "spanish": sense[1], "chinese": chinese})
            translated_examples.append({
                "french": french,
                "english": example[1],
                "spanish": example[2],
                "chinese": translated,
            })
        word["senses"] = translated_senses
        word["examples"] = translated_examples
        chinese_reference = "textbook-a1-chinese-glosses" if source_entries else "editorial-chinese-glosses"
        word["references"] = list(dict.fromkeys(word.get("references", []) + [
            chinese_reference, "google-translate-fr-zh-example-snapshot"
        ]))


def main() -> None:
    rows = list(csv.DictReader(HEADWORDS.open(encoding="utf-8-sig", newline="")))
    chinese_glosses, chinese_examples = load_chinese_inputs()
    kaikki = json.loads(KAIKKI.read_text(encoding="utf-8"))["entries"]
    wikt = json.loads(WIKTAPI.read_text(encoding="utf-8"))["entries"]
    ap = apertium_map()
    curated_doc = json.loads(CURATED.read_text(encoding="utf-8"))
    curated = curated_doc["words"]
    curated_by_uid = {w["uid"]: w for w in curated}
    curated_by_lemma_pos = {(canonical(w["lemma"]), w["pos"]): w for w in curated}
    curated_verbs = {canonical(w["lemma"]): w for w in curated if w["pos"] == "verb"}

    occurrences: dict[str, list[dict]] = defaultdict(list)
    for row in rows:
        query = query_for(row["法语词条"])
        occurrences[canonical(query)].append(row)

    duplicate_seen: Counter[str] = Counter()
    uid_seen: Counter[tuple[str, str]] = Counter()
    words: list[dict] = []
    covered_curated: set[str] = set()
    collapsed: dict[str, dict] = {}
    diagnostics = Counter()

    for row in rows:
        raw_display = unicodedata.normalize("NFC", row["法语词条"].strip())
        display = clean(raw_display)
        # query_for contains exact overrides whose terminal punctuation is meaningful.
        query = query_for(raw_display)
        qkey = canonical(query)
        dup_index = duplicate_seen[qkey]
        duplicate_seen[qkey] += 1
        if qkey in EXACT_DEDUPE and qkey in collapsed:
            collapsed[qkey]["source_entries"].append({"number": int(row["序号"]), "page": int(row["原书页码"]), "display": display})
            diagnostics["collapsed_repeated_occurrence"] += 1
            continue

        entries = kaikki.get(query, [])
        pos = desired_pos(display, query, dup_index, entries)
        candidates = candidate_entries(entries, pos)
        entry_index = DUPLICATE_SENSE.get(qkey, [0] * (dup_index + 1))[dup_index] if qkey in DUPLICATE_SENSE else 0
        entry = candidates[min(entry_index, len(candidates) - 1)] if candidates else None

        # Verb constructions reuse the standalone infinitive's morphology when present.
        if pos == "verb" and (not entry or entry.get("pos") != "verb"):
            base, _ = verb_base(query)
            base_candidates = candidate_entries(kaikki.get(base, []), "verb")
            entry = base_candidates[0] if base_candidates else None

        lemma = LEMMA_OVERRIDES.get(qkey, query)
        if display == "souvenir(se)":
            lemma, pos = "se souvenir", "verb"
            base_candidates = candidate_entries(kaikki.get("souvenir", []), "verb")
            entry = base_candidates[0] if base_candidates else entry
        homonym_key = (canonical(lemma), pos)
        uid_seen[homonym_key] += 1
        homonym = uid_seen[homonym_key]
        uid = f"fr:{canonical(lemma)}:{pos}:{homonym}"

        existing = curated_by_lemma_pos.get((canonical(lemma), pos)) if homonym == 1 else None
        if existing:
            word = copy.deepcopy(existing)
            covered_curated.add(existing["uid"])
            diagnostics["hand_reviewed_reused"] += 1
        else:
            sense_index = entry_index if qkey in DUPLICATE_SENSE else 0
            english = EN_OVERRIDES.get(qkey) or sense_gloss(entry, sense_index) or any_gloss(entry, sense_index)
            if not english:
                definitions = ((wikt.get(query) or {}).get("en_definitions") or {}).get("definitions", [])
                target = WIKT_POS.get(pos, pos)
                matches = [d for d in definitions if d.get("pos") == target] or definitions
                fake = {"senses": matches[0].get("senses", [])} if matches else None
                english = sense_gloss(fake, sense_index)
            if not english:
                raise ValueError(f"Missing English gloss: {display} -> {query} ({pos})")

            spanish_source = "editorial-bridging-glosses"
            spanish = ES_OVERRIDES.get(qkey)
            if qkey == "tableau" and dup_index == 1:
                spanish = "tabla; cuadro"
            if not spanish:
                spanish = wikt_gloss(wikt, query, pos, entry_index)
                if spanish: spanish_source = "wiktapi-eswiktionary"
            if not spanish:
                choices = apertium_choices(ap.get(key(query), []), pos)
                if choices:
                    spanish = clean(choices[min(entry_index, len(choices) - 1)]["spanish"])
                    spanish_source = "apertium-fra-spa"
            if not spanish:
                raise ValueError(f"Missing Spanish gloss: {display} -> {query} ({pos})")

            ipa, ipa_source = ipa_for(lemma, entry)
            refs = ["textbook-a1-headwords", "kaikki-enwiktionary", spanish_source]
            if ipa_source not in refs: refs.append(ipa_source)
            word = {"uid": uid, "lemma": lemma, "pos": pos, "ipa": ipa,
                    "senses": [[clean(english), clean(spanish)]],
                    "references": list(dict.fromkeys(refs))}
            if pos == "noun":
                word["gender"], word["forms"] = noun_data(lemma, entry)
            elif pos == "adjective":
                feminine, mpl, fpl = feminine_and_plurals(lemma, entry)
                word["forms"] = [["féminin", feminine], ["masculin pluriel", mpl], ["féminin pluriel", fpl]]
            elif pos == "verb":
                word["verb"], word["forms"] = verb_data(lemma, entry, curated_verbs)
            else:
                word["forms"] = [["forme", "invariable"]]
            word["examples"] = [generated_example(word, entry)]
            diagnostics[f"generated_{pos}"] += 1

        word["source_entries"] = [{"number": int(row["序号"]), "page": int(row["原书页码"]), "display": display}]
        words.append(word)
        if qkey in EXACT_DEDUPE:
            collapsed[qkey] = word

    # Preserve three useful hand-reviewed development entries absent from the textbook.
    for original in curated:
        if original["uid"] not in covered_curated:
            words.append(copy.deepcopy(original))
            diagnostics["hand_reviewed_extra"] += 1

    # Reassign only colliding generated UIDs after curated entries have kept their stable IDs.
    seen: set[str] = set()
    counters: Counter[tuple[str, str]] = Counter()
    for word in words:
        pair = (canonical(word["lemma"]), word["pos"])
        counters[pair] += 1
        expected = f"fr:{pair[0]}:{pair[1]}:{counters[pair]}"
        if word["uid"] in curated_by_uid and word["uid"] not in seen:
            expected = word["uid"]
            counters[pair] = int(expected.rsplit(":", 1)[1])
        word["uid"] = expected
        if expected in seen:
            raise ValueError(f"Duplicate UID after merge: {expected}")
        seen.add(expected)

    add_chinese_fields(words, chinese_glosses, chinese_examples)

    result = {
        "content_version": "2026.09.10-a1.3",
        "language": "fr-FR",
        "scope": f"《你好！法语1》A1 词表的 {len(rows)} 条来源记录，合并为 {len(words) - diagnostics['hand_reviewed_extra']} 个教材词条，并保留 {diagnostics['hand_reviewed_extra']} 个既有精选词条。",
        "review_date": "2026-09-10",
        "words": words,
    }
    OUTPUT.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    by_source_number = {occurrence["number"]: word for word in words
                        for occurrence in word.get("source_entries", [])}
    with AUDIT_OUTPUT.open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=("序号", "原书页码", "原词条", "lexeme_uid", "lemma",
                                                      "pos", "ipa", "english", "spanish", "chinese",
                                                      "example_fr", "example_en", "example_es", "example_zh",
                                                      "review_status"))
        writer.writeheader()
        for row in rows:
            word = by_source_number[int(row["序号"])]
            writer.writerow({"序号": row["序号"], "原书页码": row["原书页码"], "原词条": row["法语词条"],
                             "lexeme_uid": word["uid"], "lemma": word["lemma"], "pos": word["pos"],
                             "ipa": word["ipa"], "english": word["senses"][0]["english"],
                             "spanish": word["senses"][0]["spanish"], "chinese": word["senses"][0]["chinese"],
                             "example_fr": word["examples"][0]["french"],
                             "example_en": word["examples"][0]["english"],
                             "example_es": word["examples"][0]["spanish"],
                             "example_zh": word["examples"][0]["chinese"],
                             "review_status": "seed-reviewed" if word["uid"] in curated_by_uid else "machine-assisted"})
    print(json.dumps({"source_rows": len(rows), "lexemes": len(words), "verbs": sum(w["pos"] == "verb" for w in words),
                      "output": str(OUTPUT), "audit": str(AUDIT_OUTPUT), "diagnostics": diagnostics}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
