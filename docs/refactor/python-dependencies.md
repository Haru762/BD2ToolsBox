# Android 侧 Python 依赖与第三方来源（T12）

> 状态：**IN_PROGRESS**（2026-09-19）。本文件记录 Chaquopy 打进 APK 的 Python
> 依赖锁定口径、目标解释器/构建机/pyc 兼容规则，以及随源码或安装包分发的
> 第三方组件来源。依赖解析、wheel 哈希、冷解析与构建验收由 Codex 本批实测
> 提供（见文末「验证证据」）；本文不把「宿主回归」「wheel 可下载」当作
> 「设备内目标解释器 import 已通过」。设备内 import 未完成；T11 已本地验收完成（受控 Release 构建通过）。

## 1. 解释器与构建机（两套版本，别混）

| 项 | 值 | 来源 |
|---|---|---|
| APK 内目标解释器 | CPython **3.8.18**（wheel tag `cp38`） | `build-python-summary.json`（真实 Gradle daemon 日志） |
| wheel ABI | 字节码依赖 `cp38`（C 扩展）；pure 包为 `py3-none-any` | 同上 / §4 |
| Android 平台 tag | C 扩展 wheel 为 `android_21`（个别如 libcxx 为 `android_24`） | `wheel-summary.json` |
| app minSdk | **API 29**（应用最低系统版本，与 wheel tag 是两回事） | `build-python-summary.json` |
| 构建机 buildPython | **3.11.9** | 同上 |
| 目标解释器声明位置 | `app/build.gradle.kts` 的 `chaquopy.defaultConfig.version = "3.8"` | 工作区源码（本批未改） |

`version = "3.8"` 是 T12 的实际改动之一：显式钉死目标解释器，不吃 Chaquopy 15.0
随插件升级而变的默认值。实际运行时落到 3.8.18，这个补丁号由 **Chaquopy 的
目标运行时构件**确定（即打进 APK、在设备上执行的那个 CPython）；pip 索引
只负责依赖 wheel 的获取，不决定 APK 内 CPython 的补丁号。

`cp38` 只适用于带 C 扩展的 wheel（Pillow/lz4/Brotli 与 Chaquopy native），纯 Python
包都是 `py3-none-any`；Android 平台 tag（android_21/24）也不等于 app minSdk 29，
不要把“所有 wheel 都要求 API 29”混为一谈。

**buildPython 查找顺序（local.properties → 环境变量 `CHAQUOPY_PYTHON` → PATH）在
T12 之前已存在**，不是本任务新增交付；T12 只新增了目标解释器显式化、pip `-r`
清单与配套注释。

## 2. pyc 生成情况（实测，不作减包保证）

buildPython 3.11.9 与目标 3.8 的 minor 不一致，Chaquopy 无法为本项目代码与
pip 依赖编译 `.pyc`。真实 Gradle daemon 日志（`assemble-debug-daemon.log`）含
**两条** pyc 不兼容警告（`build-python-summary.json` 已摘录）。

实测 Debug APK 资产（`debug-apk-audit.json`）：

| IMY | `.py` | `.pyc` |
|---|---|---|
| `app.imy`（本项目 Python） | 105 | 0 |
| `requirements-common.imy`（pip 依赖 pure 部分） | 361 | 0 |
| 预编译 bootstrap | — | 96 |
| stdlib-common | — | 598 |

**因此不能写「整个 APK 没有 pyc」**：bootstrap 与 stdlib 的 pyc 由 Chaquopy 预编译
打包，与本项目 buildPython 版本无关。要让本项目代码与 pip 依赖也生成 pyc，需把
buildPython 换成 3.8.x；pyc 是否生成属可选优化，不是本任务减包手段。

> 已知未治理（登记，不属 T12）：单 ABI APK 的 `lib/` 正确分架构，但 Chaquopy 的
> IMY 仍同时含两 ABI（既有 T49 议题）。

## 3. 依赖锁定口径与实际解析结果

