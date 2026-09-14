# Android 依赖现代化：有界一手文档核对

- **核对日期：2026-09-14（Asia/Shanghai）**。下文“当前官方”指该日期访问到的文档或发布物，不代表本项目应追随最新版。
- **仓库基准：** `D:/bd2 mod/bdroid_src`，HEAD `b83c1c0`；本任务开始时只读检查的 `git status --short` 为空。
- **范围：** 仅核对 Kotlin/JSON、Hilt/KSP/进程入口、AboutLibraries/许可证、工具链与 Chaquopy 兼容约束；不重复代码和架构总盘点，不提供总重构实施计划。
- **操作边界：** 仅写本文；未修改生产代码、依赖配置或许可证，未运行 Gradle、构建、安装、提交或推送，未再派子代理。网络材料通过获准的只读 HTTPS 请求获取；POM/AAR 元数据只在内存中解析，未落盘或执行。
- **证据标签：** **【事实】**指官方文档、固定 release 源码或发布物可直接支持的结论；**【建议】**指针对本项目的取舍；**【需实测】**指本轮没有构建、运行或最终依赖解析证据。局部版本约束相容，不等于整套应用已验证兼容。

## 0. 接收的本地基线，不重复扫描

以下配置与代码事实来自主盘点/用户补充，除 HEAD 和初始 Git 状态外，本任务未重新验证源码：

- AGP `8.4.1`、Gradle `8.9`、Kotlin `1.9.0`、Compose compiler extension `1.5.1`、Compose BOM `2024.06.00`；Java source/target 与 Kotlin `jvmTarget` 都为 `11`；compile/target SDK `34`、min SDK `29`。
- Chaquopy `15.0.1`；构建机 Python 注释写为 `3.8–3.11`，pip 依赖未锁版本；Gson `2.11.0`、Shizuku `13.1.5`；尚无 Hilt、KSP、AboutLibraries。
- Gson 涉及 8 个 Kotlin 文件，另有 `org.json` 混用；MainViewModel 快照含 `Uri TypeAdapter` 和反射字段排除策略。用户所述 THIRD-PARTY-NOTICES 仍记 Gson `2.10.1`，与构建声明 `2.11.0` 漂移；**声明版本不冒充本轮已解析的最终版本**。
- 当前没有自定义 Application，manifest 的 `application` 没有 `android:name`；Python 在 MainViewModel、ModdingService 等入口按需启动。
- `D:/bd2 mod/bdroid_src/licenses` 只有 5 份许可证；随包内容包括 PixiJS 脚本、vendored UnityPy/repacker 源码及 native 库。**pixi-spine 按项目政策运行时下载、不随 APK 打包，此边界必须保持。**

## 1. Kotlin 2.x 与 Gson：不是“升级语言即可删除 JSON 依赖”

### 1.1 已核对的事实

1. **【事实】Kotlin 2.x 不内置 Gson，也不会自动替换 Gson。** Gson 是独立 Java JSON 库；Kotlin 官方提供的序列化方案是独立的 `kotlinx.serialization` 组件。Gson 官方还明确提醒：Kotlin 非空类型、带默认参数的构造器不是 Gson 的原生语义支持范围，并不推荐在 Android 上依赖开放式反射序列化。[Kotlin 官方说明][K1]、[Gson 官方 README][K3]。
2. **【事实】两种依赖必须区分：**
   - `org.jetbrains.kotlin.plugin.serialization`：编译器插件，用于生成 `@Serializable` 类的 serializer；版本随 Kotlin 编译器发布，使用与 Kotlin **相同版本**。
   - `org.jetbrains.kotlinx:kotlinx-serialization-json`：JSON runtime，版本独立于 Kotlin；不是只应用编译器插件就自动具有的 JSON 功能。
   - 官方安装流程明确要求分别添加插件和库。[官方 setup][K2]、[独立版本说明][K1]。
3. **【事实】动态 JSON 不必伪装成固定 DTO。** `Json.parseToJsonElement` 提供树模型，`JsonObject`、`JsonArray`、`JsonPrimitive`/`JsonNull` 可处理不规则结构；官方特意区分这种“解析树”与 DTO 反序列化。仅使用已有的 JSON 树 API，并不意味着必须为业务类生成 serializer。[JSON tree 文档][K4]。
4. **【事实】JSON 语义不等价。** kotlinx.serialization 默认拒绝未知字段；默认不编码等于默认值的属性；`explicitNulls` 控制 null 的编码/解码行为；缺失的无默认值属性可能失败。`@SerialName`、`@Transient`、自定义 `KSerializer`/contextual serializer 有各自规则，不会自动继承 Gson 注解、TypeAdapter 或 ExclusionStrategy。[JSON 配置][K4]、[类序列化规则][K6]、[自定义 serializer][K5]。

### 1.2 本项目的迁移建议与门槛

