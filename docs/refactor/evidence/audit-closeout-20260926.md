# 审查修复收尾：13 项映射与本地验证（2026-09-26）

状态：**LOCAL_VERIFIED / 待独立审阅与设备验收**。分支 `fix/audit-safety`，起点 `1ce7c28`，全部改动**未提交**。
未合并、未推送、未发布；未连接设备（隔壁窗口正在用 adb 做 arm64 T12，本分支设备验收需另行安排）。

## 1. 13 项 → 代码 → 测试

| # | 问题 | 修复位置 | 证明测试（JVM，除非注明） |
|---|---|---|---|
| 1 | 无可信摘要时同尺寸游戏文件被当原版基底 | `PristineVerifier.classify`（无摘要同尺寸→UNKNOWN）；`MainViewModel.tryUseLocalBundle` 统一走 classify | `PristineVerifierTest.digestlessBaselineCannotCertifySameSizedContent`；**`LocalBaseReuseTest`**（真实调用点，还原旧逻辑→FAILED） |
| 2 | converted 把 `filesReplaced` 当成功 | `ConvertedSubmitResult.completedSuccessfully`；`doInstallConvertedBundles` / `installOneConverted` 全程传结果；`directInstallOutcome` 三态文案；`InGameResultSection` 显示失败 | `ConvertedInstallSubmitterTest.aReplacedFile…`；`DirectInstallSummaryTest` 6；**`DirectInstallWiringTest`** 2（改回 `filesReplaced`→FAILED） |
| 3 | move 复制整个 `Download/Shared`，journal 只记当前 jobs | `MoveInstallStaging`：只暂存冻结目标的 `__data`，逐目标 `installConvertedBundle` | `MoveInstallStagingTest`；`MoveInstallWiringTest.onlySelectedOutputReachesGame…` |
| 4 | 现存空白账本被当合法空表 | `InstalledLedgerStore.read` 空白→Corrupt；`preserveCorruptFile` 不覆盖既有保留件 | `InstalledLedgerStoreTest.blankFileIsCorruptAndRemainsUntouched`（合法 `{}` 仍 Empty，见同文件 §合法空表） |
| 5 | 提交阶段备份失败后自动 move 不查门禁 | `moveFilesToGame` 提交前按冻结目标检查 `unfinishedBackups` + `decideBackupGate(onCommitPath=true)` | `MoveInstallWiringTest.backupFailureStopsTheRealCommit…`、`disablingAutomaticBackup…` |
| 6 | legacy 快照迁移覆盖新扫描 | `ModSnapshotStore.loadAndMigrate`：同步、仅 revision==0 时写、不分配 revision | `SnapshotMigrationRaceTest` 3（两种交错；还原旧实现→FAILED） |
| 7 | instrumentation 判定缺终态协议 | `tools/verification.ps1` 配对 start/end、终止顺序与数量 | `Test-AuditVerification.ps1` 16；`Test-SnapshotReleaseHarness.ps1` 16 |
| 8 | 关闭对话框清空 jobs 导致漏记账 | `moveFilesToGame` 在 Main 首个挂起点前冻结 jobs/选项/删除意图；账本记录取自 journal 冻结 upserts | `MoveInstallWiringTest.closingTheDialogDuringCopy…` |
| 9 | SAF `DocumentFile.listFiles()` 吞错 | `DocumentFileCopySource.listChildren` 直接查 Cursor，null/loading/error/中途异常均抛 | `SafCopyFailureTest` 4 |
| 10 | 无可删记录时不传播 Repaired | `InstalledModRepository.unchangedWrite` | `InstalledModRepositoryLedgerTest.removingFromCorruptLedger…` |
| 11 | UI 恢复失败后租约不释放 | `recoverContent` / `recoverOnce` 一次性入口，每次恢复独立 lease token，finally 仅释放自己的；UI 只用该入口 | `JournalRecoveryApplyTest` +3；`MoveInstallWiringTest.twoRecoveryClicks…`、`cancellationDuringRecovery…` |
| 12 | JVM 验证入口不支持通配选择 | `verification.ps1` 按实际匹配类集合核对 XML | `Test-AuditVerification.ps1` |
| 13 | Python runner 越界输出仍写盘 | `run_python_tests.py._validate_report_destination`：副作用前拒绝；仅 `local/` 新 JSON；拒链接/junction；独占创建 | `tests/test_verification_runner_safety.py` 11 |

复核新增（非原 13 项）：

| 项 | 修复 | 测试 |
|---|---|---|
| 直接装入标记为 VM 级可变字段 | `InstallJob.producesDownload` | `MoveInstallWiringTest.anEarlierConvertedBatchDoesNotBlock…`（粘滞标记→FAILED） |
| 批量 move 每目标重核全树（O(n²)） | `verifyTarget` 拷贝前后逐目标核对 | `MoveInstallStagingTest` +3；`MoveInstallWiringTest` +2（两种破坏各自→FAILED） |
| `BoundStagingTree.rel` 在 8.3 短路径下生成 `../` 键 | 相对遍历根 | `MoveInstallStagingTest.selectiveSnapshotDigestsOnlyRequestedFiles` |

## 2. 本地验证（临时副本、独立 GRADLE_USER_HOME、只读依赖缓存、`--no-daemon --max-workers=1`）

- `:app:testDebugUnitTest` **468/0/0/0**
- `:app:compileDebugAndroidTestKotlin` 通过；`:app:assembleDebug` 三包（arm64-v8a / x86_64 / universal）生成，**未安装**
- Python **133/133**（6 skip = 可选外部 catalog）；PowerShell 16 / 16 / 14
- 未运行：release 构建、release-probe、任何 instrumentation/设备用例

## 3. 其他工作树

- `bdroid-android-refactor`：只读；本轮期间其 HEAD 由 `020871b` 前进到 `131e8c1`（隔壁窗口自行提交），未被本分支触碰。
- `bdroid_src`（main）：未改动。

## 4. 仍未完成（不打勾）

- 独立审阅。
- 真实 Android/Shizuku 验收：move 冻结目标、直接装入三态、恢复一次性入口、SAF Cursor 行为、`RealGameRecoveryDeviceTest`。
- 与 `refactor/android-architecture` 的集成方式（本分支起点落后其 HEAD 若干提交）由用户决定。
