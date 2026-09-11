# 法语 A1 内容

`french_a1.json` 是当前正式内容源，共 894 个词条。它覆盖《你好！法语 1》词表页 213–224 的 891 条来源记录：完全重复的 `bien sûr` 合并为一个词条，同形异义词保留不同 homonym 编号；另保留 4 个既有精选词条。`french_dev.json` 是早期 40 词人工编写与独立审查的种子集。

每词包含稳定 `fr:lemma:pos:homonym` UID、fr-FR IPA、ZH/EN/ES 核心义项，以及法语实际用例和 ZH/EN/ES 译文。名词保留性别和复数；形容词保留性数形式。145 个动词都有关键现在时形式、现在分词、过去分词、助动词，以及直陈式 présent / passé composé / imparfait 各六人称。词性契约还覆盖介词、连词、代词、限定词、专名、数词与固定表达。校验器会拒绝“该词出现在本课词表”一类元语言占位句。

`provenance.json` 记录作者说明、来源、许可和审校边界。教材中文释义保存在 `data/raw/textbook_a1_chinese_glosses.csv`，既有法语例句的中文译文保存在固定快照 `data/raw/example_zh_translations.json`；正式构建不访问网络。新增条目使用固定的 Kaikki/Wiktionary、WiktApi/西语 Wiktionary、Apertium 法西词典快照；缺失 IPA 用本地 eSpeak NG 生成，固定搭配的缺口用显式桥接释义补齐。每个教材条目同时记录原序号、原书页码和原显示形式。新增条目已通过自动覆盖与结构校验，尚未宣称专业人工逐条审校；40 词种子的独立审查见 [内容验收报告](../../docs/qa/CONTENT_REVIEW.md)。

2026.09.08-dev.2 补充 professeur 的单词级 CNRTL 引用并更新审核状态。aimer 的现有 `/ɛ.me/` 已由独立审查确认有效，保留当前值；另一有效变体 `/e.me/` 的来源与说明见验收报告 C1。本次未改变语言内容或稳定 UID。

## 生成与验证

在项目目录执行（Python 3.10+ 标准库，无额外依赖）：

```powershell
python -B tools/lexicon/extract_chinese_glosses.py
python -B tools/lexicon/build_a1_source.py
python -B tools/lexicon/build_content.py
python -B -m unittest discover -s tools/lexicon -p 'test_*.py' -v
```

生成资产为 `app/src/main/assets/french_content.db`。两个生成步骤均只读取项目内固定快照，不访问网络。脚本所有写入限于本项目，包括 `.tools/lexicon-tmp` 和测试临时目录。固定输入和相同 SQLite 版本产生字节相同的资产，输出在校验后原子替换。

## 数据契约

- Schema `user_version=2`：`sense.chinese` 与 `example.chinese` 均为非空中文字段；其余表为 lexeme / word_form / verb_info / conjugation_form / pronunciation / content_meta / vocabulary_book / book_lexeme。外键完整，顺序显式持久化。
- 默认词书 `essential-fr` 包含全部 894 词；`verbs-fr` 包含 145 个动词，同 UID 共享学习记录。
- UID 一经发布不因排序、教材页码、词书或释义修订而改变。同形异义且需拆卡的词新增 homonym 号码；例如 `livre:noun:1` 表示书本，`livre:noun:2` 表示阴性“磅”。
- 表内变位 `form` 不含主语。缩合时 `pronoun=j’`，显示为 `j’` + `form`；其余用空格连接。person=1…6 固定表示六个语法人称。第三人称单数含 il / elle / on。
- `aller` 的过去分词以括号展示性数变化，另有说明解释 vous 敬称单数和 on 的实际指代。avoir 复合表展示无前置宾语时的基础形式。
- `audio_asset=NULL` 表示没有本地录音，不伪造可播放路径。应用按设置尝试离线 fr-FR TTS。
- `ContentRepository` 只读打开按 asset SHA-256 命名的独立数据库并缓存，随后关闭数据库。新内容生成新文件；用户库不参与复制、删除或版本迁移。

## 扩展内容版本

编辑教材词头 CSV、生成器或 provenance 时应保留已有 UID，更新 content_version，重新生成并验证，再做独立语言验收。刷新开放词典数据必须显式运行网络抓取脚本；正常内容构建保持离线。新增时态时需同时扩展源、schema、读取和覆盖校验；新增本地音频时需校验路径和许可。