| 本地使用方式 | 【建议】 | 【需实测】 |
|---|---|---|
| 固定配置、持久化 DTO | 显式定义序列化边界、字段名、默认值和 null 策略；不要直接把整个 ViewModel 的反射字段集合当新 DTO | 旧 JSON 能否读取；写回后旧版本能否读取；未知字段、缺失字段、枚举及数字格式 |
| 不规则/动态 JSON，以及 `org.json` 混用 | 为各调用点决定保留旧库、转换到 JsonElement，还是固定 schema；不要全局替换 import | 缺失键与 JSON null、字符串数字、大整数、数组/对象形态，以及跨库转换 |
| `Uri TypeAdapter` | 优先评估持久化为显式字符串 DTO，或实现对应 `KSerializer<Uri>`；必须保持既有文本约定 | content/file URI、转义、空值、非法输入、往返一致性 |
| 反射字段排除策略 | 将排除规则转为显式 DTO 字段选择、适当的 `@Transient` 或自定义 serializer；不能假设注解与运行时排除策略一一对应 | 被排除字段不意外泄漏，默认值不意外恢复或丢失，运行时对象/循环引用不进入快照 |

**【建议】Gson 删除是迁移的验收结果，不是 Kotlin 升级的前置动作。** 允许新旧库短期共存；8 个文件、快照自定义规则与 `org.json` 边界全部核对并通过回归后，再决定是否移除。本文不删除或修订旧 notices，只将已知版本漂移列为后续核验项。

## 2. Hilt、KSP 与 Android/Shizuku 入口

### 2.1 普通 Android 组件的正式接入要求

- **【事实】构建侧：** 使用 `com.google.dagger.hilt.android` 插件、`com.google.dagger:hilt-android` runtime、KSP 配置中的 `com.google.dagger:hilt-compiler`；KSP 插件 ID 为 `com.google.devtools.ksp`。同一 Hilt 版本应统一用于插件、runtime、compiler，不把 Hilt processor 同时重复挂到 kapt 和 ksp。[Hilt Gradle setup][H1]、[Dagger KSP 指南][H14]。
- **【事实】Application：** Hilt 需要带 `@HiltAndroidApp` 的 Application；它建立应用级组件。**【建议】本项目没有现成自定义 Application，实施时需要增加并在 manifest 注册实际类名，不能只添加 Activity 注解就宣布接入完成。**[Hilt quick start][H3]。
- **【事实】Activity/Service：** `@AndroidEntryPoint` 支持 Activity、Fragment、View、Android Service、BroadcastReceiver 等 Android 类型；Activity 要基于 `ComponentActivity`，Fragment 的宿主也要满足 Hilt 条件。ViewModel 不是直接套 `@AndroidEntryPoint` 的类型。[Android entry points][H2]。
- **【事实】ViewModel：** 使用 `@HiltViewModel` 与 `@Inject constructor`，通过 ViewModelProvider/对应的 Hilt factory 获取，而不是把 ViewModel 当普通单例直接注入或随意 `new`。[Hilt ViewModel][H4]。
- **【事实】Compose API 已迁移：** AndroidX Hilt `1.3.0` 将 `hiltViewModel()` 移至 `androidx.hilt:hilt-lifecycle-viewmodel-compose`，包为 `androidx.hilt.lifecycle.viewmodel.compose`，不再为此 API 强制引入 Navigation。不能只照旧示例引入 `hilt-navigation-compose`。[官方 release notes][H7]。
- **【事实】不能忽略 SDK 门槛：** `hilt-lifecycle-viewmodel-compose:1.3.0` 的官方 AAR 元数据写明 `minCompileSdk=35`、`minAndroidGradlePluginVersion=8.6.0`。现有 compileSdk 34 + AGP 8.4.1 不满足它；POM 还包含 Compose `1.8.2`、Lifecycle `2.9.1` 等依赖，不能仅看项目旧 BOM 就假定版本不变。[POM][H8]、[AAR][H9]。

**【建议】Hilt Application 的建立与 Python 启动时机分开处理。** Chaquopy 官方既支持每进程一次的 Application 启动，也支持先检查 `Python.isStarted()` 后按需启动。当前项目采用后者，不应因为加 Hilt 就悄悄切到 `PyApplication` 或把 Python 提前到 Application.onCreate。[Chaquopy 启动说明][C2]。

### 2.2 AIDL/Shizuku 的技术边界

| 对象/入口 | 已核对的边界 | 【建议】 |
|---|---|---|
| 正常由 Android 创建、挂在 Hilt Application 下的 Service | 是 Hilt 支持的 Android 组件；声明到另一个 Android 应用进程，不等于天然不能用 Hilt，但组件实例/内存不跨进程共享 | 保持每进程初始化与 Service 生命周期正确，不把 `@Singleton` 理解为跨 Binder 的全局单例 |
| AIDL 接口、生成的 Stub/Binder 对象 | IPC 类型本身不是 Hilt 的 Android Service 生命周期入口 | 在受支持的宿主中构造/提供实现，或显式传入依赖；不要仅因名称含 Service 就加注解 |
| Shizuku user service | 官方说它在 root/shell 身份的独立进程运行，**不是有效的普通 Android application process**；即使拿到 Context，`registerReceiver`、`getContentResolver` 等仍可能不可用 | 不将它当普通 `android.app.Service` 直接套 `@AndroidEntryPoint` |
| Shizuku 的 Context 构造器 | v13 起优先尝试带 Context 的构造器；有 Context 不等于有本应用的 Hilt Application/component holder | 保留规定的构造方式；可评估显式构造依赖或进程内独立的普通 Dagger 组件，不隐式假定可以取得主进程对象图 |
| `EntryPointAccessors`/`EntryPoints` | Hilt 官方要求传入匹配 `@InstallIn` 的真实组件或 component holder；它不是把任意 Context 变成 Hilt 容器的工具 | 不把对 Shizuku Context 调用 `fromApplication(...)` 当通用补丁；应用侧 Hilt 可以管理 Binder 客户端/连接包装，远端仍守 IPC 边界 |

