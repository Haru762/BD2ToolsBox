import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 只有这一个模块实际应用 Chaquopy（官方要求：每个应用恰好一个模块）。
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

android {
    namespace = "com.bd2toolsbox.compat"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.bd2toolsbox.compat"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 与生产相同的两个 ABI，不增不减。
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        release {
            // release 走与生产相同的 R8 路径，用来暴露 Hilt/serialization 的 keep 问题。
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 探针不参与发布：用本机 debug 证书签名即可安装。
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.javaVersion.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.javaVersion.get())
    }

    buildFeatures { compose = true }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.fromTarget(libs.versions.javaVersion.get())) }
}

chaquopy {
    defaultConfig {
        // 与生产相同的目标解释器；本探针验证的是「Chaquopy 16.1 + 3.8」这一组合。
        version = "3.8"
        val localPython = Properties().apply {
            rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
        }.getProperty("chaquopy.python") ?: System.getenv("CHAQUOPY_PYTHON")
        if (!localPython.isNullOrBlank()) buildPython(localPython)
        pip {
            // 取生产锁定清单中的两个 native wheel，验证 16.1 仍能为两 ABI 取得 cp38 构件。
            install("lz4==4.3.2")
            install("Pillow==9.2.0")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.aboutlibraries.compose.m3)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
