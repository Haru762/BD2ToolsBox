# R8 与 APK 包体审计（现成 0.2.16 产物）

- **日期 / 源码基线：** 2026-09-14，b83c1c0。只读检查现成 APK、源码配置、R8 报告与 ELF，未重建、安装、重签名或修改任何 APK/业务代码。
- **主对象：** [arm64 release APK](<D:/bd2 mod/bdroid_src/app/build/outputs/apk/release/app-arm64-v8a-release.apk>)。它与 [发布目录中的 arm64 APK](<D:/bd2 mod/发布包/2-手机端转换器/BD2ToolsBox-0.2.16-android-arm64-v8a.apk>) SHA-256 一致：A52FA13909E7D4B8D79DD3B4D2BA4054693FDD9324C68FC850A0785BD713BEE8。
- **单位：** MiB = 1,048,576 字节。分类统计为 APK ZIP entry 的实际 compressed payload；容器头、对齐、签名等另计。内部 IMY 用其内层 ZIP compressed payload。不是安装后磁盘占用或运行内存。
- [原始测量数据](<D:/bd2 mod/bdroid_src/docs/research/2026-09-14-apk-size-inventory.json>) · [官方平台约束核对](<D:/bd2 mod/bdroid_src/docs/research/2026-09-14-r8-platform-constraints.md>)

## 一、先纠正基线：R8 已经执行

