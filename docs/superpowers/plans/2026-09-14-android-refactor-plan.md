# [历史参考] BD2 ToolsBox Android 重构盘点与阶段总览（2026-09-14）

> **已停用，仅供历史参考。** 唯一有效执行清单是 [2026-09-15 交接修订版 TODO](<D:/bd2 mod/bdroid_src/docs/superpowers/plans/2026-09-15-android-refactor-todo.md>)。不要执行本文件中的命令，不在这里维护进度；原复选框已转换为普通历史条目。正文保留旧时点的盘点和方案，不代表当前实施状态。


> **决策已经确认。正式执行以 [62项详细TODO](<D:/bd2 mod/bdroid_src/docs/superpowers/plans/2026-09-15-android-refactor-todo.md>) 为准。** 本文保留原始源码盘点、证据与P0–P8粗分期；详细清单使用G0–G9/T01–T62，包含新增深色模式、文件安全、发布版兼容和减包验收。

> 本轮只做盘点与方案，不实施业务代码修改。获得开始实施的明确授权后按阶段执行，每阶段独立验证、独立回退；本次文档确认不等于创建分支、改代码或发布授权。

**目标：** 引入 Hilt、版本目录和 AboutLibraries，现代化构建依赖，并从任务生命周期与职责划分上拆解 MainViewModel 等集中点，而不改变现有 MOD 安装、恢复、扫描与预览语义。

**架构：** 先在现有单模块内建立按功能分包的清晰接口（seam），让界面/ViewModel 只处理界面状态与用户意图，长任务由界面之外的任务宿主持有。依赖从构造函数进入，存储、网络、Python 与 Shizuku 实现位于可替换的接口之后；边界稳定后才按收益提取 Gradle modules。

**技术栈：** Kotlin、Jetpack Compose、Hilt/KSP、Gradle version catalog、AboutLibraries、kotlinx.serialization、Chaquopy/Python、Shizuku/AIDL。

**盘点基线：** 2026-09-14，Android 仓库 HEAD b83c1c0，开始盘点时工作区干净；当前应用为 0.2.16 / versionCode 17。未运行本轮构建或测试，不把现有测试报告当作本轮验证证据。

## 一、你的要求，转成可验收的事项

| 要求 | 计划中的具体含义 |
|---|---|
| 使用 Hilt | Application/Android 入口接入 Hilt；Repository、运行时和依赖经构造注入；去除 ViewModel 的 initialize(context) + lateinit 装配，以及有状态仓库的手工 get(context) 单例。 |
| 删除许可证目录，使用 AboutLibraries | 用生成的依赖报告与 Compose 许可证页替换手工维护入口；非 Maven 组件补自定义定义/许可原文，迁移完整后再删旧 licenses/，保留主项目 LICENSE。 |
| 根/模块版本统一提取，App版本另配 | 根脚本和模块的插件/第三方依赖版本统一进TOML，App versionName/versionCode单独配置；不额外新增根工程发布号或内部库独立发版体系。 |
| libs.versions.toml | Gradle 插件与 Maven 依赖的版本集中管理，根脚本和模块脚本只消费 aliases；不把 App 发布版本混入依赖版本目录。 |
| 更新老依赖 | 按 Kotlin/Compose/KSP/Hilt/AGP/Gradle/JDK/Chaquopy 的兼容组合升级，不逐个机械改成 latest。 |
| 移除 Gson | Kotlin 本身不内置 Gson；迁移到独立的 kotlinx.serialization 插件与 JSON runtime 后，移除项目直接 Gson 依赖及旧 R8 规则。 |
| MainViewModel 与类似问题 | 同时治理职责、状态所有权、长任务生命周期、UI 与数据层耦合，而不是只把一个大文件切成多个扩展函数文件。 |

### 已确认范围

本次主战场是 D:/bd2 mod/bdroid_src。已同时查看桌面壳 winui/Bd2Converter、converter 的入口及 engine、tools 的结构；它们不是 Android Gradle modules，不能套用 Hilt/AboutLibraries。桌面/Python API 的独立重构列为后续包，不和这次 Android 框架迁移混提交。MOD 素材、历史发布包、下载产物与诊断目录不在源码重构范围。

用户最终确认的含义是：工具/依赖版本统一管理，App自己的发布版本单独管理，构建脚本只引用不重复写死。先前额外提出的根工程发布标识、内部模块各自发版不属于本轮要求。

### 分支与集成策略（按本轮补充）

- **整轮重构在独立新分支中完成，main 保持现有发布线。** 已确认实施时使用 refactor/android-architecture 分支和独立 worktree；本次未创建或切换分支，也不将清单确认视为已批准开始实施。
- P0–P8 是重构分支内部的实施检查点，不要求每完成一阶段就合回 main。达到整体功能、旧数据、两种 ABI 和 release 验收门槛后，再决定合并。
- 新分支允许调整内部接口、包结构和状态模型，不必为了维持旧架构长期保留兼容壳；但用户数据、对外协议、文件安全和既有核心能力仍须兼容。
- 可以先建立目标工程/DI/明确模块的骨架，再按功能迁入。中间提交可以尚未接齐所有功能，不要求每个提交都可发布；关键里程碑仍须可构建、可测试、可回退，不等最后一次才验证。
- 工具链、Hilt、任务模型、功能迁移、序列化和许可证各自保留清晰提交，避免整个分支只有一次无法定位问题的大提交。main 后续必要修复显式同步，不混入无关改动。
- **Git 分支不等于独立目录。** 用户已确认采用独立 worktree，预定实施目录为 D:/bd2 mod/worktrees/bdroid-android-refactor，当前目录留在 main；T01 在明确获准开始实施后检查路径/分支冲突并创建。
- 新分支隔离的是主线风险，不会消除 Hilt/AGP/Chaquopy 的版本约束、历史 JSON 迁移和许可证原文保留要求。

