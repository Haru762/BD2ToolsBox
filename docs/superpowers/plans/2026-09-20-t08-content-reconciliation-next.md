# T08 后续：内容核对后补账，而不是重新安装

> 状态：**IN_PROGRESS（未完成）**。A/B/C 已在源码与 JVM/设备只读用例落地（投影、租约补账、Shizuku 只读观察、「核对并修复记录」、move SHA / 写前意图）。**D 未做**：真实覆盖安装中断与杀进程补账需要游戏目录写入授权，本轮未授权。不勾选 T08 完成，不推进 T13。

## 当前已查证缺口

- 新 converted 提交将有 SHA + 全部 ledgerUpserts，但 checkRecovery 仍只返回 pending，不读游戏内容、不补账。
- OperationJournalStore.recoveryVerdict 旧 TARGET_REPLACED 无写前意图分支仍按 URI/bundle 与旧账本匹配就放行。旧记录没有本次内容证据，这不是完成恢复的证明。
- TargetContentAudit 目前是纯判定器，无 I/O 和解除门禁能力。AllMatched 不能直接清 journal。
- 同进程共享所有者已存在；正常提交 owner 可以写 ledger，因此恢复必须区分恢复租约，防止同一 Repository 实例的其它 UI 写入绕过恢复。

## 不变式

1. 恢复不调用安装、转换、move 或删除游戏文件；只读观察目标。不得以再次覆盖来制造匹配。
2. facts 不完整、legacy 无 facts、目标缺失/读失败/摘要不同、重复/冲突键、账本损坏、日志不可读：保留原件和 pending/unknown，不猜字段、不造空表。
3. 基于 journal 的文件顺序关联 operationId，不用时钟排序。不能用其它 op 的 commit marker 关闭本 op，也不能遗漏更早未闭合目标。
4. 上层不能直接给任意 upserts 作为恢复输入；它们必须来自本次租约捕获并再次验证的磁盘日志。
5. 全部未闭合目标内容匹配后，一次原子 ledger RMW 合并完整 upserts，保留无关记录；随后持久化相应 commit marker，再清日志。任何失败都返回真实阶段，保留可重复核对的证据，不重新写游戏。
6. ledger 已补但 marker 失败：再次核对同一内容并幂等补相同完整记录；不能仅因 URI 已存在就跳过证据。
7. 只承诺当前已实现的同进程所有权，不冒称跨 Android 进程互斥。宿主 new Repository 是磁盘重读，不是杀进程验收。

## 分批实施

### A. 纯日志恢复投影与 legacy 门禁（TDD，适合 Pi 独立实现）

- 输入读取成功的 JournalEntry 序列，输出已闭合/待核对目标或不可安全恢复原因；不做 I/O。
- 遍历所有未闭合 TARGET_REPLACING/TARGET_REPLACED，不只最后一条。跨 stage facts 必须一致；相同 operation 重复目标/相同 URI 冲突不得静默 last-wins。
- 先写真实 JSON/纯数据 RED：时钟倒退、无关 marker、同 op marker 后又有替换、空目标、旧无 facts、旧账本同 URI/不同内容、多个未闭合 op、facts 跨阶段冲突。
- 改旧恢复 gate：缺本次内容证明的已替换目标不能仅靠账本放行。已有明确闭合提交不因后来卸载误报 pending。

### B. Repository 恢复租约与原子补账（Codex 集成）

- 捕获 journal 原字节指纹、解析投影及 ledger 状态；建立明确的恢复租约，阻止普通提交和所有普通 ledger mutation（包括同实例），随后释放短锁执行慢只读观察。
- 完成观察后重取锁，验证租约、journal 指纹、ledger 状态未被改换；只接收逐目标观察结果，不允许上层代填修复记录。
- 以私有受控路径原子合并 upserts；逐 op marker 全部成功后才清理。失败不得清日志或把坏 ledger 降级为空。
- 实文件故障注入：目标已换/ledger 未写、ledger 成功/marker 失败、marker 成功/clear 失败、账本满盘/日志读失败、恢复期间普通写被拒、完成后新写可进入。

### C. 真实只读观察适配与 UI

- 使用既有受控 Shizuku 只读复制接口读取精确 bundle/hash/__data 至独占外部缓存，流式计算 SHA；缺失/null/服务断连是明确 ReadFailed，不取旧缓存。
- 严格路径验证和 owned-only 清理。单目标读取只能关联请求的 operation/bundle/hash，不按返回顺序猜配。
- 启动保留 pending；提供显式“核对并修复安装记录”动作及逐目标不匹配/无法核对说明。该动作只补账，不重新安装、不把“已知悉”当清日志许可。
- 核对是观察时刻的证据，不声称能够阻止外部游戏/其它工具并发改文件；需在验收中记录目标稳定/游戏已关闭前提。

### D. 验收与剩余路径

- 宿主真实文件集成、全量 JVM、Debug/R8 构建及源树指纹。
- 按原白名单/签名/沙箱规则另行做跨进程故障验收：实际临时目标先换文件后在 ledger 前中断，新进程核对、补账，第二次恢复零游戏写入、零叠加。
- 旧 moveFilesToGame 的写前意图及事实接线单独完成；不能仅因 converted 通过而关闭整个 T08。
- T10 的真实 UI 冷启动、arm64 与 T12 原生模块缺口仍按各自前置条件验收，不被本计划自动勾选。

此文只记录下一轮路线，不是测试结果或已完成证明；不新增设备安装、清数据或真实游戏写入授权。