当前 [release 配置](<D:/bd2 mod/bdroid_src/app/build.gradle.kts#L60>) 已有 isMinifyEnabled=true 和 proguard-android-optimize.txt。

不是只看配置推测：现成 [mapping.txt](<D:/bd2 mod/bdroid_src/app/build/outputs/mapping/release/mapping.txt>) 记录 compiler=R8、compiler_version=8.4.26，且有 configuration/seeds/usage。aapt2 确认 release 的 package/version 是 com.bd2toolsbox / 0.2.16 / 17，没有 debuggable 标志；debug 包有该标志。

| 架构 | debug APK | release APK |
|---|---:|---:|
| arm64-v8a | 89.704 MiB | 32.516 MiB |
| x86_64 | 89.563 MiB | 32.375 MiB |
| universal | 98.735 MiB | 41.547 MiB |

arm64 的 DEX 从 debug 的 **12 个、53.685 MiB**，变为 release 的 **1 个、2.744 MiB**。这说明应以现成 release 为优化基线，而不是承诺再把 debug 那一大截“通过打开 R8”减一次。

**归因限制：** debug/release 还包含调试依赖、代码生成/打包等差异，不能把 APK 差值全部归因给 R8，也不能把这两个现成产物冒充本轮同源重建的 A/B 实验。

## 二、arm64 release 的实际组成

| 类别 | APK 内占用 | 占总包约 |
|---|---:|---:|
| Chaquopy 的 Python/扩展归档与 bootstrap assets | 19.835 MiB | 61.0% |
| lib/ 下原生库 | 9.114 MiB | 28.0% |
| DEX | 2.744 MiB | 8.4% |
| Android res/resources.arsc | 0.417 MiB | 1.3% |
| WebView/其他 assets、元数据及 ZIP/签名/对齐开销 | 0.406 MiB | 1.2% |
| **总计** | **32.516 MiB** | **100%（分项四舍五入）** |

**Python 与 native 合计约 89.03%。** R8 不能自动裁剪这些 Python 模块、IMY 内二进制、ELF 或 JS。当前继续从 DEX 上精修有价值，但不是这份包体的最大杠杆。

## 三、优先级最高的三个包体候选

### A. 单 ABI APK 仍携带另一 ABI 的 Python 内容

arm64 APK 的 lib/ 确实只包含 arm64，但 assets/chaquopy/ 仍有14个 x86_64 项，合计 **4.658 MiB**：

- requirements-x86_64.imy：1.668 MiB；
- stdlib-x86_64.imy：1.411 MiB；
- bootstrap-native/x86_64/ 下的扩展库等：其余约1.579 MiB。

x86_64 APK 中也有14个 arm64 项，约 **4.350 MiB**。因此不能从 APK 名字或 native-code 标志推断 Python 打包输入已经单架构。

**方向：** 按 Chaquopy15 官方建议采用 ABI product flavor dimension，每个 variant 的有效 abiFilters 只有所需 ABI；整理现有 defaultConfig/buildType/splits 的合并关系，避免默认两个 ABI 又在 flavor 里仅追加一个。不要继续叠出多重 split/flavor 组合。

**注意：** 官方说标准 ABI splits/app bundle 对 Chaquopy 的帮助有限，不是“开启 splits 就一定构建失败”。这次 splits 已减少 lib/ 的另一架构，但没有完整裁掉 Python assets。具体净节省要用正确配置重建后测量，不能直接从 APK 中删除 ZIP entry。

保留现有发布命名及更新器的架构选择契约；Google Play 多 APK 与本项目直接 APK 分发的 versionCode 策略分别处理，不擅自更改现有升级规则。

### B. Android Python 归档中带入了桌面 FMOD 二进制

app.imy 本身6.033 MiB，其中 **8 个 FMOD 二进制占5.531 MiB**：Windows DLL、Darwin dylib、Linux 的 arm/arm64/x86/x86_64 SO。仅 Windows/Mac 两类就占 **3.045 MiB**。

本轮还解析了4个 Linux FMOD ELF 的 DT_NEEDED，均依赖 libc.so.6、libpthread.so.0、libstdc++.so.6 等 GNU/Linux运行库，不能把它们视为可直接使用的 Android/Bionic 版本。

[AudioClipConverter.py](<D:/bd2 mod/bdroid_src/app/src/main/python/vendor/UnityPy/export/AudioClipConverter.py>) 把 pyfmodex/FMOD 加载推迟到音频导出调用；源码检索未发现 Android 业务入口主动调用这些音频导出。当前 [unpacker](<D:/bd2 mod/bdroid_src/app/src/main/python/unpacker.py>) 的主要导出路径是纹理、文本/骨架等。不过“静态未找到调用”不替代完整导入与功能回归。

**方向：** 给 Android 构建生成受控 Python 输入目录，过滤这些不适用的桌面二进制，再用 Chaquopy 官方支持的 sourceSets 输入绑定。保留上游源目录/来源信息，不为了 Android 包体直接破坏桌面端可能共享的 vendor；不发明 chaquopy.exclude/pip.exclude 之类未核实的 DSL。

**不要顺手删除** AudioClip Python 类型、整个 export 包或 TypeTree 数据库。它们可能参与通用解析/模块初始化。应只裁明确不适用的资源，并验证 import UnityPy、纹理/骨架解包、重打包和主流程。若保留 FMOD 二进制，还需独立核对其来源/许可，不应自动认为 UnityPy 的许可覆盖所有附带二进制。

对比：此前看起来很大的 generated.py，在归档中压缩后仅约 **0.051 MiB**；必要的 uncompressed.tpk 压缩后约 **0.262 MiB**。代码行数大并不等于主要包体来源。

### C. 两个随包原生库仍含调试节和静态符号

本轮解析的是 **APK 内的 SO**，不是只看开发目录的未处理文件：

| 库 | 随包大小 | .debug* | .symtab/.strtab | 两类节合计 |
|---|---:|---:|---:|---:|
| libastcenc.so | 1.634 MiB | 0.940 MiB | 0.196 MiB | 1.136 MiB |
| libtexture2ddecoder.so | 0.994 MiB | 0.296 MiB | 0.187 MiB | 0.484 MiB |
| **合计候选** | | | | **1.620 MiB** |

其他已检查的 arm64 lib/ 库未发现这两类额外节。当前 SO 是 stored entry，所以上述节也确实占 APK 文件字节。

**方向：** 核对 stripReleaseDebugSymbols 与 NDK LLVM 工具链，让正式包使用正确 strip 的副本，保留必要动态符号/JNI/ctypes 导出；未 strip 的符号资料另存，用于 native 崩溃解析。不要删除唯一的带符号源文件，也不要对不同目标乱用宿主 strip。

配置的 SDK 路径下本轮未发现 ndk/ 目录，是需要检查的环境线索；**没有查看当次构建日志，不能把它直接断言为根因**，也不能据此断言机器其他位置没有 NDK。

### 候选池不是已实现节省

arm64 包中的“另一 ABI payload + FMOD二进制 + 上述 ELF 节”当前合计约 **11.809 MiB**，内容来源互不重叠。这是优先检查/处理对象的量级，不是保证能把APK直接减掉11.809 MiB，更不是本轮已产出的优化结果。正确 variant、导入/运行兼容、工具链变化及重新压缩都会影响净结果。

## 四、R8 继续优化，但先把规则修准

### 4.1 当前还有资源收缩缺口

项目未声明 isShrinkResources，已有 minify 不等于已经做资源收缩。后续可以在保留原有 Chaquopy/ProGuard 配置的前提下启用：

~~~kotlin
release {
    isMinifyEnabled = true       // 已有
    isShrinkResources = true    // 候选新增，需 release 回归
}
~~~

当前 Android 资源盘子只有约0.417 MiB，不能指望它节省十几 MiB。仍建议作为规范配置启用，并检查动态资源与后续 AboutLibraries 许可证页。

AGP8.0起默认 R8 full mode，项目内未发现 opt-out；但本轮没有重跑 Gradle 导出所有外部/命令行最终选项，不把默认值当作无条件的最终生效证明。

**版本门槛：** optimized resource shrinking 在 AGP8.12 引入，8.12/8.13 可选择启用；AGP9 在开启资源收缩时自动使用新管线。上轮候选 **8.11.1 不能宣称有这个新管线**，普通资源收缩仍可使用。不为这一小块体积独自打破 Kotlin/Hilt/Chaquopy 的兼容矩阵。

### 4.2 当前 keep 规则既有冗余，也有真实缺口

[项目规则](<D:/bd2 mod/bdroid_src/app/proguard-rules.pro>) 对 com.chaquo.python.**、其 runtime 子包、整个 data.model 包等作了保留。部分 Chaquopy/Gson 规则在库自带 consumer rules 中也存在。

- 删除重复规则通常只是清理配置，不能直接算包体收益。
- 全包 keep 会限制优化，但 JNI、Python 按名称访问 Java/Kotlin 的边界不能盲删。
- 用 usage/seeds/configuration 和 why-are-you-keeping 思路定位真实保留原因；按入口/字段收紧，而不是全局 dontwarn 或关闭必要保护。
- Gson 模型转到显式 DTO/kotlinx.serialization 后，可以重新审视宽泛 model keep；迁移之前应保留真实的反射兼容性。
- 升级 AGP 会带入相应 R8，默认不单独强行覆盖内置 R8 版本。

### 4.3 已发现一个实际 release 快照契约风险

源码 [MainViewModel.ModListSnapshot](<D:/bd2 mod/bdroid_src/app/src/main/java/com/bd2toolsbox/ui/viewmodel/MainViewModel.kt#L2955>) 没有显式稳定 JSON 字段名，仍通过 Gson 自动反射读写。

现成 release 的 mapping 显示：

~~~text
MainViewModel$ModListSnapshot -> r1.p
savedAt    -> a
mods       -> b
sourceDirs -> c
~~~

进一步检查实际 classes.dex：Lr1/p; 的父类是 Object，实例字段只有 a:J、b:List、c:List，**已没有 version 字段**。而源码仍用 snapshot.version 与 SNAPSHOT_VERSION 比较。

**结论边界：** 字段改名/删除是产物事实，不是猜测；Gson 按字段反射的存储协议不能继续假定与源码名称一致。旧明文键快照、跨 minified 版本快照和 schema 检查必须做发布版兼容测试。尚未在设备上测量具体冷启动表现，不把它夸大为整个 App 无法运行或所有安装账本都会损坏。

**方向：** 先给持久化协议明确稳定字段名与精确保留规则，或改为显式序列化 DTO，再谈进一步收紧。这里的缓存可以按明确 schema 失效，但不能让 R8 隐式改变格式。不要为了绕过此问题整体关闭 R8，回到近90MiB的debug基线。

反例也要保留：ModRepository.ModCache 的内部字段虽然同样重命名，但它的落盘代码手工写 schemaVersion/indexStamp/entries 键，不能只看到映射改名就宣称该协议也坏了。

## 五、其他方案与收益/代价

| 方案 | 适用判断 |
|---|---|
| 修正 ABI flavors | 优先级高；真实存在另一ABI Python payload，按variant正确生成，不手工剪APK |
| Android专用 Python 输入集 | 优先级高；先清不适用的桌面二进制，不凭名字删动态依赖 |
| Native strip | 优先级高；包内已确认有调试/静态符号节，保留外部符号资料与必要导出 |
| 继续R8/资源收缩 | 应做但先修保持规则；当前DEX仅2.744MiB、资源仅0.417MiB，收益有上限 |
| 压缩 native libs | 可降低APK下载体积，但当前 extractNativeLibs=false 支持直接加载；改为压缩/提取可能增大安装后占用并影响安装/启动，需产品取舍 |
| Python pyc | 当前 app有105个.py、requirements-common有361个.py且均无.pyc；目标Python为3.8、extract_packages为空。需核实buildPython/pyc任务；预编译主要影响首次运行与分发方式，不能承诺一定更小 |
| 审查pip可选依赖 | 后续再做；requirements-common总共约1.418MiB，低于错误平台二进制。动态import与Pillow格式插件不能靠静态未引用就删 |
| 限定语言/密度或自选图标 | 根据产品实际支持范围决定；不要拿依赖JAR或debug大小当release收益，当前资源总盘子很小 |
| AAB/按设备分发 | 未来渠道方案；不能自动解决Chaquopy放在assets中的内容，仍需正确variant输入。当前直接发APK先解决ABI flavors |
| 引擎按需下载/轻量版 | 会改变离线功能、总下载量、校验/更新与分发策略，不作为这轮默认方案 |

### Native 压缩的受控测算

仅在内存中以 Deflate level9 压缩当前 arm64 lib/ 字节，9.114MiB约变成3.523MiB。**没有重建、安装或更改包装标志**，这只是压缩负载测算，不是可发布APK结果。它包含当前未strip数据，不能再与1.620MiB strip收益简单相加。

优先先清冗余与符号，再决定“下载文件更小”和“安装后占用/直接加载”谁优先。不要直接重新压缩已经签名的APK，也不要盲改IMY的noCompress；签名、对齐、随机访问和加载方式都需要正确构建支持。

## 六、建议执行顺序

1. **建立发布基线：** 固定源码/工具链/依赖/版本/ABI；保存本轮大小数据、mapping和必要native符号。后续只对比同源同variant的release，分别记录APK字节、安装后空间与关键启动/操作耗时。
2. **先补 release 行为回归：** 快照稳定格式、Python↔Java回调、Shizuku绑定、纹理/骨架解包、重打包与恢复；不能只跑未混淆debug测试。
3. **处理明确的打包输入问题：** ABI flavors、FMOD等不适用平台二进制；每次单独构建对比，核对build.json、IMY和lib/三处是否一致。
4. **完善 native strip：** 校验保留导出/各ABI功能，符号独立归档。
5. **精修 R8 与资源收缩：** 先修存储契约与动态入口保护，再缩keep；配合既定AGP升级，不另堆“优化开关”。
6. **建立包体回归门槛：** CI/构建报告记录总包、DEX、Python归档、各ABI ELF、Android资源。禁止Android包重新混入Windows/Mac二进制，单ABI包不得出现未经说明的另一ABI Python归档。

每一步都记录“实测净变化与功能回归”，不靠删源码行数或推测节省比例验收。mapping.txt虽较大，但并不在APK内，不能为缩APK删除崩溃反混淆资料；证书、许可、必要类型数据库也不是优先乱删对象。

## 七、本轮实际做过的验证

- 读取6个现成APK的ZIP central directory；用.NET ZipArchive独立核对arm64 release的189项及compressed总和33840209字节、x86_64资产14项4884646字节。
- 用aapt2检查实际APK的版本、debuggable、native-code与extractNativeLibs=false。
- 对发布目录和构建输出arm64 APK计算SHA-256，结果一致。
- 只在内存展开IMY与ELF、解析节表/DT_NEEDED，未执行任何native库。
- 读取R8配置、mapping、usage/seeds文件信息；解析实际DEX确认快照字段。
- 没有构建对照版本、没有strip或安装APK，也未确认所有候选移除后的功能兼容性。生产源码、构建配置和现有发布包未修改。
