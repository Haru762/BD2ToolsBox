# 重构证据索引（docs/refactor/evidence/）

> 维护规则（2026-09-15 TODO §0.3）：每个任务完成后在此登记状态。恢复/续跑时先核对下表事实，再继续；不能仅凭聊天摘要或已打勾标题认定代码仍已验证。
>
> 状态取值：NOT_STARTED、IN_PROGRESS、IMPLEMENTED_NOT_VERIFIED、BLOCKED、DONE。只有 DONE 可勾选任务完成。

## 当前快照

| 项 | 值 |
|---|---|
| 阶段 | **G1 安全护栏进行中**（T05–T07 已完成 —— 领域结果/文件错误/计划模型 + 归档路径越界封堵 + 游戏文件原子提交；G1 还剩 T08–T10） |
| 最近已验收任务 | T07（见下方登记表） |
| 下一任务 | T08 — 安装账本原子落盘并记录可恢复的提交事实（前置 T05 已满足） |
| 最后核对的代码提交 | `06f4384`（T07 提交）。T07.md §9.1 的十一个交付源码逐条复算一致（工作区 sha256 与 `git hash-object` 两套值；新建文件工作区就是 LF，两列差异仅是算法不同；改过的文件才是 CRLF/LF 差异，因 `core.autocrlf=true` 且无 `.gitattributes`）。T06 为 `f9f335b`、T05 为 `d4b41ff`、T04 为 `44b9024`、T03 为 `954e57b` |
| 工作区脏文件 | T07 提交后**只剩本次待提交的清单/索引进度**：`docs/superpowers/plans/2026-09-15-android-refactor-todo.md`（T07 复选框 + §0.1）与本文件；外加持久的被忽略项 `/local/`、`environment.local.json`、`local.properties` 与各类 `build/`、`__pycache__/`。T07 三轮整改与复核的还原源/探针在 `local/scratch/t07*.orig`、`local/scratch/t07r2-*`、`local/scratch/t07r3-*`、`local/scratch/t07v3-*`（被忽略，不提交） |
| 环境/输入清单 | local/refactor-artifacts/historical-0.2.16（2 APK + mapping）、rebuilt-original（3 APK + mapping + metadata）；local/refactor-inputs（catalog-alpha.json、wiki-v2-detail-2238.source.json）。冻结记录见 artifact-manifest.json 的 t03Freeze；旧数据样本与缺口见 fixture-manifest.json |
| 命令结果 | 见 T01.md、T02.md、T03.md、T04.md、T05.md |
| 设备覆盖 | MuMu 127.0.0.1:16384（x86_64 / API 32）：T03 做**只读**包身份与占用测量（T03.md §6）；T04 在其上实跑 release `baseline-startup` 5 次与 6 个 androidTest 类的 35 条用例（T04.md §5）。**T05 与 T06 全程没有碰设备**（T05 的 runId 皆为 `…-unit-…`；T06 的 runId 皆为 `-unit-` / `-python-`）。**T07 在其上实跑两个设备套件**（`GameFileCommitTest` 13 条、`BackupFailureSummaryTest` 5 条，skip 全 0，三轮共跑了几十次），并且在**真实游戏目录**上做了一次端到端补充验证（T07.md §11.0：用 T07 的提交序列改写一个 30 KB `__data` —— mode/属组不变、属主变为写入者、内容哈希逐字节相同，游戏随后启动无 bundle/cache/CRC 报错且未重下载）。该设备上此时已装好真实 BD2（18 GB / 1830 个 `__data`），**全程只读**（`ls`/`stat`/`sha256sum`），一处写入都没有。arm64-v8a 真机仍**未覆盖**，该 ABI 的 smoke 每次运行都按白名单为空而拒绝安装（T04.md §5.2、§16.1） |

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
| T08–T62 | NOT_STARTED | （未创建） | — | 按 2026-09-15 TODO 顺序推进（G1 内 T08→T09→T10 全部改 `MainViewModel.kt` 与/或 `core/`，必须串行，且不得与前一个任务合并提交）。T08–T15 的实施前简报已全部写好（`task-T0x-implementer-brief.md`，gitignored） |
