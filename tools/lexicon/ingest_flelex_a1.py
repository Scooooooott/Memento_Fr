"""Build a FLELex CEFR-level snapshot and merge it into the bundled content DB.

The existing lexeme UID is retained when a FLELex lemma/POS matches it, so
learning history remains attached to the same entry. FLELex entries replace
the content of that lexeme and are all added to the requested FLELex book.
"""
from __future__ import annotations

import argparse
import csv
from concurrent.futures import ThreadPoolExecutor, as_completed
from collections import Counter
from hashlib import sha256
import json
import re
import shutil
import sqlite3
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import quote, unquote
from urllib.request import Request, urlopen
import unicodedata

from content_review import apply_review_registry

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CSV = ROOT / "outputs/flelex-cefr-sorted/flelex_A1.csv"
DEFAULT_RAW = ROOT / "data/raw/open_lexicon/flelex_a1_wiktionary.json"
DEFAULT_CFDICT = ROOT / "data/raw/open_lexicon/cfdict.u8"
DEFAULT_DB = ROOT / "app/src/main/assets/french_content.db"
DEFAULT_SOURCE = ROOT / "data/curated/flelex_a1.json"
DEFAULT_CACHE = ROOT / "tmp/flelex-content/translation_cache.json"
DEFAULT_REVIEW_REGISTRY = ROOT / "data/curated/review-overrides/flelex_a1.json"
POS_MAP = {
    "NOUN": "noun", "VERB": "verb", "ADJ": "adjective", "ADV": "adverb",
    "PREP": "preposition", "PREPDET": "preposition", "PRON": "pronoun",
    "DET": "determiner", "CONJ": "conjunction", "INT": "interjection", "X": "expression",
}
PERSONS = ("je", "tu", "il / elle / on", "nous", "vous", "ils / elles")
VOWELS = "aeiouyàâäéèêëîïôöùûüœæh"
META_MARKERS = ("vocabulaire de cette leçon", "lesson's vocabulary", "vocabulario de esta lección",
                "on utilise souvent cette expression", "this expression is often used")

SPECIAL_EXAMPLES = {
    "bonjour": ("Je dis bonjour à mes voisins.", "I say hello to my neighbors.", "Digo hola a mis vecinos.", "我向邻居们问好。"),
    "merci": ("Je vous remercie pour votre aide.", "I thank you for your help.", "Le agradezco su ayuda.", "谢谢您的帮助。"),
    "course": ("J'ai regardé la course à la télévision.", "I watched the race on television.", "Vi la carrera en la televisión.", "我在电视上看了比赛。"),
    "courses": ("J'ai fait les courses pour le dîner.", "I did the shopping for dinner.", "Hice la compra para la cena.", "我为晚餐买了东西。"),
    "en cours": ("Le projet est en cours.", "The project is underway.", "El proyecto está en curso.", "项目正在进行中。"),
    "en cours de": ("Le travail est en cours de préparation.", "The work is being prepared.", "El trabajo se está preparando.", "这项工作正在准备中。"),
    "dans le cours de": ("Dans le cours de la journée, le temps a changé.", "During the day, the weather changed.", "Durante el día, el tiempo cambió.", "一天中天气发生了变化。"),
}

PREPOSITION_EXAMPLES = {
    "à": ("Je vais à la gare.", "I am going to the station.", "Voy a la estación.", "我去车站。"),
    "de": ("Je parle de mon travail.", "I am talking about my work.", "Hablo de mi trabajo.", "我在谈论我的工作。"),
    "dans": ("Le livre est dans le sac.", "The book is in the bag.", "El libro está en el bolso.", "书在包里。"),
    "sur": ("Le livre est sur la table.", "The book is on the table.", "El libro está sobre la mesa.", "书在桌子上。"),
    "sous": ("Le chat dort sous la table.", "The cat is sleeping under the table.", "El gato duerme debajo de la mesa.", "猫在桌子下面睡觉。"),
    "chez": ("Je dîne chez mes parents.", "I am having dinner at my parents' house.", "Ceno en casa de mis padres.", "我在父母家吃晚饭。"),
    "pour": ("Ce cadeau est pour toi.", "This gift is for you.", "Este regalo es para ti.", "这份礼物是给你的。"),
    "par": ("Nous passons par le parc.", "We are going through the park.", "Pasamos por el parque.", "我们经过公园。"),
    "en": ("Je voyage en train.", "I am traveling by train.", "Viajo en tren.", "我乘火车旅行。"),
    "entre": ("Le café est entre la banque et la poste.", "The café is between the bank and the post office.", "El café está entre el banco y la oficina de correos.", "咖啡馆在银行和邮局之间。"),
    "avec": ("Je travaille avec mes collègues.", "I work with my colleagues.", "Trabajo con mis colegas.", "我和同事一起工作。"),
    "sans": ("Il est parti sans son téléphone.", "He left without his phone.", "Se fue sin su teléfono.", "他没带手机就离开了。"),
    "avant": ("Je pars avant midi.", "I am leaving before noon.", "Me voy antes del mediodía.", "我中午前出发。"),
    "après": ("Je rentre après le travail.", "I come home after work.", "Vuelvo a casa después del trabajo.", "我下班后回家。"),
    "pendant": ("Je lis pendant le voyage.", "I read during the trip.", "Leo durante el viaje.", "我在旅途中阅读。"),
    "depuis": ("J'habite ici depuis deux ans.", "I have lived here for two years.", "Vivo aquí desde hace dos años.", "我在这里住了两年。"),
    "vers": ("Nous marchons vers la gare.", "We are walking toward the station.", "Caminamos hacia la estación.", "我们向车站走去。"),
    "autour de": ("Les enfants courent autour de la table.", "The children are running around the table.", "Los niños corren alrededor de la mesa.", "孩子们在桌子周围跑。"),
    "près de": ("L'hôtel est près de la gare.", "The hotel is near the station.", "El hotel está cerca de la estación.", "酒店在车站附近。"),
    "loin de": ("Il habite loin de Paris.", "He lives far from Paris.", "Vive lejos de París.", "他住得离巴黎很远。"),
    "devant": ("La voiture est devant la maison.", "The car is in front of the house.", "El coche está delante de la casa.", "汽车在房子前面。"),
    "derrière": ("Le jardin est derrière la maison.", "The garden is behind the house.", "El jardín está detrás de la casa.", "花园在房子后面。"),
    "à côté de": ("La pharmacie est à côté de la boulangerie.", "The pharmacy is next to the bakery.", "La farmacia está al lado de la panadería.", "药店在面包店旁边。"),
    "en face de": ("La banque est en face de la gare.", "The bank is opposite the station.", "El banco está enfrente de la estación.", "银行在车站对面。"),
    "grâce à": ("Grâce à vous, j'ai compris.", "Thanks to you, I understood.", "Gracias a usted, entendí.", "多亏了您，我明白了。"),
    "malgré": ("Malgré la pluie, nous sortons.", "Despite the rain, we are going out.", "A pesar de la lluvia, salimos.", "尽管下雨，我们还是出门了。"),
    "selon": ("Selon le médecin, tout va bien.", "According to the doctor, everything is fine.", "Según el médico, todo está bien.", "医生说一切都很好。"),
    "parmi": ("Il est parmi ses amis.", "He is among his friends.", "Está entre sus amigos.", "他在朋友们中间。"),
}


def norm(value: str) -> str:
    return " ".join(unicodedata.normalize("NFC", value).replace("’", "'").split()).casefold()


