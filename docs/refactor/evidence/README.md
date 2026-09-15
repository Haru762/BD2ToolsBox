# 重构证据索引（docs/refactor/evidence/）

> 维护规则（2026-09-15 TODO §0.3）：每个任务完成后在此登记状态。恢复/续跑时先核对下表事实，再继续；不能仅凭聊天摘要或已打勾标题认定代码仍已验证。
>
> 状态取值：NOT_STARTED、IN_PROGRESS、IMPLEMENTED_NOT_VERIFIED、BLOCKED、DONE。只有 DONE 可勾选任务完成。

## 当前快照

| 项 | 值 |
|---|---|
| 阶段 | G0 |
| 最近已验收任务 | T03（见下方登记表） |
| 下一任务 | T04 — 建立真实回归入口并替换无效集成测试 |
| 最后核对的代码提交 | 03805d1be561112d3c0bf0914d6be1280dfffeab（T02 修正提交，即 T03 取证所依据的 `classes.dex`/mapping 基线）；T03 已提交为 `954e57b`，其审查整改见 T03.md §13 |
| 工作区脏文件 | T03 提交后应仅剩被忽略的 /local/ 与 environment.local.json；清单见 T03.md §2 与各任务证据文件 |
| 环境/输入清单 | local/refactor-artifacts/historical-0.2.16（2 APK + mapping）、rebuilt-original（3 APK + mapping + metadata）；local/refactor-inputs（catalog-alpha.json、wiki-v2-detail-2238.source.json）。冻结记录见 artifact-manifest.json 的 t03Freeze；旧数据样本与缺口见 fixture-manifest.json |
| 命令结果 | 见 T01.md、T02.md、T03.md |
| 设备覆盖 | MuMu 127.0.0.1:16384（x86_64 / API 32）已做**只读**包身份与占用测量（见 T03.md §6）；运行内存 NOT_MEASURED。arm64-v8a 真机、安装/启动/升级等行为验收仍**未覆盖**，白名单为空 |

## 任务登记

| 任务 | 状态 | 证据文件 | 完成时间 | 备注 |
|---|---|---|---|---|
| T01 | DONE | [T01.md](T01.md) | 2026-09-15 | worktree + 白名单 9 文档 + 5 份保全，哈希全部核对一致 |
| T02 | DONE | [T02.md](T02.md) | 2026-09-15 | 原工具链重建成功；JVM 15/15；Python 65 通过、6 因 catalog 定位 skip，转 T04 修复 |
| T03 | DONE | [T03.md](T03.md) | 2026-09-15 | 冻结包体/旧数据/行为基线；18 步流水线全部 exit 0；整包哈希差异归因于构建环境（非 R8）；20/20 fixture 哈希复算一致。独立审查后整改 10 处缺陷（§13），基线数字未变、判据与措辞已修正 |
| T04 | NOT_STARTED | T04.md | — | 真实回归入口 + 替换无效集成测试；须消化 T02 的 6 个 skip 与 verification.ps1 工作目录偏差 |
| T05–T62 | NOT_STARTED | （未创建） | — | 按 2026-09-15 TODO 顺序推进 |
