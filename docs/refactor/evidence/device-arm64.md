# arm64 真机验收（Xiaomi 2509FPN0BC / f7f409ac）

- 日期：2026-10-11（Asia/Shanghai）。
- 设备：`f7f409ac`，Xiaomi 2509FPN0BC（`popsicle`），**arm64-v8a**，Android 16 / API 36，
  页大小 **4096 B（4 KB）**，root + LSPosed MIUI，已装 Shizuku（`moe.shizuku.privileged.api`）。
- 目的：补上此前因真机掉线而**全部缺失**的 arm64 侧证据（T48–T55 的结论此前只有静态审计）。

## 1. 通过的用例（真机实测）

安装：`:app:assembleArm64Debug` + `:app:assembleArm64DebugAndroidTest`，
产物 `apk/arm64/debug/app-arm64-debug.apk` 与
`apk/androidTest/arm64/debug/app-arm64-debug-androidTest.apk`；
用 `am instrument` 逐类跑（不重复安装，避开 adb transport 抖动）。

| 用例 | 结果 | 覆盖任务 |
|---|---|---|
| `core.engine.AndroidPythonStagingTest` | **4/4 PASS** | **T50**（设备上无任何桌面 FMOD 文件；保留模块可 import；`pyfmodex` 仍不可用、Ogg 基线不变；走生产入口 `main_script` 可导入 repacker） |
| `core.engine.NativeEngineSmokeTest` | **9/9 PASS** | T18 / T50（裁剪后源集上的真实引擎调用） |
| `platform.PlatformBehaviorTest#packagedJniLibrariesAre16KbAligned` | **PASS** | **T51 / T15**（arm64 APK 内每个 `lib/*.so` 未压缩存放、ZIP 数据偏移 16 KB 对齐、`PT_LOAD p_align` 达标；`libastcenc.so` 仍是登记在案的 4 KB 例外） |
| `platform.PlatformBehaviorTest#jniLibrariesLoadOnThisPageSize` | **PASS** | **T51**（真机 `System.loadLibrary("texture2ddecoder")` + `System.loadLibrary("astcenc")` 成功，无 `UnsatisfiedLinkError`） |
| `ui.WebContentThemeTest` | **6/6 PASS** | **T47**（真实 WebView 读 `getComputedStyle`：深色下正文 `rgb(230,225,229)`、浅色下 `rgb(34,34,34)`、链接与 `.mention` 底色正确、`colorScheme` 正确） |
| `service.InstallServiceLifecycleTest` | **4/4 PASS** | T22 |
| `service.ScanServiceTest` | **5/5 PASS** | **T23**（此前真机上卡住的用例） |
| `core.operations.ProcessDeathRecoveryTest` | **4/4 PASS** | **T25**（此前真机上卡住的用例） |
| `service.GameFileCommitTest` | **13/13 PASS**（修复后；修复前 11/13） | **发现并修掉一个真实回归**，见 [game-file-commit-permissions.md](game-file-commit-permissions.md) |
| `data.repository.LogExporterTest` / `ModDiscoveryTest` / `ModSnapshotTest` / `PreviewCacheNegativeTest` / `PreviewCacheSizeSlotTest` / `SnapshotStoreInstanceReuseTest` | **3/12/5/5/6/1 PASS** | T21–T26 的仓储/缓存面 |
| `service.BackupFailureSummaryTest` / `LedgerRecoveryStartupTest` / `SidecarRoundTripDeviceTest` / `TryUseBackupTrustGateTest` / `ContentRecoveryDeviceTest` / `ShizukuFileServiceListingTest` | **5/4/1/2/1/5 PASS** | T08/T19/T21/T25 |
| `di.HiltEntryPointTest`（3 个不用 ActivityScenario 的方法） | **1/1/1 PASS** | T20 |

## 2. 真机上的 release 实测（T54 的 arm64 侧 + T20 的 minified Release 冷启动）

用 `local/refactor-artifacts/pre-shrink/app-arm64-v8a-release.apk`（优化前）
与 `app/build/outputs/apk/arm64/release/app-arm64-release.apk`（优化后）分别
`install -r`（同 debug 签名 `892d95f0…`，原地覆盖、数据保留），
安装后先启动一次并静置，再连续 5 次 `force-stop` + `am start -W`。

| 指标 | 优化前 | 优化后 | 变化 |
|---|---:|---:|---:|
| 设备上 `base.apk` 字节 | 35,146,618 | **22,661,146** | **−12,485,472** |
| `du -sk` | 34,304 KB | **22,096 KB** | −12,208 KB |
| 冷启动 TotalTime（静置后） | 205 / 302 / 289 / 301 ms | 254 / 357 / 340 / 285 / 217 ms | 中位数 ~295 → ~285 ms（同量级，**无退化**） |
| `TOTAL PSS` | 205,264 KB | **166,003 KB** | −39,261 KB |
| `TOTAL RSS` | 340,772 KB | 304,868 KB | −35,904 KB |
| `logcat -b crash` | — | **空** | 无崩溃 |

- **minified release 在真机 arm64 上能装、能起、无 crash**（T20 的「冷启动」缺口在 arm64 上补上了）。
- 安装字节差额（−12,485,472）与 APK 差额**逐字节一致**（`extractNativeLibs=false`，
  native 不解出，安装占用 ≈ APK 大小）。
- 首次安装后的第一次启动会偏慢（1190/517/937 ms），是 dexopt / profile 安装的
  一次性开销；静置后回到与优化前同量级。**不把它当成性能回归**，也不当成收益。