## 二、现状盘点：确实不止 MainViewModel

主源码含 43 个 Kotlin 文件，约 20,320 行；Python 有 105 个文件，其中大量是 vendor/UnityPy。行数仅用来定位，不作为必须拆分的唯一标准。

| 文件 | 行数 | 已确认的职责/耦合 | 建议 |
|---|---:|---|---|
| [MainViewModel.kt](<D:/bd2 mod/bdroid_src/app/src/main/java/com/bd2toolsbox/ui/viewmodel/MainViewModel.kt>) | 4439 | 49 处 MutableStateFlow 创建，设置/目录权限/扫描/安装卸载/快照/预览/更新集中；直接持有平台 Context、文件和归档操作 | 第一优先级：先抽长任务与存储，再按功能拆 ViewModel |
| [AppDialogs.kt](<D:/bd2 mod/bdroid_src/app/src/main/java/com/bd2toolsbox/ui/dialogs/AppDialogs.kt>) | 1937 | 安装、卸载、预解包、来源目录、扫描、更新等对话框同文件；参数依赖 MainViewModel 内嵌 Plan 类型 | 对话框随 feature 归属；Plan 类型离开 ViewModel，通用样式只保留真正复用部分 |
| [ModScreen.kt](<D:/bd2 mod/bdroid_src/app/src/main/java/com/bd2toolsbox/ui/screens/ModScreen.kt>) | 1550 | 路由、状态收集、筛选/分组、卡片、异常/过期 MOD 展示同文件，整个 MainViewModel 下传 | Route 收集状态，Screen 只吃 UiState/回调；投影/分组可测，卡片与分区分开 |
| [GamekeeRepository.kt](<D:/bd2 mod/bdroid_src/app/src/main/java/com/bd2toolsbox/data/repository/GamekeeRepository.kt>) | 1398 | HTTP、目录/文章/CDK、缓存、Slate/表格/评论、HTML/CSS 和阵容逻辑混合 | 拆数据读取、缓存、纯渲染；保留刚补好的 v2/v3 行为回归 |
| [MainActivity.kt](<D:/bd2 mod/bdroid_src/app/src/main/java/com/bd2toolsbox/MainActivity.kt>) | 1023 | Android 入口同时装配主题、导航、弹窗、权限、更新广播与全局状态 | 只保留 Android launcher/receiver 和 AppHost；按 feature 挂接内容 |
| [GuideScreen.kt](<D:/bd2 mod/bdroid_src/app/src/main/java/com/bd2toolsbox/ui/screens/GuideScreen.kt>) | 806 | 分类树、文章列表、缩略图加载、WebView 阅读器在一起 | 分 Route/List/Reader；缩略图加载依赖从明确入口提供 |
| [ModRepository.kt](<D:/bd2 mod/bdroid_src/app/src/main/java/com/bd2toolsbox/data/repository/ModRepository.kt>) | 683 | SAF 发现、扫描完整性、批量解析、缓存读写 | 对外仍保持小接口，内部按发现/解析/存储拆实现，不让复杂度回流到 ViewModel |
| [CdkScreen.kt](<D:/bd2 mod/bdroid_src/app/src/main/java/com/bd2toolsbox/ui/screens/CdkScreen.kt>) | 661 | Composable 内获取两个仓库，并执行加载/单次兑换/批量兑换 | 新增 CdkViewModel，提取兑换用例；保留纯视觉 remember 状态 |
| [PrepackService.kt](<D:/bd2 mod/bdroid_src/app/src/main/java/com/bd2toolsbox/service/PrepackService.kt>) | 425 | 已承载实际长任务，但仍用 companion 中的 pendingTargets/progress 交接工作 | 保留前台宿主语义，把队列/结果与可观察状态放到统一任务接口之后 |
| [GuideViewModel.kt](<D:/bd2 mod/bdroid_src/app/src/main/java/com/bd2toolsbox/ui/viewmodel/GuideViewModel.kt>) | 335 | 职责较集中，但有 lateinit repo 和 initialize(context)，加载/错误/数据状态分散 | 做构造注入与 UiState 收口即可，不因为行数再拆一堆空壳 |
| [main_script.py](<D:/bd2 mod/bdroid_src/app/src/main/python/main_script.py>) | 777 | Kotlin 桥接入口同时含 catalog 缓存、扫描、解析、下载、恢复与校验实现 | 后期瘦入口，按现有 Python 功能模块下沉；先冻结桥接契约 |

其他：ShizukuManager 是带连接、权限与扫描标志的全局 object；ModdingService 是 Python 调用 object（并非 Android Service）。两者需要明确生命周期/接口，但不能一律加 AndroidEntryPoint。纯渲染器、无状态工具 object 不必为了 DI 强行变成可注入服务。

