# 重构证据索引（docs/refactor/evidence/）

> 维护规则（2026-09-15 TODO §0.3）：每个任务完成后在此登记状态。恢复/续跑时先核对下表事实，再继续；不能仅凭聊天摘要或已打勾标题认定代码仍已验证。
>
> 状态取值：NOT_STARTED、IN_PROGRESS、IMPLEMENTED_NOT_VERIFIED、BLOCKED、DONE。只有 DONE 可勾选任务完成。

## 当前快照

| 项 | 值 |
|---|---|
| 阶段 | **G3 Hilt 与平台适配层进行中**（G2 已收口 T11–T15）；历史 **G2 构建与依赖治理**（2026-09-26 更新：T08 IN_PROGRESS、T10 IMPLEMENTED_NOT_VERIFIED、T11 LOCAL_VERIFIED、T12 DONE（含用户授权的已接受限制）、T13 DONE、T14 DONE、T15 DONE、T16 DONE、T17 DONE；T18 IMPLEMENTED_NOT_VERIFIED（2026-09-29，协议边界修复后需重新做原生 arm64 验证）） |
| arm64 验证策略 | 2026-09-26 起按 [arm64-verification-policy.md](../arm64-verification-policy.md)：原生 arm64 只要求 T14/T18/T49/T50/T51/T54/T57；其余任务为 x86_64 设备验收 + arm64 静态审计（`tools/audit_native_abi.py`） |
| 权威状态依据 | 本文件与各任务证据的最新追加章节；历史完成记录与当前权威状态分开，本次同步不删改原需求、不降低验收标准 |
| 最近已验收任务 | T17（仓储/VM 构造注入，RepositoryConstructionTest 5/5 + 6 个设备类全过 + minified release PASS，arm64 静态审计）；T16（Hilt Application + 5 入口）；T15（SDK/权限/前台服务/16KB，四台 + 原生 arm64）；T14（2026-09-27，生产构建链升级）；T13（2026-09-26，兼容探针两 ABI 通过）；T12（2026-09-26，含用户授权的已接受限制） |
| 下一任务 | T18 新版 9 项 NativeEngineSmokeTest 原生 arm64 补验；随后 T19。T18 旧 4 项真机结果不替代有效 bundle 转换验收。 |
| 并行分支合并（2026-09-30） | 已把 `fix/review-closeout` 的 R1（验证器加固，`01868b1`）与 `fix/audit-safety` 的 13 项只读审查修复（`1a27342`）cherry-pick 合入本分支（提交 `cb8df30`、`2fafebd`）。`MainViewModel.kt` 的 `installOneConverted`/`moveFilesToGame`/`recordSuccessfulInstall` 与 T15–T18 冲突已手工解决，统一采用审查修复侧的完整实现；三个继承自 T17 前基线的 JVM 测试（`DirectInstallWiringTest`、`LocalBaseReuseTest`、`MoveInstallWiringTest`）已迁移到当前构造注入签名与 `ModEngine` 接口。合并后 `:app:testDebugUnitTest` **505/0/0/0**，`compileDebugAndroidTestKotlin`、`assembleDebug` 均成功。`fix/review-closeout` 的 R2–R6（未落地的生产代码修复）与 `fix/audit-safety` 的「独立审阅、真实设备验收」仍未完成，不因本次合并升级为 DONE。两个来源 worktree 保持原状未被本次操作修改。 |
| 最后核对的代码提交 | `b1dfb6e`；本轮只补 T18 的协议校验、扫描错误、取消传播、实例一致性和有效转换测试，见 [T18.md](T18.md)。 |
| 工作区脏文件 | 开始时有 T18 的三份未提交状态文档，已纳入本轮修正；旧 `diff.txt`、`patch.py`、三份 09-23 计划与两份诊断 probe 为原有未跟踪文件，不暂存、不清理。准确状态以 `git status` 为准。 |
| 环境/输入清单 | local/refactor-artifacts/historical-0.2.16（2 APK + mapping）、rebuilt-original（3 APK + mapping + metadata）；local/refactor-inputs（catalog-alpha.json、wiki-v2-detail-2238.source.json）。冻结记录见 artifact-manifest.json 的 t03Freeze；旧数据样本与缺口见 fixture-manifest.json |
| 命令结果 | 2026-09-29：T18 引擎 JVM 20/20、Python 扫描相关 12/12、x86_64 加强版 NativeEngineSmokeTest 9/9。只构建 `:app`；未用旧的全量测试结果为新实现背书。 |
| 设备覆盖 | MuMu 127.0.0.1:16384（x86_64 / API 32）：T03 做**只读**包身份与占用测量（T03.md §6）；T04 在其上实跑 release `baseline-startup` 5 次与 6 个 androidTest 类的 35 条用例（T04.md §5）。**T05 与 T06 全程没有碰设备**（T05 的 runId 皆为 `…-unit-…`；T06 的 runId 皆为 `-unit-` / `-python-`）。**T07 在其上实跑两个设备套件**（`GameFileCommitTest` 13 条、`BackupFailureSummaryTest` 5 条，skip 全 0，三轮共跑了几十次），并且在**真实游戏目录**上做了一次端到端补充验证（T07.md §11.0：用 T07 的提交序列改写一个 30 KB `__data` —— mode/属组不变、属主变为写入者、内容哈希逐字节相同，游戏随后启动无 bundle/cache/CRC 报错且未重下载）。该设备上此时已装好真实 BD2（18 GB / 1830 个 `__data`）；**除 T07 那次已登记的单次 `__data` 写入外，T03/T04 与 2026-09-19 审计均为只读**（`ls`/`stat`/`sha256sum`）；不可用「全程只读」概括整个项目。arm64-v8a 真机仍**未覆盖**，该 ABI 的 smoke 每次运行都按白名单为空而拒绝安装（T04.md §5.2、§16.1） |