依据：[Hilt Android 类型][H2]、[Hilt entry points 的 holder 要求][H5]、[Shizuku 官方 UserService 说明][H6]。上表关于本项目注入位置的取舍是技术建议，并非本轮已完成改造。

**【需实测】** Compose ViewModel 的 owner/作用域与保存状态、Application/Service 冷启动、普通多进程与 Shizuku 进程重启、Binder 断连与权限失效。KSP 官方还指出，它不能解析其他 Javac/KAPT 注解处理器生成的类型；若依赖图涉及这类生成类型，要一起迁移或隔离。这个限制**不等于 AIDL 生成源码一律不能使用**，AIDL 的任务顺序和源码可见性应在实施轮单独验证。[Dagger KSP 交互限制][H14]。

### 2.3 “最新版 Hilt + AGP 8”是明确冲突，不是小修参数

- **【事实】Hilt/Dagger `2.59` 的 release notes 明确要求 Hilt Gradle 插件使用 AGP 9；不是只支持 AGP 9 而仍兼容 AGP 8。** `2.58` 的 release notes 则明确暂缓这项变更；其固定标签插件源码的检查为 AGP `>=8.4.0`。[2.59 breaking change][H13]、[2.58 release][H10]、[2.58 插件源码][H11]。
- **【事实】当前 Dagger 官方将 KSP 支持标为 stable 的起点是 Dagger `2.60+` 且 KSP `2.3.9+`。** 所以保留 AGP 8、选 Hilt 2.58 的方案，不能同时宣传为“已满足官方当前 stable KSP 门槛”。支持 KSP 与已经达到当前官方 stable 声明是两回事。[当前 KSP 指南][H14]。
- **【建议】** 若坚持先留 AGP 8，可以将 2.58 + 同代 KSP2 作为有证据的受控候选，但必须接受处理器链验证门槛；若“当前官方 stable KSP”是硬要求，应把 AGP 9 迁移作为单独决策，不能假装不存在冲突。本轮不扩展研究或实施 AGP 9 总迁移。

## 3. AboutLibraries：自动图、Material3 与许可证保留

### 3.1 当前官方接入方式与新旧差异

核对的当前稳定 release 为 **15.2.0**。以下描述当前 API，**不是建议将 15.2.0 直接粘贴到本项目**。[release][A3]、[固定标签 README][A1]。

| 项目 | 【事实】当前官方方式 |
|---|---|
| 主 Gradle 插件 | `com.mikepenz.aboutlibraries.plugin`；提供手动导出任务，例如 `exportLibraryDefinitions` |
| 自动接入 Android 构建 | `com.mikepenz.aboutlibraries.plugin.android`；v13 起与主插件拆分，需要自动生成/接入 Android 资源时选它，而非假定主插件自动完成 |
| Material3 UI 依赖 | `com.mikepenz:aboutlibraries-compose-m3`；group 仍为 `com.mikepenz` |
| Android 基本 API | `produceLibraries(R.raw.aboutlibraries)` 加 Material3 的 `LibrariesContainer(...)`；后者位于 `com.mikepenz.aboutlibraries.ui.compose.m3` |
| 手工定义入口 | `aboutLibraries` 的 `collect.configPath`；该目录下以 `libraries`、`licenses` 中的 JSON 增补或覆盖 |

依据：[15.2.0 README][A1]、[M3 API 源码][A11]、[迁移指南][A2]。

**需明确区分的迁移：**

- **【事实】** 从所核对的 13.1.0 到 15.2.0，Material3 坐标仍是 `com.mikepenz:aboutlibraries-compose-m3`，并非发现了必须更换 Maven group 的证据；实际变化在插件拆分、旧 UI/配置/API 移除。[13.1.0 README][A6]、[15.2.0 README][A1]。
- **【事实】** v14 移除了旧 `generateLibraryDefinitions*` 任务；v15 移除了 View UI 模块 `com.mikepenz:aboutlibraries` 及 LibsBuilder/LibsActivity/LibsFragment 一类旧用法。v15 的 LibrariesContainer 样式参数也重组，部分点击回调改为 Boolean。不要把这些时代的示例混用。[迁移指南][A2]。
- **【事实】** v15 `.android` 插件要求 AGP `>=8.13.0`；v15.1 插件 target 变为 Java 17。更关键的是 **15.2.0 Material3 Android AAR 自身要求 compileSdk 37**，POM 声明 Kotlin stdlib `2.4.10`、Compose `1.12.0` 等。AGP 8.13 官方最大 API 为 36.1，因此不能把“插件允许 AGP 8.13”推断为“15.2.0 整套 UI 在 AGP 8.x 上受支持”。[迁移说明][A2]、[15.2.0 POM][A4]、[15.2.0 AAR][A5]、[AGP 8.13 兼容表][T3]。

