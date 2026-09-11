# 教材 A1 内容覆盖报告

生成日期：2026-09-10  
内容版本：`2026.09.10-a1.3`

## 项目要求

当前 Android 读取层要求每个词条具有稳定 UID、法语 lemma、词性、fr-FR IPA、1–2 个 ZH/EN/ES 核心义项，以及与每个义项一一对应的法语例句和 ZH/EN/ES 译文。中文字段遵循 `docs/CONTENT_CONTRACT_ZH.md`：`sense.chinese` 与同一行英西义项对应，`example.chinese` 只翻译同一行既有法语例句。名词还需要性别和复数，形容词需要阴性与复数形式。动词需要组别、助动词、过去分词、现在分词，以及直陈式现在时、复合过去时、未完成过去时的六个人称形式。

本次把合法词性扩展为 noun、verb、adjective、adverb、interjection、preposition、conjunction、pronoun、determiner、proper_noun、expression 和 numeral。SQLite 外键、顺序、每词发音、义项与例句的一一对应、动词 18 条变位、词书成员关系仍由构建器强制校验。

## 覆盖结果

| 项目 | 数量 |
| --- | ---: |
| 教材来源记录 | 891 |
| 教材学习词条 | 890 |
| 保留的既有精选词条 | 4 |
| 最终 lexeme | 894 |
| ZH/EN/ES sense | 901 |
| FR example + ZH/EN/ES translation | 901 |
| verb | 145 |
| conjugation form | 2,610 |
| pronunciation | 894 |

891 个教材序号均且仅映射一次。教材中重复出现的 `bien sûr` 共用一个学习词条并保留两个来源位置；`aller`、`livre`、`tableau` 等同形异义项使用独立 UID。逐行映射及首义、IPA、审校状态见 `data/curated/a1_content_audit.csv`。

词性分布为：noun 473、verb 145、adjective 115、adverb 58、proper_noun 43、preposition 21、expression 17、interjection 14、conjunction 6、pronoun 2。

## 来源与审校边界

词头和教材词条中文释义来自用户提供的总词汇表。中文释义以 OCR 行坐标和 891 条法语词头的固定顺序动态对齐，再对扫描噪声、同形多义和当前核心义项做显式修订。既有法语例句没有改写；中文例句译文由法语原句生成并保存为本地固定快照，构建时不访问网络。新增 EN/ES 内容继续使用项目内固定的 Kaikki/Wiktionary、WiktApi/西语 Wiktionary和 Apertium 法西词典快照；22 个没有词典 IPA 的条目使用 eSpeak NG 离线生成。

教材记录中 36 条复用了已独立审查的种子内容；其余 855 条标记为 `machine-assisted`。自动校验确认中文完整、语言字段分离和内部一致，不能替代专业译者逐条审校。新增条目均使用包含目标词或其正确变位的实际用例；生成器和校验器会拒绝“该词出现在本课词表”一类元语言占位句。

## 验证

- 内容测试：10/10 通过。
- SQLite schema v2：894 lexeme、901 sense、901 example、145 verb_info、2,610 conjugation_form；中文空值为 0，完整性和外键检查通过。
- Android：`assembleDebug`、`assembleDebugAndroidTest`、`testDebugUnitTest`、`lintDebug` 全部通过。
- APK 内数据库 SHA-256 与 `app/src/main/assets/french_content.db` 一致：`0b748024f5c8267578f287f597c28d8b02476eb9d8152df181f6da1b1a64209f`。
- 发布 APK SHA-256：`313bdb570a9799bbbbc452cc46c28918891b02b73558f93a09faaa9e3108fa9d`。
