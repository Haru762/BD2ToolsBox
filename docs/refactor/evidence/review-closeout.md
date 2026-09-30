# 审查修复证据（2026-09-28）

分支 `fix/review-closeout`，基线 `95b5d8d`。实施计划：`docs/superpowers/plans/2026-09-28-review-closeout.md`。

总体状态：**IN_PROGRESS**。不将已通过的主机测试等同于业务、Android 设备或 release 验收完成。

## R1 — 验证器（S1–S4）

**状态：LOCAL_VERIFIED。** 只改主机工具、对应测试和说明；未编译 App，未连接设备。

### 改动

- 从用户批准迁入的 `fix/audit-safety` 候选中携带 Python 输出保护、严格 instrumentation 协议、JVM 通配/XML/夹具判定；没有改动来源工作树。
- 工作区门禁仅追加本次明确授权的 `fix/review-closeout`；仍验证真实仓库根、脚本来源、交接资料，拒绝 main/detached HEAD。
- Python runner 在实际报告副作用之前拒绝越界、链接/重解析点、已有文件和源码树输出；最终使用独占创建。
- ABI 审计打开 stdlib/requirements/bootstrap `.imy`，逐项读取触发 ZIP CRC 校验，并核验内部 native 的 ELF 架构；损坏、空归档、截断 ELF 不再伪通过。

### RED → GREEN

命令均在本工作树执行；Python 使用已安装的 3.11，PowerShell 使用本机 5.1，未安装依赖。

| 检查 | 修复前 | 修复后 | 原始证据（相对仓库根） |
|---|---|---|---|
| `python -B -m unittest discover -s tests -p test_verification_runner_safety.py -v` | 11 条，10 失败 | **11/11**，skip 0 | `local/review-closeout/R1-python-{red,green}.log` |
| `tools/tests/Test-AuditVerification.ps1` | 16 条，9 失败 | **16/16** | `local/review-closeout/R1-powershell-{red,green}.log` |
| `python -B -m unittest discover -s tools/tests -p test_audit_native_archives.py -v` | 7 条，6 失败 | **7/7**，skip 0 | `local/review-closeout/R1-native-{red,green}.log` |
| `tools/tests/Test-SnapshotReleaseHarness.ps1` | 既有回归 | **16/16** | `local/review-closeout/R1-snapshot-harness.log` |
| `tools/tests/Test-UiColdStartHarness.ps1` | 既有回归 | **14/14** | `local/review-closeout/R1-ui-harness.log` |
| 点源本树 `verification.ps1 -ProjectRoot <本树>` | — | **Load PASS**；无构建/设备副作用 | 会话命令输出 |

合计 **64 条主机回归通过**。snapshot harness 的 cleanup 警告属于故障注入测试，不是设备清理失败。初次 RED 的中文控制台编码不佳，原日志保留；GREEN 命令显式使用 UTF-8。

### 真实归档兼容对照

仅只读检查另一工作树已有的两份 release APK，**不是本修复分支构建或运行的 APK**。新审计器对两 ABI 都 PASS；输入哈希、各库和归档计数保存在：

- `local/review-closeout/R1-existing-arm64-audit.json`
- `local/review-closeout/R1-existing-x64-audit.json`

已有 `libastcenc.so` 4 KB 对齐、双 ABI bootstrap 警告保持原样，未放宽任何检查。没有通过全量构建来制造这些输入。

### 来源与剩余范围

迁入 patch：`local/review-closeout/R1-candidate.patch`（只含 3 个明确选定的工具/测试文件，未暂存来源工作树）。新的 `.imy` 回归自建 ZIP/ELF 元数据夹具，不依赖 APK 构建。

R2–R6 仍未完成；尚无本分支 Android/Hilt 入口、真实 Shizuku/SAF、游戏文件写入或新 release 运行证据。不会据 R1 给它们勾选完成。
