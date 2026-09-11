# 法语开发词库独立内容验收

审查日期：2026-09-08。审查者：backend agent，独立于内容生成 agent。本次只读检查内容源、provenance、构建规则及生成 SQLite；未修改词库。属于独立 LLM 编辑审查，不是专业人工或母语审校认证。

## 结论

**40/40 词条已逐词覆盖，开发版内容验收通过；未发现必须阻断本开发版的法语、EN/ES 释义或变位错误。** 全部 47 个核心义项及各自例句/双语译文已审读，10 个动词的 180 个生成变位已检查。40 词仍是精选开发内容，不能标为完整教材或已认证 A0–A1 词库。无真人音频；资产未伪造音频路径。

两项非阻断建议：补充 aimer 的常见读音变体与可追踪来源；把教授词条已有的 CNRTL 音标来源加入该词 references。另需更新 provenance/README 中仍写着“独立验收待完成”的状态，使其指向本报告。

## 本次审查对象与可重复证据

| 对象 | SHA-256 |
|---|---|
| data/curated/french_dev.json | `c5e71c1462f2b87c1fdf67141ac4e637bf5510473d603e8b179f6d40471cc3de` |
| data/curated/provenance.json | `4d4f10789af4e07826b831af7eb8780449ee0da34702ba47bf1e5ab1627cd02d` |
| app/src/main/assets/french_content.db | `e9d6b919550684cd9853d16a13ade7d66fb5745131dc76eb2ca603931694865a` |

数据库用 Python 标准库 SQLite 的 `mode=ro` 打开，临时存储设 MEMORY。实测 `integrity_check=ok`、`foreign_key_check=[]`，内容元数据 source_sha256 与 JSON 文件一致。逐 UID 对比词头、词性、性别、IPA、EN/ES 义项与三语例句，没有源与数据库差异。

| 表 | 实测数量 |
|---|---:|
| lexeme | 40 |
| sense / example | 47 / 47 |
| word_form | 125 |
| verb_info / conjugation_form | 10 / 180 |
| pronunciation | 40 |
| vocabulary_book / book_lexeme | 2 / 50 |

## 逐词覆盖记录

表中“通过”表示已审读 IPA、全部 EN/ES 核心义项、对应法语例句及译文、已收录词形；动词另含三时态六人称及两分词和助动词。不是说每个字段均单独查过外部词典。常见语言事实由独立 judge 审读；疑难和易错项另用下文一手来源核对。