不做机械拆分：UnityPy/classes/generated.py（11,628 行）、第三方派生的 json_to_skel.py、maintenance_info_pb2.py、压缩 JS 均应按来源/生成器/补丁维护，不作为“巨型业务类”重写。

## 三、最重要的架构问题：任务所有权不是文件大小

1. MainViewModel:298 创建独立 installScope，:1398 执行安装流水线，:4429 的 onCleared 明确不取消它。它能继续执行，不代表它真是规范的进程级状态所有者：旧 ViewModel 会被协程保留，新 ViewModel 仍要重新找回任务状态。
2. ScanService 的说明明确“扫描循环仍在 MainViewModel，服务只负责通知与保活”；PrepackService 则已经把工作循环移入服务。三种长任务目前不是同一种所有权模型。
3. MainActivity:953 在 isFinishing 时解绑 Shizuku；是否有后台装入/扫描仍需要 Binder，应由任务所有者决定，不能仅从页面结束推断。
4. Hilt 的 Singleton 只保证单进程生命周期内的共享实例，不自动提供进程死亡恢复，也不会自动把工作移出 ViewModel。

**目标：** 页面可以销毁/重建，任务状态不挂在某个页面对象上；界面仅观察并提交命令，任务宿主负责执行、取消、互斥、清理和通知。推荐保留现有三个前台 Service 作为 Android 宿主，由它们驱动注入的 Install/Scan/Prepack 执行器；OperationCoordinator 管理任务标识、互斥和共享状态，单次任务 scope 由运行时显式收尾，不要求先把三个 Service 合并成一个。保留既有扫描 checkpoint，区分“界面重建继续”与“进程死亡安全停止/按已有 checkpoint 恢复”。本轮不额外承诺所有操作都支持任意位置断点续跑。

严禁为了“标准化”把所有 installScope 换成 viewModelScope，否则会破坏现有后台安装能力。也不默认把 20–40 分钟交互式扫描一律改成普通 WorkManager 任务。

## 四、推荐目标结构

### 4.1 先分职责，再分 Gradle modules

第一轮保留单一 :app，按下列逻辑组织包；这是职责示意，不要求一次改完所有包名：

~~~text
D:/bd2 mod/bdroid_src/app/src/main/java/com/bd2toolsbox/
  di/                    Hilt modules、qualifiers、dispatcher/application scope
  core/model/            稳定领域模型与操作结果，不依赖某个 ViewModel
  core/storage/          设置、安装账本、MOD 列表快照、各自的 schema/迁移
  core/engine/           Kotlin ↔ Python 接口与运行时 adapter
  core/fileaccess/       MOD 来源 SAF 与游戏目录 Shizuku 的不同能力接口
  core/operations/       长任务协调、可观察进度、取消和资源清理
  feature/mods/          库/筛选/选择/分组、MOD Screen 与 ViewModel
  feature/install/       安装/卸载计划、操作状态与对应对话框
  feature/preview/       预览准备、缓存管理与展示入口
  feature/tools/         解包/合并等工作台功能
  feature/settings/      设置、来源目录、关于/许可证
  feature/guides/        Wiki 目录、阅读器和纯 HTML 渲染
  feature/cdk/           CDK 列表、兑换与历史
  feature/update/        更新检查/下载状态与安装器请求
~~~

依赖方向：Screen → ViewModel → 业务用例/仓库接口 → 平台或数据 adapter。ViewModel 之间不互相注入；共享事实来自同一仓库/任务状态源。复杂流程可以有内部辅助类，不要求每个函数都包一层 UseCase。

App 级任务与配置共享实例可以 Singleton；页面筛选/选中态仍属于 ViewModel/SavedStateHandle；任务参数与工作临时目录属于单次任务，不放进永不清理的全局对象。Context 用 ApplicationContext 注入到真正需要 Android 的实现，纯计算与纯渲染不接触 Context。

### 4.2 MainViewModel 的拆分落点

- App/Settings：主题、壁纸、引导状态、来源目录和配置，从 SettingsRepository 观察。先保留现有 SharedPreferences 存储，DataStore 迁移不是引入 Hilt 的前置条件。
- ModLibraryViewModel：列表加载状态、筛选/搜索、选中项和展示投影；扫描由仓库/任务入口执行。
- 安装用例与 OperationCoordinator：安装计划、同 bundle 多 MOD 合并、基底选择、转换/移入/卸载、账本提交、失败恢复与通知。
- Preview/Tools：预览准备、预解包、静态/Spine 展示、解包/合并；界面只处理展示与权限结果。
- SnapshotStore：列表快照 DTO、读写排序/原子提交、损坏降级；不再放在 ViewModel 中，也不序列化整个 UI 模型。
- Update：版本检查、下载队列/完成状态在独立用例；系统安装器和权限 launcher 保留在 Android/UI 入口。

MainViewModel.PrepackPlan、UninstallPlan、ConvertedOverwritePlan 等类型移到对应功能模型。目标是单一职责和可测接口，不设置“为了小于某个行数必须拆”的机械门槛。

### 4.3 Hilt 的真实范围

