# vendored UnityPy 对上游 1.23.0 的只读审计（T12）

> 状态：**只读审计（内容比对完成，来源仍有缺口）**。
> 静态比对与 APK 内容审计证据生成于 **2026-09-19**（见 §7）；
> **本轮文档更正/补充日期 2026-09-20**，是对既有 2026-09-19 证据的复核修正，
> **未新跑构建、未重新抓取 sdist**。本文件把 `app/src/main/python/vendor/UnityPy/`
> 与 PyPI 官方 UnityPy 1.23.0 sdist 做逐文件比对，登记每个真实文本补丁的
> 用途/范围、6 个本地新增文件、随 APK 分发的 FMOD 库，以及仍无法证实来源的二进制。
> **本审计只做可核验的内容比对，不推断授权法律结论、不杜撰版本或编译来源。** 固定
> 版本号 `1.23.0` 不等同于「完全原版」——下文列出的 12 个真实补丁即证据。

## 1. 上游来源与校验口径

| 项 | 值 |
|---|---|
| 上游版本 | UnityPy **1.23.0** |
| 元数据 URL | `https://pypi.org/pypi/UnityPy/1.23.0/json` |
| sdist URL | `https://files.pythonhosted.org/packages/c9/8d/b5f68af949762edad75b3fb2eb304640ebd2b919eb2f3594f8ae865b0ca4/unitypy-1.23.0.tar.gz` |
| sdist 文件名 | `unitypy-1.23.0.tar.gz` |
| sdist SHA256 | `396e2bf8dea49b2bfd6309f5c790b2a5838613be037094bb55e6076c4852a890` |
| sdist 字节数 | 6 227 392 |
| 抓取时间 | 2026-09-19T12:51:33Z |
| 上游仓库 | https://github.com/K0lb3/UnityPy |

审计方法（见 `compare_unitypy.py`）：脚本用 `tarfile` 打开本地留档的官方 sdist，
**仅通过 `tar.extractfile(member).read()` 把各成员读入内存做比对，不调用 `extractall`、
不落盘展开、不 import 或执行任何包内代码**。对 `UnityPy/` 包内每个成员计算原始 SHA256
与本地文件逐一比对；对文本文件再做 CRLF→LF 归一化后二次比对，把「仅换行差异」与
「真实文本补丁」分开统计。sdist 制品 SHA256（`396e2bf8…4852a890`）记录于
`unitypy-source.json`（连同 PyPI 元数据 URL / 字节数 / 抓取时间）；本审计据此核对制品
身份；2026-09-20 主控重新计算本地 sdist SHA256，与留档 PyPI 元数据值相等
（见 `local/pi-director/20260920/continue-2026-09-20T02-12-54-898Z/t12-source-recheck.json`），
未重新联网抓取元数据。**原始 SHA256 全部保留在 JSON 证据中，未被归一化
覆盖。** 二进制文件（`.tpk`/`.so`/`.dll`/`.dylib`）不做归一化，按原始哈希判定。

## 2. 统计口径（归一化后）

| 维度 | 值 |
|---|---|
| 本地文件数 | 110 |
| 上游包文件数 | 104 |
| 原始 identical | 23 |
| 原始 changed | 81 |
| local_only | 6 |
| upstream_only | 0 |
| 归一化后：仅 CRLF 换行差异 | 68 |
| 归一化后：真实文本补丁 | 12 |
| 归一化后：二进制变化 | 1（`resources/uncompressed.tpk`） |

**68 个「仅换行差异」不算算法补丁**：本地整体是 CRLF 行尾，上游 sdist 是 LF；这
68 个文件（含体量最大的 `classes/generated.py`，338KB→350KB 全部来自 CRLF 膨胀）
CRLF→LF 归一后与上游逐字节相同。**`classes/generated.py` 确认仅换行变更，非内容
补丁**，其代码生成产物与上游一致。

## 3. 12 个真实文本补丁（逐条用途/范围）

证据：`unitypy-normalized-changes.diff`（归一化后仅含真实文本差异）。

1. **`export/Texture2DConverter.py`** — ASTC 解码入口改造。原 `import astc_encoder`
   直接解压改为优先 `from unpacker import decompress_astc_ctypes`（本项目 ctypes 入口）；
   `astc_encoder` 降级为可选（ImportError 时置 `None`）。`astc()` 函数在解码器缺失或
   报错时返回黑色占位 RGBA 图而非抛异常。范围：仅 ASTC 分支。
