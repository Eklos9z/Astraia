claude.md: Android 黑白棋高阶复盘应用开发指南
1. 项目概述 (Project Overview)
本项目旨在开发一款顶级的 Android 端黑白棋（Othello）复盘与分析应用程序。核心引擎基于开源的 Edax 引擎构建，该引擎采用 C 语言编写，具备极高的位棋盘（Bitboard）并发计算效率与精确的中盘评估能力。
本应用在保留 Edax 强悍算力的基础上，融合了当前主流黑白棋软件的核心优势：Kifubox 的物理环境交互与复盘专注度、Egaroucid 的极致性能与现代训练流，以及 Othello Sensei 的非阻塞交互与算力分配逻辑，致力于打造 Android 生态中最专业的便携式黑白棋工作台。  

2. 核心架构与扩展性设计 (Architecture & Extensibility)
为了保证 App 具备极强的后续扩展能力（如未来可能替换更强的神经网络评估函数，或接入联机对战平台），项目必须严格遵循高内聚、低耦合的架构原则：

UI 呈现层：使用 Kotlin 与 Jetpack Compose 声明式框架，确保极高的数据刷新帧率。

业务逻辑层 (ViewModel / UseCases)：采用 MVVM 架构或 Clean Architecture。将棋谱解析（支持文本串、CSV 等格式）、数据库检索与引擎调度逻辑彻底分离。

底层引擎通信层 (JNI/NDK)：Edax 引擎是基于 C 语言编写的。必须通过 Android NDK 构建 CMakeLists.txt 将其编译为 .so 动态链接库。JNI 层仅负责暴露最基础的接口（如：初始化盘面、发送计算指令、中断搜索、获取实时评估数据），绝对不处理任何 Android 业务逻辑。  

3. 核心功能需求模块 (Core Features Roadmap)
3.1 引擎驱动与状态反馈 (融合 Othello Sensei)
非阻塞连续更新流：彻底摒弃传统的“等待进度条”。底层引擎在进行多线程搜索时，必须通过异步回调或 Kotlin Flow，持续（毫秒级）向主线程发送当前候选点的评估石差、搜索节点总数及置信区间预判。

节流控制 (Throttling)：UI 层应对连续吐出的引擎数据流进行节流处理（如设置 100ms-200ms 的刷新间隔），以防止 Android 主线程渲染阻塞。

自定义算力倾斜：允许在设置中配置引擎参数，实现对“劣势分支”和“优势分支”的非对称算力分配，在移动端有限算力下追求最高效的推演。

3.2 视觉与交互体验 (融合 Kifubox & Wzebra)
物理盘面数字桥接 (OCR)：后续扩展的重要模块。预留 Android CameraX API 接口与本地 ML Kit 视觉识别模块，实现对线下实体比赛棋盘的拍照识别与一键导入功能。

容错率可视化：在局势图表中不仅绘制传统的“胜率折线图”，还需引入“海龟数”（Umigame numbers）指标，量化并标识当前局面下能够保持胜势的候选点数量。

着法视觉投影 (Move Preview)：用户手指长按合法候选点时，利用半透明遮罩在棋盘上即时渲染该步落子后的翻转结果，减轻玩家脑内推演的负担。

3.3 数据库与现代训练 (融合 Egaroucid & Wzebra)
端侧历史棋谱库：支持无缝集成和挂载压缩版的 WThor 赛事数据库。当玩家复盘时，系统能并发展示历史职业选手在同一局面的实战胜率与分支选择。

XOT 随机开局生成：内置 XOT 残局库或算法，支持一键生成滤除极端劣势的随机中盘局面，供用户进行纯计算能力的抗遗忘训练。

4. Claude Code 辅助开发守则 (AI Coding Guidelines)
JNI / C 跨语言边界：

在修改 Edax 的 C 源码时，注意剔除其中针对桌面端特有的不兼容特性或多余输出，避免标准输出（stdout/stderr）导致 Android 缓冲区溢出。  

优化 Makefile / CMake 以适配 ARM64-v8a 架构。尽可能利用 ARM NEON 向量指令集代替原先 x86 架构下的 SSE/AVX 优化。  

移动端温控约束 (Thermal Management)：

编写引擎管理服务时，必须考虑到移动设备的电池与发热墙。

要求实现基于 Android PowerManager 或热量 API 的动态降级策略：当检测到设备过热或电量低于 20% 时，强制降低引擎搜索的并发线程数（如从 8 线程降至 2 线程），或限制其搜索深度（如锁定前 40 步不进入极深算尽模式）。

内存管理 (Memory Leak Prevention)：

Edax 使用无锁哈希表（Lock-free hash table）进行大规模搜索。在 JNI 层进行实例销毁时，务必显式调用 C 层的清理函数释放内存，防止 Android 应用因 Native 层内存泄漏而遭遇 OOM 崩溃。  

保持代码的独立性与可插拔性：

对于 OCR 识别、WThor 数据库引擎等高级功能，应定义清晰的 Interface 接口。即使 MVP（最小可行性产品）版本暂时不实现它们，也要在依赖注入（DI）容器中预留好位置。