| # | 词条 | 结果与检查重点 |
|---:|---|---|
| 1 | être | 通过；être 用 avoir 构成复合过去时；été / étant、sommes / êtes、étions / étiez 正确。 |
| 2 | avoir | 通过；拥有义 tener 与助动词义 haber 对应；eu / ayant、j’ai eu 正确。英语一般过去时与西语复合过去时均可表达例句的完成事件。 |
| 3 | aller | 通过；être 助动词、不规则现在时、allé / allant 正确；vous 敬称单数和 on 的实际性数配合另有说明。 |
| 4 | faire | 通过；do / make 两义分别有例句；fait / faisant、faites、faisions / faisiez 正确。 |
| 5 | parler | 通过；speak / talk 与 hablar 对应；例句口语疑问自然，规则 -er 变位正确。 |
| 6 | aimer | 通过；like 对应 gustar、love 对应 amar / querer；当前 /ɛ.me/ 有词典依据，另有常见 /e.me/，见建议 C1。 |
| 7 | habiter | 通过；live in a place 对应 vivir / residir；h muet 的 j’habite、habitions / habitiez 正确。小城 town / ciudad pequeña 译法可接受。 |
| 8 | travailler | 通过；/tʁa.va.je/ 的 /j/ 正确；travaillions / travailliez 双 i 正确。 |
| 9 | manger | 通过；mangeons / mangeais 保留 e，mangions / mangiez 不加 e；mangeant 正确。 |
| 10 | prendre | 通过；take 与用餐/饮用 have 两义区分；咖啡邀约译为 Would you like… / ¿Quiere…? 自然；prennent / pris 正确。 |
| 11 | maison | 通过；阴性 maison / maisons；house / home 与 casa 及例句一致。 |
| 12 | livre | 通过；当前只收阳性“书”；未混入阴性“磅”义；livres 正确。 |
| 13 | école | 通过；阴性、l’école 省音、écoles；school / escuela 一致。 |
| 14 | ami | 通过；ami / amie、amis / amies；Mon amie 正确展示元音前 mon，译文指女性朋友。 |
| 15 | famille | 通过；阴性，/fa.mij/ 的 /j/ 正确，familles；family / familia 一致。 |
| 16 | café | 通过；饮料和场所两义各有例句；café (bebida) 与 cafetería 区分明确。 |
| 17 | eau | 通过；法语阴性，不受西语 el agua 的冠词表面形式影响；eaux 复数有矿泉水语境说明。 |
| 18 | pain | 通过；阳性；du pain 与 un pain / des pains 的物质名词与整条面包差别有说明。 |
| 19 | ville | 通过；阴性 villes；town / city 与 ciudad，在例句中对应明确。 |
| 20 | pays | 通过；/pe.i/；阳性，单复数词形同为 pays；country / país 一致。 |
| 21 | jour | 通过；阳性 jours；cinq jours par semaine 三语自然。 |
| 22 | femme | 通过；/fam/ 特殊读音正确；woman / mujer 与 wife / esposa 分义。Sa femme 的 His wife 是允许的具体语境解读，不能推广为 sa 只表示 his。 |
| 23 | homme | 通过；阳性 /ɔm/；cet homme、l’homme 的 h muet 说明正确。 |
| 24 | enfant | 通过；可用 un enfant / une enfant；复数 enfants；child / niño / niña 对应。 |
| 25 | professeur | 通过；/pʁɔ.fɛ.sœʁ/ 有 TLFi 依据；professeure 和 une professeur 均可用，见建议 C2。 |
| 26 | bon | 通过；bonne / bons / bonnes；面包味道语境用西语 está bueno 自然。 |
| 27 | petit | 通过；/pə.ti/；petite / petits / petites；small / pequeño 一致。 |
| 28 | grand | 通过；big / large 与 tall 人高两义各有例句；grande / grands / grandes。 |
| 29 | français | 通过；形容词小写；française / français / françaises；语言名 le français 被明确说明未另计词条。 |
| 30 | content | 通过；happy / pleased 与 contento；contente / contents / contentes；être content de 例句自然。 |
| 31 | difficile | 通过；阴性词形不变、复数 difficiles；difficult / difícil 对应。 |
| 32 | facile | 通过；阴性词形不变、复数 faciles；easy / fácil 对应。 |
| 33 | jeune | 通过；/ʒœn/；阴性不变、复数 jeunes；young / joven 对应。 |
| 34 | bonjour | 通过；白天问候覆盖 hello / good morning / good afternoon，与西语 hola / buenos días / buenas tardes 相容。 |
| 35 | merci | 通过；thank you / thanks 与 gracias；merci pour 与例句一致。 |
| 36 | oui | 通过；/wi/，西语 sí 保留重音符号；不可变。 |
| 37 | non | 通过；/nɔ̃/，no；拒绝加 merci 的礼貌例句自然。 |
| 38 | aujourd’hui | 通过；UID/lemma 使用 ASCII 撇号、例句用排版撇号均合法；/o.ʒuʁ.dɥi/ 含 /ɥ/ 正确；today / hoy。 |
| 39 | demain | 通过；/də.mɛ̃/；tomorrow / mañana；À demain 三语自然。 |
| 40 | très | 通过；/tʁɛ/；very / muy，而非西语 mucho；例句用法正确。 |

## 变位与显示约定

已审读数据库全部 30 组“动词 × 时态”，每组六人称。规则生成的 avoir + 过去分词正确；être 的复合过去时为 avoir + été；aller 为 être + allé，括号形式表达性数变化。`j’` 与动词分列存储是有效契约，UI 必须直接拼接，不能显示成 `j’ ai`。这属于 UI 集成要求，不是数据错误。

