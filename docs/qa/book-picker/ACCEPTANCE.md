# 词书选择交互验收 · 2026-09-11

## 完成内容

- 首页只展示当前词书的名称、已学习数量和百分比，提示「可在设置页切换词书」。
- 设置页词书区域收拢为「选择词书」入口，显示当前词书名称和学习进度。
- 二级选择页使用滚动单选列表，展示全部 13 本内置词书的名称和进度；底部固定「保存词书」。
- 点击选项只修改草稿；返回取消，保存成功后返回设置页。保存期间禁止重复提交和返回，保存失败沿用已有错误提示并保留选择。
- 词书独立保存；往返二级页面或重建 Activity 时保留其他设置草稿。主设置再次保存时使用最新词书，不会回退到旧选择。
- 已开始的学习轮次保持原队列与设置，切换词书从下一轮生效。

## 验证结果

Android API 35 项目专用模拟器 `WordsApi35` / `emulator-5556`，1080×1920。

- `assembleDebug`、`assembleDebugAndroidTest` 构建成功。
- 单元测试 11/11：学习队列 6 项，FSRS 5 项。
- `lintDebug`：0 errors，5 条依赖版本更新提示。
- 界面测试 7/7：新增词书流程 3 项，原有 AppFlow 回归 4 项。
- 1.5 倍字体：重复词书展示、遍历全部词书、保存按钮可见及取消选择检查，1/1 通过；已人工检查截图。

首次回归中，旧查词测试假定 `être` 位于搜索结果首屏，扩充词库后该假定失效。测试现先滚动定位目标词条，再执行原有收藏、详情和音频断言；最终回归全部通过。

日志：

- [最终构建与单元测试](../book-picker-final-build.log)
- [最终测试包构建与静态检查](../book-picker-test-final-build.log)
- [7 项界面测试](../book-picker-final-instrumentation.log)
- [1.5 倍字体检查](../book-picker-large-font.log)

截图：

- [首页](screenshots/01-home.png)
- [设置页](screenshots/02-settings.png)
- [词书选择页](screenshots/03-book-selection.png)
- [大字体设置页](screenshots-large-font/02-settings.png)
- [大字体词书选择页](screenshots-large-font/03-book-selection.png)

交付包：`releases/french-vocab-0.4.0-book-picker-debug.apk`（版本 0.4.0 / versionCode 4）。

SHA-256：`6f0fea81eb07977d1b54aedf2d5ce66271a17f4a4f8fdb75c4102f817738a407`。
