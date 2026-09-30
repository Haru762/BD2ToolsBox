# 审查问题修复收口实现计划（2026-09-28）

> 按 executing-plans 在当前会话逐项实现；每项使用失败回归 → 最小修复 → 定向验证 → 原子提交。无子代理工具，不创建并行执行窗口。

**目标：** 闭合本轮报告 S1–S6、F1–F16，以及经复现确认的同 bundle 覆盖/部分提交一致性问题。不能用降低验收标准或仅修改 DONE 标记代替修复。

**架构：** 沿用 Store/Submitter/Repository 边界；文件提交消费冻结计划，journal 保存能重建账本的目标事实，UI 不拥有长任务。只增加确有消费者的 DI 与操作所有者，不重做无关功能。

**技术栈：** 项目既定 Kotlin 2.2.20、Hilt/KSP、Gson 过渡格式、Python 3.11 主机测试、PowerShell、JUnit；版本与 ABI 不变。

## 授权与隔离

- 用户已确认整体 Implementation Plan，授权建立隔离工作树、迁入报告对应的现有未提交修复、兼容扩展恢复日志。
- 工作树：`D:/bd2 mod/worktrees/bdroid-review-closeout`；分支：`fix/review-closeout`；基线：`95b5d8dada9bd933bd643f7bf6d3991775d760d6`。
- `bdroid_src`、`bdroid-android-refactor`、`bdroid-audit-fixes` 均只读；后两者的并行/未提交内容不覆盖、不暂存、不清理。T18 不纳入。
- 候选只迁入报告对应的生产代码与测试，不携带临时脚本、旧构建输出、个人配置、素材或设备数据。保留迁入来源及验证日志在忽略的 `local/review-closeout/`。
- 只运行相关文件/模块的构建与定向测试；禁止整仓构建、全量门禁、清理其他工作树或停止其进程。优先离线复用已有依赖。
- 本任务可创建原子提交并在全部验证后推送修复分支；不自动合并主线、发布、安装到设备或写真实游戏目录。设备验收另行确认。

## R1 — 验证器与证据可信度（S1–S4）

**文件：** `tools/verification.ps1`、`tools/run_python_tests.py`、`tools/audit_native_abi.py`、`tools/tests/Test-AuditVerification.ps1`、`tools/tests/Test-SnapshotReleaseHarness.ps1`、`tests/test_verification_runner_safety.py`、`tools/tests/test_audit_native_archives.py`。

- [x] 先迁入 host 反例，确认旧解析器/输出保护在这些输入下失败。
- [x] 迁入并复核严格终态、逐用例配对、XML/通配选择与夹具检查；仅将本次授权的修复分支纳入工作区门禁。
- [x] 报告仅写入本仓 local 的新 JSON；路径/链接在真正创建输出时检查，越界不能产生写盘副作用。
- [x] ABI 审计打开并检查 `.imy`、内部 native 架构；损坏归档、错误 ABI 均明确失败。
- [x] 运行 `Test-AuditVerification.ps1`、`Test-SnapshotReleaseHarness.ps1`、`Test-UiColdStartHarness.ps1`，以及两个对应 Python 测试文件；记录实际通过/失败/skip。
- [ ] 检查差异后按 R1 文件精确提交。

## R2 — 文件提交、基底与账本护栏（F1–F8、F10–F11）

**文件：** `core/storage/PristineVerifier.kt`、`InstalledLedgerStore.kt`、`BoundStagingTree.kt`、`MoveInstallStaging.kt`、`data/model/ModModels.kt`、`data/repository/InstalledModRepository.kt`、`ConvertedInstallSubmitter.kt`、`LedgerSubmissionCoordinator.kt`、`ui/viewmodel/MainViewModel.kt`、`DirectInstallSummary.kt`、`ui/dialogs/AppDialogs.kt`（Kotlin 路径均以 `app/src/main/java/com/bd2toolsbox/` 为前缀）。

