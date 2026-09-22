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
from contextlib import closing
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
RAW_PATHS = tuple(
    ROOT / "data/raw/open_lexicon" / f"flelex_{level}_wiktionary.json"
    for level in ("a1", "a2", "b1", "b2", "c1", "c2")
)
CACHE_PATH = ROOT / ".tools/example-quality-translation-cache.json"
AUDIT_PATH = ROOT / "outputs/example-quality-review.json"
BATCH_SIZE = 24
LEGACY_CACHE_PATHS = (
    ROOT / "tmp/flelex-content/translation_cache.json",
    ROOT / "tmp/a2-content/translation_cache.json",
    ROOT / "tmp/a2-content/translation_cache_b1.json",
)
UNRESOLVED_TRANSLATION_MARKERS = ("Traduction contextuelle indisponible.", "Traducción contextual no disponible.", "暂缺上下文译文。")
MANUAL_TRANSLATIONS = {
    "fr:vision:noun:1:sense:1:example:1": (
        "AI does not replace a vision; it replaces everything that tried to pass itself off as a vision.",
        "La IA no reemplaza una visión: reemplaza todo lo que intentaba hacerse pasar por una visión.",
        "人工智能不会取代愿景；它取代的是一切试图冒充愿景的东西。",
    ),
    "fr:record:noun:1:sense:1:example:1": (
        "You'll buy me records by Michel Rivard; I'll pretend that it's interesting.",
        "Me comprarás discos de Michel Rivard; fingiré que es interesante.",
        "你会给我买米歇尔·里瓦尔的唱片，我会假装这很有趣。",
    ),
    "fr:oued:noun:1:sense:1:example:1": (
        "The Oued En-Nedja flows at our feet through a green ravine and disappears into a rocky landscape in the southwest.",
        "El oued En-Nedja fluye a nuestros pies por un barranco verde y se pierde en un paisaje rocoso del suroeste.",
        "恩内德贾河在我们脚下流过一条葱绿的峡谷，消失在西南部的岩石景观中。",
    ),
    "fr:rivaliser:verb:1:sense:1:example:1": (
        "One had just revealed a new talent and, with his first painting, competed with the great figures of imperial painting.",
        "Uno acababa de revelar un talento nuevo y, con su primer cuadro, rivalizaba con las glorias de la pintura imperial.",
        "其中一人刚展现出新的才华，并凭借第一幅画作与帝国画坛的巨匠相媲美。",
    ),
    "fr:coûteux:adjective:1:sense:1:example:1": (
        "My hypothesis is that many people like being close to power, but fewer truly want to oppose it. Opposition is frightening, and it is costly.",
        "Mi hipótesis es que a mucha gente le gusta estar cerca del poder, pero son menos quienes realmente quieren oponerse a él. Oponerse da miedo y resulta costoso.",
        "我的假设是，许多人喜欢接近权力，但真正想反对权力的人更少。反对它令人害怕，而且代价高昂。",
    ),
    "fr:hurlement:noun:1:sense:1:example:1": (
        "With every roll or pitch, the wind's continuous howl grew louder with a sinister whistle; [...].",
        "Con cada balanceo o cabeceo, el aullido continuo del viento se intensificaba con un silbido siniestro; [...].",
        "每次横摇或纵摇时，风的持续呼啸声都伴随着阴森的哨声变得更响；……",
    ),
    "fr:audit:noun:1:sense:1:example:1": (
        "The audit: its objectives and role in management. An approach to auditing sales and accounts receivable.",
        "La auditoría: sus objetivos y su función en la gestión. Un enfoque de la auditoría de ventas y cuentas por cobrar.",
        "审计：其在管理中的目标和作用；一种审计销售额及应收账款的方法。",
    ),
    "fr:rocheux:adjective:1:sense:1:example:1": (
        "The Oued En-Nedja flows at our feet through a green ravine and disappears into a rocky landscape in the southwest.",
        "El oued En-Nedja fluye a nuestros pies por un barranco verde y se pierde en un paisaje rocoso del suroeste.",
        "恩内德贾河在我们脚下流过一条葱绿的峡谷，消失在西南部的岩石景观中。",
    ),
    "fr:brigand:noun:1:sense:1:example:1": (
        "The former striker Brigand, who has worked at Zurich's major football organization for fifteen years, ...",
        "El exdelantero Brigand, que trabaja desde hace quince años en la gran organización futbolística de Zúrich, ...",
        "前锋布里冈曾在苏黎世的大型足球机构工作了十五年……",
    ),
    "fr:ylang-ylang:noun:1:sense:1:example:1": (
        "It is on the island of Luzon that ylang-ylang grows; its exquisite aroma has recently become fashionable in France and England.",
        "En la isla de Luzón crece el ylang-ylang, cuyo exquisito aroma se ha puesto de moda recientemente en Francia e Inglaterra.",
        "依兰依兰生长在吕宋岛上；它的芳香气味近来在法国和英国流行起来。",
    ),
    "fr:camerounais:adjective:1:sense:1:example:1": (
        "The meeting called today by Samuel Eto'o, president of the Cameroonian Football Federation (Fecafoot), is still under way.",
        "La reunión convocada hoy por Samuel Eto'o, presidente de la Federación Camerunesa de Fútbol (Fecafoot), sigue en curso.",
        "喀麦隆足协主席塞缪尔·埃托奥今天召集的会议仍在进行。",
    ),
    "fr:hi-fi:adjective:1:sense:1:example:1": (
        "Unchained Melody, created by Roy Hamilton, who also made Don't Let Go famous, was probably the first rock-and-roll song recorded in hi-fi stereo.",
        "Unchained Melody, creada por Roy Hamilton, que también hizo famosa Don't Let Go, probablemente fue la primera canción de rock and roll grabada en estéreo de alta fidelidad.",
        "罗伊·汉密尔顿创作的《Unchained Melody》也让《Don't Let Go》广为人知；它可能是第一首以高保真立体声录制的摇滚歌曲。",
    ),
    "fr:boiteux:adjective:1:sense:1:example:1": (
        "The lame man hung his melon on the coat hook, [...].",
        "El cojo colgó su melón en el perchero, [...].",
        "那个跛子把自己的瓜挂在衣帽钩上……",
    ),
    "fr:technophile:adjective:1:sense:1:example:1": (
        "However, caution is needed about sometimes fanciful announcements from technophiles, who often produce AI services.",
        "Sin embargo, hay que ser prudente con los anuncios a veces fantasiosos de los tecnófilos, que a menudo producen servicios de IA.",
        "不过，对于技术爱好者有时夸张的声明仍需谨慎，因为他们常常生产人工智能服务。",
    ),
    "fr:bae:expression:1:sense:1:example:1": (
        "The blue boat slipped behind the dock on the other side of the bay.",
        "El barco azul se deslizó detrás del muelle, al otro lado de la bahía.",
        "蓝色小船从海湾另一侧的码头后方滑过。",
    ),
    "fr:eugénol:noun:1:sense:1:example:1": (
        "Eugenol, extracted from clove oil, has many applications in dental practice.",
        "El eugenol, extraído del aceite de clavo, tiene muchas aplicaciones en la práctica dental.",
        "丁香酚从丁香油中提取，在牙科实践中有许多用途。",
    ),
    "fr:opéra-comique:noun:1:sense:1:example:1": (
        "The White Lady, Le Pré-aux-clercs and Jeanette's Wedding are charming opéra-comique works. Opéra-comique is a distinctly French genre.",
        "La dama blanca, Le Pré-aux-clercs y Las bodas de Jeanette son encantadoras obras de opéra-comique. La opéra-comique es un género claramente francés.",
        "《白衣女士》《Le Pré-aux-clercs》和《珍妮特的婚礼》都是迷人的喜歌剧作品。喜歌剧是独具法国特色的体裁。",
    ),
    "fr:hongrois:noun:1:sense:1:example:1": (
        "Goulash is a Hungarian word.",
        "Goulash es una palabra húngara.",
        "“古拉什”是一个匈牙利词。",
    ),
    "fr:odorant:adjective:1:sense:1:example:1": (
        "[Bois] of rosewood or violet, an Indian tree with fragrant wood used in marquetry.",
        "[Madera] de palisandro o violeta, árbol indio de madera fragante usado en marquetería.",
        "紫檀木或紫罗兰木，印度的一种芳香木材树，用于细木镶嵌。",
    ),
    "fr:drôle:adjective:1:sense:1:example:1": (
        "It is funny, but that is how it is…",
        "Es gracioso, pero así es…",
        "这很有趣，不过事实就是这样……",
    ),
}


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
    if norm(surface(lemma)) in {"arr\u00eat\u00e9", "\u00e9noncer", "ardent", "produit"}:
        return None
    bad_patterns = (
        r"^Cette situation para\u00eet .+\.$",
        r"^Ce mot est un masculin\.$",
        r"^Un ciel de lit ciel de lit\.$",
        r"^faire la paix Faire la paix\.$",
        r"<br|\]\]",
    )
    examples = [value for value in examples if not any(re.search(pattern, value, flags=re.I) for pattern in bad_patterns)]
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
        if key == "énoncer":
            return "Le professeur énonce clairement les règles de l’exercice."
        if key == "avoir besoin":
            return "J'ai besoin de votre aide demain matin."
        if key in {"prendre rendez-vous", "donner rendez-vous"}:
            return "Je vais prendre rendez-vous chez le médecin."
        if key == "faire partir":
            return "Le conducteur fait partir le train à huit heures."
        if key == "il y avoir":
            return "Il y aura une réunion demain matin."
        return f"Le médecin doit {value} rapidement pour aider le patient."
    if pos == "preposition":
        if key in PREPOSITION_EXAMPLES:
            return PREPOSITION_EXAMPLES[key][0]
        return {
            "du": "Je reviens du marché.", "au": "Je vais au marché.",
            "des": "Je parle des vacances.", "aux": "Je pense aux enfants.",
            "comme": "Il travaille comme ingénieur.",
            "il y a": "Il y a un café près d'ici.",
        }.get(key, f"Elle a écrit « {value} » dans le message envoyé hier.")
    if pos == "adjective":
        adjective = value
        if key == "masculin":
            return "Le genre masculin est utilisé dans cette phrase."
        if key == "arr\u00eat\u00e9":
            return "La décision arrêtée par le comité sera annoncée demain."
        if key == "ardent":
            return "Son discours ardent a convaincu le public."
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
            return f"Le projet semble {adjective} malgré les difficultés."
        if key in {"belle", "jolie", "heureuse", "contente", "fatiguee", "fatiguée"}:
            return f"La ville est {adjective}."
        return f"Le rapport présente un contexte {adjective} dans ce dossier."
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
        }.get(key, f"Elle a {value} accepté la proposition de son équipe.")
    if pos == "proper_noun":
        if key == "eurostar":
            return "Nous prenons l'Eurostar pour Paris."
        if key == "jungle":
            return "Nous écoutons Jungle dans la voiture."
        return f"Un article consacré à {value} est paru ce matin."
    if pos == "determiner":
        determiner_examples = {
            "un peu de": "Il reste un peu de pain sur la table.",
            "ont": "Ils ont déjà réservé leur chambre.",
            "lés": "Les documents lés au dossier doivent rester confidentiels.",
            "cind": "Le terme « cind » apparaît dans cette liste de formes.",
        }
        if key in determiner_examples:
            return determiner_examples[key]
        if re.fullmatch(r"(?:un|deux|trois|quatre|cinq|six|sept|huit|neuf|dix|onze|douze|treize|quatorze|quinze|seize|vingt|trente|quarante|cinquante|soixante|cent|mille|million|millions)(?:[- ](?:un|deux|trois|quatre|cinq|six|sept|huit|neuf|dix|onze|douze|treize|quatorze|quinze|seize|vingt|trente|quarante|cinquante|soixante|cent|mille|million|millions))*", key):
            return f"Nous avons reçu {value} demandes cette semaine."
        return f"Nous avons reçu {value} demandes cette semaine."
    if pos == "pronoun":
        pronoun_examples = {
            "moi": "C'est pour moi.", "toi": "Je pense à toi.", "qui": "Qui vient avec nous?", "que": "Je sais que tu as raison.",
            "lui": "Je travaille avec lui.", "elle": "Je travaille avec elle.", "eux": "Je travaille avec eux.",
            "y": "J'y pense souvent.", "en": "J'en parle souvent.", "le": "Je le vois chaque matin.",
            "la": "Je la vois chaque matin.", "les": "Je les vois chaque matin.", "te": "Je te comprends.",
            "me": "Tu me comprends.",
            "quelqu'un": "Quelqu'un a laissé un message à l'accueil.",
            "quelqu’ un": "Quelqu'un a laissé un message à l'accueil.",
            "rien": "Je n'ai rien compris à cette histoire.",
            "rien te": "Je ne te cache rien.",
            "cela même": "Cela même suffit à expliquer sa décision.",
            "soi-même": "Il faut apprendre à se faire confiance à soi-même.",
            "nous-mêmes": "Nous-mêmes avons vérifié les résultats.",
            "auxquelles": "Les règles auxquelles nous obéissons sont clairement affichées.",
            "auquel": "Voici le projet auquel elle a consacré deux ans.",
            "où": "La ville où il habite est près de la mer.",
            "d' aucuns": "D'aucuns estiment que cette décision est prématurée.",
        }
        if key in pronoun_examples:
            return pronoun_examples[key]
        return f"Le professeur a expliqué que {value} désignait la personne concernée."
    if pos == "conjunction":
        return {
            "et": "Paul et Marie arrivent ce soir.", "mais": "Je voudrais venir, mais je travaille.", "ou": "Tu préfères le thé ou le café?", "que": "Je pense que tu as raison.", "si": "Si tu veux, nous pouvons partir.",
            "parce": "Je reste ici parce que tu peux revenir plus tard.", "puis": "Il a fini, puis il est parti.", "et puis": "Il a fini, et puis il est parti.",
            "donc": "Il pleut, donc nous restons ici.", "car": "Je reste, car il est tard.", "comme": "Comme il pleut, nous restons ici.",
            "quand": "Quand tu arrives, appelle-moi.", "lorsque": "Lorsque tu arrives, appelle-moi.", "bien que": "Bien qu'il soit tard, nous continuons.",
        }.get(key, f"Il est parti, {value} la réunion était terminée.")
    if pos == "interjection":
        if key == "allons":
            return "— Allons, rentrons à la maison !"
        if key == "bah":
            return "— Bah, ce n'est pas grave !"
        return f"— {value} ! s'exclame-t-il en souriant."
    if pos == "noun":
        sense_key = norm(sense)
        if key == "produit":
            return "Ce produit est fabriqué localement."
        if key == "paix":
            return "Ils ont enfin fait la paix après leur dispute."
        if key == "arr\u00eat\u00e9":
            return "Un arrêté municipal fixe les horaires du marché."
        if key == "masculin":
            return "Le masculin et le féminin se distinguent par l’accord."
        if key == "ciel":
            return "Le ciel est dégagé après la pluie."
        definite = article_for(lemma, gender, definite=True)
        phrase = article_for(lemma, gender)
        if any(word in sense_key for word in ("person", "man", "woman", "child", "friend", "teacher", "student", "worker")):
            return f"Je connais {phrase} du quartier."
        if any(word in sense_key for word in ("food", "drink", "meal", "fruit", "vegetable", "bread", "meat", "dessert")):
            return f"Nous mangeons {phrase} ce soir."
        if any(word in sense_key for word in ("city", "town", "country", "station", "airport", "restaurant", "hotel", "museum", "place")):
            return f"Nous visitons {phrase} pendant les vacances."
        return f"Je cherche des informations sur {definite}."
    return f"Elle a écrit « {value} » dans le message envoyé hier."


