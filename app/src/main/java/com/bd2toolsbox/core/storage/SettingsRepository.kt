package com.bd2toolsbox.core.storage

import com.bd2toolsbox.core.model.AppearanceSettings
import com.bd2toolsbox.core.model.ThemeMode

/**
 * 用户设置的唯一读写入口（T27）。
 *
 * 保留既有 `SharedPreferences` 键值（不换存储框架），只把散落在 ViewModel 里的
 * 读写与迁移/回退逻辑收拢到一处：
 * - 旧用户缺 `theme_mode` → [ThemeMode.LIGHT]，外观不变；
 * - 旧单目录键 `mod_source_dir_uri` → 新键 `mod_source_dirs`；
 * - 未知配色预设名、损坏的遮罩值、非法画质都安全回退，不抛异常、不丢其它设置。
 *
 * URI 的持久读权限校验属于 Android 侧（`ContentResolver`），不在这里做；
 * 本类只负责字符串层面的迁移与回退。
 */
class SettingsRepository(
    private val store: SettingsStore,
    /** 已知配色预设名；为空表示不校验（保留原值）。由 UI 层传入 `AppTheme` 的名字集合。 */
    private val knownColorPresets: Set<String> = emptySet()
) {
    companion object {
        const val KEY_USE_ASTC = "use_astc"
        const val KEY_BACKUP_ORIGINALS = "backup_originals"
        const val KEY_DYNAMIC_COLOR = "dynamic_color"
        const val KEY_SELECTED_QUALITY = "selected_quality"
        const val KEY_APP_THEME = "app_theme"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_ONBOARDING_DONE = "onboarding_done"
        const val KEY_WALLPAPER_SCRIM = "wallpaper_scrim"
        const val KEY_WALLPAPER_URI = "wallpaper_uri"
        const val KEY_MOD_SOURCE_DIRS = "mod_source_dirs"
        const val KEY_LEGACY_MOD_SOURCE_DIR_URI = "mod_source_dir_uri"

        /** 多目录在字符串键里的分隔符（与旧实现一致）。 */
        const val DIR_SEPARATOR = "\n"
        const val DEFAULT_QUALITY = "HD"
    }

    fun appearance(): AppearanceSettings {
        val raw = store.all()
        return AppearanceSettings(
            themeMode = ThemeMode.fromName(raw[KEY_THEME_MODE] as? String),
            colorPreset = sanitizePreset(raw[KEY_APP_THEME] as? String),
            dynamicColor = raw[KEY_DYNAMIC_COLOR] as? Boolean ?: false,
            wallpaperUri = (raw[KEY_WALLPAPER_URI] as? String)?.takeIf { it.isNotBlank() },
            scrim = sanitizeScrim(raw[KEY_WALLPAPER_SCRIM])
        )
    }

    fun saveAppearance(settings: AppearanceSettings): Boolean = store.put(
        mapOf(
            KEY_THEME_MODE to settings.themeMode.name,
            KEY_APP_THEME to sanitizePreset(settings.colorPreset),
            KEY_DYNAMIC_COLOR to settings.dynamicColor,
            KEY_WALLPAPER_URI to settings.wallpaperUri,
            KEY_WALLPAPER_SCRIM to sanitizeScrim(settings.scrim)
        )
    )

    /** 画质只认 SD/HD；历史 FHD 等非法值回退 HD。 */
    fun quality(): String = sanitizeQuality(store.all()[KEY_SELECTED_QUALITY] as? String)

    fun setQuality(quality: String): Boolean =
        store.put(mapOf(KEY_SELECTED_QUALITY to sanitizeQuality(quality)))

    fun backupOriginals(): Boolean = store.all()[KEY_BACKUP_ORIGINALS] as? Boolean ?: true
    fun setBackupOriginals(enabled: Boolean): Boolean =
        store.put(mapOf(KEY_BACKUP_ORIGINALS to enabled))

    fun useAstc(): Boolean = store.all()[KEY_USE_ASTC] as? Boolean ?: false
    fun setUseAstc(enabled: Boolean): Boolean = store.put(mapOf(KEY_USE_ASTC to enabled))

    fun onboardingDone(): Boolean = store.all()[KEY_ONBOARDING_DONE] as? Boolean ?: false
    fun setOnboardingDone(done: Boolean): Boolean = store.put(mapOf(KEY_ONBOARDING_DONE to done))

    fun clearWallpaperUri(): Boolean = store.put(mapOf(KEY_WALLPAPER_URI to null))

    /** 迁移：新键优先，旧单目录键兜底。 */
    fun sourceDirs(): List<String> {
        val raw = store.all()
        val saved = raw[KEY_MOD_SOURCE_DIRS] as? String
        val legacy = raw[KEY_LEGACY_MOD_SOURCE_DIR_URI] as? String
        return when {
            saved != null -> saved.split(DIR_SEPARATOR).map { it.trim() }.filter { it.isNotBlank() }
            !legacy.isNullOrBlank() -> listOf(legacy)
            else -> emptyList()
        }
    }

    /** 落一次新格式，并清掉旧单目录键（迁移完成）。 */
    fun setSourceDirs(dirs: List<String>): Boolean = store.put(
        mapOf(
            KEY_MOD_SOURCE_DIRS to dirs.joinToString(DIR_SEPARATOR),
            KEY_LEGACY_MOD_SOURCE_DIR_URI to null
        )
    )

    private fun sanitizeQuality(value: String?): String =
        if (value == "SD" || value == "HD") value else DEFAULT_QUALITY

    private fun sanitizePreset(value: String?): String {
        val candidate = value?.takeIf { it.isNotBlank() } ?: return AppearanceSettings.DEFAULT_COLOR_PRESET
        if (knownColorPresets.isNotEmpty() && candidate !in knownColorPresets) {
            return AppearanceSettings.DEFAULT_COLOR_PRESET
        }
        return candidate
    }

    private fun sanitizeScrim(value: Any?): Float {
        val f = when (value) {
            is Float -> value
            is Number -> value.toFloat()
            is String -> value.toFloatOrNull()
            else -> null
        } ?: return AppearanceSettings.DEFAULT_SCRIM
        return if (f.isFinite() && f in 0f..1f) f else AppearanceSettings.DEFAULT_SCRIM
    }
}