- [ ] 按实际包路径迁入报告对应候选；MainViewModel 保留 T17 构造注入，不整份覆盖为旧版。
- [ ] 冻结 jobs、选项、删除意图、目标字节；只提交并清理本批文件，覆盖前执行备份门禁。
- [ ] 直接装入完整传递提交结果，不触发普通 move；缺可信摘要回退可信来源。
- [ ] 处理空白账本、损坏保留件、无删除项时的修复通知；恢复租约每次调用独立并在所有退出路径释放。
- [ ] SAF Cursor 的 null/loading/error/遍历异常均失败，不把部分枚举当完整成功。
- [ ] 适配候选 VM 测试到 T17 构造器；定向运行对应 Store、Submitter、LocalBaseReuse、DirectInstallWiring、MoveInstallWiring、SafCopyFailure 回归。
- [ ] 检查差异后按 R2 文件精确提交。

## R3 — 可恢复的卸载及部分提交（F15、覆盖账本一致性）

**文件：** `core/storage/JournalRecoveryFacts.kt`、`JournalMoveFacts.kt`、`JournalRecoveryProjection.kt`、`OperationJournalStore.kt`、`data/repository/InstalledModRepository.kt`、两个 Submitter、`MainViewModel.kt` 及对应回归。

- [ ] 在真实临时文件/仓库接口上复现：新卸载 facts 缺失、同 bundle 全覆盖后残留旧账本、部分替换后的恢复阻断。
- [ ] 恢复事实兼容旧格式；新写入保存目标内容摘要与账本变更意图，包括卸载删除，禁止伪造安装 upsert。
- [ ] 账本按已核对的目标事实原子更新；完整覆盖不保留已经被移除的 MOD，不影响其他 bundle。
- [ ] 区分未替换、已替换、未知的目标，提供安全的核对闭合路径；不以清日志或放宽门禁绕过未知状态，不承诺任意步骤自动续跑。
- [ ] 测试新旧日志、取消/异常、每个提交窗口、重试幂等、无关记录保留；记录 schema 兼容和回退边界。
- [ ] 检查差异后原子提交 R3。

## R4 — 快照的全部竞态（F9、F16）

**文件：** `core/storage/ModSnapshotStore.kt`、`MainViewModel.kt`、`SnapshotMigrationRaceTest.kt` 及 Store 回归。

- [ ] 迁移不分配业务 revision、不覆盖新捕获/提交。
- [ ] 损坏/未知版本清理绑定读取时的状态；旧清理不删除新提交，也不使较新的待写失效。
- [ ] 将本轮两种 cleanup 交错与已有三种 migration 交错作为生产接口回归，保留失败原件/写失败语义。
- [ ] 定向验证 Store 与 VM 接线后提交 R4。

## R5 — T17 真正接线与任务所有权（F12–F14、S6）

**文件：** `di/DispatcherModule.kt`、`RuntimeModule.kt`、`RepositoryModule.kt`、两个 ViewModel、`MainActivity.kt`、`GuideScreen.kt`，必要的应用级安装任务所有者及其测试。

- [ ] IO/Default、时钟注入真实业务消费者；测试能控制调度与时间，移除无消费者/恒真假过渡代码。
- [ ] ViewModel 自身建立可用初始状态，移除必须先调用 initialize 的顺序约束；保留攻略进入页面时刷新语义。
- [ ] 安装状态、执行与收尾由单一应用级所有者持有；VM 重建不创建第二个 worker、不重复提交，不因 UI 销毁取消在途安全收尾。
- [ ] 以行为测试验证两个观察者共享任务、取消/失败释放、配置快照、无 initialize 的入口，以及注入时间/调度的实际效果。
- [ ] 定向编译/测试修改模块并提交 R5；不迁入并行 T18。

## R6 — 入口验收与交付（S5）

**文件：** Hilt 入口/Repository 构造测试、涉及的 androidTest、`docs/refactor/evidence/`、执行清单。

- [ ] 主界面、预览和三个 Service 的真实入口测试，不用仅加载生成类代替冷启动；不得碰真实 MOD/游戏数据。
- [ ] 运行已授权范围的定向验证；需设备时先说明目标、安装/清理范围并取得授权。未执行保持未验收，不冒充 DONE。
- [ ] 逐项映射全部问题 → 修复 → 测试 → 实际结果；只更新本工作树的文档。
- [ ] 核对 Git 差异、分支与远端状态，只提交/推送本次修复；不合并或发布。清理自身 scratch，保留测试脚本、日志和必要证据。