FALLBACK_TRANSLATIONS = {
    "lequel": ("which one", "cuál", "哪一个"),
    "tire": ("maple taffy", "caramelo de arce", "枫糖太妃糖"),
    "tien": ("yours; your", "tuyo; tuyo", "你的；属于你的"),
    "jusqu' à ce que": ("until", "hasta que", "直到……为止"),
    "tantour": ("soon; later", "pronto; más tarde", "很快；稍后"),
    "taxis": ("taxis", "taxis", "出租车"),
    "tout d' un coup": ("suddenly", "de repente", "突然"),
    "toc": ("knock; tap", "golpe; toque", "敲；轻敲"),
    "la veille de": ("on the eve of", "la víspera de", "在……前夕"),
    "linger": ("to linger", "demorarse", "逗留"),
    "tatin": ("tarte Tatin", "tarta Tatin", "塔丁挞"),
    "jusqu' à terre": ("down to the ground", "hasta el suelo", "一直到地面"),
    "traits": ("features; traits", "rasgos; facciones", "特征；面容"),
    "jusque au plafond de": ("up to the ceiling of", "hasta el techo de", "一直到……的天花板"),
    "tire - bouchon": ("corkscrew", "sacacorchos", "开瓶器"),
    "jusqu' au lendemain": ("until the next day", "hasta el día siguiente", "直到第二天"),
    "tout le jour": ("all day", "todo el día", "整天"),
    "jusqu' ici": ("until now; so far", "hasta ahora", "到目前为止"),
    "kiffe": ("to love; to really like", "encantar", "喜欢；很喜欢"),
    "le on": ("one; people", "uno; la gente", "人们；有人"),
    "tout au moins": ("at least", "al menos", "至少"),
    "théodore": ("Theodore", "Teodoro", "西奥多"),
    "jusqu' à quelle heure": ("until what time", "hasta qué hora", "到几点"),
    "laurentienne": ("Laurentian", "laurentino", "劳伦蒂安的"),
    "tiers - monde": ("Third World", "Tercer Mundo", "第三世界"),
    "toujours de ce que tu as": ("always from what you have", "siempre de lo que tienes", "总是用你拥有的东西"),
    "le plupart": ("most; the majority", "la mayoría", "大多数"),
    "tienne": ("hold; keep", "sostenga; mantenga", "拿住；保持"),
    "lave - main": ("washbasin", "lavamanos", "洗手盆"),
    "levis que": ("provided that", "siempre que", "只要"),
    "terraser": ("to knock down; overpower", "derribar; dominar", "击倒；制服"),
    "tout le contraire": ("quite the opposite", "todo lo contrario", "恰恰相反"),
    "l' envie de": ("the desire to", "las ganas de", "想要……的愿望"),
    "lapidaire gallo - romain": ("Gallo-Roman lapidary", "lapidario galorromano", "高卢罗马碑铭的"),
    "touarègues": ("Tuareg", "tuareg", "图阿雷格人的"),
    "jusque au coucher du": ("until sunset", "hasta la puesta del sol", "直到日落"),
    "system": ("system", "sistema", "系统"),
    "jusque au parc de": ("as far as the park of", "hasta el parque de", "一直到……公园"),
    "tchao": ("bye; goodbye", "adiós", "再见"),
    "pas moins": ("no less", "no menos", "不少于"),
    "principalement": ("principally; mainly", "principalmente", "主要地"),
    "au jour": ("day by day", "al día", "按天"),
    "vivement": ("vigorously; briskly", "vivamente", "充满活力地"),
    "tee-shirt": ("T-shirt", "camiseta", "T恤"),
    "sculpteur": ("sculptor", "escultor", "雕塑家"),
    "renseignement": ("information; advice", "información; consejo", "信息；建议"),
    "égyptiens": ("Egyptians", "egipcios", "埃及人"),
    "parures": ("ornaments; adornments", "adornos", "装饰品"),
    "au plus vite": ("as soon as possible", "lo antes posible", "尽快"),
    "au juste": ("exactly", "exactamente", "确切地"),
    "écoattitude": ("eco-friendly attitude", "ecoactitud", "环保态度"),
    "au hasard": ("at random", "al azar", "随机地"),
    "par tout": ("everywhere; in all circumstances", "por todas partes", "到处；在各种情况下"),
    "au même titre que": ("in the same way as", "del mismo modo que", "和……同样"),
    "au prix de": ("at the cost of", "al precio de", "以……为代价"),
    "partout ailleurs": ("everywhere else", "en cualquier otro lugar", "其他任何地方"),
    "épargne - logement": ("housing savings", "ahorro para vivienda", "住房储蓄"),
    "par définition": ("by definition", "por definición", "按定义"),
    "au premier pas": ("at the first step", "en el primer paso", "在第一步时"),
    "au pas": ("at a walking pace", "a paso", "以步行速度"),
    "étonnement": ("astonishment", "asombro", "惊讶"),
    "par - dessus de": ("over; above", "por encima de", "在……上方"),
    "équilibré": ("well-balanced", "equilibrado", "均衡的"),
    "au long de": ("throughout; along", "a lo largo de", "在整个……期间"),
    "au fort de": ("at the height of", "en pleno", "在……最盛时"),
    "écerveler": ("to rack one's brains", "devanarse los sesos", "绞尽脑汁"),
    "équipé": ("equipped", "equipado", "配备齐全的"),
    "au premier chef": ("first and foremost", "ante todo", "首要地"),
    "au premier moment": ("at first", "al principio", "起初"),
    "par demi - canton": ("by half-canton", "por medio cantón", "通过半州"),
    "par habitude": ("by habit", "por costumbre", "出于习惯"),
    "au gré de": ("according to the will of", "a voluntad de", "按照……的意愿"),
    "par le biais": ("through; by means of", "por medio de", "通过"),
    "par portable": ("by cell phone", "por teléfono móvil", "通过手机"),
    "épar": ("scattered", "disperso", "分散的"),
    "par derrière": ("from behind", "por detrás", "从后面"),
    "par l' intermédiaire": ("via; through the intermediary of", "por medio de", "通过……的中介"),
    "au environs de": ("around; approximately", "alrededor de", "大约；在……附近"),
    "par là même": ("by the same token", "por lo mismo", "同样地；因此"),
    "à votre tour": ("your turn", "su turno", "轮到您"),
    "au froid": ("in the cold", "en el frío", "在寒冷中"),
    "au pot": ("in the pot", "en la olla", "在锅里"),
    "écoutes": ("listening sessions; listens", "escuchas", "收听；聆听"),
    "évasé": ("flared", "acampanado", "喇叭形的"),
    "par chat": ("by chat", "por chat", "通过聊天"),
    "par ordre de": ("by order of", "por orden de", "按照……的命令"),
    "à vue de": ("in sight of; at a glance", "a la vista de", "在……视线内"),
    "émigré": ("emigrant; émigré", "emigrado", "移民的"),
    "par moins": ("by less; to a lesser extent", "en menor medida", "较少地"),
    "par trop": ("too much; excessively", "demasiado", "过于"),
    "par - ci": ("this way; here", "por aquí", "这边；这里"),
    "au haut de": ("at the top of", "en lo alto de", "在……顶部"),
    "par - là": ("over there", "por allí", "那边"),
    "au parché": ("parched; dried out", "reseco", "干燥的"),
    "écosceptique": ("eco-skeptic", "ecologista escéptico", "生态怀疑论者"),
    "par de": ("by; through", "por", "通过"),
    "écolo": ("eco-friendly; environmentalist", "ecológico", "环保的；环保主义者"),
    "au dépens de": ("at the expense of", "a costa de", "以……为代价"),
    "au départ de": ("from the departure of", "a partir de", "从……出发"),
    "rien que": ("just; merely", "no más que", "只是；仅仅"),
    "réinventer": ("to reinvent", "reinventar", "重新发明"),
    "regroupement": ("regrouping", "reagrupación", "重组"),
    "remarquablement": ("remarkably", "notablemente", "显著地"),
    "réduit": ("reduced", "reducido", "减少的"),
    "répertorier": ("to list; catalogue", "catalogar", "列出；编目"),
    "réintroduire": ("to reintroduce", "reintroducir", "重新引入"),
    "rugbyman": ("rugby player", "jugador de rugby", "橄榄球运动员"),
    "rythmé": ("rhythmic", "rítmico", "有节奏的"),
    "rengainez": ("put away; sheathe", "enfunde", "收起；收刀入鞘"),
    "rendant": ("making; rendering", "haciendo; volviendo", "使得；变得"),
    "remédier": ("to remedy", "remediar", "解决；补救"),
    "relancer": ("to relaunch", "relanzar", "重新启动"),
    "relire": ("to reread", "releer", "重读"),
    "rengainer": ("to sheathe", "enfundar", "收刀入鞘"),
    "roman - photo": ("photo story", "fotonovela", "连环画小说"),
    "rigoureusement": ("rigorously", "rigurosamente", "严格地"),
    "réjouissances": ("festivities", "celebraciones", "庆祝活动"),
    "ruser": ("to use cunning", "ingeniárselas", "设法；耍机灵"),
    "réchauffe": ("warms up; reheats", "recalienta", "加热；重新加热"),
    "réaménager": ("to redevelop; rearrange", "reestructurar", "重新改造；重新安排"),
    "rendre visiter": ("to take someone to visit", "llevar de visita", "带某人参观"),
    "renchérir": ("to become more expensive; raise the price", "encarecer", "变得更贵；提高价格"),
    "renforcement": ("reinforcement", "refuerzo", "加强"),
    "refroidissement": ("cooling", "enfriamiento", "冷却"),
    "renouer": ("to renew; reconnect", "reanudar", "重新建立；恢复"),
    "reloger": ("to rehouse", "realojar", "重新安置"),
    "regardant": ("looking; watching", "mirando", "看着"),
    "renouvellement": ("renewal; renewal of a contract", "renovación", "更新；续约"),
    "reléguer": ("to relegate", "relegar", "贬到；降级"),
    "réapprendre": ("to relearn", "reaprender", "重新学习"),
    "rendormir": ("to fall back asleep", "volverse a dormir", "再次入睡"),
    "référentiel": ("reference framework", "marco de referencia", "参考框架"),
    "rudement": ("very; roughly", "tremendamente", "非常地；猛烈地"),
    "rousse": ("red-haired; russet", "pelirroja; rojizo", "红发的；红褐色的"),
    "renier": ("to deny; repudiate", "renegar de", "否认；背弃"),
    "remariage": ("remarriage", "nuevo matrimonio", "再婚"),
    "réadaptation": ("readaptation; rehabilitation", "readaptación", "重新适应；康复"),
    "récuser": ("to challenge; disqualify", "recusar", "拒绝；申请回避"),
    "ruée": ("rush; stampede", "avalancha", "蜂拥；涌入"),
}

CHINESE_TRANSLATION_CORRECTIONS = {
    "donc d' abord": "所以首先",
    "k": "字母K；千（缩写）",
    "admiratif": "赞赏的；钦佩的",
    "domaine d' avant - garde": "前卫领域",
    "dramatiser": "使戏剧化；夸大",
    "dormeur": "睡眠者；爱睡觉的人",
    "donc moins": "所以更少",
    "académisme et avant - garde": "学院派与前卫",
    "adn": "脱氧核糖核酸（DNA）",
    "achoppement": "绊脚；障碍",
    "addict": "成瘾者；入迷的人",
    "doléances": "不满；抱怨",
    "académisme": "学院派；学究主义",
    "accrue": "增加的；增长的",
    "doléance": "不满；抱怨",
    "doser": "定量；确定……的剂量",
    "donc tout d' abord": "所以首先",
    "dopamine": "多巴胺",
    "dracher": "下小雨",
    "ducal": "公爵的",
    "accouder": "用肘支撑；靠肘",
    "dommageable": "有害的；造成损害的",
    "adaptable": "可适应的；可改编的",
    "abyme": "深渊；深渊意象",
    "droguiste": "药店老板；药剂师",
    "droguerie": "药店；杂货药品店",
    "douro": "杜罗河",
    "acrobatique": "杂技的；特技的",
}

