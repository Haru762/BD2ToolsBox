# Audit D：生产搬运接线与冻结提交（2026-09-25）

## 当前结论

**代码接线及本地回归已完成，状态 LOCAL_VERIFIED；未做设备或 minified release 验收，未独立审阅、合并、推送或发布。**

本文件接续 [暂存组件检查点](audit-move-staging-20260925.md)。该检查点中“生产入口未接入”的描述是历史状态：本次已接入，不改写旧日志和旧哈希。

工作区仍为 `worktrees/bdroid-audit-fixes` / `fix/audit-safety`，HEAD 仍为 `1ce7c28decad1ee9c6e7272e720f2ad2fb913eea`。本次保留此前所有未提交修复，仅继续修改 `MainViewModel.kt`、新增 `MoveInstallWiringTest.kt`，并更新 `RealGameRecoveryDeviceTest.kt` 的 move 分支源码以使用新暂存路径。未触碰另外两个 worktree 的代码、构建输出或设备。

## 修复内容

### 固定本次写入与记账范围

- 完成汇总与 `moveFilesToGame()` 都在 Main dispatcher 上运行；在第一个挂起点前捕获成功 jobs、嵌套 mod 列表、画质、ASTC、安装时间、备份设置、失败备份列表及卸载意图。
- 关闭弹窗仍可清理 UI，但异步提交不再重新读取 `_installJobs` 或设置来决定账本内容。
- `MoveInstallStaging` 从捕获目标构建独占暂存。`MoveInstallSubmitter` 的 journal、实际目标列表与 `recordSuccessfulInstall(targets)` 均来自 `prepared.targets`。
- 真实搬运逐个调用现有的 `installConvertedBundle(ownedBundleDir, bundleName)`，不再调用整目录复制并递归删除 Download/Shared 的旧入口。
- 所有文件和账本均完整成功后，才按原文件摘要有条件地清理本次源文件；记账失败、复制中断或待核对时保留源文件与 journal。

### 提交门禁与还原

- 自动备份开启且本次选定目标有已记录的备份失败时，整个本次提交被拒绝，不触碰游戏文件，也不删除转换产物。没有静默打开用户关闭的备份设置。
- 已走 converted 直装的批次不能再触发 Download 搬运。
- 独立 `moveInProgress` 标志防止关闭弹窗或重置 `MoveState` 导致同一 VM 的重复提交；持久待核对仍由 Repository 的门禁拒绝。
- 批量还原不再 `clear()` 全账本，仅删除本次实际成功恢复的目标记录；下载/还原失败目标的账本记录保留。
- 这是对安全边界的必要修正，不把原计划“清空”的文字当作删除未还原事实的授权。还原日志继续沿用旧的无 upsert 契约，不声称新增了删除意图的自动恢复能力。

### 状态与错误

- `filesReplaced` 不等于完整提交成功；只有 `completedSuccessfully` 才触发成功展示与源文件清理。
- 内联复查发现新增 try/catch 会把安装后的 metadata 刷新异常误归类为提交失败。新增回归先复现后修复：已完成的文件、账本与 journal 收尾不会因状态刷新失败被改报为失败。
- 暂存关闭仍在 finally 执行；清理异常记录日志，不覆盖已有的提交结果。

## 测试如何执行真实路径

`MoveInstallWiringTest` 直接创建并调用生产 `MainViewModel`，使用真实 `MoveInstallStaging`、`MoveInstallSubmitter`、Repository、磁盘账本与 journal。测试通过反射设置宿主依赖和初始状态，未新增仅供测试使用的生产 API。

仅替换以下边界：

- Android `Context`、`Uri`、`Environment` 与日志；
- Shizuku/Binder：把待复制文件真实写入临时“游戏”目录，断言实际文件和实际账本，不只断言 mock 调用；
- metadata 网络/Python adapter；
- Windows 目录身份读取：生产默认 NIO `fileKey` 在该宿主为 null，因此测试将 companion getter 接到既有 `JvmTestStagingIdentity`，使用真实 Windows File ID。未替换暂存复制、核对或清理算法；**这不是 Android fileKey 可用的证据**。

11 条生产接线用例覆盖：仅选定目标、关闭弹窗及设置变化、备份失败拦截、尊重备份关闭设置、记账失败与重试门禁、部分还原、源文件后来变化、后续目标复制失败、converted 批次拒绝重复搬运、提交后刷新失败、取消后的租约与证据保留。

## RED → GREEN 与命令

日志均在本 worktree 的 `local/audit-fixes/`。