锁定文件：`app/requirements-android.txt`，只锁 **9 个直接依赖**（全部 `==`）。
本批冷解析（Codex 实测）得到的完整分发集为 **18 个 distribution / 472 个文件**
（`inventory-comparison.json`、`before-inventory.json`）。

**9 个直接依赖**（== 锁定）：Pillow 9.2.0、lz4 4.3.2、brotli 1.0.7、
fsspec 2025.3.0、attrs 25.3.0、requests 2.32.4、beautifulsoup4 4.15.0、
protobuf 5.29.6、tqdm 4.70.1。

**实际传递依赖**（本批解析结果，requirements 未锁）：certifi 2026.7.22、
charset-normalizer 3.5.1、idna 3.15、urllib3 2.2.3（均来自 requests）、
soupsieve 2.7、typing-extensions 4.13.2（来自 beautifulsoup4）。

**Chaquopy 自动注入的 native 依赖**（由 Pillow/Brotli 的 Requires-Dist 引入，
不写进 requirements）：chaquopy-freetype 2.9.1、chaquopy-libcxx 180000、
chaquopy-libjpeg 1.5.3。

### 3.1 requirements 现有说明的待审查点

`app/requirements-android.txt` 头部注释与 `app/build.gradle.kts` 里有三处论断，本批
不静默修改，先列为审查问题：

- **为何不锁传递依赖。**注释称「锁传递依赖会把 pip 解析器锁死、上游撤回旧版
  就构建失败」。这是一种取舍（可复现性 vs 解析鲁棒性），不是唯一正确做法；
  已由本文 §3 完整登记传递依赖实际版本，若后续要求完全可复现需另评估。
- **现有版本无升级。**本批冷解析的 18 包版本集与历史基线一致（见 §5），
  即「纯锁版本」成立；包升级必须与纯锁定分提交。
- **pyc 必然如何。**注释将 pyc 不生成归因于 buildPython minor 不匹配；已由 §2 实测
  证实（两条真日志警告 + IMY 清点），但不能因此写成「整包无 pyc」。

### 3.2 为什么不能用宜家 Windows 的 pip freeze

本文所有 wheel 均为 Chaquopy 为 Android 解析的 `cp38 + android_21/24_<abi>` 产物，
与宜机 Windows 的解析结果是不同产物，不能互相顶替。

## 4. wheel 来源与校验（本批实测 24 个 wheel）

本批冷解析下载并复算 **24 个 wheel**：12 个 pure（`py3-none-any`）+ arm64 6 +
x86_64 6。来自 PyPI 的 12 个有 index sha256 且**均匹配**；Chaquopy 镜像的 12 个
无 index 哈希，此处记的是实际下载 body 的 sha256，**不声称有上游哈希背书**。
完整来源/文件名/hash 见 `wheel-summary.json`（精简）与 `wheel-audit.json`（元数据）。

| 包 / 文件名 | 来源 | index哈希 |
|---|---|---|
| certifi-2026.7.22-py3-none-any | PyPI (files.pythonhosted.org) | ✓ 匹配 |
| charset_normalizer-3.5.1-py3-none-any | PyPI | ✓ 匹配 |
| idna-3.15-py3-none-any | PyPI | ✓ 匹配 |
| urllib3-2.2.3-py3-none-any | PyPI | ✓ 匹配 |
| soupsieve-2.7-py3-none-any | PyPI | ✓ 匹配 |
| typing_extensions-4.13.2-py3-none-any | PyPI | ✓ 匹配 |
| attrs-25.3.0-py3-none-any | PyPI | ✓ 匹配 |
| requests-2.32.4-py3-none-any | PyPI | ✓ 匹配 |
| beautifulsoup4-4.15.0-py3-none-any | PyPI | ✓ 匹配 |
| protobuf-5.29.6-py3-none-any | PyPI | ✓ 匹配 |
| fsspec-2025.3.0-py3-none-any | PyPI | ✓ 匹配 |
| tqdm-4.70.1-py3-none-any | PyPI | ✓ 匹配 |
| Pillow-9.2.0-0-cp38 (arm64_v8a / x86_64) | chaquo.com/pypi-13.1 | — 无 index |
| lz4-4.3.2-0-cp38 (arm64_v8a / x86_64) | chaquo.com/pypi-13.1 | — 无 index |
| Brotli-1.0.7-12-cp38 (arm64_v8a / x86_64) | chaquo.com/pypi-13.1 | — 无 index |
| chaquopy_freetype-2.9.1-2-py3 (arm64/x86_64) | chaquo.com/pypi-13.1 | — 无 index |
| chaquopy_libjpeg-1.5.3-1-py3 (arm64/x86_64) | chaquo.com/pypi-13.1 | — 无 index |
| chaquopy_libcxx-180000-0-py3 (android_24 arm64/x86_64) | chaquo.com/pypi-13.1 | — 无 index |

