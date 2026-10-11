import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.chaquopy)
    alias(libs.plugins.aboutlibraries.android)
}

// ---------------------------------------------------------------------------
// App 发布版本（与依赖版本分开维护，见 gradle/version.properties 顶部说明）
//
// 位置：gradle/version.properties 的 appVersionName / appVersionCode
// 覆盖：APP_VERSION_NAME 环境变量（既有行为，保留）
//
// 下面的校验在**配置阶段**执行 —— 值不对就别开始构建，而不是产出包之后才发现。
// 用 GradleException 让 gradlew 以非零退出（println 之类的「软报错」不算失败）。
// ---------------------------------------------------------------------------
val appVersionFile = rootProject.file("gradle/version.properties")
if (!appVersionFile.isFile) {
    throw GradleException("缺少 gradle/version.properties：App 发布版本必须从该文件读取")
}
val appVersionProps = Properties().apply {
    appVersionFile.inputStream().use { load(it) }
}

val appVersionName = run {
    val fromEnv = System.getenv("APP_VERSION_NAME")
    // 环境变量为空串/纯空白时**不**覆盖 —— 那多半是 CI 里变量未设置的常见形态，
    // 静默留空会做出一个 versionName="" 的包。
    val raw = if (fromEnv.isNullOrBlank()) appVersionProps.getProperty("appVersionName") else fromEnv
    if (raw.isNullOrBlank()) {
        throw GradleException(
            "appVersionName 为空：请在 gradle/version.properties 里设置非空的 appVersionName，" +
                "或用 APP_VERSION_NAME 环境变量覆盖"
        )
    }
    raw.trim()
}

val appVersionCode = run {
    val raw = appVersionProps.getProperty("appVersionCode")
    if (raw.isNullOrBlank()) {
        throw GradleException("appVersionCode 为空：请在 gradle/version.properties 里设置为正整数")
    }
    val parsed = raw.trim().toIntOrNull()
        ?: throw GradleException("appVersionCode 不是十进制整数（实际值：'$raw'）")
    if (parsed <= 0) {
        throw GradleException("appVersionCode 必须是正整数（实际值：$parsed）")
    }
    parsed
}

// ---------------------------------------------------------------------------
// T50：Android 侧 Python 源集先在构建目录暂存，再交给 Chaquopy。
//
// `app/src/main/python` 里 vendored 的 UnityPy 随包带着**桌面平台**的 FMOD
// 原生库（macOS dylib / Windows dll / 桌面 Linux glibc so，18 个文件）。它们在
// Android/Bionic 上既不会被加载（`pyfmodex` 根本不在 pip 清单里），也没有调用
// 路径。直接删 vendored 原文件会破坏「第三方来源原样保留」，所以在暂存阶段剔除。
//
// 清单：config/android-python-excludes.json（每条必须在源树里真实命中，否则失败）。
// 暂存逻辑：tools/stage_android_python.py（确定性：先清空、归一 mtime）。
// ---------------------------------------------------------------------------
val androidPythonSourceDir = layout.projectDirectory.dir("src/main/python")
val stagedAndroidPythonDir = layout.buildDirectory.dir("staged-python/main")
val stagedAndroidPythonManifest = layout.buildDirectory.file("staged-python/manifest.json")
val androidPythonExcludeConfig = rootProject.layout.projectDirectory.file("config/android-python-excludes.json")

// 构建机 Python：与 chaquopy.buildPython 同一套查找顺序（local.properties → 环境变量 → PATH）。
val hostPythonExecutable: String = run {
    val localPython = Properties().apply {
        rootProject.file("local.properties")
            .takeIf { it.exists() }
            ?.inputStream()
            ?.use { load(it) }
    }.getProperty("chaquopy.python") ?: System.getenv("CHAQUOPY_PYTHON")
    if (!localPython.isNullOrBlank()) localPython else "python"
}

val stageAndroidPython by tasks.registering(Exec::class) {
    group = "build"
    description = "按 config/android-python-excludes.json 暂存 Android Python 源集（T50）"
    inputs.dir(androidPythonSourceDir).withPropertyName("pythonSource")
    inputs.file(androidPythonExcludeConfig).withPropertyName("excludeConfig")
    inputs.file(rootProject.file("tools/stage_android_python.py")).withPropertyName("stagingTool")
    outputs.dir(stagedAndroidPythonDir).withPropertyName("stagedPython")
    outputs.file(stagedAndroidPythonManifest).withPropertyName("stagingManifest")
    commandLine(
        hostPythonExecutable,
        rootProject.file("tools/stage_android_python.py").absolutePath,
        "--source", androidPythonSourceDir.asFile.absolutePath,
        "--dest", stagedAndroidPythonDir.get().asFile.absolutePath,
        "--config", androidPythonExcludeConfig.asFile.absolutePath,
        "--manifest", stagedAndroidPythonManifest.get().asFile.absolutePath,
        "--quiet"
    )
}

