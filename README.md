[![GitHub release](https://img.shields.io/github/v/release/Haru762/BD2ToolsBox)](https://github.com/Haru762/BD2ToolsBox/releases/latest)

[简体中文](./README.md) | [English](./README.en.md)

# BD2 ToolsBox

**在手机上直接管理和装入 Brown Dust 2 的 mod，不需要电脑。**

---

## 功能

*   **全程手机操作**：解包、纹理转换（ASTC）、重打包全部在安卓设备上完成。
*   **按角色浏览**：自动扫描 mod 文件夹，按游戏角色分组，防止冲突。
*   **批量处理**：一次勾选多个不同分组的 mod，一起转换装入。
*   **Shizuku 集成**：转换完一键移入游戏目录。
*   **动画预览**：长按列表里的 mod，直接播放它的 Spine 动画。
*   **攻略板块**：GameKee 棕尘2分区攻略——分类树、搜索、评论区，正文本地缓存，可离线重读。
*   **兑换码板块**：兑换码列表 + 官方通道一键兑换，填一次游戏内昵称，奖励由官方直发游戏内邮箱，不用登录、不用进游戏。
*   **内置工具**：图集合并、游戏 bundle 解包。

---

## 上手

### 需要

*   Android 8 及以上。
*   已安装官方最新版 Brown Dust 2。
*   [Shizuku](https://shizuku.rikka.app/)——扫描与移入游戏目录都靠它。没有 Shizuku 能转换，但装不进游戏。

### 安装

1.  从 [Releases](https://github.com/Haru762/BD2ToolsBox/releases) 下载最新 APK 安装。模拟器上务必强制 x86_64，否则会走 ARM 转译闪退：
    模拟器装 `BD2ToolsBox-<版本>-android-x86_64.apk`（手机装 arm64-v8a 版；universal 通用包在模拟器上仍需 `--abi x86_64`）
2.  启动 Shizuku，按首次引导完成授权。
3.  点「**添加 mod 文件夹**」，选到你放 mod 的目录。

> 建议在手机存储里建一个专用目录（如 `.BD2_Mods`），把所有 mod 集中放在里面。

---

## mod 的目录结构（重要）

每个 mod 一个文件夹，里面的文件名必须与游戏原始资源**完全一致**。zip 包也认，任意层级都会被找出来；子文件夹会自动往下找，选合集总目录也可以。

> **⚠️ 注意：所有文件名必须全小写**（`char000104.png` 而不是 `Char000104.png`），且 `.atlas` 里引用的图片文件名也得是小写，否则游戏可能崩溃。

**目录结构示例：**
```
.BD2_Mods/
├── Lathel_DarkKnight_IDLE/
│   ├── char000104.skel      （骨架，或 .json）
│   ├── char000104.atlas     （图集映射）
│   └── char000104.png       （贴图）
└── Another_Mod/
    └── ...
```

---

## 装 mod

1.  **勾选**：在列表里勾上要装的 mod。
2.  **转换**：点「**开始**」——app 会自动下载所需的原版文件并重打包，弹窗显示实时进度。
3.  **装入**：转换完点「**一键装入游戏**」（需 Shizuku 运行中）——文件移入游戏目录，**重启游戏后生效**。
4.  **卸载**：「一键卸载全部 mod」或单个 mod 的「卸载」，还原原版。

手动搬运时的目标路径：`/Android/data/com.neowizgames.game.browndust2/files/UnityCache/`

---

## 其他工具

### 动画预览
不确定 mod 装上什么效果？**长按**列表里的 mod 即可打开预览播放。

### 图集合并（自救工具）
装入后贴图错乱时：只勾选出问题的那个 mod，点合并。原文件会备份进 `.old` 子目录，重新装入即可。

### bundle 解包
想提取原版游戏文件：不勾任何 mod，点解包图标，选要解的 `__data` 文件，产物保存在 `Download/outputs`。

---

## 常见问题

**Q：列表里看不到我的 mod。**
检查「添加 mod 文件夹」选的目录对不对；mod 要按上面的结构放；文件名要和游戏原始资源一致。

**Q：装完 mod 游戏闪退。**
多半是这两种：贴图数量和原版对不上（app 一般会自动修）；或文件名没全小写（见上文警告）。先试试「图集合并」工具；还不行就是 mod 本身损坏或不兼容。

**Q：装入失败。**
常见原因：网络不好（原版文件没下载下来）、mod 文件名不对、转换出错。看弹窗里的报错信息定位。

**Q：装完后游戏里贴图错乱。**
mod 不完整或不兼容安卓版。完整的角色 mod 需要三个文件：`.png`、`.atlas`、`.skel`（或 `.json`）。

---

## 开发与验证

### 一条命令跑完本地验证（不需要设备）

```powershell
& (Join-Path $Root 'tools/verification.ps1') -ProjectRoot $Root -Mode LocalAll
```

依次跑：依赖解析 → 全部 JVM 单测 → 全部 Python 测试 → 两个 ABI 的 release 构建 →
静态审计（分层架构、APK 组成/ABI/native/资源/DEX 键名、native ABI）。
**任一步失败即非零退出；skip 不算通过。** 设备测试**不**包含在内，必须显式调用
`Test-Bd2Device` / `Test-Bd2ReleaseSmoke`，不会默认往已连接设备上装东西。

完整清单、干净构建与可复现性边界见 [`docs/refactor/build-and-verify.md`](docs/refactor/build-and-verify.md)。

### 工具链与版本

| 项 | 值 | 位置 |
|---|---|---|
| Kotlin / AGP / Gradle | 2.2.20 / 8.11.1 / 8.13 | `gradle/libs.versions.toml` |
| Chaquopy（Python 运行时） | 16.1.0，目标解释器 **3.8** | `app/build.gradle.kts` |
| compileSdk / minSdk / targetSdk | 36 / **29** / 34 | `gradle/libs.versions.toml` |
| NDK（native 剥离） | 26.3.11579264 | `gradle/libs.versions.toml` |
| App 发布版本 | `versionName` / `versionCode` | **`gradle/version.properties`**（可用 `APP_VERSION_NAME` 覆盖） |
| Python 依赖锁定 | 9 个直接依赖 `==` 锁定 | `app/requirements-android.txt`（解析结果见 `docs/refactor/python-dependencies.md`） |

构建机 Python 按 `local.properties` 的 `chaquopy.python` → `CHAQUOPY_PYTHON` → `PATH`
依次查找；Android SDK 读 `local.properties` 的 `sdk.dir`。**不写死绝对路径。**

### 两个 ABI 分开分发

| ABI | flavor | 产物 |
|---|---|---|
| arm64-v8a（手机） | `arm64` | `app/build/outputs/apk/arm64/release/app-arm64-release.apk` |
| x86_64（模拟器） | `x64` | `app/build/outputs/apk/x64/release/app-x64-release.apk` |

不再产出 universal 包：单 ABI flavor 让 `lib/` 与 Chaquopy 的 `assets/IMY` **都只含当前架构**。

### 主题与壁纸

浅色 / 深色 / 跟随系统三选一，另有七套配色预设与「跟随壁纸取色」（Android 12+）。
自定义壁纸只存 SAF URI（不拷图进应用目录），并可在设置里调遮罩强度。
**壁纸只在背景层可见；列表、工具栏、弹窗等文字密集区一律用不透明底色**，
不让图片穿过文字。

### 后台限制与数据恢复

*   转换 / 装入 / 扫描 / 预解包都挂在**前台服务**上（通知栏可见进度）。
  系统拒绝启动前台服务时**明确失败并提示**，不会照常开跑。
*   进程被杀后重启：只读操作日志做**分类**（中断 / 待核对 / 结果未知 / 已提交），
  在界面上说清下一步，**不自动重放**安装步骤、不改账本、不碰游戏文件。
  已提交的事实不会被覆盖。
*   游戏文件的提交是**同目录暂存 + 校验 + 原子替换**，不是事务：目录树拷贝时
  每个文件各自独立提交，失败会按目标分别上报。

磁盘格式与迁移规则见 [`docs/refactor/data-contracts.md`](docs/refactor/data-contracts.md)。

### 许可证

应用内「设置 → 开源许可」由 AboutLibraries 生成，数据来自构建期产出的
`res/raw/aboutlibraries.json`（含 SPDX 全文，**离线可读**）。
组件来源与义务见 [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md)，
清单与全文位置见 [`docs/refactor/license-inventory.md`](docs/refactor/license-inventory.md)。

---

## 致谢

本项目站在这些项目之上：

*   [browndust2-repacker-android](https://codeberg.org/kxdekxde/browndust2-repacker-android)（GPL-3.0）——重打包与纹理压缩核心。
*   [ReDustX](https://github.com/Jelosus2/ReDustX)（MIT）——json 转 skel 逻辑与 CDN 下载方法。
*   [UnityPy](https://github.com/K0lb3/UnityPy)（MIT）——Unity 资源读写。
*   [astc-encoder](https://github.com/ARM-software/astc-encoder)（Apache-2.0）——官方 ASTC 纹理编码器。
*   [BD2ModManager](https://github.com/bruhnn/BD2ModManager)（GPL-3.0）——角色元数据（中文名 / 性别 / 头像）来源。
*   数据来源：[browndust2modding.pages.dev](https://browndust2modding.pages.dev/characters)（角色表）、[Brown-Dust-2-Asset](https://github.com/myssal/Brown-Dust-2-Asset)（头像与立绘）。

各组件在代码里的位置与完整清单见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。

---

## 许可

本项目按 **GNU General Public License v3** 分发，全文见 [`LICENSE`](LICENSE)。

选择 GPLv3 是有意为之：角色元数据提取自 GPL-3.0 项目（见致谢），也想与社区工具生态保持同样的开放义务。有一点需要注意（详见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)）：

-   `pixi-spine` 使用与 GPL 不兼容的 SPINE-LICENSE，**不随 APK 分发**——首次使用动画预览时由 app 从 npm CDN 自行下载。

*Brown Dust 2* 及其全部游戏素材版权归 Neowiz / Round8 Studio 所有。本工具只修改本地游戏缓存，不分发任何游戏素材。