### 3.2 自动报告究竟覆盖什么

**【事实】官方 disclaimer 明确说：** 工具基于 Gradle 定义的编译期依赖及其子依赖；可能列入未实际分发的编译期库，也可能因 POM 许可证元数据不完整而漏报；**非 Gradle 引入的 native 子依赖不能自动解析**，需要补充定义和人工复核。[官方 Disclaimer][A1]。

由此不能承诺它会自动盘点压缩后的 JavaScript、Chaquopy pip 完整依赖树、vendored Python/repacker 源码、JNI 二进制内部静态链接库，或证明 APK 的许可证义务已经全部满足。就算某个 native 外层 AAR 在 Gradle 图里，也不等于内部所有 native 组件都已逐项识别。

**【建议】自动清单覆盖 Gradle 部分，手工定义覆盖非 Gradle 部分；两者统一展示，不等于删除后者的事实来源。** 导出应按实际分发的 release/variant 核对，排除仅构建使用的误报，补齐漏报。不能用“生成了 JSON”代替完整性验收。

### 3.3 五份现有许可证的处置门槛

下表对应关系来自主盘点，ReDustX 与具体源码的关联尚未由本调研核定；本轮不判断每份文本的 SPDX 类型或实际 upstream commit。

| 现存原文 | 主盘点提供的关联 | 【建议】迁移前要求 |
|---|---|---|
| `D:/bd2 mod/bdroid_src/licenses/astc-encoder-LICENSE.txt` | arm64 与 x86_64 的 libastcenc | 补库名、实际版本/来源、ABI 分发范围、完整原文；不能只靠 Gradle 图 |
| `D:/bd2 mod/bdroid_src/licenses/pixijs-LICENSE.txt` | 随包 PixiJS；APK asset 键为 `spine-viewer/js/pixi.min.js` | 按实际 vendored 脚本版本登记，不与运行时下载的 pixi-spine 混为一项 |
| `D:/bd2 mod/bdroid_src/licenses/ReDustX-LICENSE.txt` | 与 repacker/来源代码的具体关联待主盘点确认 | 关联未厘清前保留；不要因自动报告未出现就认定冗余 |
| `D:/bd2 mod/bdroid_src/licenses/texture2ddecoder-LICENSE.txt` | arm64 libtexture2ddecoder；其他源码/打包关联待核 | 补原文、来源和实际分发组件；核对是否含其他需署名的实现 |
| `D:/bd2 mod/bdroid_src/licenses/UnityPy-LICENSE.txt` | vendored UnityPy Python 源码 | 补固定来源版本/commit；其 Python/native 子依赖另外核对，不能用 UnityPy 一项代替全部 |

**【事实】官方提供的补充定义格式：** 每个库一份 JSON，含 `uniqueId`、`name`、`artifactVersion` 等，`licenses` 数组引用许可标识；许可证 JSON 包含 `hash`、`name`、`url`、`content`。官方示例用库的 `licenses: ["asdkl"]` 对应许可证的 `hash: "asdkl"`；原文放在 `content` 中。`uniqueId` 相同会合并，不能随意复用导致两份第三方事实被覆盖。[library 示例][A9]、[license 示例][A10]、[配置说明][A1]。

**【建议】原文的来源、版本和版权行都应保存。** 可将原有文本完整转为 JSON 的 `content`，同时记录固定版本的上游 URL；不要只写“MIT/Apache”等短名或只给外链。若保留独立原文文件作为权威输入，也可以在后续实施中生成 JSON，而不维护两套各自手改的事实。具体转义、换行和 UI 显示必须核验。

**【建议】删除任一原文/整个目录前，至少同时满足：**

1. 五份文本各自对应的分发组件、版本/来源、许可证、版权和 NOTICE 要求已确认；不存在因自动图未发现而遗漏的条目。
2. 新定义与原文可逐项对照，完整文本可离线读取；不能因为去重、过滤、缩减字段而丢失不同版权人的文本。
3. 实际 release APK/分发包中确有这些资料，许可证页面能显示；启用资源收缩时仍保留生成 JSON。AboutLibraries 官方特别提醒优化资源收缩可能移除 `aboutlibraries.json`，需要 keep 配置或验证。[资源收缩说明][A1]。
4. 新的唯一事实来源与更新流程已明确；历史 Gson `2.10.1`/当前声明 `2.11.0` 这种漂移有检测办法。后续对照实际解析结果，而不是仅对照手填版本。
5. **主项目 `D:/bd2 mod/bdroid_src/LICENSE` 不在“重复的第三方目录”删除范围内。** 本项目自身授权文件和依赖归属报告不是同一职责，接入 AboutLibraries 不授权删除它。

**当前结论：五份原文都不满足“本轮可直接删”的条件。** 允许规划迁移与集中管理，不允许先删目录再寄望自动补齐。上述门槛是技术完整性要求，不是自动完成法律合规判断。

