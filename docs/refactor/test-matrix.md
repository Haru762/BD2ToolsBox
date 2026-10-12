# 测试矩阵（T04）

本文件记录本轮验证入口覆盖到什么、靠什么输入、以及**哪些还没覆盖**。
它描述的是**判定规则与当前实测状态**，不是「计划要写哪些测试」。

配套文件：
- 夹具与输入清单：`docs/refactor/fixture-manifest.json`
- 输入/产物/环境三类目录的分离规则：`.gitignore`（整个 `/local/` 本机专用）
- 原始报告归档：`local/refactor-artifacts/test-results/<运行ID>/`（被忽略，不入库）
- 可提交证据只记录脱敏摘要、哈希与相对路径：`docs/refactor/evidence/T0x.md`

---

## 1. 判定规则

### 1.1 四个入口共用的规则

| 规则 | 说明 |
|---|---|
| **skip 不是通过** | 必需用例被 skip 一律判失败（`required-test-skipped`）。可选覆盖只能标 NOT_RUN，不抵扣必需项。**设备侧同理**：AndroidJUnitRunner 的 `OK (n tests)` 把 `@Ignore`（code `-3`）与 `Assume` 不成立（code `-4`）也算进 `n`，必须按逐条状态码判定，不能只看尾部。 |
| **退出码 0 不是验收** | 只看进程退出码会得到假绿色：本仓实测过 `python -m unittest` 在 6 条真实用例全被 skip 时照样 `exit 0` 并报 `OK (skipped=6)`。判定一律看**逐用例结果 + 必需集合 + 夹具哈希**。 |
| **零用例 / 无报告非零** | 发现到 0 个用例、报告缺失或不可解析、必需测试 ID 未被发现，都是失败。 |
| **失败要能复现** | 人为破坏实现必须让对应测试真的失败，见 `docs/refactor/evidence/T04.md` 的变异实验记录。 |
| **失败也要落档** | 运行失败应留下 `summary.json`（含 `result` 与 `reason`），包括构建、安装和 runner 启动失败。**非法输出路径除外**：只向 stderr 报错并非零退出，不能为生成失败报告而创建越界目录或覆盖已有文件。 |

### 1.1.1 审查修复后的边界（2026-09-28）

- 验证工作区只允许 `refactor/android-architecture` 与本轮授权的 `fix/review-closeout`；仍拒绝 main、detached HEAD、跨工作树加载脚本。
- Python `--output` 必须是当前仓库 `local/` 下不经过 symlink/junction 的**新 JSON**；发现前、建目录前及独占创建文件前均校验，已有报告不覆盖。
- 必需用例不能通过 `--allow-skip` / `-AllowSkip` 豁免；expected-failure 同样不是必需覆盖通过。
- JVM `-TestClass` 支持 `*`，按实际类名匹配，并校验对应夹具及 XML 真实 testcase 数量。
- instrumentation 必须有成功终态、成功摘要和匹配的测试起止身份/数量；非零传输退出码、崩溃、缺终态或残缺运行均失败。
- 本轮定向 RED/GREEN 结果见 [修复证据](evidence/review-closeout.md)；下文 T04 的历史数量不代表本轮运行数。


### 1.2 夹具规则（各入口的实施范围不同，别按表头想当然）

| 规则 | 说明 | 谁实施 |
|---|---|---|
| 夹具缺失或哈希不符非零 | `fixture-missing` / `fixture-hash-mismatch` / `fixture-not-configured`。派生夹具记录**自己的**哈希，不沿用原文件哈希。 | `Test-Bd2Python`（全量，经 `run_python_tests.py`）；`Test-Bd2Unit`（按 manifest `usedBy` 匹配到本类的条目，见 T04.md §5.4.2）|
| 只要求当前选择范围的夹具 | runner 按 `usedBy` 里出现的 `.py` 文件名与 `--pattern` 匹配来选夹具；没被选中的范围不会因为无关输入缺失而失败。 | 仅 `Test-Bd2Python` |
| 夹具路径可来自本机配置 | `externalFixtures.catalogAlpha` 取自被忽略的 `environment.local.json`；**新机器没有这份文件时依赖它的用例会一律失败**，这是设计而非缺陷（拒绝默默跳过）。 | 仅 `Test-Bd2Python` |

`Test-Bd2Device` / `Test-Bd2ReleaseSmoke` 目前**不做**夹具存在性/哈希判定：
前者用的测试数据由用例自身在设备上构造，后者的基线 APK 哈希在入口内单独核对
（见 T04.md §6 的 F1/F2/F3b）。

