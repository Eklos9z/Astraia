推荐结构与核心内容
1. 产品愿景与绝对原则 (Product Vision & Core Principles)
在文件开头，给 AI 设定不可逾越的底线。

产品定位：这是一款面向 Android 平台的专业黑白棋复盘与数据分析工具，对标 iOS 的 Kifubox 和跨平台的 Othello Sensei。

绝对原则：

禁止使用传统的“人机对战”游戏 UI 逻辑。

底层运算必须完全依赖通过 NDK 移植的 Edax C/C++ 引擎。

主线程绝对不能被阻塞，UI 必须保持 60fps 的流畅响应。

2. 核心架构与技术规范 (Architecture & Tech Stack)
通信架构：说明底层是 Edax，通信协议基于标准 Othello Engine Protocol。说明 C++ 引擎与 Android UI 之间必须通过 JNI 和异步数据流（如 Kotlin Flow）进行通信，并带有防抖/节流机制（例如每 100ms-200ms 更新一次 UI）。

UI 规范：规定使用 Jetpack Compose（或你当前选择的框架）。明确要求棋盘不只是显示棋子，每个合法的落子格子上都必须能渲染高信息密度的数据矩阵（类似 Othello Sensei 的三行显示：终局石差、置信度/搜索深度、已遍历节点数）。

3. 分阶段路线图 (Phased Roadmap)
这是解决你“脑子里功能太多，不知如何下手”的关键。在 CLAUDE.md 中把功能切分成不同的阶段（Phases），并告诉 Claude Code 每次只专注当前阶段：

Phase 1：基础设施与核心分析视图（当前重点）

抛弃现有的游戏 UI，重构棋盘视图，实现高密度的动态评估矩阵显示。

完善 JNI 层的异步通信，确保 Edax 引擎的实时输出能平滑地反映在棋盘数字上。

Phase 2：高级输入与棋谱管理（借鉴 Kifubox）

实现从剪贴板导入标准棋谱代码。

实现从主流平台（如 Othello Quest 等）解析并导入棋谱。

构建基础的历史步数树状列表，支持随时跳转盘面。

Phase 3：高阶分析指标与训练工具

引入“海龟数”（Umigame numbers）的计算与可视化，帮助分析局面的容错率。

内置 XOT 随机开局生成器，用于残局推演训练。

Phase 4：移动端特化与性能控制

实现设备温控与电量管理 API 的接入，在设备过热时自动降低引擎的并发线程数。