**pixi-spine 分发边界：** 按已给出的项目政策继续运行时下载、不进入 APK。若在 notices 中列出，应明确标记“运行时获取/非随包分发”，并核验其实际使用条款；本轮不新增 Gradle/npm 依赖、不将其 vendoring 到 assets，也不因展示需求改变下载时机。“不随包”也不应被误写为“不需要考虑许可”。

## 4. 兼容升级方向：先用可追溯的 AGP 8.x 候选，而非全部追最新

### 4.1 一套有官方证据的保守候选

**【建议】下表是一组用于下一轮验证的明确候选，不是已在此仓库通过构建的最终锁定方案。** 选择 Kotlin 2.2.20，是为了与 Hilt 2.58 固定标签及 AboutLibraries 13.1.0 的版本声明一致，而非声称它是最新 Kotlin。未验证的 Compose BOM 最终版本不在本文编造。

| 项目 | 候选/约束 | 已核对的官方依据 |
|---|---|---|
| Gradle 运行 JDK | **17** | AGP 8.x 要求 JDK 17；AGP 8.11 兼容表也是 17。[JDK 文档][T4]、[AGP 表][T2] |
| AGP / Gradle | **8.11.1 / 8.13** | AGP 8.11 的 Gradle 最低/默认都是 8.13，支持最大 API 36；8.11.1 有正式 release 记录。[T2] |
| Kotlin/KGP | **2.2.20** | KGP 2.2.20–2.2.21 的 fully supported 范围为 Gradle 7.6.3–8.14、AGP 7.3.1–8.11.1；上述 AGP/Gradle 位于范围内。[T1] |
| Compose compiler | **`org.jetbrains.kotlin.plugin.compose:2.2.20`** | Kotlin 2.0+ 使用新插件且与 Kotlin 同版本；替换旧 extension 版本管理，Compose 模块分别应用。[T5] |
| KSP | **2.2.20-2.0.3，使用 KSP2** | 是正式 release，也是 Hilt 2.58 版本目录所用版本；该标签 README 说明 KSP2 默认启用，不能误开 `ksp.useKSP2=false`。[release][P1]、[README][P2]、[Hilt 版本目录][H12] |
| Hilt | **2.58**，插件/runtime/compiler 同版本 | 插件检查 AGP >=8.4.0；上游版本目录为 Kotlin 2.2.20 + KSP 2.2.20-2.0.3。仍受 §2.3 的 KSP stable 限定。[H11]、[H12]、[H14] |
| AndroidX Hilt Compose VM | **1.3.0** 的新 lifecycle-viewmodel-compose artifact | 正式稳定版；直接 AAR 要求 compileSdk >=35、AGP >=8.6.0。[H7]、[H9] |
| Chaquopy | **16.1.0** | 固定版官方文档明确支持 AGP 7.0.x–8.13.x、minSdk >=24，覆盖上述候选及现有 minSdk 29。[C2] |
| AboutLibraries | **13.1.0 作为受控候选，插件与 UI 同版本** | 该版正式 README 已有分拆插件和 M3 API；M3 POM 为 Kotlin 2.2.20、Compose 1.9.0 等；插件源码 target/toolchain 为 Java 11，可在 JDK17 路线上评估。没有查到官方对本项目全组合的认证矩阵，插件联动仍待验证。[A6]、[A7]、[A8] |
| compileSdk | **36**，由实际新增库门槛驱动 | AboutLibraries 13.1.0 直接依赖 core-ktx 1.17.0；该 AAR 要求 compileSdk 36、AGP >=8.9.1。AGP 8.11 支持 API36。[A7]、[A12]、[T2] |
| targetSdk / minSdk | 可在依赖升级阶段分别保留 **34 / 29**，再单独审查 target 行为与发行要求 | compileSdk 与 targetSdk 的职责不同；targetSdk 影响 Android 兼容行为。**不声称 target34 满足未来商店发行政策**。[SDK 文档][T8] |
| Java source/target、Kotlin jvmTarget | 先保持三者 **11** 一致；不要与运行 Gradle 的 JDK 混淆 | JDK17 运行 Gradle 不要求把应用源码 target 自动改成17。若以后提升源码语言级别/字节码目标，需作为独立变更验证。[T4] |
| serialization（若迁移 Gson） | 编译器插件 **2.2.20**；JSON runtime 可验证候选 **1.9.0** | 插件跟 Kotlin；runtime 独立。1.9.0 release 使用 Kotlin 2.2.0，亦为 AboutLibraries 13.1.0 上游版本目录采用的 runtime 代际；不能把 runtime 版本写成 Kotlin 版本。[K2]、[1.9.0 release][K7]、[上游版本目录][A13] |
| Compose BOM | **版本待选，不沿用“2024.06.00 保证不变”的假设** | BOM 不管理 compiler；新增 Hilt/AboutLibraries 的 POM 已引入更高 Compose 需求。后续按实际解析图选匹配的稳定 BOM，不能仅改 compiler 或压低传递版本来绕过验证。[BOM 官方说明][T6]、[H8]、[A7] |