FALLBACK_EXAMPLES = {
    "lequel": ("Lequel préférez-vous ?", "Which one do you prefer?", "¿Cuál prefiere?", "您更喜欢哪一个？"),
    "tire": ("La tire d'érable est vendue au marché.", "Maple taffy is sold at the market.", "El caramelo de arce se vende en el mercado.", "市场上出售枫糖太妃糖。"),
    "tien": ("C'est ton livre, pas le tien.", "It is your book, not yours.", "Es tu libro, no el tuyo.", "这是你的书，不是你的那个。"),
    "jusqu' à ce que": ("Attends ici jusqu'à ce que je revienne.", "Wait here until I come back.", "Espera aquí hasta que vuelva.", "在我回来之前请在这里等着。"),
    "tantour": ("Je reviendrai tantour.", "I will be back soon.", "Volveré pronto.", "我很快就回来。"),
    "taxis": ("Les taxis attendent devant la gare.", "The taxis are waiting in front of the station.", "Los taxis esperan delante de la estación.", "出租车在车站前等候。"),
    "tout d' un coup": ("Tout d'un coup, la lumière s'est éteinte.", "Suddenly, the light went out.", "De repente, se apagó la luz.", "突然，灯灭了。"),
    "toc": ("Il a frappé à la porte : toc, toc !", "He knocked on the door: knock, knock!", "Llamó a la puerta: ¡toc, toc!", "他敲了敲门：咚，咚！"),
    "la veille de": ("La veille de l'examen, je révise.", "I revise on the eve of the exam.", "Repaso la víspera del examen.", "考试前夕我在复习。"),
    "linger": ("Il ne faut pas linger ici.", "We must not linger here.", "No debemos demorarnos aquí.", "我们不能在这里逗留。"),
    "tatin": ("Nous partageons une tarte Tatin.", "We are sharing a tarte Tatin.", "Compartimos una tarta Tatin.", "我们一起吃塔丁挞。"),
    "jusqu' à terre": ("La robe descend jusqu'à terre.", "The dress reaches down to the ground.", "El vestido llega hasta el suelo.", "这条裙子一直垂到地面。"),
    "traits": ("Ses traits sont très fins.", "Her features are very delicate.", "Sus facciones son muy delicadas.", "她的面容非常精致。"),
    "jusque au plafond de": ("Le rideau monte jusque au plafond de la chambre.", "The curtain reaches up to the bedroom ceiling.", "La cortina llega hasta el techo del dormitorio.", "窗帘一直垂到卧室的天花板。"),
    "tire - bouchon": ("Le tire-bouchon est dans le tiroir.", "The corkscrew is in the drawer.", "El sacacorchos está en el cajón.", "开瓶器在抽屉里。"),
    "jusqu' au lendemain": ("Nous avons attendu jusqu'au lendemain.", "We waited until the next day.", "Esperamos hasta el día siguiente.", "我们一直等到第二天。"),
    "tout le jour": ("Il a plu tout le jour.", "It rained all day.", "Llovió todo el día.", "下了一整天的雨。"),
    "jusqu' ici": ("Jusqu'ici, tout va bien.", "So far, everything is fine.", "Hasta ahora, todo va bien.", "到目前为止，一切都很好。"),
    "kiffe": ("Elle kiffe cette chanson.", "She really likes this song.", "A ella le encanta esta canción.", "她很喜欢这首歌。"),
    "le on": ("Le pronom « on » remplace souvent « nous ».", "The pronoun “on” often replaces “nous”.", "El pronombre «on» a menudo sustituye a «nous».", "代词“on”经常代替“nous”。"),
    "tout au moins": ("C'est difficile, tout au moins au début.", "It is difficult, at least at first.", "Es difícil, al menos al principio.", "这很难，至少一开始是这样。"),
    "théodore": ("Théodore habite à Lyon.", "Theodore lives in Lyon.", "Teodoro vive en Lyon.", "西奥多住在里昂。"),
    "jusqu' à quelle heure": ("Jusqu'à quelle heure restez-vous ouvert ?", "Until what time are you open?", "¿Hasta qué hora están abiertos?", "你们营业到几点？"),
    "laurentienne": ("La forêt laurentienne est magnifique.", "The Laurentian forest is magnificent.", "El bosque laurentino es magnífico.", "劳伦蒂安森林非常壮美。"),
    "tiers - monde": ("Ce terme historique désignait le tiers-monde.", "This historical term referred to the Third World.", "Este término histórico se refería al Tercer Mundo.", "这个历史术语指的是第三世界。"),
    "toujours de ce que tu as": ("Tout dépend toujours de ce que tu as.", "Everything always depends on what you have.", "Todo depende siempre de lo que tienes.", "一切总是取决于你拥有的东西。"),
    "le plupart": ("La plupart des étudiants arrivent à l'heure.", "Most students arrive on time.", "La mayoría de los estudiantes llega a tiempo.", "大多数学生准时到达。"),
    "tienne": ("Il faut qu'elle tienne la porte ouverte.", "She must hold the door open.", "Tiene que mantener la puerta abierta.", "她必须扶着门让它保持打开。"),
    "lave - main": ("Le lave-main est près de l'entrée.", "The washbasin is near the entrance.", "El lavamanos está cerca de la entrada.", "洗手盆在入口附近。"),
    "levis que": ("Nous partirons, pourvu que tu sois prêt.", "We will leave, provided that you are ready.", "Nos iremos, siempre que estés listo.", "只要你准备好了，我们就出发。"),
    "terraser": ("Il veut terraser son adversaire.", "He wants to overpower his opponent.", "Quiere derribar a su adversario.", "他想击倒对手。"),
    "tout le contraire": ("C'est tout le contraire de ce que je pensais.", "It is quite the opposite of what I thought.", "Es todo lo contrario de lo que pensaba.", "这恰恰与我想的相反。"),
    "l' envie de": ("J'ai envie de voyager.", "I feel like travelling.", "Tengo ganas de viajar.", "我想去旅行。"),
    "lapidaire gallo - romain": ("La collection présente une inscription lapidaire gallo-romaine.", "The collection includes a Gallo-Roman inscription.", "La colección presenta una inscripción galorromana.", "这件藏品是一块高卢罗马铭文。"),
    "touarègues": ("Les traditions touarègues sont très riches.", "Tuareg traditions are very rich.", "Las tradiciones tuareg son muy ricas.", "图阿雷格人的传统非常丰富。"),
    "jusque au coucher du": ("Nous avons marché jusque au coucher du soleil.", "We walked until sunset.", "Caminamos hasta la puesta del sol.", "我们一直走到日落。"),
    "system": ("Le système fonctionne correctement.", "The system works properly.", "El sistema funciona correctamente.", "系统运行正常。"),
    "jusque au parc de": ("Nous avons marché jusque au parc de la ville.", "We walked as far as the city park.", "Caminamos hasta el parque de la ciudad.", "我们一直走到城市公园。"),
    "tchao": ("Tchao, à demain !", "Bye, see you tomorrow!", "¡Adiós, hasta mañana!", "再见，明天见！"),
    "au jour": ("Au jour le jour, il note ses dépenses.", "Day by day, he records his expenses.", "Día a día, anota sus gastos.", "他每天记录开支。"),
    "au plus vite": ("Répondez au plus vite, s'il vous plaît.", "Please reply as soon as possible.", "Responda lo antes posible, por favor.", "请尽快回复。"),
    "au juste": ("Que voulez-vous dire au juste ?", "What exactly do you mean?", "¿Qué quiere decir exactamente?", "您到底是什么意思？"),
    "au hasard": ("J'ai choisi un livre au hasard.", "I chose a book at random.", "Elegí un libro al azar.", "我随便选了一本书。"),
    "par tout": ("Par tout temps, elle marche jusqu'au travail.", "In all weather, she walks to work.", "Con cualquier tiempo, ella camina al trabajo.", "无论天气如何，她都步行上班。"),
    "partout ailleurs": ("Ici c'est calme, mais partout ailleurs il y a du bruit.", "It is quiet here, but noisy everywhere else.", "Aquí hay tranquilidad, pero en cualquier otro lugar hay ruido.", "这里很安静，但其他地方都很吵。"),
    "par définition": ("Par définition, un triangle a trois côtés.", "By definition, a triangle has three sides.", "Por definición, un triángulo tiene tres lados.", "根据定义，三角形有三条边。"),
    "au premier pas": ("Au premier pas, l'exercice semble difficile.", "At the first step, the exercise seems difficult.", "Al primer paso, el ejercicio parece difícil.", "刚开始时，这道练习似乎很难。"),
    "au pas": ("Les soldats avancent au pas.", "The soldiers are marching at a walking pace.", "Los soldados avanzan a paso.", "士兵们迈步前进。"),
    "au même titre que": ("Il est responsable au même titre que son collègue.", "He is responsible in the same way as his colleague.", "Es responsable del mismo modo que su colega.", "他和同事一样负有责任。"),
    "au prix de": ("Au prix de nombreux efforts, elle a réussi.", "Through great effort, she succeeded.", "A costa de muchos esfuerzos, lo consiguió.", "她付出了许多努力才成功。"),
    "au long de": ("Au long de la route, nous avons admiré le paysage.", "Along the road, we admired the scenery.", "A lo largo del camino, admiramos el paisaje.", "一路上我们欣赏了风景。"),
    "au fort de": ("Au fort de la tempête, nous sommes restés à l'abri.", "At the height of the storm, we stayed sheltered.", "En pleno temporal, nos quedamos a cubierto.", "暴风雨最猛烈时，我们待在避风处。"),
    "au premier chef": ("Cette décision concerne au premier chef les habitants.", "This decision primarily concerns the residents.", "Esta decisión afecta ante todo a los habitantes.", "这项决定首先关系到居民。"),
    "au premier moment": ("Au premier moment, je n'ai pas compris.", "At first, I did not understand.", "Al principio no entendí.", "刚开始时我没明白。"),
    "par habitude": ("Par habitude, je me lève tôt.", "By habit, I get up early.", "Por costumbre, me levanto temprano.", "我习惯早起。"),
    "au gré de": ("Il voyage au gré de ses envies.", "He travels according to his wishes.", "Viaja según sus deseos.", "他随自己的意愿旅行。"),
    "par le biais": ("Nous avons envoyé le document par le biais du site.", "We sent the document through the website.", "Enviamos el documento por medio del sitio web.", "我们通过网站发送了文件。"),
    "par portable": ("Je vous réponds par portable.", "I will reply to you by cell phone.", "Le responderé por teléfono móvil.", "我通过手机回复您。"),
    "par derrière": ("Il est arrivé par derrière.", "He came from behind.", "Llegó por detrás.", "他从后面来了。"),
    "par l' intermédiaire": ("J'ai rencontré ce contact par l'intermédiaire d'un ami.", "I met this contact through a friend.", "Conocí a este contacto por medio de un amigo.", "我通过一位朋友认识了这个联系人。"),
    "au environs de": ("Aux environs de midi, nous ferons une pause.", "Around noon, we will take a break.", "Alrededor del mediodía, haremos una pausa.", "中午左右我们会休息一下。"),
    "par là même": ("Il a accepté et, par là même, il a clos la discussion.", "He agreed and thereby ended the discussion.", "Aceptó y, por lo mismo, puso fin a la discusión.", "他同意了，也因此结束了讨论。"),
    "à votre tour": ("À votre tour, présentez-vous au groupe.", "In your turn, introduce yourself to the group.", "A su turno, preséntese al grupo.", "轮到您时，请向大家介绍自己。"),
    "au froid": ("Les aliments restent au froid.", "The food is kept in the cold.", "Los alimentos se conservan en frío.", "食物放在冷处保存。"),
    "au pot": ("Elle ajoute les légumes au pot.", "She adds the vegetables to the pot.", "Añade las verduras a la olla.", "她把蔬菜放进锅里。"),
    "par chat": ("Nous avons discuté par chat.", "We discussed it by chat.", "Lo hablamos por chat.", "我们通过聊天讨论了这件事。"),
    "par ordre de": ("Il agit par ordre du directeur.", "He is acting on the director's orders.", "Actúa por orden del director.", "他奉主任之命行事。"),
    "à vue de": ("À vue de nez, il reste dix kilomètres.", "At a rough estimate, ten kilometers remain.", "A ojo, quedan diez kilómetros.", "估计还剩十公里。"),
    "par moins": ("Cette méthode coûte par moins que l'autre.", "This method costs less than the other one.", "Este método cuesta menos que el otro.", "这种方法比另一种花费少。"),
    "par trop": ("Ne dépensez pas par trop d'argent pour ce cadeau.", "Do not spend too much money on this gift.", "No gaste demasiado dinero en este regalo.", "不要为这份礼物花太多钱。"),
    "par - ci": ("Venez par-ci, s'il vous plaît.", "Come this way, please.", "Venga por aquí, por favor.", "请往这边来。"),
    "au haut de": ("En haut de la colline, la vue est magnifique.", "At the top of the hill, the view is magnificent.", "En lo alto de la colina, la vista es magnífica.", "在山顶，景色非常壮美。"),
    "par - là": ("Regardez par-là !", "Look over there!", "¡Mire por allí!", "看那边！"),
    "par de": ("Par de simples gestes, on peut aider.", "Simple gestures can help.", "Con gestos sencillos se puede ayudar.", "通过简单的举动就能提供帮助。"),
    "au dépens de": ("Il ne faut pas rire aux dépens des autres.", "You should not laugh at other people's expense.", "No hay que reírse a costa de los demás.", "不应该拿别人开玩笑。"),
    "au départ de": ("Au départ de Paris, le train est presque vide.", "When it leaves Paris, the train is almost empty.", "Al salir de París, el tren está casi vacío.", "火车从巴黎出发时几乎是空的。"),
}