两 ABI（arm64-v8a / x86_64）均取到适配 wheel；ABI 分布与逐文件 sha256 见
`before-inventory.json` 的 `files` 段与 `wheel-summary.json`。

## 5. 验证证据（区分「独立输出构建」与「真正冷 pip 解析」）

归档目录：`local/pi-director/20260919/t12-2026-09-19T11-31-45-190Z/`。

- **宿主 Python 回归**（非 APK 内 3.8 import）：`Test-Bd2Python` exit 0，96 discovered/
  executed/passed，failed/errors/skipped/notRun 均 0。日志 `python-regression.log`；
  报告 `local/refactor-artifacts/test-results/20260919-193157-python-test__.py/report.json`
  （sha256 `cc77226b25b9f10c89db8a12550a105e157641ef5420a7362e147df427f3e9ad`）。
- **冻结产物/夹具完整性**：8 产物 + 20 夹具哈希全匹配，`frozen-integrity.json`。
- **独立输出构建（不等于冷 pip）**：最初的 `:app:assembleDebug --no-build-cache
  --project-cache-dir` 虽用了独立 buildDir/project cache，但 Chaquopy `pip_install.py`
  的 `--isolated` 会忽略 `PIP_CACHE_DIR`/`PIP_LOG`，输出出现 `Using cached`。
  **因此这次 assembleDebug 不能记为冷 pip 验收**。
- **真正冷 pip 解析**（r2）：按 Gradle 实际参数直接调 `chaquopy.pip_install`，显式传
  `--cache-dir`/`--log` 到全新专用目录，预建空 target，exit 0；`verified-cold-pip-r2-result.json`、
  `verified-cold-pip-r2-console.log`。首轮未预建 target 导致 exit 1，已留档
  `verified-cold-pip-result.json`（错误尝试，不与 r2 混用）。
- **冷解析一致性**：`inventory-comparison.json` — 版本集与历史基线相等；
  逐路径 SHA256 与本批构建、与原 `app/build` 均 **0 差异**（18 dists / 472 files）。
- **assembleDebug**：exit 0，46 任务实执，`BUILD SUCCESSFUL in 3m 20s`；`build-python-summary.json`
  与 `assemble-debug-daemon.log`（真正正文）。`cold-assemble-debug.log` 只是 PowerShell
  transcript 壳，不含 native 正文，不作唯一构建证据。
- **Debug APK 资产**：3 个 Debug APK 均 SHA256 归档，`debug-apk-audit.json`。

**2026-09-20 设备补充：** 当前 minified Release 在 x86_64 设备内运行 Python 3.8.18，
9 个直接依赖及生产入口/桥接均 import 通过；独立 native 句柄测试失败（`_lib=None`）。
arm64 仍未运行。详见 `evidence/T12.md` §7，T12 仍 **IN_PROGRESS**。

## 6. 第三方来源（vendored / 生成代码 / JNI / 压缩 JS）

权威清单以仓库根 `THIRD-PARTY-NOTICES.md` 与 `LICENSE`（GPL-3.0）为准；本节只
补充 T12 视角的来源/版本/本地修改线索。注意 `THIRD-PARTY-NOTICES.md` 只是仓库
**已有的许可/来源声明**，不是经独立验证过的 upstream 权威证明。本地文件
指纹（116 个，排除 `__pycache__`）见 `thirdparty-local-hashes.json` — **只能证明
本地内容，不能证明上游 commit**；无法证实的版本/patch/commit 仍明确未闭合。