**候选的证据强度：** AGP–Gradle–JDK、KGP 支持范围、Chaquopy 的 AGP/minSdk 范围，以及所检查 AAR 的最低门槛可直接核对；Hilt 上游 Kotlin/KSP 版本一致是有利证据，但不是本项目全部 annotation processing、AboutLibraries Gradle 插件、Compose BOM、Python/native 集成测试已经通过的证明。

### 4.2 为什么不写成“所有库直接用最新版”

- **【事实】KSP 的版本机制也变化了。** 2.3.0 官方说明其版本不再绑定 Kotlin 的旧 `<kotlinVersion>-<kspVersion>` 格式；不能推导为“任意 Kotlin 2.x 配任意 KSP”。当前 2.3.12 release 又把最低 AGP 提升到 8.12.0，因此直接把它放进上表 AGP 8.11.1 会产生明确冲突。[KSP 2.3.0][P3]、[KSP 2.3.12][P4]。
- **【事实】AGP 8.13.2 确有官方 Kotlin 2.3 支持说明，Gradle/JDK 为 8.13/17。** 但 KGP 2.2.20 的 fully supported AGP 上界是8.11.1；若另选 Kotlin 2.3 + AGP8.13.2，应重新验证 Hilt processor/metadata、KSP 和 AboutLibraries，而不是仅替换表中的 AGP。一组有依据的路线比跨代拼“最高版本”更可追溯。[AGP8.13.2][T3]、[KGP 矩阵][T1]。
- **【事实】最新 Hilt 的 AGP9 要求和最新 AboutLibraries UI 的 SDK37 要求是两个独立约束。** Chaquopy 当前表已列出17.0对较新AGP的支持，也不能反向证明其他插件的组合或现有 Python/native 代码兼容。[H13]、[A5]、[Chaquopy 版本表][C3]。
- **【建议】** 若团队要求所有新增依赖都是“当前最新 stable 且 processor 也达到官方 stable 声明”，应明确告知与“先保留 AGP8”的目标存在冲突；不能把上表受控候选包装成满足二者的无条件方案。

### 4.3 Chaquopy：Python 版本与单模块约束

1. **【事实】现有“构建机 Python 3.8–3.11”注释不是 Chaquopy 15 的官方完整约束。** 15.0 文档的 `buildPython` 章节说部分功能需要构建机 Python **3.7 或以上**；应用内 Python 默认3.8，可选3.9、3.10、3.11、3.12。这是“构建机工具”和“APK 内解释器”两套版本概念，不是任选一个3.8–3.11就一定兼容。[15.0 固定版文档][C1]。
2. **【事实】字节码编译另有匹配条件。** 对 `src`/`pip` 的 pyc 编译，buildPython 必须与 Chaquopy 使用相同字节码格式，通常意味着 Python **minor 版本相同**。默认情况下失败可能只警告并留到设备编译；显式要求 pyc 时可能失败。仅看“能装 Python”不能证明构建可复现。[15.0 Bytecode compilation][C1]。
3. **【事实】16.1 的 buildPython 基础要求为3.8或以上，应用内支持3.8–3.13，minSdk24。** 17.0 的应用 Python 范围已变为3.10–3.14；不能在不知道现有目标解释器版本、wheel 可用性和 ABI 条件时盲升。[16.1 文档][C2]、[版本摘要][C3]。
4. **【事实】同一应用只能在一个模块应用 Chaquopy 插件：** 可以是 `:app`，也可以恰好一个 Android library 模块；多个模块同时应用时，构建可能失败，**即使成功，也只会包含其中一个模块的 Python 代码**。15.0 与16.1文档均明确如此。[C1]、[C2]。
5. **【建议】先包内分层时保持唯一插件所属模块不变。** 后续提取 `:core:engine` 时，如果决定将 Python 打包职责移到该 Android library，就同步从 `:app` 移除实际应用插件/打包职责；不是两边都套 `com.chaquo.python`。根构建中 `apply false` 仅集中声明插件版本，不等于在两个业务模块实际应用。
6. **【建议】Python 小版本、pip 版本/约束及 vendored 来源单独记录。** Chaquopy 官方支持精确 requirement（如 `package==version`）与 requirements 文件；具体本项目版本应先核实 wheel、Python minor、ABI 和许可来源，本文不虚构锁定结果。[pip 接入语法][C2]。

**【需实测】** 应用内 Python 的实际目标 minor、构建机解释器匹配、pip 包解析及 native wheel；Python 多入口按需启动、普通 Service 与 Shizuku 进程边界；arm64/x86_64 的 JNI 加载与功能覆盖。主盘点只报告 x86_64 libastcenc，不能因此在本轮断言 x86_64 所有解码功能都可用或一定失败。

### 4.4 版本分离与下一轮验证门槛

**【建议】版本目录只负责“声明”，不要把它当完整 lockfile：**