必需集合：`fixture-manifest.json` 里 `requiredness` 为 `g0-required` / `phase-required` 的夹具是**输入**上的必需项；
测试**用例**上的必需集合默认取本次发现到的全部用例（`--required` 显式收窄时才用给定清单，未发现的 ID 直接失败）。

---

## 2. 入口与报告

| 入口 | 范围 | 结果来源 | 原始报告落点 |
|---|---|---|---|
| `Test-Bd2Unit -TestClass <类名或通配选择器>` | JVM 单测（实际 variant） | `app/build/test-results/<task>/TEST-*.xml` 真解析；外加按 manifest `usedBy` 匹配到本类的夹具存在性/哈希核对 | `test-results/<运行ID>-unit-<类名>/summary.json`（含 `fixtures` 段）+ 复制过来的 XML |
| `Test-Bd2Python -Pattern <文件模式>` | Python unittest | `tools/run_python_tests.py` 的 JSON（逐用例 ID + 计数 + 夹具结论 + reasons） | `test-results/<运行ID>-python-<模式>/report.json` |
| `Test-Bd2Device -TestClass <类名> -Abi <ABI>` | 设备 instrumentation | `am instrument -w -r` 的逐测试起止身份/状态码、声明数量、`OK (n tests)` 与 `INSTRUMENTATION_CODE: -1` 必须一致，且 `adbExit == 0`。跳过、错误、崩溃、缺终态、残缺或乱序输出均失败 | `test-results/<运行ID>-device-<ABI>-<类名>/`（成功与失败都写 `instrumentation.txt` + `summary.json`；**构建/安装阶段的失败也写** `summary.json`，见 T04.md §5.4.1） |
| `Test-Bd2ReleaseSmoke -Case <case> -Abi <ABI>` | 真实 minified release | apksigner 证书 + 清单哈希 + 设备内 `ReleaseContractProbeTest` | `test-results/<运行ID>-smoke-<case>-<ABI>/`（`summary.json` 同样在 `finally` 里落盘） |
| `verification.ps1 -Mode LocalAll` | 本地全量调度 | 上述各步；**未实现步骤一律非零**，不返回空成功 | 各步各自的运行目录 |

当前 variant 映射由**真实 Gradle 任务清单**决定，不写死：`testArm64DebugUnitTest` / `testX64DebugUnitTest`（T49 才有）
不存在时回退 `testDebugUnitTest`，两者都没有即抛错。

---

## 3. 覆盖现状

计数口径：JVM/设备列是 `@Test` 注解的**静态计数**；Python 是 unittest 发现到的**动态计数**（已实跑）。

### 3.1 JVM 单测 `app/src/test/java`（**66 个类 / 728 条用例**，2026-10-12 实测）

| 文件 | 用例 | 覆盖内容 | 依赖夹具 | 状态 |
|---|---:|---|---|---|
| `data/repository/GamekeeBattleV2RendererTest.kt` | 9 | v2 阵容渲染：回合编号、站位、行动顺序与技能、空位占位、备注转义、异常 content、默认展开 | `repo.wiki-v2-detail-2238`（classpath，g0-required） | 实测 PASS（9/9，0 skip） |
| `integration/PrepackCacheIntegrationTest.kt` | 10 | 「预解包 → 缓存」落盘判定：合并 / 记负缓存 / 丢弃三选一；真实 `gen`/`.srcsize`/`.nopreview` 文件与 `isValid`/`isKnownNoPreview`/`count()`；含覆盖竞态回归（同槽换内容不得留下上一轮文件） | 无外部夹具（`Context` 用 mockk 桩 `getExternalFilesDir`） | 实测 PASS（10/10，0 skip） |

JVM 侧约束：本仓**没有 Robolectric**，`android.*` 只有 stub，所以单测不得构造 `ContextWrapper`（Android 类，会撞 `Stub!`），
文件访问边界一律用 mockk 桩 `Context`。

`GamekeeBattleV2RendererTest` 的夹具从 2026-09-16 起**真的参与判定**：`Test-Bd2Unit` 会按
manifest 的 `usedBy` 找到 `repo.wiki-v2-detail-2238`，核对存在性与哈希，不符即判失败。
此前是空转的——该文件不查存在也不查哈希（见 `T04.md` §5.4.2）。`PrepackCacheIntegrationTest`
不声明任何外部夹具，故它的 `fixtures` 段为空数组，属正常。

### 3.2 Python `tests/`（**16 个文件 / 208 条用例**，2026-10-12 实测）