- 新增 Bd2Application，HiltAndroidApp，并在 Manifest 指定应用类；不在 Application.onCreate 抢跑网络或重型 Python 初始化。
- MainActivity/需要注入的 Activity 和三个普通 Android Service 使用 AndroidEntryPoint。
- MainViewModel/GuideViewModel 及后续新 ViewModel 使用 HiltViewModel + Inject constructor；SavedStateHandle 由 Hilt 提供。
- 有状态仓库和 PythonRuntime/Shizuku 连接管理器经构造或 Hilt module 提供；IO dispatcher、应用级 scope、文件目录/外部配置用明确 qualifier。
- ShizukuFileService 是外部特权进程里的 IFileService.Stub，不是普通 Android Service。保持独立构造入口，通过应用内可注入的 Binder adapter 使用它，不让它访问主进程 Hilt 对象图。
- 单元测试直接构造纯类并传 fake；只有 Android 对象图集成测试才用 Hilt 测试机制，避免所有测试都启动整个 App。

## 五、版本与依赖策略

### 5.1 当前真实版本

| 项目 | 当前值/位置 |
|---|---|
| AGP / Kotlin / Chaquopy | 8.4.1 / 1.9.0 / 15.0.1，根 build.gradle.kts |
| Gradle wrapper | 8.9，gradle/wrapper/gradle-wrapper.properties |
| Java 源码目标 / Kotlin JVM target | 11 / 11，注意这不是运行 Gradle 所需的 JDK 版本 |
| Compose | compiler extension 1.5.1；BOM 2024.06.00 |
| SDK | compileSdk 34、targetSdk 34、minSdk 29 |
| 关键库 | Gson 2.11.0；Lifecycle 2.8.6；Activity Compose 1.9.2；Core 1.13.1；Shizuku 13.1.5 |
| 测试 | kotlin-test 1.9.0、MockK 1.13.8、coroutines-test 1.7.3 |
| 发布版本 | versionCode 17，APP_VERSION_NAME 环境变量可覆盖 versionName 0.2.16；根工程无显式 version |
| Python 包 | 9 个 pip install 声明均未固定版本，没有这部分可复现版本清单 |

### 5.2 单一来源，但不混淆版本语义

- gradle/libs.versions.toml：版本区分 Kotlin、serialization runtime、Hilt、KSP、AGP、Chaquopy、Compose BOM、AndroidX 与测试依赖；根 plugins 用 alias(...) apply false，:app 的 plugins/dependencies 用同一 catalog 的别名。
- Kotlin Android、Compose compiler、serialization 编译插件对齐 Kotlin 版本；kotlinx-serialization-json runtime 有自己的版本。Compose BOM 不代管 Kotlin/编译插件、Lifecycle 或 Activity。
- Gradle wrapper 仍以自己的 properties 文件为执行来源，不能宣称在 TOML 写一项就升级了 wrapper。
- Version catalog 声明 requested versions，不是完整 lockfile，也不强制最终冲突解析结果；需要检查实际依赖图，并在 P1/P2 决定启用依赖锁定/校验的范围。
- 仅 App versionName/versionCode 放单独发布配置（gradle/version.properties），保留 APP_VERSION_NAME 覆盖规则；校验 versionCode 单调增长，不在规划阶段发新版本。根/模块插件与库版本只通过 catalog 引用。
- 本轮不增加根工程发布号、内部库模块独立发布版本或 Maven 发布流水线；逻辑模块化不要求额外编一套发布号。
- Python 依赖建立单独的固定版本 requirements 文件，并记录原生/第三方 vendored 来源版本；不把它们伪装成 TOML 中可解析的 Maven 依赖。构建机 Python 与 APK 内解释器不是同一概念，当前构建脚本的“Chaquopy 15 只支持 3.8–3.11”注释不能当完整兼容依据；pyc 编译还需核对 minor 版本匹配。

升级顺序先“迁移配置但不变版本”，再升级兼容组合。compileSdk 与 targetSdk 的调整分开审视；targetSdk 改动会影响权限/前台服务行为，不能把它当普通依赖数字顺手改掉。现代工具链的候选组合与一手来源汇总另见依赖调研文档，实施前按所选组合固定版本，不使用 +、latest 或动态版本。

### 5.3 升级候选与明确的版本冲突

**下面保留历史调研候选，不是已验证可用或已锁定的清单。** 用户已确认稳定兼容优先、不机械追latest；详细TODO的T13会基于官方约束和实际探针确定组合，T14才升级，不能为凑版本改变minSdk29等已确认范围。

| 轴 | 路线 A：保留 AGP 8 的受控候选 |
|---|---|
| 构建工具 | AGP 8.11.1 / Gradle 8.13；Gradle 运行 JDK 17 |
| Kotlin / Compose compiler | Kotlin 2.2.20；org.jetbrains.kotlin.plugin.compose 2.2.20 |
| Hilt / KSP | Hilt 2.58（plugin/runtime/compiler 一致）；KSP 2.2.20-2.0.3，使用 KSP2 |
| Hilt Compose ViewModel | AndroidX Hilt 1.3.0 的 lifecycle-viewmodel-compose 接入 |
| AboutLibraries | 13.1.0，Gradle 插件与 Material3 UI 保持同一版本 |
| Python 打包 | Chaquopy 16.1.0；APK 内 Python minor、buildPython 与 wheels 单独核实/固定 |
| SDK 与源码目标 | compileSdk 36；targetSdk/minSdk 可先保持 34/29，另审平台行为及发行政策；源码 Java/JVM target 先保持 11 |
| serialization | 编译插件跟 Kotlin 2.2.20；JSON runtime 1.9.0 作为兼容候选 |
| Compose BOM | 根据新增库的 Compose 基线和最终解析图选择匹配的稳定 BOM；不能继续假定 2024.06.00 可不变 |

