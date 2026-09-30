# Audit D 续跑：选定目标的独占暂存（2026-09-25）

> **历史检查点。** 后续已完成生产搬运接线及本地回归，当前状态见 [接线证据](audit-move-wiring-20260925.md)。以下结论、命令和哈希保留其当时含义，不外推到新源码。

## 结论与范围

**状态：暂存组件本地验证通过；Audit D 整体仍为 IN_PROGRESS。**

- 工作区：`worktrees/bdroid-audit-fixes`，分支 `fix/audit-safety`。
- 起点：`1ce7c28decad1ee9c6e7272e720f2ad2fb913eea`，恢复时保留全部既有未提交修复。
- 本次新增实现仅在 `MoveInstallStaging.kt`；扩充 `MoveInstallStagingTest.kt`。未在本轮重新实现其他审查问题。
- `MainViewModel.moveFilesToGame()` **仍调用旧的 `ShizukuManager.moveDownloadToGame()`**。新组件尚未接到生产提交入口，不能把本次结果称为整目录覆盖/清理风险的端到端修复。
- 没有调用 ADB、安装 APK、写入游戏目录、清理其他 worktree、合并、推送或发布。原 `bdroid_src` 和 `bdroid-android-refactor` 的可见 git 状态与本次开始时一致。

## 上次中断点与本次实现

恢复时的占位实现直接把 `shared` 和可变 `targets` 返回给调用者，`verifyUnchanged()` 恒返回 `null`，`cleanupSources()` 递归删除整个共享目录。

本次实现：

1. 深拷贝并以不可修改列表保存目标、URI 与账本记录；拒绝空选择、重复 bundle、不安全路径、非正长度、安装目标缺失恢复事实。
2. 在独立暂存根下申请既有 `StagingDirectoryLease`，只复制选定的 `bundle/hash/__data`，不复制历史残留和伴随文件。
3. 在复制前比较原文件长度及安装目标的预期 SHA-256；复制后比较实际暂存清单，拒绝复制途中产生的不一致。
4. `verifyUnchanged()` 同时核对目录所有权与完整文件/目录清单；新增文件、内容变化、目录身份变化以及关闭后的调用均不可被认作有效。
5. `cleanupSources()` 仅逐个删除仍与冻结摘要一致的原文件；保留发生变化的源文件、无关文件以及所有父目录，不递归清理 Download/Shared。
6. `close()` 仅释放本次拥有的暂存目录；准备失败时清理本次 partial，保留原始异常和清理异常。
7. 纯还原目标沿用 `modUris=[] / recoveryFacts=null` 的旧契约，不伪造安装 upsert；仍绑定本次暂存字节，但**不因此取得还原删除意图的自动恢复能力**。

## RED → GREEN

原始日志全部位于本 worktree 的 `local/audit-fixes/`，保留不覆盖。

| 日志 | 结果 | 说明 |
|---|---|---|
| `resume-move-red.log` | exit 1，3/3 失败 | 重新复现上次遗留测试，失败是未冻结副本、误删共享目录、未拒绝原文件变化 |
| `resume-move-extended-red.log` | exit 1，测试编译失败 | 新测试跨模块 nullable 属性不能 smart cast；只修测试局部变量，不计作有效 RED |
| `resume-move-extended-red-2.log` | exit 1，10 条中 9 条失败 | 通过编译后在占位实现上实跑；纯还原读取用例是既有行为对照，不计作新缺陷 RED |
| `resume-move-green.log` | exit 0，10/10 通过 | 实现后定向回归，0 failure/error/skip |

定向命令（工作目录为 audit worktree）：

```bash
bash local/audit-fixes/gradle.sh :app:testDebugUnitTest \
  --tests 'com.bd2toolsbox.core.storage.MoveInstallStagingTest'
```

`gradle.sh` 使用 JDK 17、Gradle 8.9、该 worktree 独占 `local/gradle-home`、只读依赖缓存、`--offline --no-daemon --max-workers=1` 和进程内 Kotlin 编译；未停止其他 daemon，也未执行 clean。

## 本次全量本地回归

