# 架构与模块边界（T55）

- 日期：2026-10-11。
- 结论：本轮交付**单一 `:app` 内清晰的 `core` / `data` / `feature` / `ui` 逻辑模块**；
  不增加空 Gradle module，不引入独立发布体系。依赖方向由
  `tools/check_architecture.py` 机器校验（116 个文件，0 违规，1 条带理由的例外）。

## 1. 分层与允许的依赖方向

```
        ┌─────────────────────────────────────────────┐
        │ ui/            screens / components /        │
        │                dialogs / theme / viewmodel   │
        └───────────────┬─────────────────────────────┘
                        │  ui → feature（executor/纯逻辑）、data.model、core.model
                        ▼
        ┌─────────────────────────────────────────────┐
        │ feature/       install / mods / preview /    │
        │                cdk / guides / update /       │
        │                settings                      │
        └───────────────┬─────────────────────────────┘
                        │  feature → data、core
                        ▼
        ┌─────────────────────────────────────────────┐
        │ data/          model / repository            │
        └───────────────┬─────────────────────────────┘
                        │  data → core
                        ▼
        ┌─────────────────────────────────────────────┐
        │ core/          engine / fileaccess / model / │
        │                operations / python / storage │
        └─────────────────────────────────────────────┘
```

**反向依赖一律禁止**：`core` 不认得上层，`data` 不认得 `feature`/`ui`，
`feature` 不认得 `ui`。

## 2. 机器校验的规则（`tools/check_architecture.py`）

| 规则 | 触发条件 | 判定 |
|---|---|---|
| **R1** | `core/**` import `com.bd2toolsbox.ui.*` 或 `.feature.*` | 违规 |
| **R2** | `data/**` import `com.bd2toolsbox.ui.*` 或 `.feature.*` | 违规 |
| **R3** | `feature/**` import `com.bd2toolsbox.ui.*` | 违规 |
| **R4** | `feature/**` 下**既非 Compose、也非 ViewModel** 的文件 import `android.*` / `androidx.*` | 违规 |
| **R5** | `ui/viewmodel/**` import `androidx.compose.*` | 违规（VM 不持 UI） |
| **R6** | 任何 `*ViewModel` 通过构造器注入另一个 `*ViewModel` | 违规 |
| **R7** | `ui/screens/**` 或 `ui/components/**` import `java.io` / `java.net` / `okhttp3` / `com.chaquo.*` / `core.engine` / `core.python` / `core.fileaccess` / `data.repository` | 违规（UI 不执行文件/Python/网络业务） |

设计取舍：
- **R4 按内容判定 ViewModel**（`:\s*[\w.]*ViewModel\b`），不按文件名 —— 文件名可以随便叫，
  按名字判既会漏也会误（本轮就踩过一次）。
- **R7 只覆盖 `ui/screens` 与 `ui/components`**：`ui/viewmodel` 是执行入口的合法持有者，
  允许引用仓储与 executor。
- **误报写进 `ALLOW` 且必须带理由**，`tests/test_check_architecture.py`
  有一条用例强制「每条 allow 都有非空理由」。**没有全局忽略开关**。

### 当前的唯一例外

| 文件 | 例外 | 理由 |
|---|---|---|
| `feature/install/InstallExecutor.kt` | `android.util.Log` | 仅一处 `Log.w` 的失败路径日志；不是平台/UI 依赖，不影响 JVM 可测性（`InstallExecutorTest` 在 JVM 上跑）。**残留风险**：该行在单元测试的 mockable `android.jar` 下会抛 `Stub!`，所以这条失败路径当前**没有 JVM 覆盖** —— 见 §5。 |

## 3. 旧巨型文件的职责落点

对照 `bdroid_src`（重构前）与当前工作区的同名文件：

| 文件 | 重构前 | 现在 | 说明 |
|---|---:|---:|---|
| `ui/viewmodel/MainViewModel.kt` | 4438 | 5306 | 见下方逐项落点；行数**增长**（新增编排与文档），不是拆分目标 |
| `ui/dialogs/AppDialogs.kt` | 1936 | 2066 | 弹窗集合，仍是聚合文件 |
| `ui/screens/ModScreen.kt` | 1549 | 1464 | 略降 |
| `data/repository/GamekeeRepository.kt` | 1397 | 1415 | 基本持平（新增 `GuideHtmlRenderer` 已外移，见 T47） |

