# [已停用] BD2 ToolsBox Android 重构详细 TODO（2026-09-14 历史稿）

> **已停用，仅供历史参考。** 唯一有效执行清单是 [2026-09-15 交接修订版 TODO](<D:/bd2 mod/bdroid_src/docs/superpowers/plans/2026-09-15-android-refactor-todo.md>)。不要执行本文件中的命令，不在这里维护进度；原复选框已转换为普通历史条目。正文保留旧时点的盘点和方案，不代表当前实施状态。


> **面向执行者：** 按 executing-plans 的逐任务验证方式执行；只有另行获得授权才采用并行子代理执行。使用复选框跟踪，每个任务独立留证据。此文档本身不授权创建 worktree、修改业务代码、安装到设备、推送或发布。

**目标：** 在保留现有核心功能、Android 10+ 与两种 ABI 的前提下，完成 Hilt、版本治理、依赖现代化、职责/生命周期重构、数据安全、JSON/许可证迁移、UI 可读性与冗余清理。

**架构：** 第一轮保持一个实际应用模块，内部按 core/feature 建立深模块与可测试 interface。UI 观察状态并提交意图，Android Service 驱动长任务 executor，数据事实由统一 Store/Repository 持有；Python、Shizuku、网络与文件访问位于可替换 adapter 后。

**技术栈：** Kotlin 2.x、Compose/Material 3、Hilt/KSP、Gradle Version Catalog、kotlinx.serialization、AboutLibraries、Chaquopy/Python、Shizuku/AIDL；Coil 为图片层首选验证方案，不强上新导航/存储/网络全家桶。

**状态：** 用户已确认需求边界；本清单共 **62 个主任务、10 个阶段**，实现项全部未执行。文档自检不等于构建、测试或重构完成。

---

## 1. 已确认决策：执行时不得擅自扩大或缩小

| 原编号 | 已确认内容 | 对应任务 |
|---|---|---|
| 1 | Android 项目及其必要 Python/JNI 接口和打包；不同时重写 Windows 桌面端/独立转换器，不处理 MOD 素材和历史产物目录 | T18、T29、T50、T55 |
| 2 | 根脚本与模块里的插件/第三方库版本统一进 TOML；App versionName/versionCode 独立配置；**不新增根工程发布号或内部模块独立发版体系** | T11、T14、T61 |
| 3 | 稳定兼容优先，尽量现代化，不机械追所有 latest；确切版本由兼容验证决定 | T12–T15 |
| 4 | 保留 minSdk29 / Android10+，compileSdk 与 targetSdk 分别核对 | T15、T57 |
| 5 | 直接分发 arm64 与 x86_64 两个独立 APK；不默认发布 universal，不新增 Google Play/AAB 发行流程 | T32、T49、T54、T61 |
| 6 | 新增 LIGHT/DARK/SYSTEM 三种主题模式并做壁纸/状态可读性验证；旧设置缺新键时默认 LIGHT | T27、T39、T47、T58 |
| 7 | 手机横竖屏、大字体与大屏基本可用；不开发专用平板双栏/折叠屏独立布局 | T46、T58 |
| 8 | 缩包仅以清理冗余为目标，尽量不改变使用体验；不砍核心功能、不取消 ABI、不新增首次联网获取核心组件的要求 | T48–T54、T59 |
| 9 | 切页/旋转/退后台任务可继续；进程死亡保证文件安全、状态可核对，沿用已有扫描 checkpoint，不承诺所有步骤任意断点续跑 | T21–T26、T43 |
| 10 | 实施时用独立 worktree 和 refactor/android-architecture 分支；原目录留 main；本次只写计划 | T01、T61 |

另有已确认 UI 原则：**保留自定义壁纸，关键内容区默认不透明，可读性优先于装饰**；不要求保留旧壁纸表现。“角色主题”不是现有功能，不能据此增加角色专属主题系统。

Hilt、AboutLibraries、版本目录、移除 App Gson、巨型类治理和已发现安全/正确性问题均属于本轮目标。文件安全、用户数据迁移和许可证合规不可为缩包让步。具体 UI 布局由这些原则约束，截图验收是任务的一部分，而不是重新让用户选择框架。

## 2. 基线、证据与工作区规则

- 方案基线：2026-09-14，main / b83c1c0；App 0.2.16 / versionCode17；AGP8.4.1、Kotlin1.9.0、Gradle8.9、Chaquopy15.0.1。执行开始时再次核对，出现主线变化先记录差异。
- 现成 arm64 release 为32.516MiB，全部 DEX2.744MiB、Android资源0.417MiB，Python/native 约89%；R8 已运行。约11.809MiB是候选冗余池，**不是承诺净节省**。DEX/资源不是 Compose 单独体积。
- 原源码/证据目录：[D:/bd2 mod/bdroid_src](<D:/bd2 mod/bdroid_src>)。规划文档位于此处，生产代码本轮未改。
- 预定实施目录：[D:/bd2 mod/worktrees/bdroid-android-refactor](<D:/bd2 mod/worktrees/bdroid-android-refactor>)。**它现在尚未创建**；以下任务文件链接均指此工作区的现有文件副本或规划新增文件，不是要求去 main 修改。未创建前链接可能不存在。
- 初始 docs 尚未跟踪，worktree 不会自动携带；T01 显式携带已确认规划。禁止覆盖用户其他未提交内容，禁止自动 reset --hard、clean、强推或递归删除工作区。
- 各任务证据文件统一为实施目录下 docs/refactor/evidence/Txx.md。至少记录基点/提交、环境、输入、精确命令、退出码、修复前后结果、产物/截图哈希与尚未覆盖的条件。原始设备配置、凭据和未脱敏数据不提交。

### 依据文件

- [原始盘点与粗阶段](<D:/bd2 mod/bdroid_src/docs/superpowers/plans/2026-09-14-android-refactor-plan.md>)
- [依赖兼容研究](<D:/bd2 mod/bdroid_src/docs/research/2026-09-14-dependency-modernization.md>)
- [额外正确性问题](<D:/bd2 mod/bdroid_src/docs/research/2026-09-14-additional-findings.md>)
- [R8/包体实测](<D:/bd2 mod/bdroid_src/docs/research/2026-09-14-apk-size-audit.md>) 与 [数值清单](<D:/bd2 mod/bdroid_src/docs/research/2026-09-14-apk-size-inventory.json>)
- [平台约束](<D:/bd2 mod/bdroid_src/docs/research/2026-09-14-r8-platform-constraints.md>)
- [UI 评审及已确认壁纸原则](<D:/bd2 mod/bdroid_src/docs/research/2026-09-14-ui-review-and-library-options.md>)

这些文档中的历史版本组合是候选，不替代 T13 的真实构建验证；旧截图和网页 detector 也不是当前原生 UI 的通过证据。

## 3. 执行顺序与完成门槛

**默认顺序为 G0 → G1 → G2 → G3 → G4 → G5 → G6 → G7 → G8 → G9。** 单个任务以前置依赖为准；只有写范围清楚、无需等待对方输出且另行获准时才并行。不能等所有代码改完才首次构建。

| 阶段 | 任务 | 数量 | 阶段完成条件 |
|---|---|---:|---|
| G0 基线 | T01–T04 | 4 | 工作区/环境和数据基线可追溯，已知失败有明确归属，测试能真实检出问题 |
| G1 安全护栏 | T05–T10 | 6 | 越界、半写、账本丢失、假原版与 release 快照风险得到针对性验证 |
| G2 构建 | T11–T15 | 5 | TOML/发布版本分开，Python固定，新工具链真实构建与平台行为通过 |
| G3 DI | T16–T20 | 5 | 真实入口接通，唯一事实源，Python/Shizuku 不误用进程/Context |
| G4 任务 | T21–T26 | 6 | 页面退出不终止工作，取消/进程死/连接断开有安全结果 |
| G5 功能分解 | T27–T34、T62 | 9 | 大类职责有唯一落点，竞态修复，UI 不执行业务，图片缓存受控 |
| G6 数据/许可 | T35–T38 | 4 | 旧数据可迁移、App不带Gson、许可完整再删旧目录 |
| G7 UI | T39–T47 | 9 | 明暗/系统主题、壁纸分层和关键交互在原生界面可用 |
| G8 减包 | T48–T54 | 7 | 两个单ABI包，清理项可解释，无功能/离线能力损失，收益实测 |
| G9 验收 | T55–T61 | 7 | 核心路径、真实release、平台、UI、性能、复现及交付证据齐全 |

- **P0：** 数据安全、兼容或发布阻断项；不通过不得进入相关后续任务/发布。
- **P1：** 本轮必须完成的结构与体验项，不是可随意跳过的建议。
- 每个实现任务执行“写针对性失败测试/保存基线 → 运行确认 → 最小实现 → 运行通过 → 独立审阅/记录 → 清晰提交”。G0 允许如实记录已有红灯，但不能把它们标为通过；映射到后续任务修复。
- 不以文件行数、贴完注解、Gradle sync 成功或测试数量作为重构完成标准。测试代码必须调用生产 interface/流程，fake 仅隔离外部边界。
- 回退代码和恢复数据是不同工作。迁移前原件/journal 保留；不得用旧账本覆盖之后新发生的安装事实。

## 4. 目标文件职责

完整写集见每个任务；以下列出主要落点（均为规划的实施路径）：

| 职责 | 主要落点 |
|---|---|
| 应用与对象图 | [Bd2Application.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/Bd2Application.kt>)、[di](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/di>) |
| 领域计划与结果 | [core/model](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/core/model>) |
| 文件能力与原子写入 | [core/fileaccess](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/core/fileaccess>) |
| 配置/账本/快照/事务日志 | [core/storage](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/core/storage>) |
| Python 引擎与图片加载 | [core/engine](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/core/engine>)、[core/images](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/core/images>) |
| 任务协调与恢复 | [core/operations](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/core/operations>) |
| 各功能的 ViewModel/Route/用例 | [feature](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/feature>) 下 mods/install/preview/tools/settings/guides/cdk/update |
| 共享主题和交互组件 | [ui/theme](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/theme>)、[ui/components](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/components>) |
| Android 宿主与特权 Binder | [service](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/service>)；组件身份不随结构整理改名 |

依赖方向：Screen → ViewModel → 用例/Repository interface → 平台或数据 adapter。ViewModel 不互相注入；后台工作不依附界面。模块是逻辑边界，不等于必须创建更多 Gradle 项目；T55 记录物理拆分收益，本轮不强制空模块化。

## 5. 逐项实施清单

> 所有复选框为空表示未执行。每项的命令约定和 release case 含义见第6节；它们是未来执行命令，不是本轮已运行的验证。
## G0 — 执行隔离与可信基线

### T01 — 在明确获准执行后建立隔离工作区

- **历史任务 T01（原稿未实施，已停止维护）**

**优先级：** P0　**前置：** 无（必须另获实施授权）

**文件范围（实施工作区）：**
- 读取：[docs/superpowers/plans/2026-09-14-android-refactor-todo.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/superpowers/plans/2026-09-14-android-refactor-todo.md>)
- 新增：[docs/refactor/evidence/README.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/README.md>)

**落实要求：**
- 仅在用户另行授权开始实施后，核对 main 的 HEAD、未提交文件和现有 worktree，再创建 refactor/android-architecture；本次写清单不执行这些操作。
- 实施目录固定为 D:/bd2 mod/worktrees/bdroid-android-refactor；目录或分支已存在时停止核对，不覆盖、不删除、不自动换基点。当前 main 目录继续保留。
- 将已确认规划文档显式复制到实施目录并纳入后续文档提交；注意当前 docs 尚未跟踪，git worktree 不会自动携带这些文件。只在新分支整理自己的提交。

**验收与回归：**
- 原目录分支仍为 main；新目录分支和记录的起点一致；两处 git status 可区分，main 的生产代码无变化。
- 模拟目标目录或同名分支已存在，流程应停止，不执行删除、reset、clean 或覆盖。

