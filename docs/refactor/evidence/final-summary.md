# 最终汇总（T61）

- 日期：2026-10-13。
- 分支：`refactor/android-architecture`，基点 `43f2b4e`。
- 状态：**未完成**。逐项见下。**未运行的项目保持未运行**，不用「其它项都过了」代替。

## 1. 结论先说

| 维度 | 结论 |
|---|---|
| 代码质量门禁 | **全绿**（架构 0 违规、JVM 1472/0/0/0、Python 219/0/0/0、基线清单 16/16） |
| 产物 | **两个 ABI 的 minified release 已构建**，签名与既有 0.2.16 一致（可覆盖升级） |
| 设备矩阵 | **x86_64 四个 API 档全实测**（API 29 / 32 / 34 / 36-16KB），release smoke 各 **10/10**；**arm64 真机未跑** |
| 设备用例（MuMu 最终门禁） | **18/18 PASS**（16 个类 + 两条进程死亡编排） |
| release smoke | x86_64 四个 API 档各 **10/10 PASS**；arm64 未跑 |
| 截图 | **17 张**真实 release 截图，程序化核对 PASS |
| 对比度 | 7 预设 × 明暗 × 11 对 = **154 对全部达标** |
| 未完成的验收项 | 见 §6。**不勾 T61 完成** |

## 2. 机器门禁（全部实测）

| 检查 | 命令 | 结果 |
|---|---|---|
| 架构规则 R1–R7 | `python tools/check_architecture.py --root . --quiet` | 退出码 **0**（0 违规） |
| 基线清单核对 | `python tools/verify_manifests.py --root .` | **16/16** 冻结基线哈希全对（交付记录默认跳过，见下行） |
| 交付记录核对 | `python tools/verify_manifests.py --root . --check-delivery` | **18/18**，与当前两个 ABI 的 release 产物一致 |
| JVM 单测（两个 flavor） | `Test-Bd2UnitAll` | **tests=1472 failures=0 errors=0 skipped=0 → PASS** |
| Python 测试 | `Test-Bd2Python -Pattern 'test_*.py'` | **discovered=242 executed=242 passed=242 failed=0 skipped=0 → PASS**（19 个文件） |
| 一键本地验证 | `& tools/verification.ps1 -Mode LocalAll` | **`LocalAll: PASS`，退出码 0**（含依赖解析 → JVM → Python → 两个 ABI release 构建 → 静态审计） |
| UI 冷启动宿主测试 | `tools/tests/Test-UiColdStartHarness.ps1` | **19/19 PASS** |

## 3. 交付产物

两个 ABI 的单 flavor minified release（**无 universal**，见 T49）：

| ABI | 路径 | 字节数 | SHA-256 | 签名证书 SHA-256 |
|---|---|---|---|---|
| arm64-v8a | `app/build/outputs/apk/arm64/release/app-arm64-release.apk` | 22,693,914 | `0d9c89f83554b94e6a1284a642f62a95c94ea40b038b129b64d0bc52b34e9dc6` | `892d95f0be2e42fa1293247e5153c1b90d04adb13a70ebff1e63a705156bdbd1` |
| x86_64 | `app/build/outputs/apk/x64/release/app-x64-release.apk` | 22,865,999 | `d70ab6a15c13d6c26807921c34c549f9449586e83bdaed8c5c3e2384758a12ff` | 同上 |

- 身份：`versionCode=17`、`versionName=0.2.16`（与冻结基线一致，见 §5）。
- 签名指纹与设备上既装 0.2.16 **相同** → 用户**可直接覆盖升级、无需清数据**。
- R8 mapping：`app/build/outputs/mapping/{arm64Release,x64Release}/mapping.txt`
  （44,592,671 / 44,592,588 字节，SHA-256 `b884f8c3…` / `74fd6d2c…`）。
- native 符号与 16 KB 对齐：见 `docs/refactor/evidence/native-strip.md`、`abi-packaging.md`。
- 许可清单：`THIRD-PARTY-NOTICES.md`、`docs/refactor/license-inventory.md`。

### 3.1 包体对比（对照冻结基线）

| 阶段 | arm64 字节数 | 相对基线 |
|---|---|---|
| 冻结基线（优化前） | 35,146,618 | — |
| 本轮交付 | 22,693,914 | **−35.42%** |

