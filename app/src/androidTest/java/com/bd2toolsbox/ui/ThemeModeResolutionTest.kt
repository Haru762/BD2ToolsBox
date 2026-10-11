package com.bd2toolsbox.ui

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bd2toolsbox.core.model.ThemeMode
import com.bd2toolsbox.ui.theme.BD2ModManagerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T39：设置页选择的明暗模式**真的**决定主题（设备）。
 *
 * 这是「主题模式选择器」接线的直接判据 —— 只看 `ThemeMode` 被存下来是不够的，
 * 要证明它驱动了实际配色。SYSTEM 一项按当前系统 `uiMode` 分别断言（不假定跑在哪种模式）。
 */
@RunWith(AndroidJUnit4::class)
class ThemeModeResolutionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun backgroundLuminance(mode: ThemeMode): Float {
        var luminance = -1f
        composeRule.setContent {
            BD2ModManagerTheme(themeMode = mode) {
                luminance = MaterialTheme.colorScheme.background.luminance()
                Text("x")
            }
        }
        composeRule.waitForIdle()
        assertTrue("未渲染出背景色", luminance >= 0f)
        return luminance
    }

    private fun systemIsDark(): Boolean {
        val uiMode = InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.configuration.uiMode
        return (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    @Test
    fun lightModeIsAlwaysLight() {
        assertTrue("LIGHT 必须是浅色底", backgroundLuminance(ThemeMode.LIGHT) > 0.5f)
    }

    @Test
    fun darkModeIsAlwaysDark() {
        assertTrue("DARK 必须是深色底", backgroundLuminance(ThemeMode.DARK) < 0.5f)
    }

    @Test
    fun systemModeFollowsTheSystemUiMode() {
        val luminance = backgroundLuminance(ThemeMode.SYSTEM)
        if (systemIsDark()) {
            assertTrue("系统深色时 SYSTEM 必须是深色底（实际亮度 $luminance）", luminance < 0.5f)
        } else {
            assertTrue("系统浅色时 SYSTEM 必须是浅色底（实际亮度 $luminance）", luminance > 0.5f)
        }
    }

    @Test
    fun darkAndLightDifferClearly() {
        // setContent 每个用例只能调一次，所以两种模式在**同一次**组合里嵌套渲染。
        var light = -1f
        var dark = -1f
        composeRule.setContent {
            BD2ModManagerTheme(themeMode = ThemeMode.LIGHT) {
                light = MaterialTheme.colorScheme.background.luminance()
                BD2ModManagerTheme(themeMode = ThemeMode.DARK) {
                    dark = MaterialTheme.colorScheme.background.luminance()
                    Text("x")
                }
            }
        }
        composeRule.waitForIdle()
        assertTrue("浅色底($light) 必须比深色底($dark) 亮得多", light > dark + 0.3f)
    }

    @Test
    fun themeModeEnumRoundTripsThroughNames() {
        // 设置页写入的名字必须能被读回（未知/大小写不符回退 LIGHT）
        ThemeMode.entries.forEach { mode ->
            assertEquals(mode, ThemeMode.fromName(mode.name))
            assertEquals(mode, ThemeMode.fromName(mode.name.lowercase()))
        }
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromName(null))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromName("NO_SUCH_MODE"))
    }
}
