# 0.3.0 使用反馈更新

日期：2026-09-10。包名 `com.scott.frenchvocab`，versionCode 3。交付包：[french-vocab-0.3.0-debug.apk](../releases/french-vocab-0.3.0-debug.apk)。

## 已实现

1. **每日新词取消 100 上限，保留自定数量。** 默认 10，0 仍表示只复习，允许 101、1000、10000 等数量；每日额度跨轮累计。输入完整保留并校验，底层使用非负 Int，极端大数明确提示而不截断或溢出。实际开始数量由剩余词条决定。
2. **点击词卡外空白揭示答案。** 正面移除原揭示按钮，提示改为“点击空白处揭示答案”。词卡本身、词头、IPA、词性、发音、收藏及导航点击不会揭示；滑动也不会当作轻点。保留无障碍揭示操作，揭示后四档评分不变。
3. **接入中英西显示和读取契约。** 释义、例句译文、设置与词库搜索支持中文；可独立开关三种语言，至少保留一种。只显示所选且已收录的语言，没有内容时明确提示。词库窗口可按 [中文字段契约](CONTENT_CONTRACT_ZH.md) 补充 `sense.chinese`、`example.chinese`；App 同时兼容旧双语库和含可选中文列的版本 1/2 内容库。

## 升级兼容

用户数据库升级到 v2，只迁移设置表，解除旧 100 限制并加入中文开关；保留学习卡片、日志、收藏、未完成队列、答案状态及旧设置值。旧轮次缺少中文设置字段时保持原英西显示，下轮采用新设置。

已在专用 Android 35 模拟器上实际从 0.2.0 **覆盖安装** 0.3.0：六张用户表的全部旧字段与自增序列逐行一致，原未完成轮次及答案恢复，数据库版本从 1 升至 2。[升级结果](qa/0.3.0/apk-upgrade.json)、[恢复截图](qa/0.3.0/upgrade-restored.png)。现有用户使用同签名 APK 直接覆盖安装，无需卸载旧版。

## 验收证据

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| 构建 | 成功，2 分 54 秒 | [build.log](qa/0.3.0/build.log) |
| JVM | 11/11，含大额度、整数边界及既有 FSRS 对照 | [JVM 报告](qa/0.3.0/junit/) |
| Android | 28/28，37.795 秒 | [instrumentation.log](qa/0.3.0/instrumentation.log) |
| 200% 字号 | 8/8 UI 补充回归，39.435 秒；完成后恢复 100% 字号 | [large-font.log](qa/0.3.0/large-font.log)、[大字体设置页](qa/0.3.0/screenshots-large-font/06-settings.png) |
| 进程重启 | 真实强制结束后 PID 3141 → 3204，六张用户表逐行一致，已揭示答案恢复 | [process-recovery.json](qa/0.3.0/process-recovery.json) |
| 旧库迁移 | 硬编码真实 v1 schema 与六张非空表验证通过；全局/会话语言快照、Int.MAX_VALUE、重新打开与后续评分均覆盖 | `SettingsMigrationInstrumentedTest`，见同一设备日志 |
| 中文读取 | 缺列、NULL、双语旧库、版本 1/2 中文列、仅释义中文、未来格式拒绝及实际随包词库读取通过 | `ChineseContentInstrumentedTest`，见同一设备日志 |
| 中文界面 | 三语顺序、仅中文、最多两个核心义项、详情全部义项、中文搜索、缺失提示通过 | `ChineseUiInstrumentedTest`，见同一设备日志 |
| 轻点交互 | 物理空白点及无障碍揭示通过；卡片/发音/收藏/导航/滑动不揭示 | `AppFlowInstrumentedTest`，见同一设备日志 |
| lint | 0 错误，5 条 `GradleDependency` 版本更新提示；本次未扩大依赖升级范围 | [lint XML](qa/0.3.0/lint-results-debug.xml) |

截图：[无揭示按钮的正面](qa/0.3.0/screenshots/02-question.png)、[设置页](qa/0.3.0/screenshots/06-settings.png)、[中文答案组件](qa/0.3.0/screenshots/10-chinese-answer.png)、[三语详情组件](qa/0.3.0/screenshots/11-chinese-detail.png)、[中文搜索组件](qa/0.3.0/screenshots/12-chinese-search.png)。中文组件截图使用独立测试内容，并非随包中文词库完成声明。

## 词库边界与文件约束

本窗口未修改 `data/`、`tools/lexicon/`、正式 `app/src/main/assets/french_content.db` 或其词条内容。读取和打包使用另一个窗口提供的 894 词数据库，本次处理前后及 APK 内的数据库 SHA-256 均为 `8a4efd7013a26cef4f0e0c516ab50efa8ad7097c92fd05e590837c6241498543`。其中尚无中文列，中文内容仍由词库窗口补齐。

中文和迁移测试使用 `app/src/androidTest/assets/content-fixtures/` 的独立小样，基线从保留的 40 词 `french_dev.json` 只读生成；未混入正式 APK。所有开发、构建、测试、缓存与证据均在 `L:\Words` 内，未修改项目外配置或文件。

本轮 11 项 JVM + 28 项 Android，共 39 项不同测试通过；8 项大字体测试为既有 UI 测试的补充运行。APK 与覆盖安装、设备回归时的构建输出一致，为 10,102,134 字节。验收设备为项目内 Android 35 / x86_64 模拟器，未扩大为全部 Android 版本或实体机覆盖声明。

## 重跑

```powershell
. .\tools\project-env.ps1
python -B tools/prepare-test-content.py
.\tools\verify-device.ps1 -EvidenceDirectory docs/qa/latest
python -B tools/verify-process-recovery.py --evidence-dir docs/qa/latest
```

这些脚本只用于专用测试模拟器，功能测试会重置该 App 的测试数据。实际 APK 升级脚本 `tools/verify-apk-upgrade.py` 需在设备仍安装 0.2.0 且有未完成已揭示学习轮次时运行，不能把反复安装旧版作为日常恢复流程。

APK SHA-256：`677457c8022a1a08de2e0e0e768be82cc1b6b640173c55b17e977b80f2f26faa`。
