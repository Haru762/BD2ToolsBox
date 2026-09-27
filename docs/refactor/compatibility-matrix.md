# 工具链兼容矩阵（T13）

- **核对与验证日期：** 2026-09-26（Asia/Shanghai）
- **探针：** [`tools/compat-probe`](../../tools/compat-probe)，独立 Gradle 根，生产 `settings.gradle.kts` 不 include 它。
- **结论范围：** 下表是**经本探针实际构建并在设备上运行**的组合。它是 T14 升级生产配置的依据，但 T13 本身不修改生产配置（生产仍为 AGP 8.4.1 / Kotlin 1.9.0 / Chaquopy 15.0.1）。
- 设备覆盖、命令与退出码见 [T13 证据](evidence/T13.md)。
- **2026-09-27 更新：** T14 已按本表升级生产构建链（Hilt/KSP、serialization、AboutLibraries 留给 T16/T35/T38）。「当前生产」一列是 T13 时的状态。升级中发现 Chaquopy 16 把 bootstrap 扩展改名为 `*.cpython-38.so`，APK 内 Python 补丁号变为 3.8.20，见 [T14 证据](evidence/T14.md)。

## 1. 已验证组合

| 组件 | 当前生产 | 已验证候选 | 实际解析 / 核对依据 |
|---|---|---|---|
| Gradle 运行 JDK | 17.0.12 | **17.0.12**（`D:/java17`） | AGP 8.x 要求 JDK 17 |
| Gradle wrapper | 8.9 | **8.13**，`distributionSha256Sum=20f1b117…aed78` | 官方 `.sha256` 文件；AGP 8.11 的最低/默认 Gradle |
| AGP | 8.4.1 | **8.11.1** | Google Maven POM 200；支持最大 API 36 |
| Kotlin / KGP | 1.9.0 | **2.2.20** | KGP 2.2.20 fully supported：Gradle 7.6.3–8.14、AGP 7.3.1–8.11.1 |
| Compose compiler | extension 1.5.1 | **`org.jetbrains.kotlin.plugin.compose` 2.2.20** | 与 Kotlin 同版本；不再有 `kotlinCompilerExtensionVersion` |
| serialization 编译插件 | —（Gson） | **2.2.20** | 与 Kotlin 同版本 |
| kotlinx-serialization-json | — | **1.9.0** | 实际解析 1.9.0；JSON runtime 独立于 Kotlin |
| KSP | — | **2.2.20-2.0.3**（KSP2 默认） | Gradle Plugin Portal 有该版本；生成 36 个 Hilt Java 源文件，无 kapt |
| Hilt / Dagger | — | **2.58**（插件/runtime/compiler 同版本） | 实际解析 `hilt-android:2.58`、`dagger:2.58`；`hilt-lifecycle-viewmodel-compose` 要求的 2.54 被抬到 2.58 |
| AndroidX Hilt Compose VM | — | **`androidx.hilt:hilt-lifecycle-viewmodel-compose:1.3.0`** | AAR：minCompileSdk 35、minAGP 8.6.0 |
| Compose BOM | 2024.06.00 | **2025.08.00** | ui/runtime/foundation **1.9.0**、material3 **1.3.2**；AAR：minCompileSdk 35、minAGP 8.6.0 |
| AboutLibraries | — | **13.1.0**（`com.mikepenz.aboutlibraries.plugin.android` + `aboutlibraries-compose-m3`） | M3 AAR 无 SDK 门槛；传递 core-ktx 1.17.0；自动生成 109 条库定义 |
| androidx.core | 1.13.1 | **1.17.0**（显式声明） | AAR：minCompileSdk 36、minAGP 8.9.1 |
| Chaquopy | 15.0.1 | **16.1.0** | 官方版本表：Python 3.8–3.13、AGP 7.0–8.13 |
| APK 内 Python | 3.8 | **3.8**（不变） | 设备上 `sys.version_info` = 3.8 |
| buildPython | 3.11.9 | 3.11.9（不变） | pyc 不生成（minor 不匹配，与 T12 相同的已知警告） |
| compileSdk | 34 | **36** | 由 core-ktx 1.17.0 的 AAR 门槛驱动 |
| targetSdk | 34 | **34**（不变） | T15 裁定保持：升 35+ 需另做 dataSync 6h 超时与 edge-to-edge 验证；API 36 设备实测兼容（[T15.md](evidence/T15.md)） |
| minSdk | 29 | **29**（不变） | 未提高 |
| ABI | arm64-v8a、x86_64 | **arm64-v8a、x86_64**（不变） | APK `native-code: 'arm64-v8a' 'x86_64'` |
| Java/JVM 字节码目标 | 11 | **11**（不变） | 运行 JDK 17 不强制改字节码目标 |

