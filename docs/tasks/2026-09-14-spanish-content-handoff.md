# 西语内容工作交接与可复制提示词

日期：2026-09-14。项目：`L:\Words_esp`。不要误改法语项目 `L:\Words`。

## 当前状态

- 问题 1/2/3/5 的 Android 实现已完成：词库按 50 条分页、重新进入先到首页、显式继续学习、新用户默认 `elelex-a1-top1200-es`、全部已收录变位的滚动表格。13 项 JVM 测试、27 项设备测试、lint、进程恢复检查已通过。
- Android 已支持逐义项例句和无可信例句时的空数组，学习页面不再只显示前两个义项。
- **问题 4 尚未完成，不能宣称正式词库已经修复。** 正式 `data/curated/spanish_full.json` 与 `app/src/main/assets/spanish_content.db` 仍是 9 月 12 日版本。当前交互修复 APK 使用这份旧正式库。
- `tools/lexicon/generate_elelex_content.py` 有未完成的生成器修改；`data/curated/spanish_sample_qa.json` 是未验收样本，不是合格正式源。最后样本含明显错误，不能直接发布。
- 原始备份：`.task-backups/20260913-app-fixes`。稳定性基线：`docs/qa/app-fixes-20260913/baseline.json`。保留 14,290 个 UID、顺序和 17 本词书的成员顺序。
- 两个原 Agent 因用量上限停止。用户要求将逐词审校交由新聊天独立处理，不继续调子 Agent 做批量逐词核查。

## 提示词 A：先收尾生成器和候选数据（实现任务，不做全量人工审校）

```text
你在 L:\Words_esp 工作。请读取 L:\Words\docs\tasks\2026-09-14-spanish-content-handoff.md，完成问题4的生成器收尾与候选数据生成。不要启动子 Agent，不逐条人工审校整个词库，不修改已经通过测试的 Android 交互实现。

先做一次有边界的代码策略检查，然后直接实施：
1. 检查 tools/lexicon/generate_elelex_content.py 的已有改动，不要从头重写。当前 exact POS 匹配已改进，空 examples 数组已经允许，统计已改为实际义项/例句计数。这些保留。
2. 纠正当前 candidates 将英、西词典直接拼接的未完成策略：以同词性英文词典的多个非重复义项为主；没有匹配的英文词条时再取西语词典。经人工核实的西语补充义可单独覆盖；不要全局直接拼接造成重复义项和机器误译扩散。不准拿其他词性凑数。无可靠匹配时保留 UID 并明确标待核实。
3. 常用核心义优先，金融、罕见、历史和地区用法按实际意义标注；不要仅凭英文短词直接翻译来决定首义。保留多个真实义项，不把重复的解释句当新义项。
4. 例句优先采用与义项匹配的词典/语料句或已核实的人工句；允许动词变位、反身形式。优先完整的 10–18 词情境句，此长度是软目标，不为凑字数改坏句子。不得用通用词性模板、元语言“这个词是……”句或错义项语料填空。无可信例句则 examples=[]，进入 QA 待补。
5. 修复代码中已记录的明确回归，不再扩大人工核查范围：interesar 的中文不能译反施受关系，英法译文不能漏掉半句；hacer 的 ellipsis of hacerse el tonto 不能译为“粗糙的吨位椭圆形”，天气句不能译为“这是40度昨天”；sobre 的罕见“床”义和 derecho 的罕见“道路”义没有来源确认的例句直接置空。删除这种猜造句，而不是再造一个替代。当前 hacer 样本因双源拼接多达数十义，含“作禅床”等误译，必须先纠正生成策略。interés 常用兴趣义与利息义需合理排序。其他逐词判断留给分批审校任务。
6. 把已有人工覆盖整理为可复现、按 lemma/POS/原义项定位的规则，避免后面的 .update 悄悄盖回错误。不要只改生成 JSON、SQLite 或翻译缓存。
7. 先输出候选文件到独立目录（如 data/curated/review-candidate/），不要用默认路径覆盖正式库。运行必要的生成器/空例句/多义关系测试，给 QA 添加义项来源、翻译来源、未核实清单。不要把“字段非空”报告成“语义正确”。不需要全量逐词复查。

输入：
- tools/lexicon/generate_elelex_content.py、build_content.py、schema.sql、test_content.py、test_elelex_full.py
- data/curated/spanish_full.json（旧正式基线）、spanish_sample_qa.json（未验收样本）
- data/source_cache/kaikki_elelex_a1_c1.jsonl、eswiktionary_elelex_a1_c1.jsonl
- data/source_cache/tatoeba_elelex_a1_c1_examples.json、translation_cache.json、verbecc_elelex_a1_c1.json
- docs/CONTENT_CONTRACT_ES.md、docs/qa/app-fixes-20260913/baseline.json

可复用的正式生成接口（必须显式指定候选输出）：
python -X utf8 -B tools/lexicon/generate_elelex_content.py --output data/curated/review-candidate/spanish_full.json --qa data/curated/review-candidate/spanish_full_qa.json
先确认输出父目录存在。若需要仅生成样本，请使用脚本现有函数或增加小范围参数，当前 CLI 没有 --sample 参数，不要假定它存在。

输出：候选源/QA绝对路径、可复现命令、实际词条/义项/例句数量、与基线 UID 和词书顺序的比较、测试结果、未审校范围。不要构建或发布带未审校候选词库的正式 APK，不要声称所有翻译已经正确。
```

