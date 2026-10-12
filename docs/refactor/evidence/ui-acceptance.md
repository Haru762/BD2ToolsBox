# 原生 UI 验收（T58）

本文记录**实际测到的东西**与**明确没测的东西**。截图在
[`screenshots/`](screenshots/)，索引 [`screenshots/index.json`](screenshots/index.json)。

- 日期：2026-10-13
- 被测 APK：minified release `x86_64`，SHA-256 `d70ab6a15c13d6c26807921c34c549f9449586e83bdaed8c5c3e2384758a12ff`
- 设备：MuMu `127.0.0.1:16384`（x86_64 / API 32 / **1920×1080 横屏**）
- 采集工具：`tools/capture_ui_screenshots.py`（`adb screencap -p` + `pull`）
- 核对工具：`tools/verify_ui_screenshots.py`（PIL）+ `ThemeContrastTest`（JVM）

## 1. 对比度：154 对配色全部达标（实测值）

`ThemeContrastTest` 用**WCAG 2.1 规范公式**（先把每个 sRGB 通道线性化再加权）核对
**7 个预设 × 明/暗 × 11 对配色 = 154 对**：

| 类别 | 配色对 | 要求 | 实测最紧的一对 |
|---|---|---|---|
| 正文 | `onBackground/background`、`onSurface/surface`、`onSurfaceVariant/surface` | ≥ 4.5:1 | `onSurfaceVariant/surface` 9.19:1（紫浅色） |
| 容器文字 | `on*Container/*Container`（4 对） | ≥ 4.5:1 | 见完整表 |
| 大字与图标 | `onPrimary/primary`、`onSecondary/secondary`、`onError/error` | ≥ 3.0:1 | 6.46:1（紫浅色 `onPrimary`） |
| 控件边界 | `outline/surface` | ≥ 3.0:1 | **4.44:1**（橙浅色）← 全局最紧 |

**154 对全部达标，0 对不足。** 最紧的是「橙浅色 `outline/surface` = 4.44:1」（要求 3.0），
余量 1.48×。完整 154 行原始表在 `local/t58-contrast-table.txt`（一次性产物，忽略）。

### 1.1 顺带修掉的判定缺陷

`ThemeContractTest` 里已有的 `luminance()` 是**朴素亮度** —— 直接在 gamma 编码分量上
加权，不是 WCAG 的相对亮度。两者在中间调上差 20% 以上（对白底的中灰：规范 4.03:1、
朴素 1.90:1），用它判「≥4.5:1」会给出错误结论。新测试实现了规范公式，并加了一条
**反向守卫**：如果两者算出的值接近到 1.0 以内，说明实现被换回朴素亮度了，直接失败。

## 2. 截图：7 张，全部来自本轮 release APK

| 文件 | 应用内明暗 | 预设 | 字体 | 平均亮度 | 颜色数 | 网格标准差 |
|---|---|---|---|---|---|---|
| `light-default.png` | LIGHT | 紫 | 1.0 | 0.4492 | 663 | 26.11 |
| `dark-default.png` | DARK | 紫 | 1.0 | 0.0775 | 615 | 13.57 |
| `light-teal.png` | LIGHT | 青 | 1.0 | 0.4487 | 685 | 26.34 |
| `dark-graphite.png` | DARK | 石墨 | 1.0 | 0.0713 | 587 | 13.95 |
| `light-font1.3.png` | LIGHT | 紫 | 1.3 | 0.4638 | 735 | 30.38 |
| `light-font2.0.png` | LIGHT | 紫 | 2.0 | 0.4891 | 926 | 36.23 |
| `dark-font2.0.png` | DARK | 紫 | 2.0 | 0.1043 | 898 | 21.83 |

核对结果 **PASS**：每张都 ≥8 种颜色（不是单色/空窗口）、网格亮度标准差 ≥2.0
（有真实内容）、分辨率 1920×1080；且**浅色最暗 0.4487 > 深色最亮 0.1043**，
明暗切换确实生效。

## 3. 采集过程中发现的三个问题（都已修）

1. **系统深色不影响界面**：`cmd uimode night yes` 之后浅/深两张截图的 SHA-256
   **完全相同**。查明是 App 的 `theme_mode` 默认 `LIGHT`（`SettingsRepository` 注释：
   旧用户缺这个键时保持外观不变），这是**正确行为**；采集脚本因此改为直接改 App 自己的
   偏好。加了「同一次采集内截图不得重复」的守卫 —— 以后任何一维没生效都会被拦下。
2. **`run-as` 用不了**：采集的是 release 包，`run-as` 报 `package not debuggable`。
   改用 `su`（MuMu 上 `su -c id` 返回 uid=0）。**不用 debug 包代替** —— 那会让截图与
   交付产物脱钩。
3. **方向这一维本机测不了**：MuMu 固定 1920×1080 横屏，`settings put system user_rotation`
   不生效。脚本一度把请求竖屏的截图按计划写成「portrait」文件名 —— 那是把「没拍到」
   记成「拍到了」。现在计划里**不含方向**，限制如实写进 `index.json` 的 `limitations`。

## 4. 明确未验证（不勾 T58 的原因）

- **竖屏布局与旋转恢复**：本机只有横屏。需要真机或可竖屏的模拟器。
- **真实软键盘 / 刘海 / 手势导航条**：需要交互或真机。
- **TalkBack**：只做了点击范围与语义的机器检查（`AdaptiveAccessibilityTest` 8/8），
  没有真人用 TalkBack 走一遍。
- **任务面板收起/再打开、备份确认、兑换结果的观感**：语义与状态由
  `TaskPanelLifecycleTest` 6/6、`DestructiveActionConfirmationTest` 12/12、
  `CdkResultPresentationTest` 5/5 覆盖，但**没有对应截图**。
- **许可证页与阅读器页面**：`WebContentThemeTest` 6/6 覆盖 WebView 主题，
  **没有截图**。
- **壁纸状态（无/亮/暗/高纹理）**：`WallpaperReadabilityTest` 3/3 覆盖「表面恒不透明」，
  **没有壁纸截图**（本机未设置壁纸）。
- **arm64 真机**：未跑。
- **`UiAcceptanceTest`（计划指定的设备用例）未新建**：本轮的设备侧工作由上面的截图
  管线 + 已有的 Compose 用例承担；计划点名的那个类名没有落地，登记为偏离。

## 5. 复现命令

```powershell
# 1) 配色对比度（纯 JVM，无需设备）
. tools/verification.ps1 -ProjectRoot $Root
Invoke-Bd2Gradle --offline :app:testX64DebugUnitTest --tests 'com.bd2toolsbox.ui.theme.ThemeContrastTest'

# 2) 采集截图（需要设备；APK 必须是本轮构建的 release）
python tools/capture_ui_screenshots.py --abi x86_64

# 3) 核对截图
python tools/verify_ui_screenshots.py --json local/t58-screenshot-check.json
```
