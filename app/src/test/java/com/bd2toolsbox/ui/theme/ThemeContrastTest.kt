package com.bd2toolsbox.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T58：**配色预设的 WCAG 对比度核对**（纯 JVM，不需要设备）。
 *
 * ## 为什么单独一个测试类
 *
 * `ThemeContractTest` 里已经有一个 `luminance()`，但它是**朴素亮度** ——
 * 直接在 sRGB 的 gamma 编码分量上做加权和：
 *
 * ```
 * 0.2126f * red + 0.7152f * green + 0.0722f * blue
 * ```
 *
 * WCAG 2.1 的相对亮度要求**先把每个通道线性化**再加权：
 *
 * ```
 * c' = c/12.92                        (c <= 0.03928)
 * c' = ((c + 0.055)/1.055)^2.4        (否则)
 * L  = 0.2126 R' + 0.7152 G' + 0.0722 B'
 * ```
 *
 * 两者在中间调上能差出 20% 以上，用朴素亮度判「≥4.5:1」会给出**错误的结论**。
 * 所以这里实现规范公式，而不是复用那个近似。
 *
 * ## 判据
 *
 * - **正文**（`onBackground`/`background`、`onSurface`/`surface`、
 *   `onSurfaceVariant`/`surface`）：≥ **4.5:1**（WCAG AA 正文）；
 * - **大字与图标**（容器上的 `on*` 对、`primary` 上的 `onPrimary`）：≥ **3:1**；
 * - **控件边界**（`outline`/`surface`）：≥ **3:1**（WCAG 1.4.11 非文本对比度）；
 * - `outlineVariant` 只做分隔线/装饰，**不设门禁**，但要求它比 `surface` 更明显。
 *
 * 全部 7 个预设 × 明/暗两套都要过 —— 加预设时不需要改这个文件。
 */
class ThemeContrastTest {