def is_low_quality(row: sqlite3.Row) -> bool:
    french = row["french"]
    manual = MANUAL_TRANSLATIONS.get(row["example_id"])
    if manual and (row["english"], row["spanish"], row["chinese"]) != manual:
        return True
    if any(marker in str(row[field]) for field in ("english", "spanish", "chinese") for marker in UNRESOLVED_TRANSLATION_MARKERS):
        return True
    if re.match(r"^Nous avons parlé de .+ pendant le dîner\.$", french):
        return True
    if re.match(r"^.+ est au centre de la discussion\.$", french):
        return True
    if re.match(r"^J['’]ai choisi .+ pour le dîner\.$", french):
        return True
    if len(french.split()) <= 5 and re.match(r"^On parle (?:de|du|des|d['’]).+", french):
        return True
    if re.match(r"^Nous allons .+ demain matin\.$", french):
        return True
    if re.match(r"^Je vais .+ demain matin\.$", french):
        return True
    if re.match(r"^Le déterminant « .+ » accompagne un nom dans cette phrase\.$", french):
        return True
    if re.match(r"^Le mot « .+ » accompagne un nom dans cette phrase : .+ projet avance rapidement\.$", french):
        return True
    if re.match(r"^Le pronom « .+ » apparaît dans cette phrase\.$", french):
        return True
    if re.match(r"^Le verbe « .+ » apparaît dans un texte récent\.$", french):
        return True
    if re.match(r"^L’expression « .+ » apparaît dans un texte récent\.$", french):
        return True
    if re.match(r"^L’adverbe « .+ » précise le sens de cette phrase\.$", french):
        return True
    if re.match(r"^Le nom « .+ » apparaît dans un article récent\.$", french):
        return True
    if re.match(r"^Le terme « .+ » apparaît dans les journaux\.$", french):
        return True
    if re.match(r"^Je pense que « .+ » peut remplacer ce nom dans la phrase\.$", french):
        return True
    if re.match(r"^Elle répond « .+ » dans cette conversation\.$", french):
        return True
    if re.match(r"^Je parle avec .+\.$", french):
        return True
    if re.match(r"^Je reste ici, .+ tu peux revenir plus tard\.$", french):
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
    if re.match(r"^Le résultat est .+\.$", french):
        return True
    if re.match(r"^On utilise « .+ » dans cette phrase\.$", french):
        return True
    if re.match(r"^Il agit .+ dans cette situation\.$", french):
        return True
    if re.match(r"^Cette situation para\u00eet .+\.$", french):
        return True
    if re.match(r"^Ce mot est un masculin\.$", french):
        return True
    if re.match(r"^Voici le mot\.$", french):
        return True
    if re.match(r"^Faut faire sortir le m\u00e9chant faire sortir le m\u00e9chant\.$", french):
        return True
    if re.match(r"^Un ciel de lit ciel de lit\.$", french):
        return True
    if re.match(r"^faire la paix Faire la paix\.$", french):
        return True
    if any(re.search(r"<br|\]\]|\{\\fn|\bw:", str(row[field]), flags=re.I) for field in ("french", "english", "chinese")):
        return True
    if re.match(r"^Les ardents\.$", french):
        return True
    if re.match(r"^C’est un homme qui s’énonce clairement\.$", french):
        return True
    if any(marker in str(row[field]) for field in ("french", "english", "spanish", "chinese") for marker in ("peinture fr", "paint fr", "pintura es", "中文释义待补")):
        return True

    if row["lexeme_uid"] == "fr:ardent(e):adjective:1" and french.startswith("Être attaqué du mal"):
        return True
    if row["lexeme_uid"] == "fr:produit:noun:1" and french.startswith("La locution conjonctive"):
        return True
    if row["lexeme_uid"] == "fr:arrêté:noun:1" and french.startswith("L’agent de Fan Bingbing"):
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
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    batch_size = 128 if device.type == "cuda" else 48
    for target, model_name in model_names.items():
        missing = [sentence for sentence in sentences if f"{target}|{sentence}" not in cache]
        if not missing:
            continue
        model_path = model_dir / model_name
        print(f"loading local model {model_name} for {len(missing)} sentences", flush=True)
        tokenizer = AutoTokenizer.from_pretrained(model_path)
        model = AutoModelForSeq2SeqLM.from_pretrained(model_path)
        model.to(device)
        model.eval()
        for offset in range(0, len(missing), batch_size):
            batch = missing[offset:offset + batch_size]
            inputs = [sentence if target == "en" else cache.get(f"en|{sentence}", "") for sentence in batch]
            if any(not value for value in inputs):
                raise RuntimeError(f"English translation missing for local {target} batch")
            try:
                encoded = {key: value.to(device) for key, value in tokenizer(inputs, return_tensors="pt", padding=True, truncation=True).items()}
                with torch.inference_mode():
                    generated = model.generate(**encoded, max_new_tokens=96, num_beams=1)
                values = [value.strip() for value in tokenizer.batch_decode(generated, skip_special_tokens=True)]
            except RuntimeError:
                if device.type == "cuda":
                    torch.cuda.empty_cache()
                values = []
            if len(values) != len(batch) or any(not value for value in values):
                values = []
                for sentence in batch:
                    source_sentence = sentence.translate(str.maketrans({"“": '"', "”": '"', "«": '"', "»": '"', "„": '"'}))
                    single_inputs = [source_sentence if target == "en" else cache.get(f"en|{sentence}", "")]
                    encoded = {key: value.to(device) for key, value in tokenizer(single_inputs, return_tensors="pt", padding=True, truncation=True).items()}
                    with torch.inference_mode():
                        generated = model.generate(**encoded, max_new_tokens=96, num_beams=1)
                    candidate = tokenizer.batch_decode(generated, skip_special_tokens=True)[0].strip()
                    if not candidate:
                        candidate = "Traduction contextuelle indisponible." if target == "en" else "Traducción contextual no disponible." if target == "es" else "暂缺上下文译文。"
                    values.append(candidate)
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


