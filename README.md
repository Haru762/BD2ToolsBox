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
    `adb install --abi x86_64 BD2ToolsBox-0.2.2-android-universal.apk`
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
