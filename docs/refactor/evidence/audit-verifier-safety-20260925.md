# Audit A：验证器安全门禁（2026-09-25）

## 结论

**LOCAL_VERIFIED。** 已补齐报告输出边界、必需覆盖判定、instrumentation 结束协议和 JUnit XML 计数一致性检查。仅在 `worktrees/bdroid-audit-fixes` / `fix/audit-safety` 操作；未执行 ADB、设备安装、游戏写入、合并或发布。

本次只修改验证脚本与 host 测试，不修改 App Kotlin/Python 业务代码。App JVM 446 条、release-probe JVM 6 条是上一轮已执行结果；本轮用新的 XML 解析器重新读取并确认它们没有被错误拒绝，**不声称重新执行了这些 JVM 用例**。

## 发现与修复

### Python 报告输出

恢复时虽然已有 realpath 包含性检查和独占创建，但仍可：

1. 把新报告写进 `app/src`；
2. 通过指回仓库内部源码目录的 junction 绕过范围意图；
3. 在测试运行期间创建/改指目录链接，导致报告写入前的 `makedirs` 先在仓库外创建目录，然后才报越界。

修复后 `--output` 必须是实施根 `local/` 下的新 `.json` 文件。逐个检查现存路径组件，拒绝符号链接及 Windows reparse point/junction。在测试发现之前、创建报告目录之前、目录创建之后均验证边界，最后继续用独占 `open(..., 'x')`，不覆盖已有或测试期间新出现的文件。

这是明确收紧接口：过去能传任意仓库内路径，现在只能传 `local/...json`。已核对仓库的 PowerShell 正常调用使用 `local/refactor-artifacts/test-results/.../report.json`，本地验收使用 `local/audit-fixes/`，两者仍兼容。CLI help 和模块说明已更新。

### Python 必需覆盖

- 原 `--allow-skip <必需测试ID>` 会让全部必需用例都被跳过的运行返回 PASS；现在无论该登记是否存在，必需用例 skip 均 FAIL。
- `@expectedFailure` 不再被算作满足必需覆盖。保留真实 `outcome=expected-failure`，报告添加 `required-test-expected-failure` 原因，而不是伪造一次正常通过。
- 原 CLI `--allow-skip` 参数保留用于兼容旧调用及报告记录，但不能豁免必需覆盖；没有扩大可跳过的集合。

### instrumentation 原始协议

以前主要按开始和通过的数量判断，会放过未知状态码、不同测试的开始/结束配对、先结束后开始、终止信号后仍有测试状态等输入。

现在检查：

- adb transport exit 0；唯一的成功 `INSTRUMENTATION_CODE: -1` 必须处于末尾；
- 唯一成功汇总必须位于最后一条 raw status 之后、终止信号之前；
- test/class 的开始与结束必须按顺序配对；
- 只接受 start=1、pass=0 和 progress=2，失败/跳过/未知/畸形状态均拒绝；
- 声明数量、开始数量、通过数量与最后汇总一致。

progress=2 仍是合法进度，不计为用例通过，不改既有 snapshot probe 协议。

### JUnit XML

解析器原先相信 `<testsuite tests/failures/errors/skipped>` 属性，忽略属性与真实 `<testcase>` 子节点矛盾。现在核对每个计数均为非负整数且与实际 testcase、failure/error/skipped 节点一致。非零 tests 属性但没有 testcase、skipped=0 却含 skipped 节点等报告均不能通过。

JVM `-AllowSkip` 不能豁免所选必需用例的已有规则已补行为测试。通配符选中多个测试类及各自 fixture 的行为继续通过。

## RED → GREEN

原始日志保留在本 worktree 的 `local/audit-fixes/`：

| 日志 | 结果 |
|---|---|
| `verification-runner-extended-red.log` | 11 条中 5 条断言失败，分别复现源码输出、内部链接、创建目录前越界、required skip、expected failure |
| `verification-runner-extended-green.log` | 11/11 通过 |
| `verification-protocol-extended-red.log` | 16 条中 6 条失败：四个 raw protocol 缺口及两个伪造 XML 计数缺口 |
| `verification-protocol-extended-green.log` | 16/16 通过 |
| `verification-snapshot-regression.log` | 旧 harness 因强制匹配中文“起止数不匹配”报错；实际 malformed protocol 已被更早的身份配对检查拒绝，**不是放行** |
| `verification-snapshot-final.log` | 将该断言修正为检查必须拒绝，而非固定错误阶段/文案后，16/16 通过；没有删掉错误输入或放宽可接受协议 |
| `verification-protocol-final.log` | 清理不可达的旧负码统计分支后，再次 16/16 通过 |
| `verification-ui-final.log` | 14/14 host UI harness 通过 |
| `verification-real-unit-xml.log` | 新解析器正确读取现有 App 446、probe 6 条 JVM XML，均 0 failure/error/skip |
| `verification-python-final.log` | Python 全量 133/133，0 failed/errors/skipped/notRun；两项 catalog 配置/哈希门禁均通过 |

## 可重放命令

工作目录：audit worktree。

```bash
python -B -m unittest discover -s tests -p test_verification_runner_safety.py -v

powershell -NoProfile -ExecutionPolicy Bypass -File tools/tests/Test-AuditVerification.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File tools/tests/Test-SnapshotReleaseHarness.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File tools/tests/Test-UiColdStartHarness.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File local/audit-fixes/verify-existing-unit-xml.ps1

python -B tools/run_python_tests.py --root . \
  --environment local/audit-fixes/resume-environment.json \
  --output local/audit-fixes/verification-python-final-summary.json
```

重放时必须为 JSON 选择新文件名；已有报告不会被覆盖。PowerShell 测试从 AST 提取被测函数，未 dot-source 顶层构建/设备初始化。

## 固定证据

- 清单：`local/audit-fixes/verification-audit-manifest.json`，记录 17 个源码、测试、日志与结果文件的 SHA-256。
- 清单 SHA-256：`4ba70d3e58871d6a0efbf71b4f5147c76eb3f5ce7805b962278ebea781eccee2`。
- Python 全量报告 SHA-256：`26ce885c90ccdda0853e7e7425d080e91191fbce8c981cd001a15202261c9516`。

## 保留边界

- 路径测试在 Windows 用真实 NTFS junction，在非 Windows 分支用符号链接；本机实际执行的是 junction 分支。
- 多次复核和独占创建封堵本次实际复现的改指窗口，但没有宣称能抵御任意外部进程在系统调用之间持续恶意替换祖先目录。没有实施平台专用的 dirfd/openat 能力沙箱。
- 协议检查只证明 host 解析器能正确拒绝伪绿色输入，不是设备测试本身已完成；本轮没有设备操作。
- malformed XML 会抛错拒绝，不会生成 PASS；本轮未重构所有错误归档路径。
- 当前无独立审阅子代理，仅实施者自查与自动化验证。Audit B/C/E 的逐项收口及最终 F 仍待继续，不把本项通过等同整个项目完成。
