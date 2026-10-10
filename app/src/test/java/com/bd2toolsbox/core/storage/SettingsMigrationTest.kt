package com.bd2toolsbox.core.storage

import com.bd2toolsbox.core.model.AppearanceSettings
import com.bd2toolsbox.core.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 纯 JVM：内存 store 验证 0.2.16 配置迁移与损坏值回退。 */
class SettingsMigrationTest {

    private class FakeStore(initial: Map<String, Any?> = emptyMap()) : SettingsStore {
        val data = HashMap<String, Any?>(initial)
        var failNextPut = false
        var putCount = 0
        override fun all(): Map<String, Any?> = data.toMap()
        override fun put(updates: Map<String, Any?>): Boolean {
            putCount++
            if (failNextPut) {
                failNextPut = false
                return false
            }
            for ((key, value) in updates) {
                if (value == null) data.remove(key) else data[key] = value
            }
            return true
        }
    }

    private val presets = setOf("PURPLE", "BLUE", "TEAL")

    /** 一份典型 0.2.16 配置（没有 theme_mode 键）。 */
    private fun legacyStore() = FakeStore(
        mapOf(
            "selected_quality" to "SD",
            "backup_originals" to false,
            "use_astc" to true,
            "dynamic_color" to true,
            "app_theme" to "TEAL",
            "onboarding_done" to true,
            "wallpaper_scrim" to 0.5f,
            "wallpaper_uri" to "content://wallpaper/1",
            "mod_source_dirs" to "content://tree/a\ncontent://tree/b"
        )
    )

    private fun repo(store: SettingsStore) = SettingsRepository(store, presets)

    @Test fun upgradeFromLegacyKeepsExistingSettings() {
        val store = legacyStore()
        val r = repo(store)
        assertEquals("SD", r.quality())
        assertFalse(r.backupOriginals())
        assertTrue(r.useAstc())
        assertTrue(r.onboardingDone())
        val a = r.appearance()
        assertEquals("TEAL", a.colorPreset)
        assertTrue(a.dynamicColor)
        assertEquals(0.5f, a.scrim, 0.0001f)
        assertEquals("content://wallpaper/1", a.wallpaperUri)
        assertEquals(listOf("content://tree/a", "content://tree/b"), r.sourceDirs())
    }

    @Test fun missingThemeModeDefaultsToLight() {
        val r = repo(FakeStore(mapOf("selected_quality" to "HD")))
        assertEquals(ThemeMode.LIGHT, r.appearance().themeMode)
    }

    @Test fun explicitThemeModesRoundTrip() {
        val store = FakeStore()
        val r = repo(store)
        assertTrue(r.saveAppearance(AppearanceSettings(themeMode = ThemeMode.SYSTEM)))
        assertEquals(ThemeMode.SYSTEM, r.appearance().themeMode)
        assertTrue(r.saveAppearance(AppearanceSettings(themeMode = ThemeMode.DARK)))
        assertEquals(ThemeMode.DARK, r.appearance().themeMode)
    }

    @Test fun unknownColorPresetFallsBackToDefault() {
        val r = repo(FakeStore(mapOf("app_theme" to "NOT_A_REAL_PRESET")))
        assertEquals(AppearanceSettings.DEFAULT_COLOR_PRESET, r.appearance().colorPreset)
    }

    @Test fun blankColorPresetFallsBackToDefault() {
        val r = repo(FakeStore(mapOf("app_theme" to "   ")))
        assertEquals(AppearanceSettings.DEFAULT_COLOR_PRESET, r.appearance().colorPreset)
    }

    @Test fun corruptScrimFallsBackToDefault() {
        for (bad in listOf(2.5f, -0.1f, Float.NaN, "not a number")) {
            val r = repo(FakeStore(mapOf("wallpaper_scrim" to bad)))
            assertEquals(
                "损坏值 $bad 应回退默认",
                AppearanceSettings.DEFAULT_SCRIM,
                r.appearance().scrim,
                0.0001f
            )
        }
    }

    @Test fun integerScrimInRangeIsAccepted() {
        // SharedPreferences 可能存 Int；1 是合法的「完全遮住壁纸」，不是损坏值。
        val r = repo(FakeStore(mapOf("wallpaper_scrim" to 1)))
        assertEquals(1.0f, r.appearance().scrim, 0.0001f)
    }

    @Test fun validScrimIsPreserved() {
        val r = repo(FakeStore(mapOf("wallpaper_scrim" to 0.3f)))
        assertEquals(0.3f, r.appearance().scrim, 0.0001f)
    }

    @Test fun invalidQualityFallsBackToHd() {
        assertEquals("HD", repo(FakeStore(mapOf("selected_quality" to "FHD"))).quality())
        assertEquals("HD", repo(FakeStore(mapOf("selected_quality" to "4K"))).quality())
        assertEquals("HD", repo(FakeStore()).quality())
        assertEquals("SD", repo(FakeStore(mapOf("selected_quality" to "SD"))).quality())
    }

    @Test fun legacySingleSourceDirMigratesToNewKey() {
        val store = FakeStore(mapOf("mod_source_dir_uri" to "content://tree/legacy"))
        val r = repo(store)
        assertEquals(listOf("content://tree/legacy"), r.sourceDirs())
        // 落一次新格式后旧键被清掉，新键生效。
        assertTrue(r.setSourceDirs(listOf("content://tree/legacy")))
        assertNull(store.data["mod_source_dir_uri"])
        assertEquals("content://tree/legacy", store.data["mod_source_dirs"])
    }

    @Test fun newKeyWinsOverLegacyWhenBothPresent() {
        val store = FakeStore(
            mapOf(
                "mod_source_dirs" to "content://tree/new",
                "mod_source_dir_uri" to "content://tree/legacy"
            )
        )
        assertEquals(listOf("content://tree/new"), repo(store).sourceDirs())
    }

    @Test fun writeFailureIsObservable() {
        val store = FakeStore()
        val r = repo(store)
        store.failNextPut = true
        assertFalse("写入失败必须返回 false", r.setBackupOriginals(false))
        // 失败后内存值不应被改（生产里 SharedPreferences commit 失败同样不落盘）。
        assertTrue(r.backupOriginals())
    }

    @Test fun missingKeysDoNotThrowAndKeepDefaults() {
        val r = repo(FakeStore())
        val a = r.appearance()
        assertEquals(ThemeMode.LIGHT, a.themeMode)
        assertEquals(AppearanceSettings.DEFAULT_COLOR_PRESET, a.colorPreset)
        assertFalse(a.dynamicColor)
        assertNull(a.wallpaperUri)
        assertTrue(r.backupOriginals())
        assertFalse(r.useAstc())
        assertTrue(r.sourceDirs().isEmpty())
    }
}
