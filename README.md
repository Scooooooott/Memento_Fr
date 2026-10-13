# Memento

法语、西语共用学习核心的离线 Android 背单词应用。应用 ID 为 `com.scott.memento`，可与原法语、西语应用并存。当前源码版本为 `0.2.0` / versionCode `2`，minSdk26、target/compileSdk35。

## 功能

- 首次选择目标语言和学习语言，设置中可切换。法语支持中文/英语/西语释义，西语支持中文/英语/法语释义；界面目前为中文。英语及地区变体不能作为目标语言。
- 法语19,465、西语14,290个正式词条，共享查词、词书、详情、收藏、发音入口及统计。法西语法按各自人称、语气、时态和地区信息展示；其他正式语言尚未接入。
- 进度、词书、计划和额度按目标语言保存。每日总目标包含复习与新词，复习优先；普通计划完成后可选择剩余到期复习，也可使用不限总量的全部复习模式。
- 同日恢复队列；修改目标和词书当日调整剩余任务，保留已评分记录。学习时区固定保存并可修改；午夜可以选择延迟至02:00，宽限评分归前一业务日，FSRS仍使用真实时间。
- 支持法语旧用户库v1/v2和西语v1的只读预览、显式合并导入、冲突归档和重复保护。新应用不能自动访问旧包私有目录；输入须为已获得的一致独立SQLite快照。

## 构建与检查

Kotlin/Compose/SQLite，Gradle8.9、AGP8.7.3、Kotlin2.0.21、JDK21（编译目标17）、AndroidSDK35、Python3.12。Gradle wrapper 与官方依赖仓库配置已提供。

现有 Windows 本机入口：

```powershell
Set-Location -LiteralPath 'L:\Memento'
.\tools\build.ps1 -Tasks @('assembleDebug','testDebugUnitTest','assembleDebugAndroidTest','lintDebug')
```

该入口仍使用本机 JDK/Python 路径、隔离工具目录及本地依赖镜像；不同目录、没有旧工程缓存的干净环境尚未验收。镜像只读读取旧缓存，新工具、缓存和输出写在 Memento。

可单独核对正式资产和当前提交范围：

```powershell
python -B .\tools\check_content_assets.py
python -B .\tools\check_git_scope.py
```

正式内容位于 `app/src/main/assets/content`。当前内容门槛还需要 `data/source-snapshots` 的两个冻结法西库、`data/normalized`、`data/content-packages`、对应manifest及三个QA机器输入；因此这些输入保留在源码范围内。扩展测试小包位于 `data/fixtures/p7/generated`，仅作为fixture，不是正式学习内容。

`tools/prepare_content_packages.py --deploy` 可从本项目冻结输入转换、全量逐项对照后部署法西包；普通构建只检查，不重跑翻译或生成正式词条。稳定 UID、文本关系、词书顺序和来源保持不变。

开发说明、阶段记录、研究下载、原始验收证据、SDK/缓存、APK、用户备份和签名秘密留在本地，不进入源码历史。历史证据聚合脚本依赖完整当次本地证据束；它们不能在只有源码的checkout中直接重放。范围工具不会暂存、提交或推送。

## 验证与当前限制

当前实现此前在专用API35模拟器完成182项Android用例、44项JVM用例、37项桌面契约与法西33,755词全量结构/字段对照，并有宽度/字号和持久学习状态进程恢复记录。该结果不等于所有设备和教学内容均已验证；原始证据未随源码上传。

已知待处理边界：异常旧库的已评分FSRS参数校验不足；普通完成后未结束的额外复习跨日缺少明确归档；播放结束后状态提示未自动清除。已有法语部分复合形式内容矛盾保留待审。完整用户备份/恢复、旧词库文件回收及独立界面语言切换尚未实现。

低版本Android/真机/OEM、横屏、真人屏幕阅读器、离线法西声音实播、真实私有备份及最大容量迁移尚未完成专门验收。设备清库测试只允许专用隔离模拟器，不操作真实设备或旧应用用户数据。

## 来源与许可

FSRS调度器移植自 `ts-fsrs v5.4.2`（FSRS-6，长期模式），保留MIT归属和JVM参考数据；完整说明见 `app/src/main/java/com/scott/memento/domain/fsrs/THIRD_PARTY_LICENSE.txt` 与 `app/src/main/assets/licenses/ts-fsrs.txt`。

法西正式内容与第三方来源记录、许可说明保存在 `app/src/main/assets/licenses` 和数据库的逐条来源字段。现有内容含机器辅助材料，转换保真/字段完整不代表全面专业语义审校。西语说明仍记录 supplied ELELex 数据未随附授权文件；本项目尚未选择顶层代码许可证，不宣称所有再分发条件已确认。
