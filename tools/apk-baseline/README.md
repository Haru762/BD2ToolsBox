# tools/apk-baseline/ — 只读 APK 基线核验工具（T03）

用于**可重复计算**任务 T03「冻结功能、旧数据和真实 release 包体基线」所需的
APK 文件级、条目级、签名块与内容分类数据。全部脚本：

- **只读** —— 不修改、不移动、不解包任何输入到磁盘
- **仅依赖 Python 标准库** —— 无需 pip 安装
- **单个工具不含硬编码本机路径** —— 所有路径由命令行参数给出
- **驱动脚本不依赖当前工作目录** —— `run-t03-analysis.ps1` 的 `-Root` 默认由
  `$PSScriptRoot` 上溯两级得到，且传给每个工具的路径一律**绝对**

这些是**诊断/取证工具**，不参与 Gradle 构建，不被 app 代码引用，也不影响发布产物。

## 脚本

| 脚本 | 作用 |
|---|---|
| `dump_entries.py` | 手工解析 ZIP 中央目录、本地文件头与 APK Signing Block，输出完整条目级 JSON |
| `compare.py` | 比较两份 `dump_entries.py` 输出：条目名集合、元数据字段、内容差异、偏移位移 |
| `structure.py` | 单次给出 EOCD / 中央目录 / Signing Block / 结构记账 / 逐条目载荷哈希的比较 |
| `byte_diff.py` | 逐字节差异，并按所属 APK 条目与结构区（载荷、签名块、中央目录、EOCD）归类 |
| `classify_content.py` | 按统一口径重算各内容分组的条目数与压缩/原始字节，并用**四项独立于分组循环**的判据决定退出码 |
| `imy_diff.py` | 比较两个 APK 内 `assets/chaquopy/app.imy` 的条目集合、CRC 与行尾归一化后的内容一致性 |
| `run-t03-analysis.ps1` | 驱动脚本：对五个包跑完上述 18 步，写出产物与 `SHA256SUMS.txt`；任一步非零即整体非零 |

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

# 5) 内容分类（判定失败时返回非零退出码）
& $PY tools/apk-baseline/classify_content.py <apk> --package-id <id> --out <out.json>

# 5b) 内容分类 + 回归判据：与已冻结的 baseline.json 逐组比对
& $PY tools/apk-baseline/classify_content.py <apk> --package-id <id> --out <out.json> `
    --expect docs/refactor/evidence/baseline.json --expect-package <baselineId>

# 6) app.imy 内部比较
& $PY tools/apk-baseline/imy_diff.py <a.apk> <b.apk> --out <out.json>
```

### `classify_content.py` 的退出码由什么决定

**不是**「各组之和 = 实测总量」——那两边在同一个循环里累加，恒真，没有鉴别力
（该检查仍会打印，但记为 `internalConsistency`，**不参与判定**）。真正决定退出码的是四项
取自分组**之外**的量：

| 判据 | 独立来源 |
|---|---|
| `eocdEntryCount` | EOCD 记录里声明的条目数（裸字节解析）vs `zipfile.infolist()` 的长度 |
| `localRegionAccounted` | Signing Block / 中央目录偏移给出的 local 区字节数 vs 在每个 local header 处**实读**的 `30 + nameLen + localExtraLen + compressSize` 之和 |
| `groupEntrySum` | 分组条目数之和 vs 上述 EOCD 声明数，且任何条目都必须落进某个分组 |
| `regression` | 各组 `entries`/`compressed`/`raw` vs `--expect` 指向的 `baseline.json` 冻结值 |

已做反证确认它**真的会失败**：把 Chaquopy 谓词改坏（20.8 MB 悄悄挪进 Other assets，
任何总量都不变）→ `regression` FAIL、退出码 1；把 EOCD 声明的条目数改 +1 →
`eocdEntryCount` 与 `groupEntrySum` 双双 FAIL、退出码 1。两次反证中 `internalConsistency`
都仍报 `True`。没有显式 `--package-id` 时，`--expect` 会报错要求给出 `--expect-package`。

## 输出含义

- `compressed`：中央目录记录的**压缩后**载荷字节，即容器实际存储的部分
- `raw`：条目**未压缩**字节
- `container overhead` = 文件字节 − 压缩载荷之和，包含本地头、文件名、对齐 extra
  字段、APK Signing Block、中央目录与 EOCD
- 条目级比较的「内容相同」判据是 `CRC-32 + 未压缩大小 + 压缩方法`；
  载荷逐字节哈希另由 `structure.py` 单独给出

## 已知限制（**如实记录，不要当成已验证**）

- `structure.py` 内含一条 APK Signature Scheme v2 signer 明细解析路径。真实包上该路径的
  格式假设不成立，解析会失败——而且是**静默降级**：异常只被写进一个本地 dict 的
  `parse_error` 键（`structure.py:105-106`），该键既不打印也不序列化；下游比较块以
  `if pra.get("parsed") and prb.get("parsed")` 为前提（`structure.py:187`），失败时整段跳过。
  表现出来是「没有 signer 输出」而非「解析失败」，**不要据此认为包没有签名**。
  **签名方案与证书指纹的权威核验请使用 `apksigner verify --print-certs`**。
- 曾有一个 `sigdetail.py` 专门做 v2 signer 明细解析，实测在真实包上抛
  `struct.error`，已移除，以免仓库里留一个必崩的工具。
- `byte_diff.py` 只在两个输入**等长**时工作（历史包与重建包正好等长；若不等长会断言失败）。
- `run-t03-analysis.ps1` 用 `Out-File -Encoding utf8` 捕获文本报告，**Windows PowerShell 5.1
  会给这些文件加 UTF-8 BOM**，所以它在写 `SHA256SUMS.txt` 前对本轮生成的文件做了一轮
  BOM 剥离。`SHA256SUMS.txt` 本身以「裸字节、LF 行尾、UTF-8 无 BOM」写出：若用
  `WriteAllLines` 会写出 CRLF，`sha256sum -c` 会把行尾的 `\r` 当成文件名的一部分而全部报错。

## 一次完整运行的固化产物

T03 的完整运行输出（四/五个包的 dump、比较、结构、分类、app.imy 比较、apksigner 记录）
保存在**被忽略**的 `local/refactor-artifacts/t03-apk-analysis/`，逐条命令、退出码与
输出哈希见该目录的 `RUN.md` 与 `docs/refactor/evidence/T03.md`。