## 提示词 B：分批核查词义（可重复使用，先改批次范围）

```text
请在 L:\Words_esp 做一批西语词义核查，不调用子 Agent。

本次批次：源文件 words 数组的第 1–50 条（从1开始，执行前可由我替换此范围）。优先使用已完成生成器收尾的 data/curated/review-candidate/spanish_full.json；若候选不存在，使用旧正式 data/curated/spanish_full.json，并在报告中明确基线。不能使用 spanish_sample_qa.json 作为正确答案。

只处理本批词条，读取整份文件建立索引可以，但不要顺手审校其他批次。保持 UID、词条次序、词性、等级、词书关系不变。先记录源文件 SHA256 与所选 UID，避免后续批次错位。

逐条检查：
- 首义是否为该词性下常用核心义，是否把次要金融义、罕见义放在前面。
- 多个真实含义是否遗漏；中文、英文、法文是否对应同一义项，有无短词机器翻译造成的歧义、重复或词性串用。
- 常用义与罕见/历史/地区义区分清楚；反身/及物/不及物及必要介词搭配要准确；同形异性名词按实际含义标明性别。
- 避免机械合并不同语言词典的全部解释。允许合理合并近义解释，不为数量增加重复义项。

优先查已有 Kaikki/西语 Wiktionary 缓存；遇到不确定含义再查权威词典的实际页面并记录来源，不凭记忆硬改。缓存和机器译文不是已人工审核的证据。

只生成独立候选补丁 docs/content-review/meanings-0001-0050.jsonl 和简短总结，不修改正式 JSON、数据库、Android 或共享生成器，避免与别的聊天冲突。每条补丁至少包含 uid、原词条内容哈希、原义项定位、问题、建议的中文/英文/法文释义、排序/用法建议、来源、置信度、unresolved。没有问题的词条只列在已检查清单，不整段重复输出。不能确认的写 unresolved，不强行修正。

当前正式源只有每词一个义项，因此不能只检查“现有义项是否正确”，还应核实是否漏了常用义。不要随意新增/删除 UID。例句本批只标记与新义项不对应的句子，不展开全量例句重写，由独立例句批次处理。

最终报告：本批范围及UID、检查数、改动候选数、未解决数、补丁路径。不要循环重查同一批，不跨批扩展。
```

## 提示词 C：分批核查例句和译文（词义补丁先有结论后再用）

```text
请在 L:\Words_esp 做一批西语例句核查，不调用子 Agent。
本次范围：与词义批次 meanings-0001-0050 相同的 50 个 UID；先读对应候选源和词义补丁，避免按错误义项配句。若一个词多义很多，本次最多处理150个义项，剩余明确留到下一批。

对每个有待处理的义项，检查现有例句是否对应词义、词性和搭配，包含词头或合法的屈折/变位/反身形式。逐句核对中文、英文、法文译文是否保留完整信息，主客体、否定、时态和语气是否一致。不能把 interesar 的“使她感兴趣”译成“她使别人感兴趣”，也不能让法文只剩复句的一半。

优先使用有来源的匹配义项完整句；选择实用、有情境的例句，通常10–18个西语词，长度为软目标。不要改写受版权保护的长段落，不搬整篇词典。来源句很短但准确时，可保留并标待补，不为凑长度扩写。可自行编写日常情境例句，但必须明确标“人工新编”，核对语法、搭配、该义项用法及全部译文；罕见义不确定就 examples=[] 加待补，禁止按词性槽位套模板。

输出 docs/content-review/examples-0001-0050.jsonl，每项含 uid、原义项定位/哈希、原句、问题、建议 spanish/chinese/english/french、来源或人工新编标记、置信度、unresolved。不要覆盖正式内容或其他聊天的补丁，不修改生成器或Android。
最终只给实际审校数、改句数、待补数、补丁路径和必要的疑难项，不重复长篇报告。
```

## 审校后的合并与发布

由一个集成任务合并各批补丁，先检查源哈希和冲突，落实到生成器可重放的覆盖规则，再生成正式 JSON 和 SQLite。校验词条 UID 与顺序、17 本词书的成员顺序、sense/example 外键、允许空例句、已审校回归项；不要重新人工审校所有已批准批次。然后构建 APK，检查嵌入 DB 与当前正式资产 SHA256 一致、签名与旧 APK 一致。未完成这些步骤前，问题 4 仍属于待完成。