- 在后续允许配置修改时，以 `D:/bd2 mod/bdroid_src/gradle/libs.versions.toml` 集中管理 `[versions]`、`[plugins]`、`[libraries]`；Kotlin、Compose compiler 插件、serialization 插件共用 Kotlin version key。
- KSP 独立 key；Hilt 插件/runtime/compiler 共用 Hilt key；AboutLibraries 插件/UI 共用 AboutLibraries key；Compose BOM 与 serialization JSON runtime 各用独立 key。应用的 versionCode/versionName 不与这些依赖版本绑在一起。
- Gradle wrapper、Gradle 运行 JDK、SDK、Python/buildPython、pip/vendor 版本另有职责与存放位置，不能声称一个 TOML 覆盖全部。
- **【事实】Gradle 官方明确：version catalog 声明的是 requested versions，不强制最终冲突解析结果。** 因此后续仍需检查实际 release 解析图并选择锁定/校验策略，尤其要防止 notices 再次漂移；本轮没有解析或锁定依赖。[Version catalogs 官方文档][T7]。

下一轮才执行的验收项（**本轮均未运行**）：

1. 配置/处理器链：用选定运行 JDK 与版本组合验证 Gradle 配置、Hilt/KSP 生成代码及 AIDL 可见性，确认没有 kapt/KSP 冲突和未预期的版本抬升。
2. UI/生命周期：核对 Compose BOM 最终解析、hiltViewModel owner、Activity/Service 冷启动，以及新增 Application 不改变 Python 懒启动语义。
3. Python/native：核对单模块 Python 产物、minor/pyc/wheel、两类进程及各 ABI 的能力，不以升级 Gradle 代替运行验证。
4. 数据兼容：用既有快照/配置样本验证 DTO/动态 JSON、Uri 和排除策略，之后才决定删除 Gson。
5. 许可分发：逐项核对五份原文、自动/手工条目、APK 内离线可读性与资源收缩；主项目 LICENSE 保留，pixi-spine 仍为运行时获取。

## 5. 给主计划的简明结论

- 纠正“升级 Kotlin 2.x 就能删除 Gson”：插件、runtime 与业务 JSON 语义是三个独立问题，当前自定义快照行为要求显式迁移。
- Hilt 先补真正的 Application 与受支持组件入口；Shizuku user service 不等同于普通 Android Service，Context 不能凭空提供 Hilt 组件。
- Hilt 2.59+ 要求 AGP 9；当前官方 stable KSP 门槛又从 Dagger 2.60/KSP 2.3.9 开始，必须向主计划暴露这个取舍。
- AGP 8.x 可先评估 §4.1 的同代候选；它不是全栈已验证结果。AboutLibraries 15.2.0 不能直接用于现有 SDK 34 配置，保守候选的 compileSdk 也要因已查明的 AAR 门槛升到 36。
- Chaquopy 每应用仅一个实际应用插件的模块，禁止 `:app` 与 `:core:engine` 重复打包；Python 构建机版本与 APK 内版本分开核实。
- 五份手工许可证对应自动 Gradle 图之外的内容，本轮不能删；集中展示不等于失去原文、版权/NOTICE、项目 LICENSE 或改变 pixi-spine 分发边界。

---

## 来源说明

所有下列链接均在 **2026-09-14** 核对。优先采用固定版本的 README、release、源码；滚动官方文档可能在之后变化。AAR证据为发布方在 Google Maven/Maven Central 发布的 `META-INF/com/android/build/gradle/aar-metadata.properties`，不是运行其代码所得。没有使用二手博客作为版本依据，没有生成额外诊断文件。

