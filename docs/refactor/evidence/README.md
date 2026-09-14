# 重构证据索引（docs/refactor/evidence/）

> 维护规则（2026-09-15 TODO §0.3）：每个任务完成后在此登记状态。恢复/续跑时先核对下表事实，再继续；不能仅凭聊天摘要或已打勾标题认定代码仍已验证。
>
> 状态取值：NOT_STARTED、IN_PROGRESS、IMPLEMENTED_NOT_VERIFIED、BLOCKED、DONE。只有 DONE 可勾选任务完成。

## 当前快照

| 项 | 值 |
|---|---|
| 阶段 | G0 |
| 最近已验收任务 | T01（见下方登记表） |
| 下一任务 | T02 — 建立命令引导、核实环境并重建原版 |
| 最后核对的代码提交 | b83c1c076e95942b317649273fba559d9d3af5ca（worktree 基点，尚未产生新提交时以此为准） |
| 工作区脏文件 | 见各任务证据文件的记录；T01 提交后应仅剩被忽略的 /local/ 内容 |
| 环境/输入清单 | local/refactor-artifacts/historical-0.2.16（2 APK + mapping）；local/refactor-inputs（catalog-alpha.json、wiki-v2-detail-2238.source.json）；均已被 .gitignore 忽略且哈希核对通过 |
| 命令结果 | 见 T01.md |
| 设备覆盖 | 尚未核对（T02 建立验收设备清单） |

## 任务登记

| 任务 | 状态 | 证据文件 | 完成时间 | 备注 |
|---|---|---|---|---|
| T01 | DONE | [T01.md](T01.md) | 2026-09-15 | worktree + 白名单 9 文档 + 5 份保全，哈希全部核对一致 |
| T02 | NOT_STARTED | T02.md | — | — |
| T03 | NOT_STARTED | T03.md | — | — |
| T04 | NOT_STARTED | T04.md | — | — |
| T05–T62 | NOT_STARTED | （未创建） | — | 按 2026-09-15 TODO 顺序推进 |