| 日志 | 结果 |
|---|---|
| `move-wiring-red.log` | 7 条因测试环境 setup 错误失败：MockK 不能反射 Kotlin synthetic SAM 类；**不计有效 RED** |
| `move-wiring-red-2.log` | 改为替换 companion getter 后，7 条中 5 条真实断言失败，2 条原有行为通过 |
| `move-wiring-green.log` | 接线、暂存和 submitter 三套定向测试共 29 条通过 |
| `move-wiring-refresh-red.log` | 扩展至 11 条接线测试后，只有“提交后刷新失败不应改报失败”一条失败 |
| `move-wiring-full-green.log` | 修复刷新归类后 App 全量 446 条通过，Android 测试源码编译通过 |
| `move-wiring-probe-fresh.log` | `--rerun-tasks` 重新执行 release-probe JVM，6 条通过；不是只引用 UP-TO-DATE |
| `move-wiring-debug-build.log` | `assembleDebug` exit 0，生成 arm64、x86_64 和 universal 三个本地 debug APK；未安装 |

```bash
# 每条命令工作目录都是 audit worktree。
bash local/audit-fixes/gradle.sh :app:testDebugUnitTest \
  --tests 'com.bd2toolsbox.ui.viewmodel.MoveInstallWiringTest'

bash local/audit-fixes/gradle.sh :app:testDebugUnitTest \
  :release-probe:testDebugUnitTest :app:compileDebugAndroidTestKotlin

bash local/audit-fixes/gradle.sh :release-probe:testDebugUnitTest --rerun-tasks
bash local/audit-fixes/gradle.sh :app:assembleDebug

python -B tools/run_python_tests.py --root . \
  --environment local/audit-fixes/resume-environment.json \
  --output local/audit-fixes/move-wiring-python-summary.json

powershell -NoProfile -ExecutionPolicy Bypass -File tools/tests/Test-AuditVerification.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File tools/tests/Test-SnapshotReleaseHarness.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File tools/tests/Test-UiColdStartHarness.ps1
```

Gradle 仍使用 JDK 17、Gradle 8.9、worktree 独占 user home、只读依赖缓存、offline/no-daemon/一个 worker。未运行 clean。存在既有 Kotlin 警告、Gradle 9 弃用提示、debug native 库无法 strip 的提示；这些均未被宣称已经修复。

## 最终本地结果

| 范围 | 结果 |
|---|---|
| App JVM | **446/446**，0 failure/error/skip（包含 11 条新接线测试） |
| Release-probe JVM | **6/6**，0 failure/error/skip，强制重跑 |
| Python | **126/126**，0 failed/errors/skipped/notRun；catalog 两项配置/哈希门禁通过 |
| PowerShell 审查护栏 | **7/7** |
| Snapshot host harness | **16/16** |
| UI cold-start host harness | **14/14**；仅 host harness，不是设备 UI 验收 |
| Debug APK | 三个 ABI 产物构建成功；没有 release 产物或发布动作 |
| Android instrumentation | 新 move 路径源码编译成功；设备用例 **NOT_RUN** |

## 固定证据与可复验范围

- 原始 JVM XML：`local/audit-fixes/move-wiring-jvm-xml/`。
- 源码、测试、日志、报告、XML 和三个 debug APK 的哈希：`local/audit-fixes/move-wiring-verification.json`。
- 清单 SHA-256：`2fc31a385434a06c723e088946387558053aee73f143973831f414d351519eb1`。
- arm64 debug APK SHA-256：`9804d36d38138b2842be14138dc3039a05298d621633d7be7bdcbaae0d698c2e`。
- x86_64 debug APK SHA-256：`68b1134fcfa5f955c1d21ef01f49085fd0b3eff3edfe27b2814440e8b29f87e1`。
- universal debug APK SHA-256：`d9260bc99afe113525f6fcd75e9b3200d56e9c0b3e4ed94de1ffc9b981ea22c7`。

## 边界与后续

1. 本轮没有独立审阅子代理，只有实现者内联审查和真实自动化回归；合并前仍需独立审阅。
2. 新路径未在实际 Android 外部缓存、Shizuku、ARM64 或 minified release 上运行。既有 T08 设备证据不能自动外推到本次新路径；已更新设备 driver，但未运行。
3. Source cleanup 的检查与删除之间没有承诺抵御任意外部并发写入；原先记录的该边界不因 JVM 通过而消失。
4. 当前每个 bundle 提交前会重新核对完整暂存树；大量大文件批次可能重复读盘。正确性测试通过不等于批量性能验收，后续若改为按目标核对必须保留篡改拒绝与部分提交证据。
5. 旧 `ShizukuManager.moveDownloadToGame()` 定义仍保留，但生产 VM 和更新后的设备 driver 均不再调用；旧的手动 root shell 命令没有迁移为本次有 journal 的自动流程。后续清理应分别处理，不能混同。
6. Audit A/B/C/E 的逐项证据及最终 F 收尾仍需核对；本次只收口 D 的代码接线和本地验证，不将整个 13 项审查修复或 T12/后续重构标为完成。
