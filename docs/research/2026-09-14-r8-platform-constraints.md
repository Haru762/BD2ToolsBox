# APK/R8 瘦身：平台约束的一页核对

- **核对日期：2026-09-14；适用基线：AGP 8.4.1、Chaquopy 15.0.1。** Chaquopy 依据其官方固定版 15.0 指南，不套用新版 DSL。
- **范围：** 仅文档核对；未分析本地 APK/DEX/Python 归档/ELF/R8 报告，未构建、安装、修改配置或许可证、提交、派生代理；仅写本文。
- **证据：**【事实】为官方可核对内容；【解释/建议】为适用于本项目的推论；【待验证】交给实施/主审计，不假造配置或节省比例。
- **接收基线（未复查）：** release 已有 `isMinifyEnabled=true`、`proguard-android-optimize.txt` 及 mapping/configuration/usage/seeds；未声明 `isShrinkResources`。因此审计起点不是“R8 未执行”。
- **主审计补充实测（本任务未复验）：** arm64 release 的assets仍有14个x86_64项、合计4.658 MiB，涉及 `requirements-x86_64`、`stdlib-x86_64`、`bootstrap-native/x86_64`；这与下述平台限制一致，但不等于已证明全部可删除或保证同量净节省。FMOD使用链与取舍由主审计负责，本文不扩展该研究。

## 1. R8 full mode、代码/资源收缩与边界

| 核对项 | 结论与官方依据 |
|---|---|
| AGP 8.4.1 的 full mode | 【事实】AGP **8.0 起默认开启 R8 full mode**，故8.4.1处于该默认范围；`android.enableR8.fullMode=false` 可退回兼容模式。**默认值不证明本项目无显式 opt-out**，由主审计检查有效配置。[8.0 发布说明][R1]、[优化指南][R2] |
| `isMinifyEnabled` | 【事实】在本项目使用的旧式 DSL 中开启代码收缩、优化、混淆；并非 Python/native payload 的通用压缩开关。[R2] |
| `isShrinkResources` | 【事实】是独立的 Android 资源收缩开关，官方要求与代码优化分别启用；不能由 `minify=true` 或 R8 mapping 推定资源收缩已开启。未声明时还需核对其他构建约定/最终配置。[AGP8.4 API][R3]、[R2] |
| optimized resource shrinking | 【事实】**AGP 8.12.0 引入**，8.12/8.13 用 `android.r8.optimizedResourceShrinking=true` 选择新管线，并开启资源收缩。它将代码和资源放入统一可达性图；与“R8 full mode”不是同一个功能。[R2] |
| 何时默认启用新管线 | 【事实】**AGP 9.0.0 起，在 `isShrinkResources=true` 时自动应用**，无需上述 property；不是说所有 build type 不设资源收缩也会自动瘦身。[R2] |
| 上轮候选 AGP 8.11.1 | 【事实】低于8.12.0官方支持起点，**不能把该 property 列为8.11.1可用的新优化项**；普通 `isShrinkResources` 与新管线应分开讨论。[R2] |

- **【解释】R8 的代码/Android资源可达性分析，不等于裁剪 Python 模块。** 不应期待它递归裁剪 Python 归档、`assets` 内 ELF/native 库或 JS；这些非JVM代码与资产的输入和打包范围必须另审。即使 DEX 缩小，相关 payload 也可能原样保留。[R2]、[Chaquopy 打包/源码说明][C1]
- **【事实】JNI 与反射需要保留边界。** R8不能可靠推断动态名称调用；Chaquopy 的 `java.jclass` 按完整Java/JNI类名查找，动态代理使用反射机制。[Android keep 指南][R4]、[Chaquopy Python API][C3]
- **【建议】不能简单删除全部 keep，也不应永久用全包 keep 替代定位。** 区分库自带规则、JNI入口、Python调用的Java/Kotlin类/成员、动态代理；在主审计确认真实入口后收紧，并做release回归。R8已执行不证明这些边界已充分覆盖。

## 2. Chaquopy15：ABI splits、Python 包/数据与 pyc

### ABI splits 的准确措辞