def fallback_translation(word: str) -> tuple[str, str, str] | None:
    for key, value in FALLBACK_TRANSLATIONS.items():
        if norm(key) == norm(word):
            return value
    return None


def fallback_example(word: str) -> tuple[str, str, str, str] | None:
    for key, value in FALLBACK_EXAMPLES.items():
        if norm(key) == norm(word):
            return value
    return None


POS_SPECIAL_EXAMPLES = {
    ("de", "determiner"): ("La couverture de ce livre est bleue.", "The cover of this book is blue.", "La portada de este libro es azul.", "这本书的封面是蓝色的。"),
    ("dont", "pronoun"): ("C'est le livre dont je vous ai parlé.", "This is the book I told you about.", "Este es el libro del que le hablé.", "这就是我跟您提过的那本书。"),
    ("que", "adverb"): ("Je sais que tu as raison.", "I know that you are right.", "Sé que tienes razón.", "我知道你是对的。"),
    ("public", "adjective"): ("Le transport public est pratique.", "Public transport is convenient.", "El transporte público es práctico.", "公共交通很方便。"),
    ("public", "noun"): ("Le public applaudit à la fin du spectacle.", "The audience applauded at the end of the show.", "El público aplaudió al final del espectáculo.", "演出结束时观众鼓起了掌。"),
    ("-ci", "adverb"): ("Ce livre-ci est à moi.", "This book here is mine.", "Este libro de aquí es mío.", "这本书是我的。"),
    ("faillir", "verb"): ("Il a failli tomber dans l'escalier.", "He nearly fell on the stairs.", "Casi se cae en la escalera.", "他差点从楼梯上摔下来。"),
    ("avenir", "noun"): ("Nous préparons l'avenir de nos enfants.", "We are preparing our children's future.", "Preparamos el futuro de nuestros hijos.", "我们正在为孩子们的未来做准备。"),
    ("rien que", "adverb"): ("Je n'ai besoin de rien que de votre accord.", "I need nothing but your agreement.", "No necesito más que su acuerdo.", "我只需要您的同意。"),
    ("remarquablement", "adverb"): ("Elle travaille remarquablement bien.", "She works remarkably well.", "Trabaja extraordinariamente bien.", "她工作得非常出色。"),
    ("rengainez", "verb"): ("Rengainez votre épée.", "Sheathe your sword.", "Enfunde su espada.", "请把剑收回鞘中。"),
    ("rendre visiter", "verb"): ("Nous allons rendre visite à nos amis.", "We are going to visit our friends.", "Vamos a visitar a nuestros amigos.", "我们要去拜访朋友。"),
    ("regardant", "verb"): ("En regardant le ciel, elle sourit.", "Looking at the sky, she smiles.", "Al mirar el cielo, sonríe.", "她看着天空，脸上露出了微笑。"),
    ("réchauffe", "verb"): ("Elle réchauffe la soupe.", "She reheats the soup.", "Recalienta la sopa.", "她把汤重新加热。"),
    ("rengainer", "verb"): ("Il doit rengainer son épée.", "He must sheathe his sword.", "Debe enfundar su espada.", "他必须把剑收回鞘中。"),
    ("rengainez", "preposition"): ("Rengainez votre épée.", "Sheathe your sword.", "Enfunde su espada.", "请把剑收回鞘中。"),
    ("rien que", "conjunction"): ("Rien que cette nouvelle me réjouit.", "This news alone makes me happy.", "Solo esta noticia me alegra.", "仅仅这个消息就让我高兴。"),
}


def clean(value: str) -> str:
    value = re.sub(r"<!--.*?-->", "", value, flags=re.S)
    value = re.sub(r"'''|''", "", value)
    value = re.sub(r"\[\[([^\]|]+)\|([^\]]+)\]\]", r"\2", value)
    value = re.sub(r"\[\[([^\]]+)\]\]", r"\1", value)
    value = re.sub(r"\{\{[^{}]*\}\}", "", value)
    value = re.sub(r"<[^>]+>", "", value)
    value = re.sub(r"\[[^\]]+\]", "", value)
    return re.sub(r"\s+", " ", value).strip().replace(" ,", ",").replace(" .", ".")


def marker_base(word: str) -> str:
    return re.sub(r"\s+", " ", re.sub(r"\([^)]*\)", "", word)).strip().replace(" - ", "-")


def french_block(wikitext: str) -> str:
    marker = "== {{langue|fr}} =="
    if marker in wikitext:
        return wikitext.split(marker, 1)[1].split("\n== {{langue|", 1)[0]
    return wikitext


def translations(block: str, codes: tuple[str, ...]) -> str:
    values: list[str] = []
    code_re = "|".join(re.escape(code) for code in codes)
    for match in re.finditer(r"\{\{trad[-+]?\|(?:" + code_re + r")\|([^|}]+)", block):
        value = clean(match.group(1))
        if value and value not in values:
            values.append(value)
    return "; ".join(values[:3])


def examples_from_wikitext(block: str) -> list[str]:
    lines = block.splitlines()
    result: list[str] = []
    for index, line in enumerate(lines):
        if "{{exemple" not in line:
            continue
        parts = [part.strip() for part in line.split("|")[1:] if part.strip()]
        for following in lines[index + 1:index + 8]:
            following = following.strip()
            if following.startswith("}}") or following.startswith("| source"):
                break
            if following.startswith("|"):
                value = following[1:].strip()
                if value and not value.startswith(("lang=", "source=")):
                    parts.append(value)
        candidate = clean(" ".join(parts))
        if 8 <= len(candidate) <= 220 and not any(marker in candidate.casefold() for marker in META_MARKERS):
            if candidate not in result:
                result.append(candidate)
    return sorted(result, key=len)


def ipa_from(block: str) -> str:
    match = re.search(r"\{\{pron\|([^|}]+)(?:\|fr)?\}\}", block)
    return f"/{match.group(1).strip()}/" if match else ""


def gender_from(word: str, block: str) -> str:
    if re.search(r"\{\{(?:mf|m/f)\}\}", block):
        return "m. / f."
    if re.search(r"\{\{f\}\}|\b[fF]\.\b", block):
        return "f."
    if re.search(r"\{\{m\}\}|\b[mM]\.\b", block):
        return "m."
    return "f." if re.search(r"(?:e|ion|té|ance|ence|ie|ure|ette|ité|aison|ière)$", marker_base(word), re.I) else "m."


def plural(word: str) -> str:
    if re.search(r"(?:s|x|z)$", word, re.I):
        return word
    if re.search(r"al$", word, re.I) and not re.search(r"(?:bal|carnaval|festival)$", word, re.I):
        return word[:-2] + "aux"
    if re.search(r"(?:eau|au|eu)$", word, re.I):
        return word + "x"
    return word + "s"


def adjective_forms(word: str) -> list[list[str]]:
    base = marker_base(word)
    marker = re.search(r"\(([^)]*)\)", word)
    feminine = base
    if marker and marker.group(1) == "e":
        feminine = base + "e"
    elif marker and marker.group(1) == "ve":
        feminine = re.sub(r"f$", "ve", base)
    elif marker and marker.group(1) == "se":
        feminine = re.sub(r"x$", "se", base)
    elif marker and marker.group(1) == "le":
        feminine = re.sub(r"l$", "lle", base)
    elif marker and marker.group(1) == "ère":
        feminine = re.sub(r"er$", "ère", base)
    elif marker and marker.group(1) == "trice":
        feminine = re.sub(r"teur$", "trice", base)
    elif base == "beau":
        feminine = "belle"
    elif not re.search(r"e$", feminine, re.I):
        feminine += "e"
    masculine_plural = base if re.search(r"[sxz]$", base, re.I) else base + "s"
    feminine_plural = feminine if re.search(r"[sxz]$", feminine, re.I) else feminine + "s"
    return [["féminin", feminine], ["masculin pluriel", masculine_plural], ["féminin pluriel", feminine_plural]]


