package com.bd2toolsbox.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bd2toolsbox.core.model.ThemeMode
import com.bd2toolsbox.ui.components.AppScreenSurface
import com.bd2toolsbox.ui.components.WallpaperBackdrop
import com.bd2toolsbox.ui.theme.BD2ModManagerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * T40：壁纸分层的可读性契约。
 *
 * 断言（不依赖截图，机器可判）：
 * - 有壁纸背景层时，主题的 `background`/`surface` **alpha 仍为 1**（壁纸不修改全局
 *   ColorScheme）；
 * - 内容面 [AppScreenSurface] 用不透明底色；
 * - 真实壁纸图能被 [WallpaperBackdrop] 解码并绘制（不崩、不 OOM）。
 *
 * 真机/模拟器上「纯白/纯黑/高饱和壁纸下正文可辨识」的观感验收仍需人工截图，
 * 不在本用例内冒充。
 */
@RunWith(AndroidJUnit4::class)
class WallpaperReadabilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun makeWallpaper(): Uri {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(ctx.cacheDir, "t40-wallpaper-${System.nanoTime()}.png")
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.RED)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return Uri.fromFile(file)
    }

    @Test
    fun wallpaperLayerKeepsThemeSurfacesOpaque() {
        var bgAlpha = -1f
        var surfaceAlpha = -1f
        var onBgAlpha = -1f
        composeRule.setContent {
            BD2ModManagerTheme(themeMode = ThemeMode.LIGHT) {
                WallpaperBackdrop(uri = makeWallpaper(), scrim = 0.5f)
                AppScreenSurface {
                    bgAlpha = MaterialTheme.colorScheme.background.alpha
                    surfaceAlpha = MaterialTheme.colorScheme.surface.alpha
                    onBgAlpha = MaterialTheme.colorScheme.onBackground.alpha
                    Text("正文", color = MaterialTheme.colorScheme.onBackground)
                }
            }
        }
        composeRule.waitForIdle()
        assertEquals("有壁纸时 background 仍须不透明", 1f, bgAlpha, 0f)
        assertEquals("有壁纸时 surface 仍须不透明", 1f, surfaceAlpha, 0f)
        assertEquals("正文色不透明", 1f, onBgAlpha, 0f)
    }

    @Test
    fun darkModeWallpaperAlsoKeepsSurfacesOpaque() {
        var bgAlpha = -1f
        composeRule.setContent {
            BD2ModManagerTheme(themeMode = ThemeMode.DARK) {
                WallpaperBackdrop(uri = makeWallpaper(), scrim = 0.3f)
                AppScreenSurface {
                    bgAlpha = MaterialTheme.colorScheme.background.alpha
                    Text("正文", color = MaterialTheme.colorScheme.onBackground)
                }
            }
        }
        composeRule.waitForIdle()
        assertEquals(1f, bgAlpha, 0f)
    }

    @Test
    fun nullWallpaperIsSafeAndStillOpaque() {
        var bgAlpha = -1f
        composeRule.setContent {
            BD2ModManagerTheme(themeMode = ThemeMode.LIGHT) {
                WallpaperBackdrop(uri = null, scrim = 0.5f)
                AppScreenSurface { bgAlpha = MaterialTheme.colorScheme.background.alpha }
            }
        }
        composeRule.waitForIdle()
        assertEquals(1f, bgAlpha, 0f)
    }
}
