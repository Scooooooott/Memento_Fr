# 法语 F4b 词义审校：第 1–50 条

日期：2026-09-14。批次：`flelex-a1-high-1000` 词书稳定顺序第 1–50 条；这 50 个 UID 同时对应 `data/curated/flelex_a1.json` 的 `words[0:50]`。

## 基线与边界

- 正式源：`data/curated/flelex_a1.json`，SHA-256 `53d12c9ea19884fbf8214878e1e98a1c5b24429a02b8716e993ebc699f495194`。
- 正式数据库：`app/src/main/assets/french_content.db`，SHA-256 `59beead01d5c6cd446c38d4021c6d33528de6479713dbc551697a4e00d97d78d`。
- 词义证据优先使用项目内固定法语 Wiktionary 与 Kaikki 快照；`on`、`ne`、`plus`、`aussi` 的疑难语法用途另由 CNRTL 页面核对。来源注册表和完整 UID 清单见 `batch-0001-0050-manifest.json`。
- 本批完成候选审校后，通过哈希锁定的审核注册表写入正式 JSON 与 SQLite；只替换目标 50 个 UID 的 `senses` 和 `examples`，不修改 UID、词序、词性、等级或词书关系。

## 审校结果

- 检查词条：50。
- 生成词义候选：39 个词条，共 99 个替换义项。
- 现有词义无需修改：11 个词条。
- 合并候选后预计共 110 个义项，低于单批 150 义项上限。
- 词义未解决项：0。这里的“0”仅表示本批核心义项已有结论，不表示收录所有罕见义或固定短语。

无需改词义的 UID：

`fr:et:conjunction:1`、`fr:je:pronoun:1`、`fr:mais:conjunction:1`、`fr:avec:preposition:1`、`fr:mon:determiner:1`、`fr:très:adverb:1`、`fr:ils:pronoun:1`、`fr:nous:pronoun:1`、`fr:moi:pronoun:1`、`fr:oui:interjection:1`、`fr:ou:conjunction:1`。

主要问题类型：

- 词性串用：`le` 限定词混入宾语代词义，`pas` 副词混入“脚步”名词义，`bien` 副词混入“财产”名词义。
- 明显误译：`se` 的中文为“碳粉匣”，`sur` 为“酸”，`ça` 的英文含 `id`，`son` 的西语混入“声音/被救”。
- 多个核心义被压成一个跨语言不对齐的字符串：`être`、`avoir`、`aller`、`faire`、`plus`、`aussi` 等。
- 语法角色未说明：`on` 的不定主语与口语“我们”、`ne` 的否定与赘词用法、`du/des` 的缩合与部分冠词用法。

词义补丁：`docs/content-review/meanings-0001-0050.jsonl`。每条均包含 UID、原词条规范化内容哈希、原义项位置、问题、按 EN/ES/ZH 对齐的建议义项、排序说明、来源、置信度和未解决项。

## 例句与随机抽查

- `examples-0001-0050.jsonl` 已覆盖 50 个词的全部 110 个义项：人工新编 98 条、完整保留基线 9 条、保留法语并重译 3 条；例句未解决项为 0。
- 结构检查确认每个义项恰有一条例句，四语字段齐全，义项索引连续；占位模板扫描未发现“讨论某词/本课词汇”式句子。
- 使用固定种子 `2026-09-14-f4b-0001-0050` 随机抽取 10/50 个词（20%）。抽中 `le`、`de`、`je`、`ce`、`elle`、`on`、`avec`、`pouvoir`、`petit`、`bien`。
- 初查发现 1 项：`le` 的中文释义过度近似为指示词；已改为“阳性单数定冠词（通常不单独译出）”。复核后 10 个样本均通过，例句均有正常具体语义。完整记录见 `spot-check-0001-0050.json`。

## 正式接入与验证

- 审核注册表：`data/curated/review-overrides/flelex_a1.json`；生成器会在 A1 刷新时自动重放这些人工结果。
- 正式源版本：`2026.09.14-flelex-a1.f4b.1`，SHA-256 `3f9d19243cf3f54eb4d4c03f7a97b16c8e2efdbdf3bbea29388c2058bbc0ab1f`。
- 正式数据库 SHA-256：`45e2426bab08e5532439a588bdc35a771e6f1da582de74ed6a5f3404e327a2e8`，并含 `flelex_a1_f4b_review` 元数据。
- Git 基线对比：总词数仍为 2343，UID 顺序不变，前 50 个词均按审核结果更新，第 51–2343 个词改动数为 0。
- `test_content_review.py` 3 项通过；既有 `test_content.py` 11 项通过；SQLite 完整性与外键检查通过。