IRREGULAR = {
    "avoir": ("3e groupe / auxiliaire", "avoir", "eu", "ayant", ["ai", "as", "a", "avons", "avez", "ont"], ["avais", "avais", "avait", "avions", "aviez", "avaient"]),
    "être": ("3e groupe / auxiliaire", "avoir", "été", "étant", ["suis", "es", "est", "sommes", "êtes", "sont"], ["étais", "étais", "était", "étions", "étiez", "étaient"]),
    "aller": ("3e groupe", "être", "allé", "allant", ["vais", "vas", "va", "allons", "allez", "vont"], ["allais", "allais", "allait", "allions", "alliez", "allaient"]),
    "faire": ("3e groupe", "avoir", "fait", "faisant", ["fais", "fais", "fait", "faisons", "faites", "font"], ["faisais", "faisais", "faisait", "faisions", "faisiez", "faisaient"]),
    "pouvoir": ("3e groupe", "avoir", "pu", "pouvant", ["peux", "peux", "peut", "pouvons", "pouvez", "peuvent"], ["pouvais", "pouvais", "pouvait", "pouvions", "pouviez", "pouvaient"]),
    "vouloir": ("3e groupe", "avoir", "voulu", "voulant", ["veux", "veux", "veut", "voulons", "voulez", "veulent"], ["voulais", "voulais", "voulait", "voulions", "vouliez", "voulaient"]),
    "devoir": ("3e groupe", "avoir", "dû", "devant", ["dois", "dois", "doit", "devons", "devez", "doivent"], ["devais", "devais", "devait", "devions", "deviez", "devaient"]),
    "savoir": ("3e groupe", "avoir", "su", "sachant", ["sais", "sais", "sait", "savons", "savez", "savent"], ["savais", "savais", "savait", "savions", "saviez", "savaient"]),
    "prendre": ("3e groupe", "avoir", "pris", "prenant", ["prends", "prends", "prend", "prenons", "prenez", "prennent"], ["prenais", "prenais", "prenait", "prenions", "preniez", "prenaient"]),
    "comprendre": ("3e groupe", "avoir", "compris", "comprenant", ["comprends", "comprends", "comprend", "comprenons", "comprenez", "comprennent"], ["comprenais", "comprenais", "comprenait", "comprenions", "compreniez", "comprenaient"]),
    "apprendre": ("3e groupe", "avoir", "appris", "apprenant", ["apprends", "apprends", "apprend", "apprenons", "apprenez", "apprennent"], ["apprenais", "apprenais", "apprenait", "apprenions", "appreniez", "apprenaient"]),
    "mettre": ("3e groupe", "avoir", "mis", "mettant", ["mets", "mets", "met", "mettons", "mettez", "mettent"], ["mettais", "mettais", "mettait", "mettions", "mettiez", "mettaient"]),
    "dire": ("3e groupe", "avoir", "dit", "disant", ["dis", "dis", "dit", "disons", "dites", "disent"], ["disais", "disais", "disait", "disions", "disiez", "disaient"]),
    "lire": ("3e groupe", "avoir", "lu", "lisant", ["lis", "lis", "lit", "lisons", "lisez", "lisent"], ["lisais", "lisais", "lisait", "lisions", "lisiez", "lisaient"]),
    "écrire": ("3e groupe", "avoir", "écrit", "écrivant", ["écris", "écris", "écrit", "écrivons", "écrivez", "écrivent"], ["écrivais", "écrivais", "écrivait", "écrivions", "écriviez", "écrivaient"]),
    "voir": ("3e groupe", "avoir", "vu", "voyant", ["vois", "vois", "voit", "voyons", "voyez", "voient"], ["voyais", "voyais", "voyait", "voyions", "voyiez", "voyaient"]),
    "venir": ("3e groupe", "être", "venu", "venant", ["viens", "viens", "vient", "venons", "venez", "viennent"], ["venais", "venais", "venait", "venions", "veniez", "venaient"]),
    "tenir": ("3e groupe", "avoir", "tenu", "tenant", ["tiens", "tiens", "tient", "tenons", "tenez", "tiennent"], ["tenais", "tenais", "tenait", "tenions", "teniez", "tenaient"]),
    "partir": ("3e groupe", "être", "parti", "partant", ["pars", "pars", "part", "partons", "partez", "partent"], ["partais", "partais", "partait", "partions", "partiez", "partaient"]),
    "sortir": ("3e groupe", "être", "sorti", "sortant", ["sors", "sors", "sort", "sortons", "sortez", "sortent"], ["sortais", "sortais", "sortait", "sortions", "sortiez", "sortaient"]),
    "dormir": ("3e groupe", "avoir", "dormi", "dormant", ["dors", "dors", "dort", "dormons", "dormez", "dorment"], ["dormais", "dormais", "dormait", "dormions", "dormiez", "dormaient"]),
    "vivre": ("3e groupe", "avoir", "vécu", "vivant", ["vis", "vis", "vit", "vivons", "vivez", "vivent"], ["vivais", "vivais", "vivait", "vivions", "viviez", "vivaient"]),
    "ouvrir": ("3e groupe", "avoir", "ouvert", "ouvrant", ["ouvre", "ouvres", "ouvre", "ouvrons", "ouvrez", "ouvrent"], ["ouvrais", "ouvrais", "ouvrait", "ouvrions", "ouvriez", "ouvraient"]),
    "offrir": ("3e groupe", "avoir", "offert", "offrant", ["offre", "offres", "offre", "offrons", "offrez", "offrent"], ["offrais", "offrais", "offrait", "offrions", "offriez", "offraient"]),
    "recevoir": ("3e groupe", "avoir", "reçu", "recevant", ["reçois", "reçois", "reçoit", "recevons", "recevez", "reçoivent"], ["recevais", "recevais", "recevait", "recevions", "receviez", "recevaient"]),
    "suivre": ("3e groupe", "avoir", "suivi", "suivant", ["suis", "suis", "suit", "suivons", "suivez", "suivent"], ["suivais", "suivais", "suivait", "suivions", "suiviez", "suivaient"]),
    "connaître": ("3e groupe", "avoir", "connu", "connaissant", ["connais", "connais", "connaît", "connaissons", "connaissez", "connaissent"], ["connaissais", "connaissais", "connaissait", "connaissions", "connaissiez", "connaissaient"]),
    "croire": ("3e groupe", "avoir", "cru", "croyant", ["crois", "crois", "croit", "croyons", "croyez", "croient"], ["croyais", "croyais", "croyait", "croyions", "croyiez", "croyaient"]),
    "naître": ("3e groupe", "être", "né", "naissant", ["nais", "nais", "naît", "naissons", "naissez", "naissent"], ["naissais", "naissais", "naissait", "naissions", "naissiez", "naissaient"]),
    "rire": ("3e groupe", "avoir", "ri", "riant", ["ris", "ris", "rit", "rions", "riez", "rient"], ["riais", "riais", "riait", "riions", "riiez", "riaient"]),
}


def verb_info(word: str) -> dict:
    base = marker_base(word).removeprefix("s'").removeprefix("se ")
    known = IRREGULAR.get(base)
    if known:
        group, auxiliary, past, present_participle, present, imperfect = known
    elif base.endswith("er"):
        stem = base[:-2]
        singular = stem
        if re.search(r"(?:acheter|lever|mener|peser|préférer|espérer|compléter|régler|répéter|geler|employer|nettoyer|essayer|payer|protéger)$", base):
            singular = re.sub(r"e(?=[^e]*$)", "è", stem)
            singular = re.sub(r"y(?=[^e]*$)", "i", singular)
        if base.endswith(("appeler", "jeter")):
            singular = stem + ("l" if base.endswith("eler") else "t")
        present = [singular + "e", singular + "es", singular + "e", stem + ("eons" if stem.endswith("g") else "çons" if stem.endswith("c") else "ons"), stem + "ez", singular + "ent"]
        imperfect = [stem + x for x in ("ais", "ais", "ait", "ions", "iez", "aient")]
        past, present_participle, group = stem + "é", stem + ("eant" if stem.endswith("g") else "ant"), "1er groupe"
        auxiliary = "être" if base.startswith(("s'", "se ")) or base in {"arriver", "partir", "rentrer", "retourner", "revenir", "naître", "mourir", "tomber", "rester", "passer", "sortir", "entrer", "monter", "descendre"} else "avoir"
    elif base.endswith("ir"):
        stem = base[:-2]
        present = [stem + x for x in ("is", "is", "it", "issons", "issez", "issent")]
        imperfect = [stem + "iss" + x for x in ("ais", "ais", "ait", "ions", "iez", "aient")]
        past, present_participle, group, auxiliary = stem + "i", stem + "issant", "2e groupe", "avoir"
    else:
        stem = base[:-2] if base.endswith("re") else base
        present = [stem + x for x in ("s", "s", "", "ons", "ez", "ent")]
        imperfect = [stem + x for x in ("ais", "ais", "ait", "ions", "iez", "aient")]
        past, present_participle, group, auxiliary = (base[:-3] + "u" if base.endswith("endre") else stem + "u"), stem + "ant", "3e groupe", "avoir"
    if word.startswith(("s'", "s’", "se ")):
        pronouns = ("m'", "t'", "s'", "nous ", "vous ", "s'")
        present = [p + v for p, v in zip(pronouns, present)]
        imperfect = [p + v for p, v in zip(pronouns, imperfect)]
        auxiliary = "être"
    compound = [f"{aux} {past}" for aux in ("suis", "es", "est", "sommes", "êtes", "sont")]
    if auxiliary == "être":
        compound = [f"{aux} {past}{'(e)' if i < 3 else '(e)s'}" for i, aux in enumerate(("suis", "es", "est", "sommes", "êtes", "sont"))]
    return {"group": group, "auxiliary": auxiliary, "past_participle": past,
            "present_participle": present_participle, "present": present,
            "imperfect": imperfect, "compound": compound}


def generated_example(word: str, pos: str, gender: str) -> str:
    base = marker_base(word)
    pos_special = POS_SPECIAL_EXAMPLES.get((base.casefold(), pos))
    if pos_special:
        return pos_special[0]
    if base.casefold() in SPECIAL_EXAMPLES:
        return SPECIAL_EXAMPLES[base.casefold()][0]
    if pos == "preposition" and base.casefold() in PREPOSITION_EXAMPLES:
        return PREPOSITION_EXAMPLES[base.casefold()][0]
    special = {
        "être": "Je suis à la maison.", "avoir": "J'ai le temps de répondre.", "aller": "Nous allons au marché demain.",
        "faire": "Nous faisons un exercice ensemble.", "venir": "Elle vient ce soir.", "pouvoir": "Je peux vous aider.",
        "vouloir": "Je veux un café, s'il vous plaît.", "devoir": "Nous devons partir maintenant.", "savoir": "Je sais la réponse.",
        "dire": "Il dit la vérité.", "prendre": "Je prends le train à huit heures.", "voir": "Nous voyons la mer depuis la fenêtre.",
        "mettre": "Elle met son manteau avant de sortir.", "lire": "Je lis un livre dans le train.", "écrire": "Il écrit un message à sa sœur.",
    }
    if pos == "verb":
        return special.get(base, f"Nous allons {base} demain matin.")
    if pos == "noun":
        return f"Nous avons parlé de {base} pendant le dîner."
    if pos == "proper_noun":
        return f"Nous aimerions visiter {base} un jour."
    if pos == "adjective":
        feminine = adjective_forms(word)[0][1]
        return f"Cette idée est {feminine}."
    if pos == "adverb":
        return f"Elle répond {base} à la question."
    if pos == "preposition":
        if base == "avec":
            return "Je travaille avec mes collègues."
        if base == "sans":
            return "Il est parti sans son téléphone."
        return f"Le livre est {base} la table."
    if pos == "determiner":
        return f"{base.capitalize()} livre est sur la table."
    if pos == "pronoun":
        return {"je": "Je travaille aujourd'hui.", "tu": "Tu arrives ce soir.", "moi": "C'est pour moi.", "toi": "Je pense à toi.", "qui": "Qui vient avec nous?", "que": "Je sais que tu as raison."}.get(base, f"Je parle avec {base}.")
    if pos == "conjunction":
        return {"et": "Paul et Marie arrivent ce soir.", "mais": "Je voudrais venir, mais je travaille.", "ou": "Tu préfères le thé ou le café?", "que": "Je pense que tu as raison.", "si": "Si tu veux, nous pouvons partir."}.get(base, f"Je reste ici, {base} tu peux revenir plus tard.")
    if pos == "interjection":
        return f"— {base} ! s'exclame-t-il en souriant."
    return f"Il dit « {base} » en souriant."


def is_good_example(value: str) -> bool:
    return bool(value and 8 <= len(value) <= 220 and not any(marker in value.casefold() for marker in META_MARKERS))


def first_translation(value: str) -> str:
    return value.split(";", 1)[0].strip()


