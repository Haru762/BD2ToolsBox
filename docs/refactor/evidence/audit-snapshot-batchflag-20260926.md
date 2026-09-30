# 审查增补修复：快照迁移序号 / 直接装入标记（2026-09-26）

状态：**LOCAL_VERIFIED**，待独立审阅；无设备、无合并、无发布。只修改本 worktree。

## 1. 快照迁移占用序号，丢弃较早捕获的扫描（审查 6 复核）

- 缺陷：`ModSnapshotStore.loadAndMigrate` 用 `commit(capture(...))` 写迁移结果，`capture` 递增 revision。
  顺序「扫描 capture(rev=1) → 冷启动 loadAndMigrate(rev=2) → 扫描 commit(rev=1)」时，扫描写盘被判
  `Skipped(write.revision=1, 当前=2)`，磁盘留下旧格式迁移出的表，下次冷启动恢复旧列表。
- 原 `SnapshotMigrationRaceTest` 只覆盖「迁移先读、扫描后写」一种交错，未覆盖此顺序。
- 修复：迁移只在本实例尚无任何 capture/discard（revision == 0）时写，且经私有 `persist` 写盘，不分配 revision；
  `commit` 的两道闸保持不变，序列化/原子写抽到 `persist` 共用。
- 测试：`SnapshotMigrationRaceTest` 新增
  - `migrationDoesNotSupersedeAnEarlierCapturedScan`（反例）
  - `migrationUpgradesFormatAndLeavesLaterScansCommittable`（迁移仍升格格式，之后扫描可提交）
- 证伪：临时副本中还原旧实现，反例 **FAILED**（3 completed, 1 failed）；修复后 3/3 通过。

## 2. `installProducesDownload` 为 ViewModel 级可变标记（审查新增）

- 缺陷：直接装入流程把 VM 字段置 false，只在下一次 `processInstallJobs` 恢复；标记不属于任何批次，
  与 `_installJobs` 可不一致（关闭对话框、别的入口改写 jobs 时仍残留）。
- 修复：标记改为 `InstallJob.producesDownload`（默认 true），由 `doInstallConvertedBundles` 为本批 job 设 false，
  随状态 copy 传递；汇总收集器与 `moveFilesToGame` 均改为读取已冻结的 `displayedJobs` / 当次 `jobs`。删除 VM 字段。
- 测试：`MoveInstallWiringTest`
  - `aConvertedBatchCannotMoveUnrelatedDownloadLeftovers` 改为通过 job 标记构造（不再反射已删字段）
  - 新增 `anEarlierConvertedBatchDoesNotBlockALaterRepackBatch`
- 证伪：临时副本中注入「粘滞的 VM 级标记」，新用例 **FAILED**（14 completed, 1 failed）；修复后 14/14。

## 3. 验证环境与结果

- 全部构建/测试在临时副本中执行（`git ls-files -co --exclude-standard` 导出，排除 `local/`），独立
  `GRADLE_USER_HOME`，`GRADLE_RO_DEP_CACHE` 只读复用用户缓存，`--no-daemon --max-workers=1`；
  未在本 worktree 或 refactor worktree 运行 Gradle，未停止任何 daemon，未连接设备。
- `:app:testDebugUnitTest` **453/0/0/0**（此前 450；+2 快照、+1 接线）；`:app:compileDebugAndroidTestKotlin` 通过。
- 未重跑 Python / PowerShell harness（本次未改相关文件；上次 133/133、16+16+14）。

## 4. 未覆盖

- 真实 Android/Shizuku 设备验收、独立审阅仍未做。
- 直接装入部分失败时界面同时显示「失败」与「完整提交 N 个」，文案属 UX 判断，未改。
- move 每个 bundle 重核整个暂存树的 O(n²) 读盘，沿用接线证据第 4 条登记，未改。

---

# 追加（同日）：直接装入结果文案 / 批量提交校验开销

状态：**LOCAL_VERIFIED**，待独立审阅与设备验收。

## 5. 直接装入部分失败被显示为整批失败（原审计第 2 条）

- 缺陷：`doInstallConvertedBundles` 有任一失败即设 `MoveState.Failed("完整提交 N 个，M 个失败或待核对。<首个原始错误>")`；
  而 `InGameResultSection` **忽略 Failed**，始终画绿色勾与 `successFallback`「已装入游戏目录」——全部失败也显示成功。
- 修复：
  - 新增纯函数 `directInstallOutcome(committed, failed, needsReconciliation)`（`ui/viewmodel/DirectInstallSummary.kt`）：
    全成功 → Success；部分成功 → Failed「已装入 N 个（重启游戏后生效），M 个未装入。」；全失败 → 不声称任何装入；
    待核对目标单独提示「可能已改动游戏文件，请先核对，不要直接重试」。首个原始错误不再塞进标题，明细仍在失败列表。
  - `InGameResultSection` 对 Failed 显示错误图标/颜色与真实文案；「启动游戏」按 `finalResult.successfulJobs > 0` 显示。
- 测试：`DirectInstallSummaryTest` 6 条。证伪：换回旧文案实现，4/6 FAILED。
- 未做：Compose 截图/UI 测试（本分支无 Robolectric/Compose host 测试基建），需设备验收。

## 6. move 每个 bundle 重核整个暂存树（原审计第 4 条，O(n²) 读盘）

- 修复：
  - `BoundStagingTree.snapshot(root, digestFile)`：结构（全部文件、大小、目录、链接拒绝）照旧完整检查，
    只对选中文件计算 SHA-256；原 `snapshot(root)` 等价于全选。
  - `PreparedMoveInstall.verifyTarget(bundle)`：结构全检 + 仅该 bundle 字节摘要；未冻结目标、关闭后、清单无该前缀均失败（fail closed）。
  - `moveFilesToGame` 每个目标**拷贝前后**各调用一次 `verifyTarget`，替代「每个目标前全树 + 结尾全树」。
    后续目标在其自身拷贝前仍被全字节核对，因此篡改防护不减；另保留提交前的一次全树 `verifyUnchanged`。
  - 每目标摘要次数：旧 n+3（n=6 时 9 次），新固定 4 次，与批量大小无关。
- 顺带修复：`BoundStagingTree.rel` 改为相对**遍历根**而非 `toRealPath()`。Windows 8.3 短路径（`NEFELI~1`）下
  两者不同，会生成 `../../…` 键；原全选路径因两次快照同样错误而相等，未暴露，选择性摘要按前缀匹配时暴露。
- 测试：
  - `MoveInstallStagingTest` +3：只核本目标但同尺寸篡改由其自身检查捕获；其他目标尺寸变化/注入文件/未知目标/关闭后均拒绝；选择性快照只摘要选中文件、目录集合与全量一致。
  - `MoveInstallWiringTest` +2：第一个目标拷贝后同尺寸篡改第二个暂存目标 → 第二个不进游戏、账本不写、保留待核对；6 目标批量每目标摘要 ≤ 4 次。
- 证伪：(a) 换回每目标全树核对 → 摘要计数用例 FAILED；(b) 删掉拷贝前核对 → 篡改用例 FAILED。

## 7. 本轮验证

- 同上：临时副本、独立 `GRADLE_USER_HOME`、只读依赖缓存、`--no-daemon --max-workers=1`，未触碰 refactor worktree、未连设备。
- `:app:testDebugUnitTest` **464/0/0/0**；`:app:compileDebugAndroidTestKotlin` 通过。
- Python 133/133（6 skip 为可选外部 catalog）；PowerShell 审查护栏 16/16、snapshot 16/16、UI host 14/14。