`MainViewModel` 中被移除的函数与它们的**唯一落点**（`rg` 实证）：

| 旧函数 | 现在在哪 |
|---|---|
| `loadModListSnapshot` / `normalizeSnapshotEntry` | `core/storage/ModSnapshotStore`（T10/T23） |
| `recordSuccessfulInstall` | `feature/install/InstallExecutor`（T22） |
| `validateConvertedForInstall` | `service/ShizukuFileService` |
| `copyTreeFromSaf` / `walk`（原为 SAF 递归拷贝） | `core/fileaccess/AtomicFileWriter`、`service/ShizukuFileService` |
| `initRepositories` | Hilt（`di/`） |
| `mergeWithInstalled` / `recordConvertedInstalls` | **无同名落点**：已并入/改名，**未逐行核对**（见 §5） |

`MainViewModel` 仍是最大的文件，但它的角色已经从「什么都做」变成
「app 级编排 + 状态持有」：安装/卸载/扫描/预解包的实际执行体分别在
`feature/install/{Install,Uninstall}Executor`、`feature/mods/ScanExecutor`、
`feature/preview/PrepackExecutor`，且**各自有 JVM 用例**
（`InstallExecutorTest` / `UninstallExecutorTest` / `ScanExecutorTest` / `PrepackExecutorTest`）。

## 4. 物理模块化结论

**不拆分 Gradle module。** 依据：

- 本轮已有的**逻辑**模块边界（`core`/`data`/`feature`/`ui`）已由 R1–R7 机器守住，
  依赖方向是可判定的；拆物理 module 主要带来的是编译增量收益，而不是边界收益。
- 现有验证体系（`Test-Bd2Unit` 跑 `arm64Debug+x64Debug`、`Test-Bd2Device` 按 ABI 装
  per-flavor APK）建立在「单一 `:app`」上；拆模块会牵动 `verification.ps1` 的
  任务映射、`release-probe` 的跨包反射面与 R8 mapping 归档，收益与风险不成比例。
- 空 Gradle module 只是形式，会带来额外的 `settings.gradle.kts`、依赖声明与构建时间，
  却不会自动产生边界（边界靠规则守，不靠目录）。

**未来若迁 Python 模块**：Chaquopy 仍只在一个模块实际应用（现在是 `:app`；
`:release-probe` 刻意不应用它，见 `settings.gradle.kts` 与 T04 证据）。
这一点是本轮已确立并验证过的约束，不因模块化调整而放宽。

## 5. 未完成与已知风险（不静默）

- **`mergeWithInstalled` / `recordConvertedInstalls` 的落点未逐行核对**：`rg` 找不到同名函数，
  也没有逐行确认它们的逻辑去了哪里。「职责有唯一落点」这一条对这两个函数**未验证**。
- **`InstallExecutor` 的 `Log.w` 失败路径无 JVM 覆盖**：mockable `android.jar` 下会抛
  `Stub!`。要么给 executor 注入一个 logger，要么让该路径可测；本轮未做。
- **`AppDialogs.kt`（2066 行）仍是聚合文件**：本轮未拆分；它是否构成「两套事实来源」
  未逐项审计。
- **`MainViewModel` 行数增长**：本轮未把「行数」当验收标准，但也**没有**证明它的
  每块职责都有唯一落点。
- **`ui/screens` 仍直接引用 `data.model` 与少量 `data.repository` 类型**：
  R7 只拦「执行面」，`UpdateRepository.Release` 这类**类型引用**保留（弹窗参数需要）。
  这是有意的边界，不是遗漏。
- **`feature/cdk/CdkResultPresentation.kt` 在 T55 中被移到 `ui/components/RedeemResultComponents.kt`**：
  它是 Compose 文件，放在 `feature/` 会让 `feature → ui` 成立（R3 会红）。
  移动后 `feature/cdk` 只剩纯逻辑（`RedeemSummary`）与 ViewModel。

## 6. 复验

```powershell
$Root = 'D:/bd2 mod/worktrees/bdroid-android-refactor'
. (Join-Path $Root 'tools/verification.ps1') -ProjectRoot $Root
Invoke-Bd2Python (Join-Path $Root 'tools/check_architecture.py') --root $Root
Test-Bd2Python -Pattern 'test_check_architecture.py'
Test-Bd2Unit -TestClass 'com.bd2toolsbox.*'
```