2. **`files/BundleFile.py`** — 磁盘溢出（spill）大改。新增 `SpillWindow(io.RawIOBase)`
   惰性窗口 + `SPILL_THRESHOLD`(48MB)/`SPILL_NODE_THRESHOLD`(8MB)；解压总量超阈值时
   落临时文件，资源流节点按需读盘，`.assets` 对象树仍驻内存。引入 `UnityVersion`、
   `SerializedFile`、`EndianBinaryReader_Streamable`、`ImportHelper`。范围：读/存包路径。
3. **`files/ObjectReader.py`** — `version` 类型由 4 元组改 `UnityVersion`；删除
   `build_type` 字段；`write()` 重写为 `meta_writer`/`data_writer` 分离并用
   `write_stream` 流式写；`get_nodes_up` 增加 `base_node` 入参并处理空 namespace。
4. **`files/SerializedFile.py`** — 删除内嵌 `BuildType` 类与基于 `re` 的
   `build_type`/`version` 解析；`version` 改 `UnityVersion`；引入 `tempfile`。
5. **`files/WebFile.py`** — 无效签名处理由静默 `return` 改为 `raise ValueError`
   （携带签名值）。范围：单一签名校验分支。
6. **`helpers/CompressionHelper.py`** — 新增 `chunk_based_compress_stream()`：按
   压缩类型分块流式压缩，供 spill 存盘路径使用。**归一化 diff 在该文件只含新增行、
   无删除行**（上游原 `chunk_based_compress` 在 diff 中未出现修改）。
7. **`helpers/MeshHelper.py`** — **不仅是辅助函数重排**：删 `flat_list_to_tuples`
   与旧 `zeros(shape)`，新增 `lists_to_tuples` 与新签名 `zeros(m, n)`；并改动
   `decompress_compressed_mesh` 与 `get_triangles` 的实际计算（如骨权重阈值由
   `sum >= 1.0` 改为 `sum >= 31`、`weight/31` 移位、TriangleStrip/Quads 索引重写）。
   **属有行为改动，非纯重排；行为等价未经运行验证。**
8. **`helpers/PackedBitVector.py`** — `reshape` 二维分支修正（去掉多余一层嵌套）；
   `unpack_floats` 增加 `m_BitSize==0` 零除保护；返回类型标注改 `List[Any]`。
9. **`helpers/Tpk.py`** — 移除内嵌 `UnityVersion(int)` 与 `UnityVersionType` 类，
   改从 `helpers/UnityVersion` 导入；新增 `read_version()`；`get_item_for_version`
   改用 `TypeVar` 泛型。范围：版本类型抽取与类型标注。
10. **`helpers/TypeTreeGenerator.py`** — `load_local_dll_folder` 过滤非 `.dll` 文件；
    `get_nodes_up` 增加 `base_node` 入参并传 `m_Children` 副本。
11. **`helpers/__init__.py`** — `__all__` 与导入新增 `UnityVersion` 模块。
12. **`streams/EndianBinaryWriter.py`** — 新增 `write_stream(source, length)`：4KB
    分块流式写，配合 spill/`data_writer`。**归一化 diff 在该文件只含新增行、无删除行**。

补丁 2/6/12 属同一「大包磁盘溢出」主线；3/4/9/10/11 属「UnityVersion 类型统一 +
写入路径重排」主线；1 属本项目 ASTC 解码接入；5/7/8 为独立健壮性/算法修正。
上述分类依据 diff 文本事实，**未对任何补丁做运行时行为等价验证**。

## 4. 6 个本地新增文件（local_only）

| 路径 | 本地字节 | 说明 |
|---|---|---|
| `LICENSE` | 1088 | MIT，本地 `(c) 2019-2026 K0lb3`。sdist 内未含此路径（upstream 为 null）。见 §5。 |
| `__main__.py` | 76 | 本地新增模块入口。 |
| `cli/__init__.py` | 657 | 本地新增 CLI 包。 |
| `cli/update_tpk.py` | 636 | 本地新增（tpk 更新脚本）。 |
| `helpers/UnityVersion.py` | 4356 | 独立 `UnityVersion` 模块，被补丁 3/9/10 等引用（对应从 `Tpk.py` 抽出的类）。 |
| `lib/README.MD` | 154 | 记 FMOD Engine 2.00.10 与 Linux execstack 清除；见 §6。FMOD 库本身属 sdist，非本地新增。 |

这 6 个文件上游 sdist 无对应路径（`upstream_only = 0`，即上游未出现本地缺失的文件）。