### 6.1 vendored UnityPy

- 位置 `app/src/main/python/vendor/UnityPy/`；`__init__.py` 声明 `__version__ = "1.23.0"`；
  `LICENSE` 为 MIT（(c) 2019-2026 K0lb3）。上游：https://github.com/K0lb3/UnityPy。
- **对上游 1.23.0 官方 sdist 的逐文件只读审计已完成**（本批新增），完整结果见
  `docs/refactor/unitypy-upstream-audit.md`。sdist SHA256
  `396e2bf8dea49b2bfd6309f5c790b2a5838613be037094bb55e6076c4852a890`。摘要：本地 110 /
  上游 104 文件；原始 identical 23 / changed 81 / local_only 6 / upstream_only 0；CRLF
  归一化后为 **68 个仅换行差异 + 12 个真实文本补丁 + 1 个二进制变化**。
- **12 个真实文本补丁**（逐条用途见审计 §3）：`export/Texture2DConverter.py`（ASTC 解码
  改走 `unpacker.decompress_astc_ctypes`）、`files/BundleFile.py`（磁盘溢出 SpillWindow）、
  `files/ObjectReader.py`、`files/SerializedFile.py`、`files/WebFile.py`、
  `helpers/CompressionHelper.py`（新增流式压缩）、`helpers/MeshHelper.py`、
  `helpers/PackedBitVector.py`、`helpers/Tpk.py`、`helpers/TypeTreeGenerator.py`、
  `helpers/__init__.py`、`streams/EndianBinaryWriter.py`（新增 `write_stream`）。
- **6 个本地新增文件**：`LICENSE`、`__main__.py`、`cli/__init__.py`、`cli/update_tpk.py`、
  `helpers/UnityVersion.py`、`lib/README.MD`（上游 sdist 均无对应路径）。
- `classes/generated.py` 确认**仅 CRLF 换行变更**（338KB→350KB 全部来自行尾膨胀），
  归一化后与上游逐字节相同，不算算法补丁。原始 SHA256 全部保留在审计 JSON。
- **LICENSE 逐字节仍与上游不同**：本地 `(c) 2019-2026`，上游（同一 sdist 根目录
  `unitypy-1.23.0/LICENSE`，非单独网络抓取）`(c) 2019-2021`，且官方 sdist 的 `UnityPy/`
  包内无 `LICENSE` 路径；即便 CRLF 归一仍不同。逐行已确认唯一差异为版权年份，MIT
  正文逐字相同。仅陈述文本事实，**不推断授权法律结论**。
- **`resources/uncompressed.tpk` 是唯一二进制变化**，本地生成/更新来源无仓内证据 →
  **生成来源 UNKNOWN**，不杜撰。2026-09-20 已将当前 `.tpk` 原始字节匹配到本地保存的
  BDroid_X 上游提交 `566990cf1290a8d718b0ddd596b07ca7c36794c8`；这是分发来源证据，
  不是原始生成输入或生成 commit 证明。FMOD 库则不同：上游 sdist 本就内含 `lib/FMOD/**`，19 个常规
  文件（8 二进制 + 11 空 `__init__.py`）均 raw identical（见 `unitypy-upstream-audit.md`
  §6），**字节可溯源到 sdist**；仅原始编译 commit / 声称版本 2.00.10 / 授权法律
  性质仍 UNKNOWN。固定版本号 `1.23.0` 不等同「完全原版」（有上述 12 个补丁）。
- UnityPy 可选加速 `UnityPyBoost`（C 扩展）在本项目缺失时回退为 `None`（见
  `helpers/ArchiveStorageManager.py`、`helpers/MeshHelper.py`）。
- `config.FALLBACK_UNITY_VERSION` 在各入口被设为 `'2022.3.22f1'`（本项目代码）。

### 6.2 生成/手写代码