| 文件 | 用例 | 覆盖内容 | 依赖夹具 |
|---|---:|---|---|
| `test_resolution_catalog_fallback.py` | 46 | 目录解析回退与真实 catalog 兼容（`RealCatalogCompatTest` 4 条 + `DatingPrefabBridgeTest` 2 条原先因定位失败被 skip，T04 迁移后实际运行） | `external.catalog-alpha`（`BD2_TEST_CATALOG` 注入，g0-required） |
| `test_resolver_cross_bundle.py` | 4 | 跨 bundle 解析 | — |
| `test_scan_checkpoint.py` | 5 | 断点续扫：`checkpoint_scan` 落盘 + 原子写入 + 恢复后按哈希跳过已扫 | — |
| `test_scan_full_default.py` | 3 | 全量扫描默认行为 | — |
| `test_spill_window.py` | 5 | 溢出窗口 | — |
| `test_unpack_fast_filter.py` | 8 | 快速解包过滤 | — |

`BD2_TEST_CATALOG` / `BD2_TEST_CATALOG_SHA256` 由 `Test-Bd2Python` 在**调用期间**注入、结束后恢复原进程环境值
（`try/finally`，不泄漏到后续调用）。测试文件在**模块顶层**读取该环境变量 ——
`@unittest.skipUnless` 这类类装饰器在导入期求值，事后 monkeypatch 无效。

### 3.3 设备 instrumentation（`app/src/androidTest/java` **39 个类 / 190 条用例**，另 `:release-probe` 7 个类）

| 文件 | 用例 | 覆盖内容 | 设备覆盖（x86_64，`127.0.0.1:16384`） |
|---|---:|---|---|
| `data/repository/LogExporterTest.kt` | 3 | 日志导出 | 实测 PASS（3/3，0 skip） |
| `data/repository/ModDiscoveryTest.kt` | 12 | MOD 发现 | **11 PASS + 1 从未执行**（`realLibraryPreservesAllPreviouslyVisibleAndroidVariants` 因缺 `libraryManifest` 夹具被 `Assume` 跳过，见下） |
| `data/repository/ModSnapshotTest.kt` | 4 | MOD 快照 | 实测 PASS（4/4，0 skip） |
| `data/repository/PreviewCacheNegativeTest.kt` | 5 | 负缓存标记与失效 | 实测 PASS（5/5，0 skip） |
| `data/repository/PreviewCacheSizeSlotTest.kt` | 6 | 缓存按 size 分槽、变体互不覆盖 | 实测 PASS（6/6，0 skip） |
| `service/ScanServiceTest.kt` | 5 | 扫描服务 | 实测 PASS（5/5，0 skip） |
| `release-probe/…/integration/ReleaseContractProbeTest.kt` | 1 | `baseline-startup`：release 包身份、runner、主界面可启动、载荷与设备 ABI 自洽、与测试包同签名 | x86_64 实测 PASS（见 T04 证据 §5.1）；arm64-v8a **未覆盖** |

前 6 个类原先记作「一次都没跑过」，T04 补齐上机。**但 6 个类第一轮全部报 PASS 是假象**：
`ModDiscoveryTest` 的尾部是 `11 个点 / OK (12 tests)`，即 1 条被 `Assume` 跳过却仍被计入
`n`。当时设备入口只匹配尾部 `OK (n tests)`，看不见 skip——与 §1 第一条规则相反。
已改为按 `am instrument -w -r` 的逐条 `INSTRUMENTATION_STATUS_CODE` 判定（`-3`/`-4` 即失败），
并让失败运行也落盘 `summary.json`。详见 T04 证据 §5.3。

`libraryManifest` 这条夹具在仓库里不存在，也没有任何调用方传该参数，所以那条用例
**在上机条件下必然跳过**——记为「从未执行」，不记为通过。

---

## 4. 明确未覆盖（不得据本矩阵推断为通过）

