Method: dual-agent（A: 01a09fca-5a68-70f1-8b47-11770e526676 · B: 01a09fca-aa48-7d02-bbc2-af4d240dfa9b）。以下是源码级评审，不是真机视觉验收。

# UI 评审与库选型建议

日期：2026-09-14。生产代码、构建配置与分支均未修改；没有构建、安装或运行 App。

## 结论：保留 Compose + Material 3，但不建议完全维持现状

当前 UI 技术栈没有问题。需要重构的是交互和页面代码，而不是换一整套 UI 框架。

值得保留的部分：
- 「Mods／攻略／兑换码」有明确的文字导航。
- MOD 按角色、资源和安装状态组织，符合玩家的使用习惯。
- 已有主题、壁纸和动态配色，以及比较具体的 Shizuku 恢复说明。

视觉方向：保留“玩家工具箱”的身份，统一按钮、状态标签、间距和弹窗规范；角色图片与主题色负责特色，操作区域优先保证清晰。不必为了显得新而全面加毛玻璃、渐变或动画。这是建议方向，不是已经批准的视觉重设计。

## 比换库更值得先改的四件事

| 优先级 | 目前的问题 | 建议 |
|---|---|---|
| P1 | 预览准备和安装处理中，部分弹窗没有应用内退出入口，慢任务容易让人以为卡住 | 提供“收起，继续处理”和可重新打开的进度入口；取消只在安全阶段开放 |
| P1 | 兑换码把“全部账号处理完，包括终局失败”也显示成“已兑换” | 区分“兑换成功”“已处理但未成功”，允许查看各账号结果 |
| P2 | MOD 预览依赖长按菜单，或点击“已转换”状态标签；其他类似标签却不执行操作 | 提供明确的“预览／更多”入口，长按仅作为快捷方式 |
| P2 | “清空原版备份”直接执行，没有二次确认 | 明示数量、空间及重新下载的后果；普通可再生缓存不必一律增加确认 |

