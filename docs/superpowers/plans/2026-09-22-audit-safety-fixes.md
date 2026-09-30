# 审查问题隔离修复计划

**目标：** 修复只读审查确认的 13 项问题，不修改另一个 agent 的工作树、分支、构建输出或设备。

**授权与隔离：** 用户已要求开始修复并避免干扰并行开发。修复位于 `D:/bd2 mod/worktrees/bdroid-audit-fixes`，分支 `fix/audit-safety`，起点 `1ce7c28decad1ee9c6e7272e720f2ad2fb913eea`。原重构工作树只读。无 ADB、合并、推送或发布。本计划细化已在审查报告中提供并获用户要求实施的修复方向，不启动 T13+。

**架构：** 在既有 Store/Submitter 内闭合结果和资源所有权；move 使用冻结的目标、内容和账本记录，UI 关闭只影响展示。验证器必须在副作用前拒绝不安全输入。保留现有 ABI、依赖版本、原始基线和只读内容恢复策略。

## A. 验证器（审查 7/12/13）

**2026-09-25 当前检查点：LOCAL_VERIFIED。** Python runner 安全用例 11/11、PowerShell 审查护栏 16/16；本轮补齐输出白名单、junction、测试期间改指、required skip/expected-failure、raw protocol 配对及 XML 真实计数检查。详见 [验证器证据](../../refactor/evidence/audit-verifier-safety-20260925.md)。

- [x] 用独立临时目录和子进程复现 runner 越界输出仍写盘；拒绝越界、符号链接和覆盖源码输出，报告只进入允许目录。
- [x] 使用 PowerShell AST 载入被测函数，不执行顶层构建／设备代码；复现缺终态协议仍通过。
- [x] 验证通配符选中多个 XML 类、匹配各自夹具；明确必需 skip 不可通过 blanket allow 绕过。
- [x] 定向 RED/GREEN 与既有 host harness；日志仅写本 worktree 的 local。

## B. 账本与恢复（审查 4/10/11）

（2026-09-26 收尾：LOCAL_VERIFIED，见 [收尾证据](../../refactor/evidence/audit-closeout-20260926.md)）

- [x] 零字节／空白现存账本为 Corrupt，合法空 JSON 表仍可用；先用新反例替换旧错误期望并运行 RED。
- [x] remove/removeByTargetHash 即使无可删记录也传播 Repaired 与保留件位置。
- [x] 单次内容恢复入口拥有恢复租约，成功／NotApplied／异常／取消均 finally 释放；UI 使用该入口，pending journal 不因释放租约而清除。
- [x] JVM 临时文件和多 Repository 实例证明失败后新实例可以恢复。

## C. 基底与 converted（审查 1/2/9）

（2026-09-26 收尾：LOCAL_VERIFIED，见 [收尾证据](../../refactor/evidence/audit-closeout-20260926.md)）

- [x] 缺少可信内容摘要不得作为原版基底；保持联网下载回退，备份来源过滤不退化。
- [x] converted 全程传递 ConvertedSubmitResult，不把 filesReplaced 当作完整提交成功；待核对结果禁止重试／自动 move。
- [x] SAF 直接读取 DocumentsContract Cursor 并校验 null/loading/error/中途异常，不依赖会吞错的 DocumentFile.listFiles。
- [x] 验证缺摘要、损坏／部分 Cursor、记账失败及用户可观察状态。

## D. 冻结 move（审查 3/5/8）

**2026-09-25 当前检查点：LOCAL_VERIFIED，待独立审阅和设备验收。** 暂存组件已接入生产 `MainViewModel`；真实写入、journal 与账本消费同一冻结目标集，实际覆盖前检查备份门禁。11 条真实 VM 接线测试及全量回归通过。详见 [接线证据](../../refactor/evidence/audit-move-wiring-20260925.md)；此前仅暂存组件的阶段记录保留于 [历史检查点](../../refactor/evidence/audit-move-staging-20260925.md)。

- [x] 暂存组件：冻结嵌套记录，仅复制选定 `__data`；拒绝源变化、暂存篡改及未知目录身份。
- [x] 暂存组件：关闭仅清理自己的目录；源清理保留无关文件及后来改变的源文件。
- [x] 主线程先捕获 jobs/选项/删除意图，后台只消费该快照；关闭结果框不改变在途记账。
- [x] 仅复制选定 bundle/hash/__data 到本次独占暂存，核对冻结 SHA/长度；真实写入、journal 与账本使用同一目标集。
- [x] 实际提交前执行备份失败门禁；不改变关闭自动备份的设置，不中止纯转换产物生成。
- [x] 成功后只清理本次源文件且需内容仍一致；不递归删除整个 Download/Shared。
- [x] 临时文件测试覆盖历史残留、源变更、复制失败、UI 列表变化与记录冻结。
- [x] 批量提交改为逐目标拷贝前后核对（结构全检、仅本目标摘要），每目标摘要次数与批量无关；见 [增补证据](../../refactor/evidence/audit-snapshot-batchflag-20260926.md) §6。
- [x] 直接装入部分失败/全部失败/待核对三态文案与结果区显示分开；见增补证据 §5。
- [ ] 独立审阅与真实 Android/Shizuku 验收。

## E. 快照排序（审查 6）

**2026-09-26 当前检查点：LOCAL_VERIFIED。** 复核发现迁移占用 revision、会丢弃较早捕获的扫描，已修复并补反例；另把直接装入标记收进批次。详见 [增补证据](../../refactor/evidence/audit-snapshot-batchflag-20260926.md)。

- [x] 将旧格式读取及升格置于 Store 单次同步操作内，读／迁移不得跨越新 capture/commit；原 load 保持只读。
- [x] 用可控线程和真实 Store 验证迁移与新扫描交错不回写旧结果（两种交错顺序均覆盖）。

## F. 收尾

**2026-09-25 最新验证器增量：** Python 全量 **133/133**、PowerShell 审查护栏 **16/16**、snapshot **16/16**、UI host harness **14/14**。本轮只修改 host 脚本/测试；新 XML 解析器复核既有 App 446、probe 6 条报告通过，未重新执行 JVM 或重建 APK。详情见验证器证据。

**此前生产接线回归（历史，非全审查收尾）：** App JVM **446/446**、release-probe JVM **6/6**（强制重跑）、Python **126/126**；全部 0 failure/error/skip。PowerShell 审查护栏 7、snapshot host harness 16、UI host harness 14 条通过；三个 debug APK 构建成功但未安装，Android instrumentation 源码编译成功但未运行设备用例。旧暂存阶段 435 条 JVM 及首次 Python 配置失败记录仍保留。

- [x] 独立构建目录／Gradle user home，最多一个 worker；仅只读复用缓存，不停止他人 daemon、不 clean 其他工作树。
- [x] 针对性测试、全量可运行本地测试、Android 编译；缺失平台证据明确保留，不给设备验收打勾。
- [x] 核对差异、13 项修复映射与其他工作树状态；交付修复分支及证据，不自动集成。
- [ ] 独立审阅（未做）。
- [ ] 真实 Android/Shizuku 设备验收（未做；不因本地通过而打勾）。
