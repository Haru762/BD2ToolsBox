package com.bd2toolsbox.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * 全局主题。
 *
 * @param dynamicColor          跟随壁纸取色（Material You），Android 12+ 有效，
 *                              优先于 [theme]（系统给的整套色没法叠预设）。
 * @param theme                 手选配色预设，见 [AppTheme]。
 * @param transparentBackground 用自定义壁纸时置 true：把 background/surface 抽掉
 *                              不透明度让壁纸透出来，状态栏随之透明。
 *
 * 始终浅色：界面大量状态靠颜色区分（生效中/需重新应用/被其他工具改过），
 * 一套配色调准了更可靠，所以不做深浅切换。
 */
@Composable
fun BD2ModManagerTheme(
    dynamicColor: Boolean = false,
    theme: AppTheme = AppTheme.PURPLE,
    transparentBackground: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val base = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicLightColorScheme(context)
    } else {
        colorSchemeFor(theme)
    }

    // 壁纸模式只透明这两个面色 —— surfaceVariant（头像位/徽章底）和各种
    // container 必须保持不透明，否则文字直接压在壁纸上没法读
    val colorScheme = if (transparentBackground) {
        base.copy(background = Color.Transparent, surface = Color.Transparent)
    } else {
        base
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = true
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
