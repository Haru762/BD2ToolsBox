import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "com.bd2toolsbox"
    compileSdk = 34

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
        minSdk = 26
        targetSdk = 34
        versionCode = 15
        versionName = System.getenv("APP_VERSION_NAME") ?: "0.2.13"

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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        compose = true
        aidl = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.google.code.gson:gson:2.10.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.valentinilk.shimmer:compose-shimmer:1.2.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