| 缺口 | 说明 | 归属 |
|---|---|---|
| arm64-v8a **真实设备** | 真机 `f7f409ac` 已在本轮跑过一批（见 `device-arm64.md`），但**本批接线没有 arm64 证据**（当时真机在充电）。MuMu 与 AVD 的 `arm64-v8a` 都是**转译/仿真**，不算数。 | 待真机 |
| `libraryManifest` 只读目录快照 | `ModDiscoveryTest#realLibraryPreservesAllPreviouslyVisibleAndroidVariants` 需要 `-e libraryManifest <path>` 指向一份捕获的只读目录快照（断言硬编码 1044 条 Android-only / 1741 条混合）。仓库里没有该夹具，也没有调用方传该参数，故该用例**上机必跳过**。它声称覆盖的「真实只读目录快照」实际是空的 —— 要么补夹具，要么承认无覆盖。 | 待定 |
| **ASTC 实际解码** | `native-load-and-decode` smoke 走的是 **BC1**（`decode_bc1`）。`libastcenc` 的 ASTC 解码入口没有端到端调用。 | T51 |
| **真实软键盘 / 刘海 / 横屏侧边栏观感** | `InsetsAdaptationTest` 验的是结构契约（`windowInsetsPadding` 真的产生位移、输入框在 `imePadding` 容器里可用），**不是**真实软键盘弹出后的截图核对。 | T46 / T58 |
| **真实 SAF 树 URI 上建 `.nomedia`** | `SafMediaExclusion` 的 JVM 侧契约有覆盖，但真实树 URI 上 `DocumentFile.createFile` 需要交互授权，**未验证**。 | T62 |
| **真实 Shizuku 授权 / 断连** | 设备上装了 Shizuku，但授权需要人工在设备上确认，**未走流程**。`ShizukuConnectionLifecycleTest` 只覆盖不依赖真实绑定的部分。 | T19 |
| **旋转 / 滚动恢复** | `ModFilterRestorationTest` 验的是 `SavedStateHandle` 往返，**不是**真实旋转后的界面状态。 | T28 / T58 |
| **UI 截图观感** | 主题/壁纸/明暗/大字体在真实页面上的观感未截图核对。 | T40 / T41 / T58 |
| **16 KB 页上的 ASTC / 更多 API 级别** | 16 KB 页已验（`device-16k.md`），但只有 API 36 那一台；API 30/31/33/34/35 未跑。 | T57 |
| 安装账本 / 兑换历史 / 隐藏列表 / 更新 APK | 设备侧保全时即缺失，见 `fixture-manifest.json` 的 `gaps`。 | T25 / T28 / T31 / T35 / T57 |

### 4.1 已经闭合、从上一版缺口表里移除的

| 原缺口 | 现状 |
|---|---|
| release 契约全量（9 个 case） | **9 个 case 全部实现**并在 x86_64 MuMu 上 PASS（`json-migration`、`native-load-and-decode`、`resources-and-licenses`、`engine-and-preview`、`full-r8-contract`、`all-core-features`、`performance-scenarios`、`platform-matrix` + 原有两个）。见 `wiring-2026-10-11.md`。 |
| `LocalAll` 完整调度 | **已完成**：依赖 → JVM → Python → 两个 release 构建 → 静态审计（含基线清单核对），全新 PowerShell 进程实测 exit 0。见 `build-and-verify.md`。 |
| 单 ABI flavors | **已存在**：`arm64`/`x64` 两个 flavor，JVM 单测跑在 `testArm64DebugUnitTest` + `testX64DebugUnitTest` 上（每类各跑一遍）。 |
| 16 KB 页运行时 | **已验证**：AVD `bd2-x64-api36-16k`（`PAGE_SIZE` = 16384）上 15 个类 / 87 次执行全 PASS，含真实 `System.loadLibrary`。见 `device-16k.md`。 |

---

## 4.2 T56 要求的功能场景 → 实际覆盖（2026-10-12）

T56 要求覆盖 14 类场景，并「在复制/校验/备份/账本/Binder/网络各边界注入失败」。
下表是**实际**覆盖位置（不是计划）；标「缺」的即当前没有覆盖。

| 场景 | 覆盖位置 | 状态 |
|---|---|---|
| 导入（导入 mod 源目录） | `ModDiscoveryTest` 12（设备）、`ModRepository` 相关 JVM | 部分 |
| 来源扫描 | `ScanExecutorTest` 7（JVM，含未连接/读取失败/占用释放）、`ScanServiceTest` 5（设备）、`ModDiscoveryTest` 12（设备） | 有 |
| 游戏索引扫描 | 同上 | 有 |
| 预解包 | `PrepackExecutorTest` 8（JVM）、`PrepackCacheIntegrationTest`（JVM）、`PrepackService` 设备侧 | 有（Service 本体靠设备） |
| SD/HD 转换 | `NativeEngineSmokeTest` 9（设备，真实转换 + 产物逐字节检查） | 有（单一路径） |
| 同 bundle 多 MOD 叠加/卸载 | `UninstallExecutorTest` 5、`InstallExecutorTest#restoringOneBundleCannotClearOtherInstalledRecords` | 有 |
| 跨 bundle | `InstallExecutorTest#commitsOnlyFrozenTargetsAndPreservesUnrelatedDownloads` | 有 |
| 日期/好感剧情 | `tests/test_scan_full_default.py` 3（全量扫描默认，修「剧情/好感类 mod 永远 UNKNOWN」）、`tests/test_spill_window.py` 5（500MB 体量帽与磁盘溢出，修 422MB 约会大包预览被拒） | **有**（纯 IO 与判定链；真实大包的峰值内存靠基准，不在本矩阵） |
| 静态/Spine 预览 | `PreviewCacheNegativeTest` 5、`PreviewCacheSizeSlotTest` 6（设备）、`tests/test_unpack_fast_filter.py`（解包快筛）、`SpinePreviewActivity` 主题（T47）、`WebContentThemeTest` 6（设备，真实 WebView） | 部分（pixi-spine 运行时的真实加载未在设备上验） |
| Wiki 双版本 | `GamekeeBattleV2RendererTest`（真实抓下的 v2 夹具 `wiki-v2-detail-2238.json`，夹具哈希已登记在 fixture 清单）、`GuideHtmlRendererTest` 7、`GuideRequestRaceTest` 14 | 有（v2 用真实夹具；v3/其它版本没有夹具） |
| 兑换 | `CdkViewModelTest` 24、`RedeemSummaryTest` 20、`CdkResultPresentationTest` 5（设备） | 有（真实官方接口未跑） |
| 更新 | `UpdateViewModelTest` 14、`ApkSelectorTest` | 有（真实下载/安装未跑） |
| 日志导出 | `LogExporterTest` 3（设备） | 有 |

