# 第三方组件与许可

本项目整体按 **GNU General Public License v3** 分发，全文见根目录 [`LICENSE`](LICENSE)。
下面列出打包进 APK 或随源码分发的第三方组件。各许可全文在 [`licenses/`](licenses/) 目录。

---

## 两件必须先说清楚的事

### 1. 上游 BDroid_X 目前没有许可证

本项目从 [Ark-Repoleved/BDroid_X](https://github.com/Ark-Repoleved/BDroid_X) 改造而来
（`git clone --depth 1`，基线是 2026-04-27 的 `566990c`）。

截至撰写本文件时，该仓库**没有 LICENSE 文件**，GitHub API 的 `license` 字段为 `null`，
也就是默认保留全部权利。本项目选择 GPLv3 是因为它的转换核心本身源自 GPLv3 项目
（见下条），但严格说，来自 BDroid_X 原创部分的再分发授权尚未取得。

已经/应该做的：把来源标注清楚，并向上游提 issue 请其明确许可。
如果你是 BDroid_X 的作者并对此有异议，请开 issue，我们会立即配合处理。

相对上游的改动规模（2026-09-08 复核，包名重命名已做规范化对齐）：上游 16 个
Kotlin 文件中，包名规范化后仍完全相同的有 3 个（CharacterRepository / Color /
Type，共 185 行）；其余改动过的文件里残留约 3200 行相同（含 import、括号等
样板行）。其中 Shizuku 集成三件套（ShizukuManager / ModdingService /
ShizukuFileService）基本仍是上游原样，是尚未取得授权的最大一块。

### 2. Spine 运行时（pixi-spine）不随安装包分发

`pixi-spine` 使用 Esoteric Software 的 **SPINE-LICENSE** —— 一份专有的 source-available
许可，要求每个使用者自行持有 Spine Editor 授权。GPL 不允许对下游接收者附加这类额外条件，
两者不兼容。

因此 APK 里**不包含** pixi-spine：首次使用「预览动画」时，app 会从 npm CDN
（`cdn.jsdelivr.net/npm/pixi-spine@3.1.2`）取一份放进应用私有目录。
我们分发的只是 GPL 代码，Spine 运行时由用户自行从上游获取 ——
这与 Linux 发行版不打包专有编解码器、由用户自行安装是同一个做法。

实现见 [`SpineRuntimeRepository.kt`](app/src/main/java/com/bd2toolsbox/data/repository/SpineRuntimeRepository.kt)。
设置 → 预览缓存里可以看到它的状态并清除。

---

## 打包进 APK 的组件

### 转换核心（Python）

| 组件 | 位置 | 许可 | 版权 |
|---|---|---|---|
| [browndust2-repacker-android](https://codeberg.org/kxdekxde/browndust2-repacker-android) | `python/repacker/repacker.py` | **GPL-3.0** | kxdekxde |
| [ReDustX](https://github.com/Jelosus2/ReDustX) | `python/repacker/json_to_skel.py` | MIT | (c) 2025 Jelosus2 |
| [UnityPy](https://github.com/K0lb3/UnityPy) | `python/vendor/UnityPy/` | MIT | (c) 2019-2026 K0lb3 |

`repacker.py` 是本项目受 GPLv3 约束的直接原因 —— 它经 BDroid_X 改造后并入，
重打包与纹理压缩逻辑源自上游。上面两个文件的头部都补了来源与许可声明。

### 原生库

| 组件 | 位置 | 许可 | 版权 |
|---|---|---|---|
| [astc-encoder](https://github.com/ARM-software/astc-encoder) | `jniLibs/*/libastcenc.so` | Apache-2.0 | Arm Limited |
| [texture2ddecoder](https://github.com/K0lb3/texture2ddecoder) | `jniLibs/*/libtexture2ddecoder.so` | MIT | (c) 2020 K0lb3 |

### 预览页（WebView）

| 组件 | 位置 | 许可 | 版权 |
|---|---|---|---|
| [PixiJS](https://github.com/pixijs/pixijs) 6.5.10 | `assets/spine-viewer/js/pixi.min.js` | MIT | (c) 2013-2023 Mathew Groves, Chad Engler |
| [pixi-spine](https://github.com/pixijs/spine) 3.1.2 | **不随包**，运行时下载 | SPINE-LICENSE（专有） | (c) 2019-2020 Ivan Igorevich Popelyshev |

### Android 依赖（Gradle）

均为 **Apache-2.0**：AndroidX Core / Lifecycle / Activity Compose / DocumentFile、
Jetpack Compose（BOM 2024.06.00，含 Material 3 与 material-icons-extended）、
[Gson](https://github.com/google/gson) 2.10.1、
[Shizuku](https://github.com/RikkaApps/Shizuku) api + provider 13.1.5、
[compose-shimmer](https://github.com/valentinilk/compose-shimmer) 1.2.0。

### Python 运行时与包

| 组件 | 许可 |
|---|---|
| [Chaquopy](https://github.com/chaquo/chaquopy) 15.0.1 | MIT |
| CPython（由 Chaquopy 打包） | Python Software Foundation License |
| Pillow | MIT-CMU / HPND |
| requests | Apache-2.0 |
| beautifulsoup4、attrs、lz4、brotli | MIT |
| protobuf、fsspec | BSD-3-Clause |
| tqdm | MPL-2.0 + MIT |

Chaquopy 会把每个 wheel 自带的 `*.dist-info/LICENSE` 一并打进 APK，
所以这些包的许可全文随安装包分发。

---

## 数据来源

| 数据 | 位置 | 来源 |
|---|---|---|
| 角色附加信息（性别 / 联动 / 中文名 / 头像文件名） | `assets/character_meta.json` | 从 [bruhnn/BD2ModManager](https://github.com/bruhnn/BD2ModManager)（GPL-3.0）发行版 exe 内嵌的 JSON 提取，脚本见 `tools/extract_chars.py` |
| 角色表（角色名 / file_id / 皮肤名） | 运行时抓取 | [browndust2modding.pages.dev](https://browndust2modding.pages.dev/characters) |
| 角色头像与立绘 | 运行时下载 | [myssal/Brown-Dust-2-Asset](https://github.com/myssal/Brown-Dust-2-Asset) |
| 原版游戏资源 | 运行时下载 | Brown Dust 2 官方 CDN（`cdn.bd2.pmang.cloud`） |

《Brown Dust 2》及其全部游戏资源的版权属 Neowiz / Round8 Studio。
本项目只是一个修改本地游戏缓存的工具，不分发任何游戏资源。