证据：
- [AppDialogs.kt](<D:/bd2 mod/bdroid_src/app/src/main/java/com/bd2toolsbox/ui/dialogs/AppDialogs.kt#L409>)：409–428 的准备态；874–875、973–986 的安装处理态关闭路径。
- [CdkScreen.kt](<D:/bd2 mod/bdroid_src/app/src/main/java/com/bd2toolsbox/ui/screens/CdkScreen.kt#L64>)：64–81 的 settledAll 定义及计算；250–255 传为 redeemed；343–345 显示“已兑换”。是静态语义问题，不冒充真实接口场景复现。
- [ModScreen.kt](<D:/bd2 mod/bdroid_src/app/src/main/java/com/bd2toolsbox/ui/screens/ModScreen.kt#L1069>)：1069–1074 的单击／长按；1132–1148 的状态标签操作。
- [WorkbenchSheet.kt](<D:/bd2 mod/bdroid_src/app/src/main/java/com/bd2toolsbox/ui/screens/WorkbenchSheet.kt#L149>)：149–160 直接调用 clearBackups；[MainViewModel.kt](<D:/bd2 mod/bdroid_src/app/src/main/java/com/bd2toolsbox/ui/viewmodel/MainViewModel.kt#L669>) 669–674 直接启动清空。

第一项必须配合任务生命周期重构，不能只是允许关闭弹窗，却把实际任务一起丢掉。这些改进都能用现有 Material 3 完成。

对应后续工作方向：处理态退出与危险操作使用 harden 思路；兑换结果语义使用 clarify 思路；预览入口使用 clarify／polish 思路。当前仅列为待批准的改进，不执行任何 UI 编辑。

## 库怎么选

| 库／方案 | 建议 | 对本项目的价值 |
|---|---|---|
| Compose + Material 3 | 继续使用，随工具链升级 | 主体已经合适，不需要换框架 |
| Coil 3 | 最值得评估的新库 | 统一角色头像、皮肤图、攻略缩略图的加载、解码、缓存与占位状态，减少两套手写图片加载逻辑 |
| lifecycle-runtime-compose | 建议补上 | 使用 collectAsStateWithLifecycle() 管理 Android 页面状态采集；不是视觉库，也不替代后台任务管理 |
| Navigation 3 / Navigation Compose | 按导航复杂度选一套，不强制引入 | 出现独立详情页、返回栈、状态恢复需求时有价值；只有几个顶层入口时不必为了重构而换导航 |
| Material 3 Adaptive | 平板、折叠屏有需求再加 | 大屏导航栏、列表—详情分栏；只做手机时不是优先项 |
| AboutLibraries 的 Material 3 UI | 按既定计划引入 | 许可证页融入现有主题，不需要再手写一套展示界面 |

当前依赖由 [app/build.gradle.kts](<D:/bd2 mod/bdroid_src/app/build.gradle.kts#L131>) 确认：已有 Material3、material-icons-extended、compose-shimmer；尚无显式 Coil、Compose 导航和 lifecycle-runtime-compose 声明。已有 Compose UI Test 依赖可先用来补真实交互回归，不以再引一套测试库为前提。

Coil 不是零成本替换。当前两处图片仓库有自己的离线缓存和请求规则，迁移时要保留 Gamekee 的 Referer、缓存键和离线行为；网络图片还需要选择网络模块，避免无意引入多套 HTTP 客户端。离线攻略正文不能简单改成可自动淘汰的图片缓存。

若希望界面更现代，可在升级之后选用 Material 3 Expressive 的部分组件、形状和动效。它是 Material 3 的演进方向，不是必须另装一个第三方大 UI 套件。不要全量套用或默认采用实验 API；具体版本须纳入原计划的兼容矩阵，而非本报告直接锁定最新版。

已有 compose-shimmer 不必同时再叠一套加载特效；默认不新增毛玻璃、Lottie 或整套图标依赖。material-icons-extended 不能按依赖原始体积断言全部进入 APK，仍要以 R8 后的实际产物判断。

一手参考（本轮核对了 Material3、生命周期、Navigation3 官方页面及 Coil 官方 README）：
- [Material 3 官方说明](https://developer.android.com/develop/ui/compose/designsystems/material3)
- [Coil 官方文档](https://coil-kt.github.io/coil/)
- [Coil 官方 README](https://github.com/coil-kt/coil/blob/main/README.md)
- [生命周期状态采集](https://developer.android.com/develop/ui/compose/state)
- [Navigation 3](https://developer.android.com/guide/navigation/navigation-3)

## 源码级交互暂评

这是启发式判断，只用于排优先级，不是对实际外观或发布质量的测量。0 为差、4 为优秀；视觉相关项仅评价信息组织。

| 项目 | 暂分 /4 | 主要依据 |
|---|---:|---|
| 系统状态可见 | 2 | 有进度，但部分结果标签不够准确 |
| 贴近用户语言 | 3 | 角色组织自然，仍有技术术语 |
| 用户控制 | 2 | 处理态缺少退路 |
| 一致性 | 3 | Material 骨架统一，标签交互不统一 |
| 错误预防 | 3 | 已有防护，清空备份是缺口 |
| 操作可发现性 | 2 | 预览依赖长按和隐藏含义 |
| 操作效率 | 3 | 批量、筛选、多账号能力值得保留 |
| 信息精简 | 2 | 部分菜单和技术说明较密；视觉未验证 |
| 错误恢复 | 3 | 有重试和 Shizuku 重检 |
| 帮助引导 | 2 | 有引导，但关键入口缺即时提示 |
| 合计 | 25/40 | 有可用基础，优先改善交互；不是发布验收 |

认知负荷：长按操作带来记忆负担；目录菜单展开后有六个候选项，可分组，但不能据此断言整屏过载。情绪上的薄弱点是“处理中不能离开”和“完成后不确定是否真的成功”。

用户视角：
- 新手最大的收益是“知道下一步点哪里”：明确预览入口，处理前后有清晰反馈。
- 熟手最大的收益是“处理时能离开，回来能准确核对结果”：保留批量、多账号与历史能力。

## 其他观察与验证边界

[AvatarRepository.kt](<D:/bd2 mod/bdroid_src/app/src/main/java/com/bd2toolsbox/data/repository/AvatarRepository.kt#L124>) 与 [GamekeeRepository.kt](<D:/bd2 mod/bdroid_src/app/src/main/java/com/bd2toolsbox/data/repository/GamekeeRepository.kt#L457>) 用 ConcurrentHashMap 保存已解码图片，源码中未见容量淘汰上限，是统一图片加载层的理由之一。本轮没有内存或滚动性能测试，不能直接断言已经卡顿。

两张现有截图来自 2026-08-21，导航结构与当前代码不一致，未作为当前外观验证。未构建、安装或运行 App；未启动浏览器可视化。原生 Compose 不能用网页 DOM 检测代替真机截图。

Assessment B 实际执行 detector --json，退出码 0、输出 []。后续只读文件访问观测显示枚举 12 个目录、看到 43 个 .kt 文件，但读取目标源文件数为 0；因此零发现不能解释为 UI 检测通过。命中规则／路径／误报条目均无，不等于没有问题。原始 detector JSON 本身不含扫描计数，覆盖率信息来自该补充观测。

深浅色、壁纸对比度、大字体、触控区域、TalkBack、系统 Insets、返回手势，仍需后续真机验证。若要验证性能，应采集真实设备／代表性列表的测量数据，而不是按页面行数判断卡顿。

前序 [APK 包体审计](<D:/bd2 mod/bdroid_src/docs/research/2026-09-14-apk-size-audit.md>)：arm64 release 32.516 MiB，Python/native 约占 89%；全部 DEX 2.744 MiB、Android 资源 0.417 MiB。DEX／资源不是 Compose 单独体积；不要把换 UI 框架当主要减包措施，也不承诺 Coil 能净减 APK。

## 用户反馈后的范围修正：自定义壁纸保留，可读性优先

已确认：用户希望优化交互和一致性；不满意现有壁纸表现；继续支持自定义壁纸。明确痛点为“可读性太差了，加了壁纸很多东西看不见”。因此不再把保留现有壁纸风格作为设计约束。

术语纠正：当前只有角色头像／皮肤图片展示、通用配色预设和独立壁纸设置，没有按特定角色切换整套视觉的“角色主题”系统。原先的“保留角色主题”是表述不准确，不作为新功能需求。

### 已确认的源码机制（未真机复现）

- [MainActivity.kt](<D:/bd2 mod/bdroid_src/app/src/main/java/com/bd2toolsbox/MainActivity.kt#L169>) 在存在壁纸时启用 transparentBackground。
- [Theme.kt](<D:/bd2 mod/bdroid_src/app/src/main/java/com/bd2toolsbox/ui/theme/Theme.kt#L40>) 将全局 background 和 surface 改成 Color.Transparent；默认使用这些语义底色的组件可能透出壁纸或后方内容，而文字颜色仍沿用主题。
- [AppDialogs.kt](<D:/bd2 mod/bdroid_src/app/src/main/java/com/bd2toolsbox/ui/dialogs/AppDialogs.kt#L443>) 已用 surfaceContainerHigh 局部兜底，并明确说明默认透明底导致两层文字叠加。不能说所有弹窗都未修复；问题是全局透明策略迫使各组件逐个绕过主题默认值。
- [WallpaperBackdrop.kt](<D:/bd2 mod/bdroid_src/app/src/main/java/com/bd2toolsbox/ui/components/WallpaperBackdrop.kt#L28>) 依赖用户调遮罩平衡壁纸和文字，不能保证任意图片、任意控件的可读性。“壁纸清晰度”滑块实际调遮罩，不是图片清晰度。

### 已确认的设计基础与实施建议（未实施）

用户已明确同意以“保留自定义壁纸、关键内容区默认不透明”作为本次 UI 重构的基础。背景装饰不能牺牲正文、操作和状态信息的可读性；不再要求保留旧壁纸表现。以下步骤用于落实这一原则，具体页面版式、壁纸展示范围与参数仍待细化；本次确认不代表开始执行整体重构。

1. 保留壁纸为独立背景层，不再通过污染全局 Material surface 颜色实现穿透；只在明确需要展示壁纸的布局层设置透明。
2. 默认给文字密集的内容区、工具栏、菜单、底部面板和弹窗稳定的不透明主题底色；用连续内容面承载列表，不为每行堆一张装饰卡片。壁纸在页面背景、留白和适当的展示区域可见，具体布局待设计确认。
3. 统一正文、辅助信息、图标、选中态、错误和禁用态的颜色／底色组合，壁纸调整不能降低这些关键内容区域的可读性。
4. 不把“加重整屏遮罩”“所有字改白”“增加阴影／模糊”当作主要修复；模糊和半透明仅作为后续可选装饰，不能承担唯一的对比度保障。不为第一阶段新增特效库。
5. 将壁纸设置中的遮罩与图片裁切等概念准确命名；是否扩展裁切交互另行设计，不默认为新功能已批准。
6. 验收覆盖纯亮、纯暗、强对比及高纹理壁纸，以及所有现有配色、空／加载／成功／失败／选中等状态；在原生截图上核对实际合成后的对比度。普通正文以至少 4.5:1、必要图标与控件辨识边界以至少 3:1 为目标，并检查字体缩放和系统栏。尚未执行这些测试。

当前 Theme 实际固定浅色，这是源码现状；用户已在最终范围确认中同意本轮新增 LIGHT/DARK/SYSTEM，并保留旧配置默认 LIGHT。明暗模式、所有现有配色、壁纸、系统栏和 WebView 一起验收；不能把确认目标当成已经实现。交互、组件一致性与可读性优先，Expressive 等审美升级后置。具体任务见[正式TODO](<D:/bd2 mod/bdroid_src/docs/superpowers/plans/2026-09-14-android-refactor-todo.md>)的T27、T39–T47、T58。