- `app/src/main/python/maintenance_info_pb2.py` **不是 protoc 生成**：运行时用
  `descriptor_pb2` 手工构造 descriptor 并注册进独立 pool。字段名/编号对齐游戏
  客户端线上协议，属接口事实；App 只消费 `MaintenanceInfoResponse` 解析。
  protobuf 运行时由 pip 依赖 protobuf 5.29.6 提供。

### 6.3 JNI / native .so

- `THIRD-PARTY-NOTICES.md` 登记：`libastcenc.so` ← astc-encoder（Apache-2.0，Arm Limited）；
  `libtexture2ddecoder.so` ← texture2ddecoder（MIT，(c) 2020 K0lb3）。
- **当前源树实际分布（2026-09-21）**：两 ABI 均有 `libastcenc.so` + `libtexture2ddecoder.so`。x86_64 decoder 已设备 LoadLibrary PASS。arm64 真机 import / LoadLibrary 仍未执行。T12 保持 IN_PROGRESS。
- **2026-09-20 分发来源复核**：2026-09-20「三个 JNI 都与 BDroid_X 提交 `566990cf1290a8d718b0ddd596b07ca7c36794c8` 逐字节相同」只对当时的文件成立。当前 `libtexture2ddecoder.so` 是后来按上面的固定源码重编的，不能再算进那条旧结论。`libastcenc.so` 没有新的源码提交或构建配方，原始源码 commit / 构建步骤仍是 UNKNOWN。
- `libtexture2ddecoder.so` 的两份 ABI 已包含在提交 `e993800`。来源是 K0lb3/Texture2DDecoder 固定提交 `3ebc3b758bd6b1a3108b50148f7998ee34f058c6`、仓库补丁 `tools/native/texture2ddecoder-c-linkage.patch`、NDK `26.3.11579264`。细节以 `docs/refactor/native-texture-decoder.md` 和 `tools/native/texture2ddecoder-source.json` 为准。不要再说「x86_64 库未提交」。
- 证据：`local/pi-director/20260920/continue-2026-09-20T02-12-54-898Z/native-origin-local-audit.json`；
  `.tpk` 分发比对见同目录 `other-thirdparty-origin-local-audit.json`。
- `repacker/repacker.py` 头声明：0.3.0 起已全新实现，仅对齐 astc-encoder 公开 C ABI
  与 Spine atlas 公开格式，历史上参考过 kxdekxde/browndust2-repacker-android（GPL-3.0）。

### 6.4 压缩 JS / 预览运行时

- `assets/spine-viewer/js/pixi.min.js`：PixiJS **v6.5.10**（文件头 banner，MIT，
  Compiled 2023-07-06）。2026-09-20 另从 npm 官方注册表取得固定版本 6.5.10 的元数据与
  tarball，下载体 SHA1 与 SHA512 SRI 均匹配元数据；本地 JS 与包内
  `package/dist/browser/pixi.min.js` 仅 CRLF 差异，归一化后字节相同。
  元数据 `gitHead=8cdbf55064b7adc05f65c51e177f1c22f7329f0f`；tarball SHA256
  `f7830312c02742e1a29a31ac3ff3fdf3b264d8a772a0678b9a07a3b6d77474a0`。
  证据为上述目录 `pixi-source-metadata.json` / `pixi-upstream-comparison.json`；
  只做哈希校验，**未验证 npm 元数据签名或从源码重建压缩产物**。
- **pixi-spine 3.1.2 不随包分发**：专有 SPINE-LICENSE 与 GPLv3 不兼容，首次预览时
  从 npm CDN（`cdn.jsdelivr.net/npm/pixi-spine@3.1.2`）下载到私有目录（见
  `SpineRuntimeRepository.kt`、`preview.html`）。
