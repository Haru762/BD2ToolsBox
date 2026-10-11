package com.bd2toolsbox.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.material3.MaterialTheme
import com.bd2toolsbox.core.model.ThemeMode
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * 全局主题。
 *
 * @param themeMode             明/暗/跟随系统（T39）；旧配置缺该键时默认 LIGHT。
 * @param dynamicColor          跟随壁纸取色（Material You），Android 12+ 有效，
 *                              优先于 [theme]（系统给的整套色没法叠预设）。
 * @param theme                 手选配色预设，见 [AppTheme]。
 *
 * T40：**不再接收壁纸透明度**。全局 ColorScheme 始终不透明；壁纸由独立的背景层
 * （[com.bd2toolsbox.ui.components.WallpaperBackdrop]）展示，文字密集区域用
 * [com.bd2toolsbox.ui.components.AppScreenSurface] 的不透明底色。
 */
@Composable
fun BD2ModManagerTheme(
    themeMode: ThemeMode = ThemeMode.LIGHT,
    dynamicColor: Boolean = false,
    theme: AppTheme = AppTheme.PURPLE,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val dark = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val dynamicScheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        null
    }
    // 语义面色恒不透明（appColorScheme 保证 alpha=1）；壁纸不修改全局 ColorScheme。
    val colorScheme = appColorScheme(theme, dark, dynamicScheme)

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // 系统栏已由 enableEdgeToEdge 置透明、内容画到其后；这里只管图标深浅。
            // 深色主题用浅色图标，浅色主题用深色图标。
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