**执行/验证命令：**
~~~text
$Repo = 'D:/bd2 mod/bdroid_src'
$Target = 'D:/bd2 mod/worktrees/bdroid-android-refactor'
$Branch = 'refactor/android-architecture'
$ResolvedTarget = [IO.Path]::GetFullPath($Target)
$AllowedParent = [IO.Path]::GetFullPath('D:/bd2 mod/worktrees').TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
if (-not $ResolvedTarget.StartsWith($AllowedParent, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unexpected worktree location' }
if (Test-Path -LiteralPath $ResolvedTarget) { throw 'Target already exists; inspect, do not overwrite' }
$CurrentBranch = (git -c safe.directory=$Repo -C $Repo branch --show-current) -join ''
if ($LASTEXITCODE -ne 0 -or $CurrentBranch.Trim() -ne 'main') { throw 'Verify the source checkout before proceeding' }
$ExistingBranch = (git -c safe.directory=$Repo -C $Repo branch --list $Branch) -join ''
if ($LASTEXITCODE -ne 0 -or -not [string]::IsNullOrWhiteSpace($ExistingBranch)) { throw 'Branch check failed or branch already exists' }
git -c safe.directory=$Repo -C $Repo status --short --branch
if ($LASTEXITCODE -ne 0) { throw 'Source status failed' }
git -c safe.directory=$Repo -C $Repo worktree list
if ($LASTEXITCODE -ne 0) { throw 'Worktree inventory failed' }
# 仅在完成未提交工作审查、已获准开始实施后运行下一条；不在写计划时执行。
git -c safe.directory=$Repo -C $Repo worktree add -b $Branch $ResolvedTarget main
if ($LASTEXITCODE -ne 0) { throw 'Worktree creation failed; do not clean or reset anything automatically' }
~~~

**完成证据：** [T01.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T01.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 不自动删除 worktree；保存未提交工作并报告冲突。任何移除工作区或改写分支操作另行确认。

### T02 — 核实构建环境、签名身份及可复现的原版构建

- **历史任务 T02（原稿未实施，已停止维护）**

**优先级：** P0　**前置：** T01

**文件范围（实施工作区）：**
- 读取：[gradle/wrapper/gradle-wrapper.properties](<D:/bd2 mod/worktrees/bdroid-android-refactor/gradle/wrapper/gradle-wrapper.properties>)
- 读取：[local.properties](<D:/bd2 mod/worktrees/bdroid-android-refactor/local.properties>)
- 读取：[app/build.gradle.kts](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/build.gradle.kts>)
- 新增：[docs/refactor/evidence/environment.local.json](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/environment.local.json>)

**落实要求：**
- 记录 Gradle 运行 JDK、Android SDK/build-tools、实际可执行的构建 Python、测试 Python、Chaquopy 目标 Python 和 ABI；不得把 py 启动器存在当成解释器可用。测试环境与 pyc 构建 minor 的用途分开记录。
- 确认 applicationId、FileProvider/Shizuku authority、现有版本号与 release 证书指纹；不把 keystore、密码、令牌或真实账号写入文档。新增 SDK/NDK、联网下载、写全局缓存按执行环境权限请求，不绕过限制。
- 先用现有工具链构建 debug/release 并运行现有测试，保存命令和原始结果；已有失败逐一分类，不能直接改测试期望或全局禁用检查来制造绿色基线。建立仅用于验收的设备清单。
- 环境清单 environment.local.json 只保存在本地并加入忽略规则，至少包含 testPythonExe、buildPythonExe、sdkDir、adbExe、验证过的 JDK 和测试设备白名单；脱敏后的版本/哈希结论另存可提交文档。

**验收与回归：**
- 环境记录中的解释器可实际输出版本；Gradle 使用预期 JDK；构建产物 applicationId、versionCode 与签名策略可核对。
- 对缺少 SDK、Python 或签名配置，输出具体缺项并停在该门槛，不声称构建成功。

**执行/验证命令：**
~~~text
Set-Location -LiteralPath $Root
Invoke-Bd2Gradle --version
Invoke-Bd2Gradle :app:assembleDebug :app:assembleRelease --console=plain
~~~

**完成证据：** [T02.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T02.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 不改全局 Java/Python 配置、不复制私钥。环境不可复现时先完成缺项处理，不推进版本升级。

### T03 — 冻结功能、旧数据和真实 release 包体基线

- **历史任务 T03（原稿未实施，已停止维护）**

**优先级：** P0　**前置：** T02

**文件范围（实施工作区）：**
- 读取：[app/build/outputs/apk/release/app-arm64-v8a-release.apk](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/build/outputs/apk/release/app-arm64-v8a-release.apk>)
- 读取：[app/build/outputs/mapping/release/mapping.txt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/build/outputs/mapping/release/mapping.txt>)
- 新增：[app/src/test/resources/legacy/fixture-manifest.json](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/test/resources/legacy/fixture-manifest.json>)
- 新增：[docs/refactor/evidence/baseline.json](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/baseline.json>)

**落实要求：**
- 留存现成 0.2.16 发布包和 T02 同源重建包的哈希、构建配置、DEX/resources/Chaquopy/native 分类；分别记录，不能把历史 debug/release 差值全归因于 R8。
- 收集匿名化的旧配置、安装账本、列表快照、MOD 缓存、兑换历史与扫描 checkpoint；至少包括实际 release 的混淆快照形态。注明来源版本与预期迁移结果，不提交真实用户身份或未经授权的游戏资源。
- 列出安装/卸载、同 bundle 合并、SD/HD、预览分槽、跨 bundle、日期/好感剧情、Wiki v2/v3、兑换与更新的可复现输入和预期；区分不可丢失事实与可重建缓存。

**验收与回归：**
- 旧数据样本可由基线读取或明确记录其已知失败；快照失效不会等同安装账本清空。
- 同一份 APK 的哈希和条目分类可重复计算；记录下载体积、安装占用与运行内存的口径差别。

**执行/验证命令：**
~~~text
Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $Root 'app/build/outputs/apk/release/app-arm64-v8a-release.apk')
Show-Bd2Dependencies
~~~

**完成证据：** [T03.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T03.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 基线与原始样本只读保留；数据修复不能覆盖用于比对的原文件。

### T04 — 建立真实回归入口并替换无效集成测试

- **历史任务 T04（原稿未实施，已停止维护）**

**优先级：** P0　**前置：** T03

**文件范围（实施工作区）：**
- 修改：[app/src/test/java/com/bd2toolsbox/integration/PrepackCacheIntegrationTest.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/test/java/com/bd2toolsbox/integration/PrepackCacheIntegrationTest.kt>)
- 新增：[tools/verification.ps1](<D:/bd2 mod/worktrees/bdroid-android-refactor/tools/verification.ps1>)
- 修改：[.gitignore](<D:/bd2 mod/worktrees/bdroid-android-refactor/.gitignore>)
- 新增：[docs/refactor/test-matrix.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/test-matrix.md>)
- 新增测试：[app/src/androidTest/java/com/bd2toolsbox/integration/ReleaseContractProbeTest.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/androidTest/java/com/bd2toolsbox/integration/ReleaseContractProbeTest.kt>)

**落实要求：**
- 建立本文附录规定的 Test-Bd2Unit、Test-Bd2Device、Test-Bd2ReleaseSmoke 入口，读取已验证环境和实际 variant；不以未来不存在的 Gradle task 名称冒充可执行命令。
- 重写 PrepackCacheIntegrationTest：调用真实扫描/预解包/缓存流程，fake 只位于文件访问、时间或 Python 边界；测试必须读取真实产生的索引/缓存文件，而不是验证常量大小关系。
- 给新缺陷测试记录修复前失败与修复后成功；测试设备必须显式选定且可清理，拒绝默认操作用户日用设备、真实游戏目录或所有已连接设备。恢复测试前字体、主题等系统设置，不假定原值。
- ReleaseContractProbeTest 使用标准生产 Application 和真实 release 流程，不通过测试专用 keep、替换生产 DTO 或引用反射对象来让 R8 缺陷消失；签名/设备受控，fixture 仅写到隔离验收环境。

**验收与回归：**
- 人为破坏缓存 key、跳过 checkpoint 写入或引入覆盖竞态时，对应测试确实失败；恢复正确实现后通过。
- 未指定/未批准设备、ABI 不匹配、外部目录越界时验证脚本拒绝安装或清理；命令失败返回非零退出码。

**执行/验证命令：**
~~~text
Test-Bd2Unit -TestClass 'com.bd2toolsbox.integration.PrepackCacheIntegrationTest'
Invoke-Bd2Python -m unittest discover -s (Join-Path $Root 'tests') -p 'test_scan_checkpoint.py' -v
~~~

**完成证据：** [T04.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T04.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 保留旧测试作为缺陷说明但不计为覆盖；验收脚本出错时停用脚本，不能放宽设备或路径保护。

## G1 — 文件安全与数据护栏

### T05 — 建立稳定的领域结果、文件错误与计划模型

- **历史任务 T05（原稿未实施，已停止维护）**

**优先级：** P0　**前置：** T04

**文件范围（实施工作区）：**
- 修改：[app/src/main/java/com/bd2toolsbox/data/model/ModModels.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/data/model/ModModels.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/ui/viewmodel/MainViewModel.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/viewmodel/MainViewModel.kt>)
- 新增：[app/src/main/java/com/bd2toolsbox/core/model/OperationModels.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/core/model/OperationModels.kt>)
- 新增：[app/src/main/java/com/bd2toolsbox/core/fileaccess/FileAccessModels.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/core/fileaccess/FileAccessModels.kt>)
- 新增测试：[app/src/test/java/com/bd2toolsbox/core/model/OperationContractTest.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/test/java/com/bd2toolsbox/core/model/OperationContractTest.kt>)

**落实要求：**
- 迁出 MainViewModel 内嵌的 PrepackPlan、UninstallPlan、ConvertedOverwritePlan 等类型；模型只描述业务参数/结果，不持有 ViewModel、Context、Composable 或 PyObject。
- 定义任务 ID、阶段、可取消性、已提交/失败目标、错误分类与可重试性；扫描来源目录、扫描游戏索引、预解包是不同操作。
- 目录访问返回明确的成功列表或失败；空列表只代表确实读取成功且为空。拒绝把权限拒绝、Binder 断连、解析失败统一变成空列表或 false。固定 UI、用例与平台 adapter 之间的错误映射。

**验收与回归：**
- 访问成功但为空与访问失败的结果类型不同，调用方不能把后者提交为空索引。
- 同 bundle 的多个 MOD 计划保留既有安装项；计划模型不依赖 UI 包和 Android 对象。

**执行/验证命令：**
~~~text
Test-Bd2Unit -TestClass 'com.bd2toolsbox.core.model.OperationContractTest'
~~~

**完成证据：** [T05.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T05.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 逐调用方替换旧类型；迁移完成前仅允许明确的内部 adapter，不建立长期双份事实模型。

### T06 — 封堵 Kotlin 与 Python 的归档路径越界

- **历史任务 T06（原稿未实施，已停止维护）**

**优先级：** P0　**前置：** T05

**文件范围（实施工作区）：**
- 修改：[app/src/main/java/com/bd2toolsbox/data/repository/ModRepository.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/data/repository/ModRepository.kt>)
- 新增：[app/src/main/java/com/bd2toolsbox/core/fileaccess/SafeArchivePaths.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/core/fileaccess/SafeArchivePaths.kt>)
- 修改：[app/src/main/python/resolver.py](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/python/resolver.py>)
- 新增：[app/src/main/python/safe_archive.py](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/python/safe_archive.py>)
- 新增测试：[app/src/test/java/com/bd2toolsbox/core/fileaccess/SafeArchivePathsTest.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/test/java/com/bd2toolsbox/core/fileaccess/SafeArchivePathsTest.kt>)
- 新增：[tests/test_safe_archive.py](<D:/bd2 mod/worktrees/bdroid-android-refactor/tests/test_safe_archive.py>)

**落实要求：**
- 搜索本项目处理输入归档的全部入口，将条目输出路径规范化并验证其严格位于指定根内；拒绝绝对路径、盘符、父目录回退以及能借现有符号链接逃逸的路径。
- 先验证条目再写入；失败返回可识别的导入错误，清理只针对本次创建的临时内容，不触及 MOD 来源和游戏原文件。Kotlin 与 Python 路径规则一致。
- 保留正常嵌套目录、中文文件名和已有 MOD 导入语义；资源限额若需要增加，先用基线样本确定并记录，不能随意设小阈值损坏正常大 MOD。
- 测试 interface 固定为 SafeArchivePaths.resolveInside(root: File, entryName: String): File，非法路径抛 IllegalArgumentException；Python 使用 safe_archive.extract_zip(archive_path, destination)，非法条目抛 ValueError，正常归档保持目录结构。

**验收与回归：**
- ../outside、嵌套回退、绝对路径、Windows 盘符和反斜杠绕过、符号链接逃逸均不能在边界外创建或覆盖文件。
- 正常中文嵌套归档能完整导入；同时覆盖 API29–33 的应用防护与 API34+ 的系统提前拒绝。

**执行/验证命令：**
~~~text
Test-Bd2Unit -TestClass 'com.bd2toolsbox.core.fileaccess.SafeArchivePathsTest'
Invoke-Bd2Python -m unittest discover -s (Join-Path $Root 'tests') -p 'test_safe_archive.py' -v
~~~

**完成证据：** [T06.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T06.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 出现合法归档兼容问题时修正规则并补样本，不撤掉路径包含性验证；仅回收自己创建的临时目录。

### T07 — 游戏文件采用同目录暂存、校验和原子提交

- **历史任务 T07（原稿未实施，已停止维护）**

**优先级：** P0　**前置：** T05

**文件范围（实施工作区）：**
- 修改：[app/src/main/java/com/bd2toolsbox/service/ShizukuFileService.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/service/ShizukuFileService.kt>)
- 读取：[app/src/main/aidl/com/bd2toolsbox/IFileService.aidl](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/aidl/com/bd2toolsbox/IFileService.aidl>)
- 修改：[app/src/main/java/com/bd2toolsbox/ui/viewmodel/MainViewModel.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/viewmodel/MainViewModel.kt>)
- 新增：[app/src/main/java/com/bd2toolsbox/core/fileaccess/AtomicFileWriter.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/core/fileaccess/AtomicFileWriter.kt>)
- 新增测试：[app/src/test/java/com/bd2toolsbox/core/fileaccess/AtomicFileWriterTest.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/test/java/com/bd2toolsbox/core/fileaccess/AtomicFileWriterTest.kt>)
- 新增测试：[app/src/androidTest/java/com/bd2toolsbox/service/GameFileCommitTest.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/androidTest/java/com/bd2toolsbox/service/GameFileCommitTest.kt>)

**落实要求：**
- 不再先截断最终 __data。写入目标同目录的独占临时文件，完成大小/内容校验与可支持的同步后再替换；实际文件系统不支持所需提交语义时安全失败，不退化为覆盖最终文件的半途复制。
- 明确单文件提交点及批次部分成功，不能把单文件 rename 宣称为整批事务。取消在提交点外协作处理，提交临界区完成后再响应取消。
- 自动备份开启时必须检查备份返回结果，失败即停止该目标写入；不记录虚假的“已备份”。备份明确关闭的既有用户设置不被静默改为开启。
- AtomicFileWriter 提供 write(target: File, verifyBeforeCommit: (File) -> Unit = {}, write: (OutputStream) -> Unit)；写入/校验失败向调用者抛出原始错误，目标保持旧内容，校验发生在替换之前。

**验收与回归：**
- 复制半途异常、磁盘不足、校验失败时最终文件仍是旧内容，暂存文件可识别清理。
- 成功提交得到完整新内容；备份返回 false 时目标不变且失败传给调用方；在真实 Shizuku 文件系统验证 rename/权限行为。

**执行/验证命令：**
~~~text
Test-Bd2Unit -TestClass 'com.bd2toolsbox.core.fileaccess.AtomicFileWriterTest'
Test-Bd2Device -TestClass 'com.bd2toolsbox.service.GameFileCommitTest' -Abi 'arm64-v8a'
Test-Bd2Device -TestClass 'com.bd2toolsbox.service.GameFileCommitTest' -Abi 'x86_64'
~~~

**完成证据：** [T07.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T07.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 保留原文件与备份，不以直接覆盖作为兼容回退。Binder 接口若需扩展须同步主/服务端且保留组件身份。

### T08 — 安装账本原子落盘并记录可恢复的提交事实

- **历史任务 T08（原稿未实施，已停止维护）**

**优先级：** P0　**前置：** T07

**文件范围（实施工作区）：**
- 修改：[app/src/main/java/com/bd2toolsbox/data/repository/InstalledModRepository.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/data/repository/InstalledModRepository.kt>)
- 新增：[app/src/main/java/com/bd2toolsbox/core/storage/InstalledLedgerStore.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/core/storage/InstalledLedgerStore.kt>)
- 新增：[app/src/main/java/com/bd2toolsbox/core/storage/OperationJournalStore.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/core/storage/OperationJournalStore.kt>)
- 新增测试：[app/src/test/java/com/bd2toolsbox/core/storage/InstalledLedgerStoreTest.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/test/java/com/bd2toolsbox/core/storage/InstalledLedgerStoreTest.kt>)
- 新增测试：[app/src/test/java/com/bd2toolsbox/core/storage/OperationRecoveryTest.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/test/java/com/bd2toolsbox/core/storage/OperationRecoveryTest.kt>)

**落实要求：**
- 区分文件不存在、有效空账本、读取失败和损坏；损坏/读失败时保留原件，禁止按空表自动覆盖。写入结果必须传回上层，不能只打印异常。
- 使用原子文件写入和统一并发所有者保护 read-modify-write，保留现有锁的作用而不是误判为完全无锁。保存失败时旧账本仍可用。
- 为有副作用的操作记录计划、暂存、目标已替换、账本已提交等阶段及目标摘要；在游戏文件提交与账本写入之间崩溃时可核对实际结果，不能自动伪装成全失败或全成功。日志不承担任意步骤断点续跑承诺。

**验收与回归：**
- 并发写入不丢记录；保存中断仍可读取旧账本；损坏账本不会被下一次安装当空表覆盖。
- 注入“文件已换、账本未存”故障，重启后进入明确的待核对状态，依据日志和目标内容恢复一致性；不重复叠加安装。

**执行/验证命令：**
~~~text
Test-Bd2Unit -TestClass 'com.bd2toolsbox.core.storage.InstalledLedgerStoreTest'
Test-Bd2Unit -TestClass 'com.bd2toolsbox.core.storage.OperationRecoveryTest'
~~~

**完成证据：** [T08.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T08.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 数据迁移前保留受保护原件；回退代码时不得盲目用旧账本覆盖后续已发生的游戏文件事实。

### T09 — 区分可信原版与仅仅同尺寸的文件

- **历史任务 T09（原稿未实施，已停止维护）**

**优先级：** P0　**前置：** T07、T08

**文件范围（实施工作区）：**
- 修改：[app/src/main/java/com/bd2toolsbox/data/repository/BundleBackupRepository.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/data/repository/BundleBackupRepository.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/ui/viewmodel/MainViewModel.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/viewmodel/MainViewModel.kt>)
- 新增：[app/src/main/java/com/bd2toolsbox/core/storage/PristineVerifier.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/core/storage/PristineVerifier.kt>)
- 读取：[app/src/main/python/catalog_parser.py](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/python/catalog_parser.py>)
- 新增测试：[app/src/test/java/com/bd2toolsbox/core/storage/PristineVerifierTest.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/test/java/com/bd2toolsbox/core/storage/PristineVerifierTest.kt>)

**落实要求：**
- 查明 catalog/hash 目录名的真实语义，不假定它就是内容 MD5/SHA256；大小仅作快速排除。复用或标为原版前必须与可信来源/摘要建立可验证关联。
- 明确“恢复到操作前的备份”与“可作转换基底的干净原版”不同。已装 MOD、同尺寸不同内容、来源不明的备份不能直接标 PRISTINE。
- 摘要采用流式计算，不把大 bundle 全读入内存；无法确认保留 UNKNOWN 并给出可解释的处理路径，不能为减少联网次数而假称已验证原版。

**验收与回归：**
- 两个同名同尺寸但内容不同的 bundle，只有匹配可信基准者可作为原版；不得用待验证文件自己的摘要循环证明自己。
- 既有 SD/HD、缓存来源和真实原版重编码场景都有明确验证来源；被修改备份不会作为干净基底再次复用。

**执行/验证命令：**
~~~text
Test-Bd2Unit -TestClass 'com.bd2toolsbox.core.storage.PristineVerifierTest'
~~~

**完成证据：** [T09.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T09.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 保留原件和来源元数据；遇到无法识别的新格式标 UNKNOWN，不能恢复为“大小相同即可信”。

### T10 — 先修现有 release 快照的序列化契约风险

- **历史任务 T10（原稿未实施，已停止维护）**

**优先级：** P0　**前置：** T03、T05、T08

**文件范围（实施工作区）：**
- 修改：[app/src/main/java/com/bd2toolsbox/ui/viewmodel/MainViewModel.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/viewmodel/MainViewModel.kt>)
- 新增：[app/src/main/java/com/bd2toolsbox/core/storage/ModSnapshotStore.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/core/storage/ModSnapshotStore.kt>)
- 修改：[app/proguard-rules.pro](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/proguard-rules.pro>)
- 新增：[app/src/test/resources/legacy/mod-snapshot-release.json](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/test/resources/legacy/mod-snapshot-release.json>)
- 新增测试：[app/src/androidTest/java/com/bd2toolsbox/data/repository/ReleaseSnapshotCompatibilityTest.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/androidTest/java/com/bd2toolsbox/data/repository/ReleaseSnapshotCompatibilityTest.kt>)

**落实要求：**
- 在更换 R8/序列化之前，迁出 ModListSnapshot 并固定持久化字段名、schema 和必要保留规则；不能继续依赖会被重命名/删除的 UI 内嵌字段。过渡期可用精确 Gson 注解/规则，最终由 T35–T36 移除。
- 覆盖真实 0.2.16 release 中 savedAt/mods/sourceDirs 被改名、version 被移除的事实；能无歧义读取则迁移，否则仅显式失效可重建快照，不猜字段、不清安装账本或配置。
- 原文件在安全迁移完成前保留；快照写入顺序、失败回退和 schema 校验集中到 Store。

**验收与回归：**
- 测试使用实际 release 样本，而不只有 debug 的明文键；损坏/未知版本快照触发明确重新加载，不制造“用户没有安装过 MOD”的事实。
- 混淆发布构建冷启动、保存、再次启动后列表仍可恢复，字段稳定且安装账本保持不变。

**执行/验证命令：**
~~~text
Test-Bd2ReleaseSmoke -Case 'snapshot-compatibility' -Abi 'arm64-v8a'
Test-Bd2ReleaseSmoke -Case 'snapshot-compatibility' -Abi 'x86_64'
~~~

**完成证据：** [T10.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T10.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 原快照可备份或隔离；可重建缓存允许失效，但不以卸载重装、清应用数据或丢账本作为迁移策略。

## G2 — 版本治理与工具链

### T11 — 建立版本目录和独立的 App 发布版本配置

- **历史任务 T11（原稿未实施，已停止维护）**

**优先级：** P1　**前置：** T04

**文件范围（实施工作区）：**
- 修改：[build.gradle.kts](<D:/bd2 mod/worktrees/bdroid-android-refactor/build.gradle.kts>)
- 修改：[settings.gradle.kts](<D:/bd2 mod/worktrees/bdroid-android-refactor/settings.gradle.kts>)
- 修改：[app/build.gradle.kts](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/build.gradle.kts>)
- 新增：[gradle/libs.versions.toml](<D:/bd2 mod/worktrees/bdroid-android-refactor/gradle/libs.versions.toml>)
- 新增：[gradle/version.properties](<D:/bd2 mod/worktrees/bdroid-android-refactor/gradle/version.properties>)

**落实要求：**
- 先只迁移配置，不升级版本：把现有插件、Maven 依赖、测试依赖和 Compose BOM 统一为 catalog aliases，去掉根脚本/模块里的重复字面版本；保留迁移前解析结果。
- App 发布配置使用 appVersionName/appVersionCode 两个字段（配置迁移时保持执行基线值，当前为0.2.16/17），继续支持有文档的 APP_VERSION_NAME 覆盖。校验版本名非空、versionCode 为正整数且发布时递增；不新增根工程发布号或内部模块独立发版体系。
- wrapper 版本仍由 wrapper properties 执行，Python 包由 T12 管理；不能把它们写进 TOML 后就声称已生效。记录依赖锁定/校验文件覆盖范围，禁用动态版本和无约束 latest。

**验收与回归：**
- 迁移前后 releaseRuntimeClasspath 的坐标/版本一致，默认生成 APK 仍为基线应用版本和标识。
- 正常 APP_VERSION_NAME 覆盖仅影响显示版本；空值/非法 versionCode 应在配置阶段明确失败；Compose BOM 和 kotlin-test 不再重复维护。

**执行/验证命令：**
~~~text
Show-Bd2Dependencies
Invoke-Bd2Gradle :app:assembleDebug :app:assembleRelease --console=plain
~~~

**完成证据：** [T11.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T11.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 配置迁移单独提交，可独立回退；不与依赖升级、包名变更或发版号提升混在一起。

### T12 — 固定 Python 依赖、目标解释器和第三方来源

- **历史任务 T12（原稿未实施，已停止维护）**

**优先级：** P1　**前置：** T03、T11

**文件范围（实施工作区）：**
- 修改：[app/build.gradle.kts](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/build.gradle.kts>)
- 新增：[app/requirements-android.txt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/requirements-android.txt>)
- 新增：[docs/refactor/python-dependencies.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/python-dependencies.md>)
- 读取：[app/src/main/python/maintenance_info_pb2.py](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/python/maintenance_info_pb2.py>)
- 读取：[app/src/main/python/vendor/UnityPy](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/python/vendor/UnityPy>)

**落实要求：**
- 固定当前 9 个直接 pip 依赖，并记录实际传递依赖、Android wheel 来源、ABI、Python minor 与校验信息；不要用 Windows pip freeze 结果假装 Android 依赖锁。
- 记录 Chaquopy APK 内 Python、buildPython 和 pyc 生成兼容要求；目标 Python 和 wheel 调整必须成套验证。构建路径通过本地配置/环境变量提供，不提交个人机器绝对路径。
- 为 vendored UnityPy、生成代码、JNI、压缩 JS 建立上游版本/补丁来源；只治理必要适配和打包，不把生成文件、大段第三方算法按行数重写。

**验收与回归：**
- 清理本次专用构建缓存后仍解析到相同已记录依赖；arm64/x86_64 都能取得适配 wheel。
- 实际目标解释器能 import 所需包；pyc 是否生成及原因有构建日志，不把 pyc 当必然减包手段。

**执行/验证命令：**
~~~text
Invoke-Bd2Python -m unittest discover -s (Join-Path $Root 'tests') -p 'test_*.py' -v
Invoke-Bd2Gradle :app:assembleDebug --console=plain
~~~

**完成证据：** [T12.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T12.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 保留旧解析清单与可追溯 wheel 来源；包升级与纯锁版本分提交，不能因锁定失败删掉功能依赖。

### T13 — 用兼容探针确定稳定工具链组合

- **历史任务 T13（原稿未实施，已停止维护）**

**优先级：** P0　**前置：** T10、T11、T12

**文件范围（实施工作区）：**
- 新增：[docs/refactor/compatibility-matrix.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/compatibility-matrix.md>)
- 新增验证：[build/compat-probe](<D:/bd2 mod/worktrees/bdroid-android-refactor/build/compat-probe>)
- 读取：[docs/research/2026-09-14-dependency-modernization.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/research/2026-09-14-dependency-modernization.md>)
- 新增验证：[build/compat-probe/app/src/androidTest/java/com/bd2toolsbox/compat/CompatibilitySmokeTest.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/build/compat-probe/app/src/androidTest/java/com/bd2toolsbox/compat/CompatibilitySmokeTest.kt>)

**落实要求：**
- 核对 AGP/Gradle/JDK、Kotlin/Compose compiler、KSP/Hilt、Chaquopy/Python/wheels、Compose BOM、AboutLibraries/serialization 的官方支持矩阵及 AAR 元数据，输出一套完整固定版本，不能把历史候选当已验证结果。
- 在独立的最小验证工程验证 Compose + Hilt/KSP 生成代码 + serialization + Python 调用；每个 probe App 仍只在一个模块实际应用 Chaquopy。探针使用候选自己的 Gradle wrapper，不误用旧 wrapper。
- 保持 minSdk29、不增删 ABI、不为追 latest 扩大业务范围。显式记录 Hilt 2.59+ 的 AGP9 约束、Dagger/KSP 稳定支持条件、AboutLibraries 的 compileSdk 门槛，以及 AGP8.12 起的新资源收缩管线；任何不满足之处必须说明，不能假称全链路稳定。

**验收与回归：**
- 探针在目标 JDK 下完成 debug/release 构建，生成 Hilt/KSP 代码且序列化往返、Python 调用可运行。
- 矩阵包含实际解析版本、SDK 要求、证据链接、命令及退出码；不兼容组合被排除，不偷偷提高最低 Android 版本。

**执行/验证命令：**
~~~text
& (Join-Path $Root 'build/compat-probe/gradlew.bat') -p (Join-Path $Root 'build/compat-probe') assembleDebug assembleRelease --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Compatibility probe failed' }
Test-Bd2Device -TestClass 'com.bd2toolsbox.compat.CompatibilitySmokeTest' -Abi 'arm64-v8a' -ProjectRoot (Join-Path $Root 'build/compat-probe')
Test-Bd2Device -TestClass 'com.bd2toolsbox.compat.CompatibilitySmokeTest' -Abi 'x86_64' -ProjectRoot (Join-Path $Root 'build/compat-probe')
~~~

**完成证据：** [T13.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T13.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 探针失败仅调整兼容组合；不推进生产配置升级。遇到已确认范围与所有稳定组合冲突时报告事实，不自行改变产品边界。

### T14 — 升级 Kotlin、Compose 编译和 Android/Python 构建链

- **历史任务 T14（原稿未实施，已停止维护）**

**优先级：** P1　**前置：** T13

**文件范围（实施工作区）：**
- 修改：[gradle/libs.versions.toml](<D:/bd2 mod/worktrees/bdroid-android-refactor/gradle/libs.versions.toml>)
- 修改：[gradle/wrapper/gradle-wrapper.properties](<D:/bd2 mod/worktrees/bdroid-android-refactor/gradle/wrapper/gradle-wrapper.properties>)
- 修改：[gradle.properties](<D:/bd2 mod/worktrees/bdroid-android-refactor/gradle.properties>)
- 修改：[build.gradle.kts](<D:/bd2 mod/worktrees/bdroid-android-refactor/build.gradle.kts>)
- 修改：[app/build.gradle.kts](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/build.gradle.kts>)

**落实要求：**
- 按 T13 的已验证组合升级，Kotlin 2.x 使用匹配的 Compose compiler Gradle 插件，移除旧 kotlinCompilerExtensionVersion 管理方式；serialization 编译插件随 Kotlin，JSON runtime 独立管理。
- 处理选定 AGP 下的 Kotlin 插件/内置 Kotlin 模式，避免重复应用；迁移废弃 DSL。KSP/Hilt 处理链只保留必要的一套，不同时留 kapt/KSP 双重生成。
- Gradle 运行 JDK 与应用 Java/JVM 字节码目标分开。提高运行 JDK 不自动要求把应用字节码从11改17；任何变更记录理由。保留 AIDL、签名、Python 懒启动与应用身份。
- 若最终组合改变目标 Python minor 或 wheel 版本，同步更新 T12 的解析/来源记录并重跑相关回归；不能沿用旧 lock 却声称新解释器可复现。

**验收与回归：**
- 全量单元测试和 Python 回归通过，两 ABI debug/release 均可构建；新版本不是只通过 Gradle sync。
- 依赖图无意外版本抬升/重复 Compose 族，Hilt/KSP 编译日志与 pyc/wheel 产物可核对。

**执行/验证命令：**
~~~text
Invoke-Bd2Gradle --version
Invoke-Bd2Gradle :app:assembleDebug :app:assembleRelease --console=plain
Test-Bd2Unit -TestClass 'com.bd2toolsbox.*'
Invoke-Bd2Python -m unittest discover -s (Join-Path $Root 'tests') -p 'test_*.py' -v
~~~

**完成证据：** [T14.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T14.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 工具链作为独立可回退变更；保留旧 wrapper/锁定清单和兼容数据读取能力，不夹带 UI/业务重写。

### T15 — 验证 SDK、权限、前台服务和更新安装行为

- **历史任务 T15（原稿未实施，已停止维护）**

**优先级：** P0　**前置：** T14

**文件范围（实施工作区）：**
- 修改：[app/src/main/AndroidManifest.xml](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/AndroidManifest.xml>)
- 修改：[app/build.gradle.kts](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/build.gradle.kts>)
- 修改：[app/src/main/java/com/bd2toolsbox/utils/SafManager.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/utils/SafManager.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/service/InstallService.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/service/InstallService.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/service/ScanService.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/service/ScanService.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/service/PrepackService.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/service/PrepackService.kt>)
- 新增测试：[app/src/androidTest/java/com/bd2toolsbox/platform/PlatformBehaviorTest.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/androidTest/java/com/bd2toolsbox/platform/PlatformBehaviorTest.kt>)

**落实要求：**
- minSdk 固定29。根据 T13 和实测结果明确 compileSdk/targetSdk，分别记录；选择稳定且已验证的目标，不把 targetSdk 上调当普通依赖改号。
- 验证通知拒绝、后台启动限制、前台服务类型/权限、适用系统的 dataSync 超时，以及受限情况下安全停止和反馈；不靠 Service 名称或 Hilt 注解假定保活成立。
- 验证 SAF 持久授权、全部文件权限、Shizuku 授权、下载完成广播、FileProvider 和未知来源 APK 安装授权；不更改 applicationId、authority 或外部组件身份。
- 对适用的新系统验证16KB内存页下的 native 加载与 APK 对齐；ELF 链接对齐和 ZIP 对齐分别检查，不能用 strip 或压缩代替兼容修复。

**验收与回归：**
- API29、34及选定目标/较新系统上，授权成功/拒绝/撤销都有正确结果；拒绝权限不能显示成功的空列表。
- 前台服务被拒绝或超时不会截断游戏文件、漏记账本或永久卡住进度；更新仍交由系统安装器确认。

**执行/验证命令：**
~~~text
Test-Bd2Device -TestClass 'com.bd2toolsbox.platform.PlatformBehaviorTest' -Abi 'arm64-v8a'
Test-Bd2Device -TestClass 'com.bd2toolsbox.platform.PlatformBehaviorTest' -Abi 'x86_64'
~~~

**完成证据：** [T15.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T15.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 可单独回退 targetSdk 行为升级而保留其余依赖更新；不能删除权限检查或降低文件安全换取通过。

## G3 — Hilt 与平台适配层

### T16 — 建立 Hilt Application 和真实 Android 入口

- **历史任务 T16（原稿未实施，已停止维护）**

**优先级：** P1　**前置：** T14、T15

**文件范围（实施工作区）：**
- 新增：[app/src/main/java/com/bd2toolsbox/Bd2Application.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/Bd2Application.kt>)
- 修改：[app/src/main/AndroidManifest.xml](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/AndroidManifest.xml>)
- 修改：[gradle/libs.versions.toml](<D:/bd2 mod/worktrees/bdroid-android-refactor/gradle/libs.versions.toml>)
- 修改：[app/build.gradle.kts](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/build.gradle.kts>)
- 修改：[app/src/main/java/com/bd2toolsbox/MainActivity.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/MainActivity.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/SpinePreviewActivity.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/SpinePreviewActivity.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/service/InstallService.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/service/InstallService.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/service/ScanService.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/service/ScanService.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/service/PrepackService.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/service/PrepackService.kt>)
- 新增测试：[app/src/androidTest/java/com/bd2toolsbox/di/HiltEntryPointTest.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/androidTest/java/com/bd2toolsbox/di/HiltEntryPointTest.kt>)

**落实要求：**
- 引入经 T13 验证的 Hilt plugin/runtime/compiler 与 KSP 配置；Application 使用 @HiltAndroidApp，需要注入的 Android Activity/Service 使用受支持入口。
- 新增 Application 不抢先启动 Python、扫描游戏文件或阻塞首屏；依赖按实际使用懒加载。
- 明确 ShizukuFileService 是外部进程的 Binder Stub，不给它生搬 @AndroidEntryPoint，不让它依赖主进程对象图。

**验收与回归：**
- 主界面、预览入口、三个 Service 的独立冷启动均能解析所需依赖；未使用 Python 功能前不触发 Python 重初始化。
- Hilt 生成代码构建正常，配置错误会被测试捕获；无跨进程 Hilt 单例访问假设。

**执行/验证命令：**
~~~text
Test-Bd2Device -TestClass 'com.bd2toolsbox.di.HiltEntryPointTest' -Abi 'arm64-v8a'
Test-Bd2Device -TestClass 'com.bd2toolsbox.di.HiltEntryPointTest' -Abi 'x86_64'
~~~

**完成证据：** [T16.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T16.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 入口接入单独提交；失败时回退入口配置而不是保留半套手工/半套注入的重复对象。

### T17 — 把仓库、调度器和有状态依赖改成构造注入

- **历史任务 T17（原稿未实施，已停止维护）**

**优先级：** P1　**前置：** T16、T05

**文件范围（实施工作区）：**
- 新增：[app/src/main/java/com/bd2toolsbox/di/RepositoryModule.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/di/RepositoryModule.kt>)
- 新增：[app/src/main/java/com/bd2toolsbox/di/DispatcherModule.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/di/DispatcherModule.kt>)
- 新增：[app/src/main/java/com/bd2toolsbox/di/RuntimeModule.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/di/RuntimeModule.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/data/repository/InstalledModRepository.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/data/repository/InstalledModRepository.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/data/repository/ModRepository.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/data/repository/ModRepository.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/ui/viewmodel/MainViewModel.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/viewmodel/MainViewModel.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/ui/viewmodel/GuideViewModel.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/viewmodel/GuideViewModel.kt>)
- 新增测试：[app/src/test/java/com/bd2toolsbox/di/RepositoryConstructionTest.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/test/java/com/bd2toolsbox/di/RepositoryConstructionTest.kt>)

**落实要求：**
- 逐个迁移有状态 Repository 的 get(context)/手工单例和 ViewModel.initialize(context)+lateinit 装配；纯静态工具不为形式统一强行加 DI。
- 统一注入 ApplicationContext、IO/Default dispatcher、时钟和必要文件/网络 adapter；Activity Context 不进入长寿命对象，作用域以资源所有权而非“全部 Singleton”决定。
- 页面选择/筛选状态不放进应用级单例；安装账本与任务协调共享一个事实源。纯逻辑测试直接构造类，不要求每个测试启动 Hilt。

**验收与回归：**
- 同一应用图不会构造多个可竞争写账本的仓库；重建 Activity 不创建新的后台任务所有者。
- 真实业务类可用 fake 文件/网络/时间 adapter 测试；去掉 initialize 调用后所有入口仍可用。

**执行/验证命令：**
~~~text
Test-Bd2Unit -TestClass 'com.bd2toolsbox.di.RepositoryConstructionTest'
~~~

**完成证据：** [T17.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T17.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 以一组可独立验证的调用链迁移，避免双写；临时兼容入口必须标出唯一委托对象并在 T20 清除。

### T18 — 抽取 Kotlin—Python 引擎适配层并保留协议

- **历史任务 T18（原稿未实施，已停止维护）**

**优先级：** P1　**前置：** T17、T12

**文件范围（实施工作区）：**
- 修改：[app/src/main/java/com/bd2toolsbox/service/ModdingService.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/service/ModdingService.kt>)
- 新增：[app/src/main/java/com/bd2toolsbox/core/engine/ModEngine.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/core/engine/ModEngine.kt>)
- 新增：[app/src/main/java/com/bd2toolsbox/core/engine/ChaquopyModEngine.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/core/engine/ChaquopyModEngine.kt>)
- 修改：[app/src/main/python/main_script.py](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/python/main_script.py>)
- 新增测试：[app/src/test/java/com/bd2toolsbox/core/engine/EngineProtocolTest.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/test/java/com/bd2toolsbox/core/engine/EngineProtocolTest.kt>)
- 新增测试：[app/src/androidTest/java/com/bd2toolsbox/core/engine/NativeEngineSmokeTest.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/androidTest/java/com/bd2toolsbox/core/engine/NativeEngineSmokeTest.kt>)

**落实要求：**
- 把 ModdingService 静态桥收敛到可注入的 ModEngine，集中 Python 模块获取、参数装配、返回值校验、进度与取消；不让 PyObject 穿透到 ViewModel/UI。
- 保持现有函数名、参数顺序、SD/HD、ASTC、resolver 语义；只有明确协议缺陷才改 Python 入口并同步两端测试，不重写 vendored 算法。
- 捕获桥接错误形成有类型结果，保留必要诊断；协程 CancellationException 不被吞成一般失败。Python 实例初始化一次、按使用启动，记录可取消与不可中断调用的边界。

**验收与回归：**
- 下载/重打包/解包/合并/扫描等入口的成功、异常返回、非法元组和取消都有协议测试。
- 两 ABI 的真实 Python/JNI 纹理解码与至少一条转换路径可运行；仅通过 host mock 不算此项完成。

**执行/验证命令：**
~~~text
Test-Bd2Unit -TestClass 'com.bd2toolsbox.core.engine.EngineProtocolTest'
Test-Bd2Device -TestClass 'com.bd2toolsbox.core.engine.NativeEngineSmokeTest' -Abi 'arm64-v8a'
Test-Bd2Device -TestClass 'com.bd2toolsbox.core.engine.NativeEngineSmokeTest' -Abi 'x86_64'
~~~

**完成证据：** [T18.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T18.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 保留原协议 golden fixtures；可替换 adapter 回旧桥，但不可让业务再直接持有 PyObject 或默默吞错误。

### T19 — 隔离 Shizuku Binder 并由任务持有连接

- **历史任务 T19（原稿未实施，已停止维护）**

**优先级：** P0　**前置：** T17、T07、T05

**文件范围（实施工作区）：**
- 修改：[app/src/main/java/com/bd2toolsbox/service/ShizukuManager.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/service/ShizukuManager.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/service/ShizukuFileService.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/service/ShizukuFileService.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/MainActivity.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/MainActivity.kt>)
- 新增：[app/src/main/java/com/bd2toolsbox/core/fileaccess/GameFileAccess.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/core/fileaccess/GameFileAccess.kt>)
- 新增：[app/src/main/java/com/bd2toolsbox/core/fileaccess/ShizukuGameFileAccess.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/core/fileaccess/ShizukuGameFileAccess.kt>)
- 新增测试：[app/src/androidTest/java/com/bd2toolsbox/service/ShizukuConnectionLifecycleTest.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/androidTest/java/com/bd2toolsbox/service/ShizukuConnectionLifecycleTest.kt>)

**落实要求：**
- 主进程通过可注入 GameFileAccess 使用 Binder，外部 Shizuku 服务保留独立构造；AIDL、authority、进程/组件身份不随包结构整理改名。
- 连接的获取/释放依据运行任务和客户端占用；移除 Activity.isFinishing 即无条件销毁仍被后台任务使用的连接的逻辑。
- 断连、权限撤销、服务死亡传播为可恢复/不可恢复的明确错误；目录列举不能用 [] 隐藏失败，重连不能重复提交已完成文件。

**验收与回归：**
- Activity 结束而安装/扫描仍运行时连接继续可用；最后一个使用者退出后连接释放。
- 在列目录、暂存、提交前后注入 Binder 死亡，结果准确且遵守 T07/T08 的安全边界。

**执行/验证命令：**
~~~text
Test-Bd2Device -TestClass 'com.bd2toolsbox.service.ShizukuConnectionLifecycleTest' -Abi 'arm64-v8a'
Test-Bd2Device -TestClass 'com.bd2toolsbox.service.ShizukuConnectionLifecycleTest' -Abi 'x86_64'
~~~

**完成证据：** [T19.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T19.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 保留旧 Binder 接口时用 adapter 兼容；不能回到由页面关闭直接切断共享任务资源的策略。

### T20 — 完成 DI 装配收尾和冷启动回归

- **历史任务 T20（原稿未实施，已停止维护）**

**优先级：** P1　**前置：** T16、T17、T18、T19

**文件范围（实施工作区）：**
- 修改：[app/src/main/java/com/bd2toolsbox/ui/viewmodel/MainViewModel.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/viewmodel/MainViewModel.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/ui/viewmodel/GuideViewModel.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/viewmodel/GuideViewModel.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/data/repository/AvatarRepository.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/data/repository/AvatarRepository.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/data/repository/GamekeeRepository.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/data/repository/GamekeeRepository.kt>)
- 新增测试：[app/src/androidTest/java/com/bd2toolsbox/di/ObjectGraphLifecycleTest.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/androidTest/java/com/bd2toolsbox/di/ObjectGraphLifecycleTest.kt>)

**落实要求：**
- 清除已迁移调用链的临时手工 factory、重复构造、initialize(Context) 和 mutable 全局仓库；保留有理由的纯工具 object 清单。
- 核对 hiltViewModel 的 owner 与生命周期；同一 feature 不因切页产生多个互相覆盖的任务观察者，ViewModel 间不互相注入。
- 检查冷启动、进程重建、预览 Activity、Service 独立启动和 Hilt 测试替换；用实际行为证明装配完整，不以“注解编译通过”作为全部验收。

**验收与回归：**
- 真实入口无未初始化 lateinit 崩溃；重建后观察同一任务/账本状态，不出现两个数据写入者。
- 源码扫描命中剩余手工 get(context)/initialize 时逐条解释，不能批量 suppress 或把调用换个名字藏起来。

**执行/验证命令：**
~~~text
Test-Bd2Device -TestClass 'com.bd2toolsbox.di.ObjectGraphLifecycleTest' -Abi 'arm64-v8a'
Test-Bd2Device -TestClass 'com.bd2toolsbox.di.ObjectGraphLifecycleTest' -Abi 'x86_64'
rg -n 'initialize(|lateinit|fun get(context' (Join-Path $Root 'app/src/main/java')
~~~

**完成证据：** [T20.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T20.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 未验证链路不能删除原入口；已切换的链路保持唯一事实源，回退按完整调用链而非逐注解撤销。

## G4 — 后台任务与生命周期

### T21 — 建立有明确所有者的任务协调器和状态机

- **历史任务 T21（原稿未实施，已停止维护）**

**优先级：** P0　**前置：** T08、T19、T20

**文件范围（实施工作区）：**
- 新增：[app/src/main/java/com/bd2toolsbox/core/operations/OperationCoordinator.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/core/operations/OperationCoordinator.kt>)
- 新增：[app/src/main/java/com/bd2toolsbox/core/operations/OperationStateStore.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/core/operations/OperationStateStore.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/core/model/OperationModels.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/core/model/OperationModels.kt>)
- 新增测试：[app/src/test/java/com/bd2toolsbox/core/operations/OperationCoordinatorTest.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/test/java/com/bd2toolsbox/core/operations/OperationCoordinatorTest.kt>)

**落实要求：**
- 以任务 ID 关联参数、执行状态、进度、结果和 journal；界面仅提交意图/观察状态，任务 scope 由 Android 运行宿主负责启动和收尾，不属于旧 ViewModel。
- 定义准备、运行、提交、成功、部分失败、失败、取消、中断的合法状态转换；输出每个目标真实结果，禁止 finally 中无条件发成功或100%。
- 同一游戏目标/同 bundle 的写操作互斥；冲突操作明确排队或拒绝，不让安装、卸载、预解包、清缓存竞争同一资源。取消是请求，提交临界区不能立即终止。

**验收与回归：**
- 重复点击不会产生两次同目标写入；冲突有可解释结果；失败、取消和部分成功不会互相冒充。
- 真实协调器配合可阻塞 executor，移除 UI 观察者后执行仍继续，结束后 scope/锁/临时引用全部释放。

**执行/验证命令：**
~~~text
Test-Bd2Unit -TestClass 'com.bd2toolsbox.core.operations.OperationCoordinatorTest'
~~~

**完成证据：** [T21.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T21.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 状态迁移按任务 ID 保持可识别；不退回 GlobalScope 或 ViewModel 私有逃逸 scope。

### T22 — 把安装、转换和卸载执行迁入 InstallService 宿主

- **历史任务 T22（原稿未实施，已停止维护）**

**优先级：** P0　**前置：** T21、T18、T09

**文件范围（实施工作区）：**
- 修改：[app/src/main/java/com/bd2toolsbox/service/InstallService.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/service/InstallService.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/ui/viewmodel/MainViewModel.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/viewmodel/MainViewModel.kt>)
- 新增：[app/src/main/java/com/bd2toolsbox/feature/install/InstallExecutor.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/feature/install/InstallExecutor.kt>)
- 新增：[app/src/main/java/com/bd2toolsbox/feature/install/UninstallExecutor.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/feature/install/UninstallExecutor.kt>)
- 新增测试：[app/src/test/java/com/bd2toolsbox/feature/install/InstallExecutorTest.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/test/java/com/bd2toolsbox/feature/install/InstallExecutorTest.kt>)
- 新增测试：[app/src/androidTest/java/com/bd2toolsbox/service/InstallServiceLifecycleTest.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/androidTest/java/com/bd2toolsbox/service/InstallServiceLifecycleTest.kt>)

**落实要求：**
- 迁移安装计划执行、同 bundle 已装项合并、基底选择、转换、写入、账本提交及卸载恢复；Service 驱动 executor，不能仍由 ViewModel 跑循环而 Service 只挂通知。
- 每个 bundle 走 T07/T08 提交协议；失败保留准确的部分成功明细。调用者既能观察批次进度，也能核对每个目标，不能只剩 Boolean。
- 保留不必重跑转换的 Shizuku 恢复路径、已有安装/卸载语义和通知；解绑页面不取消操作，真正取消遵守安全点。

**验收与回归：**
- 同 bundle 多 MOD 合并、删除其中一个、覆盖转换产物、SD/HD 基底选择均与 golden 行为一致。
- 退出 Activity、旋转、备份失败、写入失败、账本失败时没有半份目标或虚假成功；真实 Service 场景可重连显示同一任务。

**执行/验证命令：**
~~~text
Test-Bd2Unit -TestClass 'com.bd2toolsbox.feature.install.InstallExecutorTest'
Test-Bd2Device -TestClass 'com.bd2toolsbox.service.InstallServiceLifecycleTest' -Abi 'arm64-v8a'
Test-Bd2Device -TestClass 'com.bd2toolsbox.service.InstallServiceLifecycleTest' -Abi 'x86_64'
~~~

**完成证据：** [T22.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T22.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 保留可核对的逐目标 journal 和原文件；回退安装 executor 前先完成/安全终止现存任务，不能双引擎同时写游戏目录。

### T23 — 把扫描循环迁入 ScanService 并修正分页与错误协议

- **历史任务 T23（原稿未实施，已停止维护）**

**优先级：** P0　**前置：** T21、T18

**文件范围（实施工作区）：**
- 修改：[app/src/main/java/com/bd2toolsbox/service/ScanService.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/service/ScanService.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/ui/viewmodel/MainViewModel.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/viewmodel/MainViewModel.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/service/ShizukuFileService.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/service/ShizukuFileService.kt>)
- 新增：[app/src/main/java/com/bd2toolsbox/feature/mods/ScanExecutor.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/feature/mods/ScanExecutor.kt>)
- 修改：[app/src/main/python/local_bundle_indexer.py](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/python/local_bundle_indexer.py>)
- 修改：[app/src/androidTest/java/com/bd2toolsbox/service/ScanServiceTest.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/androidTest/java/com/bd2toolsbox/service/ScanServiceTest.kt>)
- 新增测试：[app/src/test/java/com/bd2toolsbox/feature/mods/ScanExecutorTest.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/test/java/com/bd2toolsbox/feature/mods/ScanExecutorTest.kt>)

**落实要求：**
- 来源 MOD 扫描与游戏 bundle 索引扫描分别建模，权限、进度和完成反馈不混用；实际循环由 Service/executor 执行。
- 保留 check_scan_needed、checkpoint_scan 和全量扫描默认行为；失败读取目录不允许覆盖旧索引为空，部分进度可安全写 checkpoint。
- 量测现有分页重复枚举；同次扫描使用稳定快照/游标减少重复全目录遍历，仍分批传输，避免把超大目录一次塞进 Binder。明确扫描期间目录变化和新一轮扫描刷新规则。

**验收与回归：**
- N 页列表不再触发 N 次完整枚举；正确性先于性能，遗漏、重复和 Binder 事务过大均有测试。
- 权限错误不等于空目录；杀进程后既有 checkpoint 被利用，已扫条目不无故从零重扫；跨 bundle/日期/好感剧情回归保持。

**执行/验证命令：**
~~~text
Test-Bd2Unit -TestClass 'com.bd2toolsbox.feature.mods.ScanExecutorTest'
Invoke-Bd2Python -m unittest discover -s (Join-Path $Root 'tests') -p 'test_scan_*.py' -v
Test-Bd2Device -TestClass 'com.bd2toolsbox.service.ScanServiceTest' -Abi 'arm64-v8a'
Test-Bd2Device -TestClass 'com.bd2toolsbox.service.ScanServiceTest' -Abi 'x86_64'
~~~

**完成证据：** [T23.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T23.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 保留旧索引/checkpoint；可回退枚举优化，但不能撤掉错误与成功空列表的区分或把循环搬回 ViewModel。

### T24 — 治理预解包任务参数、缓存分槽和状态所有权

- **历史任务 T24（原稿未实施，已停止维护）**

**优先级：** P0　**前置：** T21、T18

**文件范围（实施工作区）：**
- 修改：[app/src/main/java/com/bd2toolsbox/service/PrepackService.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/service/PrepackService.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/data/repository/PreviewCacheRepository.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/data/repository/PreviewCacheRepository.kt>)
- 新增：[app/src/main/java/com/bd2toolsbox/feature/preview/PrepackExecutor.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/feature/preview/PrepackExecutor.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/ui/viewmodel/MainViewModel.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/viewmodel/MainViewModel.kt>)
- 新增测试：[app/src/test/java/com/bd2toolsbox/feature/preview/PrepackExecutorTest.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/test/java/com/bd2toolsbox/feature/preview/PrepackExecutorTest.kt>)
- 修改：[app/src/test/java/com/bd2toolsbox/integration/PrepackCacheIntegrationTest.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/test/java/com/bd2toolsbox/integration/PrepackCacheIntegrationTest.kt>)

**落实要求：**
- 去除静态 pendingTargets/全局 progress 作为唯一任务来源；Service 通过任务 ID 取得经过验证的参数，缺参数重建不能显示已完成。
- 保留尺寸/质量分槽、负缓存、异常缓存恢复与空间限制；缓存 key 不因 UI 拆分丢掉 bundle、SD/HD 或槽位维度。
- 预解包与安装/预览对同一输出的冲突交给协调器；异常/取消只清理本任务临时产物，不能删除已有可用缓存。

**验收与回归：**
- 进程重建但原静态字段为空时给出中断/恢复状态；不同分槽不串缓存，已有完整缓存不被失败任务覆盖。
- 真实生产缓存文件参与集成测试；重复预解包、半写文件和负缓存过期场景得到正确结果。

**执行/验证命令：**
~~~text
Test-Bd2Unit -TestClass 'com.bd2toolsbox.feature.preview.PrepackExecutorTest'
Test-Bd2Unit -TestClass 'com.bd2toolsbox.integration.PrepackCacheIntegrationTest'
~~~

**完成证据：** [T24.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T24.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 维持旧缓存可读或精确失效对应槽；不全量清空用户缓存来掩盖 key 迁移错误。

### T25 — 实现进程死亡后的安全收尾与可核对恢复

- **历史任务 T25（原稿未实施，已停止维护）**

**优先级：** P0　**前置：** T22、T23、T24

**文件范围（实施工作区）：**
- 新增：[app/src/main/java/com/bd2toolsbox/core/operations/OperationRecovery.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/core/operations/OperationRecovery.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/core/storage/OperationJournalStore.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/core/storage/OperationJournalStore.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/Bd2Application.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/Bd2Application.kt>)
- 新增测试：[app/src/androidTest/java/com/bd2toolsbox/core/operations/ProcessDeathRecoveryTest.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/androidTest/java/com/bd2toolsbox/core/operations/ProcessDeathRecoveryTest.kt>)

**落实要求：**
- 重启时检查未收尾 journal：依据提交阶段和实际文件内容标记中断、已提交或待人工核对，不自动重放所有安装步骤。
- 沿用扫描已有检查点；转换/安装不承诺任意断点无缝续跑。UI 明示中断与下一步，不能一直显示运行中或悄悄清掉记录。
- 清理路径必须来自已验证的本任务工作目录并再次校验归属；不得递归清理用户选择的 MOD 根、游戏目录、备份根或任意 journal 字符串路径。

**验收与回归：**
- 在准备、暂存、目标替换后、账本写入前后杀进程，再启动不会截断旧目标、重复叠加或丢失已提交事实。
- 伪造/过期 journal 路径不能导致边界外删除；不存在参数或缓存时给出可行动错误，而非成功。

**执行/验证命令：**
~~~text
Test-Bd2Device -TestClass 'com.bd2toolsbox.core.operations.ProcessDeathRecoveryTest' -Abi 'arm64-v8a'
Test-Bd2Device -TestClass 'com.bd2toolsbox.core.operations.ProcessDeathRecoveryTest' -Abi 'x86_64'
~~~

**完成证据：** [T25.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T25.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 保留待核对日志与原文件；恢复逻辑不确定时安全停下，不以清空数据或自动回滚所有目标猜测事实。

### T26 — 验收后台生命周期、通知与资源释放

- **历史任务 T26（原稿未实施，已停止维护）**

**优先级：** P0　**前置：** T25、T15

**文件范围（实施工作区）：**
- 修改：[app/src/main/java/com/bd2toolsbox/service/InstallService.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/service/InstallService.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/service/ScanService.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/service/ScanService.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/service/PrepackService.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/service/PrepackService.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/MainActivity.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/MainActivity.kt>)
- 新增测试：[app/src/androidTest/java/com/bd2toolsbox/core/operations/OperationHostLifecycleTest.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/androidTest/java/com/bd2toolsbox/core/operations/OperationHostLifecycleTest.kt>)

**落实要求：**
- 覆盖切页、旋转、退后台、息屏、Activity 真正结束、Service 重建与最后任务完成；以任务宿主状态决定通知和 Binder 生命周期。
- 检查成功、失败、取消三条路径的 Job、文件流、Binder 占用、临时对象与通知都释放；不留下持有旧 Activity/ViewModel 的闭包。
- 采用 Android 前台服务约束支持的运行方式；拒绝通知、系统限制或超时时仍能准确告知状态，不能承诺进程永不被杀。

**验收与回归：**
- 任务中销毁界面再进入可观察同一任务，完成后不存在旧 ViewModel 持有链或残留运行通知。
- 提交临界区取消、系统终止、Binder 断连分别得到正确状态；两 ABI 上至少各验证一条真实长任务路径。

**执行/验证命令：**
~~~text
Test-Bd2Device -TestClass 'com.bd2toolsbox.core.operations.OperationHostLifecycleTest' -Abi 'arm64-v8a'
Test-Bd2Device -TestClass 'com.bd2toolsbox.core.operations.OperationHostLifecycleTest' -Abi 'x86_64'
~~~

**完成证据：** [T26.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T26.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 某系统不允许继续运行时安全停止并记录，而不是关闭限制检查或依赖非受控全局线程。

## G5 — 按功能拆分与图片加载

### T27 — 抽取设置存储与可迁移的外观配置

- **历史任务 T27（原稿未实施，已停止维护）**

**优先级：** P1　**前置：** T20

**文件范围（实施工作区）：**
- 新增：[app/src/main/java/com/bd2toolsbox/core/storage/SettingsRepository.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/core/storage/SettingsRepository.kt>)
- 新增：[app/src/main/java/com/bd2toolsbox/core/model/AppearanceSettings.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/core/model/AppearanceSettings.kt>)
- 新增：[app/src/main/java/com/bd2toolsbox/feature/settings/SettingsViewModel.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/feature/settings/SettingsViewModel.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/ui/viewmodel/MainViewModel.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/viewmodel/MainViewModel.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/ui/screens/WorkbenchSheet.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/screens/WorkbenchSheet.kt>)
- 新增测试：[app/src/test/java/com/bd2toolsbox/core/storage/SettingsMigrationTest.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/test/java/com/bd2toolsbox/core/storage/SettingsMigrationTest.kt>)

**落实要求：**
- 迁出主题、壁纸、引导、来源目录、质量、备份开关等配置；先保留 SharedPreferences 及原键值，不把 DataStore 当 Hilt 迁移前提。
- AppearanceSettings 定义 ThemeMode.LIGHT/DARK/SYSTEM、配色预设名、dynamicColor 开关、壁纸 URI 字符串和背景遮罩值；核心模型不依赖 UI ColorScheme 或 Android Context。
- 旧用户没有模式字段时默认 LIGHT，保持当前外观；用户可选择 DARK/SYSTEM。校验已撤销 URI 权限、损坏数值和未知预设名的回退，不能丢来源目录或备份设置。

**验收与回归：**
- 0.2.16 配置升级后质量、备份开关、来源目录和壁纸保持；缺失新键不抛异常，模式默认明确。
- 未知预设/损坏透明度安全回退，写入失败可观察；旋转后设置来自同一事实源。

**执行/验证命令：**
~~~text
Test-Bd2Unit -TestClass 'com.bd2toolsbox.core.storage.SettingsMigrationTest'
~~~

**完成证据：** [T27.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T27.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 原配置键保留可回读；不通过清空偏好设置“解决”迁移，不顺手迁移全部存储框架。

### T28 — 抽取 MOD 列表状态、投影和交互入口

- **历史任务 T28（原稿未实施，已停止维护）**

**优先级：** P1　**前置：** T22、T23、T27

**文件范围（实施工作区）：**
- 新增：[app/src/main/java/com/bd2toolsbox/feature/mods/ModLibraryViewModel.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/feature/mods/ModLibraryViewModel.kt>)
- 新增：[app/src/main/java/com/bd2toolsbox/feature/mods/ModUiState.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/feature/mods/ModUiState.kt>)
- 新增：[app/src/main/java/com/bd2toolsbox/feature/mods/ModRoute.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/feature/mods/ModRoute.kt>)
- 新增：[app/src/main/java/com/bd2toolsbox/feature/mods/ModScreen.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/feature/mods/ModScreen.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/ui/screens/ModScreen.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/screens/ModScreen.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/ui/components/ModListComponents.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/components/ModListComponents.kt>)
- 新增测试：[app/src/test/java/com/bd2toolsbox/feature/mods/ModLibraryViewModelTest.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/test/java/com/bd2toolsbox/feature/mods/ModLibraryViewModelTest.kt>)

**落实要求：**
- Route 获取 ViewModel 并做生命周期感知采集；Screen 只接收 UiState 与事件，不接收整个 MainViewModel，也不在 Composable 扫描、安装或读账本。
- 分组/筛选/搜索/选中为可测投影，使用稳定 ID；保留异常、待更新、未识别 MOD 分区和既有批量选择行为。
- SavedStateHandle 只保存小型恢复参数，不塞整份 MOD 列表/图片/大批文件对象；大型状态由仓库或任务状态恢复。控制列表 keys/contentType 与重算范围，以量测而非乱加 @Stable 作为性能依据。

**验收与回归：**
- 搜索、状态过滤、角色分组、批量选择交叉操作结果正确；列表更新不让选择跳到别的 MOD。
- 旋转/重进页面保留合理的筛选与滚动恢复，后台任务不重启；大列表不触发保存状态过大。

**执行/验证命令：**
~~~text
Test-Bd2Unit -TestClass 'com.bd2toolsbox.feature.mods.ModLibraryViewModelTest'
~~~

**完成证据：** [T28.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T28.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 保留状态/动作语义对照表；新旧页面不得各自维护一套安装事实，迁移后删除旧转发壳。

### T29 — 拆出预览、工具流程和缓存管理

- **历史任务 T29（原稿未实施，已停止维护）**

**优先级：** P1　**前置：** T24、T27

**文件范围（实施工作区）：**
- 新增：[app/src/main/java/com/bd2toolsbox/feature/preview/PreviewViewModel.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/feature/preview/PreviewViewModel.kt>)
- 新增：[app/src/main/java/com/bd2toolsbox/feature/tools/ToolsViewModel.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/feature/tools/ToolsViewModel.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/data/repository/PreviewCacheRepository.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/data/repository/PreviewCacheRepository.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/data/repository/SpineRuntimeRepository.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/data/repository/SpineRuntimeRepository.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/SpinePreviewActivity.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/SpinePreviewActivity.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/ui/viewmodel/MainViewModel.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/viewmodel/MainViewModel.kt>)
- 新增测试：[app/src/androidTest/java/com/bd2toolsbox/feature/preview/PreviewWorkflowTest.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/androidTest/java/com/bd2toolsbox/feature/preview/PreviewWorkflowTest.kt>)

**落实要求：**
- 迁出预览准备、解包/合并工具、缓存用量和清理流程；耗时操作交给用例/任务，不在页面里直接调用 Python 或遍历磁盘。
- 保留静态图与 Spine 入口、已有运行时获取策略、尺寸分槽和失败缓存语义，不为重构改变 pixi-spine 分发方式。
- 页面离开时释放本页面图片/WebView/播放器资源；任务进度由任务宿主持有，预览面板收起不误取消其他操作。缓存清理按范围执行并与活跃任务互斥。

**验收与回归：**
- 同一角色不同质量/槽位的预览不串用；关闭再打开无已销毁 WebView 回调或重复引擎初始化。
- 清缓存失败准确反馈，不能把未成功删除的空间直接显示为0；已缓存离线能力保持原有约定。

**执行/验证命令：**
~~~text
Test-Bd2Device -TestClass 'com.bd2toolsbox.feature.preview.PreviewWorkflowTest' -Abi 'arm64-v8a'
Test-Bd2Device -TestClass 'com.bd2toolsbox.feature.preview.PreviewWorkflowTest' -Abi 'x86_64'
~~~

**完成证据：** [T29.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T29.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 保留已知可用缓存与运行时来源；只失效不兼容项，不用删除全部缓存掩盖生命周期问题。

### T30 — 拆分攻略网络、缓存、渲染并修复请求竞态

- **历史任务 T30（原稿未实施，已停止维护）**

**优先级：** P1　**前置：** T20、T05

**文件范围（实施工作区）：**
- 修改：[app/src/main/java/com/bd2toolsbox/data/repository/GamekeeRepository.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/data/repository/GamekeeRepository.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/data/repository/GamekeeBattleV2Renderer.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/data/repository/GamekeeBattleV2Renderer.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/ui/viewmodel/GuideViewModel.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/viewmodel/GuideViewModel.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/ui/screens/GuideScreen.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/screens/GuideScreen.kt>)
- 新增：[app/src/main/java/com/bd2toolsbox/feature/guides/data/GamekeeApi.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/feature/guides/data/GamekeeApi.kt>)
- 新增：[app/src/main/java/com/bd2toolsbox/feature/guides/data/GuideCacheStore.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/feature/guides/data/GuideCacheStore.kt>)
- 新增：[app/src/main/java/com/bd2toolsbox/feature/guides/render/GuideHtmlRenderer.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/feature/guides/render/GuideHtmlRenderer.kt>)
- 新增测试：[app/src/test/java/com/bd2toolsbox/feature/guides/GuideRequestRaceTest.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/test/java/com/bd2toolsbox/feature/guides/GuideRequestRaceTest.kt>)
- 保留测试并执行：[app/src/test/java/com/bd2toolsbox/data/repository/GamekeeBattleV2RendererTest.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/test/java/com/bd2toolsbox/data/repository/GamekeeBattleV2RendererTest.kt>)

**落实要求：**
- 按 API 请求、缓存、HTML/阵容渲染拆分职责；保留 game-alias、XHR、Referer、v2/v3 数据结构、缓存隔离、原文跳转与离线阅读语义。
- 为搜索、目录和文章请求建立取消/请求代次控制；只有仍对应当前用户意图的结果可提交。关闭文章后，迟到结果不得重新打开文章。
- JSON/HTML 解析与 Android 展示分离，纯渲染可测试；错误明确区分网络、解析、无内容、缓存命中和取消，不用空结果掩盖失败。

**验收与回归：**
- A 请求先发后到、B 请求后发先到时最终展示 B；关闭文章后 A 返回也不能重开。
- 已有 v2 阵容测试、回合/站位、嵌套 JSON 和离线缓存样本输出一致；取消不会被转换成普通网络错误覆盖新状态。

**执行/验证命令：**
~~~text
Test-Bd2Unit -TestClass 'com.bd2toolsbox.feature.guides.GuideRequestRaceTest'
Test-Bd2Unit -TestClass 'com.bd2toolsbox.data.repository.GamekeeBattleV2RendererTest'
~~~

**完成证据：** [T30.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T30.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 保留接口/渲染 golden 样本与缓存版本；若改变渲染缓存格式，精确迁移或失效本类缓存，不清其他功能数据。

### T31 — 把兑换流程移出 Composable 并建立真实结果模型

- **历史任务 T31（原稿未实施，已停止维护）**

**优先级：** P1　**前置：** T20、T08

**文件范围（实施工作区）：**
- 修改：[app/src/main/java/com/bd2toolsbox/ui/screens/CdkScreen.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/screens/CdkScreen.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/data/repository/Bd2RedeemRepository.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/data/repository/Bd2RedeemRepository.kt>)
- 新增：[app/src/main/java/com/bd2toolsbox/feature/cdk/CdkViewModel.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/feature/cdk/CdkViewModel.kt>)
- 新增：[app/src/main/java/com/bd2toolsbox/feature/cdk/CdkUiState.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/feature/cdk/CdkUiState.kt>)
- 新增：[app/src/main/java/com/bd2toolsbox/feature/cdk/RedeemSummary.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/feature/cdk/RedeemSummary.kt>)
- 新增测试：[app/src/test/java/com/bd2toolsbox/feature/cdk/CdkViewModelTest.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/test/java/com/bd2toolsbox/feature/cdk/CdkViewModelTest.kt>)

**落实要求：**
- Composable 不再直接创建两个 Repository、加载历史或发起批量兑换；ViewModel/use case 负责账号、任务状态、重试和结果写入，Screen 只显示状态并上报事件。
- 区分成功、服务器已兑换、终局失败、可重试失败、未处理和结果未知；“全部处理完”不等于“全部成功”。按账号、按兑换码保存真实结果并计算汇总。
- 保留复制、历史、多账号和批量能力；并发/重复点击有控制，网络超时或取消后无法确认的结果不得记成成功或确定终局失败。持久化失败可见。
- RedeemDisposition 固定区分 SUCCESS、ALREADY_REDEEMED、TERMINAL_FAILURE、RETRYABLE_FAILURE、NOT_ATTEMPTED、UNKNOWN；summarizeRedemption 返回 successCount、alreadyRedeemedCount、terminalFailureCount、pendingCount、unknownCount，以及 settled/allRedeemed 属性；空集合不能显示全部兑换成功。

**验收与回归：**
- 全是终局失败时可以标为已处理，但成功数必须为0；混合结果、新增/删除账号、重复兑换和超时都有正确汇总。
- 屏幕重组/旋转不重复提交请求；历史读写失败不会静默清空，真实账号数据不进入测试仓库。

**执行/验证命令：**
~~~text
Test-Bd2Unit -TestClass 'com.bd2toolsbox.feature.cdk.CdkViewModelTest'
~~~

**完成证据：** [T31.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T31.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 先保留兼容的历史读法，结果语义升级不篡改过去记录；不能靠把失败都算已兑换来隐藏重试按钮。

### T32 — 拆分更新检查、下载状态与安装器交接

- **历史任务 T32（原稿未实施，已停止维护）**

**优先级：** P1　**前置：** T20、T15

**文件范围（实施工作区）：**
- 修改：[app/src/main/java/com/bd2toolsbox/data/repository/UpdateRepository.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/data/repository/UpdateRepository.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/MainActivity.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/MainActivity.kt>)
- 新增：[app/src/main/java/com/bd2toolsbox/feature/update/UpdateViewModel.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/feature/update/UpdateViewModel.kt>)
- 新增：[app/src/main/java/com/bd2toolsbox/feature/update/ApkSelector.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/feature/update/ApkSelector.kt>)
- 新增测试：[app/src/test/java/com/bd2toolsbox/feature/update/ApkSelectorTest.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/test/java/com/bd2toolsbox/feature/update/ApkSelectorTest.kt>)
- 新增测试：[app/src/androidTest/java/com/bd2toolsbox/feature/update/UpdateFlowTest.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/androidTest/java/com/bd2toolsbox/feature/update/UpdateFlowTest.kt>)

**落实要求：**
- 版本检查、下载队列与完成状态移出 MainActivity/MainViewModel；权限 launcher、安装器 Intent 和 UI 生命周期留在 Android 入口。
- 保留现有 DownloadManager STATUS_SUCCESSFUL 检查，不误当成缺失重写。增加失败、取消、广播重投递、页面重建时的去重与可恢复提示。
- 按设备 ABI 和发布资产元数据/现有命名规则选择 arm64 或 x86_64 APK，为 T49 的双独立包做兼容；没有匹配资产时明确失败，不退回下载错误架构或偷偷使用 universal。

**验收与回归：**
- 同一广播多次到达只触发一次正确状态变化；未成功下载不得拉起安装器。
- arm64、x86_64、未知 ABI 与资产缺失用例选择正确；应用标识、签名更新路径和 FileProvider 授权保持。

**执行/验证命令：**
~~~text
Test-Bd2Unit -TestClass 'com.bd2toolsbox.feature.update.ApkSelectorTest'
Test-Bd2Device -TestClass 'com.bd2toolsbox.feature.update.UpdateFlowTest' -Abi 'arm64-v8a'
Test-Bd2Device -TestClass 'com.bd2toolsbox.feature.update.UpdateFlowTest' -Abi 'x86_64'
~~~

**完成证据：** [T32.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T32.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 保留既有更新渠道和人工重试入口；不为结构拆分更改下载源、自动安装策略或应用签名。

### T33 — 拆分 Activity、巨型对话框与页面路由

- **历史任务 T33（原稿未实施，已停止维护）**

**优先级：** P1　**前置：** T28、T29、T30、T31、T32

**文件范围（实施工作区）：**
- 修改：[app/src/main/java/com/bd2toolsbox/MainActivity.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/MainActivity.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/ui/dialogs/AppDialogs.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/dialogs/AppDialogs.kt>)
- 新增：[app/src/main/java/com/bd2toolsbox/ui/AppRoot.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/AppRoot.kt>)
- 新增：[app/src/main/java/com/bd2toolsbox/ui/TopLevelNavigation.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/TopLevelNavigation.kt>)
- 新增：[app/src/main/java/com/bd2toolsbox/feature/install/InstallDialogs.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/feature/install/InstallDialogs.kt>)
- 新增：[app/src/main/java/com/bd2toolsbox/feature/settings/SettingsDialogs.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/feature/settings/SettingsDialogs.kt>)
- 新增测试：[app/src/androidTest/java/com/bd2toolsbox/ui/NavigationStateTest.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/androidTest/java/com/bd2toolsbox/ui/NavigationStateTest.kt>)

**落实要求：**
- MainActivity 只保留 Android 入口、权限/安装器等系统交接和根 UI 装配；按 feature 归属迁移对话框，通用部分以小组件复用，不把原大文件完整搬成另一个大文件。
- 保留 Mods/攻略/兑换码三级顶层入口及现有角色/工作台入口；使用可保存的小型路由状态，返回行为和弹窗状态明确，不把全部临时标志放在 Activity。
- 页面采集使用 collectAsStateWithLifecycle，后台任务执行不随 UI 采集暂停。当前浅层导航先采用明确的路由状态，不强制引入 Navigation 3/其他新框架。

**验收与回归：**
- 切 tab、打开子页/弹窗、系统返回和旋转不丢失关键状态或叠出多个冲突弹窗。
- 退出/重进预览和任务面板不会重复执行；Screen 参数不再包含整个 MainViewModel 或 Repository。

**执行/验证命令：**
~~~text
Test-Bd2Device -TestClass 'com.bd2toolsbox.ui.NavigationStateTest' -Abi 'arm64-v8a'
Test-Bd2Device -TestClass 'com.bd2toolsbox.ui.NavigationStateTest' -Abi 'x86_64'
~~~

**完成证据：** [T33.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T33.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 按一条完整用户路径迁移并保留可对照截图；不在同一个提交同时改导航结构、业务语义和配色。

### T34 — 验证并统一头像、缩略图的加载与缓存

- **历史任务 T34（原稿未实施，已停止维护）**

**优先级：** P1　**前置：** T17、T30、T28

**文件范围（实施工作区）：**
- 修改：[app/src/main/java/com/bd2toolsbox/data/repository/AvatarRepository.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/data/repository/AvatarRepository.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/data/repository/GamekeeRepository.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/data/repository/GamekeeRepository.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/ui/components/AvatarImage.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/components/AvatarImage.kt>)
- 新增：[app/src/main/java/com/bd2toolsbox/core/images/AppImageLoader.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/core/images/AppImageLoader.kt>)
- 新增：[app/src/main/java/com/bd2toolsbox/di/ImageModule.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/di/ImageModule.kt>)
- 修改：[gradle/libs.versions.toml](<D:/bd2 mod/worktrees/bdroid-android-refactor/gradle/libs.versions.toml>)
- 新增测试：[app/src/androidTest/java/com/bd2toolsbox/core/images/ImagePipelineTest.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/androidTest/java/com/bd2toolsbox/core/images/ImagePipelineTest.kt>)

**落实要求：**
- 以 Coil 3 作为首选验证替换方案，选与 T13 匹配的稳定版本；只加入实际需要的 Compose 和一种网络模块，不为图片顺带引入 Retrofit/Ktor 全家桶或额外动画解码器。
- 共享单一 ImageLoader/必要 HTTP 客户端，保留 Gamekee Referer、头像 URL/缓存键、预取、离线命中和坏文件处理；原本必须保留的离线文件不能直接交给可自动淘汰的临时磁盘缓存。
- 替换无容量上限的已解码图片 Map，明确内存缓存上限、尺寸采样、并发和取消；列表用统一占位/失败/重试状态。量测新增依赖的 release 体积与内存成本，不声称换 Coil 必然减包。
- 若版本/缓存契约验证不通过，完成同样的有界加载 interface 与修复后的现有 adapter，并记录不采用 Coil 的实测理由；不能同时长期保留两套重复下载/解码。

**验收与回归：**
- 无网络时原有可离线图片仍命中；Referer 缺失/404/损坏文件/取消/快速滚动各有可解释状态。
- 连续浏览大量不同图片后缓存受限，退出页面后请求可取消；清理语义不伤原本固定保留的资源，实际 release 增量和峰值内存有报告。

**执行/验证命令：**
~~~text
Test-Bd2Device -TestClass 'com.bd2toolsbox.core.images.ImagePipelineTest' -Abi 'arm64-v8a'
Test-Bd2Device -TestClass 'com.bd2toolsbox.core.images.ImagePipelineTest' -Abi 'x86_64'
Show-Bd2Dependencies -Dependency 'coil'
~~~

**完成证据：** [T34.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T34.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 切换前保留旧缓存可读 adapter；不删用户离线资源。探针结论不通过时保留一种实现，不强上新库。

### T62 — 防止 MOD 贴图进入系统相册的目录级媒体扫描隔离

- **历史任务 T62（原稿未实施，已停止维护）**

**优先级：** P1　**前置：** T19、T27、T28、T29

**状态：** 根据用户补充问题提出的方案，未实施。仅应用新建且用途明确的 MOD 专用目录可默认设置；对用户已有目录的写入必须确认影响范围。

**文件范围（实施工作区）：**
- 新增：[MediaExclusionManager.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/core/storage/MediaExclusionManager.kt>)
- 修改：[SafManager.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/utils/SafManager.kt>)
- 修改：[SettingsViewModel.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/feature/settings/SettingsViewModel.kt>)
- 修改：[SettingsDialogs.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/feature/settings/SettingsDialogs.kt>)
- 修改：[ModRepository.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/data/repository/ModRepository.kt>)
- 修改：[PreviewViewModel.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/feature/preview/PreviewViewModel.kt>)、[ToolsViewModel.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/feature/tools/ToolsViewModel.kt>)对应的导出/解包用例
- 读取输出时机：[unpacker.py](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/python/unpacker.py>)
- 修改索引过滤：[repacker.py](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/python/repacker/repacker.py>)
- 新增测试：[MediaExclusionManagerTest.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/test/java/com/bd2toolsbox/core/storage/MediaExclusionManagerTest.kt>)
- 新增测试：[MediaExclusionTest.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/androidTest/java/com/bd2toolsbox/storage/MediaExclusionTest.kt>)
- 新增测试：[test_nomedia_ignored.py](<D:/bd2 mod/worktrees/bdroid-android-refactor/tests/test_nomedia_ignored.py>)

**落实要求：**
- 使用 Android 的 .nomedia 目录约定：在指定 MOD 专用根目录创建名为 .nomedia 的普通空文件，保护该目录及子目录；不改贴图内容、扩展名或 MOD 文件夹名称，不新增依赖库。
- 对本应用新建的专用目录，在第一张贴图写入最终共享目录之前建立标记；不能只在私有暂存区建标记，复制到公开目录后又被扫描。用户通过外部文件管理器解压的文件不受本应用写入时序控制，应提示先设置目录再解压。
- 用户已有来源目录先显示路径和“此目录及子目录中的图片/音视频将不参与系统媒体扫描”的确认；不凭路径名含 mod 就认定应用拥有。禁止默认给存储根、DCIM、Pictures、整个 Download 等混合用途根目录加标记；选择其明确的 MOD 子目录，不递归去修改无关相册。
- 同时支持已有文件访问能力与 SAF：依据实际授权创建/核对 marker，不把 content URI 拼成文件路径，不新增广泛相册读取权限。已有同名文件保持不变；核对 provider 返回的实际名称，不能把 .nomedia.txt/.nomedia.bin 或写入失败报告成已保护。
- 将 .nomedia 明确排除于 MOD 资源枚举、来源内容签名、变更判断、Python 文件索引和重打包输入。当前 ModRepository 的忽略逻辑主要处理 .modfile，新增 marker 不能导致假“MOD已修改”、多出异常条目或参与 TextAsset 替换。
- 权限撤销、只读目录和 provider 不支持时反馈准确。读取/安装已有 MOD 不因无法写 marker 而失效；若新导出无法设置保护，先提示并由用户明确选择继续或更换目录，不静默宣称已屏蔽。
- 已被索引的贴图可能要等待媒体库和相册刷新，按实际路径/权限及系统能力请求局部更新；不能保证添加后立即从所有图库消失。SAF目录、厂商缓存及云备份差异如实说明，不广播全盘重挂载或重置系统媒体库。
- 禁止调用 MediaStore 删除接口或在相册里批量删除图片来“清索引”，这可能删除真实贴图。不得自动删除已有 .nomedia，移除来源目录配置也不顺手取消用户原有隐藏设置；恢复扫描必须是明确操作。

**验收与回归：**
- 系统相册/MediaStore 按正常扫描流程不再新增专用目录中的新贴图，子目录同样有效；目录外对照图片仍正常显示。分别覆盖已有图片再设置、先设置再解压及重新导出的场景。
- 设置前后 MOD 识别数量、资源内容签名、安装状态及重打包结果不因 marker 改变；真实贴图字节/哈希保持。仅含 marker 的目录不被识别为 MOD；标记不参与生成产物内容。
- SAF重命名、权限拒绝、已有 marker、连续点击、导出中断均返回正确状态，不覆盖或删除其他文件，不误保护公共根目录。不能因为 marker 创建成功就声称所有第三方/云端副本已清理。
- 在 API29、34及选定较新平台和实际相册上记录效果。相册自身缓存未刷新时报告其局限与操作指引，不用删除原图让测试变绿。

**执行/验证命令：**
~~~text
Test-Bd2Unit -TestClass 'com.bd2toolsbox.core.storage.MediaExclusionManagerTest'
Invoke-Bd2Python -m unittest discover -s (Join-Path $Root 'tests') -p 'test_nomedia_ignored.py' -v
Test-Bd2Device -TestClass 'com.bd2toolsbox.storage.MediaExclusionTest' -Abi 'arm64-v8a'
Test-Bd2Device -TestClass 'com.bd2toolsbox.storage.MediaExclusionTest' -Abi 'x86_64'
~~~

**完成证据：** [T62.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T62.md>)：记录目录范围、访问方式、marker实际名称、创建时机、扫描/相册表现、MOD输入与产物对照、失败场景；测试中不使用真实私人相册或真实日用MOD目录。

**风险与回退：** .nomedia 是媒体扫描约定，不是加密或访问权限；不能控制无视此约定的第三方自建图库或已上传云端副本。任何回退不得删贴图或用户原有 marker；停止自动创建也不擅自改全机媒体库。

**依据：** [Android MediaStore.MEDIA_IGNORE_FILENAME](https://developer.android.com/reference/android/provider/MediaStore#MEDIA_IGNORE_FILENAME)。仅做方案与源码核对，尚未在用户手机创建任何文件。

## G6 — JSON 迁移与许可证

### T35 — 按数据类别迁移到 kotlinx.serialization

- **历史任务 T35（原稿未实施，已停止维护）**

**优先级：** P0　**前置：** T10、T27、T30、T31、T14

**文件范围（实施工作区）：**
- 新增：[app/src/main/java/com/bd2toolsbox/core/storage/dto/StoredModSnapshot.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/core/storage/dto/StoredModSnapshot.kt>)
- 新增：[app/src/main/java/com/bd2toolsbox/core/storage/dto/StoredInstalledMod.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/core/storage/dto/StoredInstalledMod.kt>)
- 新增：[app/src/main/java/com/bd2toolsbox/core/storage/LegacyJsonReader.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/core/storage/LegacyJsonReader.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/core/storage/InstalledLedgerStore.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/core/storage/InstalledLedgerStore.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/core/storage/ModSnapshotStore.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/core/storage/ModSnapshotStore.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/feature/guides/data/GamekeeApi.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/feature/guides/data/GamekeeApi.kt>)
- 新增测试：[app/src/test/java/com/bd2toolsbox/core/storage/JsonMigrationTest.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/test/java/com/bd2toolsbox/core/storage/JsonMigrationTest.kt>)

**落实要求：**
- 先为持久化事实、可重建缓存、动态网络 JSON 分别定义契约；固定 DTO 使用显式字段名/schema，动态 Wiki 树使用 JsonElement/JsonObject，不序列化整份 UI/Android/Python 对象。
- Kotlin 本身不内置 JSON 库：使用与 Kotlin 匹配的 serialization 插件和独立 runtime。处理旧字段名、缺字段、null、数字、未知字段、枚举及 URI 字符串，不假定 Gson 与新库默认语义相同。
- 按存储逐项双读兼容、单写新格式并保留迁移前原件；迁移失败不能损坏账本/历史。对无法安全理解的快照只精确失效缓存，不把安装记录当缓存丢弃。

**验收与回归：**
- 真实旧版/混淆版 fixture、当前格式、未知 schema、截断 JSON、空文件和多字节中文都有明确读取/迁移结果。
- 装入记录、目录 URI、兑换历史、Wiki v2/v3 嵌套 JSON 的语义与 golden 样本一致；释放版混淆后仍可往返。

**执行/验证命令：**
~~~text
Test-Bd2Unit -TestClass 'com.bd2toolsbox.core.storage.JsonMigrationTest'
Test-Bd2ReleaseSmoke -Case 'json-migration' -Abi 'arm64-v8a'
Test-Bd2ReleaseSmoke -Case 'json-migration' -Abi 'x86_64'
~~~

**完成证据：** [T35.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T35.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 代码回退与数据恢复分别处理；不覆盖迁移后新增事实。必须保留可核对的原件和 journal，不能用清应用数据回退。

### T36 — 删除 App 的 Gson 使用与过渡保留规则

- **历史任务 T36（原稿未实施，已停止维护）**

**优先级：** P1　**前置：** T35、T34

**文件范围（实施工作区）：**
- 修改：[gradle/libs.versions.toml](<D:/bd2 mod/worktrees/bdroid-android-refactor/gradle/libs.versions.toml>)
- 修改：[app/build.gradle.kts](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/build.gradle.kts>)
- 修改：[app/proguard-rules.pro](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/proguard-rules.pro>)
- 修改：[app/src/main/java/com/bd2toolsbox/core/storage/LegacyJsonReader.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/core/storage/LegacyJsonReader.kt>)
- 新增：[docs/refactor/evidence/json-dependency-closure.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/json-dependency-closure.md>)

**落实要求：**
- 删除所有应用源码中的 Gson/TypeToken/旧自定义 adapter 使用及直接依赖；旧格式读取必须已经不依赖 Gson 才能删库。
- 检查 releaseRuntimeClasspath 和实际 APK，区分构建工具自身使用 Gson 与 App 携带 Gson；不得通过盲目 exclude 强行移除仍被运行时依赖调用的库。
- 清理仅为 Gson 反射新增的过渡 keep 规则；保留 Chaquopy/JNI/AIDL/真实动态入口必要规则，之后由 T52 精修。

**验收与回归：**
- 源码扫描无应用 Gson 使用，release 运行时依赖图无 Gson；Gradle/AGP 的构建期依赖不被误报为 APK 依赖。
- 删除后旧数据迁移、冷启动、安装账本与动态 Wiki 解析仍在 minified release 上通过。

**执行/验证命令：**
~~~text
rg -n 'com\.google\.gson|Gson\(|TypeToken|SerializedName' (Join-Path $Root 'app/src')
Show-Bd2Dependencies -Dependency 'gson'
Test-Bd2ReleaseSmoke -Case 'json-migration' -Abi 'arm64-v8a'
~~~

**完成证据：** [T36.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T36.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 发现未迁移调用就补迁移测试，不强行排除依赖。确需回退时成套恢复 adapter/依赖/规则，不混用两套写入格式。

### T37 — 生成完整、可追溯的许可证依赖清单

- **历史任务 T37（原稿未实施，已停止维护）**

**优先级：** P1　**前置：** T12、T14

**文件范围（实施工作区）：**
- 修改：[gradle/libs.versions.toml](<D:/bd2 mod/worktrees/bdroid-android-refactor/gradle/libs.versions.toml>)
- 修改：[app/build.gradle.kts](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/build.gradle.kts>)
- 新增：[config/aboutlibraries/libraries](<D:/bd2 mod/worktrees/bdroid-android-refactor/config/aboutlibraries/libraries>)
- 新增：[config/aboutlibraries/licenses](<D:/bd2 mod/worktrees/bdroid-android-refactor/config/aboutlibraries/licenses>)
- 修改：[THIRD-PARTY-NOTICES.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/THIRD-PARTY-NOTICES.md>)
- 新增：[docs/refactor/license-inventory.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/license-inventory.md>)

**落实要求：**
- 引入匹配工具链和 compileSdk 的 AboutLibraries 插件与 Material3 组件版本；绑定实际生成任务到对应构建 variant，不依赖开发者手工运行一次。
- 区分自动收集的 Gradle 运行时依赖与 Python、vendored、JNI、JS 等非 Maven 组件；后者补自定义定义、版本来源、作者和必须分发的许可证全文。
- 核对当前 excludes/META-INF 打包规则、Spine/pixi 等运行时获取边界和根项目 GPL LICENSE；不能将“自动扫描”当作所有非 Maven 许可义务已完成。

**验收与回归：**
- 许可证清单与最终 App 依赖/资源清单逐项对应，缺少元数据时构建/检查明确提示，不静默漏项。
- 在干净构建与两个最终 ABI variant 下都生成可离线读取的许可数据，版本与依赖锁定记录一致。

**执行/验证命令：**
~~~text
Invoke-Bd2Gradle :app:tasks --all --console=plain | Select-String 'Libraries|LibraryDefinitions|aboutLibraries'
Invoke-Bd2Gradle :app:assembleRelease --console=plain
~~~

**完成证据：** [T37.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T37.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 新清单未核对完前保留旧 licenses 目录和原文；不因为重复或包体原因先删许可。

### T38 — 接入 AboutLibraries 页面并安全退役旧许可证目录

- **历史任务 T38（原稿未实施，已停止维护）**

**优先级：** P1　**前置：** T37、T33

**文件范围（实施工作区）：**
- 修改：[app/src/main/java/com/bd2toolsbox/ui/screens/WorkbenchSheet.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/screens/WorkbenchSheet.kt>)
- 新增：[app/src/main/java/com/bd2toolsbox/feature/settings/LicensesScreen.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/feature/settings/LicensesScreen.kt>)
- 删除前核对：[licenses](<D:/bd2 mod/worktrees/bdroid-android-refactor/licenses>)
- 保留：[LICENSE](<D:/bd2 mod/worktrees/bdroid-android-refactor/LICENSE>)
- 修改：[README.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/README.md>)
- 修改：[README.en.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/README.en.md>)
- 新增测试：[app/src/androidTest/java/com/bd2toolsbox/feature/settings/LicensesScreenTest.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/androidTest/java/com/bd2toolsbox/feature/settings/LicensesScreenTest.kt>)

**落实要求：**
- 用 Material3 许可证页面替换手写展示，接入现有设置/关于入口和返回行为；长文本可滚动、离线打开、跟随主题。
- 逐项核对旧 licenses 中的原文已在正确的新分发位置保留，更新 README/关于页等引用，再删除被替代的目录；项目根 LICENSE 和必需 notices 不删除。
- 把目录删除与构建产物核验绑定：不能只有源码里有原文但 release 被 shrink/exclude 掉。

**验收与回归：**
- 无网络时库列表、作者/版本和许可证全文可读；返回、长文本、大字体无阻塞或截断。
- 源码旧目录引用全部处理，release APK 中所需元数据与全文存在；后续 T39 深色模式与 T53 资源收缩后复验。

**执行/验证命令：**
~~~text
Test-Bd2Device -TestClass 'com.bd2toolsbox.feature.settings.LicensesScreenTest' -Abi 'arm64-v8a'
Test-Bd2Device -TestClass 'com.bd2toolsbox.feature.settings.LicensesScreenTest' -Abi 'x86_64'
rg -n 'licenses[/\\]' (Join-Path $Root 'README.md') (Join-Path $Root 'README.en.md') (Join-Path $Root 'app/src')
~~~

**完成证据：** [T38.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T38.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 许可缺项时保留/恢复原文，不通过删除 About 页面入口规避问题；文档与代码一起回退。

## G7 — 交互、一致性、深色与壁纸

### T39 — 建立浅色、深色、跟随系统的完整主题

- **历史任务 T39（原稿未实施，已停止维护）**

**优先级：** P1　**前置：** T27、T33

**文件范围（实施工作区）：**
- 修改：[app/src/main/java/com/bd2toolsbox/ui/theme/AppThemes.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/theme/AppThemes.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/ui/theme/Theme.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/theme/Theme.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/ui/theme/Color.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/theme/Color.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/ui/theme/Type.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/theme/Type.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/feature/settings/SettingsViewModel.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/feature/settings/SettingsViewModel.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/ui/screens/WorkbenchSheet.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/screens/WorkbenchSheet.kt>)
- 新增测试：[app/src/test/java/com/bd2toolsbox/ui/theme/ThemeContractTest.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/test/java/com/bd2toolsbox/ui/theme/ThemeContractTest.kt>)
- 修改：[app/src/main/res/values/themes.xml](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/res/values/themes.xml>)
- 修改：[app/src/main/res/values/colors.xml](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/res/values/colors.xml>)
- 新增：[app/src/main/res/values-night/themes.xml](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/res/values-night/themes.xml>)

**落实要求：**
- 实现 LIGHT/DARK/SYSTEM 三种模式，七套现有配色预设都有对应的可读明暗方案；Android12+ 动态颜色分别使用明暗 API，低版本稳定回退。旧配置默认 LIGHT，不把新增模式误当已有能力。
- 主题 surface/background 等语义颜色保持不透明；壁纸不修改全局 ColorScheme。统一正文、辅助文字、状态色和系统栏图标明暗，检查启动到 Compose 首帧的颜色衔接。
- 主题设置与壁纸设置独立持久化；默认、选中、失败、警告、禁用状态均在明暗下定义，不能只把浅色颜色反转或全局降低 alpha。
- 提供可独立测试的 appColorScheme(theme: AppTheme, dark: Boolean, dynamicScheme: ColorScheme? = null): ColorScheme；此函数不接收壁纸透明度。

**验收与回归：**
- 全部预设 × LIGHT/DARK、SYSTEM 日夜切换、动态颜色开关与 API29 回退的组合可用，surface/background alpha 均为1。
- 重启后模式保持；切换不触发业务任务重跑，状态栏/导航栏图标不在同色背景消失。

**执行/验证命令：**
~~~text
Test-Bd2Unit -TestClass 'com.bd2toolsbox.ui.theme.ThemeContractTest'
~~~

**完成证据：** [T39.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T39.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 主题改动单独可回退至可靠浅色，但保留用户模式字段；不得恢复全局透明底色来显示壁纸。

### T40 — 重做壁纸与内容分层，先解决看不清

- **历史任务 T40（原稿未实施，已停止维护）**

**优先级：** P0　**前置：** T39

**文件范围（实施工作区）：**
- 修改：[app/src/main/java/com/bd2toolsbox/ui/components/WallpaperBackdrop.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/components/WallpaperBackdrop.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/ui/AppRoot.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/AppRoot.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/ui/screens/WorkbenchSheet.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/screens/WorkbenchSheet.kt>)
- 新增：[app/src/main/java/com/bd2toolsbox/ui/components/AppScreenSurface.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/components/AppScreenSurface.kt>)
- 新增测试：[app/src/androidTest/java/com/bd2toolsbox/ui/WallpaperReadabilityTest.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/androidTest/java/com/bd2toolsbox/ui/WallpaperReadabilityTest.kt>)

**落实要求：**
- 采用独立壁纸背景层和明确内容层：仅根展示区域透明，文字密集列表、搜索/筛选、工具栏、菜单、弹窗、底部面板默认使用不透明主题底色。用连贯内容面，不为每行增加装饰卡片。
- 壁纸在背景、留白和适当展示区可见；不以全局透明让图片穿过关键文字。具体页头/留白构图在本任务先出原生界面样例再核对，依据已确认原则执行而非重开框架选择。
- 壁纸强度仅作用于背景，命名为实际的背景显隐/遮罩含义，不再把遮罩叫分辨率“清晰度”。读取失败、权限撤销、超大图片安全回退；不新增裁切编辑器或特效库作为前置。

**验收与回归：**
- 纯白、纯黑、高饱和、强对比/高纹理壁纸下，正文、辅助信息、按钮和状态均可辨识；遮罩调至两端也不能让关键内容消失。
- 切主题、横竖屏和撤销图片权限不造成透明空洞/崩溃；确认 wallpaper 图像并非被全屏不透明 Scaffold 意外完全遮住。

**执行/验证命令：**
~~~text
Test-Bd2Device -TestClass 'com.bd2toolsbox.ui.WallpaperReadabilityTest' -Abi 'arm64-v8a'
Test-Bd2Device -TestClass 'com.bd2toolsbox.ui.WallpaperReadabilityTest' -Abi 'x86_64'
~~~

**完成证据：** [T40.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T40.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 若展示效果不理想只调整背景展示层，保留可靠内容底色；绝不回退为全局 surface 透明。

### T41 — 统一内容容器、按钮、标签与反馈规范

- **历史任务 T41（原稿未实施，已停止维护）**

**优先级：** P1　**前置：** T40、T33

**文件范围（实施工作区）：**
- 新增：[app/src/main/java/com/bd2toolsbox/ui/components/AppDialog.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/components/AppDialog.kt>)
- 新增：[app/src/main/java/com/bd2toolsbox/ui/components/StatusBadge.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/components/StatusBadge.kt>)
- 新增：[app/src/main/java/com/bd2toolsbox/ui/components/TaskProgressPanel.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/components/TaskProgressPanel.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/ui/components/WorkbenchItems.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/components/WorkbenchItems.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/ui/components/ModListComponents.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/components/ModListComponents.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/ui/theme/Type.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/theme/Type.kt>)
- 新增：[docs/refactor/ui-component-contracts.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/ui-component-contracts.md>)

**落实要求：**
- 梳理真正复用的内容底色、字体层级、间距、形状、按钮优先级、状态标签、空/加载/失败区；使用 Material 语义颜色，不让各页继续散落一套自定义 alpha 和 hex。
- 明确状态标签与操作按钮的区别、主次按钮的位置和文案、错误是否可重试；重要结果不能只靠 Toast 或颜色，普通提示不滥用阻断弹窗。
- 保留现有玩家内容与系统原生操作习惯；不引入大 UI 套件、额外字体包或装饰动画，不以机械卡片化/巨型通用组件替代清晰界面。

**验收与回归：**
- 同一种状态/动作在 Mods、攻略、兑换码和工作台具有同样语义；明暗模式下底色/文字搭配一致。
- 通用组件提供可测试的语义名称和交互状态，页面特殊业务不被塞入通用组件的分支开关。

**执行/验证命令：**
~~~text
Test-Bd2Unit -TestClass 'com.bd2toolsbox.ui.theme.ThemeContractTest'
Test-Bd2Device -TestClass 'com.bd2toolsbox.ui.NavigationStateTest' -Abi 'arm64-v8a'
Test-Bd2Device -TestClass 'com.bd2toolsbox.ui.NavigationStateTest' -Abi 'x86_64'
~~~

**完成证据：** [T41.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T41.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 按组件切换并对照核心页面；不通过恢复每页独立样式解决单处布局问题。

### T42 — 让预览、选择和更多操作可发现且一致

- **历史任务 T42（原稿未实施，已停止维护）**

**优先级：** P1　**前置：** T41、T28、T29

**文件范围（实施工作区）：**
- 修改：[app/src/main/java/com/bd2toolsbox/feature/mods/ModScreen.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/feature/mods/ModScreen.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/ui/components/ModListComponents.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/components/ModListComponents.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/ui/screens/CharacterScreen.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/screens/CharacterScreen.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/ui/screens/CharacterSheet.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/screens/CharacterSheet.kt>)
- 新增测试：[app/src/androidTest/java/com/bd2toolsbox/ui/ModActionsTest.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/androidTest/java/com/bd2toolsbox/ui/ModActionsTest.kt>)

**落实要求：**
- 提供明确的预览/更多操作入口；长按保留为熟手捷径，不再是唯一入口。“已转换”等标签优先表达状态，不用相同外观却无响应的假按钮。
- 区分卡片选择、复选框、预览与删除点击区域，避免一次点击同时选中并打开/删除；触控与 TalkBack 语义都可辨识。
- 不支持直接预览的资源说明原因或沿用已有准备路径，不凭空承诺新格式支持；保留批量选择效率。

**验收与回归：**
- 首次使用者无需长按即可找到可用预览；点击预览不改变选择，点击选择不启动预览。
- 键盘/辅助功能激活与触摸操作一致，禁用状态有原因；不同资源类型的入口不会出现“看起来可点但什么也不做”。

**执行/验证命令：**
~~~text
Test-Bd2Device -TestClass 'com.bd2toolsbox.ui.ModActionsTest' -Abi 'arm64-v8a'
Test-Bd2Device -TestClass 'com.bd2toolsbox.ui.ModActionsTest' -Abi 'x86_64'
~~~

**完成证据：** [T42.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T42.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 保留熟手长按捷径和原选择语义；不能为了入口明显而增加多余强制确认步骤。

### T43 — 任务面板可收起，取消只在安全阶段开放

- **历史任务 T43（原稿未实施，已停止维护）**

**优先级：** P0　**前置：** T41、T26

**文件范围（实施工作区）：**
- 修改：[app/src/main/java/com/bd2toolsbox/ui/components/TaskProgressPanel.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/components/TaskProgressPanel.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/feature/install/InstallDialogs.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/feature/install/InstallDialogs.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/feature/preview/PreviewViewModel.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/feature/preview/PreviewViewModel.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/ui/AppRoot.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/AppRoot.kt>)
- 新增测试：[app/src/androidTest/java/com/bd2toolsbox/ui/TaskPanelLifecycleTest.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/androidTest/java/com/bd2toolsbox/ui/TaskPanelLifecycleTest.kt>)

**落实要求：**
- 准备、转换、安装、预解包的进行中界面提供“收起，继续处理”和可重新打开的统一入口，不再把所有阶段锁在不可退出的对话框里。
- 收起只改变展示状态，绝不取消实际任务或释放仍使用的 Binder；按任务 ID 重连，提交临界区禁用取消但仍允许收起。
- 显示阶段、真实进度、部分成功、失败原因和下一步；完成/中断后状态清楚，重试只针对符合条件的失败目标，不重复已提交项。

**验收与回归：**
- 启动真实协调器任务后点击收起、切页、旋转，再打开仍显示同一任务且执行次数为1。
- 提交阶段不能被立即取消；完成/失败/中断有对应结果，用户不会被永久卡在“处理中”。

**执行/验证命令：**
~~~text
Test-Bd2Device -TestClass 'com.bd2toolsbox.ui.TaskPanelLifecycleTest' -Abi 'arm64-v8a'
Test-Bd2Device -TestClass 'com.bd2toolsbox.ui.TaskPanelLifecycleTest' -Abi 'x86_64'
~~~

**完成证据：** [T43.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T43.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 不能只删除 onDismiss 限制而未接好任务所有权；依赖任务层未通过时保留安全行为，不假装功能完成。

### T44 — 统一危险操作确认和真实结果反馈

- **历史任务 T44（原稿未实施，已停止维护）**

**优先级：** P0　**前置：** T41、T21、T27

**文件范围（实施工作区）：**
- 修改：[app/src/main/java/com/bd2toolsbox/ui/screens/WorkbenchSheet.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/screens/WorkbenchSheet.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/feature/settings/SettingsDialogs.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/feature/settings/SettingsDialogs.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/core/storage/SettingsRepository.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/core/storage/SettingsRepository.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/data/repository/BundleBackupRepository.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/data/repository/BundleBackupRepository.kt>)
- 新增测试：[app/src/androidTest/java/com/bd2toolsbox/ui/DestructiveActionConfirmationTest.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/androidTest/java/com/bd2toolsbox/ui/DestructiveActionConfirmationTest.kt>)

**落实要求：**
- 清空原版备份前显示当前数量、空间和失去本地恢复资源的后果；按钮明确写“清空原版备份”，取消不产生文件操作。
- 与活跃安装/卸载/预解包互斥；确认后若目标集合已变化，重新核对而不是删除未确认的新备份。普通可再生预览缓存不一律添加繁琐确认。
- 操作失败显示真实失败，重新统计实际用量，不能在删除未成功时直接把数量/空间设成0；目录删除、覆盖等现有确认同步统一。

**验收与回归：**
- 取消确认后备份哈希/数量不变；确认后仅处理所确认范围；任务正在使用备份时不能清理。
- 权限失败/部分删除/磁盘异常的显示与实际文件一致，不给虚假成功，也不承诺并不存在的撤销。

**执行/验证命令：**
~~~text
Test-Bd2Device -TestClass 'com.bd2toolsbox.ui.DestructiveActionConfirmationTest' -Abi 'arm64-v8a'
Test-Bd2Device -TestClass 'com.bd2toolsbox.ui.DestructiveActionConfirmationTest' -Abi 'x86_64'
~~~

**完成证据：** [T44.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T44.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 保留原确认保护与准确结果；文案/样式可回退，但不恢复无确认的备份清空。

### T45 — 纠正兑换“已处理”等同“已兑换”的界面语义

- **历史任务 T45（原稿未实施，已停止维护）**

**优先级：** P1　**前置：** T41、T31

**文件范围（实施工作区）：**
- 修改：[app/src/main/java/com/bd2toolsbox/feature/cdk/CdkUiState.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/feature/cdk/CdkUiState.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/feature/cdk/RedeemSummary.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/feature/cdk/RedeemSummary.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/ui/screens/CdkScreen.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/screens/CdkScreen.kt>)
- 新增测试：[app/src/test/java/com/bd2toolsbox/feature/cdk/RedeemSummaryTest.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/test/java/com/bd2toolsbox/feature/cdk/RedeemSummaryTest.kt>)
- 新增测试：[app/src/androidTest/java/com/bd2toolsbox/ui/CdkResultPresentationTest.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/androidTest/java/com/bd2toolsbox/ui/CdkResultPresentationTest.kt>)

**落实要求：**
- 依据 T31 的真实结果显示成功、已兑换、已处理但未成功、可重试或结果未知，不能只把 settledAll 改变量名后继续输出成功标签。
- 批量结果按账号显示成功/失败/待处理数量和原因；入口是否允许重试由结果性质决定，不能以“按钮会误导”为由把失败涂成成功。
- 复制、历史查看、账号切换、网络错误/空列表的反馈采用统一组件，重要失败保留可查看信息，不只瞬间 Toast。

**验收与回归：**
- 两个账号都终局失败时绝不出现表示领取成功的行级标签；一成功一失败显示准确数量和原因。
- 新增账号会重新计算待处理项；超时结果未知不会被永久记成已领取，历史数据不被 UI 汇总覆盖。

**执行/验证命令：**
~~~text
Test-Bd2Unit -TestClass 'com.bd2toolsbox.feature.cdk.RedeemSummaryTest'
Test-Bd2Device -TestClass 'com.bd2toolsbox.ui.CdkResultPresentationTest' -Abi 'arm64-v8a'
Test-Bd2Device -TestClass 'com.bd2toolsbox.ui.CdkResultPresentationTest' -Abi 'x86_64'
~~~

**完成证据：** [T45.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T45.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 保留原始服务端结果/历史，不用数据改写配合新文案；问题在呈现层时只回退映射而不删记录。

### T46 — 完成手机横竖屏、大字体、Insets 与辅助功能适配

- **历史任务 T46（原稿未实施，已停止维护）**

**优先级：** P1　**前置：** T41、T42、T43、T44、T45、T15

**文件范围（实施工作区）：**
- 修改：[app/src/main/java/com/bd2toolsbox/ui/AppRoot.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/AppRoot.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/ui/TopLevelNavigation.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/TopLevelNavigation.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/feature/mods/ModScreen.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/feature/mods/ModScreen.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/ui/screens/GuideScreen.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/screens/GuideScreen.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/ui/screens/CdkScreen.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/screens/CdkScreen.kt>)
- 新增测试：[app/src/androidTest/java/com/bd2toolsbox/ui/AdaptiveAccessibilityTest.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/androidTest/java/com/bd2toolsbox/ui/AdaptiveAccessibilityTest.kt>)

**落实要求：**
- 处理状态栏、导航栏、刘海与 IME insets；横屏/窄屏下关键操作和对话框内容可达，大屏不溢出但不增加专用平板双栏或折叠屏布局。
- 验证实际点击区域至少48dp、图标/状态具有可理解语义，状态不能仅靠颜色；装饰图片不重复朗读，焦点与返回顺序正确。
- 验证字体1.0/1.3/2.0倍、长文件名/中文/空/错误内容、系统返回和可适用的预测返回；不通过固定像素字号、强行截断关键原因或锁定竖屏掩盖问题。

**验收与回归：**
- 软键盘出现时搜索/兑换输入与提交按钮可达；旋转后任务与选择不重复执行；大字体不把确认/取消挤出屏幕。
- TalkBack 能发现预览、收起、重试与危险操作；触控区域按真实语义边界检查，不能只看图标尺寸推断通过。

**执行/验证命令：**
~~~text
Test-Bd2Device -TestClass 'com.bd2toolsbox.ui.AdaptiveAccessibilityTest' -Abi 'arm64-v8a'
Test-Bd2Device -TestClass 'com.bd2toolsbox.ui.AdaptiveAccessibilityTest' -Abi 'x86_64'
~~~

**完成证据：** [T46.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T46.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 保留基本适配，缩小装饰或调整布局而不缩小可读字号/点击范围；原设备系统设置测试后恢复。

### T47 — 让攻略阅读器和 Spine 预览跟随新主题

- **历史任务 T47（原稿未实施，已停止维护）**

**优先级：** P1　**前置：** T39、T29、T30、T46

**文件范围（实施工作区）：**
- 修改：[app/src/main/java/com/bd2toolsbox/feature/guides/render/GuideHtmlRenderer.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/feature/guides/render/GuideHtmlRenderer.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/ui/screens/GuideScreen.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/screens/GuideScreen.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/SpinePreviewActivity.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/SpinePreviewActivity.kt>)
- 修改：[app/src/main/assets/spine-viewer/preview.html](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/assets/spine-viewer/preview.html>)
- 修改：[app/src/main/assets/spine-viewer/css/main.css](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/assets/spine-viewer/css/main.css>)
- 新增测试：[app/src/androidTest/java/com/bd2toolsbox/ui/WebContentThemeTest.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/androidTest/java/com/bd2toolsbox/ui/WebContentThemeTest.kt>)

**落实要求：**
- 原生容器与 WebView 正文/预览控制层共享明确的明暗模式和可读底色；必要时只调整主题包装/CSS，不改 Wiki 内容、阵容数据或 Spine 动画协议。
- 检查从缓存加载的旧 HTML 也能得到正确主题，不把深色页面里的白底正文、浅色控件或透明文字遗漏；保证原文跳转和图片请求头仍正确。
- 保留 pixi-spine 现有获取/分发策略，不因主题改造新增远程脚本或捆绑受限运行时；释放 WebView、方向切换与渲染尺寸处理保持。

**验收与回归：**
- 浅/深/SYSTEM 切换、缓存阅读、长表格/阵容、已有离线资源、预览工具栏和旋转均可读。
- 旧缓存无需全量删除即可正确展示，首次缺运行时仍按原有网络流程提示；不会宣称原本需下载的内容完全离线。

**执行/验证命令：**
~~~text
Test-Bd2Device -TestClass 'com.bd2toolsbox.ui.WebContentThemeTest' -Abi 'arm64-v8a'
Test-Bd2Device -TestClass 'com.bd2toolsbox.ui.WebContentThemeTest' -Abi 'x86_64'
~~~

**完成证据：** [T47.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T47.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 主题样式与渲染协议分离提交；可以回退 CSS，但不能恢复与当前模式冲突的不可读容器或改变许可分发策略。

## G8 — 仅清理冗余的包体优化

### T48 — 建立可重复的 APK 组成、ABI 与 native 审计脚本

- **历史任务 T48（原稿未实施，已停止维护）**

**优先级：** P1　**前置：** T03、T14、T36

**文件范围（实施工作区）：**
- 新增：[tools/audit_apk_size.py](<D:/bd2 mod/worktrees/bdroid-android-refactor/tools/audit_apk_size.py>)
- 新增：[tests/test_apk_size_audit.py](<D:/bd2 mod/worktrees/bdroid-android-refactor/tests/test_apk_size_audit.py>)
- 新增：[docs/refactor/evidence/apk-before-arm64.json](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/apk-before-arm64.json>)
- 新增：[docs/refactor/evidence/apk-before-x64.json](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/apk-before-x64.json>)

**落实要求：**
- 按 ZIP 实际 compressed payload 分类 DEX、Android资源、Chaquopy/IMY、native、其他和容器开销，统一 MiB 口径；检查嵌套 IMY 中各 ABI 与平台二进制。
- 只读解析 ELF 节/动态依赖/导出信息，不执行库、不向不可信路径解包；审计脚本支持畸形/重复条目并明确失败。
- 以本轮重构完成、尚未进行专项裁剪的 release 为优化 A/B 基线，另保留0.2.16历史基线；不能把代码升级/调试依赖变化误算成单项优化收益。
- 将本轮优化前两个候选复制到 build/refactor-baseline 并记录哈希；分别保存为 app-arm64-v8a-release.apk 和 app-x86_64-release.apk，$BeforeApk/$BeforeX64Apk 指向它们。检查目标在本工作区内，不覆盖历史0.2.16原件。

**验收与回归：**
- 合成 APK/IMY 样本的大小合计、ABI 分类与 ZIP 工具交叉一致；不会漏算 stored native，也不把 mapping 文件算进 APK。
- 同文件重复运行输出一致；归档内危险路径不触发落盘或执行，未知平台文件单独列出。

**执行/验证命令：**
~~~text
Invoke-Bd2Python -m unittest discover -s (Join-Path $Root 'tests') -p 'test_apk_size_audit.py' -v
Invoke-Bd2Python (Join-Path $Root 'tools/audit_apk_size.py') --apk $BeforeApk --output (Join-Path $Root 'docs/refactor/evidence/apk-before-arm64.json')
Invoke-Bd2Python (Join-Path $Root 'tools/audit_apk_size.py') --apk $BeforeX64Apk --output (Join-Path $Root 'docs/refactor/evidence/apk-before-x64.json')
~~~

**完成证据：** [T48.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T48.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 审计不改产物；口径变更保留旧输出和解释，禁止删除未理解的大项来凑数。

### T49 — 用单 ABI flavors 生成两个独立 APK

- **历史任务 T49（原稿未实施，已停止维护）**

**优先级：** P0　**前置：** T48、T32、T12

**文件范围（实施工作区）：**
- 修改：[app/build.gradle.kts](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/build.gradle.kts>)
- 修改：[settings.gradle.kts](<D:/bd2 mod/worktrees/bdroid-android-refactor/settings.gradle.kts>)
- 修改：[app/src/main/java/com/bd2toolsbox/feature/update/ApkSelector.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/feature/update/ApkSelector.kt>)
- 修改：[tools/verification.ps1](<D:/bd2 mod/worktrees/bdroid-android-refactor/tools/verification.ps1>)
- 新增：[docs/refactor/evidence/abi-packaging.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/abi-packaging.md>)

**落实要求：**
- 将 ABI 发布建模为 arm64 与 x64 两个 flavor，分别唯一对应 arm64-v8a/x86_64；移除与其冲突的全局多 ABI 过滤/传统 splits 配置，不默认产出 universal。
- Chaquopy 仅在 :app 实际应用。验收 lib 和 Chaquopy assets/IMY 都只含当前 ABI，不以 lib 目录正确就认为整个包正确。
- 保持两个包的 applicationId、签名与发布版本语义一致；发布文件名明确写实际 ABI，更新选择器支持两者。更新测试 task 映射、构建脚本和文档，禁止悄悄丢 x86_64 支持。

**验收与回归：**
- arm64 包无 x86_64 Chaquopy 内容，x64 包无 arm64 内容；独立冷启动、Python/JNI、转换和预览各在相应设备可用。
- 两个构建任务和对应单元/设备测试可发现；没有 universal 被误上传或更新器选错包。

**执行/验证命令：**
~~~text
Invoke-Bd2Gradle :app:assembleArm64Release :app:assembleX64Release --console=plain
Invoke-Bd2Python (Join-Path $Root 'tools/audit_apk_size.py') --apk (Join-Path $Root 'app/build/outputs/apk/arm64/release/app-arm64-release.apk') --expected-abi arm64-v8a
Invoke-Bd2Python (Join-Path $Root 'tools/audit_apk_size.py') --apk (Join-Path $Root 'app/build/outputs/apk/x64/release/app-x64-release.apk') --expected-abi x86_64
~~~

**完成证据：** [T49.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T49.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 先保留已有分发方式的可用产物作为比对；flavor 切换失败时回退打包配置，不靠删 ABI 功能解决。

### T50 — 从 Android Python 归档中剔除已确认无关平台内容

- **历史任务 T50（原稿未实施，已停止维护）**

**优先级：** P1　**前置：** T49、T37

**文件范围（实施工作区）：**
- 读取：[app/src/main/python/vendor/UnityPy](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/python/vendor/UnityPy>)
- 新增：[tools/stage_android_python.py](<D:/bd2 mod/worktrees/bdroid-android-refactor/tools/stage_android_python.py>)
- 新增：[config/android-python-excludes.json](<D:/bd2 mod/worktrees/bdroid-android-refactor/config/android-python-excludes.json>)
- 修改：[app/build.gradle.kts](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/build.gradle.kts>)
- 新增：[tests/test_android_python_staging.py](<D:/bd2 mod/worktrees/bdroid-android-refactor/tests/test_android_python_staging.py>)

**落实要求：**
- 以审计结果建立精确路径排除清单，优先处理随包的 Windows/macOS 及经验证不适配 Android/Bionic 的桌面 FMOD 二进制；不按所有 .so、整个 export 目录或文件行数粗暴删除。
- 使用所选 Chaquopy 支持的源集过滤或可声明 inputs/outputs 的构建暂存源；确定一种可重复方式，不改 APK 后重签，也不破坏 vendored 原始来源。
- 保留 Python 类、延迟导入、纹理/文本/骨骼等现有能力及所需 notices；排除前后逐项核对真实 import 和调用。未知依赖宁可记录而非猜着删，不增加首次联网获取核心组件。

**验收与回归：**
- 清单内文件确实未进入 app.imy，其余必须内容哈希/路径符合预期；重复构建无残留、无绝对路径或时间戳引起的非确定差异。
- 两个 ABI 下 import、解包、重打包、静态/Spine 预览回归通过；涉及音频路径按现有基线验证，不凭扩展名推断可删。

**执行/验证命令：**
~~~text
Invoke-Bd2Python -m unittest discover -s (Join-Path $Root 'tests') -p 'test_android_python_staging.py' -v
Test-Bd2ReleaseSmoke -Case 'engine-and-preview' -Abi 'arm64-v8a'
Test-Bd2ReleaseSmoke -Case 'engine-and-preview' -Abi 'x86_64'
~~~

**完成证据：** [T50.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T50.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 排除清单逐条可撤销，保留原始第三方文件和许可；功能缺失即回退该条，不把退化列为可接受减包收益。

### T51 — 移除可剥离 native 调试信息并保留诊断能力

- **历史任务 T51（原稿未实施，已停止维护）**

**优先级：** P1　**前置：** T49、T13

**文件范围（实施工作区）：**
- 修改：[app/build.gradle.kts](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/build.gradle.kts>)
- 修改：[gradle/libs.versions.toml](<D:/bd2 mod/worktrees/bdroid-android-refactor/gradle/libs.versions.toml>)
- 修改：[tools/audit_apk_size.py](<D:/bd2 mod/worktrees/bdroid-android-refactor/tools/audit_apk_size.py>)
- 新增：[docs/refactor/evidence/native-strip.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/native-strip.md>)

**落实要求：**
- 确认并固定能用于本构建的 NDK/strip 工具，检查 Gradle 日志是否真正执行；当前缺 NDK 只是历史线索，必须以本轮日志确认原因，不直接断言。
- 重点检查 libastcenc、libtexture2ddecoder 等实际含调试节的产物，使用受支持的构建剥离流程；保留需要的动态导出、DT_NEEDED、ABI 与加载段信息，原始符号另存并关联版本/ABI。
- 不得盲删 .so、JNI 符号或所有静态/动态信息。检查适用系统的16KB页与 APK 对齐兼容，strip 不是修复链接对齐的手段；必要的 native 兼容更新与减包收益分开记录。

**验收与回归：**
- 剥离前后动态依赖/必要导出一致、可剥离节减少，包体变化可测；符号归档能对应回最终二进制。
- 两 ABI 真实 ASTC/纹理解码及 Python native 导入通过，在所选现代系统页大小环境中能加载；没有 UnsatisfiedLinkError 或崩溃。

**执行/验证命令：**
~~~text
Invoke-Bd2Gradle :app:assembleArm64Release :app:assembleX64Release --info --console=plain
Test-Bd2ReleaseSmoke -Case 'native-load-and-decode' -Abi 'arm64-v8a'
Test-Bd2ReleaseSmoke -Case 'native-load-and-decode' -Abi 'x86_64'
~~~

**完成证据：** [T51.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T51.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 保留原始 native 和对应符号；出现加载/导出问题回退该库的剥离配置，不删功能绕过崩溃。

### T52 — 精修 R8 规则，保护真实动态入口

- **历史任务 T52（原稿未实施，已停止维护）**

**优先级：** P0　**前置：** T36、T20、T49

**文件范围（实施工作区）：**
- 修改：[app/proguard-rules.pro](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/proguard-rules.pro>)
- 修改：[app/build.gradle.kts](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/build.gradle.kts>)
- 读取：[app/src/main/java/com/bd2toolsbox/core/engine/ChaquopyModEngine.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/core/engine/ChaquopyModEngine.kt>)
- 读取：[app/src/main/java/com/bd2toolsbox/SpinePreviewActivity.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/SpinePreviewActivity.kt>)
- 新增：[docs/refactor/evidence/r8-rules.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/r8-rules.md>)

**落实要求：**
- release 已启用 R8，保留 optimize 配置，检查所选 AGP 的实际 full-mode 行为；不能把“打开 R8”算新增主要收益。
- 逐条审查全包 keep/dontwarn 和已失效 Gson 规则，依据 usage/seeds/configuration/whyareyoukeeping 收窄；保留 Chaquopy consumer rules、Python 回调、JNI/AIDL、真实反射和存在的 JS bridge 入口。
- 对每条自定义 keep 写明被谁动态调用及对应 smoke 用例。不得用保留所有业务类、忽略所有警告或 -dontoptimize 作为最终通过条件。mapping/符号按版本+ABI归档，不打进 APK。

**验收与回归：**
- minified release 的冷启动、Hilt 对象图、旧数据、Python 回调、扫描/安装、WebView/预览、许可证均可运行。
- 应用 runtime 中无 Gson；公开数据字段不再靠偶然未混淆生存。规则变动有 DEX/类数/大小及保留原因前后对照。

**执行/验证命令：**
~~~text
Invoke-Bd2Gradle :app:assembleArm64Release :app:assembleX64Release --console=plain
Test-Bd2ReleaseSmoke -Case 'full-r8-contract' -Abi 'arm64-v8a'
Test-Bd2ReleaseSmoke -Case 'full-r8-contract' -Abi 'x86_64'
~~~

**完成证据：** [T52.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T52.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 按规则/入口局部回退并补精确测试，不恢复无说明的全包 keep；发现序列化问题先修契约。

### T53 — 启用资源收缩并验证动态资源与许可证

- **历史任务 T53（原稿未实施，已停止维护）**

**优先级：** P1　**前置：** T52、T38、T47

**文件范围（实施工作区）：**
- 修改：[app/build.gradle.kts](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/build.gradle.kts>)
- 修改：[gradle.properties](<D:/bd2 mod/worktrees/bdroid-android-refactor/gradle.properties>)
- 有真实动态引用时新增：[app/src/main/res/raw/resource_keep.xml](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/res/raw/resource_keep.xml>)
- 修改：[tools/audit_apk_size.py](<D:/bd2 mod/worktrees/bdroid-android-refactor/tools/audit_apk_size.py>)
- 新增：[docs/refactor/evidence/resource-shrink.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/resource-shrink.md>)

**落实要求：**
- release 设置 isShrinkResources=true，保留代码收缩；检查实际 AGP 是否支持8.12起的新优化管线，AGP9的自动行为与旧版本分开，不向不支持的版本盲加开关。
- 追踪实际动态资源访问，为确有需要的资源加最小保留配置；生成的 AboutLibraries 数据究竟是 raw 还是 assets 以构建结果为准，不预设不存在的资源名。
- 清理未使用 Android 资源/图标时以收缩报告为依据；material-icons-extended 的声明大小不等于 APK 大小。Python、JS assets 和许可证不能当 Android 资源收缩自动负责。

**验收与回归：**
- release 许可证全文、图标、空状态、深色主题、FileProvider XML 和预览相关资源仍可用，无 Resources.NotFoundException。
- 报告列出实际删除/保留项与资源体积差异；保留规则不使用覆盖全项目的通配符掩盖缺引用。

**执行/验证命令：**
~~~text
Invoke-Bd2Gradle :app:assembleArm64Release :app:assembleX64Release --console=plain
Test-Bd2ReleaseSmoke -Case 'resources-and-licenses' -Abi 'arm64-v8a'
~~~

**完成证据：** [T53.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T53.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 按确切资源补最小保留规则或回退该项删除，不能删许可证来解决包体，也不能全关收缩后声称完成。

### T54 — 核对真实减包收益与功能零损失边界

- **历史任务 T54（原稿未实施，已停止维护）**

**优先级：** P0　**前置：** T50、T51、T52、T53

**文件范围（实施工作区）：**
- 新增：[docs/refactor/apk-comparison.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/apk-comparison.md>)
- 修改：[tools/audit_apk_size.py](<D:/bd2 mod/worktrees/bdroid-android-refactor/tools/audit_apk_size.py>)
- 新增：[docs/refactor/evidence/apk-after-arm64.json](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/apk-after-arm64.json>)
- 新增：[docs/refactor/evidence/apk-after-x64.json](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/apk-after-x64.json>)

**落实要求：**
- 对 arm64/x64 分别输出优化前后 compressed APK、各内容类别、native 调试信息、安装占用及基本启动/内存数据；将新依赖成本与冗余清理收益分开。
- 确认仅清理冗余：核心功能、支持 ABI、原有离线能力和既有运行时获取策略不变。未获单独批准，不通过删功能、首次下载核心组件或有明显体验代价的 native 压缩追数字。
- 历史约11.809MiB仅是候选内容池，不能提前宣称净节省；不同优化有重叠，逐项 A/B 与最终合计分别报告。pyc、压缩/对齐也不自动等于减包。

**验收与回归：**
- 最终两个包通过同一套功能/协议 smoke；全部差异条目可解释，无错误 ABI、桌面冗余混回、缺许可或运行时缺模块。
- 同一口径可复算收益；若某优化出现功能/离线/性能退化，该项回退并重新测量，不列为成功节省。

**执行/验证命令：**
~~~text
Invoke-Bd2Python (Join-Path $Root 'tools/audit_apk_size.py') --apk (Join-Path $Root 'app/build/outputs/apk/arm64/release/app-arm64-release.apk') --expected-abi arm64-v8a --output (Join-Path $Root 'docs/refactor/evidence/apk-after-arm64.json')
Invoke-Bd2Python (Join-Path $Root 'tools/audit_apk_size.py') --apk (Join-Path $Root 'app/build/outputs/apk/x64/release/app-x64-release.apk') --expected-abi x86_64 --output (Join-Path $Root 'docs/refactor/evidence/apk-after-x64.json')
Invoke-Bd2Python (Join-Path $Root 'tools/audit_apk_size.py') --compare (Join-Path $Root 'docs/refactor/evidence/apk-before-arm64.json') (Join-Path $Root 'docs/refactor/evidence/apk-after-arm64.json')
Invoke-Bd2Python (Join-Path $Root 'tools/audit_apk_size.py') --compare (Join-Path $Root 'docs/refactor/evidence/apk-before-x64.json') (Join-Path $Root 'docs/refactor/evidence/apk-after-x64.json')
Test-Bd2ReleaseSmoke -Case 'all-core-features' -Abi 'arm64-v8a'
Test-Bd2ReleaseSmoke -Case 'all-core-features' -Abi 'x86_64'
~~~

**完成证据：** [T54.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T54.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 保留每项优化的独立变更与基线；不达体验边界就撤销具体优化，允许牺牲减包幅度保住功能。

## G9 — 综合验收与交付

### T55 — 完成架构收尾并明确物理多模块化边界

- **历史任务 T55（原稿未实施，已停止维护）**

**优先级：** P1　**前置：** T28、T29、T30、T31、T32、T33、T34、T36

**文件范围（实施工作区）：**
- 修改：[app/src/main/java/com/bd2toolsbox/ui/viewmodel/MainViewModel.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/viewmodel/MainViewModel.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/ui/dialogs/AppDialogs.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/ui/dialogs/AppDialogs.kt>)
- 修改：[app/src/main/java/com/bd2toolsbox/data/repository/GamekeeRepository.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/data/repository/GamekeeRepository.kt>)
- 新增：[tools/check_architecture.py](<D:/bd2 mod/worktrees/bdroid-android-refactor/tools/check_architecture.py>)
- 新增：[docs/refactor/architecture.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/architecture.md>)

**落实要求：**
- 删除已无调用者的旧装配、重复缓存、临时转发 facade 和无意义 interface；ViewModel 不互相注入，UI 不执行文件/Python/网络业务，pure model/render 不依赖 Android/UI。
- 以职责和 interface 能否独立测试为验收，不规定所有文件必须低于某个行数，也不重写生成/vendored 大文件。检查改名后仍把全部逻辑挪到一个 helper 的伪拆分。
- 本轮默认交付单一 :app 内清晰的 core/feature 逻辑模块。评估真实测试隔离/依赖/构建收益并记录物理模块化结论，不为形式增加空 Gradle modules 或独立发布体系；未来若迁 Python 模块，Chaquopy 仍只在一个模块实际应用。

**验收与回归：**
- 架构检查能发现明确的反向依赖和旧手工装配回流，误报有具体理由而非全局忽略；关键用例可在不启动 Activity 的条件下测试。
- 对照旧巨型文件逐项确认职责有唯一落点，既无遗漏功能也无两套事实来源。

**执行/验证命令：**
~~~text
Invoke-Bd2Python (Join-Path $Root 'tools/check_architecture.py') --root $Root
Test-Bd2Unit -TestClass 'com.bd2toolsbox.*'
~~~

**完成证据：** [T55.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T55.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 删 adapter 前确认所有调用已迁移；必要时回退完整功能链，不重新引入全局万能 ViewModel。

### T56 — 完成核心功能与故障注入端到端验收

- **历史任务 T56（原稿未实施，已停止维护）**

**优先级：** P0　**前置：** T54、T55、T26、T09、T62

**文件范围（实施工作区）：**
- 修改：[docs/refactor/test-matrix.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/test-matrix.md>)
- 新增测试：[app/src/androidTest/java/com/bd2toolsbox/integration/ModWorkflowAcceptanceTest.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/androidTest/java/com/bd2toolsbox/integration/ModWorkflowAcceptanceTest.kt>)
- 新增：[docs/refactor/evidence/functional-acceptance.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/functional-acceptance.md>)

**落实要求：**
- 覆盖导入、来源扫描、游戏索引扫描、预解包、SD/HD 转换、同 bundle 多 MOD 叠加/卸载、跨 bundle、日期/好感剧情、静态/Spine预览、Wiki双版本、兑换、更新与日志导出。
- 在复制/校验/备份/账本/Binder/网络各边界注入失败，验证真实文件与显示结果一致；重要测试必须穿过生产 use case/协调器，不能只测 fake 自己。
- 所有破坏性场景在隔离测试数据/设备中运行；禁止把真实游戏目录用作磁盘不足、路径越界或进程杀死实验场。

**验收与回归：**
- 无半份 __data、误删来源、污染可信原版、丢安装记录、请求晚到覆盖新页面或虚假兑换成功。
- 取消/重试/部分成功可核对，旧数据和主要功能均有证据；未能运行的项保持未完成，不用构建通过代替。

**执行/验证命令：**
~~~text
Test-Bd2Device -TestClass 'com.bd2toolsbox.integration.ModWorkflowAcceptanceTest' -Abi 'arm64-v8a'
Test-Bd2Device -TestClass 'com.bd2toolsbox.integration.ModWorkflowAcceptanceTest' -Abi 'x86_64'
Invoke-Bd2Python -m unittest discover -s (Join-Path $Root 'tests') -p 'test_*.py' -v
~~~

**完成证据：** [T56.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T56.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 任何数据安全回归阻止交付；保留失败现场和原始数据，先定位再修复，不修改 golden 期望迎合错误实现。

### T57 — 完成平台、ABI 与真实 release 验收矩阵

- **历史任务 T57（原稿未实施，已停止维护）**

**优先级：** P0　**前置：** T56、T15

**文件范围（实施工作区）：**
- 修改：[docs/refactor/test-matrix.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/test-matrix.md>)
- 新增：[docs/refactor/evidence/platform-release-matrix.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/platform-release-matrix.md>)
- 修改：[tools/verification.ps1](<D:/bd2 mod/worktrees/bdroid-android-refactor/tools/verification.ps1>)

**落实要求：**
- 至少覆盖 API29、API34、所选较新目标平台；包含真实 arm64 设备与 x86_64 模拟器，以及适用的16KB页环境。记录系统/API、ABI、页大小、Shizuku状态、APK哈希和签名。
- 用实际混淆 release 或代码/资源载荷等同的签名受控验收产物验证，不仅跑 debug；确认最终实际发布签名的包还经过安装/更新 smoke。测试注入不能替 production 保留规则掩盖反射缺陷。
- 验证首次安装、覆盖升级、权限拒绝/撤销、系统限制、后台/旋转/杀进程、未知来源安装器流程；不把设备上未覆盖的场景写成通过。

**验收与回归：**
- 两个 ABI 所有核心 native/Python 入口可加载；旧数据覆盖升级无损；较新系统与最低支持系统均有实测记录。
- 清单中每格标明通过、失败或尚未执行并附证据；缺少关键设备/凭据时只报告未完成，不能最终合并发版。

**执行/验证命令：**
~~~text
Test-Bd2ReleaseSmoke -Case 'platform-matrix' -Abi 'arm64-v8a'
Test-Bd2ReleaseSmoke -Case 'platform-matrix' -Abi 'x86_64'
~~~

**完成证据：** [T57.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T57.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 出现平台回归就回退对应变更或修复；不自行提高 minSdk、取消 ABI 或删除权限受限功能缩小验收范围。

### T58 — 完成原生 UI 截图、可读性与交互回归

- **历史任务 T58（原稿未实施，已停止维护）**

**优先级：** P1　**前置：** T46、T47、T57、T38

**文件范围（实施工作区）：**
- 新增：[docs/refactor/evidence/ui-acceptance.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/ui-acceptance.md>)
- 新增测试：[app/src/androidTest/java/com/bd2toolsbox/ui/UiAcceptanceTest.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/androidTest/java/com/bd2toolsbox/ui/UiAcceptanceTest.kt>)
- 新增：[docs/refactor/evidence/screenshots](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/screenshots>)

**落实要求：**
- 对主要页面、任务面板、确认框、许可证与阅读器记录浅/深/SYSTEM、配色预设、无壁纸及亮/暗/高纹理壁纸状态；截图必须来自本轮 APK 的设备/模拟器。
- 检查实际合成后的正文对比度至少4.5:1、必要图标/控件边界至少3:1，并区分装饰和不适用项；检查字体缩放、TalkBack、点击范围、返回和 IME，不把 DOM detector 的零发现当 Compose 验收。
- 合并一轮问题清单统一修正，再做确认性验证；保存前后截图与实际 APK 对应关系，不使用旧截图或网页原型冒充原生验证。

**验收与回归：**
- 壁纸强度、模式、预设切换均不遮蔽关键内容；任务能收起再打开，备份确认和兑换结果语义正确。
- 没有关键文字/按钮被系统栏、键盘或大字体挤出；旧固定浅色样式和透明面色未残留在弹窗/WebView中。

**执行/验证命令：**
~~~text
Test-Bd2Device -TestClass 'com.bd2toolsbox.ui.UiAcceptanceTest' -Abi 'arm64-v8a'
Test-Bd2Device -TestClass 'com.bd2toolsbox.ui.UiAcceptanceTest' -Abi 'x86_64'
~~~

**完成证据：** [T58.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T58.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 可读性不达标先回退装饰/透明效果，保留内容保护；不能让用户通过降低字号或更换壁纸规避。

### T59 — 核对启动、列表、图片、扫描和转换性能

- **历史任务 T59（原稿未实施，已停止维护）**

**优先级：** P1　**前置：** T54、T58

**文件范围（实施工作区）：**
- 新增：[docs/refactor/evidence/performance.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/performance.md>)
- 修改：[tools/verification.ps1](<D:/bd2 mod/worktrees/bdroid-android-refactor/tools/verification.ps1>)
- 读取：[app/src/main/java/com/bd2toolsbox/core/images/AppImageLoader.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/core/images/AppImageLoader.kt>)
- 读取：[app/src/main/java/com/bd2toolsbox/feature/mods/ScanExecutor.kt](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/java/com/bd2toolsbox/feature/mods/ScanExecutor.kt>)

**落实要求：**
- 在相同设备/电源/温度条件下对比 release 的冷暖启动、代表性大列表滚动、图片峰值内存、目录枚举次数、扫描与转换耗时；区分缓存冷/热和网络因素。
- 短场景多次采样、长任务至少重复3次，记录中位数和波动；超过基线噪声且可重复的明显回退（以10%为调查触发线）必须归因并复测，OOM/持续可见卡顿直接不通过。
- 不要机械移除 largeHeap、盲加 @Stable、宣称 Hilt/Coil 自动更快；只依据量测修正本轮引入的问题，不增加有体验代价的缩包措施。

**验收与回归：**
- 图片缓存受限且不长期持有已销毁 UI；扫描重复枚举减少没有漏项；关键耗时没有未解释的回退。
- 报告包含输入规模、设备、APK哈希、测量工具与多次原始结果，不能只给主观“更流畅”。

**执行/验证命令：**
~~~text
Test-Bd2ReleaseSmoke -Case 'performance-scenarios' -Abi 'arm64-v8a'
~~~

**完成证据：** [T59.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T59.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 按功能/依赖/打包选项定位并回退回归来源；不能降低功能或缩小测试数据来掩盖。

### T60 — 固化一键验证、干净构建与维护文档

- **历史任务 T60（原稿未实施，已停止维护）**

**优先级：** P1　**前置：** T59、T55

**文件范围（实施工作区）：**
- 修改：[tools/verification.ps1](<D:/bd2 mod/worktrees/bdroid-android-refactor/tools/verification.ps1>)
- 修改：[README.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/README.md>)
- 修改：[README.en.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/README.en.md>)
- 修改：[.gitignore](<D:/bd2 mod/worktrees/bdroid-android-refactor/.gitignore>)
- 新增：[docs/refactor/build-and-verify.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/build-and-verify.md>)
- 新增：[docs/refactor/data-contracts.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/data-contracts.md>)

**落实要求：**
- 将依赖解析、JVM/Python测试、两个 release 构建、许可证/ABI/包体检查串成可重复本地命令，失败即非零退出；设备测试独立显式执行，不默认安装到任意连接设备。
- 在隔离环境做一次干净构建，核对实际依赖版本、文件集合与载荷哈希；签名/时间戳造成的非字节级差异解释清楚，不虚报完全可复现。
- 文档说明工具链、App发布版本位置、Python锁定、主题/壁纸、后台限制、数据恢复、许可证和两ABI分发；不引入独立模块发布、远端自动发布或额外下载组件。

**验收与回归：**
- 新环境按文档可得到相同解析图与通过的验证结果，构建不依赖个人绝对路径或未记录手工步骤。
- 文档命令与实际 flavor/task 一致，敏感配置/大产物/设备数据不进入 Git；新增依赖和维护方式可追溯。

**执行/验证命令：**
~~~text
& (Join-Path $Root 'tools/verification.ps1') -Mode LocalAll
if ($LASTEXITCODE -ne 0) { throw 'Full local verification failed' }
~~~

**完成证据：** [T60.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T60.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 保留已验证环境清单；修正文档/脚本而不通过隐藏失败或省掉检查让一键命令变绿。

### T61 — 最终核对、交付材料与受控合并发布

- **历史任务 T61（原稿未实施，已停止维护）**

**优先级：** P0　**前置：** T60、T56、T57、T58

**文件范围（实施工作区）：**
- 新增：[docs/refactor/release-checklist.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/release-checklist.md>)
- 新增：[docs/refactor/evidence/final-summary.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/final-summary.md>)
- 修改：[gradle/version.properties](<D:/bd2 mod/worktrees/bdroid-android-refactor/gradle/version.properties>)

**落实要求：**
- 逐项核对本清单与10项已确认决策，所有必需门槛有证据；附变更概要、数据兼容说明、已知限制、两个ABI产物、包体/性能对比、mapping/native符号和许可清单。
- 发版前核对渠道当前最大 versionCode、最终显示版本与签名，避免用今天的17盲推未来发布号。版本号和发布权限属于实际发版时的操作核对，不提前发包或暴露凭据。
- 先在重构分支完成整体验收；main 有必要修复时显式同步并重跑受影响验证。只有用户另行批准才合并、推送、发布或移除 worktree，不做一次性巨大无证据提交。

**验收与回归：**
- 最终发布 APK 与验收产物的代码/资源、ABI、版本和签名可追溯，用户可直接覆盖升级且无需清数据。
- 未运行/失败的关键项不能勾完成；主线无未经授权修改，发布流程不会误传 universal、debug包或不匹配符号。

**执行/验证命令：**
~~~text
git -c safe.directory=$Root -C $Root status --short --branch
git -c safe.directory=$Root -C $Root diff --check
Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $Root 'app/build/outputs/apk/arm64/release/app-arm64-release.apk')
Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $Root 'app/build/outputs/apk/x64/release/app-x64-release.apk')
~~~

**完成证据：** [T61.md](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/T61.md>)：落实本项输入、修复前后/基线对照、命令退出码、产物或截图哈希；注明真实设备/release覆盖情况，不以预期代替结果。

**风险与回退：** 合并/发布前保留完整分支和可追溯产物；不能自动强推、reset或删除工作区。数据已改变时按 journal 恢复，不把代码回退等同数据回退。


## 6. 验证命令约定与产物协议

### 6.1 命令不是本轮已经运行的结果

以下启动片段供执行阶段使用，T04 将它固化为 [verification.ps1](<D:/bd2 mod/worktrees/bdroid-android-refactor/tools/verification.ps1>)。T01 在工作区存在前独立执行路径/分支检查；T02 先记录本机已验证解释器，再开始 Python 测试。脚本必须拒绝在 main 或未经批准的设备上执行有副作用的验证。

~~~text
$ErrorActionPreference = 'Stop'
$Root = 'D:/bd2 mod/worktrees/bdroid-android-refactor'
$Gradlew = Join-Path $Root 'gradlew.bat'
$BeforeApk = Join-Path $Root 'build/refactor-baseline/app-arm64-v8a-release.apk'
$BeforeX64Apk = Join-Path $Root 'build/refactor-baseline/app-x86_64-release.apk'
$Python = $null
$environmentFile = Join-Path $Root 'docs/refactor/evidence/environment.local.json'
if (Test-Path -LiteralPath $environmentFile) {
    $localEnvironment = Get-Content -LiteralPath $environmentFile -Raw | ConvertFrom-Json
    $Python = [string]$localEnvironment.testPythonExe
}
Set-Location -LiteralPath $Root
function Invoke-Bd2Gradle {
    & $Gradlew @args
    if ($LASTEXITCODE -ne 0) { throw 'Gradle command failed' }
}
function Invoke-Bd2Python {
    if ([string]::IsNullOrWhiteSpace($Python) -or -not (Test-Path -LiteralPath $Python)) {
        throw 'T02 must record an executable testPythonExe'
    }
    & $Python @args
    if ($LASTEXITCODE -ne 0) { throw 'Python command failed' }
}
function Test-Bd2Unit {
    param([Parameter(Mandatory=$true)][string]$TestClass)
    $tasks = (Invoke-Bd2Gradle :app:tasks --all --console=plain) -join [Environment]::NewLine
    if ($tasks -match '(?m)^\s*testArm64DebugUnitTest(?:\s|$)' -and
        $tasks -match '(?m)^\s*testX64DebugUnitTest(?:\s|$)') {
        $unitTasks = @(':app:testArm64DebugUnitTest', ':app:testX64DebugUnitTest')
    } elseif ($tasks -match '(?m)^\s*testDebugUnitTest(?:\s|$)') {
        $unitTasks = @(':app:testDebugUnitTest')
    } else { throw 'No supported unit-test variant found' }
    foreach ($task in $unitTasks) {
        Invoke-Bd2Gradle $task --tests $TestClass --console=plain
    }
}
function Show-Bd2Dependencies {
    param([string]$Dependency)
    $tasks = (Invoke-Bd2Gradle :app:tasks --all --console=plain) -join [Environment]::NewLine
    $configs = if ($tasks -match '(?m)^\s*testArm64DebugUnitTest(?:\s|$)' -and
                   $tasks -match '(?m)^\s*testX64DebugUnitTest(?:\s|$)') {
        @('arm64ReleaseRuntimeClasspath', 'x64ReleaseRuntimeClasspath')
    } else { @('releaseRuntimeClasspath') }
    foreach ($configuration in $configs) {
        if ([string]::IsNullOrWhiteSpace($Dependency)) {
            Invoke-Bd2Gradle :app:dependencies --configuration $configuration --console=plain
        } else {
            Invoke-Bd2Gradle :app:dependencyInsight --dependency $Dependency --configuration $configuration --console=plain
        }
    }
}
~~~

说明：这里的任务名与 T49 的 arm64/x64 flavor 定义对应。T04/T49 必须用实际 Gradle task 输出验证映射；不存在任务应报错，不能返回成功。rg 的退出码1代表无匹配，不能一律视为测试失败或通过，需按该项预期解释。构建使用的 Python/pyc 编译器可能与测试解释器用途不同，分别记录，不以 PATH 猜测。

### 6.2 T04 必须落实的设备验证接口

| 入口 | 具体职责与退出规则 |
|---|---|
| Test-Bd2Device -TestClass <完整类名> -Abi arm64-v8a或x86_64 | 选择本地白名单内显式指定的测试 serial；核对 ABI/现有应用/签名，按实际 variant 构建测试包，仅安装到该设备并运行指定 instrumentation 类，收集结果；失败非零，未运行不算通过 |
| Test-Bd2ReleaseSmoke -Case <下表名称> -Abi <ABI> | 验证实际 minified release 的代码/资源载荷、签名与哈希，在受控设备运行 ReleaseContractProbeTest 对应 case；不能用 debug 或测试保留规则代替生产行为 |
| verification.ps1 -Mode LocalAll | 执行 JVM/Python、架构检查、两个 release 构建、ABI/许可/包体静态检查；不隐式安装设备、不自动发布、不访问真实游戏目录；失败即停止 |

Test-Bd2Device 另接受可选 -ProjectRoot（默认实施根），仅允许同一工作区内的项目，用于 T13 独立兼容探针；根据该项目实际元数据核对 package、variant、输出和测试 runner，不把探针当生产 App。

设备脚本不应仅假设 ANDROID_SERIAL 会约束 Gradle connected 任务。可使用显式 adb -s 安装与 am instrument，或经过验证的设备筛选；每次操作前再次确认目标。截图、字体/主题临时设置在完成后恢复原值。不能自动 pm clear、卸载日用 App 或把真实游戏目录作为破坏性样本。测试签名/最终发布签名分开说明，最终发布包仍需覆盖升级 smoke。

| release case | 要验证的实际流程 |
|---|---|
| snapshot-compatibility | 旧 release 快照导入/失效策略、保存后重启、账本不丢 |
| json-migration | 旧/新 JSON、必要字段、未知schema、URI与历史记录，真实混淆后仍正确 |
| engine-and-preview | Python 入口、转换/解包、静态/Spine预览与既有离线命中 |
| native-load-and-decode | 每个 ABI 的 native 导入、ASTC/纹理解码及适用的页大小 |
| full-r8-contract | Hilt/动态入口/回调/JSON/服务/预览等所有可能被收缩影响的行为 |
| resources-and-licenses | 许可数据/全文、图标、XML、明暗主题和动态资源 |
| all-core-features | T56 的代表性功能矩阵，核对文件结果而非只看成功提示 |
| platform-matrix | 最低/现有/选定新平台、权限、后台、进程死亡与覆盖升级 |
| performance-scenarios | T59 的固定输入、冷暖状态、设备和多次测量；有原始数据 |

### 6.3 规划中新增脚本的完整输入/输出要求

- [audit_apk_size.py](<D:/bd2 mod/worktrees/bdroid-android-refactor/tools/audit_apk_size.py>)：支持 --apk、可选 --expected-abi、--output，以及 --compare <before.json> <after.json>。JSON 至少含 APK SHA256/总字节、分类压缩字节、ABI集合、嵌套IMY平台文件、ELF调试字节/必要导出/依赖、未知项与工具版本。异常返回非零，不解包执行任何二进制。
- [stage_android_python.py](<D:/bd2 mod/worktrees/bdroid-android-refactor/tools/stage_android_python.py>)：输入源码根、输出构建暂存根和精确排除清单；验证两根不是同一路径且输出在本工作区 build 内；不改 vendored 原件，禁止路径越界。输出包含保留/排除清单和内容哈希，重复构建一致。
- [check_architecture.py](<D:/bd2 mod/worktrees/bdroid-android-refactor/tools/check_architecture.py>)：--root 指定实施根，检查明示的禁止依赖和旧装配回流，排除生成/vendor；输出文件与规则名，非零表示违反。regex/静态扫描仅作为边界检查的一部分，不能冒充完整类型或运行验证。
- 环境配置 [environment.local.json](<D:/bd2 mod/worktrees/bdroid-android-refactor/docs/refactor/evidence/environment.local.json>) 记录实际解释器/SDK/JDK及测试设备白名单，保持本地忽略；可提交文档只保留脱敏版本、命令和哈希。

### 6.4 T50 的精确冗余候选（不是无条件删除清单）

以下来自现成0.2.16 APK审计；T48须重新核对本轮产物，T50须验证导入/调用和许可后才能排除。单位为IMY内该entry的压缩MiB，不可直接相加承诺最终APK节省。Linux项存在glibc依赖，不因名称有arm64就认为适配Android。

| 源码候选路径 | 历史压缩MiB |
|---|---:|
| [vendor/UnityPy/lib/FMOD/Darwin/libfmod.dylib](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/python/vendor/UnityPy/lib/FMOD/Darwin/libfmod.dylib>) | 1.091 |
| [vendor/UnityPy/lib/FMOD/Linux/arm/libfmod.so](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/python/vendor/UnityPy/lib/FMOD/Linux/arm/libfmod.so>) | 0.581 |
| [vendor/UnityPy/lib/FMOD/Linux/arm64/libfmod.so](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/python/vendor/UnityPy/lib/FMOD/Linux/arm64/libfmod.so>) | 0.584 |
| [vendor/UnityPy/lib/FMOD/Linux/x86/libfmod.so](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/python/vendor/UnityPy/lib/FMOD/Linux/x86/libfmod.so>) | 0.660 |
| [vendor/UnityPy/lib/FMOD/Linux/x86_64/libfmod.so](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/python/vendor/UnityPy/lib/FMOD/Linux/x86_64/libfmod.so>) | 0.660 |
| [vendor/UnityPy/lib/FMOD/Windows/arm/fmod.dll](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/python/vendor/UnityPy/lib/FMOD/Windows/arm/fmod.dll>) | 0.539 |
| [vendor/UnityPy/lib/FMOD/Windows/x64/fmod.dll](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/python/vendor/UnityPy/lib/FMOD/Windows/x64/fmod.dll>) | 0.752 |
| [vendor/UnityPy/lib/FMOD/Windows/x86/fmod.dll](<D:/bd2 mod/worktrees/bdroid-android-refactor/app/src/main/python/vendor/UnityPy/lib/FMOD/Windows/x86/fmod.dll>) | 0.664 |

只排除已确认的平台二进制，不删除整个 UnityPy export/解析类；实际 Android 所需的 libastcenc、libtexture2ddecoder 等保留功能，按 T51 处理可剥离信息。

## 7. 关键契约的测试起点

下面是 T06/T07/T31/T39 对应的具体测试文件起点，先写测试再实现生产 interface。它们不是本轮已经运行的代码，也不能替代对应的设备/release集成用例。其余任务在各自“验收”栏给出了明确输入、动作和期望，必须据此落成测试，不允许只写返回常量的假测试。

### 7.1 归档路径：测试非法输入不逃逸，合法输入可用

生产入口由 T06 定义：SafeArchivePaths.resolveInside(root: File, entryName: String): File；非法路径抛 IllegalArgumentException。

~~~kotlin
package com.bd2toolsbox.core.fileaccess

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SafeArchivePathsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun rejectsTraversalAndAbsolutePaths() {
        val root = temporary.newFolder("output")
        val names = listOf("../outside", "nested/../../outside", "/outside", "C:/outside", "..\\outside")
        names.forEach { name ->
            assertThrows(IllegalArgumentException::class.java) {
                SafeArchivePaths.resolveInside(root, name)
            }
        }
    }

    @Test fun acceptsChineseNestedPath() {
        val root = temporary.newFolder("output")
        assertEquals(
            File(root, "角色/皮肤/data.bin").canonicalFile,
            SafeArchivePaths.resolveInside(root, "角色/皮肤/data.bin")
        )
    }
}
~~~

另需实际归档提取测试与符号链接场景。根目录应为本次独占工作区，不能只在检查后向可被其他进程替换的路径无条件写入。

### 7.2 原子写入：半途异常不能破坏原目标

生产入口由 T07 定义：AtomicFileWriter.write(target, verifyBeforeCommit = {}, write = lambda)。写入/校验异常须传播，暂存清理不能把失败伪装成成功。

~~~kotlin
package com.bd2toolsbox.core.fileaccess

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicFileWriterTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun interruptedWritePreservesOriginal() {
        val target = temporary.newFile("__data")
        target.writeText("original")
        assertThrows(IOException::class.java) {
            AtomicFileWriter().write(target) { output ->
                output.write("partial".toByteArray())
                throw IOException("injected write interruption")
            }
        }
        assertEquals("original", target.readText())
    }

    @Test fun failedValidationPreservesOriginal() {
        val target = temporary.newFile("__data")
        target.writeText("original")
        assertThrows(IOException::class.java) {
            AtomicFileWriter().write(
                target,
                verifyBeforeCommit = { throw IOException("injected validation failure") },
                write = { it.write("complete but invalid".toByteArray()) }
            )
        }
        assertEquals("original", target.readText())
    }

    @Test fun successfulWriteReplacesWholeContent() {
        val target = temporary.newFile("__data")
        target.writeText("original")
        AtomicFileWriter().write(target) { it.write("replacement".toByteArray()) }
        assertEquals("replacement", target.readText())
    }
}
~~~

这些 JVM 测试证明 utility 的契约；同目录原子替换/权限/同步仍必须在真实 Shizuku 文件系统验证，不能外推到所有 FUSE/SAF 场景。

### 7.3 主题：有无壁纸不能污染语义底色

T39 的 appColorScheme(theme, dark, dynamicScheme) 不接收壁纸参数，壁纸透明度只存在于布局层。动态颜色覆盖另加测试。

~~~kotlin
package com.bd2toolsbox.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeContractTest {
    @Test fun everyPresetHasOpaqueSemanticSurfaces() {
        AppTheme.entries.forEach { preset ->
            listOf(false, true).forEach { dark ->
                val colors = appColorScheme(preset, dark)
                assertEquals(1f, colors.background.alpha, 0f)
                assertEquals(1f, colors.surface.alpha, 0f)
                assertEquals(1f, colors.surfaceContainerHigh.alpha, 0f)
            }
        }
    }
}
~~~

仅检查 alpha 不代表对比度或布局通过；T40/T58 继续验证实际屏幕、关键内容、背景露出和明暗模式。

### 7.4 兑换：处理完毕不等于领取成功

T31 的 summarizeRedemption 统计真实结果，RETRYABLE_FAILURE/NOT_ATTEMPTED 计入 pendingCount，UNKNOWN 单列；settled 表示所有账号都得到终局结果，allRedeemed 仅允许成功/已兑换覆盖全部且账号数非零。

~~~kotlin
package com.bd2toolsbox.feature.cdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedeemSummaryTest {
    @Test fun terminalFailuresAreSettledButNotRedeemed() {
        val summary = summarizeRedemption(listOf(
            RedeemDisposition.TERMINAL_FAILURE,
            RedeemDisposition.TERMINAL_FAILURE
        ))
        assertTrue(summary.settled)
        assertFalse(summary.allRedeemed)
        assertEquals(0, summary.successCount)
        assertEquals(2, summary.terminalFailureCount)
    }

    @Test fun networkUncertaintyIsNotFinalSuccess() {
        val summary = summarizeRedemption(listOf(
            RedeemDisposition.SUCCESS,
            RedeemDisposition.UNKNOWN
        ))
        assertFalse(summary.settled)
        assertFalse(summary.allRedeemed)
        assertEquals(1, summary.successCount)
        assertEquals(1, summary.unknownCount)
    }
}
~~~

另需 T45 的真实 UI 断言：结果不能仍显示误导性的“已兑换”成功标签。

### 7.5 Python 归档入口：先验证再产生输出

~~~python
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "python"))
from safe_archive import extract_zip

class SafeArchiveTest(unittest.TestCase):
    def test_rejects_escape_without_writing_outside(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            archive = base / "input.zip"
            destination = base / "output"
            destination.mkdir()
            with zipfile.ZipFile(archive, "w") as output:
                output.writestr("../outside.txt", "unsafe")
            with self.assertRaises(ValueError):
                extract_zip(archive, destination)
            self.assertFalse((base / "outside.txt").exists())

    def test_extracts_normal_nested_content(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            archive = base / "input.zip"
            destination = base / "output"
            destination.mkdir()
            with zipfile.ZipFile(archive, "w") as output:
                output.writestr("角色/data.txt", "expected")
            extract_zip(archive, destination)
            self.assertEqual("expected", (destination / "角色" / "data.txt").read_text(encoding="utf-8"))

if __name__ == "__main__":
    unittest.main()
~~~

## 8. 需求与已发现问题覆盖表

| 要求/风险 | 落实任务 |
|---|---|
| Hilt、手工单例、Context/lateinit 装配 | T16–T20、T55 |
| 版本目录、App版本独立、依赖锁定 | T11–T14、T60 |
| Gson与release持久化字段不稳定 | T10、T35–T36、T52、T57 |
| AboutLibraries/删旧目录/非Maven许可 | T37–T38、T53、T60 |
| MainViewModel、Activity、Dialogs与大Repository | T21–T34、T55 |
| R1 ZIP越界 | T06、T56 |
| R2 直接覆盖游戏文件与备份假成功 | T07、T22、T56 |
| R3 账本半写/损坏被当空表 | T08、T25、T35、T56 |
| R4 同尺寸误判原版 | T09、T22、T56 |
| R5 攻略旧请求覆盖/关闭后重开 | T30、T56 |
| R6 目录错误被编码为空列表 | T05、T19、T23 |
| R7 后台任务与Shizuku连接冲突 | T19、T21–T26、T43 |
| 问题清单R8：无效集成测试（非收缩器） | T04、T24、T56 |
| 分页重复枚举与无界图片缓存 | T23、T34、T59 |
| MOD贴图进入系统相册、marker影响MOD索引 | T62、T56–T58 |
| 壁纸导致不可读、固定浅色、系统栏/WebView遗漏 | T27、T39–T41、T46–T47、T58 |
| 任务弹窗无退路、预览入口隐蔽 | T42–T43 |
| 备份清理缺确认、兑换状态混淆 | T31、T44–T45 |
| APK携带另一ABI的Python内容 | T48–T49、T54 |
| Python归档里的桌面FMOD与native调试节 | T50–T51、T54 |
| R8规则/资源收缩/动态资源 | T52–T53、T57 |
| 功能、旧数据、两ABI、平台与性能最终验收 | T56–T61 |

## 9. 最终完成定义与授权边界

本清单中的任务只有同时具备实现、针对性测试、对应真实环境证据和清晰回退说明，才能勾选完成。以下情况不算完成：只有编译通过；只改文件位置；测试未运行却写“应通过”；损坏数据被当空表；用 debug 替代 release；图片/权限/网络失败靠用户自行换素材规避；删功能凑包体。

全部核心门槛通过后，提供两个最终 APK、可复核的体积/性能对比、完整许可、版本迁移/恢复说明和匹配符号。签名凭据、正式发布编号、推送/合并/发布动作在实际操作前核对，不要求用户现在提供秘密，也不把确认本 TODO 当发布授权。

**本轮交付仅为文档。** 当前 main/业务代码、已有 APK 与设备状态保持不变；没有创建新分支或 worktree，没有运行这份计划中的构建、测试、安装或减包操作。