**为什么不直接填所有 latest：**

- Hilt Gradle 插件从 **2.59** 起要求 **AGP 9**，不能直接组合进上面的 AGP 8 路线。当前官方标记 stable 的 KSP 支持从 Dagger 2.60+/KSP 2.3.9+ 开始，因此路线 A 的 Hilt/KSP 是受控候选，不能包装成满足该最新 stable 声明的组合。
- AboutLibraries **15.2.0** 的 Material3 Android AAR 要求 **compileSdk 37**；只看 Gradle 插件允许 AGP 8.13，并不能证明整个 UI 依赖图可用于 AGP 8。
- Chaquopy 与原生 wheels 的支持范围也必须取交集；它是本项目不能无脑跳整套工具链的原因之一。
- **AGP9/新SDK作为兼容矩阵候选而非用户硬要求。** T13核对Kotlin/Compose/Hilt/KSP/AboutLibraries/Chaquopy完整支持交集；不把尚未验证的组合写成保证可用，也不因为新主版本就自动扩大业务重写范围。

版本边界、固定 tag 与 AAR 元数据来源见 [一手兼容矩阵](<D:/bd2 mod/bdroid_src/docs/research/2026-09-14-dependency-modernization.md>)。本轮没有执行 Gradle 解析、KSP 生成或 APK 构建。

### 5.4 Gson 迁移不是删除一行

目前 8 个 Kotlin 文件直接导入 Gson，同时有 org.json 混用。至少包含三种契约：

1. Wiki/CDK/更新接口的动态 JSON 树，尤其 Slate 和 v2 的 content 内嵌 JSON 字符串。
2. 安装账本、名称/扫描缓存等持久化结构，必须读旧数据。
3. MainViewModel 列表快照：自定义 Uri TypeAdapter、按字段名排除 resolvedTargets/unresolvedFiles/convertedDataUri，以及旧数据 null 修复。

先定义持久化/网络 DTO 与 Android 模型映射；Uri 存字符串，字段名显式稳定；动态正文使用 kotlinx.serialization.json 的 JsonElement/JsonObject，不强迫整棵 Slate 套固定 DTO。对缺失值、null、未知枚举、数字/字符串互换、未知字段分别做兼容测试，不全局开启宽松模式掩盖损坏数据。

迁移期间允许两个库短期并存。确认 app 主代码与测试无 Gson 使用、旧数据迁移通过后，再删直接依赖和对应 R8 keep；用 dependencyInsight 区分直接引用与第三方传递依赖，不能为追求 APK 零 Gson 强行 exclude 别人需要的依赖。

## 六、AboutLibraries 与删除许可证目录的门槛

已有 licenses/ 含 UnityPy、ReDustX、astc-encoder、texture2ddecoder、PixiJS 五份许可。它们通过 Python/vendor、jniLibs 或 assets 进入分发，不是 Maven 图里的普通依赖。现有 THIRD-PARTY-NOTICES 还把 Gson 写成 2.10.1，与实际 2.11.0 已发生漂移，适合改为生成式维护。

执行顺序：

1. 加入 AboutLibraries Gradle 插件与 Compose Material3 展示库，接到 WorkbenchSheet 的“关于 → 开源许可证”入口。
2. 从实际构建的依赖生成报告，确认传递依赖、license 标识、作者、原文/链接和离线展示。
3. 为 Python、vendored、JNI、WebView 资源补自定义定义及必须保留的许可全文；从已固定的 Python 版本清单/发行元数据取版本，不能猜。
4. 检查 APK 里实际携带的依赖与报告一致；不把“Gradle 插件跑成功”当作五个非 Maven 组件已自动覆盖。
5. 迁移旧 licenses/ 的原文/版权声明到新的自定义许可资源后再删旧目录，更新 README/THIRD-PARTY-NOTICES 中的链接。
6. 保留根 LICENSE（本项目 GPLv3），保留第三方源码内版权头、必要 NOTICE/归属信息；不删除 wheel/vendor 自带的原始许可文件。
7. 保持现有 pixi-spine 运行时下载边界，不在这轮把它改为随 APK 分发，也不把 AboutLibraries 当作许可证兼容性审核工具。

## 七、阶段计划与验收

### P0 — 冻结基线与建立有效回归门槛

**产物：** 基线记录、真实行为夹具、关键工作流验收清单。