## 5. LICENSE 实际比较（不推断法律结论）

- 本地 `app/src/main/python/vendor/UnityPy/LICENSE`：MIT，版权行 `(c) 2019-2026 K0lb3`，
  SHA256 `b350c142384a5cc7b1b71142311583286e3ec8a98eabce7516d9a1730133f3b6`，1088 字节。
- 上游对照 license（`upstream-LICENSE.txt`）：**来自同一个 sdist 的根目录**
  `unitypy-1.23.0/LICENSE`，由 `compare_unitypy.py` 从 tar 直接读出写盘（**非单独网络
  抓取**）；MIT，版权行 `(c) 2019-2021 K0lb3`。该根目录 LICENSE 未在 JSON 中记录
  单独 SHA256（脚本只用它做字节相等判定，未求哈希），不杜撰。
- **官方 sdist 的 `UnityPy/` 包内无 `LICENSE` 路径**（包内比对记 `upstreamSha256: null`；
  上文根目录 LICENSE 在包之外，两者不是同一个比对维度）。
- 逐字节比较结论：`licenseMatchesUpstreamProjectLicense = false`；即便 CRLF 归一化
  （`licenseMatchesIgnoringCRLF = false`）两者仍不同。**逐行比较已确认：唯一实质
  差异是版权年份区间（`2019-2021` vs `2019-2026`），MIT 正文逐字相同。**
- **本审计只陈述文本差异事实，不对授权合规/法律效果下结论。**

## 6. FMOD 库：字节可溯源到 sdist，但编译/法律性质 UNKNOWN

- **上游 sdist 本就内含整套 `lib/FMOD/**`**（非本项目新增）。归一化比对中
  **19 个 FMOD 常规文件（8 个二进制 + 11 个空 `__init__.py`）均 raw identical**
  （`normalizedStatus: identical`，双端原始 SHA256 逐一相等）。**因此不能写「FMOD
  与 UnityPy sdist 无关」或「来源全 UNKNOWN」——这些字节可溯源到官方 sdist 字节。**
  8 个二进制：`Darwin/libfmod.dylib`(2,430,928)、`Linux/{arm,arm64,x86,x86_64}/libfmod.so`
  （1,214,580 / 1,254,672 / 1,625,248 / 1,569,032）、`Windows/{arm,x64,x86}/fmod.dll`
  （1,071,104 / 1,724,416 / 1,491,968），**合计 12,381,948 未压缩字节**。
- **仍 UNKNOWN 的部分**：这些 FMOD 库的**原始编译 commit、`lib/README.MD` 声称的
  FMOD Engine 2.00.10 版本、以及其授权法律性质均无独立证据**。字节可溯源不等于
  版本/合规已证实，不杜撰。
- **`resources/uncompressed.tpk`**：唯一二进制内容变化（原始哈希与上游不同）。本地
  `.tpk` 的原始生成/更新输入与 commit 仍无证据。2026-09-20 额外确认本地文件与
  BDroid_X 分发提交 `566990cf1290a8d718b0ddd596b07ca7c36794c8` 逐字节相同，
  因此**分发来源已确认，原始生成来源 UNKNOWN**；见 `python-dependencies.md` §6.3。
- 包外 native `.so`/`.dll`/`.dylib`（如 `libastcenc.so`/`libtexture2ddecoder.so`）的编译
  来源、commit 均无仓内证据，不杜撰版本号（不属本包内审计范围，见 §6.1）。

## 6.1 FMOD 随 Release APK 分发：已核查（2026-09-19 静态 ZIP 内容）

证据：`fmod-apk-audit.json`（只读 ZIP 内容检查，**无解压/import/安装/执行**）。

- 3 个 Release APK（`app-arm64-v8a-release.apk`、`app-universal-release.apk`、
  `app-x86_64-release.apk`）的 `assets/chaquopy/app.imy` **均随包携带 FMOD**；与 ABI 分包
  无关，三包都含全平台 FMOD。
- 每个 `app.imy` 报告 `fmodEntries = 30`，**必须拆开：11 个目录条目（如
  `vendor/UnityPy/lib/FMOD/`、`.../Darwin/` 等，`bytes=0` 的空 SHA `e3b0c44…`，
  `localExactMatch=false` 因目录无对应本地文件）+ 19 个常规文件**（8 二进制 +
  11 空 `__init__.py`）。**不得把 30 条当作 30 个文件。**
