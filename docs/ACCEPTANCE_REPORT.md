# 法语开发版验收报告

> 本报告记录 2026-09-09 的 40 词应用开发版验收。随后加入的教材 A1 完整内容使用单独的生成与自动校验流程；本报告中的逐词审校数量不是当前词库总量。

验收日期：2026-09-09。版本：0.2.0（versionCode 2）。结论：**本阶段开发目标已完成，可安装的法语离线背词开发版验收通过。**

交付物：[APK](../releases/french-vocab-0.2.0-debug.apk)、[SHA-256](../releases/french-vocab-0.2.0-debug.apk.sha256)、[机器可读验收汇总](qa/verification-summary.json)。APK 为 debug 签名，9,700,839 字节，包名 `com.scott.frenchvocab`。

## 目标依据与范围

已读取当前项目实现及项目任务《读取法语词库设计》《读取法语词库设计 (2)》，并核对其引用的《整理法语词库拆解》最终 MVP 决策。具体目标、验收标准和分工已写入 [开发计划](DEVELOPMENT_PLAN.md)。

成品使用 Kotlin / Jetpack Compose，中文操作界面，只学习法国法语 `fr-FR`，EN/ES 为释义和例句翻译。实现从首页开始、回忆、揭示答案、四档评分到总结的完整流程，以及词书浏览、搜索、收藏、详情、统计和设置。数据保存在设备内，无需账户或网络。

当前内容为独立审查的 40 个精选开发词条：47 组释义与原创例句、125 个关键形式、10 个动词 × 3 个时态 × 6 人称，共 180 条变位。两个本地词书分别包含全部 40 词和其中 10 个动词。完整教材 A0–A1 词库、真人录音属于后续内容版本；没有把原始 PDF/OCR 当作正式词库。原始教材、OCR 和 HTML 原型保留。

## 子 agent 执行结果

| 负责人 | 完成内容 | 集成与复核 |
| --- | --- | --- |
| backend / Harvey | 用户 SQLite、固定学习队列、每日额度、FSRS-6 调度、JVM 测试 | 主 agent 用设备测试核对持久化和事务；backend 另独立审查词库与恢复验收脚本 |
| content / Planck | 40 词内容、来源记录、稳定 UID、内容数据库、构建与校验工具、读取层 | backend 逐词审查，内容 agent 落实引用与状态元数据建议 |
| ui / Descartes | 首页、学习、详情、总结、词库、统计、设置及真实状态接入 | 主 agent 运行 Compose 功能测试和截图验收；修正答案密度、对比度、大字体评分布局与设置键盘遮挡 |
| 主 agent | 目标冻结、接口契约、离线发音、环境隔离、集成修复、打包与最终验收 | 所有交付代码及证据保存在项目内 |

## 逐项验收

编号对应开发计划中的十项标准。

| 编号 | 结果 | 实现与证据 |
| --- | --- | --- |
| 1 | 通过 | 首页从用户数据库计算今日完成、当前词书进度和可学习数量。UI 流程完成 2 词后断言“今日完成 2”，额度耗尽时开始按钮禁用。 |
| 2 | 通过 | 内容库与 `french_user.db` 独立，以稳定 UID 关联。升级测试加载哈希不同、释义确实改变的内容资产，断言新内容生效，卡片、未完成 session、收藏、设置及当日日志数量保留。 |
| 3 | 通过 | 默认每日 10 新词，支持 0–100；跨轮累计，午夜按实际评分日计入额度。所有已学习到期词可跨词书进入队列；新词与复习混合去重后随机排列并持久保存。规划单测和数据库测试覆盖零额度、追加额度、跨日、跨词书。 |
| 4 | 通过 | 队列、当前位置、答案揭示状态和本轮完整设置快照落库。测试覆盖仓储重开、Activity 重建、设置下一轮生效；另以实际 `force-stop` 和新 PID 验证进程恢复，六张用户表逐行不变。 |
| 5 | 通过 | 正面未出现释义节点；答案最多两个义项，带 EN/ES、例句、四个关键形式。详情显示全部已收录义项、例句、形式与变位，`j’` 正确连写。普通与 200% 字号截图已检查。 |
| 6 | 通过 | 四评分映射 Again/Hard/Good/Easy，FSRS 为唯一调度器。评分必须已揭示且匹配当前词；重复提交不重复计数。故意注入数据库触发器异常，确认卡片、日志和队列全部回滚；Again 不插回本轮。 |
| 7 | 通过 | 总结取本轮实际评分，重点词取 Again/Hard；统计取真实日志。UI 测试核对完成数量和无任务状态，规划单测覆盖连续学习天数。 |
| 8 | 通过 | 每日额度、词书、IPA、EN/ES、三种自动播放模式与 TTS 设置持久化。测试拒绝 101，接受 0，并保证至少一种释义语言开启；配置重建后仍保留。键盘“完成”可收起，大字体下可滚动至保存按钮。 |
| 9 | 通过，见边界 | 播放器优先本地资产，仅选择已安装、无需网络的 fr-FR TTS。测试验证本地测试音频的播放路径、缺失/非法资产提示和 TTS 回退结果。未附带真人录音，模拟器缺少法语语音时验证明确提示，未做实际发音声学质量验收。 |
| 10 | 通过 | 干净构建完成；10 项 JVM、8 项内容、15 项 Android 测试通过。200% 字号补充回归 3 项通过；真实进程恢复通过。最终 lint 0 错误、0 警告。日志、XML 与截图已保存。 |