def translated_example(word: str, pos: str, gender: str, english: str, spanish: str, chinese: str) -> tuple[str, str, str]:
    pos_special = POS_SPECIAL_EXAMPLES.get((marker_base(word).casefold(), pos))
    if pos_special:
        return pos_special[1:]
    special = SPECIAL_EXAMPLES.get(marker_base(word).casefold())
    if special:
        return special[1:]
    prep = PREPOSITION_EXAMPLES.get(marker_base(word).casefold()) if pos == "preposition" else None
    if prep:
        return prep[1:]
    en, es, zh = first_translation(english), first_translation(spanish), first_translation(chinese)
    base = marker_base(word).casefold()
    if pos == "verb":
        en = re.sub(r"^to\s+", "", en, flags=re.I)
        es = re.sub(r"^(a|al)\s+", "", es, flags=re.I)
        return f"We are going to {en} tomorrow morning.", f"Mañana por la mañana vamos a {es}.", f"我们明天早上要{zh}。"
    if pos == "noun":
        return f"We talked about {en} at dinner.", f"Hablamos de {es} durante la cena.", f"晚餐时我们谈到了{zh}。"
    if pos == "proper_noun":
        return f"We would like to visit {en} one day.", f"Nos gustaría visitar {es} algún día.", f"我们希望有一天去{zh}。"
    if pos == "adjective":
        return f"This idea is {en}.", f"Esta idea es {es}.", f"这个想法很{zh}。"
    if pos == "adverb":
        return f"She answers {en} to the question.", f"Ella responde {es} a la pregunta.", f"她{zh}回答这个问题。"
    if pos == "preposition":
        if base == "avec":
            return "I work with my colleagues.", "Trabajo con mis colegas.", "我和同事一起工作。"
        if base == "sans":
            return "He left without his phone.", "Se fue sin su teléfono.", "他没带手机就离开了。"
        return f"The book is {en} the table.", f"El libro está {es} la mesa.", f"书在桌子{zh}。"
    if pos == "determiner":
        return "This book is on the table.", "Este libro está sobre la mesa.", "这本书在桌子上。"
    if pos == "pronoun":
        return "It is for me.", "Es para mí.", "这是给我的。"
    if pos == "conjunction":
        return "I would like to come, but I am working.", "Me gustaría venir, pero estoy trabajando.", "我想来，但是我正在工作。"
    if pos == "interjection":
        return f"— {en}! he exclaims with a smile.", f"— ¡{es}! exclama con una sonrisa.", f"——{zh}！他笑着喊道。"
    return f"He says “{en}” with a smile.", f"Dice «{es}» con una sonrisa.", f"他笑着说“{zh}”。"


def load_wiktapi() -> dict[tuple[str, str], list[dict]]:
    result: dict[tuple[str, str], list[dict]] = {}
    folder = ROOT / ".tools/lexicon-source/wiktapi"
    for path in folder.glob("*.json"):
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
            url = payload.get("url", "")
            if not payload.get("data") or "/word/" not in url:
                continue
            word = unquote(url.split("/word/", 1)[1].split("/", 1)[0])
            language = "en" if "/v1/en/" in url else "es" if "/v1/es/" in url else ""
            if language:
                result.setdefault((norm(word), language), []).append(payload["data"])
        except (OSError, json.JSONDecodeError):
            continue
    return result


def load_cfdict(path: Path) -> dict[str, str]:
    result: dict[str, list[str]] = {}
    if not path.exists():
        return {}
    pattern = re.compile(r"^(\S+)\s+(\S+)\s+\[[^]]+\]\s+/(.*)/\s*$")
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if not line or line.startswith("#"):
            continue
        match = pattern.match(line.strip())
        if not match:
            continue
        simplified, french_values = match.group(2), match.group(3).split("/")
        for french in french_values:
            french = re.sub(r"\([^)]*\)", "", french).strip()
            if french:
                result.setdefault(norm(french), []).append(simplified)
    return {key: "; ".join(dict.fromkeys(values[:3])) for key, values in result.items()}


def load_apertium(path: Path) -> dict[str, str]:
    result: dict[str, list[str]] = {}
    if not path.exists():
        return {}
    root = ET.parse(path).getroot()
    for pair in root.findall(".//p"):
        left, right = pair.find("l"), pair.find("r")
        if left is None or right is None:
            continue
        french = "".join(left.itertext()).strip()
        spanish = "".join(right.itertext()).strip()
        if french and spanish:
            result.setdefault(norm(french), []).append(spanish)
    return {key: "; ".join(dict.fromkeys(values[:3])) for key, values in result.items()}


def load_kaikki(path: Path) -> dict[str, str]:
    if not path.exists():
        return {}
    payload = json.loads(path.read_text(encoding="utf-8"))
    result: dict[str, list[str]] = {}
    for word, entries in payload.get("entries", {}).items():
        for entry in entries:
            for sense in entry.get("senses", []):
                for gloss in sense.get("glosses", []):
                    if gloss:
                        result.setdefault(norm(word), []).append(clean(str(gloss)))
    return {key: "; ".join(dict.fromkeys(values[:3])) for key, values in result.items()}


def api_glosses(api_pages: list[dict], pos: str) -> str:
    wanted = {"adjective": {"adjective", "adj"}, "adverb": {"adverb", "adv"}, "preposition": {"preposition", "prep"},
              "conjunction": {"conjunction", "conj"}, "pronoun": {"pronoun", "pron"}, "determiner": {"determiner", "det"},
              "interjection": {"interjection", "interj"}}.get(pos, {pos})
    values: list[str] = []
    fallback: list[str] = []
    for page in api_pages:
        for definition in page.get("definitions", []):
            for sense in definition.get("senses", []):
                for gloss in sense.get("glosses", []):
                    value = clean(str(gloss))
                    if value and value not in fallback:
                        fallback.append(value)
                    if definition.get("pos") in wanted and value and value not in values:
                        values.append(value)
    return "; ".join((values or fallback)[:3])


def request_translation(text: str, target: str) -> str:
    return request_translations([text], target)[0]


def request_translations(texts: list[str], target: str) -> list[str]:
    target_code = "zh" if target == "zh-CN" else target
    query = "\n".join(texts)
    url = "https://lingva.ml/api/v1/fr/" + quote(target_code, safe="") + "/" + quote(query, safe="")
    request = Request(url, headers={"User-Agent": "Codex-language-content/1.0"})
    with urlopen(request, timeout=30) as response:
        payload = json.load(response)
    value = payload.get("translation", "") if isinstance(payload, dict) else ""
    values = [re.sub(r"\s+", " ", line).strip() for line in str(value).splitlines()]
    return values if len(values) == len(texts) else [str(value).strip()] if len(texts) == 1 else []


def request_google_translation(text: str, target: str) -> str:
    """Small per-item fallback for the rare empty responses from Lingva."""
    url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=fr&tl=" + quote(target, safe="") + "&dt=t&q=" + quote(text, safe="")
    request = Request(url, headers={"User-Agent": "Codex-language-content/1.0"})
    with urlopen(request, timeout=30) as response:
        payload = json.load(response)
    segments = payload[0] if isinstance(payload, list) and payload and isinstance(payload[0], list) else []
    return " ".join(str(segment[0]) for segment in segments if isinstance(segment, list) and segment and segment[0]).strip()


def request_google_translations(texts: list[str], target: str) -> list[str]:
    values: list[str] = []
    for text in texts:
        value = ""
        for attempt in range(3):
            try:
                value = request_google_translation(text, target)
                if value:
                    break
            except Exception:
                if attempt < 2:
                    time.sleep(attempt + 1)
        values.append(value)
    return values


