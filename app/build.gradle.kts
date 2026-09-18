import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.chaquopy)
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

        // v0.2.2 起发分架构 APK：手机装 arm64-v8a、模拟器装 x86_64，
        // 各自只含对应架构的 Chaquopy 原生库（体积约减半），模拟器也不会
        // 再被 PackageManager 挑成 arm64 走 libhoudini 转译。通用包继续出。
        splits {
            abi {
                isEnable = true
                reset()
                include("arm64-v8a", "x86_64")
                isUniversalApk = true
            }
        }
        
        ndk {
            abiFilters.addAll(listOf("x86_64", "arm64-v8a"))
        }
    }

    buildTypes {
        debug {
            ndk {
                abiFilters.addAll(listOf("x86_64", "arm64-v8a"))
            }
        }
        release {
            isMinifyEnabled = true
            ndk {
                abiFilters.add("arm64-v8a")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
                "build/generated/chaquopy/proguard.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.javaVersion.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.javaVersion.get())
    }

    kotlinOptions {
        jvmTarget = libs.versions.javaVersion.get()
    }

    buildFeatures {
        compose = true
        aidl = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

chaquopy {
    defaultConfig {
        // 构建机上用哪个 Python 来装 pip 包。Chaquopy 15 只支持 3.8–3.11，
        // 而系统 PATH 上常常是更新的版本（3.13），所以多半要显式指定。
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
            install("Pillow")
            install("lz4")
            install("brotli")
            install("fsspec")
            install("attrs")
            install("requests")
            install("beautifulsoup4")
            install("protobuf")
            install("tqdm")
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
    implementation(libs.gson)
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
}
