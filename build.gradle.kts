// Top-level build file where you can add configuration options common to all sub-projects/modules.
//
// 版本从 gradle/libs.versions.toml 读；这里是**唯一**声明插件版本的地方，
// 各模块用 alias(libs.plugins.*) 引用，不再各自写字面量。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.chaquopy) apply false
}