def translate_missing(tasks: set[tuple[str, str]], cache: dict[str, str], allow_network: bool, cache_path: Path) -> None:
    pending = [task for task in sorted(tasks) if f"{task[0]}|{task[1]}" not in cache]
    if pending and not allow_network:
        raise RuntimeError(f"{len(pending)} translations are missing; rerun with --allow-network")
    grouped: dict[str, list[str]] = {}
    for target, text in pending:
        grouped.setdefault(target, []).append(text)
    batches = [(target, texts[index:index + 20]) for target, texts in grouped.items() for index in range(0, len(texts), 20)]
    def one(batch: tuple[str, list[str]]) -> list[tuple[str, str]]:
        target, texts = batch
        values: list[str] = []
        for attempt in range(5):
            try:
                candidate = request_translations(texts, target)
                if len(candidate) == len(texts):
                    values = candidate
                    break
            except HTTPError as error:
                if error.code == 429:
                    time.sleep(8 * (attempt + 1))
                elif attempt == 4:
                    break
            except Exception:
                if attempt == 4:
                    break
                time.sleep(2 * (attempt + 1))
        if len(values) != len(texts) or any(not value for value in values):
            fallback = request_google_translations(texts, target)
            values = [value or fallback_value for value, fallback_value in zip(values or [""] * len(texts), fallback)]
        return [(f"{target}|{text}", value) for text, value in zip(texts, values)]
    # Lingva is a public instance; keep concurrency bounded while allowing a
    # large CEFR snapshot to finish in a reasonable time.
    with ThreadPoolExecutor(max_workers=8) as pool:
        futures = [pool.submit(one, batch) for batch in batches]
        completed = 0
        for future in as_completed(futures):
            results = future.result()
            for key, value in results:
                if value:
                    cache[key] = value
            completed += len(results)
            if completed % 100 < 20 or completed == len(pending):
                cache_path.parent.mkdir(parents=True, exist_ok=True)
                cache_path.write_text(json.dumps(cache, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
                print(f"translations {completed}/{len(pending)}", flush=True)


def existing_entries(db: sqlite3.Connection) -> tuple[dict[tuple[str, str], tuple[str, dict]], int]:
    result: dict[tuple[str, str], tuple[str, dict]] = {}
    rows = db.execute("SELECT lexeme_uid,lemma,part_of_speech,level,gender,sort_order,provenance_json FROM lexeme ORDER BY homonym").fetchall()
    for uid, lemma, pos, level, gender, sort_order, provenance in rows:
        result.setdefault((norm(lemma), pos), (uid, {"lemma": lemma, "pos": pos, "level": level, "gender": gender, "sort_order": sort_order, "provenance": provenance}))
    return result, max((row[5] for row in rows), default=-1)


def load_existing_json() -> dict[tuple[str, str], dict]:
    result = {}
    for path in (ROOT / "data/curated/french_a1.json", ROOT / "data/curated/french_a2.json"):
        if not path.exists():
            continue
        try:
            for word in json.loads(path.read_text(encoding="utf-8")).get("words", []):
                result.setdefault((norm(word["lemma"]), word["pos"]), word)
        except (OSError, json.JSONDecodeError):
            continue
    return result


def make_word(row: dict, page: dict | None, api: dict, cfdict: dict[str, str], apertium: dict[str, str], kaikki: dict[str, str], old: dict | None, translations_cache: dict[str, str], tasks: set[tuple[str, str]]) -> dict:
    word = row["word"].strip()
    pos = POS_MAP.get(row["pos"], row["pos"])
    block = french_block(page.get("wikitext", "")) if page and page.get("wikitext") else ""
    old_sense = (old or {}).get("senses", [{}])[0]
    english = translations(block, ("en",)) or api_glosses(api.get((norm(word), "en"), []), pos) or kaikki.get(norm(word)) or old_sense.get("english", "")
    spanish = translations(block, ("es",)) or api_glosses(api.get((norm(word), "es"), []), pos) or apertium.get(norm(word)) or old_sense.get("spanish", "")
    chinese = translations(block, ("zh", "cmn")) or cfdict.get(norm(word)) or cfdict.get(norm(marker_base(word))) or old_sense.get("chinese", "")
    gender = gender_from(word, block) if pos == "noun" else ""
    if pos == "noun" and old and old.get("gender"):
        gender = gender or old["gender"]
    ipa = ipa_from(block) or (old or {}).get("ipa", "")
    old_example = (old or {}).get("examples", [{}])[0]
    old_senses = (old or {}).get("senses", [])
    old_examples = (old or {}).get("examples", [])
    # Preserve additional aligned meanings from the hand-reviewed source. The
    # first meaning still follows the current FLELex refresh and override path.
    additional_senses = [dict(value) for value in old_senses[1:]] if len(old_senses) == len(old_examples) else []
    additional_examples = [dict(value) for value in old_examples[1:]] if additional_senses else []
    special_key = (marker_base(word).casefold(), pos)
    forced_example = special_key in POS_SPECIAL_EXAMPLES or marker_base(word).casefold() in SPECIAL_EXAMPLES or (pos == "preposition" and marker_base(word).casefold() in PREPOSITION_EXAMPLES)
    french_example = old_example.get("french", "") if not forced_example and is_good_example(old_example.get("french", "")) else ""
    example_generated = forced_example or not bool(french_example)
    if not is_good_example(french_example):
        french_example = generated_example(word, pos, gender)
    example_en = old_example.get("english", "") if french_example == old_example.get("french") else ""
    example_es = old_example.get("spanish", "") if french_example == old_example.get("french") else ""
    example_zh = old_example.get("chinese", "") if french_example == old_example.get("french") else ""
    if not example_en or not example_es or not example_zh:
        example_en, example_es, example_zh = translated_example(word, pos, gender, english, spanish, chinese)
    for target, text, current in (("en", word, english), ("es", word, spanish), ("zh-CN", word, chinese)):
        if not current:
            tasks.add((target, text))
    if not ipa:
        # Keep the same safe fallback as the existing content pipeline when a
        # dictionary page has no pronunciation. The app can still use fr-FR TTS.
        ipa = f"/{marker_base(word)}/"
    forms: list[list[str]] = []
    verb = None
    if pos == "noun":
        base = marker_base(word)
        article = "une" if gender == "f." else "un"
        forms = [["singulier", f"{article} {base}"], ["pluriel", f"des {plural(base)}"]]
    elif pos == "adjective":
        forms = adjective_forms(word)
    elif pos == "verb":
        verb = verb_info(word)
    else:
        forms = [["forme", "invariable"]]
    return {"word": word, "pos": pos, "ipa": ipa, "gender": gender, "english": english,
            "spanish": spanish, "chinese": chinese, "french_example": french_example,
            "example_en": example_en, "example_es": example_es, "example_zh": example_zh,
            "forms": forms, "verb": verb, "example_generated": example_generated, "row_numbers": row["row_numbers"],
            "additional_senses": additional_senses, "additional_examples": additional_examples}


def finalize_word(item: dict, uid: str, level: str = "A1") -> dict:
    values = item
    english = values["english"] or ""
    spanish = values["spanish"] or ""
    chinese = values["chinese"] or ""
    example_en = values["example_en"] or ""
    example_es = values["example_es"] or ""
    example_zh = values["example_zh"] or ""
    senses = [{"english": english, "spanish": spanish, "chinese": chinese}] + values.get("additional_senses", [])
    examples = [{"french": values["french_example"], "english": example_en, "spanish": example_es, "chinese": example_zh}] + values.get("additional_examples", [])
    return {"uid": uid, "lemma": values["word"], "pos": values["pos"], "level": level, "ipa": values["ipa"],
            "gender": values["gender"], "senses": senses,
            "examples": examples,
            "forms": values["forms"], "verb": values["verb"], "source_entries": [{"number": n, "page": 1, "display": values["word"]} for n in values["row_numbers"]]}


def key_forms(word: dict) -> list[list[str]]:
    if word["pos"] != "verb":
        return word["forms"]
    verb = word["verb"]
    values = []
    for i in (0, 3, 5):
        pronoun, form = PERSONS[i], verb["present"][i]
        values.append(["présent · " + ("j’" if i == 0 and form[0].lower() in VOWELS else pronoun), form])
    values += [["participe passé", verb["past_participle"]], ["participe présent", verb["present_participle"]], ["auxiliaire", verb["auxiliary"]]]
    return values


def insert_word(db: sqlite3.Connection, word: dict, uid: str, sort_order: int, references: list[str], level: str) -> None:
    existing_senses = [row[0] for row in db.execute("SELECT sense_id FROM sense WHERE lexeme_uid=?", (uid,)).fetchall()]
    if existing_senses:
        db.executemany("DELETE FROM example WHERE sense_id=?", [(sense_id,) for sense_id in existing_senses])
    db.execute("DELETE FROM conjugation_form WHERE lexeme_uid=?", (uid,))
    db.execute("DELETE FROM verb_info WHERE lexeme_uid=?", (uid,))
    db.execute("DELETE FROM word_form WHERE lexeme_uid=?", (uid,))
    db.execute("DELETE FROM sense WHERE lexeme_uid=?", (uid,))
    db.execute("DELETE FROM pronunciation WHERE lexeme_uid=?", (uid,))
    provenance = json.dumps({"authorship": f"FLELex-{level}-ingest", "references": references, "source_entries": word["source_entries"]}, ensure_ascii=False, sort_keys=True)
    db.execute("UPDATE lexeme SET language='fr-FR',lemma=?,part_of_speech=?,homonym=?,level=?,gender=?,provenance_json=? WHERE lexeme_uid=?",
               (word["lemma"], word["pos"], int(uid.rsplit(":", 1)[1]), level, word["gender"], provenance, uid))
    if db.execute("SELECT changes()").fetchone()[0] == 0:
        db.execute("INSERT INTO lexeme VALUES (?,?,?,?,?,?,?,?,?)", (uid, "fr-FR", word["lemma"], word["pos"], int(uid.rsplit(":", 1)[1]), level, word["gender"], sort_order, provenance))
    db.execute("INSERT INTO pronunciation VALUES (?,?,?,?)", (uid, "fr-FR", word["ipa"], None))
    examples_by_sense: dict[int, list[dict]] = {}
    for position, example in enumerate(word.get("examples", [])):
        examples_by_sense.setdefault(example.get("sense_index", position), []).append(example)
    for sense_order, sense in enumerate(word["senses"]):
        sense_id = f"{uid}:sense:{sense_order + 1}"
        db.execute("INSERT INTO sense VALUES (?,?,?,?,?,?,?)", (
            sense_id, uid, sense_order, sense["english"], sense["spanish"], sense["chinese"], 1
        ))
        for example_order, example in enumerate(examples_by_sense.get(sense_order, [])):
            db.execute("INSERT INTO example VALUES (?,?,?,?,?,?,?)", (
                f"{sense_id}:example:{example_order + 1}", sense_id, example_order,
                example["french"], example["english"], example["spanish"], example["chinese"]
            ))
    for index, (label, value) in enumerate(key_forms(word)):
        db.execute("INSERT INTO word_form VALUES (?,?,?,?)", (uid, index, label, value))
    if word["pos"] == "verb":
        verb = word["verb"]
        db.execute("INSERT INTO verb_info VALUES (?,?,?,?,?)", (uid, verb["group"], verb["auxiliary"], verb["past_participle"], verb["present_participle"]))
        compound = verb["compound"]
        for tense_order, (tense, values) in enumerate((("présent", verb["present"]), ("passé composé", compound), ("imparfait", verb["imperfect"]))):
            for person, value in enumerate(values, start=1):
                pronoun = PERSONS[person - 1]
                if person == 1 and value[0].lower() in VOWELS:
                    pronoun = "j’"
                db.execute("INSERT INTO conjugation_form VALUES (?,?,?,?,?,?,?)", (uid, "indicatif", tense, tense_order, person, pronoun, value))


def ensure_level_supported(db: sqlite3.Connection, level: str) -> None:
    sql = db.execute("SELECT sql FROM sqlite_master WHERE type='table' AND name='lexeme'").fetchone()[0]
    if f"'{level}'" in sql:
        return
    if level not in {"B1", "B2", "C1", "C2"}:
        raise RuntimeError(f"Bundled lexeme schema does not support requested level {level}")
    updated_sql = sql.replace("CREATE TABLE lexeme", "CREATE TABLE lexeme_new", 1).replace("CREATE TABLE \"lexeme\"", "CREATE TABLE \"lexeme_new\"", 1)
    updated_sql = re.sub(r"CHECK \(level IN \([^)]*\)\)", "CHECK (level IN ('A1','A2','B1','B2','C1','C2'))", updated_sql, count=1)
    db.execute("PRAGMA foreign_keys=OFF")
    db.execute(updated_sql)
    db.execute("INSERT INTO lexeme_new SELECT * FROM lexeme")
    db.execute("DROP TABLE lexeme")
    db.execute("ALTER TABLE lexeme_new RENAME TO lexeme")
    db.execute("PRAGMA foreign_keys=ON")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--csv", type=Path, default=DEFAULT_CSV)
    parser.add_argument("--raw", type=Path, default=DEFAULT_RAW)
    parser.add_argument("--cfdict", type=Path, default=DEFAULT_CFDICT)
    parser.add_argument("--apertium", type=Path, default=ROOT / "data/raw/open_lexicon/apertium-fra-spa.fra-spa.dix")
    parser.add_argument("--kaikki", type=Path, default=ROOT / "data/raw/open_lexicon/kaikki_a1_snapshot.json")
    parser.add_argument("--db", type=Path, default=DEFAULT_DB)
    parser.add_argument("--source-output", type=Path, default=DEFAULT_SOURCE)
    parser.add_argument("--review-registry", type=Path)
    parser.add_argument("--cache", type=Path, default=DEFAULT_CACHE)
    parser.add_argument("--book-id", default="flelex-a1")
    parser.add_argument("--book-title", default="FLELex_A1全部词汇")
    parser.add_argument("--level", choices=("A1", "A2", "B1", "B2", "C1", "C2"), default="A1")
    parser.add_argument("--duplicate-policy", choices=("overwrite", "skip"), default="overwrite")
    parser.add_argument("--content-version", default="2026.09.10-flelex-a1.1")
    parser.add_argument("--source-meta-key", default="flelex_a1_source_sha256")
    parser.add_argument("--allow-network", action="store_true")
    args = parser.parse_args()
    review_registry = args.review_registry
    if review_registry is None and args.level == "A1" and DEFAULT_REVIEW_REGISTRY.exists():
        review_registry = DEFAULT_REVIEW_REGISTRY

    with args.csv.open(encoding="utf-8-sig", newline="") as stream:
        grouped: dict[tuple[str, str], dict] = {}
        for row_number, row in enumerate(csv.DictReader(stream), start=1):
            word = unicodedata.normalize("NFC", row["word"].strip())
            pos = POS_MAP.get(row["pos"])
            if not word or not pos:
                continue
            grouped.setdefault((norm(word), pos), {"word": word, "pos": pos, "row_numbers": []})["row_numbers"].append(row_number)
    raw = json.loads(args.raw.read_text(encoding="utf-8")) if args.raw.exists() else {}
    api = load_wiktapi()
    cfdict = load_cfdict(args.cfdict)
    apertium = load_apertium(args.apertium)
    kaikki = load_kaikki(args.kaikki)
    old_json = load_existing_json()
    cache: dict[str, str] = {}
    if args.cache.exists():
        cache.update(json.loads(args.cache.read_text(encoding="utf-8")))
    old_cache = ROOT / "tmp/a2-content/translation_cache.json"
    if old_cache.exists():
        for key, value in json.loads(old_cache.read_text(encoding="utf-8")).items():
            cache.setdefault(key, value)
    with sqlite3.connect(args.db) as base_db:
        existing, max_sort = existing_entries(base_db)
        old_by_key = old_json
    base_db.close()
    items: list[dict] = []
    tasks: set[tuple[str, str]] = set()
    for key, row in grouped.items():
        page = raw.get(row["word"]) or raw.get(row["word"].replace("’", "'"))
        item = make_word(row, page, api, cfdict, apertium, kaikki, old_by_key.get(key), cache, tasks)
        items.append(item)
    for item in items:
        fallback = fallback_translation(item["word"])
        if fallback:
            for target, value in zip(("en", "es", "zh-CN"), fallback):
                if value and not item[{"en": "english", "es": "spanish", "zh-CN": "chinese"}[target]]:
                    cache.setdefault(f"{target}|{item['word']}", value)
    print(json.dumps({"unique_flelex_entries": len(items), "translation_tasks": len(tasks), "tasks_by_target": Counter(target for target, _ in tasks)}, ensure_ascii=False))
    translate_missing(tasks, cache, args.allow_network, args.cache)
    args.cache.parent.mkdir(parents=True, exist_ok=True)
    args.cache.write_text(json.dumps(cache, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for item in items:
        item["english"] = item["english"] or cache.get(f"en|{item['word']}", "")
        item["spanish"] = item["spanish"] or cache.get(f"es|{item['word']}", "")
        item["chinese"] = item["chinese"] or cache.get(f"zh-CN|{item['word']}", "")
        item["chinese"] = CHINESE_TRANSLATION_CORRECTIONS.get(norm(item["word"]), item["chinese"])
        forced_example = fallback_example(item["word"])
        pos_special = POS_SPECIAL_EXAMPLES.get((marker_base(item["word"]).casefold(), item["pos"]))
        if forced_example:
            item["french_example"], item["example_en"], item["example_es"], item["example_zh"] = forced_example
        elif pos_special:
            item["french_example"], item["example_en"], item["example_es"], item["example_zh"] = pos_special
        if item["example_generated"] and not forced_example and not pos_special:
            item["example_en"], item["example_es"], item["example_zh"] = translated_example(
                item["word"], item["pos"], item["gender"], item["english"], item["spanish"], item["chinese"]
            )
        item["example_en"] = item["example_en"] or cache.get(f"en|{item['french_example']}", "")
        item["example_es"] = item["example_es"] or cache.get(f"es|{item['french_example']}", "")
        item["example_zh"] = item["example_zh"] or cache.get(f"zh-CN|{item['french_example']}", "")
    missing_fields = [(item["word"], key) for item in items for key in ("english", "spanish", "chinese", "example_en", "example_es", "example_zh") if not item[key]]
    if missing_fields:
        raise RuntimeError(f"Translations remain missing for {len(missing_fields)} fields: {missing_fields[:40]}")

    prepared: list[tuple[dict, str]] = []
    for item in items:
        match = existing.get((norm(item["word"]), item["pos"]))
        if match:
            uid = match[0]
        else:
            safe = norm(item["word"]).replace(":", "·")
            uid = f"fr:{safe}:{item['pos']}:1"
        prepared.append((finalize_word(item, uid, args.level), uid))
    prepared.sort(key=lambda pair: pair[0]["source_entries"][0]["number"])
    existing_uids = {match[0] for match in existing.values()}
    source_words = [word for word, _ in prepared]
    review_report = None
    if review_registry is not None:
        source_words, review_report = apply_review_registry(
            source_words, review_registry, require_original_hash=False
        )
        prepared = [(word, word["uid"]) for word in source_words]
    source_doc = {"content_version": review_report["content_version"] if review_report else args.content_version, "language": "fr-FR",
                  "scope": f"FLELex {args.level} {len(grouped)} 个唯一词形/词性词条；来源文件 {len(sum((x['source_entries'] for x in source_words), []))} 行。",
                  "review_date": review_report["review_date"] if review_report else "2026-09-11", "words": source_words}
    args.source_output.parent.mkdir(parents=True, exist_ok=True)
    args.source_output.write_text(json.dumps(source_doc, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if review_report:
        review_report["formal_source_sha256"] = sha256(args.source_output.read_bytes()).hexdigest()

    staging = args.db.with_suffix(args.db.suffix + ".flelex-building")
    shutil.copyfile(args.db, staging)
    try:
        with sqlite3.connect(staging) as db:
            db.execute("PRAGMA foreign_keys=ON")
            ensure_level_supported(db, args.level)
            db.execute("DELETE FROM book_lexeme WHERE book_id=?", (args.book_id,))
            db.execute("DELETE FROM vocabulary_book WHERE book_id=?", (args.book_id,))
            for index, (word, uid) in enumerate(prepared, start=0):
                if args.duplicate_policy == "skip" and uid in existing_uids:
                    continue
                insert_word(db, word, uid, max_sort + index + 1, [args.book_id, "wiktionary-fr", "cfdict-fr-zh"], args.level)
            book_order = db.execute("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM vocabulary_book").fetchone()[0]
            db.execute("INSERT INTO vocabulary_book VALUES (?,?,?,?)", (args.book_id, args.book_title, f"FLELex {args.level} 全部 {len(prepared)} 个词条。", book_order))
            for index, (_, uid) in enumerate(prepared):
                db.execute("INSERT INTO book_lexeme VALUES (?,?,?)", (args.book_id, uid, index))
            provenance = {
                "authorship": f"FLELex {args.level} 词表由用户提供；法语词条信息优先取法语 Wiktionary 结构化数据，缺失的英/西/中释义由本地词典或 Lingva 翻译接口补全。",
                "duplicate_policy": args.duplicate_policy,
                "review_date": "2026-09-11",
                "review_status": "machine-assisted; pending professional human editorial review",
                "sources": {
                    f"flelex-{args.level.casefold()}-csv": {"artifact": f"outputs/flelex-cefr-sorted/flelex_{args.level}.csv", "checked": f"FLELex {args.level} source rows and CEFR/POS metadata"},
                    "wiktionary-fr": {"checked": "French POS, IPA, gender, translations and available French examples", "url": "https://fr.wiktionary.org/"},
                    "cfdict-fr-zh": {"artifact": "data/raw/open_lexicon/cfdict.u8", "checked": "French-to-Chinese lexical equivalents", "license": "CC BY-SA 3.0", "url": "https://chine.in/cfdict.php"},
                    "apertium-fra-spa": {"artifact": "data/raw/open_lexicon/apertium-fra-spa.fra-spa.dix", "checked": "French-to-Spanish lexical equivalents", "license": "GPL-2.0", "url": "https://github.com/apertium/apertium-fra-spa"},
                    "kaikki-enwiktionary": {"artifact": "data/raw/open_lexicon/kaikki_a1_snapshot.json", "checked": "English glosses, IPA and inflection facts", "license": "CC BY-SA", "url": "https://kaikki.org/dictionary/French/index.html"},
                    "wiktapi-eswiktionary": {"artifact": "data/raw/open_lexicon/wiktapi_a1_snapshot.json", "checked": "Spanish glosses", "license": "CC BY-SA", "url": "https://wiktapi.dev/quickstart"},
                    "lingva-translate": {"checked": "Batch fallback for missing English, Spanish and Chinese glosses", "url": "https://github.com/cysr214/lingva-translate/blob/main/README.md"},
                    "google-translate-fallback": {"checked": "Per-item fallback for rare empty Lingva responses", "url": "https://translate.google.com/"},
                    "editorial-examples": {"checked": "Context-appropriate examples generated by POS-specific templates when no suitable retained example was available; translations are aligned French/English/Spanish/Chinese sentences"},
                },
            }
            if review_report:
                provenance["review_date"] = review_report["review_date"]
                provenance["review_status"] = "F4b batch reviewed; remaining FLELex entries pending human editorial review"
                provenance["sources"]["human-content-review"] = {
                    "artifact": review_report["registry"],
                    "sha256": review_report["registry_sha256"],
                    "checked": f"{review_report['reviewed_words']} words, {review_report['reviewed_senses']} senses and {review_report['reviewed_examples']} aligned examples",
                }
            meta = {
                "content_version": source_doc["content_version"],
                "scope": source_doc["scope"],
                args.source_meta_key: sha256(args.csv.read_bytes()).hexdigest(),
                "provenance_json": json.dumps(provenance, ensure_ascii=False),
            }
            if review_report:
                meta["flelex_a1_f4b_review"] = json.dumps(review_report, ensure_ascii=False, sort_keys=True)
            db.executemany("INSERT OR REPLACE INTO content_meta(key,value) VALUES (?,?)", meta.items())
            fk_errors = db.execute("PRAGMA foreign_key_check").fetchall()
            if fk_errors:
                raise RuntimeError(f"SQLite foreign_key_check failed: {fk_errors[:3]}")
            if db.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
                raise RuntimeError("SQLite integrity_check failed")
            if db.execute("SELECT COUNT(*) FROM book_lexeme WHERE book_id=?", (args.book_id,)).fetchone()[0] != len(prepared):
                raise RuntimeError("FLELex book membership count mismatch")
            if db.execute("SELECT COUNT(*) FROM lexeme l LEFT JOIN pronunciation p USING(lexeme_uid) WHERE p.lexeme_uid IS NULL").fetchone()[0]:
                raise RuntimeError("A lexeme lacks pronunciation")
            db.commit()
            db.execute("VACUUM")
        db.close()
        staging.replace(args.db)
    finally:
        staging.unlink(missing_ok=True)
    print(json.dumps({"database": str(args.db), "flelex_entries": len(prepared), "source": str(args.source_output)}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
