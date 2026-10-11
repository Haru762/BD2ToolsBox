# 许可证依赖清单（T37）

- 日期：2026-10-11。
- 工具：AboutLibraries `13.1.0`（`com.mikepenz.aboutlibraries.plugin.android` + `aboutlibraries-compose-m3`），
  与 compileSdk 36 / AGP 8.11.1 / Compose BOM 2025.08.00 同代（T13 兼容探针已验证该组合）。
- 生成方式：**绑定到构建 variant**，不是手工跑一次：
  - `:app:prepareLibraryDefinitionsDebug` / `:app:prepareLibraryDefinitionsRelease`
    导出依赖元数据；
  - 合并进 `:app` 的 `res/raw/aboutlibraries.json`（`app/build/generated/aboutLibraries/<variant>/res/raw/`），
    随 APK 分发，供设置页 AboutLibraries 组件离线读取。
- 配置：`config/aboutlibraries/libraries/*.json`（每文件一个**非 Maven** 组件定义），
  `collect { configPath.set(file("../config/aboutlibraries")); fetchRemoteLicense = false }`。
  关闭远程抓取以保持离线可复现；SPDX 文本由插件/库内置映射提供。

## 1. 自动收集的 Gradle 运行时依赖

- 来源：`:app` 的 `releaseRuntimeClasspath`（含 AndroidX、Compose、Hilt、Shizuku、
  kotlinx.serialization、AboutLibraries 自身等）。
- 数量：release 变体合并后 `aboutlibraries.json` 共 **138** 条（含下面 19 条自定义）。
- 每条含 `uniqueId` / `artifactVersion` / `licenses`（SPDX）/ `developers` / `website` 等；
  以构建产物 `aboutlibraries.json` 为准，本文件不复制其全部内容（避免两处漂移）。

## 2. 非 Maven 组件（自定义定义）

`config/aboutlibraries/libraries/` 下 19 个定义，与 APK 内实际分发的字节对应：

### Python（Chaquopy 打包）

| uniqueId | 组件 | 版本 | 许可 | 版权 |
|---|---|---|---|---|
| `python:cpython` | CPython | 3.8 | PSF-2.0 | Python Software Foundation |
| `python:redustx` | ReDustX | — | MIT | Jelosus2 |
| `python:unitypy` | UnityPy | — | MIT | K0lb3 |
| `python:pillow` | Pillow | 9.5.0 | HPND | Jeffrey A. Clark and contributors |
| `python:lz4` | lz4 (python) | 4.3.3 | BSD-3-Clause | Jonathan Underwood |
| `python:brotli` | Brotli (python) | 1.1.0 | MIT | The Brotli Authors |
| `python:fsspec` | fsspec | 2023.6.0 | BSD-3-Clause | Martin Durant |
| `python:attrs` | attrs | 23.1.0 | MIT | Hynek Schlawack |
| `python:requests` | requests | 2.31.0 | Apache-2.0 | Kenneth Reitz |
| `python:beautifulsoup4` | Beautiful Soup | 4.12.2 | MIT | Leonard Richardson |
| `python:protobuf` | protobuf (python) | 4.23.4 | BSD-3-Clause | Google Inc. |
| `python:tqdm` | tqdm | 4.66.1 | MPL-2.0 / MIT | tqdm developers |

版本来源：`app/requirements-android.txt`（Chaquopy pip 直接依赖锁定）。

### 原生库（jniLibs）

| uniqueId | 组件 | 许可 | 版权 |
|---|---|---|---|
| `native:astc-encoder` | astc-encoder | Apache-2.0 | Arm Limited |
| `native:texture2ddecoder` | texture2ddecoder | MIT | K0lb3 |
| `native:lz4` | LZ4 (C) | BSD-2-Clause | Yann Collet |
| `native:brotli` | Brotli (C) | MIT | The Brotli Authors |
| `native:fmod` | FMOD Ex / FMOD Studio | **UNKNOWN**（见 §4） | Firelight Technologies Pty Ltd |

### 预览页（WebView / JS）

| uniqueId | 组件 | 版本 | 许可 | 分发 |
|---|---|---|---|---|
| `js:pixijs` | PixiJS | 6.5.10 | MIT | 随 APK（`assets/spine-viewer/js/pixi.min.js`） |
| `js:pixi-spine` | pixi-spine | 3.1.2 | SPINE-LICENSE（专有） | **不随包**，运行时从 npm CDN 取回应用私有目录 |

## 3. 许可全文位置

- 随仓库分发：`licenses/ReDustX-LICENSE.txt`、`licenses/UnityPy-LICENSE.txt`、
  `licenses/astc-encoder-LICENSE.txt`、`licenses/texture2ddecoder-LICENSE.txt`、
  `licenses/pixijs-LICENSE.txt`；根 `LICENSE` 为项目自身的 GPL-3.0。
- SPDX 标准许可（MIT / Apache-2.0 / BSD-* / PSF-2.0 / HPND / MPL-2.0）由 AboutLibraries
  内置映射提供，APK 内 `aboutlibraries.json` 以 SPDX id 引用。
- 人类可读的完整清单与说明：根 [`THIRD-PARTY-NOTICES.md`](../../THIRD-PARTY-NOTICES.md)。

## 4. 已知缺口（不静默）

- **FMOD 授权状态 UNKNOWN**：T12 记录为「已接受的 UNKNOWN（分发字节已钉住）」；
  正式对外分发前必须向 Firelight Technologies 确认授权。定义里 `licenses: ["FMOD-LICENSE"]`
  且**未内嵌文本**（本地无全文）。
- **pixi-spine 不随包**：不构成分发义务；定义列出只为清单完整，`SPINE-LICENSE` 无内嵌文本。
- 自动收集只覆盖 Maven/Gradle 依赖；Python vendored、JNI、JS 三类由 §2 的自定义定义补齐，
  不把「自动扫描」当作所有非 Maven 许可义务已完成。
- 与 `excludes` / `META-INF` 打包规则、Spine/pixi 运行时获取边界、根 GPL LICENSE 的对应关系
  由 T12/T37 的源码核对记录支撑（见 T12 证据与 THIRD-PARTY-NOTICES.md）。

## 5. 复验

```powershell
$Root = 'D:/bd2 mod/worktrees/bdroid-android-refactor'
. (Join-Path $Root 'tools/verification.ps1') -ProjectRoot $Root
Invoke-Bd2Gradle :app:tasks --all --console=plain | Select-String 'LibraryDefinitions|aboutLibraries'
Invoke-Bd2Gradle :app:assembleRelease --console=plain
# 生成的离线清单（随 APK 的 res/raw）：
#   app/build/generated/aboutLibraries/<variant>/res/raw/aboutlibraries.json
```

- 2026-10-11：`prepareLibraryDefinitionsRelease` 成功；生成清单含 **19** 条自定义组件 +
  自动收集的 Gradle 依赖，共 138 条；`:app:assembleRelease`（受控 debug 签名）成功。
- T49 建立 arm64/x64 两个最终 flavor 后必须复验（本阶段尚无那两个 flavor task）。