android {
    namespace = "com.bd2toolsbox"
    compileSdk = libs.versions.compileSdk.get().toInt()

    signingConfigs {
        create("release") {
            val storeFilePath = System.getenv("KEYSTORE_PATH")
            if (storeFilePath != null && file(storeFilePath).exists()) {
                storeFile = file(storeFilePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "com.bd2toolsbox"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // T49：把「发分架构 APK」建模为两个 flavor，而不是 ABI splits。
    //
    // 理由：splits 会在同一个 variant 里同时装配两个 ABI 的 Chaquopy 资产再切开，
    // 且默认还会多产一个 universal 包（容易被误上传 / 被更新器选错）。
    // flavor 让 arm64 与 x64 各自成为一个独立 variant，`ndk.abiFilters` 只含自己的 ABI，
    // 因此 lib/ 与 Chaquopy 的 assets/IMY 都只含当前 ABI。
    //
    // 两个 flavor 共用 defaultConfig 的 applicationId / versionCode / versionName /
    // signingConfig，所以「同一应用、同一签名、同一版本」的语义不变；
    // 也不再默认产出 universal。
    flavorDimensions += "abi"
    productFlavors {
        create("arm64") {
            dimension = "abi"
            ndk { abiFilters.add("arm64-v8a") }
        }
        create("x64") {
            dimension = "abi"
            ndk { abiFilters.add("x86_64") }
        }
    }

    buildTypes {
        debug {
        }
        release {
            isMinifyEnabled = true
            // Chaquopy 16.1.0 自己把 build/python/proguard-rules.pro 接入 R8
            // （见 mapping/<variant>Release/configuration.txt 的 section 头）。
            // 这里曾写死 build/generated/chaquopy/proguard.pro —— 该路径在 16.1.0
            // 已不存在，每次 release 构建都会报 “Supplied proguard configuration
            // does not exist” 而实际规则并未缺失（T49 核对后删除）。
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // T50：把 main 源集的 Python 根换成暂存目录（替换而非追加 —— 追加会把
    // 原树里的桌面 FMOD 二进制又带回包里）。
    sourceSets.getByName("main").apply {
        val pythonSourceSet = (this as org.gradle.api.plugins.ExtensionAware)
            .extensions.getByName("python") as org.gradle.api.file.SourceDirectorySet
        pythonSourceSet.setSrcDirs(listOf(stagedAndroidPythonDir))
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.javaVersion.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.javaVersion.get())
    }

    buildFeatures {
        compose = true
        aidl = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// T14：Kotlin 2.x 起 kotlinOptions 已废弃，改用 compilerOptions。字节码目标仍为 11（运行 Gradle 的 JDK 17 不强制改它）。
kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.fromTarget(libs.versions.javaVersion.get())) }
}

chaquopy {
    defaultConfig {
        // 目标解释器：打进 APK、在设备上真正执行 Python 的那个 CPython。
        // 显式写死，不吃 Chaquopy 的默认值 —— 默认值会随插件升级而变
        // （15.0 的默认是 3.8，官方可选 3.8–3.12），显式化之后
        // 「今天解析到的依赖」才有可复现的目标，升级插件也不会无声改解释器。
        version = "3.8"

        // 构建机上用哪个 Python 来装 pip 包。这与上面的目标解释器是**两套**版本概念：
        // 目标解释器是 APK 里的 3.8；构建机 Python 版本要求由当前 Chaquopy 插件决定（参
        // 官方文档）。但 pyc 编译要求构建机与目标 minor 一致；本批实测的构建机是
        // 3.11.9，与目标 3.8 minor 不匹配，所以本项目代码与 pip 依赖的 pyc 不生成
        // （真实构建观察与 IMY 清点见 docs/refactor/python-dependencies.md）。
        // 系统 PATH 上常常是更新的版本，所以多半要显式指定。
        //
        // 不能写死路径 —— 那是某台机器上的绝对路径，别人克隆下来必然构建失败，
        // 而且会把本机用户名带进公开仓库。改成按这个顺序找：
        //   1. local.properties 里的 chaquopy.python（该文件已在 .gitignore 中）
        //   2. 环境变量 CHAQUOPY_PYTHON
        //   3. 都没有就不设，交给 Chaquopy 自己去 PATH 里找
        val localPython = Properties().apply {
            rootProject.file("local.properties")
                .takeIf { it.exists() }
                ?.inputStream()
                ?.use { load(it) }
        }.getProperty("chaquopy.python") ?: System.getenv("CHAQUOPY_PYTHON")

        if (!localPython.isNullOrBlank()) buildPython(localPython)
        pip {
            // 依赖清单在 app/requirements-android.txt：9 个直接依赖全部用 == 锁定，
            // 不用无约束的包名（无约束时一年后的解析结果不保证重现）。
            // 用 Chaquopy 的 requirements 文件语法；清单变化会被记进任务输入，
            // 从而正确触发重新解析。版本来源与传递依赖见
            // docs/refactor/python-dependencies.md。
            install("-r", "requirements-android.txt")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.aboutlibraries.compose.m3)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.shimmer.compose)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // Hilt 依赖注入（T16）：compiler 走 KSP（无 kapt）。
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
}

// ---------------------------------------------------------------------------
// T37：许可证清单。AboutLibraries 按 variant 生成依赖定义（prepareLibraryDefinitions*），
// 与构建绑定而不是手工跑一次。自定义（非 Maven）组件定义在仓库根的
// config/aboutlibraries/libraries.json，与自动收集的 Gradle 依赖合并。
// fetchRemoteLicense=false：保持离线可复现，许可文本来自插件内置 SPDX 映射与
// config/aboutlibraries 下的本地补充。
// ---------------------------------------------------------------------------
aboutLibraries {
    collect {
        // 路径相对模块（:app），见 AboutLibraries 文档；指向仓库根的 config/aboutlibraries。
        configPath.set(file("../config/aboutlibraries"))
        fetchRemoteLicense.set(false)
        fetchRemoteFunding.set(false)
    }
}

// T50：Chaquopy 的 Python 资产任务必须先把源集暂存好。
tasks.configureEach {
    if (name.contains("Python") && name != "stageAndroidPython") {
        dependsOn(stageAndroidPython)
    }
}
