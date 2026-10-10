package com.bd2toolsbox.core.model

/**
 * 外观模式（T27）。旧用户没有这个键时默认 [LIGHT]，保持当前外观不变。
 *
 * 这是核心模型，不依赖 Compose 的 `ColorScheme` 或 Android `Context`；
 * 具体配色由 UI 层的 `AppTheme` 决定（那是「配色预设」，与模式是两个维度）。
 */
enum class ThemeMode {
    LIGHT,
    DARK,
    SYSTEM;

    companion object {
        /** 未知/缺失/大小写不符都回退 [LIGHT]（不抛异常）。 */
        fun fromName(name: String?): ThemeMode =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: LIGHT
    }
}

/**
 * 可迁移的外观配置（T27）。
 *
 * - [themeMode]：明/暗/跟随系统，缺失默认 [ThemeMode.LIGHT]；
 * - [colorPreset]：配色预设名（对应 UI 层 `AppTheme` 的名字），未知/空回退默认；
 * - [dynamicColor]：动态取色开关；
 * - [wallpaperUri]：自定义壁纸的 URI 字符串（可为空）；
 * - [scrim]：壁纸上的背景遮罩值，限定在 `0f..1f`，损坏值回退默认。
 *
 * 不含任何 Android 类型，可在 JVM 单测里直接构造。
 */
data class AppearanceSettings(
    val themeMode: ThemeMode = ThemeMode.LIGHT,
    val colorPreset: String = DEFAULT_COLOR_PRESET,
    val dynamicColor: Boolean = false,
    val wallpaperUri: String? = null,
    val scrim: Float = DEFAULT_SCRIM
) {
    companion object {
        const val DEFAULT_COLOR_PRESET = "PURPLE"
        const val DEFAULT_SCRIM = 0.82f
    }
}