- 记录当前打包/运行环境、ABI、旧安装数据与缓存样本；保留干净基线，不先升级库。
- 运行现有 Kotlin/Python/设备测试；记录真实结果，不继承旧报告中的“通过”。
- 改造 PrepackCacheIntegrationTest：现有前几个用例只给 cacheCount=180 等常量并断言常量关系，没有调用业务模块，不能作为集成回归证据。改成穿过实际缓存/任务接口的测试。
- 对长任务完成/失败/取消、页面重建、用户退出时 Binder 使用、同 bundle 合并安装、清理/账本/快照建立可重复测试。
- 保留已有 Wiki v2、MOD 发现、缓存分槽、扫描 checkpoint 等有效测试；反射 MainViewModel 私有字段的快照测试逐步改为测试 SnapshotStore 的公开接口。

**验收：** 已知关键行为由真实生产路径测试约束；给测试中的生产行为故意引入错误时，对应测试必须失败。测试不能只重复硬编码样例。

### P1 — 版本治理，先不升级

**文件：** 新增 gradle/libs.versions.toml、发布版本配置；修改根/模块 Gradle 脚本与 wrapper 相关说明。

- 把已有插件/依赖的当前版本搬入 catalog，暂不改变解析结果。
- 根/模块的插件与依赖版本统一目录管理，App发布版本另配；统一Compose BOM和Kotlin test版本来源，不新增根工程发布号。
- 清点直接使用却仅靠传递关系获得的依赖，显式声明真正使用的库；对齐 coroutines runtime/test 来源。
- 固定 Python 包版本与来源，不修改第三方算法代码。

**验收：** 与基线依赖解析一致，debug/release 构建不因配置重排改变 ABI/applicationId/签名规则；业务代码无变化。

### P2 — 工具链与依赖兼容升级

- 确定有官方支持交集的 Kotlin 2.x、Compose compiler plugin、KSP、AGP/Gradle/JDK、Chaquopy 组合并固定版本。
- Kotlin 2.x 下使用 Compose compiler plugin，移除旧 kotlinCompilerExtensionVersion=1.5.1 绑定方式；同步测试编译依赖。
- 逐组更新 AndroidX/Compose 等库，依赖升级与业务拆分类改动不混合。
- 核对两个 ABI 的 Chaquopy wheels、vendored UnityPy 与 JNI 库；针对目标平台验证原生加载与页大小兼容性。
- targetSdk 的行为升级另列验证：SAF/全部文件权限、前台服务、通知、下载完成广播和 APK 安装授权。

**验收：** 原功能回归通过后才进入架构迁移；release R8、Python 启动和 arm64-v8a/x86_64 实测不能被 JVM 单测替代。

### P3 — Hilt 引入与手工装配退场

**文件范围：** 新 Application/di 包、Manifest、现有 ViewModel、仓库、Activity/普通 Service 入口；不改变 AIDL 协议。

- 配置 Hilt + KSP 与测试入口；明确 ApplicationContext、dispatcher、任务 scope 的 qualifiers。
- 先迁移 Settings/仓库/PythonRuntime/Binder adapter，再切换 ViewModel 构造注入；去掉 initialize(context) 的顺序依赖。
- 移除被替代的有状态 get(context) 单例与调用点，不保留两套互相竞争的实例源。
- 以 fake 仓库/运行时做单元测试；对普通 Service 注入与独立 Shizuku user service 分别验证。

**验收：** 依赖由构造声明，UI 不负责 new 仓库；进程内共享缓存和 Python/Binder 连接只保留预期的一份；重型初始化不阻塞首屏。

### P4 — AboutLibraries（P2 后可独立推进）

- 接入生成报告与关于页许可证入口。
- 完成五个非 Maven 组件和 Python/native/WebView 的自定义元数据/原文迁移。
- 更新文档引用后删除旧手工 licenses/ 目录，保留根 LICENSE 与必要 NOTICE。

**验收：** 许可证页可离线查看；实际随包与运行时下载组件有准确标识；没有悬空文档链接或许可原文丢失。

### P5 — 长任务和存储先离开 MainViewModel

- 抽出任务协调/状态接口，将扫描、安装、预解包的所有权统一到界面之外；ViewModel 观察状态，不持有必须跨页面运行的 worker。
- 抽出 MOD 来源访问、游戏目录访问、Python 转换接口，保留各自权限模型。
- 抽出 SettingsRepository、SnapshotStore、安装账本、预览缓存；不同事实来源与缓存不混为一个万能 Store。
- 任务与缓存继续保留互斥、同 bundle 合并、原版基底检查、失败/取消清理与原子写入规则。
- 页面退出时是否解绑 Shizuku，由活动任务状态和明确资源所有权决定。

**验收：** 重建/关闭页面不使后台工作丢状态或被误取消；进程死亡按既有 checkpoint 或安全失败处理；任务结束后不再靠长期保留旧 ViewModel 维持运行。

### P6 — 按 feature 拆 UI/ViewModel 与巨型 Repository

- 提取 ModLibrary/Settings/Install/Preview-Tools/Update 职责；已有 GuideViewModel 保持聚焦，新增 CdkViewModel。
- AppDialogs 随 feature 归位；MainActivity 收敛成平台入口与 AppHost；Screen 不再直接依赖巨型 ViewModel。
- UiState 按功能组合，允许纯视觉 remember；需要随页面活跃度收集的流使用 lifecycle-aware 收集。
- Gamekee 数据读取/缓存与纯渲染分离，ModRepository 内部发现/解析/缓存分离；保留现有公开行为。
- Python main_script 只做契约适配，已有算法模块不跟着 UI 迁移一起重写。

