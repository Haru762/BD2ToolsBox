package com.bd2toolsbox.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import com.bd2toolsbox.core.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T39：主题契约。`appColorScheme` 是纯函数，JVM 直测。
 *
 * 守的几件事：
 * - 七套预设 × LIGHT/DARK 都能取到方案，且 **surface/background 恒不透明**；
 * - 明暗两套确实不同（不是把浅色反转/降 alpha 糊过去）；
 * - 动态取色传入时优先，且同样不透明；
 * - 七套预设两两可区分（不是同一套色改名）。
 */
class ThemeContractTest {

    private val presets = AppTheme.entries.toList()

    @Test fun everyPresetHasBothLightAndDarkSchemes() {
        for (theme in presets) {
            val light = appColorScheme(theme, dark = false)
            val dark = appColorScheme(theme, dark = true)
            assertNotEquals("$theme 的明暗方案不能相同", light.primary, dark.primary)
        }
    }

    @Test fun surfaceAndBackgroundAreAlwaysOpaque() {
        for (theme in presets) {
            for (dark in listOf(false, true)) {
                val scheme = appColorScheme(theme, dark)
                assertEquals("$theme dark=$dark surface 必须不透明", 1f, scheme.surface.alpha, 0f)
                assertEquals("$theme dark=$dark background 必须不透明", 1f, scheme.background.alpha, 0f)
                assertEquals("$theme dark=$dark surfaceVariant 必须不透明", 1f, scheme.surfaceVariant.alpha, 0f)
                assertEquals("$theme dark=$dark primaryContainer 必须不透明", 1f, scheme.primaryContainer.alpha, 0f)
            }
        }
    }

    @Test fun darkSchemesAreActuallyDark() {
        for (theme in presets) {
            val dark = appColorScheme(theme, dark = true)
            // 深色方案的背景亮度必须显著低于浅色方案。
            assertTrue("$theme 深色背景应偏暗", dark.background.luminance() < 0.3f)
            assertTrue("$theme 深色 surface 应偏暗", dark.surface.luminance() < 0.3f)
            val light = appColorScheme(theme, dark = false)
            assertTrue("$theme 浅色背景应偏亮", light.background.luminance() > 0.7f)
        }
    }

    @Test fun presetsAreDistinguishableInBothModes() {
        for (dark in listOf(false, true)) {
            val primaries = presets.map { appColorScheme(it, dark).primary }
            assertEquals("$dark 模式下七套预设的 primary 必须两两不同", presets.size, primaries.toSet().size)
        }
    }

    @Test fun dynamicSchemeTakesPrecedenceWhenProvided() {
        val dynamic = darkColorScheme(
            primary = Color(0xFF123456),
            background = Color(0xFF000000),
            surface = Color(0xFF010101)
        )
        val scheme = appColorScheme(AppTheme.PURPLE, dark = false, dynamicScheme = dynamic)
        assertEquals("动态取色应优先于预设", Color(0xFF123456), scheme.primary)
        assertEquals(1f, scheme.background.alpha, 0f)
        assertEquals(1f, scheme.surface.alpha, 0f)
    }

    @Test fun dynamicSchemeWithTransparentSurfacesIsForcedOpaque() {
        val dynamic = darkColorScheme(
            primary = Color(0xFF123456),
            background = Color.Transparent,
            surface = Color.Transparent
        )
        val scheme = appColorScheme(AppTheme.PURPLE, dark = true, dynamicScheme = dynamic)
        assertEquals("动态方案也不能带透明语义面色", 1f, scheme.background.alpha, 0f)
        assertEquals(1f, scheme.surface.alpha, 0f)
    }

    @Test fun errorAndOnColorsAreOpaque() {
        for (theme in presets) {
            for (dark in listOf(false, true)) {
                val scheme = appColorScheme(theme, dark)
                assertEquals(1f, scheme.error.alpha, 0f)
                assertEquals(1f, scheme.onError.alpha, 0f)
                assertEquals(1f, scheme.onPrimary.alpha, 0f)
                assertEquals(1f, scheme.onSurface.alpha, 0f)
            }
        }
    }

    @Test fun themeModeDefaultsAreExplicit() {
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromName(null))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromName(""))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromName("not-a-mode"))
        assertEquals(ThemeMode.DARK, ThemeMode.fromName("DARK"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromName("system"))
    }

    /** 相对亮度（sRGB 线性化简化版，够用来区分「深」与「浅」）。 */
    private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue
}
