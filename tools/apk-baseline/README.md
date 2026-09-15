# tools/apk-baseline/ — 只读 APK 基线核验工具（T03）

用于**可重复计算**任务 T03「冻结功能、旧数据和真实 release 包体基线」所需的
APK 文件级、条目级、签名块与内容分类数据。全部脚本：

- **只读** —— 不修改、不移动、不解包任何输入到磁盘
- **仅依赖 Python 标准库** —— 无需 pip 安装
- **不接受硬编码本机路径** —— 所有路径由命令行参数给出

这些是**诊断/取证工具**，不参与 Gradle 构建，不被 app 代码引用，也不影响发布产物。

## 脚本

| 脚本 | 作用 |
|---|---|
| `dump_entries.py` | 手工解析 ZIP 中央目录、本地文件头与 APK Signing Block，输出完整条目级 JSON |
| `compare.py` | 比较两份 `dump_entries.py` 输出：条目名集合、元数据字段、内容差异、偏移位移 |
| `structure.py` | 单次给出 EOCD / 中央目录 / Signing Block / 结构记账 / 逐条目载荷哈希的比较 |
| `byte_diff.py` | 逐字节差异，并按所属 APK 条目与结构区（载荷、签名块、中央目录、EOCD）归类 |
| `classify_content.py` | 按统一口径重算各内容分组的条目数与压缩/原始字节，并**自检各组之和等于实测总量** |
| `imy_diff.py` | 比较两个 APK 内 `assets/chaquopy/app.imy` 的条目集合、CRC 与行尾归一化后的内容一致性 |

`classify_content.py` 刻意用标准库 `zipfile` 实现，而 `dump_entries.py` 是手工中央目录解析器——
两者独立，因此它们的分类结果一致是一次**真实交叉校验**，而不是同一段代码跑两遍。

## 用法

以下命令均在实施根 `D:/bd2 mod/worktrees/bdroid-android-refactor` 下执行。
`$PY` 为本机已验证的 Python 解释器（见 `docs/refactor/evidence/environment.local.json`）。

```powershell
# 1) 抽取条目级 dump
& $PY tools/apk-baseline/dump_entries.py <a.apk> <a.json>
& $PY tools/apk-baseline/dump_entries.py <b.apk> <b.json>

# 2) 条目元数据与内容比较
& $PY tools/apk-baseline/compare.py <a.json> <b.json> [标签A] [标签B]

# 3) 结构比较（EOCD/中央目录/签名块/结构记账/逐条目载荷）
& $PY tools/apk-baseline/structure.py <a.apk> <b.apk> <a.json> <b.json>

# 4) 逐字节差异归类（要求两个文件等长）
& $PY tools/apk-baseline/byte_diff.py <a.apk> <b.apk> <a.json> <b.json> <out.json>

# 5) 内容分类（自检失败时返回非零退出码）
& $PY tools/apk-baseline/classify_content.py <apk> --package-id <id> --out <out.json>

# 6) app.imy 内部比较
& $PY tools/apk-baseline/imy_diff.py <a.apk> <b.apk> --out <out.json>
```

## 输出含义

- `compressed`：中央目录记录的**压缩后**载荷字节，即容器实际存储的部分
- `raw`：条目**未压缩**字节
- `container overhead` = 文件字节 − 压缩载荷之和，包含本地头、文件名、对齐 extra
  字段、APK Signing Block、中央目录与 EOCD
- 条目级比较的「内容相同」判据是 `CRC-32 + 未压缩大小 + 压缩方法`；
  载荷逐字节哈希另由 `structure.py` 单独给出

## 已知限制（**如实记录，不要当成已验证**）

- `structure.py` 内含一条 APK Signature Scheme v2 signer 明细解析路径。真实包上该路径的
  格式假设不成立，会解析失败并跳过输出（不会打印错误明细，也不会打印 signer 内容）。
  **签名方案与证书指纹的权威核验请使用 `apksigner verify --print-certs`**，
  不要依赖这个脚本的 signer 明细。
- 曾有一个 `sigdetail.py` 专门做 v2 signer 明细解析，实测在真实包上抛
  `struct.error`，已移除，以免仓库里留一个必崩的工具。
- `byte_diff.py` 只在两个输入**等长**时工作（历史包与重建包正好等长；若不等长会断言失败）。

## 一次完整运行的固化产物

T03 的完整运行输出（四/五个包的 dump、比较、结构、分类、app.imy 比较、apksigner 记录）
保存在**被忽略**的 `local/refactor-artifacts/t03-apk-analysis/`，逐条命令、退出码与
输出哈希见该目录的 `RUN.md` 与 `docs/refactor/evidence/T03.md`。
