# 构建与验证（T60）

- 日期：2026-10-12。
- 目的：把「怎么构建、怎么验证」写成**一条命令 + 一份可核对清单**，
  不依赖个人绝对路径、不依赖上一个终端的函数/变量、不靠「构建通过」冒充测试通过。

## 1. 一条命令（不需要设备）

```powershell
& (Join-Path $Root 'tools/verification.ps1') -ProjectRoot $Root -Mode LocalAll
```

- **必须用 `&` 显式加载**（不是 `. ` dot-source）：`-Mode LocalAll` 会自己
  `Assert-Bd2Environment` 并把所需函数定义好，因此**全新 PowerShell 进程里也能跑**。
  这一点是实测的（见 §4）。
- 任一步失败即抛异常 → 非零退出。
- **设备测试不包含在内**：`Test-Bd2Device` / `Test-Bd2ReleaseSmoke` 必须显式调用，
  绝不默认往任意已连接设备上装东西。

### 五个步骤

| 步骤 | 内容 | 失败判据 |
|---|---|---|
| 1 | 依赖解析（`releaseRuntimeClasspath`） | Gradle 退出码非零 |
| 2 | 全部 JVM 单测（`Test-Bd2UnitAll`：每个 flavor 一次 Gradle） | 有 failures / errors / **skipped** / 零用例 / 无 XML |
| 3 | 全部 Python 测试（`Test-Bd2Python` 逐个文件） | 同上 |
| 4 | 两个 ABI 的 release 构建（`assembleArm64Release` + `assembleX64Release`） | 产物缺失或构建失败 |
| 5 | 静态审计：架构分层 + APK 组成/ABI/native/资源/DEX 键名 + native ABI | 审计工具非零退出 |

**skip 不算通过**：`Test-Bd2UnitAll` 把 `skipped != 0` 直接判失败。
T04 的逐类入口 `Test-Bd2Unit -TestClass X` 仍然保留 —— 它给每个类一份独立 runId 与
summary，供各任务引用具体证据；总入口不逐类调用是因为那会把同一个 Gradle 任务重跑几十次。

## 2. 需要设备时（显式）

```powershell
. (Join-Path $Root 'tools/verification.ps1') -ProjectRoot $Root
Test-Bd2Device -TestClass '<fqcn>' -Abi 'arm64-v8a'   # 或 x86_64
Test-Bd2ReleaseSmoke -Case '<case>' -Abi 'x86_64'
```

设备必须先在 `docs/refactor/evidence/environment.local.json` 的 `deviceWhitelist` 里
声明（该文件本地忽略）。`Get-Bd2Device` 只在**在线**的候选里判歧义：同一 ABI 有两台
在线时必须显式指定 serial；只有一台在线时直接用。

`Test-Bd2ReleaseSmoke` 的 case 见 §5。

## 3. 构建产物与任务名（与 flavor 一致）

| ABI | flavor | release 任务 | APK 路径 |
|---|---|---|---|
| arm64-v8a | `arm64` | `:app:assembleArm64Release` | `app/build/outputs/apk/arm64/release/app-arm64-release.apk` |
| x86_64 | `x64` | `:app:assembleX64Release` | `app/build/outputs/apk/x64/release/app-x64-release.apk` |

- 调试包：`apk/<flavor>/debug/app-<flavor>-debug.apk`；
  androidTest 包：`apk/androidTest/<flavor>/debug/app-<flavor>-debug-androidTest.apk`。
- **不再有 universal 包**（T49）。
- 路径与 flavor 的对应关系只在 `Get-Bd2ReleaseAuditTargets` 一处定义，
  文档与脚本都从这里取，避免「文档写一个、脚本用另一个」。

## 4. 干净环境与可复现性

- **全新 PowerShell 进程**：`& verification.ps1 -Mode LocalAll` 不 dot-source 即可跑通
  （实测 exit 0，见 §6）。
- **不依赖个人绝对路径**：`$Root` 由 `-ProjectRoot` 传入；构建机 Python 从
  `local.properties` 的 `chaquopy.python` → `CHAQUOPY_PYTHON` → `PATH` 依次找；
  Android SDK 从 `local.properties` 的 `sdk.dir` 读。
- **敏感配置不入 Git**：`local.properties`、`environment.local.json`、
  `KEYSTORE_*` 全部是本地文件或环境变量。
- **大产物不入 Git**：`/local/` 整个忽略（历史包、mapping、优化前/最终产物、报告）。
- **签名/时间戳**：release 包用本地 debug keystore 受控签名；**不宣称字节级可复现** ——
  APK 内含签名块与 ZIP 时间戳，同一份源码两次构建的哈希**不保证相同**。
  可复现的是：分类体积、ABI 内容、DEX 键名、mapping 守卫（见 §5）。

## 5. release smoke 的 case

`Test-Bd2ReleaseSmoke -Case '<name>' -Abi '<abi>'`，每个 case = **探针 + APK 级静态断言**，
两者都必须过（`Get-Bd2ReleaseSmokeCase` 里显式声明；未实现的 case 直接抛，不返回空成功）：

| case | 覆盖 |
|---|---|
| `baseline-startup` | T03 冻结基线：装得上、起得来、身份对得上（唯一用冻结基线的 case） |
| `snapshot-compatibility` | 冻结基线的快照契约 + `SnapshotV1` mapping 守卫 |
| `json-migration` | DEX 键名常量 + 真实 minified Store 跨进程读写 0.2.16 混淆夹具 |
| `native-load-and-decode` | 真实 BC1 解码 + native 调试信息确实剥离 + native ABI 审计 |
| `resources-and-licenses` | 资源名仍在 + 许可全文可解析 + 冷启动 |
| `engine-and-preview` | 裁剪后的 Python 引擎可用 + 桌面 FMOD 确实不在包里 |
| `full-r8-contract` | 冷启动 + Python 回调 + 快照契约 + mapping 守卫 |
| `all-core-features` | 上述核心并集 |
| `performance-scenarios` | 冷启动 ×3 + PSS，断言「没有明显退化」的阈值 |
| `platform-matrix` | 本设备 ABI/API 与声明一致且 >= minSdk |

**只有 `baseline-startup` 用冻结基线**，其余一律验**本轮构建出来的** minified release。

## 6. 实测结果（2026-10-12）

```
$ & tools/verification.ps1 -ProjectRoot <root> -Mode LocalAll
LocalAll [1/5] 依赖解析
LocalAll [2/5] JVM 单测
UnitAll [arm64Debug+x64Debug]: tests=1456 failures=0 errors=0 skipped=0 -> PASS
LocalAll [3/5] Python 测试
LocalAll [4/5] 两个 ABI 的 release 构建
LocalAll [5/5] 静态审计
=== LocalAll 摘要 ===
  OK   dependencies
  OK   jvm-unit:66 classes
  OK   python:15 files
  OK   release-builds
  OK   architecture
  OK   apk-audit:arm64-v8a
  OK   apk-audit:x86_64
LocalAll: PASS（设备测试未包含；需显式调用 Test-Bd2Device / Test-Bd2ReleaseSmoke）
EXITCODE=0
```

（`tests=1456` = 728 个 JVM 用例 × 2 个 flavor 变体。）

## 7. 未覆盖

- **设备测试**：见 §2，必须显式调用。
- **缺真实 catalog 时**：相关兼容测试只能标「未运行」，**不能**拿 LocalAll 通过代替。
- **字节级可复现**：不宣称（见 §4）。
- **多 API 级别矩阵**：`platform-matrix` 只覆盖当前这一台设备。
