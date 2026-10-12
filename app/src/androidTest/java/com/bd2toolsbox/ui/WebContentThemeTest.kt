package com.bd2toolsbox.ui

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bd2toolsbox.feature.guides.render.GuideHtmlRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * T47：WebView 正文的主题可读性（设备）。
 *
 * 用真实 WebView 加载渲染后的 HTML，读**计算样式**（`getComputedStyle`），
 * 而不是只检查字符串里有没有某段 CSS —— 能真正证明深色下正文不是深色字压深色底。
 *
 * 同时验证：缓存里那份「写死浅色」的旧 HTML 不删也能在深色下正确显示。
 */
@RunWith(AndroidJUnit4::class)
class WebContentThemeTest {

    /** 与 [com.bd2toolsbox.data.repository.GamekeeRepository] 生成的缓存 HTML 同形。 */
    private val cachedGuideHtml = """
        <!doctype html>
        <html lang="zh-cn">
        <head>
        <meta charset="utf-8">
        <style>
          body { font-family:sans-serif; font-size:16px; color:#222; background:transparent; }
          a { color:#1b6bb5; }
          .mention { background:#eef3fb; }
          .array-card { border:1px dashed #c9a15a; background:#fdf6e9; color:#7a5c1e; }
        </style>
        </head>
        <body><p class="hl">正文</p><a id="lnk" href="#">链接</a>
        <span id="men" class="mention">引用</span></body>
        </html>
    """.trimIndent()

    private fun loadAndQuery(html: String, js: String): String? {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val latch = CountDownLatch(1)
        var value: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val web = WebView(ctx)
            web.settings.javaScriptEnabled = true
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    view.evaluateJavascript(js) { result ->
                        value = result
                        latch.countDown()
                    }
                }
            }
            web.loadDataWithBaseURL(
                "https://www.gamekee.com/", html, "text/html", "utf-8", null
            )
        }
        val ok = latch.await(20, TimeUnit.SECONDS)
        assertTrue("WebView 未在超时内完成加载", ok)
        return value
    }

    private fun rgb(hex: String): String {
        var v = hex.removePrefix("#")
        // 支持 #abc 简写
        if (v.length == 3) v = v.map { "$it$it" }.joinToString("")
        val r = v.substring(0, 2).toInt(16)
        val g = v.substring(2, 4).toInt(16)
        val b = v.substring(4, 6).toInt(16)
        return "rgb($r, $g, $b)"
    }

    @Test
    fun darkThemeOverridesHardcodedLightBodyColour() {
        val html = GuideHtmlRenderer.render(cachedGuideHtml, dark = true)
        val colour = loadAndQuery(html, "getComputedStyle(document.body).color")
        assertNotNull(colour)
        assertEquals(
            "深色下正文必须是浅色字（缓存里写死的 #222 被覆盖）",
            rgb("#E6E1E5"),
            colour!!.trim('"')
        )
    }

    @Test
    fun lightThemeKeepsOriginalBodyColour() {
        val html = GuideHtmlRenderer.render(cachedGuideHtml, dark = false)
        val colour = loadAndQuery(html, "getComputedStyle(document.body).color")
        assertEquals(
            "浅色下不应改动原文颜色",
            rgb("#222"),
            colour!!.trim('"')
        )
    }

    @Test
    fun darkThemeAlsoFixesLinkAndComponentColours() {
        val html = GuideHtmlRenderer.render(cachedGuideHtml, dark = true)
        val link = loadAndQuery(html, "getComputedStyle(document.getElementById('lnk')).color")
        assertEquals(rgb("#A8C7FA"), link!!.trim('"'))
        val mention = loadAndQuery(
            html, "getComputedStyle(document.getElementById('men')).backgroundColor"
        )
        assertEquals(rgb("#2B2930"), mention!!.trim('"'))
    }

    @Test
    fun colourSchemeIsDeclaredSoFormControlsMatch() {
        // `color-scheme` 是**渐进增强**：它让表单控件/滚动条跟着页面走。旧 WebView
        // （实测 API 29 的 emulator 镜像）不支持这个 CSS 属性，`getComputedStyle().colorScheme`
        // 返回 null —— 那不代表页面不可读，因为正文/链接/组件底色都是显式设的
        // （同一类的其它 5 条断言在这些设备上照样通过）。
        //
        // 所以这里分两层断言：
        //   1. 我们注入的 CSS 里**确实有** color-scheme 声明（与 WebView 版本无关，硬断言）；
        //   2. 支持该属性的 WebView 上，计算值必须是 dark/light（软断言）。
        val darkHtml = GuideHtmlRenderer.render(cachedGuideHtml, dark = true)
        assertTrue("注入的 CSS 必须声明 color-scheme: dark", darkHtml.contains("color-scheme: dark"))
        val lightHtml = GuideHtmlRenderer.render(cachedGuideHtml, dark = false)
        assertTrue("注入的 CSS 必须声明 color-scheme: light", lightHtml.contains("color-scheme: light"))

        val darkComputed = loadAndQuery(
            darkHtml, "getComputedStyle(document.documentElement).colorScheme"
        )!!.trim()
        val lightComputed = loadAndQuery(
            lightHtml, "getComputedStyle(document.documentElement).colorScheme"
        )!!.trim()
        val unsupported = darkComputed == "null" || darkComputed == "\"\""
        if (unsupported) {
            // 如实记录而不是静默跳过：这条设备上的 WebView 没有这个属性
            android.util.Log.i(
                "WebContentThemeTest",
                "此 WebView 不支持 color-scheme（computed=$darkComputed）；" +
                    "只断言注入的 CSS 声明，正文配色由同类其它断言覆盖"
            )
        } else {
            assertEquals("\"dark\"", darkComputed)
            assertEquals("\"light\"", lightComputed)
        }
    }

    @Test
    fun contentIsStillReadableAfterTheming() {
        val html = GuideHtmlRenderer.render(cachedGuideHtml, dark = true)
        val text = loadAndQuery(html, "document.body.innerText")
        assertTrue(text!!.contains("正文"))
        assertTrue(text.contains("链接"))
    }

    @Test
    fun themingDoesNotBreakTheDocumentStructure() {
        val html = GuideHtmlRenderer.render(cachedGuideHtml, dark = true)
        val count = loadAndQuery(html, "document.querySelectorAll('p.hl').length")
        assertEquals("1", count!!.trim())
    }
}