def stage_curated_sources(audit: list[dict]) -> list[tuple[Path, Path]]:
    """Stage matching curated JSON updates for the reviewed DB examples."""
    updates = {
        (item["uid"], item["sense_index"], item["example_index"]): item
        for item in audit
    }
    found: set[tuple[str, int, int]] = set()
    staged: list[tuple[Path, Path]] = []
    for source_path in sorted((ROOT / "data/curated").glob("*.json")):
        document = json.loads(source_path.read_text(encoding="utf-8"))
        changed = False
        for word in document.get("words", []):
            uid = word.get("uid")
            for sense_index, _sense in enumerate(word.get("senses", [])):
                for example_index, example in enumerate(word.get("examples", [])):
                    key = (uid, sense_index, example_index)
                    item = updates.get(key)
                    if item is None:
                        continue
                    found.add(key)
                    expected = {
                        "french": item["new_french"],
                        "english": item["new_english"],
                        "spanish": item["new_spanish"],
                        "chinese": item["new_chinese"],
                    }
                    if any(example.get(field) != value for field, value in expected.items()):
                        example.update(expected)
                        changed = True
        if not changed:
            continue
        staging = source_path.with_suffix(source_path.suffix + ".example-quality-building")
        staging.unlink(missing_ok=True)
        staging.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        staged.append((source_path, staging))
    missing = set(updates) - found
    if missing:
        raise RuntimeError(f"Curated source has no matching examples for {len(missing)} reviewed rows: {sorted(missing)[:3]}")
    return staged


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
    with closing(sqlite3.connect(DB_PATH)) as db:
        db.row_factory = sqlite3.Row
        rows = db.execute(
            "SELECT e.example_id,e.french,e.english,e.spanish,e.chinese, "
            "l.lexeme_uid,l.lemma,l.part_of_speech,l.gender,s.sort_order AS sense_index,e.sort_order AS example_index, "
            "s.english AS sense_english "
            "FROM example e JOIN sense s ON e.sense_id=s.sense_id "
            "JOIN lexeme l ON s.lexeme_uid=l.lexeme_uid ORDER BY l.sort_order"
        ).fetchall()

    audit: list[dict] = []
    for row in rows:
        if not is_low_quality(row):
            continue
        chosen = pick_wiktionary_example(row["lemma"], page_for(row["lemma"], pages))
        source = "wiktionary-fr" if chosen else "editorial-fallback"
        chosen = chosen or editorial_example(row["lemma"], row["part_of_speech"], row["gender"], row["sense_english"])
        audit.append({
            "example_id": row["example_id"], "uid": row["lexeme_uid"], "sense_index": row["sense_index"],
            "example_index": row["example_index"], "lemma": row["lemma"], "part_of_speech": row["part_of_speech"],
            "source": source, "old_french": row["french"], "new_french": chosen,
        })
        manual = MANUAL_TRANSLATIONS.get(row["example_id"])
        if manual:
            audit[-1]["new_english"], audit[-1]["new_spanish"], audit[-1]["new_chinese"] = manual

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
    for item in audit:
        manual = MANUAL_TRANSLATIONS.get(item["example_id"])
        if manual:
            item["new_english"], item["new_spanish"], item["new_chinese"] = manual
        else:
            item["new_english"] = cache[f"en|{item['new_french']}"]
            item["new_spanish"] = cache[f"es|{item['new_french']}"]
            item["new_chinese"] = cache[f"zh-CN|{item['new_french']}"]

    staging = DB_PATH.with_suffix(DB_PATH.suffix + ".example-quality-building")
    source_stages = stage_curated_sources(audit)
    staging.unlink(missing_ok=True)
    shutil.copyfile(DB_PATH, staging)
    try:
        with closing(sqlite3.connect(staging)) as db:
            db.execute("PRAGMA foreign_keys=ON")
            for item in audit:
                db.execute(
                    "UPDATE example SET french=?,english=?,spanish=?,chinese=? WHERE example_id=?",
                    (item["new_french"], item["new_english"], item["new_spanish"], item["new_chinese"], item["example_id"]),
                )
            previous_review = db.execute("SELECT value FROM content_meta WHERE key='example_quality_review'").fetchone()
            review = json.loads(previous_review[0]) if previous_review else {"rows": 0, "sources": {}}
            sources = Counter(review.get("sources", {}))
            sources.update(item["source"] for item in audit)
            db.execute(
                "INSERT OR REPLACE INTO content_meta(key,value) VALUES (?,?)",
                ("example_quality_review", json.dumps({"date": "2026-09-14", "rows": int(review.get("rows", 0)) + len(audit), "sources": sources}, ensure_ascii=False, sort_keys=True)),
            )
            if db.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
                raise RuntimeError("SQLite integrity_check failed")
            if db.execute("PRAGMA foreign_key_check").fetchall():
                raise RuntimeError("SQLite foreign_key_check failed")
            db.commit()
            db.execute("VACUUM")
        staging.replace(DB_PATH)
        for source_path, source_staging in source_stages:
            source_staging.replace(source_path)
    finally:
        staging.unlink(missing_ok=True)
        for _source_path, source_staging in source_stages:
            source_staging.unlink(missing_ok=True)

    AUDIT_PATH.parent.mkdir(parents=True, exist_ok=True)
    AUDIT_PATH.write_text(json.dumps(audit, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"updated_database": str(DB_PATH), "audit": str(AUDIT_PATH), "translated_sentences": len(sentences)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