- 19 个常规文件在三包内均 `localExactMatch=true`（与本地 sha 逐一匹配）；8 个
  二进制合计 **12,381,948 未压缩字节**。**这是 imy(zip) 内未压缩字节，不是 APK 体积。**
- APK 体积（如 arm64 34,193,593 / universal 43,663,995 / x86_64 34,046,037）与 APK 本体
  SHA256 已在 `fmod-apk-audit.json` 登记；2026-09-20 主控已重算三包哈希，与审计报告相等
  （`local/pi-director/20260920/continue-2026-09-20T02-12-54-898Z/fmod-release-recheck.json`）。
  这是 2026-09-19 构建产物的复核，不代表随后 T08 代码已打进这些包。
- 未核查：FMOD 在设备上是否被 runtime 实际加载/调用（本审计只看静态 ZIP 内容）。

## 7. 证据路径（可追踪）

审计目录：`local/pi-director/20260919/continuous-2026-09-19T12-51-30-297Z/upstream/`

- `unitypy-source.json` — sdist URL/SHA256/字节数/抓取时间/PyPI 元数据。
- `unitypy-normalized-comparison.json` — 逐文件原始+归一化状态、双端 SHA256/字节数。
- `unitypy-normalized-changes.diff` — 归一化后仅含 12 个真实文本补丁的 unified diff。
- `unitypy-comparison.json` / `unitypy-changes.diff` — 未归一化（含 CRLF）原始对照。
- `compare_unitypy.py` — 本次比对脚本。
- `upstream-LICENSE.txt` — 从同一 sdist 根目录 `unitypy-1.23.0/LICENSE` 读出的 MIT 原文（非单独网络抓取）。
- `fmod-apk-audit.json` — 3 个 Release APK 内 `app.imy` 的 FMOD 条目、JNI/IMY 清点与 APK SHA256（只读 ZIP 内容）。
- `audit_fmod_apks.py` — FMOD/APK 内容审计脚本。
- `unitypy-1.23.0.tar.gz` — 官方 sdist（本地留档，SHA256 见 `unitypy-source.json`）。

## 8. 已闭合 vs 剩余缺口

已闭合（以 2026-09-19 证据为准，2026-09-20 复核）：
- UnityPy 包内逐行比对完成（12 真实补丁 + 68 仅换行 + 6 新增 + 1 二进制）。
- FMOD 库字节已溯源到官方 sdist（19 常规文件 raw identical，见 §6）。
- FMOD 随 3 个 Release APK 的 `app.imy` 分发已核查（静态 ZIP 内容，见 §6.1）。

剩余缺口（未闭合）：
- `resources/uncompressed.tpk` 原始生成输入/commit UNKNOWN（BDroid_X 分发提交已确认）。
- FMOD 库的原始编译 commit 与授权法律性质仍 UNKNOWN。本地新增的 `lib/README.MD` 声称 2.00.10，但 8 个二进制里都没有这个字符串。2026-09-22 复核：Windows x86/x64 的 UTF-16 资源含 `2.0.22 (build 122614)` 和 Firelight Technologies；Windows arm 只有 `2.0.22`；Linux 四个 ABI 和 Darwin 都没有这两个版本串。因此不能把整套库标成 2.00.10 或 2.0.22，也不能把版本字符串当成编译 commit。
- FMOD 在设备上是否被 runtime 实际加载/调用未验证（本审计只看静态 ZIP 内容）。
- `uncompressed.tpk` 当前 SHA-256 是 `3777edf16f1a57908640ef6d1e49fa51533e545424188b239c1b8e2ead3988da`（1,326,072 字节）。文件头魔数 `TPK*`、版本 1、无压缩、类型为 TypeTree。版本表有 1212 条，从 `3.4.0f5` 到 `6000.2.0a6`。`cli/update_tpk.py` 指向浮动的 nightly.link master 产物，不能证明这次生成的 commit。生成来源仍是 UNKNOWN。
- 主控已复算三个 2026-09-19 APK 的 SHA256，与旧审计相同；这不是当前代码的新构建证据。
- native `.so` 原始库源码 commit / 构建步骤仍缺；三个当前 JNI 文件已匹配到上述
  BDroid_X 分发提交（见 `python-dependencies.md` §6.3），不等于原始编译来源已闭合。
- 12 个文本补丁的运行时行为等价未验证（本审计仅做静态 diff 比对）。

> T12 本项保持 **IN_PROGRESS**：来源逐行比对与 FMOD 随包已核，但 runtime、native
> 原始构建来源、tpk 生成来源仍欠。
