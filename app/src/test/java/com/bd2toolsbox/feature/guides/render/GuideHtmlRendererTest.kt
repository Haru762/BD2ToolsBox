package com.bd2toolsbox.feature.guides.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T47：攻略正文主题包装（JVM）。
 *
 * 守的几件事：注入位置正确、幂等、旧缓存（无 head / 无 html 标签）也能套上、
 * 不改正文结构、浅色不加多余覆盖。
 */
class GuideHtmlRendererTest {

    private val sample = """
        ${'$'}RENDERER_STAMP${'$'}CONTENT_STAMP1-->
        <html lang="zh-cn">
        <head>
        <meta charset="utf-8">
        <style>
          body { color:#222; background:transparent; }
        </style>
        </head>
        <body><p class="hl">正文</p></body>
        </html>
    """.trimIndent()

    private fun countOccurrences(s: String, needle: String): Int {
        var i = 0
        var n = 0
        while (true) {
            val at = s.indexOf(needle, i)
            if (at < 0) return n
            n++
            i = at + needle.length
        }
    }

    @Test
    fun darkAddsOverrideStyleInsideHead() {
        val out = GuideHtmlRenderer.render(sample, dark = true)
        assertTrue(out.contains("id=\"${GuideHtmlRenderer.STYLE_ID}\""))
        assertTrue("覆盖必须在 </head> 之前", out.indexOf("<style id=") < out.indexOf("</head>"))
        assertTrue("深色必须覆盖正文颜色", out.contains("color:#E6E1E5 !important"))
        assertTrue("深色必须声明 color-scheme", out.contains("color-scheme: dark"))
    }

    @Test
    fun lightOnlyDeclaresColorSchemeAndKeepsOriginalBody() {
        val out = GuideHtmlRenderer.render(sample, dark = false)
        assertTrue(out.contains("color-scheme: light"))
        assertFalse("浅色不该覆盖正文颜色", out.contains("!important"))
        assertTrue("原正文样式必须保留", out.contains("body { color:#222; background:transparent; }"))
    }

    @Test
    fun renderIsIdempotent() {
        val once = GuideHtmlRenderer.render(sample, dark = true)
        val twice = GuideHtmlRenderer.render(once, dark = true)
        assertEquals(1, countOccurrences(twice, "id=\"${GuideHtmlRenderer.STYLE_ID}\""))
        // 再切回浅色也只留一份
        val back = GuideHtmlRenderer.render(twice, dark = false)
        assertEquals(1, countOccurrences(back, "id=\"${GuideHtmlRenderer.STYLE_ID}\""))
        assertFalse(back.contains("!important"))
    }

    @Test
    fun oldCachedHtmlWithoutHeadStillGetsThemed() {
        val noHead = "<html><body><p>旧缓存正文</p></body></html>"
        val out = GuideHtmlRenderer.render(noHead, dark = true)
        assertTrue(out.contains("id=\"${GuideHtmlRenderer.STYLE_ID}\""))
        assertTrue(out.contains("旧缓存正文"))
    }

    @Test
    fun fragmentWithoutHtmlTagStillGetsThemed() {
        val fragment = "<p>只有一段正文</p>"
        val out = GuideHtmlRenderer.render(fragment, dark = true)
        assertTrue(out.startsWith("<style id="))
        assertTrue(out.contains("<p>只有一段正文</p>"))
    }

    @Test
    fun contentStructureIsNotRewritten() {
        val out = GuideHtmlRenderer.render(sample, dark = true)
        assertTrue("正文元素必须原样保留", out.contains("<body><p class=\"hl\">正文</p></body>"))
        assertTrue("原 style 块必须保留", out.contains("body { color:#222; background:transparent; }"))
        assertTrue("渲染戳必须保留", out.contains("\$CONTENT_STAMP1-->"))
    }

    @Test
    fun darkCoversTheComponentsThatWouldOtherwiseStayLight() {
        val out = GuideHtmlRenderer.render(sample, dark = true)
        listOf(
            ".mention", ".array-card", ".battle", ".tabs .tl", ".tabs .tp",
            ".gktable td", "a {", "hr {", "td, th", ".fold", ".hl"
        ).forEach { selector ->
            assertTrue("深色覆盖缺少 $selector", out.contains(selector))
        }
    }
}
