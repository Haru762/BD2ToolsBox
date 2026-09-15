# 重构证据索引（docs/refactor/evidence/）

> 维护规则（2026-09-15 TODO §0.3）：每个任务完成后在此登记状态。恢复/续跑时先核对下表事实，再继续；不能仅凭聊天摘要或已打勾标题认定代码仍已验证。
>
> 状态取值：NOT_STARTED、IN_PROGRESS、IMPLEMENTED_NOT_VERIFIED、BLOCKED、DONE。只有 DONE 可勾选任务完成。

## 当前快照

| 项 | 值 |
|---|---|
| 阶段 | G0 |
| 最近已验收任务 | T02（见下方登记表） |
| 下一任务 | T03 — 冻结功能、旧数据和真实 release 包体基线 |
| 最后核对的代码提交 | db4b7aaa2560f50ffa083ac0c12ee4efec85c839（T01）；T02 的证据与命令结果见对应提交 |
| 工作区脏文件 | 见各任务证据文件的记录；T02 提交后应仅剩被忽略的 /local/ 与 environment.local.json |
| 环境/输入清单 | local/refactor-artifacts/historical-0.2.16（2 APK + mapping）、rebuilt-original（3 APK + mapping + metadata）；local/refactor-inputs（catalog-alpha.json、wiki-v2-detail-2238.source.json） |
| 命令结果 | 见 T01.md、T02.md |
| 设备覆盖 | adb 无已连接设备；白名单为空，设备验收保持未覆盖 |

## 任务登记

| 任务 | 状态 | 证据文件 | 完成时间 | 备注 |
|---|---|---|---|---|
| T01 | DONE | [T01.md](T01.md) | 2026-09-15 | worktree + 白名单 9 文档 + 5 份保全，哈希全部核对一致 |
| T02 | DONE | [T02.md](T02.md) | 2026-09-15 | 原工具链重建成功；JVM 15/15；Python 65 通过、6 因 catalog 定位 skip，转 T04 修复 |
| T03 | NOT_STARTED | T03.md | — | — |
| T04 | NOT_STARTED | T04.md | — | — |
| T05–T62 | NOT_STARTED | （未创建） | — | 按 2026-09-15 TODO 顺序推进 |