`:app:releaseRuntimeClasspath` 的完整解析坐标（252 条）见 [compat-probe-resolved-release.txt](compat-probe-resolved-release.txt)。Compose 族只解析出一个版本（1.9.0），没有重复；kotlin-stdlib 统一抬到 2.2.20。

## 2. 生产代码的迁移风险（已核对）

- **`AndroidPlatform.ABI` 仍是 public static 字段。** 对 Chaquopy 15.0.1 与 16.1.0 的 `chaquopy_java` JAR 做 `javap` 比对，`AndroidPlatform` 的字段和方法签名完全相同；16.1.0 构造器仍从 `Build.SUPPORTED_ABIS` 选 ABI，再用 `ABI` 拼 `bootstrap-native/` 与 `stdlib-` 路径。所以 T12 的 `PythonRuntime` ABI 适配在 16.1 下的语义不变。T14 仍须在两个 ABI 上重跑 `ReleasePythonRuntimeProbeTest`。
- **pip wheel：** 探针用 16.1.0 为两个 ABI 取到 `lz4==4.3.2` 与 `Pillow==9.2.0`（cp38 native wheel），设备上能 import 和运行。其余 7 个生产依赖是纯 Python 或同一来源，T14 仍须对完整的 `requirements-android.txt` 重新冷解析，并更新 `python-dependencies.md`。
- **minified release 测试方式：** 让 androidTest 针对 minified release（`testBuildType=release`）时，Application 创建阶段报 `NoSuchMethodError`，原因是测试包按 release mapping 调用了被 R8 内联的方法。这与 T04 在 `release-probe` 顶部记录的问题相同，不是依赖不兼容。因此 release 改由 App 内的 `selfCheck` 入口验证，不依赖测试包。

## 3. 被排除或未采用的组合

| 组合 | 排除原因 |
|---|---|
| Hilt ≥ 2.59 | 2.59 release notes：Hilt Gradle 插件要求 AGP 9 |
| Compose BOM ≥ 2026.09.00 | ui 1.12.1 的 AAR 要求 minCompileSdk 37、minAGP 9.1.0 |
| AboutLibraries 15.x | v15 `.android` 插件要求 AGP ≥ 8.13；15.2.0 M3 AAR 要求 compileSdk 37 |
| KSP 2.3.12 | 最低 AGP 8.12.0，高于本组合的 8.11.1 |
| Chaquopy 17.0 | 应用内 Python 只支持 3.10–3.14，会强制改目标解释器和全部 wheel，超出 T13/T14 的范围 |
| Kotlin 2.3 + AGP 8.13 | KGP 2.2.20 fully supported 的 AGP 上限是 8.11.1；换 2.3 需要重新验证 Hilt/KSP/AboutLibraries，本轮未做 |

## 4. 显式约束与不满足之处

- **Dagger KSP 的官方 stable 门槛是 Dagger 2.60+ 且 KSP 2.3.9+，本组合（2.58 / 2.2.20-2.0.3）达不到。** 本组合是"KSP2 可用且本探针验证通过"，不是"满足当前官方 stable 声明"。要满足该声明需迁移 AGP 9，这是一个独立决策。
- **Hilt 2.59+ 要求 AGP 9**：本组合停在 2.58。
- **AboutLibraries 的 compileSdk 门槛**：13.1.0 本身没有门槛，但它传递的 core-ktx 1.17.0 要求 compileSdk 36，因此 compileSdk 从 34 升到 36。targetSdk 不随之改变。
- **AGP 8.12 起的新资源收缩管线**：本组合是 AGP 8.11.1，不涉及。探针 release 中 `aboutlibraries.json` 被 R8 资源混淆改名为 `res/M7.json`，但资源 ID 映射完好，运行时能读出 109 条。T14 若升级到 AGP ≥ 8.12，须重新验证 AboutLibraries 资源在新管线下的保留情况。
- **pyc**：buildPython 3.11.9 与目标 3.8 的 minor 不一致，pyc 不生成，与 T12 相同。本组合不改变这一点，也不把 pyc 当作减包手段。
- **设备覆盖**：x86_64 AVD（API 34）上 debug 与 minified release 都通过。原生 arm64 真机在本轮未运行探针，见 T13 证据。