**验收：** 变更一个功能通常只触达自己的 feature 和相关深模块；不是把复杂度拆散到多个静态 helper 或把 MainViewModel 变成几十个透传方法。

### P7 — JSON/持久化协议迁移，移除 Gson

- 引入 serialization 插件和 JSON runtime，先补旧 JSON 样本兼容测试。
- 动态网络树与固定持久化 DTO 分开迁移，保留 Wiki 双版本阵容和嵌套 JSON 内容。
- Uri、字段排除、null/未知枚举与 schema version 明确处理；账本与 UI 快照分别设迁移策略。
- 确认零直接引用后删除 Gson 与相应 R8 规则，检查依赖树和 release 产物。

**验收：** 老用户的安装记录、目录、别名/隐藏状态和有效缓存不丢失；损坏缓存按已有策略降级；debug 与 minified release 都能解析新旧夹具。

### P8 — 再判断是否物理多模块化

候选为 :core:model、:core:engine、:core:data、确有独立收益的 :feature:*，以及最终组装的 :app。不是预先规定必须全部创建。

- 根据已经形成的真实依赖方向提取模块，不让 feature 互相环依赖。
- Hilt composition root 留在应用装配层；纯模型/算法模块不引入 Android/Compose/Hilt。
- 同一 APK 只允许一个模块实际应用 Chaquopy 插件（根 apply false 声明不算）；若把 Python 打包职责迁到 :core:engine，就同时从 :app 移除实际应用/打包配置，不能两边重复启用。
- 多模块产生实际共享构建规则后才引入 convention plugins/build-logic，不为单模块提前搭大型构建框架。

**验收：** 模块提供测试隔离、依赖约束或构建收益，删除某个接口抽象后复杂度会回流到多个调用方，才说明它值得保留。

## 八、必须守住的行为

- 扫描 MOD 来源目录 ≠ 扫描游戏 bundle 索引 ≠ 预解包，三者入口与完成反馈不能混用。
- 同一 bundle 的多个 MOD 要合并已安装项；备份/游戏干净原版/CDN 基底选择不能退化。
- SD/HD、缓存分槽、日期/好感剧情、跨 bundle 解析与扫描 checkpoint 回归继续保留。
- 安装账本是事实记录，MOD 列表快照只是可失效的展示缓存，不可为了迁移简单一起清掉。
- 取消/失败不得误删来源目录、备份或留下半份目标 __data；不把危险文件操作散回 Composable。
- Wiki v2/v3 的接口、缓存隔离、回合/站位、离线阅读与原文跳转行为保持。
- applicationId、签名、FileProvider authority、AIDL 接口和 Shizuku 组件身份不在结构整理中顺手更名。
- arm64-v8a 与 x86_64 都要验证，不能只以桌面 JVM 的通过为交付依据。

## 九、验证方式与实施节奏

计划中的主要命令（本轮未执行）：

~~~text
# 工作目录：D:/bd2 mod/bdroid_src
.gradlew.bat :app:testDebugUnitTest :app:assembleDebug
.gradlew.bat :app:connectedDebugAndroidTest
.gradlew.bat :app:dependencyInsight --dependency gson --configuration debugRuntimeClasspath
~~~

另跑仓库现有 Python 测试，并针对 API 29/34/升级后的目标平台、真实 arm64 设备和 x86_64 模拟器做关键用户路径验收。Release 混淆/签名验证使用既有安全凭据流程，不把凭据写进版本目录或仓库。

推荐每个阶段只改变一个轴：先配置管理，再工具链，再 DI，再任务/功能拆分，再序列化。P4 许可证迁移可在 P2 后作为独立变更，不必等待所有大类拆完。

**推荐在同一独立重构分支内先实施 P0–P3，P4 可独立推进；P5–P8 在真实接口与测试形成后继续迁移。** 中间阶段不必合回 main 或对外发版，但关键里程碑必须可验证；全部验收通过后再决定合并主线。

## 十、不推荐的两种极端

- 只贴 Hilt 注解、只搬 TOML：能改善装配与版本漂移，但解决不了长任务挂在 ViewModel、UI 执行业务和快照协议耦合。
- 一次性重写 + 全部升最新版 + 全量多模块：同时引入编译器、生成代码、Python/JNI、生命周期和数据迁移风险，失败后很难定位或回退。

不默认追加 Room、Retrofit/Ktor、DataStore、WorkManager 或新的导航框架。只有对应的实际问题证明它们有收益，才单独提出选择，避免用更多库代替设计。

## 依据与说明

- 所有“当前版本/行数/现有行为”来自上述基线源码与构建文件，而非旧会话印象。
- 依赖与框架的一手资料整理到 [依赖现代化调研](<D:/bd2 mod/bdroid_src/docs/research/2026-09-14-dependency-modernization.md>)，用于确定实施时的兼容组合。
- 本文是供用户审阅的设计级 plan，不是已经实施的结果；批准后再把当前阶段拆成具体改动与失败测试任务。

## 补充：框架重构之外的质量门槛