### 4.2.1 故障注入边界

| 边界 | 覆盖位置 |
|---|---|
| 复制失败 | `InstallExecutorTest#fileReplacementFailureKeepsSourceAndPendingJournal` |
| 校验失败（源在计划后变化） | `InstallExecutorTest#aSourceChangedAfterPlanningNeverReachesGame` |
| 账本失败 | `InstallExecutorTest#ledgerFailureAfterCopyIsNotReportedAsSuccessfulInstall` |
| 备份门禁 | `BackupFailureSummaryTest` 5（设备）、`TryUseBackupTrustGateTest` 2（设备） |
| Binder/连接 | `ScanExecutorTest`（未连接 vs 读取失败、占用释放）、`ShizukuFileServiceListingTest` 5（设备） |
| 网络 | `GuideRequestRaceTest` 14（请求代次）、`CdkViewModelTest#repositoryFailureIsSurfacedNotSwallowed` |
| 进程死亡 | `ProcessDeathRecoveryTest` 4（设备，进程内分类）、**`JournalRestartPhaseTest` + `Test-Bd2JournalRecovery`（真实 `am force-stop` 后新进程读回，PID 必须不同）**、`LedgerRecoveryStartupTest` 4（设备）、`OperationRecoveryTest`（JVM） |
| 磁盘/权限 | `GameFileCommitTest` 13（设备，含 API 29 的卷不兑现权限的前提判定） |

**没有**覆盖的边界：磁盘写满、真实网络中断（只做了「仓储抛异常」这一层）、
真实 Binder 断连（需要 Shizuku 授权）。

（「提交中途进程被杀」已在 2026-10-12 补上：见上面「进程死亡」那一行。）

### 4.2.2 与 T56 验收条的对照

| T56 验收条 | 现状 |
|---|---|
| 无半份 `__data` | `AtomicFileWriter` 的同目录暂存 + 原子替换有设备覆盖；**进程被杀在提交中途**的实测未做 |
| 误删来源 | `InstallExecutorTest#commitsOnlyFrozenTargetsAndPreservesUnrelatedDownloads` |
| 污染可信原版 | `BundleBackupRepositoryTest` 36、`TryUseBackupTrustGateTest` 2 |
| 丢安装记录 | `InstallExecutorTest#ledgerFailureAfterCopyIsNotReportedAsSuccessfulInstall` |
| 请求晚到覆盖新页面 | `GuideRequestRaceTest` 14（含红绿证伪） |
| 虚假兑换成功 | `CdkViewModelTest` 24、`RedeemSummaryTest` 20 |
| 破坏性场景在隔离数据上跑 | 是：所有用例都在自建临时目录或自建夹具上跑，**不用真实游戏目录** |

---

## 5. 维护约定

- 新增或迁移测试后，本矩阵的计数与状态必须同步更新；**静态计数**与**实跑计数**要分开写，不得混用。
- 设备侧记「通过」必须附**逐条状态码计数**（`started` / `passed` / 跳过），只写 `OK (n tests)`
  不作为依据——该行的 `n` 含被跳过的用例。跳过要**点名**是哪些用例、为什么。
- 不把「测试文件存在」记为覆盖；只有实跑过、且报告归档到 `test-results/<运行ID>/` 的才算。
- 不通过删断言、放宽 golden 期望或把失败改成 skip 来制造通过；真实业务缺陷允许 FAIL 并挂到后续任务。
