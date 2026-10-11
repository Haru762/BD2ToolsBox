# UI 组件契约（T41）

- 日期：2026-10-11。
- 目的：把「真正复用的内容底色、字体层级、间距、形状、按钮优先级、状态标签、
  空/加载/失败区」写成**可判定**的契约，各页不再各自散落自定义 alpha 与 hex。
- 原则：使用 Material 语义色；明暗模式下同一语义保持一致的对比关系；不引入大 UI 套件、
  额外字体包或装饰动画；不用机械卡片化替代清晰界面。

## 1. 颜色与文字

| 用途 | 取值 | 禁止 |
|---|---|---|
| 内容面底色 | `colorScheme.background`（[AppScreenSurface]） | 页面自定义 `Color(0x…)` 底色 |
| 卡片/次级容器 | `colorScheme.surfaceContainer` / `surfaceVariant` | 自算 alpha 叠色 |
| 正文 | `colorScheme.onSurface` / `onBackground` | 硬编码黑/白 |
| 辅助信息 | `colorScheme.onSurfaceVariant` | 降低整块 alpha 冒充「次要」 |
| 失败/危险 | `colorScheme.error` / `errorContainer` / `onErrorContainer` | 用主色代替错误色 |
| 成功 | `colorScheme.tertiaryContainer` + `onTertiaryContainer` | 自造绿色 hex |

文字层级只用 `MaterialTheme.typography` 的既有档位（titleMedium / bodyMedium / bodySmall /
labelMedium），不新增字号常量。

## 2. 状态标签 vs 操作按钮

- **状态标签**（[StatusBadge]）：表达「现在是什么状态」，**不可点击**、不承担动作。
  语义由 [StatusTone] 决定，映射唯一落在 `StatusTone.forInstallState`，页面不各自判断。
- **操作按钮**：承担动作（确认、取消、重试、移除…）。主操作在右且用强调色，
  次要/取消在左。
- 读屏/测试语义：状态标签 `contentDescription = "状态：<文案>"`。

## 3. 弹窗与反馈（[AppDialog]）

- **重要结果不只靠 Toast 或颜色**：需要确认或必须知晓的结果用弹窗；普通一行提示用
  Snackbar/Toast，不滥用阻断弹窗。
- 主次位置固定：确认在右、取消/关闭在左。
- **失败是否可重试**由 `onRetry != null` 表达（`null` = 不可重试），文案由调用方给；
  「重试」与「确定」是两个不同按钮，不混用。

## 4. 任务进度（[TaskProgressPanel]）

- 进度由**任务宿主**（协调器/executor）持有，面板只显示。
- `progress == null` = 不确定进度（转圈）；`0f..1f` = 确定进度（进度条）。
- 取消只在**安全阶段**开放：`cancelEnabled` 控制按钮可用性；不可取消时按钮**可见但禁用**，
  不用隐藏冒充。
- 文案含当前目标 + 细粒度阶段，避免只有一个不动的百分比。
- 语义：`contentDescription = "任务进度：<title>"`。

## 5. 空 / 加载 / 失败区

- 三态互不冒充：空态文案说明「确实为空」，失败态说明失败原因与是否可重试，加载态有明确指示。
- 空列表不得与「读取失败」共用同一句文案（T05/T23 的错误分类在 UI 层的对应）。

## 6. 页面特殊业务不进通用组件

通用组件（本节三个）只承载通用语义；页面特有的业务分支不塞进通用组件的开关参数，
留在各自页面。

## 7. 复验

```powershell
$Root = 'D:/bd2 mod/worktrees/bdroid-android-refactor'
. (Join-Path $Root 'tools/verification.ps1') -ProjectRoot $Root
Test-Bd2Unit -TestClass 'com.bd2toolsbox.ui.theme.ThemeContractTest'
Test-Bd2Device -TestClass 'com.bd2toolsbox.ui.ComponentContractTest' -Abi 'x86_64'
```