    /** WCAG 2.1 相对亮度。**必须先线性化**，不能直接在 gamma 编码值上加权。 */
    private fun relativeLuminance(color: Color): Double {
        fun linearize(channel: Float): Double {
            val c = channel.toDouble()
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * linearize(color.red) +
            0.7152 * linearize(color.green) +
            0.0722 * linearize(color.blue)
    }

    /** WCAG 对比度：(L亮 + 0.05) / (L暗 + 0.05)，恒 ≥ 1。 */
    private fun contrastRatio(a: Color, b: Color): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /**
     * 透明度会改变实际合成结果，这里按「压在背景色上」合成后再算。
     * 本项目要求全局 ColorScheme 恒不透明（T40），所以这个函数在合规输入上
     * 应当等于原色 —— 断言里顺带验证这一点。
     */
    private fun over(foreground: Color, background: Color): Color = Color(
        red = foreground.red * foreground.alpha + background.red * (1 - foreground.alpha),
        green = foreground.green * foreground.alpha + background.green * (1 - foreground.alpha),
        blue = foreground.blue * foreground.alpha + background.blue * (1 - foreground.alpha),
        alpha = 1f
    )

    private data class Pair(val name: String, val fg: Color, val bg: Color, val min: Double)

    private fun pairsFor(scheme: ColorScheme): List<Pair> = listOf(
        // 正文：AA 要求 4.5:1
        Pair("onBackground/background", scheme.onBackground, scheme.background, 4.5),
        Pair("onSurface/surface", scheme.onSurface, scheme.surface, 4.5),
        Pair("onSurfaceVariant/surface", scheme.onSurfaceVariant, scheme.surface, 4.5),
        // 大字与图标：3:1
        Pair("onPrimary/primary", scheme.onPrimary, scheme.primary, 3.0),
        Pair("onSecondary/secondary", scheme.onSecondary, scheme.secondary, 3.0),
        Pair("onPrimaryContainer/primaryContainer", scheme.onPrimaryContainer, scheme.primaryContainer, 4.5),
        Pair("onSecondaryContainer/secondaryContainer", scheme.onSecondaryContainer, scheme.secondaryContainer, 4.5),
        Pair("onTertiaryContainer/tertiaryContainer", scheme.onTertiaryContainer, scheme.tertiaryContainer, 4.5),
        Pair("onErrorContainer/errorContainer", scheme.onErrorContainer, scheme.errorContainer, 4.5),
        Pair("onError/error", scheme.onError, scheme.error, 3.0),
        // 控件边界：WCAG 1.4.11 非文本对比度 3:1
        Pair("outline/surface", scheme.outline, scheme.surface, 3.0)
    )

    private fun assertScheme(label: String, scheme: ColorScheme, problems: MutableList<String>) {
        for (pair in pairsFor(scheme)) {
            val fg = over(pair.fg, pair.bg)
            val ratio = contrastRatio(fg, pair.bg)
            if (ratio < pair.min) {
                problems += ("%s 的 %s 只有 %.2f:1（要求 ≥ %.1f:1）"
                    .format(label, pair.name, ratio, pair.min))
            }
        }
    }

    @Test
    fun everyPresetMeetsWcagContrastInBothModes() {
        val problems = mutableListOf<String>()
        // 把实测比值打进测试报告（JUnit XML 的 system-out）—— 证据里要的是数字，
        // 不是「测试过了」。最紧的那一对也要看得见。
        val table = mutableListOf<String>()
        var tightest = Double.MAX_VALUE
        var tightestLabel = ""
        for (theme in AppTheme.entries) {
            for ((mode, scheme) in listOf("浅色" to colorSchemeFor(theme), "深色" to darkColorSchemeFor(theme))) {
                val label = "${theme.label}$mode"
                assertScheme(label, scheme, problems)
                for (pair in pairsFor(scheme)) {
                    val ratio = contrastRatio(over(pair.fg, pair.bg), pair.bg)
                    val margin = ratio / pair.min
                    if (margin < tightest) {
                        tightest = margin
                        tightestLabel = "$label ${pair.name} = %.2f:1 (要求 %.1f)".format(ratio, pair.min)
                    }
                    table += ("%s %-40s %6.2f:1  min %.1f  %s"
                        .format(label, pair.name, ratio, pair.min, if (ratio >= pair.min) "OK" else "INSUFFICIENT"))
                }
            }
        }
        println("WCAG_CONTRAST_TABLE_BEGIN")
        table.forEach { println(it) }
        println("WCAG_CONTRAST_TIGHTEST $tightestLabel")
        println("WCAG_CONTRAST_TABLE_END")
        assertTrue(
            "对比度不达标：\n  " + problems.joinToString("\n  "),
            problems.isEmpty()
        )
    }

    /** 全局 ColorScheme 必须不透明（T40）—— 否则上面的合成假设就不成立。 */
    @Test
    fun schemesAreOpaqueSoCompositingIsPredictable() {
        for (theme in AppTheme.entries) {
            for ((mode, scheme) in listOf("浅色" to colorSchemeFor(theme), "深色" to darkColorSchemeFor(theme))) {
                val opaque = listOf(
                    "background" to scheme.background, "surface" to scheme.surface,
                    "onBackground" to scheme.onBackground, "onSurface" to scheme.onSurface,
                    "onSurfaceVariant" to scheme.onSurfaceVariant,
                    "primary" to scheme.primary, "onPrimary" to scheme.onPrimary,
                    "outline" to scheme.outline, "error" to scheme.error
                )
                for ((name, color) in opaque) {
                    assertTrue(
                        "${theme.label}$mode 的 $name 必须不透明（alpha=${color.alpha}）",
                        color.alpha == 1f
                    )
                }
            }
        }
    }

    /**
     * `appColorScheme` 必须把动态配色里可能带进来的透明 surface/background 强制回不透明。
     * 这条是 T40「壁纸只在独立背景层可见」的机器守卫。
     */
    @Test
    fun appColorSchemeForcesOpaqueSurfacesEvenWithDynamicScheme() {
        val translucent = colorSchemeFor(AppTheme.PURPLE).copy(
            surface = Color(0x80FFFFFF),
            background = Color(0x80FFFFFF)
        )
        for (dark in listOf(false, true)) {
            val scheme = appColorScheme(AppTheme.PURPLE, dark, translucent)
            assertTrue("动态配色的 surface 必须被强制不透明", scheme.surface.alpha == 1f)
            assertTrue("动态配色的 background 必须被强制不透明", scheme.background.alpha == 1f)
        }
    }

    /**
     * 分隔线（`outlineVariant`）是装饰，不设 3:1 门禁 —— 但也不能等于 surface，
     * 否则列表分隔与输入框边界会「看不见」。
     */
    @Test
    fun outlineVariantIsVisiblyDifferentFromSurface() {
        for (theme in AppTheme.entries) {
            for ((mode, scheme) in listOf("浅色" to colorSchemeFor(theme), "深色" to darkColorSchemeFor(theme))) {
                val ratio = contrastRatio(scheme.outlineVariant, scheme.surface)
                assertTrue(
                    "${theme.label}$mode 的 outlineVariant 与 surface 几乎同色（%.2f:1）—— 分隔线会看不见".format(ratio),
                    ratio >= 1.05
                )
            }
        }
    }

    /**
     * 反向守卫：规范公式与朴素亮度**必须给出不同结果**。
     *
     * 如果两者恰好一致，说明这个文件被改回了朴素实现 —— 那样「≥4.5:1」就是假的。
     * 取中灰（0.5）对白：朴素算 0.5 对 1.0，规范算 0.2140 对 1.0。
     */
    @Test
    fun wcagLuminanceIsNotTheNaiveGammaEncodedApproximation() {
        val mid = Color(0.5f, 0.5f, 0.5f)
        val white = Color.White
        val wcag = contrastRatio(mid, white)

        fun naive(c: Color) = 0.2126 * c.red + 0.7152 * c.green + 0.0722 * c.blue
        val naiveRatio = (1.0 + 0.05) / (naive(mid) + 0.05)

        assertTrue("规范比值 %.4f 应在 3.9..4.1 之间".format(wcag), wcag in 3.9..4.1)
        assertTrue("朴素比值 %.4f 应在 1.8..2.0 之间".format(naiveRatio), naiveRatio in 1.8..2.0)
        assertTrue(
            "规范与朴素必须明显不同（%.3f vs %.3f）—— 相同就说明实现被换回朴素亮度了".format(wcag, naiveRatio),
            Math.abs(wcag - naiveRatio) > 1.0
        )
    }
}
