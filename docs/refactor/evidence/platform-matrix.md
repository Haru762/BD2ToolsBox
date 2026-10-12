# 平台 / ABI / API 矩阵（T57）

- 日期：2026-10-13
- 分支基点：`c3ec191`
- 被测 APK：minified release，arm64 `contentSha256 58593211…` / x64 `8c4f06f8…`
- 判定口径：**每个格子的结果都来自实际执行**；没跑的一律写「未跑」，不用别的格子代替。

## 1. 矩阵

| 平台 | ABI | API | 页大小 | 设备 | ASTC 往返 | 平台行为 | 端到端 | release smoke |
|---|---|---|---|---|---|---|---|---|
| MuMu 模拟器 | x86_64 | 32 | 4 KB | `127.0.0.1:16384` | **3/3 PASS** | **13/13 PASS** | **6/6 PASS** | **10/10 PASS** |
| AVD `bd2-x64-api36-16k` | x86_64 | 36 | **16 KB** | `emulator-5556` | **3/3 PASS** | **13/13 PASS** | **6/6 PASS** | **10/10 PASS** |
| AVD `bd2-x64-api34` | x86_64 | **34** | 4 KB | `emulator-5558` | **3/3 PASS** | **13/13 PASS** | **6/6 PASS** | 未跑（本轮） |
| AVD `bd2-x64-api29` | x86_64 | **29**（= minSdk） | 4 KB | `emulator-5554` | **3/3 PASS** | **13/13 PASS** | **6/6 PASS** | 未跑（本轮） |
| 真机 Xiaomi 2509FPN0BC | **arm64-v8a** | 36 | 4 KB | `f7f409ac` | **未跑** | 未跑（本轮） | 未跑（本轮） | 未跑（本轮） |

- **16 KB 页**那一格是 `getconf PAGE_SIZE` 实测的 16384，不是声明的；
  `PlatformBehaviorTest` 里含真实 `System.loadLibrary` 与打包 JNI 库的 16 KB 对齐断言。
- API 29 是 minSdk，覆盖「最老的受支持系统」这一端；API 34 是计划点名的另一档。
  **API 29 / 32 / 34 / 36 四档都实测过**，其中 36 是 16 KB 页。
- **arm64 真机整行未跑**：本轮结束时该设备未连接（USB 已拔、无线调试未开）。
  已备好一条命令的验收脚本 `local/device/arm64_acceptance.ps1`。

## 2. 本轮新增到矩阵里的那一项：ASTC 真实往返

`AstcRoundTripTest`（3 条）走生产 `repacker.compress_image_astc` +
`texture2ddecoder.decode_astc`，在**三个平台**上都跑通：

| 平台 | 结果 | 关键数字 |
|---|---|---|
| x86_64 / API 32 / 4 KB | PASS | `encoded==expect==3072`、`flat_maxdiff=0`、`noisy_mae=25.7>0`、`mae=4.82` |
| x86_64 / API 36 / **16 KB** | PASS | 同上 |
| x86_64 / API 34 | PASS | 同上 |
| x86_64 / API 29 | PASS | 同上 |

`flat_maxdiff=0` 这一条最能说明问题：布局错位或 RGBA/BGRA 通道顺序弄反都会在这里露出来。

## 3. 签名与安装（release）

| 项 | 值 |
|---|---|
| 签名证书 SHA-256 | `892d95f0be2e42fa1293247e5153c1b90d04adb13a70ebff1e63a705156bdbd1` |
| 与既有 0.2.16 是否同一证书 | **是** → 用户可直接覆盖升级、无需清数据 |
| 签名方案 | 仅 v2（v1/v3/v3.1/v4 均 false） |
| 包名 / versionCode / versionName | `com.bd2toolsbox` / 17 / 0.2.16 |
| minSdk / targetSdk / compileSdk | 29 / 34 / 36 |
| universal APK | **不存在**（单 ABI flavor，T49） |

## 3.5 16 KB 页上的 release smoke（10/10）

```
16KSMOKE baseline-startup -> PASS         16KSMOKE engine-and-preview -> PASS
16KSMOKE snapshot-compatibility -> PASS   16KSMOKE full-r8-contract -> PASS
16KSMOKE json-migration -> PASS           16KSMOKE all-core-features -> PASS
16KSMOKE native-load-and-decode -> PASS   16KSMOKE performance-scenarios -> PASS
16KSMOKE resources-and-licenses -> PASS   16KSMOKE platform-matrix -> PASS
```

性能（16 KB 页）：冷启动中位数 **989 ms**（原始 1014/958/989/1048/943）、
PSS 中位数 **136,598 KB**。

> 第一次跑时 `json-migration` 报了 Gradle 错、`performance-scenarios` 报了
> 「冷启动未返回 TotalTime」。前者是**我并发跑了另一个 Gradle 构建**造成的锁冲突，
> 干净重跑即过；后者查出来是暖启动拿到 `TotalTime: 0` 被当成有效样本 —— 已修（见 T56 证据）。

## 4. 明确未覆盖

- **arm64 真机整行**（见 §1）。
- **API 29 / API 34 上的 release smoke 10 个 case**：本轮只跑了设备用例；
  release smoke 已在 MuMu（API 32）与 16 KB AVD（API 36）上跑满 10 个。
- ~~API 34 缺~~ → **已补**（`bd2-x64-api34`，3 个类全过）。
- **arm64 的 API 34 AVD**（`bd2-arm64-api34`）未用：它在 x86_64 主机上是全指令转译，
  耗时长且不代表真实 arm64；arm64 一律以真机为准。
- **真实设备的首次安装、权限拒绝、后台/旋转/杀进程、未知来源安装**等系统路径。
- **网络受限/离线**场景。
