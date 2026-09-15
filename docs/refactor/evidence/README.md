# 重构证据索引（docs/refactor/evidence/）

> 维护规则（2026-09-15 TODO §0.3）：每个任务完成后在此登记状态。恢复/续跑时先核对下表事实，再继续；不能仅凭聊天摘要或已打勾标题认定代码仍已验证。
>
> 状态取值：NOT_STARTED、IN_PROGRESS、IMPLEMENTED_NOT_VERIFIED、BLOCKED、DONE。只有 DONE 可勾选任务完成。

## 当前快照

| 项 | 值 |
|---|---|
| 阶段 | **G1 安全护栏进行中**（T05 已完成 —— 领域结果/文件错误/计划模型与 27 条契约用例；G1 还剩 T06–T10） |
| 最近已验收任务 | T05（见下方登记表） |
| 下一任务 | T06 — 封堵 Kotlin 与 Python 的归档路径越界（前置 T05 已满足） |
| 最后核对的代码提交 | `d4b41ff`（T05 提交）。T05.md §2.1/§2.2 的哈希行逐条复算一致：工作区 sha256 + `git rev-parse :<path>` 两套值都登记在 §8.1，因为 `core.autocrlf=true` 且无 `.gitattributes`，改动过的文件工作区字节（CRLF）与 blob（LF）不同。T04 为 `44b9024`（二轮整改，一轮 `c041617`）；T03 为 `954e57b` |
| 工作区脏文件 | T05 提交后**只剩本次待提交的清单进度**：`docs/superpowers/plans/2026-09-15-android-refactor-todo.md`（T05 复选框 + §0.1）；外加持久的被忽略项 `/local/`、`environment.local.json`、`local.properties` 与各类 `build/`、`__pycache__/`。T05 自证所用的两份原文件备份在 `local/scratch/t05-*.orig`、`local/scratch/t05b-*.orig`，逐字节 `cmp` 一致（T05.md §8.3） |
| 环境/输入清单 | local/refactor-artifacts/historical-0.2.16（2 APK + mapping）、rebuilt-original（3 APK + mapping + metadata）；local/refactor-inputs（catalog-alpha.json、wiki-v2-detail-2238.source.json）。冻结记录见 artifact-manifest.json 的 t03Freeze；旧数据样本与缺口见 fixture-manifest.json |
| 命令结果 | 见 T01.md、T02.md、T03.md、T04.md、T05.md |
| 设备覆盖 | MuMu 127.0.0.1:16384（x86_64 / API 32）：T03 做**只读**包身份与占用测量（T03.md §6）；T04 在其上实跑 release `baseline-startup` 5 次与 6 个 androidTest 类的 35 条用例（T04.md §5）。**T05 全程没有碰设备**（runId 皆为 `…-unit-…`，只跑 JVM 单测），因此本项不增加设备覆盖结论。arm64-v8a 真机仍**未覆盖**，该 ABI 的 smoke 每次运行都按白名单为空而拒绝安装（T04.md §5.2、§16.1） |

## 任务登记

| 任务 | 状态 | 证据文件 | 完成时间 | 备注 |
|---|---|---|---|---|
| T01 | DONE | [T01.md](T01.md) | 2026-09-15 | worktree + 白名单 9 文档 + 5 份保全，哈希全部核对一致 |
| T02 | DONE | [T02.md](T02.md) | 2026-09-15 | 原工具链重建成功；JVM 15/15；Python 65 通过、6 因 catalog 定位 skip，转 T04 修复 |
| T03 | DONE | [T03.md](T03.md) | 2026-09-15 | 冻结包体/旧数据/行为基线；18 步流水线全部 exit 0；整包哈希差异归因于构建环境（非 R8）；20/20 fixture 哈希复算一致。独立审查后整改 10 处缺陷（§13），基线数字未变、判据与措辞已修正 |
| T04 | DONE | [T04.md](T04.md) | 2026-09-15 | 四个真回归入口 + 独立 `-Mode LocalAll`；T02 的 6 个 skip 转为「配置到位才实跑」；35 条 androidTest 中 34 条实执行、1 条因缺夹具从未执行（如实记为未覆盖）；release `baseline-startup` 在 x86_64 上 5 次 PASS，arm64-v8a 因无真机保持未完成。独立复核（不跑 Gradle）查出两处真实缺陷并整改：断言前的失败不留档（§5.4.1）、JVM 夹具哈希空转（§5.4.2）；另更正四处文档与代码不符（§5.4.3），并整轮重放八条护栏 + LocalAll（§16）。提交 `c041617`（一轮）+ `44b9024`（二轮整改） |
| T05 | DONE | [T05.md](T05.md) | 2026-09-16 | 迁出 6 个内嵌类型到 `core/model` + `core/fileaccess`，错误分类唯一（OperationErrorKind 决定可重试性）、三类操作各自有 id/阶段/可取消性、`DirectoryListingResult` 让「成功但空」与「失败」类型不可混。27 条契约用例，二轮验证链 D→破坏→还原（BYTE_IDENTICAL）→E/F 全部 27/27、skipped 0（runId `20260916-013742` 等，见 §3.2 的 14 轮 runId 账）。**独立复核推翻了一处本会话的结论**：把 `resolveModInfos` 的静默丢弃写成「与旧行为一致」不成立（旧计划存 `List<ModInfo>` 实例，不可能丢）——这是本轮新引入的行为，已补分开归因 + `Log.w` + `toast` 用户可见提示（§9.1/§9.2）。另按复核补两处闸门盲区（父类/接口入队、静态字段不再跳过），两轮证伪实验都真实打红（§9.3），并把剩余边界（不扫方法体/顶层函数、ROOTS 手写）写进代码注释而非留白。**范围偏差**：`ui/dialogs/AppDialogs.kt` +6/−3（Kotlin 1.9 无嵌套 typealias，迁出内嵌类型必然波及类型引用），函数体零变化（§2.4）。`ModInfo.uri` 是 `android.net.Uri`，故 ModModels 只迁出计划/错误相关类型，不动 `ModInfo` 本身。一轮曾用无效破坏（`object` 带伴生对象编译不过），如实记为**不算证伪**（§8.3）。全程未用设备。提交 `d4b41ff` |
| T06 | IN_PROGRESS | （未创建） | — | 前置 T05 已满足。两项既定裁定见 `.superpowers/sdd/2026-09-15-android-refactor-todo/task-T05-T07-prep-notes.md` §6：Python 侧不新建死代码 `safe_archive.py`，改为加固 `unpacker.py::_unique_export_path` 并在 T06.md 记明计划假定的「Python 归档解包入口」不存在；Kotlin 侧扩到真实逃逸点 `MainViewModel.kt:1565` `File(dest, entry.name)` |
| T07–T62 | NOT_STARTED | （未创建） | — | 按 2026-09-15 TODO 顺序推进（G1 内 T06→T07→T08→T09→T10 全部改 `MainViewModel.kt` 与/或 `core/`，必须串行，且 T06/T07 不合并提交） |
