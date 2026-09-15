# 重构证据索引（docs/refactor/evidence/）

> 维护规则（2026-09-15 TODO §0.3）：每个任务完成后在此登记状态。恢复/续跑时先核对下表事实，再继续；不能仅凭聊天摘要或已打勾标题认定代码仍已验证。
>
> 状态取值：NOT_STARTED、IN_PROGRESS、IMPLEMENTED_NOT_VERIFIED、BLOCKED、DONE。只有 DONE 可勾选任务完成。

## 当前快照

| 项 | 值 |
|---|---|
| 阶段 | **G0 基线已完成**（T01–T04 四项全部 DONE）；下一阶段 G1 文件安全与数据护栏 |
| 最近已验收任务 | T04（见下方登记表） |
| 下一任务 | T05 — 建立稳定的领域结果、文件错误与计划模型 |
| 最后核对的代码提交 | `44b9024`（T04 二轮整改提交；一轮为 `c041617`，即 T04.md 取证所依据的基线。T04.md §5.4 的两处缺陷由独立复核查出，整改后 §16 整轮重放了八条护栏与 LocalAll）；T03 为 `954e57b`，其审查整改见 T03.md §13 |
| 工作区脏文件 | T04 提交后，只有 **T05 的进行中改动**未提交（本轮 T05 复核期间如实登记，不代表已验收）：`app/src/main/java/com/bd2toolsbox/{data/model/ModModels.kt, ui/dialogs/AppDialogs.kt, ui/viewmodel/MainViewModel.kt}`、未跟踪的 `app/src/main/java/com/bd2toolsbox/core/`、`app/src/test/java/com/bd2toolsbox/core/`、`docs/refactor/evidence/T05.md`；外加持久的被忽略项 `/local/` 与 `environment.local.json` |
| 环境/输入清单 | local/refactor-artifacts/historical-0.2.16（2 APK + mapping）、rebuilt-original（3 APK + mapping + metadata）；local/refactor-inputs（catalog-alpha.json、wiki-v2-detail-2238.source.json）。冻结记录见 artifact-manifest.json 的 t03Freeze；旧数据样本与缺口见 fixture-manifest.json |
| 命令结果 | 见 T01.md、T02.md、T03.md、T04.md |
| 设备覆盖 | MuMu 127.0.0.1:16384（x86_64 / API 32）：T03 做**只读**包身份与占用测量（T03.md §6）；T04 在其上实跑 release `baseline-startup` 5 次与 6 个 androidTest 类的 35 条用例（T04.md §5）。arm64-v8a 真机仍**未覆盖**，该 ABI 的 smoke 每次运行都按白名单为空而拒绝安装（T04.md §5.2、§16.1） |

## 任务登记

| 任务 | 状态 | 证据文件 | 完成时间 | 备注 |
|---|---|---|---|---|
| T01 | DONE | [T01.md](T01.md) | 2026-09-15 | worktree + 白名单 9 文档 + 5 份保全，哈希全部核对一致 |
| T02 | DONE | [T02.md](T02.md) | 2026-09-15 | 原工具链重建成功；JVM 15/15；Python 65 通过、6 因 catalog 定位 skip，转 T04 修复 |
| T03 | DONE | [T03.md](T03.md) | 2026-09-15 | 冻结包体/旧数据/行为基线；18 步流水线全部 exit 0；整包哈希差异归因于构建环境（非 R8）；20/20 fixture 哈希复算一致。独立审查后整改 10 处缺陷（§13），基线数字未变、判据与措辞已修正 |
| T04 | DONE | [T04.md](T04.md) | 2026-09-15 | 四个真回归入口 + 独立 `-Mode LocalAll`；T02 的 6 个 skip 转为「配置到位才实跑」；35 条 androidTest 中 34 条实执行、1 条因缺夹具从未执行（如实记为未覆盖）；release `baseline-startup` 在 x86_64 上 5 次 PASS，arm64-v8a 因无真机保持未完成。独立复核（不跑 Gradle）查出两处真实缺陷并整改：断言前的失败不留档（§5.4.1）、JVM 夹具哈希空转（§5.4.2）；另更正四处文档与代码不符（§5.4.3），并整轮重放八条护栏 + LocalAll（§16）。提交 `c041617`（一轮）+ `44b9024`（二轮整改） |
| T05 | IN_PROGRESS | T05.md | — | 领域结果/文件错误/计划模型已实现并自证（27/27）；独立复核进行中，DONE 由复核结论决定 |
| T06–T62 | NOT_STARTED | （未创建） | — | 按 2026-09-15 TODO 顺序推进（G1 起 T06→T07→T08→T09→T10 全部改 `MainViewModel.kt` 与/或 `core/`，必须串行） |