（T54 记录的是 −35.52%；本轮因 T56 的 `MoveCommit` 修复与恢复横幅入口增加了 32,768 字节。
两份数字都对，只是时点不同。）

## 4. 设备矩阵

| 平台 | 版本/页大小 | 结果 |
|---|---|---|
| MuMu 模拟器 `127.0.0.1:16384` | x86_64 / API 32 / 4 KB | 设备用例全过；release smoke **10/10 PASS** |
| AVD `bd2-x64-api29` | x86_64 / API 29（= minSdk）/ 4 KB | 设备用例全过；`ModWorkflowAcceptanceTest` **6/6** |
| AVD `bd2-x64-api36-16k` | x86_64 / API 36 / **16 KB** | `PlatformBehaviorTest` **13/13**（含真实 `System.loadLibrary`） |
| **真机 Xiaomi 2509FPN0BC** | **arm64-v8a / API 36 / 4 KB** | **本轮未跑**（见 §6） |

## 5. 版本号：**刻意不改**

`gradle/version.properties` 保持 `appVersionName=0.2.16` / `appVersionCode=17`。

理由（计划的硬性要求）：**发版号必须按渠道当前最大值决定**，不能拿本次开发用的
`17` 盲推未来的发布号。计划原文：「版本号和发布权限属于实际发版时的操作核对，
不提前发包或暴露凭据」。发版前按 `release-checklist.md` §4 实际查渠道当前最大值后再改。

## 6. 未运行 / 未完成（逐条，不勾）

### 6.1 本轮未跑

- **arm64 真机**：本批改动（T56 端到端、T59 性能门禁、T58 UI、T30/T28 拆分、恢复横幅入口）
  未在真机上重跑。**本轮结束时真机未连接**（USB 已拔、无线调试未开，`adb devices` 里只有 MuMu），
  所以这一项是被物理条件卡住的，不是被跳过。
  已准备好一条命令的验收脚本 `local/device/arm64_acceptance.ps1`（设备身份 → 两个 ABI release 构建 →
  10 个 smoke case → 4 个设备类 → 两条进程死亡编排 → 交付记录核对；设备不在线时立即停并报明原因，
  实测退出码 1）。
- **arm64 上的 release smoke 10 个 case**。
- **16 KB 页上的性能数字**（只跑了功能）。
- **API 29 上的性能数字**。

### 6.2 长期缺口（本轮之前就登记过）

- **ASTC 实际解码**未在设备上验（`libastcenc` 路径）。
- **T19 真实 Shizuku 授权**需人工确认。
- **真实软键盘 / 刘海观感**、**旋转与滚动恢复**、**UI 截图观感**：需交互或截图。
- **真实 SAF 树 URI 建 `.nomedia`**（T62 的 `SafMediaExclusion` 只做了单测）。
- **磁盘写满、真实网络中断、真实 Binder 断连**的故障注入。
- **T60 的隔离环境干净构建**（在全新 checkout 上从零构建）。
- **T58 的 UI 截图与可读性回归**未做。
- **无独立审查**（本重构全程由同一会话推进）。

### 6.3 明确记录为「部分完成」的任务

T25、T56、T59 三项**没有**勾 DONE，各自证据文件里列了缺什么。
理由：计划要求「未运行/失败的关键项不能勾完成」。

## 7. 工作区交接

- 实施工作区：`D:/bd2 mod/worktrees/bdroid-android-refactor`（分支 `refactor/android-architecture`）。
- 只读源仓库：`D:/bd2 mod/bdroid_src`。
- 本地未跟踪文件（**刻意保留、不提交**）：`diff.txt`、`patch.py`、
  `docs/superpowers/plans/2026-09-23-*.md`（3 份）、
  `release-probe/.../Release{Arm64DependencyProbe,PythonNativeStderrProbe}Test.kt`。
- 被忽略的本地配置：`docs/refactor/evidence/environment.local.json`（设备白名单）、
  `local/`（一次性脚本与设备辅助脚本）。
- 并行会话的改动（`tools/audit_native_abi.py` 等）**原样保留**，未被暂存或提交。

## 8. 权限边界（重申）

以下动作**未做、也不在未经批准时做**：合并、推送、发布、移除 worktree、修改对外版本号。