只读审查发现了 ZIP 输出路径、游戏文件/账本原子写入、原版可信度、异步请求回写、扫描错误协议与后台服务资源所有权等独立问题，详见 [补充问题清单](<D:/bd2 mod/bdroid_src/docs/research/2026-09-14-additional-findings.md>)。这些不能靠 Hilt 或换 JSON 库自动修复，建议纳入 P0 的真实回归与新分支安全门槛，再分别安排小范围修复；目前未修改生产代码或运行故障注入，未创建/切换分支。

## 补充：R8 与包体优化专项

已审计现成0.2.16发布APK，详情见 [APK/R8包体报告](<D:/bd2 mod/bdroid_src/docs/research/2026-09-14-apk-size-audit.md>) 与 [测量数据](<D:/bd2 mod/bdroid_src/docs/research/2026-09-14-apk-size-inventory.json>)。arm64 release为32.516MiB，DEX仅2.744MiB，Python/native占约89%。现成包已执行R8，不能把“开启R8”当作尚未实现的主要收益。

新分支另设包体检查点：先修发布版快照序列化/keep契约，再处理ABI flavors、Python归档内桌面FMOD二进制、native strip，之后精修R8与资源收缩；约11.809MiB是当前候选内容池，不是保证净节省。AGP8.11.1不具备8.12引入的新资源优化管线，不为此单项破坏兼容矩阵。不要把R8全包keep盲删、压缩native增加安装后占用、或pyc必然减包混为一个无风险开关。上述均未实施、未重建验证。

## 补充：UI 保留技术栈，优先交互与状态边界

见 [UI 评审与库选型](<D:/bd2 mod/bdroid_src/docs/research/2026-09-14-ui-review-and-library-options.md>)。继续使用 Compose + Material 3，不默认更换 UI 框架，也不因开启重构分支就强制增加导航库。

已确认的 UI 设计基础：**保留自定义壁纸，关键内容区默认不透明，可读性优先于背景装饰。** 具体页面版式、壁纸展示范围和参数仍待细化；其他库选型继续按需评估。下列为实现检查点，均尚未实施：
- U1：页面边界随 P3/P5 整理：Route 负责获取 ViewModel／生命周期采集，Screen 接收 UiState 与事件；从 MainActivity、ModScreen 和 AppDialogs 拆出有明确职责的页面／组件，而非仅移动大文件。
- U2：优先修复处理态无法收起、兑换成功与处理完毕混标、预览入口不明确、清空原版备份无确认；收起不等于取消，后台任务所有权按前序任务模型设计。
- U3：评估 Coil 统一头像／缩略图加载，保留 Referer、缓存键和离线协议；补 lifecycle-runtime-compose。导航、Material3 Adaptive 按真实详情页／大屏需求选择，不同时堆叠多套方案。具体版本与原兼容矩阵一起验证。
- U4：按已确认的“保留壁纸、关键内容区默认不透明”原则重做背景与内容分层，不沿用旧壁纸风格。拟取消全局 surface 透明、统一内容容器及状态／控件颜色，具体页面布局和参数再细化；“角色主题”并非现有功能，不作为需求。Expressive 等视觉升级后置。
- U5：利用已有 Compose UI Test 依赖补真实交互测试，覆盖备份确认、预览入口、成功／失败结果与任务收起；用亮色、暗色、高对比／高纹理壁纸和全部现有配色验证内容可读性，并检查大字体、TalkBack、Insets／返回手势。当前源码仅浅色主题；用户已确认本轮新增浅色/深色/跟随系统，必须覆盖这三种模式及各预设/壁纸组合。

本轮 UI 检查是源码级评审，旧截图与当前导航不一致，网页 detector 对 Kotlin 无有效覆盖，不能宣称视觉／性能验收通过。UI 不是前序 APK 包体中的主要优化杠杆，不将换框架、增加特效库或引入 Coil 说成必然减包。

## 最终确认补充

- 保留Android10+，分别发布arm64/x86_64 APK，不默认universal，不新增Google Play/AAB发行流程。
- 减包以清理冗余为限，尽量不改变功能、原有离线能力和使用体验；不为体积增加首次下载核心组件。
- 后台任务支持切页/旋转/退后台继续，进程死亡安全收尾与核对，沿用扫描checkpoint，不承诺全流程断点续跑。
- 手机横竖屏/大字体/大屏基本可用，本轮不开发专门的平板双栏、折叠屏独立布局。
- 所有实施任务、文件与验收细节见[正式TODO](<D:/bd2 mod/bdroid_src/docs/superpowers/plans/2026-09-15-android-refactor-todo.md>)；本文历史盘点不代表已修复或已测试。

## 补充：MOD目录的媒体扫描隔离

用户补充反馈：解压后的MOD贴图会被系统扫描并显示在相册。建议采用Android .nomedia机制，不改变贴图或MOD路径；应用新建的专用目录在输出图片前设置，已有来源目录需确认范围，禁止默认保护整个下载/图片/相机根目录。已有相册条目需区分媒体索引、缓存与云备份，不能通过删除图片或MediaStore删除来清理。MOD枚举、来源签名和Python打包也要忽略该marker，防止假变更。详见[详细TODO的T62](<D:/bd2 mod/bdroid_src/docs/superpowers/plans/2026-09-15-android-refactor-todo.md>)；此项未实施，未操作用户手机或真实目录。