## 任务登记

| 任务 | 状态 | 证据文件 | 完成时间 | 备注 |
|---|---|---|---|---|
| T01 | DONE | [T01.md](T01.md) | 2026-09-15 | worktree + 白名单 9 文档 + 5 份保全，哈希全部核对一致 |
| T02 | DONE | [T02.md](T02.md) | 2026-09-15 | 原工具链重建成功；JVM 15/15；Python 65 通过、6 因 catalog 定位 skip，转 T04 修复 |
| T03 | DONE | [T03.md](T03.md) | 2026-09-15 | 冻结包体/旧数据/行为基线；18 步流水线全部 exit 0；整包哈希差异归因于构建环境（非 R8）；20/20 fixture 哈希复算一致。独立审查后整改 10 处缺陷（§13），基线数字未变、判据与措辞已修正 |
| T04 | DONE | [T04.md](T04.md) | 2026-09-15 | 四个真回归入口 + 独立 `-Mode LocalAll`；T02 的 6 个 skip 转为「配置到位才实跑」；35 条 androidTest 中 34 条实执行、1 条因缺夹具从未执行（如实记为未覆盖）；release `baseline-startup` 在 x86_64 上 5 次 PASS，arm64-v8a 因无真机保持未完成。独立复核（不跑 Gradle）查出两处真实缺陷并整改：断言前的失败不留档（§5.4.1）、JVM 夹具哈希空转（§5.4.2）；另更正四处文档与代码不符（§5.4.3），并整轮重放八条护栏 + LocalAll（§16）。提交 `c041617`（一轮）+ `44b9024`（二轮整改） |
| T05 | DONE | [T05.md](T05.md) | 2026-09-16 | 迁出 6 个内嵌类型到 `core/model` + `core/fileaccess`，错误分类唯一（OperationErrorKind 决定可重试性）、三类操作各自有 id/阶段/可取消性、`DirectoryListingResult` 让「成功但空」与「失败」类型不可混。27 条契约用例，二轮验证链 D→破坏→还原（BYTE_IDENTICAL）→E/F 全部 27/27、skipped 0（runId `20260916-013742` 等，见 §3.2 的 14 轮 runId 账）。**独立复核推翻了一处本会话的结论**：把 `resolveModInfos` 的静默丢弃写成「与旧行为一致」不成立（旧计划存 `List<ModInfo>` 实例，不可能丢）——这是本轮新引入的行为，已补分开归因 + `Log.w` + `toast` 用户可见提示（§9.1/§9.2）。另按复核补两处闸门盲区（父类/接口入队、静态字段不再跳过），两轮证伪实验都真实打红（§9.3），并把剩余边界（不扫方法体/顶层函数、ROOTS 手写）写进代码注释而非留白。**范围偏差**：`ui/dialogs/AppDialogs.kt` +6/−3（Kotlin 1.9 无嵌套 typealias，迁出内嵌类型必然波及类型引用），函数体零变化（§2.4）。`ModInfo.uri` 是 `android.net.Uri`，故 ModModels 只迁出计划/错误相关类型，不动 `ModInfo` 本身。一轮曾用无效破坏（`object` 带伴生对象编译不过），如实记为**不算证伪**（§8.3）。全程未用设备。提交 `d4b41ff` |
| T06 | DONE | [T06.md](T06.md) | 2026-09-17 | 归档条目路径越界封堵（Kotlin + Python 同一套拒绝面）：新建 `core/fileaccess/SafeArchivePaths.kt`（语法闸门 → 词法包含性 → `toRealPath()` 真实路径包含性），`MainViewModel.extractModInto:1571` 一行替换，`ModRepository` 只读扫描跳过不安全条目名并 `Log.w`，`unpacker.py::_unique_export_path` 改为先规范化再判包含性。27 条 Kotlin + 25 条 Python 用例，skipped 全 0。自证伪 3 次（真实路径层 27/2、反斜杠规则 27/2、Python 规范化 4 failed+3 errors），一次无效破坏（伴生对象编译不过）如实记为不算证伪。**独立复核判定「有条件 go」、真缺陷 0 条**：7 组攻击面探针直接驱动编译产物，没找到能让 `resolveInside` 接受而文件真落到 root 之外的条目名；提交前必须改 1 条（§6.1 原写「去掉 `..` 规则不会让任何用例变红」被实测推翻，27/3 两次独立复现，已更正），应改 5 条（blob 列实为 SHA-1、「两份哈希必然不同」只对改过的文件成立、§7.1 根外断言实为 4 条、§3.3 调用方写错、§7.2(b) 措辞）全部落进 §11。源码字节在复核后一字未动，`git rev-parse HEAD:` 六个 blob 与 §9.1 登记值逐一相同。未覆盖如实登记：API34+ 无设备、真实 MOD 误拒率无数据、文件型 symlink 未覆盖、androidTest 路径可行但未做。**两项既定裁定**（prep-notes §6）：Python 半边不建死模块 `safe_archive.py`，改加固真实写盘点并在 §3.4 记明计划假定的 Python 解包入口不存在；Kotlin 半边扩到 `extractModInto` 真实逃逸点。提交 `f9f335b` |
| T07 | DONE | [T07.md](T07.md) | 2026-09-17 | 游戏文件同目录暂存 + 校验 + 原子提交：新建 `AtomicFileWriter.kt`（同目录暂存 → 校验 → `ATOMIC_MOVE`）与 `BackupGate.kt`（纯函数三态裁定），`ShizukuFileService.copyStream` 从「一打开就截断目标」改成原子提交，三处备份返回值不再丢弃，并新增「未完成的备份」批次汇总（`ModModels.kt +16/0`、`AppDialogs.kt +69/0` 是为此动的 T05 文件，已登记范围偏差；`ShizukuManager.kt +2/−1` 是 `.version(5)→6`）。**三轮独立复核**（每轮独立进程）：一轮抓到 2 条真缺陷（新建权限分支零断言：复核者把常量改成 `rw-------` 仍 11/11 全绿；空源规则外溢到目录树：同树对照旧写法 `ok=true`、新写法 `ok=false`）；二轮又抓 2 条（A′ 的实测依据测错对象 —— 真实 `__data` 的父目录是 **2750 不是 2770**，旧规则会得 0640，用户据此改选「优先抄同目录已有文件的 mode」；`reportBackupFailure` 的状态行零断言 —— 改成 `Failed` 或整行删掉都不红）；三轮定点验证确认两条都真的修好（复核者亲自打红）。**实施者自查中还发现并修掉第 3 条真缺陷**：该处 `toast` 在后台线程抛 NPE，会被 `processSingleJob` 的 catch 吞成 `JobStatus.Failed` 并中止整个转换。最终态：JVM `AtomicFileWriterTest` 17/0/0/0 + `BackupGateTest` 9/0/0/0；设备 `GameFileCommitTest` 13 条 + `BackupFailureSummaryTest` 5 条（skip 全 0）；全量 JVM 99/0/0/0（含 `OperationContractTest` 27 条）；破坏/对照证伪十余次全部编译通过且真红、还原逐字节一致。`arm64-v8a` 仍 NOT_RUN。提交 `06f4384` |
| T08 | IN_PROGRESS | [T08.md](T08.md) | — | 内容补账/move/converted 接线；跨操作冲突、旧日志身份、一次性租约释放已定向复审。当前全量 JVM 404/0/0/0。真实 UnityCache 中断仍缺。 |
| T09 | DONE | [T09.md](T09.md) | 2026-09-18 | 用可信基准区分原版与同尺寸文件：新建 `core/storage/PristineVerifier.kt`（纯 JVM、摘要可注入、流式）+ 备份 sidecar 来源元数据，**删掉「与备份大小一致即 PRISTINE」的循环自证**，换成「CDN 输入锚定 + 确定性转换 + byteAnchoredBaseline」的派生锚定链。**两条实证**：① `m_Hash` **不是** `__data` 字节摘要（63 个真实样本命中 0；Addressables 源码确认是构建期 Hash128/SpookyHash V2 内容版本号；`m_Crc` 98% 为 0）；② **CDN `.bundle` 与 `__data` 逐字节相同**（三轮独立抽样 5/5、7/7、5/5）→ 这才让 CDN 成为字节级外部锚点。**独立复核判定 `no-go`、3 条真缺陷**：①（P0）`BackupMeta` 在 release 下被 R8 改名、sidecar 读不回来；② T09 改签名打坏 T07 已交付的设备用例；③ `tryUseBackup` 用错判据、同尺寸 mod 备份可当干净基底复用。二轮整改后**定点验证者亲自重建 release dex + 三条注入全部真红**，判定 `go`。最终态：JVM `28/14`、设备 `5+2`、全量 JVM **197**。未覆盖：两处调用点仅人工核对、真机 release 运行态 NOT_RUN（本机签不出）、真机 Chaquopy 确定性未验、一条阴性证伪（流式摘要断言证明不了）。提交 `6529e81` |
| T10 | IMPLEMENTED_NOT_VERIFIED | [T10.md](T10.md) | — | 当前 minified x86_64 Store 5 条往返 PASS；主界面 chrome PASS，明确 uiColdStartVerified=false。legacy 源目录不匹配未升格；完整业务列表恢复及 arm64 未验收。 |
| T11 | LOCAL_VERIFIED | [T11.md](T11.md) | 2026-09-18 | 建立版本目录与独立的 App 发布版本配置：新建 `gradle/libs.versions.toml`（versions/libraries/plugins 三段，alias 命名成体系）与 `gradle/version.properties`（`appVersionName`/`appVersionCode`），根工程 / `:app` / `:release-probe` 改用 `libs.*` 引用（release-probe 的 5 条字面版本一并迁入）。**主判据**：依赖解析坐标前后**逐字节一致**（94 条，两份清单 sha256 均为 `1590732d…`；499 行依赖树里 300 条顺序一致、177 条 `(c)` 约束行多重集相同）；**次判据**：`aapt2 dump badging` 读出 `versionCode='17' versionName='0.2.16'` 未变。**一轮曾声称「`classes.dex` 逐字节不变」被实测推翻**：全量构建两侧各自确定（迁移前 44,544,060/`36b957ce…`、迁移后 44,544,064/`963ebf88…`，各两次全同），差异是 1/122,700 条字符串（协程调试元数据 blob，内里 50/17,891 条目变化、全部 AndroidX `$$ExternalSyntheticLambda`，+4 B，字节码全同、该 blob 零指令引用）；根因已隔离到「构建配置输入字节的函数」、机制未定位。另如实登记 `classes4/7/8.dex` 同配置三次构建三次不同（`class_data` 逐类 0 差异）。证伪：`appVersionCode 17→18` → APK 真的读出 18；`gson` 改版 → 坐标 94→93 条（复核者另换 `documentfile` 独立复现）。全量 JVM **222**。未覆盖：`assembleRelease` 未跑、blob 算法未定位、alias 独立探针未跑成（已有 accessor 直接证据）。提交 `53959b3`。**当前状态更正（2026-09-19）：本地验收完成** — 受控 `assembleRelease` 通过（arm64/x86_64/universal 三份 APK、`apksigner verify` 全 exit 0、证书与 T02 冻结基线一致、身份 0.2.16/17、`releaseRuntimeClasspath` 94 条依赖与迁移前相同）；仅本地基线签名受控验证，非正式发布/设备 smoke（[T11.md](T11.md) §12） |
| T12 | DONE | [T12.md](T12.md)、[native 来源](../native-texture-decoder.md)、[PythonRuntime ABI 证据](../python-runtime-abi-selection.md) | 2026-09-26 | 生产 PythonRuntime 入口在 x86_64 split/universal minified Release 与**原生 arm64 真机**（2026-09-26，Xiaomi 2509FPN0BC / API 36，隔离包名 `com.bd2toolsbox.verify`）均 PASS：Python 3.8.18、依赖 import、20 个 decoder 导出与 BC1 smoke。astcenc 原始构建 commit、`.tpk` 生成输入、FMOD 编译 commit 经用户 2026-09-26 授权放宽为已接受的 UNKNOWN（分发字节已钉住；FMOD 授权留待 T37/T38）。 |
| T13 | DONE | [T13.md](T13.md)、[兼容矩阵](../compatibility-matrix.md) | 2026-09-26 | 探针 `tools/compat-probe`（AGP 8.11.1 / Gradle 8.13 / Kotlin 2.2.20 / KSP 2.2.20-2.0.3 / Hilt 2.58 / Chaquopy 16.1.0 / BOM 2025.08.00 / AboutLibraries 13.1.0 / serialization 1.9.0 / compileSdk 36）。x86_64 AVD debug 5/5、minified release selfCheck 5/5，清理重建复现。原生 arm64 真机 debug/minified release selfCheck 5/5（instrumentation Activity 用例被 MIUI 拦截，已登记）。Dagger KSP 官方 stable 门槛（2.60+/KSP 2.3.9+）不满足，已登记。 |
| T14 | DONE | [T14.md](T14.md) | 2026-09-27 | AGP 8.11.1 / Gradle 8.13 / Kotlin 2.2.20 + `kotlin.plugin.compose` / Chaquopy 16.1.0 / BOM 2025.08.00 / core-ktx 1.17.0 / compileSdk 36；targetSdk 34、minSdk 29、字节码 11 不变。M3 1.3 下拉刷新 API 迁移（ModScreen/GuideScreen）。**发现并修复真实回归**：Chaquopy 16 把 bootstrap 扩展改名为 `*.cpython-38.so`，`PythonRuntime` 预检 `zlib.so` 会让 Python 起不来（构建/单测/宿主回归都发现不了）。JVM 429/0/0/0、release-probe 6/0/0/0、Python 122/122；静态审计两 ABI PASS、证伪 8/8。x86_64 AVD 与原生 arm64 真机（隔离包名）Runtime/Dependency probe 均 OK，Python 3.8.20，pip 产物逐字节不变。未覆盖：有目录时下拉重扫、攻略页下拉。release 为受控 debug signer，非正式发布。 |
| T15 | DONE | [T15.md](T15.md) | 2026-09-27 | minSdk 29 / compileSdk 36 / targetSdk 34（用户裁定保持）。前台服务被拒时三服务 `start()` 如实返回 false，装入批次全部 Failed 且未动文件/账本；SAF 授权失效改为弹窗告知；删除两个未用存储权限；`libtexture2ddecoder.so` 重编为 16 KB 对齐（两次构建逐字节相同），manifest `pageSizeCompat="enabled"` 消除 Android 16 启动警告（反证：disabled 时 lz4/brotli/UnityPy dlopen 失败）。`PlatformBehaviorTest` 13 条：MuMu API 32（`Test-Bd2Device`）与 AVD API 29/34/36-16KB 全过，三处证伪真红。JVM 435/0/0/0、Python 122/122、release probe 三台 AVD OK。**arm64 native 被替换 → 升格 A 类**：原生 arm64 真机（Xiaomi 2509FPN0BC / API 36，隔离包名）Runtime+Dependency probe PASS，无崩溃/对齐错误，用户正式包前后未变。 |
| T16 | DONE | [T16.md](T16.md) | 2026-09-27 | 引入 Hilt 2.58 / KSP 2.2.20-2.0.3 / androidx.hilt 1.3.0（T13 组合）：`@HiltAndroidApp Bd2Application`（不抢先启动 Python/不阻塞首屏）、`MainActivity`/`SpinePreviewActivity`/三个本进程 Service 加 `@AndroidEntryPoint`；`ShizukuFileService` 刻意不接（外部 shell 进程 Binder Stub）。compiler 走 KSP 无 kapt，R8 无需手写 Hilt keep。HiltEntryPointTest 4/4（AVD API34）、JVM 429、minified release 冷启动+release-probe PASS；arm64 静态审计 PASS（B 类，dex 层，未在原生 arm64 运行）。 |
| T17 | DONE | [T17.md](T17.md) | 2026-09-27 | 有状态仓储（Character/Mod/InstalledMod/BundleBackup/PreviewCache/Gamekee）改 Hilt `@Singleton`，MainViewModel/GuideViewModel 改 `@HiltViewModel` 构造注入（删 lateinit+initRepositories，`initialize()` 去 context 化），新增 DispatcherModule/RuntimeModule/RepositoryModule。账本单写不变量保持（`LedgerSubmissionCoordinator` 进程锁 + @Singleton 消分叉），Activity Context 不进长寿命对象。RepositoryConstructionTest 5/5（纯 JVM）、全量 JVM 434、6 个涉及 MainViewModel 的设备类全过（Hilt/Platform/Snapshot/BackupFailure/LedgerRecovery/TryUseBackup）、minified release 冷启动+release-probe PASS；arm64 静态审计 PASS（B 类，dex 层，未在原生 arm64 运行）。 |
| T18 | IMPLEMENTED_NOT_VERIFIED | [T18.md](T18.md) | — | 2026-09-29 撤回过早的 DONE：旧真机 4 项只证明 BC1 和无效输入调用，没有有效 bundle 转换。补齐元组/字段/JSON 校验，扫描失败不再返回空名单，修复 Python 吞取消与可替换默认实例问题。引擎 JVM 20/20、扫描 Python 12/12、x86_64 真转换/逐字节导出/取消/BC1 9/9。A 类：本轮新版 9 项仍需原生 arm64 验证。 |
| T19–T62 | NOT_STARTED | （未创建） | — | |