`il / elle / on est allé(e)` 的表格简写没有单独写出 on 指复数人时的 allés / allées，但已收录说明明确按实际指代性数配合。因此在当前展示保留该说明的前提下可以通过。不要在 UI 精简过程中删去这条说明。

## 非阻断建议

### C1：aimer 的读音选择与来源

当前 `fr:aimer:verb:1` IPA `/ɛ.me/` 不是错误。CNRTL 的 aimer 条目列出 `/e.me/`，同时承认 `/ɛ.me/` 变体；两者都符合广义法国法语。本项目“选常见孤立词头读音”的政策下，建议使用 `/e.me/` 为主读音，并在词形/使用说明加“也可读 /ɛ.me/”；为该词增加对应 CNRTL 来源。无需宣称原值错误。依据：[CNRTL — aimer 条目，Prononc. 段](https://www.cnrtl.fr/definition/aimant//2)。

### C2：professeur 的来源映射

`provenance.sources.cnrtl-professeur` 已记录 IPA 核验依据，但词条 references 只有 `larousse-professeur`。建议把 `cnrtl-professeur` 加到该词 references，保证单词级追踪同时覆盖性别和音标；现有音标及阴性写法无需更改。依据：[CNRTL — professeur 条目](https://www.cnrtl.fr/definition/professeuse)、[Larousse — professeur / professeure](https://www.larousse.fr/dictionnaires/francais/professeur/64155)。

### C3：审查状态元数据

本报告完成后，`provenance.review_status` 与 `data/curated/README.md` 中的“independent acceptance review pending / 待独立验收”应更新为“independent LLM review completed，见 docs/qa/CONTENT_REVIEW.md”，并保留“非专业人工审校”的边界。若更新源或 provenance，重新生成数据库、跑内容校验，并在报告追加新哈希。该项不改变40词的语言结论。

## 外部定点核验来源

本轮另外确认 provenance 下列链接实际指向正确动词，并用于易错项对照：[Académie — être](https://www.dictionnaire-academie.fr/conjuguer/A9E2986)、[avoir](https://www.dictionnaire-academie.fr/conjuguer/A9A3461)、[aller](https://www.dictionnaire-academie.fr/conjuguer/A9A1226)、[faire](https://www.dictionnaire-academie.fr/conjuguer/A9F0112)、[prendre](https://www.dictionnaire-academie.fr/conjuguer/A9P4070)、[Larousse — manger](https://www.larousse.fr/conjugaison/francais/manger/5950)。

本验收不涵盖教材 PDF/OCR 全量提取、不涵盖未收录的义项/时态，不把离线 TTS 当作经审校真人语音；未对每词进行词典逐字段认证。这些限制与当前开发词库说明一致。

## 审查建议落实与最终资产（2026-09-08）

内容 agent 已落实 C2：为 `fr:professeur:noun:1` 增加 `cnrtl-professeur` 引用；已落实 C3：provenance 与 README 更新为独立 LLM 审查完成，关联本报告并保留非专业人工审校边界。内容版本升为 `2026.09.08-dev.2`。C1 保留现有有效 IPA `/ɛ.me/`，README 指向本报告说明 `/e.me/` 变体；本次未修改语言内容或稳定 UID。

重新生成 SQLite 后复跑 8 项内容测试，全部通过（2026-09-08，0.231 秒）；仍为 40 词、47 核心义项/例句、125 关键词形、180 变位。测试包括只读打开、完整性/外键、语法回归、六人称覆盖、源与资产一致、重复构建字节相同、损坏输入拒绝及项目外输出拒绝。临时文件保留在项目 `.tools` 范围内。

| 最终对象 | SHA-256 |
|---|---|
| data/curated/french_dev.json | `76db12079920c1a650b93220d32018b3498fcb6d2e12701b733ce4b205f631ff` |
| data/curated/provenance.json | `4bdfbc5ef3fa9ea4b2988ab346395d6d88909de024dc5146568c7183505bbdb3` |
| app/src/main/assets/french_content.db | `2643762ac9e76331475981d1a437a32ff1470107a339c6e8eefa153fad28d25b` |