## 测试与构建证据

| 检查 | 实测结果 | 证据 |
| --- | --- | --- |
| 干净构建 | 成功，81 个任务全部执行，2 分 57 秒 | [final-build.log](qa/final-build.log) |
| 最终 lint | 0 错误、0 警告 | [XML](qa/lint-results-debug.xml)、[运行日志](qa/final-lint.log) |
| JVM | 10/10：调度 5 项、规划与统计 5 项 | [FSRS XML](qa/junit/TEST-com.scott.frenchvocab.domain.fsrs.FsrsSchedulerTest.xml)、[规划 XML](qa/junit/TEST-com.scott.frenchvocab.data.user.SessionPlannerTest.xml) |
| FSRS 数值对照 | 36 组官方 ts-fsrs 5.4.2 参考案例匹配，包含在上述调度测试中 | [来源与移植说明](../app/src/main/java/com/scott/frenchvocab/domain/fsrs/README.md) |
| 内容工具 | 8/8，含完整性、外键、语法、六人称、稳定 UID、字节级复现、非法输入及项目外路径拒绝 | [content-tests.log](qa/content-tests.log) |
| Android 功能 | 15/15：数据库 9 项、界面 3 项、发音 3 项，39.240 秒 | [instrumentation-final.log](qa/instrumentation-final.log) |
| 200% 字号 | 界面 3/3，18.772 秒，测试后恢复字号 100% | [large-font.log](qa/large-font.log)、[大字体保存按钮](qa/screenshots-large-font/06-settings.png) |
| 真实进程恢复 | PID 4923 → 4992；答案恢复；六张用户表的行内容摘要前后相同 | [process-recovery.json](qa/process-recovery.json)、[恢复截图](qa/screenshots/08-process-restored.png) |
| 语言审查 | 40/40，独立于内容生成 agent | [CONTENT_REVIEW.md](qa/CONTENT_REVIEW.md) |

以上为 **33 项不同的自动化测试**；200% 字号是已有 3 项 UI 测试的补充运行，FSRS 的 36 组参考案例不另算 36 个测试。进程恢复另运行一项 UI 流程准备受控状态，再从外部结束并重启进程。

界面截图：[首页](qa/screenshots/01-home.png)、[正面](qa/screenshots/02-question.png)、[答案](qa/screenshots/03-answer.png)、[详情](qa/screenshots/04-conjugation.png)、[总结](qa/screenshots/05-summary.png)、[设置](qa/screenshots/06-settings.png)、[发音提示](qa/screenshots/07-audio-unavailable.png)。

## 构建环境与目录约束

Gradle 8.9、Android Gradle Plugin 8.7.3、Kotlin 2.0.21、Compose BOM 2024.12.01；编译/目标 API 35，声明最低 API 26。实际功能测试设备为项目专用 `WordsApi35`，Android 35 / x86_64，1080 × 1920、420 dpi。使用本机已有 JDK 21，Java 字节码目标 17。

开发、SDK 副本、Gradle 缓存、Android 用户与模拟器数据、临时文件、测试资产和日志全部放在 `L:\Words`。通过 `tools/project-env.ps1` 为子进程设置路径，已有外部工具仅只读复用。没有修改项目外 SDK 或系统配置。构建说明与可复跑脚本见 [README](../README.md)。设备测试会重置测试 App 的数据，只应使用专用测试模拟器。

## 已知交付边界

- 这是 40 词开发版本，不是完整教材词库。内容经过独立 LLM 编辑审查，不宣称专业人工或母语审校认证。
- 未附带真人发音。实际法语朗读依赖设备已安装的离线法国法语语音；测试用短音频仅在测试 APK 中，未放入交付 APK。
- 已在 Android 35 模拟器完成验收；未在实体手机或所有 Android 8–15 版本分别测试。无窗口模拟器使用静音模式，未验证扬声器音质或每词发音准确性。
- FSRS 使用长期调度，目标记忆率 90%，关闭随机间隔和分钟级学习。首次四评分间隔为 1/2/3/8 天，“忘记”最早 24 小时后复习，符合本轮固定队列决策。
- 安装包为自用验收用 debug 签名。完整词库、真人音频、正式发布签名和商店发布尚未包含在本阶段范围内。

## 交付校验

APK SHA-256：`930cd16f59aa9ccf283053ad36ed14284f3ba318d212016ac50c0b073a52edec`

内容数据库 SHA-256：`2643762ac9e76331475981d1a437a32ff1470107a339c6e8eefa153fad28d25b`

交付 APK 与本轮安装并测试的构建输出逐字节一致。
