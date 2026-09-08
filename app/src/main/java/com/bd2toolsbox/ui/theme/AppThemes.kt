package com.bd2toolsbox.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 可选的界面配色。
 *
 * 为什么是手写的几套、而不是「给个取色盘、从任意 seed 算出整套配色」：
 * 从 seed 生成 Material3 全套色需要 material-color-utilities（HCT 色彩空间那一套），
 * 而本项目没有这个依赖，本地 Gradle 缓存里也没有，离线构建拉不到。
 * 几套调好的预设覆盖了实际需求，还省掉一整个依赖 —— 也不会出现「用户选了个亮黄色
 * 结果界面上的状态色全都看不清」那种事。
 *
 * 每套只覆盖真正影响观感的那几个色，其余沿用 [lightColorScheme] 的默认值：
 *   primary / onPrimary / primaryContainer / onPrimaryContainer  —— 按钮、选中态、徽章
 *   secondary / secondaryContainer                              —— 类别徽章、次要 chip
 *   tertiary                                                    —— 「仅产物」这类第三态 tag
 *
 * 刻意**不**动 error 系（卸载、失败、警告全靠它）和 background/surface
 * ——那两者一变，壁纸遮罩和列表分隔的对比度就得重新调一遍。
 */
enum class AppTheme(
    val label: String,
    /** 设置里那个小圆点用的颜色，取 primary。 */
    val swatch: Color
) {
    PURPLE("紫", Color(0xFF6650A4)),
    BLUE("蓝", Color(0xFF1B6BB5)),
    TEAL("青", Color(0xFF00696E)),
    GREEN("绿", Color(0xFF2E6B33)),
    ORANGE("橙", Color(0xFF9A4A0A)),
    ROSE("粉", Color(0xFFA23A62)),
    GRAPHITE("石墨", Color(0xFF4A4A55));

    companion object {
        fun fromName(name: String?): AppTheme =
            entries.firstOrNull { it.name == name } ?: PURPLE
    }
}

/** 取某套预设的浅色方案。 */
fun colorSchemeFor(theme: AppTheme): ColorScheme = when (theme) {
    AppTheme.PURPLE -> lightColorScheme(
        primary = Color(0xFF6650A4),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFE9DDFF),
        onPrimaryContainer = Color(0xFF22005D),
        secondary = Color(0xFF625B71),
        secondaryContainer = Color(0xFFE8DEF8),
        onSecondaryContainer = Color(0xFF1E192B),
        tertiary = Color(0xFF7D5260),
        background = Color.White,
        surface = Color(0xFFFDFDFD)
    )

    AppTheme.BLUE -> lightColorScheme(
        primary = Color(0xFF1B6BB5),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFD3E4FF),
        onPrimaryContainer = Color(0xFF001C39),
        secondary = Color(0xFF54606F),
        secondaryContainer = Color(0xFFD8E4F6),
        onSecondaryContainer = Color(0xFF111C2B),
        tertiary = Color(0xFF6B5778),
        background = Color.White,
        surface = Color(0xFFFCFDFF)
    )

    AppTheme.TEAL -> lightColorScheme(
        primary = Color(0xFF00696E),
        onPrimary = Color.White,
        primaryContainer = Color(0xFF9EF0F6),
        onPrimaryContainer = Color(0xFF002022),
        secondary = Color(0xFF4A6365),
        secondaryContainer = Color(0xFFCCE8E9),
        onSecondaryContainer = Color(0xFF051F21),
        tertiary = Color(0xFF4C5F7D),
        background = Color.White,
        surface = Color(0xFFFBFDFD)
    )

    AppTheme.GREEN -> lightColorScheme(
        primary = Color(0xFF2E6B33),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFB1F2AC),
        onPrimaryContainer = Color(0xFF002104),
        secondary = Color(0xFF52634F),
        secondaryContainer = Color(0xFFD5E8CF),
        onSecondaryContainer = Color(0xFF101F10),
        tertiary = Color(0xFF39656B),
        background = Color.White,
        surface = Color(0xFFFCFDF7)
    )

    AppTheme.ORANGE -> lightColorScheme(
        primary = Color(0xFF9A4A0A),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFFFDBC8),
        onPrimaryContainer = Color(0xFF341100),
        secondary = Color(0xFF755846),
        secondaryContainer = Color(0xFFFFDCC5),
        onSecondaryContainer = Color(0xFF2B1708),
        tertiary = Color(0xFF5F6236),
        background = Color.White,
        surface = Color(0xFFFFFBFF)
    )

    AppTheme.ROSE -> lightColorScheme(
        primary = Color(0xFFA23A62),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFFFD9E2),
        onPrimaryContainer = Color(0xFF3E001D),
        secondary = Color(0xFF74565F),
        secondaryContainer = Color(0xFFFFD9E2),
        onSecondaryContainer = Color(0xFF2B151C),
        tertiary = Color(0xFF7C5635),
        background = Color.White,
        surface = Color(0xFFFFFBFF)
    )

    AppTheme.GRAPHITE -> lightColorScheme(
        primary = Color(0xFF4A4A55),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFDDE1EC),
        onPrimaryContainer = Color(0xFF171A22),
        secondary = Color(0xFF5A5C63),
        secondaryContainer = Color(0xFFE0E1E9),
        onSecondaryContainer = Color(0xFF171A22),
        tertiary = Color(0xFF6B5A6E),
        background = Color.White,
        surface = Color(0xFFFDFDFD)
    )
}
