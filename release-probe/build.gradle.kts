// 自足的 release 探针 APK（T04）。
//
// 它**刻意不依赖 :app**，因此：
//   * 不会被 AGP 像 androidTest 包那样把「被测 app 已提供的类」去重掉；
//   * 不会被 R8 按被测 app 的映射改写引用。
// 这两点是能驱动**预先构建好的** minified release 包（T03 冻结基线）的前提：
//
//   1. 拿 :app 的 debug androidTest 包驱动 release app
//      → ClassNotFoundException: kotlin.jvm.internal.Intrinsics
//        （release 里它已被改名为 P1.h）
//   2. 让 androidTest 走 release 变体（testBuildType=release）
//      → NoSuchMethodError: P1.h.e(Object,String)V
//        （测试包按**当前源码**的映射混淆，而冻结基线里 R8 已把该方法消掉）
//
// 只要测试包自带一份运行时（kotlin-stdlib / androidx.test），被测包叫什么名字都无所谓。
// 生产包完全不被触碰：不加 keep 规则、不改 DTO、不放松任何断言。
//
// 这个模块不是产品的一部分，不参与发布；它只在 Test-Bd2ReleaseSmoke 里被构建和使用。
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// 探针自己的发版号（1 / "1.0"）刻意**不**走 gradle/version.properties：
// 那是 App 的发布身份，探针不参与发布，共用一个文件反而会让「改 App 发版号」
// 看起来像是在改探针。探针版本只在这里，且没有下游消费者。
val probeVersionCode = 1
val probeVersionName = "1.0"

android {
    namespace = "com.bd2toolsbox.releaseprobe"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.bd2toolsbox.releaseprobe"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = probeVersionCode
        versionName = probeVersionName
    }

    // 探针自己不做收缩：它的运行时必须完整保留，否则又回到上面那两个坑里。
    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.javaVersion.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.javaVersion.get())
    }

    kotlinOptions {
        jvmTarget = libs.versions.javaVersion.get()
    }
}

dependencies {
    implementation(libs.androidx.test.core)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.junit)
    implementation(libs.kotlin.stdlib)
}
