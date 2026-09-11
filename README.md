# French Vocab · 法语离线背词

原生 Android 法语学习版本，Kotlin + Jetpack Compose。中文操作界面，学习语言仅 `fr-FR`，支持中文、英语和西班牙语释义与例句译文。具体语言内容由词库提供；无需账户或网络。

## 当前成品

- 894 个内容词条：覆盖教材词表 891 条来源记录，并保留 4 个既有精选词条；同形异义词拆分、重复出现的同一固定搭配合并。
- 145 个动词的现在时、复合过去时、未完成过去时，共 2,610 条六人称变位。
- 首页真实进度，固定随机学习队列，正面回忆→点击词卡外空白揭示→四档评分→总结。
- SQLite 保存卡片、日志、队列、答案状态、设置与收藏，退出或进程重启可恢复。
- 词书浏览、忽略重音的法语/中文/EN/ES 搜索、收藏、已收录词条详情与统计。
- 每日新词自定，取消 100 上限，默认 10，0 表示只复习；跨轮累计计数。已学习的到期词不受当前词书限制。
- 自动发音：从不、仅新词、全部；本地录音优先，可回退到设备已安装的离线法国法语 TTS。

教材页 213–224 的词表已经进入正式内容源。全部 901 个义项均有中文释义，全部 901 条既有法语例句均有中文译文；中文释义来自教材词表并经过 OCR 对齐清洗，例句译文来自固定离线快照。新增内容由开放词典快照和离线生成器补全，并通过结构与覆盖校验；既有 40 词审校报告不等同于对全部新增词条和中文译文的专业人工审校。未附带真人录音。没有离线法语语音包的设备会收到明确提示，仍可正常学习。

## 安装与使用

最新交付包见 `releases/french-vocab-0.4.0-book-picker-debug.apk`，最低 Android 8.0（API 26）。这是 debug 签名 APK；允许来自相应文件管理器的安装后打开即可。同签名覆盖安装保留已有进度，无需卸载旧版。

在设置中选择词书和每日新词数，保存后在首页开始。每张卡先回想，点击词卡外空白揭示答案，再选择忘记、模糊、记得或很熟。点击词卡、发音、收藏、导航或滑动不会揭示。学习中返回首页会暂停，重新进入继续原队列。配置在开始一轮时冻结，修改后从下一轮生效。把每日新词设为 0 可只做复习。

首页只展示当前词书的学习进度，并提示可在设置页切换。设置页点击「选择词书」进入二级页面，浏览全部词书及各自进度，选中后点击底部「保存词书」即可保存并返回；直接返回会取消未保存的选择。词书独立保存，每日新词数等其他设置的编辑内容会保留，仍需点击「保存设置」生效。

设置可独立开关中英西释义，至少保留一种；外部旧词库未收录的中文不会自动翻译或伪造。0.3.0 的正式内置词库已完整提供中文，同时兼容原双语词库；中文对接说明见 `docs/CONTENT_CONTRACT_ZH.md`。旧版未完成轮次保留原语言设置，新中文开关从下一轮生效。

调度移植自官方 **ts-fsrs 5.4.2 / FSRS-6 LongTermScheduler**，目标记忆率 90%，关闭随机间隔和分钟级学习步骤。首次四评分对应 1、2、3、8 天；后续间隔由记忆状态计算。“忘记”不会插回当前轮，最早 24 小时后到期。算法来源与数值参考见 `app/src/main/java/com/scott/frenchvocab/domain/fsrs/README.md`，MIT 许可证也随 APK 分发。

## 项目内构建与验收

本机工具副本、缓存、临时目录、Android 用户数据与模拟器均在 `.tools/`。下面脚本会设置目录隔离；使用 Android Studio 时应配置同样的 Gradle 用户目录，避免默认写到项目外。`local.properties` 指向项目内 SDK。Gradle 8.9、Android 插件 8.7.3、Kotlin 2.0.21；已有 JDK 21 只读复用，输出 Java 17 字节码。

```powershell
# 在 L:\Words 执行
.\tools\build.ps1

# 生成/验证内容（Python 标准库，无网络）
. .\tools\project-env.ps1
python -B tools/lexicon/build_content.py
python -B -m unittest discover -s tools/lexicon -p 'test_*.py' -v

# 生成仅用于内容升级验收的测试资产
python -B tools/prepare-test-content.py

# 没有启动项目模拟器时执行一次
.\tools\start-emulator.ps1
.\tools\verify-device.ps1

# 强制结束进程并核对恢复
python -B tools/verify-process-recovery.py
```

模拟器为项目专用 `WordsApi35` / `emulator-5556`。设备验收会重置该 App 的测试数据，勿对真实使用设备运行测试脚本。Android 35 镜像已安装在项目内。停止模拟器：加载 `project-env.ps1` 后执行 `adb -s emulator-5556 emu kill`。

## 结构与后续内容更新

| 位置 | 职责 |
| --- | --- |
| `domain/Models.kt` | UI、内容与学习仓储共同契约 |
| `data/content/ContentRepository.kt` | 独立版本的只读内容数据库 |
| `data/user/StudyRepository.kt` | 用户数据、事务、恢复、设置与统计 |
| `domain/fsrs/`、`domain/audio/` | 调度与发音 |
| `feature/` | Compose 页面、导航、状态与 IO |
| `data/curated/`、`tools/lexicon/` | 词库源、出处、构建与强校验 |
| `app/src/androidTest/` | 数据库、界面、音频与内容升级验收 |
| `docs/qa/` | 审校、构建日志、设备日志、截图 |

上表的 Kotlin 路径位于 `app/src/main/java/com/scott/frenchvocab/` 下。完整内容源是 `data/curated/french_a1.json`；刷新教材派生内容时先运行 `tools/lexicon/build_a1_source.py`，再运行 `build_content.py`。`french_dev.json` 保留原始 40 词审校种子。内容库按 SHA256 独立安装；学习历史保存在单独的 `french_user.db`，不会因词库更新被清空。

最新功能与验收见 `docs/CHANGELOG_0.3.0.md`，中文对接见 `docs/CONTENT_CONTRACT_ZH.md`。原开发目标与分工见 `docs/DEVELOPMENT_PLAN.md`；0.2.0 阶段验收见 `docs/ACCEPTANCE_REPORT.md`；教材词表覆盖见 `docs/qa/A1_CONTENT_COVERAGE.md`；40 词种子的语言审校见 `docs/qa/CONTENT_REVIEW.md`。