- vendored UnityPy 自带 `lib/README.MD` 记有 FMOD Engine 2.00.10 与 Linux execstack 清除。
  **FMOD 库字节已溯源到上游 sdist**（sdist 本就内含，19 常规文件 raw identical），
  但原始编译 commit / 声称版本 / 授权法律性质 UNKNOWN（见 `unitypy-upstream-audit.md` §6）。
  **FMOD 已核实随 3 个 Release APK 的 `app.imy` 分发**（`fmod-apk-audit.json`：每包
  `fmodEntries=30` = 11 目录条目 + 19 常规文件，19 文件 `localExactMatch=true`，8 二进制
  合计 12,381,948 未压缩字节，非 APK 体积）；主控已复算三包 APK 本体 SHA256，
  与旧审计记录一致（`fmod-release-recheck.json`，仅对应 2026-09-19 产物），
  设备上 runtime 是否实际加载未验证。

## 7. 当前缺口（T12 未关闭）

- arm64 设备内目标解释器 import 未执行；x86_64 当前 Release 实际 Python 3.8.18 import 已通过。
- T11 已本地验收完成（2026-09-19 受控 `assembleRelease` 通过）；T12 自身跨 ABI 设备内 import 与 native 可用性门槛仍未关闭。
- vendored UnityPy 对上游 1.23.0 逐行差异**已完成只读审计**（见
  `docs/refactor/unitypy-upstream-audit.md`：12 真实补丁 + 6 新增 + 1 二进制）；FMOD 库
  字节已溯源到 sdist 且已核实随 3 个 Release APK 分发。但
  `resources/uncompressed.tpk` 的原始生成输入仍是 UNKNOWN。FMOD 原始编译 commit、声称版本 2.00.10 和授权法律性质仍是 UNKNOWN。不要推断许可证结论。设备上 runtime 加载未验证；`libastcenc.so` 原始源码 commit/构建步骤仍缺。
- x86_64 `libtexture2ddecoder.so` 已补编并在白名单设备 PASS。arm64 真机 import / LoadLibrary 仍未执行。T12 保持 IN_PROGRESS。


## 8. 2026-09-22 来源口径更正

1. `libtexture2ddecoder.so` 的两份 ABI 已包含在提交 `e993800`。来源是 K0lb3/Texture2DDecoder 固定提交 `3ebc3b758bd6b1a3108b50148f7998ee34f058c6`、仓库补丁 `tools/native/texture2ddecoder-c-linkage.patch`、NDK `26.3.11579264`。细节以 `docs/refactor/native-texture-decoder.md` 和 `tools/native/texture2ddecoder-source.json` 为准。不要再说「x86_64 库未提交」。
2. 2026-09-20「三个 JNI 都与 BDroid_X 提交 `566990cf1290a8d718b0ddd596b07ca7c36794c8` 逐字节相同」只对当时的文件成立。当前 `libtexture2ddecoder.so` 是后来按上面的固定源码重编的，不能再算进那条旧结论。`libastcenc.so` 没有新的源码提交或构建配方，原始源码 commit / 构建步骤仍是 UNKNOWN。
3. `resources/uncompressed.tpk` 的原始生成输入仍是 UNKNOWN。FMOD 原始编译 commit、声称版本 2.00.10 和授权法律性质仍是 UNKNOWN。不要推断许可证结论。
4. arm64 真机 import / LoadLibrary 仍未执行。T12 保持 IN_PROGRESS。
5. 2026-09-22 用 NDK 26.3 的 `llvm-readelf` / `llvm-strings` 复核当前两份 `libastcenc.so`：SONAME 分别是 `libastcenc-neon-shared.so` 和 `libastcenc-sse4.1-shared.so`，NEEDED 只有 libm/libdl/libc。两份都嵌入 `/workspaces/Ark/astc-encoder-src` 与 NDK `26.1.10909125`、Clang 17。仓库内没有对应源码，不能把这些字符串当成上游 commit。结论仍是 UNKNOWN。
6. 同日复核 FMOD 与 `.tpk`，细节见 `unitypy-upstream-audit.md`。README 的 2.00.10 没有出现在任何一个 FMOD 二进制里；Windows x86/x64 另有 UTF-16 `2.0.22 (build 122614)`，不能外推到 Linux/Darwin。`.tpk` 能解析出版本表，但 `update_tpk.py` 的 nightly master URL 不能钉住生成 commit。两项都仍是 UNKNOWN。