- 设备已恢复为**优化后的 release 包**（`com.bd2toolsbox` 0.2.16 / versionCode 17 /
  `primaryCpuAbi=arm64-v8a`）；系统 `font_scale=1.0`、`ui_night_mode=3` 与测试前一致。

## 3. 真机上**跑不了**的用例与原因（设备策略，非产品缺陷）

### 3.1 MIUI 拒绝 instrumentation 启动 Activity

`ActivityScenario` 系与 Compose 系用例在真机上挂住/崩进程。以
`ObjectGraphLifecycleTest#coldStartDoesNotCrashOnUninitializedLateinit` 为例，
清空 logcat 后单跑，设备侧留下决定性证据：

```
I TestRunner: started: coldStartDoesNotCrashOnUninitializedLateinit(…ObjectGraphLifecycleTest)
D ActivityStarterImpl: MIUILOG- Permission Denied Activity : Intent {
    act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER]
    flg=0x10008000 xflg=0x4 cmp=com.bd2toolsbox/.MainActivity }
    pkg : com.bd2toolsbox uid : 10577 tuid : 10363
```

即 **MIUI 的「后台弹出界面」策略拒绝了本进程（uid 就是被测应用自己）启动 Activity**，
`ActivityScenario.launch(...)` 因此永远等不到 RESUMED。

尝试过的设备侧开关（均**未生效**，`appops` 仍是 `default` + 新的 `rejectTime`）：
`appops set com.bd2toolsbox SYSTEM_ALERT_WINDOW allow`、加 `--uid`、
以及经 `su -c` 的同一命令。本轮不再改 MIUI 的其它系统级开关。

受此影响、**arm64 真机上未能运行**的用例：

| 用例 | 类型 | x86_64 MuMu 结果 |
|---|---|---|
| `di.ObjectGraphLifecycleTest` | `ActivityScenario` | 3/3 PASS |
| `di.HiltEntryPointTest#mainActivityColdStartsUnderHilt` | `ActivityScenario` | PASS |
| `platform.PlatformBehaviorTest` 的 3 个前台服务用例（`foregroundServiceStartsWhenAppIsInForeground`、`deniedNotificationPermissionDoesNotBlockForegroundService`、`refusedForegroundServiceStartIsReported`） | 需把 Activity 带到前台 | 通过 |
| `feature.settings.LicensesScreenTest` | Compose | 1/1 PASS |
| `ui.WallpaperReadabilityTest` / `ComponentContractTest` / `ModActionsTest` / `TaskPanelLifecycleTest` / `DestructiveActionConfirmationTest` / `AdaptiveAccessibilityTest` / `CdkResultPresentationTest` | Compose | 全部 PASS |

**这批用例的 arm64 结论保持「未验证」**，不用「MuMu 过了」代替。

### 3.2 16 KB 页运行时未验

真机 `getconf PAGE_SIZE` = **4096**，MuMu 也是 4 KB。
`packagedJniLibrariesAre16KbAligned` 只证明**打包侧**（ZIP 对齐 + `p_align`）达标，
`jniLibrariesLoadOnThisPageSize` 在 4 KB 页设备上只作对照。
**16 KB 页设备上的真实 dlopen 仍未验**（T15/T51 的登记项）。

## 3.3 发现并修掉一个真实回归（本次真机验收的主要产出）

跑 `GameFileCommitTest` 时红了 2 条，且在 x86_64 MuMu 上**同样复现**（→ 不是设备差异）。
根因是 `e993800`（T08/T10）在 `ShizukuFileService.copyFile` 之后加的
`makeWorldReadable(dest)` 里**硬编码 `chmod 0600`**，把 `AtomicFileWriter` 刚按契约
搬好的权限覆盖掉。T07 的 13 条用例当时全绿，之后 19 天没人再跑过这个类，回归因此静默。

修法：只保留 chown（属主才是「shell/root 写的文件 app 读不到」的成因），
去掉 chmod 与 `setReadable` 回退；函数改名 `restoreParentOwner`。
修复后 arm64 真机与 x86_64 MuMu **都回到 13/13**，全量 JVM **694/694 × 2**。

完整根因、`git log -S` 证据、两个设备上的不同表现与取舍见
[game-file-commit-permissions.md](game-file-commit-permissions.md)。

顺带修掉一个**门禁缺口**：`install -r` 会把 `POST_NOTIFICATIONS` 打回未授权，
而 `Test-Bd2Device` 没有补 `pm grant`，导致 `InstallServiceLifecycleTest` /
`ScanServiceTest` 表现为假抖动。已在 `verification.ps1` 的安装循环后补上。

## 4. 环境扰动与恢复

- 为跑用例安装过 arm64 debug / pre-shrink release / final release 三份包；
  最终停在 **final release**，数据未清（`install -r` 保留 `filesDir`）。
- `com.bd2toolsbox.test` 已卸载。
- 未改动设备系统设置（`font_scale` / `ui_night_mode` 与测试前一致）。
- **未触碰真实游戏目录**（本轮所有用例都是自建夹具或只读检查）。
- 一次性脚本 `local/device/batch_arm64.ps1`、`arm64_direct.sh`、`arm64_targeted.sh`
  留在 `local/`（忽略）。

## 5. 未完成

- §3.1 列出的 `ActivityScenario` / Compose 用例在 arm64 真机上**未验证**。
- §3.2 的 16 KB 页运行时**未验证**。
- T19 的**真实 Shizuku 授权/断连**未做：设备上装了 Shizuku，但本轮未走授权流程
  （需要用户在设备上手动确认授权，属交互步骤）。
- 单机、单次抽样；冷启动/内存只用于确认「没有明显退化」。
