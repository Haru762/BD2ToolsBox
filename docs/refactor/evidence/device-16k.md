# 16 KB 页设备验收（bd2-x64-api36-16k / emulator-5556）

- 日期：2026-10-12（Asia/Shanghai）。
- 设备：本地 AVD `bd2-x64-api36-16k`，镜像 `system-images;android-36;google_apis_ps16k;x86_64`，
  serial `emulator-5556`，x86_64，**API 36（Android 16）**。
- 关键事实：`getconf PAGE_SIZE` = **16384**。
- 为什么重要：真机（Xiaomi 2509FPN0BC）与 MuMu 都是 **4096**，所以
  「16 KB 页设备上真实 dlopen」一直是 T15/T51 登记在案的未验证项 ——
  这是**唯一**能验它的环境。

## 1. 设备侧用例（全部 PASS，且都是第 1 次尝试）

| 用例 | 结果 |
|---|---|
| `platform.PlatformBehaviorTest` | **13/13** —— 含 `packagedJniLibrariesAre16KbAligned`（ZIP 未压缩存放 + 数据偏移 16 KB 对齐 + `PT_LOAD p_align`）与 **`jniLibrariesLoadOnThisPageSize`（16 KB 页上真实 `System.loadLibrary("texture2ddecoder"/"astcenc")`）** |
| `core.engine.AndroidPythonStagingTest` | **4/4** —— Python 引擎在 16 KB 页上可用；设备上无桌面 FMOD |
| `core.engine.NativeEngineSmokeTest` | **9/9** —— 真实引擎调用（校验/扫描/重打包） |
| `service.InstallServiceRunHostTest` | **1/1** —— T22 的服务宿主 |
| `service.ScanServiceTest` | **5/5** |
| `service.InstallServiceLifecycleTest` | **4/4** |
| `core.operations.ProcessDeathRecoveryTest` | **4/4** |
| `service.GameFileCommitTest` | **13/13** —— 含权限回归修复后的全部用例 |
| `data.repository.SnapshotStoreInstanceReuseTest` | **1/1** |
| `ui.WebContentThemeTest` | **6/6** |
| `ui.InsetsAdaptationTest` | **4/4** |
| `ui.ThemeModeResolutionTest` | **5/5** |
| `ui.RecoveryBannerTest` | **6/6** |
| `ui.TaskPanelLifecycleTest` | **6/6** |
| `ui.DestructiveActionConfirmationTest` | **12/12** |
| `ui.ComponentContractTest` | **7/7** |

**合计 15 个类、87 次用例执行，全部第 1 次尝试通过。**

## 2. release smoke（同一台 16 KB 设备）

| case | 结果 |
|---|---|
| `native-load-and-decode` | **PASS** —— 真实 BC1 解码 + 调试信息确实剥离 + native ABI 审计 |
| `all-core-features` | **PASS** |
| `platform-matrix` | **PASS** —— 记录 API 36 / x86_64（`platform-matrix` 因此覆盖了 32 与 36 两个 API 级别） |
| `resources-and-licenses` | **PASS** |

## 3. 本轮修掉的两个门禁缺陷

1. **`Get-Bd2Device` 把掉线的白名单条目也算进命中数**：两台同 ABI 的设备只要有一台离线
   就报「必须显式指定 serial」，而实际并没有可挑的余地 —— 想跑那台**在线**的反而被挡住。
   歧义判定的本意是「不能默认挑一台**在线**设备」。已改成只在在线候选里判歧义；
   候选全离线时给出具体 serial 列表。
2. **同上的 stderr 陷阱**：`adb get-state` 对不在线的 serial 会往 stderr 写一行，
   而 `$ErrorActionPreference='Stop'` 把原生命令的 stderr 当终止错误。已显式降到
   `'Continue'` 并吞掉输出（与 `pm grant` 同一手法）。

## 4. 环境与恢复

- AVD 启动命令：`emulator -avd bd2-x64-api36-16k -no-window -no-audio -no-snapshot
  -gpu swiftshader_indirect -port 5556`。
- 已登记进 `docs/refactor/evidence/environment.local.json` 的 `deviceWhitelist`
  （本地忽略文件），并注明「与 MuMu 同为 x86_64，两者同时在线会命中两台」。
- **跑 16 KB 用例前需要 `adb disconnect 127.0.0.1:16384`**（MuMu），否则同 ABI 歧义。
- 该 AVD 的 `abilist` 含 `arm64-v8a`，但那是**转译**提供的 —— 不等于真实 arm64 设备，
  arm64 验收仍以真机为准（与 MuMu 的判定口径一致）。

## 5. 仍未覆盖

- **arm64 真实设备**：本 AVD 的 arm64 是转译，不算数。
- **其它 API 级别**：`bd2-x64-api29` / `bd2-arm64-api34` / `bd2-16k-api36` 等 AVD 存在，
  本轮只跑了 16 KB 那一台；T57 的完整平台矩阵仍可继续扩。
- **ASTC 实际解码**：`native-load-and-decode` 走的是 BC1，不是 ASTC。
- 无独立审查。