### 2026-09-20 最新增量（更新上表，历史结果不外推到新源树）

- T08：晚间同一暂存源续批 JVM **343/0/0/0**，Debug 三包构建通过、未安装，83 个源码文件与已测指纹一致。Windows 默认 NIO identity 未知时拒绝，测试使用真实 File ID；Android 接线与实际目标原子补账恢复仍未验收。前批 snapshot harness 16 项、Python 96 项为历史通过，本批未改亦未重跑两者。
- T10：上午构建的混淆 Release（不是晚间新源树）在 x86_64 的 Store 跨进程 5 条快照往返、cleanup、主界面启动、身份检查 PASS；不是完整 UI 列表冷启动，arm64 仍未运行，保持 IMPLEMENTED_NOT_VERIFIED。
- T12：同一上午 Release 的 Python 3.8.18/9 直接依赖/生产桥接 import PASS；独立 native decoder 检查 FAIL（_lib=None）。来源审计新增固定官方包及本地上游字节证据，保持 IN_PROGRESS。
- 详细命令、失败记录和产物哈希见 T08.md、T10.md、T12.md 的 2026-09-20 段；未提交，不宣称正式发布。

### 2026-09-21 最新增量

- T08：JournalMoveFacts、converted Staging/Submitter 接线、StrictDirectoryCopy、SAF 子目录保留 DocumentFile。全量 JVM **398/0/0/0**。设备 16385 只读观察 PASS。真实 UnityCache 中断仍缺。
- T10：minified snapshot-compatibility PASS（uiColdStartVerified=true，mapping keep PASS）。debug 设备快照套件 PASS。filesDir leftover 因 sourceDirs 不匹配未升格。arm64 未跑。
- T12：x86_64 native `libtexture2ddecoder.so` 设备 PASS。arm64 未跑。不推进 T13。