[K1]: https://kotlinlang.org/docs/serialization.html "Kotlin 官方 Serialization"
[K2]: https://github.com/Kotlin/kotlinx.serialization/blob/master/README.md "kotlinx.serialization 官方 setup"
[K3]: https://github.com/google/gson/blob/main/README.md "Gson 官方 README"
[K4]: https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/json.md "JSON 配置与 JsonElement"
[K5]: https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/serializers.md "自定义与 contextual serializer"
[K6]: https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/basic-serialization.md "DTO、默认值、缺失字段、Transient"
[K7]: https://github.com/Kotlin/kotlinx.serialization/releases/tag/v1.9.0 "serialization 1.9.0 release"
[H1]: https://dagger.dev/hilt/gradle-setup.html "Hilt 官方 Gradle 配置"
[H2]: https://dagger.dev/hilt/android-entry-point.html "Hilt Android entry points"
[H3]: https://dagger.dev/hilt/quick-start.html "Hilt Application quick start"
[H4]: https://dagger.dev/hilt/view-model.html "Hilt ViewModel"
[H5]: https://dagger.dev/hilt/entry-points.html "Hilt component holder 边界"
[H6]: https://github.com/RikkaApps/Shizuku-API/blob/master/README.md#userservice "Shizuku 官方 UserService"
[H7]: https://developer.android.com/jetpack/androidx/releases/hilt#1.3.0 "AndroidX Hilt 1.3.0 release"
[H8]: https://dl.google.com/dl/android/maven2/androidx/hilt/hilt-lifecycle-viewmodel-compose/1.3.0/hilt-lifecycle-viewmodel-compose-1.3.0.pom "Hilt Compose VM 1.3.0 POM"
[H9]: https://dl.google.com/dl/android/maven2/androidx/hilt/hilt-lifecycle-viewmodel-compose/1.3.0/hilt-lifecycle-viewmodel-compose-1.3.0.aar "Hilt Compose VM 1.3.0 AAR 元数据"
[H10]: https://github.com/google/dagger/releases/tag/dagger-2.58 "Dagger/Hilt 2.58 release"
[H11]: https://github.com/google/dagger/blob/dagger-2.58/java/dagger/hilt/android/plugin/main/src/main/kotlin/dagger/hilt/android/plugin/HiltGradlePlugin.kt "Hilt 2.58 的 AGP 最低版本检查"
[H12]: https://github.com/google/dagger/blob/dagger-2.58/gradle/libs.versions.toml "Hilt 2.58 上游 Kotlin/KSP 版本声明"
[H13]: https://github.com/google/dagger/releases/tag/dagger-2.59 "Hilt Gradle 插件 AGP9 breaking change"
[H14]: https://dagger.dev/dev-guide/ksp.html "Dagger KSP stable 门槛与生成类型限制"
[P1]: https://github.com/google/ksp/releases/tag/2.2.20-2.0.3 "KSP 保守候选正式 release"
[P2]: https://github.com/google/ksp/blob/2.2.20-2.0.3/README.md "候选版本默认 KSP2"
[P3]: https://github.com/google/ksp/releases/tag/2.3.0 "KSP 版本与 Kotlin 解耦"
[P4]: https://github.com/google/ksp/releases/tag/2.3.12 "KSP 最低 AGP 升至8.12"
[A1]: https://github.com/mikepenz/AboutLibraries/blob/15.2.0/README.md "AboutLibraries 接入、定义、Disclaimer 与资源收缩"
[A2]: https://github.com/mikepenz/AboutLibraries/blob/15.2.0/MIGRATION.md "AboutLibraries 固定版本迁移指南"
[A3]: https://github.com/mikepenz/AboutLibraries/releases/tag/15.2.0 "核对日当前稳定 release"
[A4]: https://repo.maven.apache.org/maven2/com/mikepenz/aboutlibraries-compose-m3-android/15.2.0/aboutlibraries-compose-m3-android-15.2.0.pom "AboutLibraries 15.2.0 M3 POM"
[A5]: https://repo.maven.apache.org/maven2/com/mikepenz/aboutlibraries-compose-m3-android/15.2.0/aboutlibraries-compose-m3-android-15.2.0.aar "AboutLibraries 15.2.0 minCompileSdk37"
[A6]: https://github.com/mikepenz/AboutLibraries/blob/13.1.0/README.md "AboutLibraries 13.1.0 官方接入"
[A7]: https://repo.maven.apache.org/maven2/com/mikepenz/aboutlibraries-compose-m3-android/13.1.0/aboutlibraries-compose-m3-android-13.1.0.pom "AboutLibraries 13.1.0 Kotlin/Compose/core 依赖"
[A8]: https://github.com/mikepenz/AboutLibraries/blob/13.1.0/plugin-build/plugin/build.gradle.kts "AboutLibraries 13.1.0 插件 Java11 target/toolchain"
[A9]: https://github.com/mikepenz/AboutLibraries/blob/15.2.0/config/libraries/lib_first.json "官方自定义 library 示例"
[A10]: https://github.com/mikepenz/AboutLibraries/blob/15.2.0/config/licenses/lic_first.json "官方自定义 license 原文示例"
[A11]: https://github.com/mikepenz/AboutLibraries/blob/15.2.0/aboutlibraries-compose-m3/src/commonMain/kotlin/com/mikepenz/aboutlibraries/ui/compose/m3/SharedLibraries.kt "Material3 LibrariesContainer 包/API"
[A12]: https://dl.google.com/dl/android/maven2/androidx/core/core-ktx/1.17.0/core-ktx-1.17.0.aar "core-ktx1.17.0 compileSdk36/AGP8.9.1 门槛"
[T1]: https://kotlinlang.org/docs/gradle-configure-project.html "KGP Gradle/AGP fully supported 矩阵"
[T2]: https://developer.android.com/build/releases/agp-8-11-0-release-notes "AGP8.11/8.11.1 Gradle8.13 JDK17 API36"
[T3]: https://developer.android.com/build/releases/agp-8-13-0-release-notes "AGP8.13.2 Kotlin2.3 支持与兼容表"
[T4]: https://developer.android.com/build/jdks "Gradle JDK、源码与字节码目标"
[T5]: https://developer.android.com/develop/ui/compose/compiler "Compose compiler Gradle plugin"
[T6]: https://developer.android.com/develop/ui/compose/bom "BOM 与 compiler 的版本职责"
[T7]: https://docs.gradle.org/current/userguide/version_catalogs.html "Version catalog 不强制最终解析版本"
[T8]: https://developer.android.com/guide/topics/manifest/uses-sdk-element "targetSdk 与运行兼容行为"
[C1]: https://chaquo.com/chaquopy/doc/15.0/android.html "Chaquopy15 Python/pyc/单模块约束"
[C2]: https://chaquo.com/chaquopy/doc/16.1/android.html "Chaquopy16.1.0 AGP/SDK/Python/单模块约束"
[C3]: https://chaquo.com/chaquopy/doc/current/versions.html "Chaquopy 官方版本摘要"
[A13]: https://github.com/mikepenz/AboutLibraries/blob/13.1.0/gradle/libs.versions.toml "AboutLibraries 13.1.0 上游 serialization 版本"
