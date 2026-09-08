package com.bd2toolsbox.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * @param dynamicColor 跟随壁纸取色（Material You）。仅 Android 12+ 有效，低版本自动忽略。
 *                     开启时**优先于** [theme]（系统给的整套色，没法再往上叠预设）。
 * @param theme        手选的配色预设，见 [AppTheme]。
 * @param transparentBackground 用了自定义壁纸时置 true —— 把 background/surface 抽掉
 *                     不透明度，壁纸才透得出来。状态栏也随之透明。
 *
 * 始终使用**浅色**方案：动态取色也取 light 变体，因此状态栏图标固定为深色。
 * 之所以不做深浅切换，是因为界面里大量状态靠颜色区分（生效中/需重新应用/被其他工具改过），
 * 一套配色调准了更可靠；有需要再单独做一轮。
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

    // 壁纸模式下让底层两个面色透明。只改这两个 —— surfaceVariant（头像位、
    // 徽章底）、各种 container 都得留着不透明，否则文字直接压在壁纸上没法读。
    val colorScheme = if (transparentBackground) {
        base.copy(
            background = androidx.compose.ui.graphics.Color.Transparent,
            surface = androidx.compose.ui.graphics.Color.Transparent
        )
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