### 2026-09-21 接续验收更正（以上旧结果保留，不替代本次证据）

- 两个前执行窗口因额度错误停止后接管；保留未提交代码与原始红/绿日志。T08 三项恢复缺陷已获官方 Antigravity 定向复审；当前全量 JVM **404/0/0/0**。
- T12 当前 minified x86_64：Python 3.8.18 导入及真实 BC1 解码 **各 1 项通过**；20 个导出齐全，7 项受保护文件字节不变。宿主 Python **104 项通过**。新包 SHA-256 `ae91183912f83f52355b65bb601f0ff40309cb4339a0cf490866c73e19208562` 与设备 base.apk 相同。
- T10 当前 Store 跨进程 5 条 PASS，prepare/restore PID 14417/14457；**uiChromeColdStartVerified=true、uiColdStartVerified=false**。旧 true 只代表界面启动，不能作为完整业务列表恢复证据。
- Host harness：snapshot **16**、UI chrome **14** 项通过；修正后者缺 BOM 导致 Windows PowerShell 5.1 中文 XML 夹具损坏的问题，不改断言。补充 native 来源及纳管证据见 [T12-native-contract-20260921.md](T12-native-contract-20260921.md)。
- 本轮原始结果在 `local/pi-resume/20260921-release-verification/` 及 `20260921-112340-smoke-snapshot-compatibility-x86_64`、`20260921-112746-python-test__.py`。未清应用数据、未写游戏目录、未提交、未发布。三个任务均未勾完成。

### 2026-09-22 授权验收（不覆盖上一段，也不勾 arm64）

- T08：x86_64 单目标 converted `200114` 与 move `200548` 均为 PASS，且都已回滚到原游戏文件。更早两次 converted 失败保留。主机门禁脚本 18 项通过，不能代替设备证据。
- T10：x86_64 真实 minified 列表「扫描→保存→冷启动→快照恢复」通过，并有移走快照后为空的对照。只证明两条未解析 PC 夹具的列表持久化。arm64 未跑。
- T12：没有新的 arm64 证据，保持 IN_PROGRESS，不推进 T13。
