# 发版核对清单（T61）

本清单是**发版前的逐项核对表**，不是发版脚本。每一项都要求「证据在哪、谁看了、
结论是什么」，不允许用「构建通过」代替。

> **状态：本清单尚未执行完毕。** 逐项结果见
> [evidence/final-summary.md](evidence/final-summary.md)。未运行的项保持未运行，
> 不因为「其它项都过了」而勾上。

## 0. 权限边界（先读这一节）

以下动作**必须**由用户明确批准后才做，本清单不代为执行：

- 合并 `refactor/android-architecture` 到任何长期分支；
- 推送（`git push`）任何东西；
- 移除 worktree；
- 对外发布 APK 或提交到任何分发渠道；
- 修改对外版本号（`gradle/version.properties` 的 `appVersionCode`/`appVersionName`）。

理由：这些是**不可逆或对外可见**的动作，且发版号必须按渠道当前最大值决定，
不能拿本次开发用的 `17` 盲推未来的发布号。

## 1. 代码与工作区

- [ ] `git status --short --branch` 输出中，**属于本重构的改动都已提交**；
      并行会话的改动原样保留、未被暂存或提交。
- [ ] `git diff --check` 无空白错误。
- [ ] 本分支之外没有未提交的构建产物被误纳入（`apk/`、`local/` 等按 `.gitignore` 忽略）。
- [ ] `docs/refactor/evidence/environment.local.json` 等本地文件未被提交。

## 2. 机器门禁（必须全绿，且记录命令与退出码）

- [ ] 架构检查：`python tools/check_architecture.py --root . --quiet` → 退出码 0。
- [ ] 基线清单核对：`python tools/verify_manifests.py --root .` → 16 条哈希全对。
- [ ] JVM 单测（两个 flavor）：`Test-Bd2UnitAll` → 0 失败 / 0 错误 / 0 跳过。
- [ ] Python 测试：`Test-Bd2Python -Pattern 'test_*.py'` → 0 失败 / 0 跳过。
- [ ] 一键本地验证：`& tools/verification.ps1 -Mode LocalAll` → 退出码 0。

## 3. 产物

- [ ] 两个 ABI 的 minified release 都已构建：`app/build/outputs/apk/{arm64,x64}/release/*.apk`。
- [ ] **不存在** universal APK（单 ABI flavor，见 T49）。
- [ ] **不存在** debug 包混入交付目录。
- [ ] 每个产物记录：字节数、SHA-256、`versionCode`、`versionName`、签名指纹。
- [ ] `tools/audit_apk_size.py` 对每个产物跑过，ABI / 资源名 / DEX 字符串守卫全过。
- [ ] `tools/audit_native_abi.py` 对每个产物跑过：ELF 架构、`DT_NEEDED`、16 KB 对齐。
- [ ] `mapping.txt` 与 native 符号表随产物一起归档（R8 与 strip 之后）。
- [ ] 许可清单（`THIRD-PARTY-NOTICES.md`、`docs/refactor/license-inventory.md`）与
      产物内嵌的 AboutLibraries 数据一致。

## 4. 版本与签名

- [ ] **渠道当前最大 `versionCode` 已实际查过**（不能假设）。
- [ ] 本次 `appVersionCode` **严格大于**该最大值。
- [ ] `appVersionName` 与本次发布说明一致。
- [ ] 签名密钥与既有发布一致 —— 用户**可直接覆盖升级、无需清数据**。
- [ ] 签名指纹与上一次对外发布比对过（不一致就意味着用户必须卸载重装）。

## 5. 兼容与数据

- [ ] 旧版（0.2.16）数据可直接升级读取：账本、设置、缓存、备份。
- [ ] 旧格式迁移路径有证据（`json-migration` smoke + `JsonMigrationTest`）。
- [ ] 升级后**不清空**用户数据；`clearAll` 之类的破坏性动作只在用户显式确认后发生。
- [ ] 中断/待核对的处理路径可用（恢复横幅 + 「核对并修复记录」）。

## 6. 设备矩阵

- [ ] x86_64 / API 32（MuMu）：release 安装、启动、身份、smoke 全过。
- [ ] x86_64 / API 29（minSdk 边界）：同上。
- [ ] x86_64 / API 36 / **16 KB 页**：同上（含真实 `System.loadLibrary`）。
- [ ] arm64-v8a / 真机：同上。
- [ ] 每个平台上 `ModWorkflowAcceptanceTest` 与 `ProcessDeathRecoveryTest` 通过。
- [ ] 未能运行的项目**逐条列出**，不勾。

## 7. 交付材料

- [ ] `docs/refactor/evidence/final-summary.md` 汇总实际**通过 / 失败 / 跳过 / 未运行**，
      逐项对应必需矩阵。
- [ ] `docs/refactor/artifact-manifest.json` 的 `t61Delivery` 记录了最终产物的路径、
      字节数、SHA-256、`versionCode`/`versionName`、签名指纹、mapping 与符号表位置。
- [ ] 原始历史基线与优化前基线**仍完整可追溯**（`t03Freeze`、`t48Freeze` 未被覆盖）。
- [ ] fixture、环境与工作区交接清单可复核（谁在哪台机器上跑过什么）。
- [ ] 唯一新版 TODO 与证据索引的最后状态已更新；**旧版复选框不重新激活**。

## 8. 发布后

- [ ] 归档本次交付件（APK × 2、mapping × 2、符号表、许可清单、验收报告）。
- [ ] 记录本次发版号，供下次核对「严格递增」。

## 9. 回退预案

- 代码回退**不等于**数据回退。数据已被改动时，按 journal 恢复，不靠回退代码猜测事实。
- 合并/发布前保留完整分支与可追溯产物；**不**自动强推、reset 或删除工作区。
- 若发现数据安全回归，**阻止交付**，保留失败现场与原始数据，先定位再修复；
  不修改 golden 期望去迎合错误实现。