- **【事实】15.0 FAQ 原文是 APK splits 和 app bundle “won’t help much”，原因是 Chaquopy 的 native components 打包方式。** 官方推荐用 **ABI product flavor dimension + 每个 flavor 的 `ndk.abiFilters`** 分别生成APK/AAB；其示例每个flavor仅选一个ABI。[固定版 FAQ][C2]
- **【结论】不要写成“一启用 `splits.abi` 就一定构建失败”。** 已核实的是：**不能依赖标准ABI split实现完整的Chaquopy/Python ABI payload拆分**；官方推荐的瘦身替代路径是上述flavors，而非继续叠加split开关。[C2]
- **【解释】`lib/<abi>` 的筛选不等于清理 `assets` 内另一个ABI的Python payload。** 标准split按Android的ABI规则出包；Chaquopy另有native/归档包装。若构建variant的Python输入仍包含两种ABI，不能从arm64 APK名称或`lib/`目录推断其assets已成为单ABI；主审计已提供另一ABI资产仍存在的实测（见基线），本任务不重复测量，也不据此承诺可直接删除。[Android splits][R5]、[C2]
- **【待验证】当前defaultConfig两ABI、release再add arm64，不足以证明最终variant只有arm64。** 需要核对合并后的ABI集合及对应Python归档。后续若采用ABI flavors，应确保每个variant的有效输入单ABI；如在Google Play发布，官方FAQ示例还要求各flavor有不同versionCode。[C2]

### 官方已有配置与未证实的排除能力

| 项目 | 可以写进计划的边界 |
|---|---|
| Python源码与数据 | 【事实】FAQ提醒：Python源目录内**所有文件**都会进入应用。官方支持通过 `chaquopy.sourceSets` 的 `srcDir` / `setSrcDirs` 选择输入目录；可规划只提供所需源码/数据，而不是指望R8清掉不用的文件。[C1]、[C2] |
| pip包 | 【事实】FAQ建议检查requirements清单；【建议】删减必须以导入路径、传递依赖和运行时数据需求为依据。本轮不生成排除清单、不建议为省体积盲目取消依赖。[C2] |
| 通用Python排除DSL | **【未证实】本轮固定版指南未找到足以承诺通用包/数据 `exclude(...)` 或逐模块stdlib裁剪的正式配置依据。** 不编造 `chaquopy.exclude` / `pip.exclude` / `pyc.exclude`，也不声称Android资源excludes会过滤Python归档内部；如需此能力，**实施阶段再做配置与兼容验证**。 |
| pyc与源文件 | 【事实】字节码编译默认启用；成功后原始`.py`不进入APK，**被 `extractPackages` 覆盖的文件除外**。这是编译/打包选择，不是unused-module消除，也不是收益百分比保证。[C1] |
| pyc的真实选项 | 【事实】官方列出 `pyc.src`、`pyc.pip`、`pyc.stdlib` 三类编译设置；例如 `pyc { src = false }` 是关闭本地源码字节码编译，**不是排除该源码或删除pip/stdlib**。[C1] |
| pyc构建条件 | 【事实】src/pip编译需buildPython使用相同字节码格式，通常需与应用Python **minor相同**；默认失败可能仅警告并改为设备编译，显式设相关pyc项为true时失败会阻断构建。因此有`.py`不能仅归咎于R8。[C1] |
| `extractPackages` | 【事实】用于将必须以真实文件存在的包在运行时提取，而非排除包；应限定到尽可能深的包路径。它还影响`.py`保留，不能为瘦身随意改动或假定磁盘资源不再需要。[C1] |

## 3. 给主审计的收敛结论

1. 先确认full-mode是否被关闭、资源收缩是否实际启用；不要把“已有R8报告”解释为所有收缩机制都开了。
2. 把DEX/Android资源优化与Python、JS、ELF及归档打包范围分开归因；AGP8.11.1不具备官方8.12新资源管线。
3. 将ABI flavors作为官方方向，而非宣称现有ABI split已经剔除了全部另一ABI Python资产；具体体积由主审计量化。
4. Python输入删减、pyc和keep规则调整均保留运行回归门槛：JNI入口、Python→Java调用、动态import/代理、包数据读取和各ABI功能；许可证与既定分发边界不随瘦身自动改变。

[R1]: https://developer.android.com/build/releases/past-releases/agp-8-0-0-release-notes "AGP8默认full mode"
[R2]: https://developer.android.com/topic/performance/app-optimization/enable-app-optimization "代码/资源优化与8.12、9.0门槛"
[R3]: https://developer.android.com/reference/tools/gradle-api/8.4/com/android/build/api/dsl/ApplicationBuildType "AGP8.4独立的minify/shrinkResources属性"
[R4]: https://developer.android.com/topic/performance/app-optimization/keep-rules-overview "JNI/反射keep边界"
[R5]: https://developer.android.com/build/configure-apk-splits "标准Android ABI splits"
[C1]: https://chaquo.com/chaquopy/doc/15.0/android.html "Chaquopy15源码、打包、extractPackages及pyc"
[C2]: https://chaquo.com/chaquopy/doc/15.0/faq.html#how-can-i-make-my-app-smaller "Chaquopy15官方ABI flavors建议与输入瘦身"
[C3]: https://chaquo.com/chaquopy/doc/15.0/python.html "Python按名查找Java类与动态代理"