```bash
bash local/audit-fixes/gradle.sh \
  :app:testDebugUnitTest :release-probe:testDebugUnitTest \
  :app:compileDebugAndroidTestKotlin

python -B -m unittest discover -s tests -p test_verification_runner_safety.py -v

powershell -NoProfile -ExecutionPolicy Bypass -File tools/tests/Test-AuditVerification.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File tools/tests/Test-SnapshotReleaseHarness.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File tools/tests/Test-UiColdStartHarness.ps1

python -B tools/run_python_tests.py --root . \
  --environment local/audit-fixes/resume-environment.json \
  --output local/audit-fixes/resume-python-configured-summary.json
```

| 范围 | 实际结果 |
|---|---|
| App JVM | 34 个 XML 套件，435 tests，0 failures/errors/skipped |
| Release-probe JVM | 2 个 XML 套件，6 tests，0 failures/errors/skipped |
| Android instrumentation 源码 | `compileDebugAndroidTestKotlin` 成功；**未运行设备测试** |
| Python runner 安全测试 | 4/4 通过（也包含在下方 Python 总数内，不重复计数） |
| PowerShell 审查护栏 | 7/7 通过 |
| Snapshot host harness | 16/16 通过 |
| UI cold-start host harness | 14/14 通过；不是实际 UI 冷启动验收 |
| Python 全量 | discovered/executed/passed=126，failed/errors/skipped/notRun=0；两项 catalog 配置/文件校验均通过 |
| Git whitespace | `git diff --check` exit 0 |

Python 第一次全量运行虽然 126 条测试均通过，但配置文件缺失，runner 正确返回 **FAIL**（`fixture-not-configured`，日志 `resume-python-full.log`，报告 `resume-python-summary.json`）。随后在忽略的本地 JSON 中显式配置已保全 catalog 路径及固定 SHA-256，重新运行才得到 PASS。没有用环境变量存在或测试计数覆盖夹具门槛。

固定 catalog SHA-256：`98fc0a1e47552116c6398bd7656b87b451adfcd97f5b3de7ebe5428314f10f3f`。仅只读使用重构 worktree 已保全的该文件，没有搬运整个素材目录。

构建仍有既有 Kotlin nullable/deprecation/unused 警告及 Gradle 9 不兼容提示；本次没有宣称零警告或工具链升级完成。

## 证据固定

- 已将两模块 JVM 原始 XML 复制到 `local/audit-fixes/resume-jvm-xml/`，避免后续构建覆盖唯一报告。
- `local/audit-fixes/resume-verification-manifest.json` 登记本次源码、测试、日志、报告、XML 的 SHA-256。
- 清单 SHA-256：`1491555ff8e77ac59bd9f20c127d3fdf09cab7d0537c6a7cd48bc001fd2adc8f`。
- `MoveInstallStaging.kt`：`fcc683c9d5faef570bc06239c2d474da289f59eee5229a362ae16b9e0a504ab6`。
- `MoveInstallStagingTest.kt`：`999f9f9b3ccfc1b21db0dc57fcfb6952bbf5f501ff4cbff26b749005562ad874`。

## 审阅与剩余工作

当前工具集没有子代理入口，本轮仅完成实现者内联自查和自动化验证，**没有独立审阅结论**。

下一步仍按原 Audit D 计划执行，不能跳过：

1. 在主线程、异步边界之前冻结成功 jobs、画质/ASTC、备份设置及卸载删除意图；后台不得再次读取可被关闭弹窗清空的状态。
2. 接入本次暂存组件。真实写入、journal 与账本必须使用同一冻结目标集；移除对旧整目录搬运入口的调用。
3. 在实际覆盖前落实备份失败门禁；关闭备份的用户选择保持不变。纯转换产物仍可生成。
4. 使用冻结账本记录提交，待核对结果禁止当作成功、自动重试或继续搬运；仅完整提交成功后清理本次源文件。
5. 补生产编排的失败/取消/关闭弹窗/部分还原测试，确认卸载不会删掉未成功恢复的目标记录。
6. 新增组件的实际 Android 文件系统身份读取、NTFS junction/符号链接专门用例以及 source cleanup 检查与删除之间的外部并发竞态，本轮未专项验收。现有 lease 的其他用例通过不等于本组件已完成这些覆盖。
7. 完成接线和独立审阅后再收口 Audit D/F；ARM64 设备、真实 release、T12 来源缺口及 T13+ 均未在本轮推进。
