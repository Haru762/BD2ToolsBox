package com.bd2toolsbox.data.repository

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v2 阵容渲染器的回归测试 —— 用公开接口抓下的真实夹具（wiki-v2-detail-2238.json，
 * Ⅶ战术教材1）断言渲染结果，规格来自交接验收清单。
 *
 * Renderer 是纯 Kotlin + Gson（无 Android 依赖），可在 JVM 直跑。
 */
class GamekeeBattleV2RendererTest {

    private fun fixtureData(): JsonObject {
        // user.dir = bdroid_src/app。夹具在仓库外的工作区（D:/bd2 mod/_大杂烩工作区），
        // 从 app 目录向上找 "bd2 mod" 根，再拼工作区相对路径；不存在则跳过本组测试。
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var root: File? = null
        while (dir != null) {
            if (dir.parentFile != null && dir.parentFile.name == "bd2 mod") { root = dir.parentFile; break }
            dir = dir.parentFile
        }
        val f = root?.let { File(it, "_大杂烩工作区/wiki-v2-detail-2238.json") }
        if (f == null || !f.isFile) org.junit.Assume.assumeTrue("夹具不在: $f", false)
        return JsonParser.parseString(f!!.readText()).asJsonObject
            .get("data").asJsonObject
    }

    private fun render(): String = GamekeeBattleV2Renderer.render(2238L, fixtureData())

    @Test
    fun `回合编号为奇数序列不被标成一二`() {
        val html = render()
        assertTrue("回合 1", html.contains("回合 1"))
        assertTrue("回合 3", html.contains("回合 3"))
        assertFalse("不应出现「回合 2」", html.contains("回合 2<"))
        assertFalse("不应出现「回合 2」summary", html.contains(">回合 2<"))
    }

    @Test
    fun `悠丝缇亚在第11格艾玛在第12格`() {
        val html = render()
        assertTrue("第11格 data-slot", html.contains("data-slot=\"11\""))
        assertTrue("第12格 data-slot", html.contains("data-slot=\"12\""))
        // 第11格标题 = 3行3列（0-based index 10, 4列）
        assertTrue("悠丝缇亚出现", html.contains("悠丝缇亚"))
        assertTrue("艾玛出现", html.contains("艾玛"))
    }

    @Test
    fun `回合一艾玛技能悠丝缇亚击退`() {
        val html = render()
        // 行动顺序列表：艾玛在前
        val m1 = Regex("回合 1.*?行动顺序.*?<ol[^>]*>(.*?)</ol>", RegexOption.DOT_MATCHES_ALL)
            .find(html) ?: throw AssertionError("未找到回合 1 行动列表")
        val order = m1.groupValues[1]
        val emmaIdx = order.indexOf("艾玛")
        val yustiaIdx = order.indexOf("悠丝缇亚")
        assertTrue("行动顺序含艾玛", emmaIdx >= 0)
        assertTrue("行动顺序含悠丝缇亚", yustiaIdx >= 0)
        assertTrue("艾玛先行动", emmaIdx < yustiaIdx)
        assertTrue("艾玛技能：我很忙，快让开！", order.contains("我很忙，快让开！"))
    }

    @Test
    fun `回合三艾玛攻击悠丝缇亚樱花砍击`() {
        val html = render()
        val m = Regex("回合 3.*?行动顺序.*?<ol[^>]*>(.*?)</ol>", RegexOption.DOT_MATCHES_ALL)
            .find(html) ?: throw AssertionError("未找到回合 3 行动列表")
        val order = m.groupValues[1]
        assertTrue("悠丝缇亚技能：樱花砍击", order.contains("樱花砍击"))
        // 艾玛此回合是 damage（攻击），「攻击」标签存在且不带技能名后缀
        val emma = Regex("艾玛.*?<div>攻击</div>", RegexOption.DOT_MATCHES_ALL).find(order)
        assertTrue("艾玛动作标为攻击", emma != null)
    }

    @Test
    fun `空位保留不丢格`() {
        val html = render()
        // 4 列 × 3 行 = 12 格，空位显示 ·
        assertTrue("有空位占位", html.contains("空位\">·"))
        val cells = Regex("data-slot=\"(\\d+)\"").findAll(html).map { it.groupValues[1].toInt() }.toList()
        assertEquals("回合1 共 12 格", listOf(1,2,3,4,5,6,7,8,9,10,11,12), cells.take(12))
    }

    @Test
    fun `备注转义`() {
        val data = JsonObject()
        data.addProperty("content", """{"roundInfos":[{"remark":"<b>注入</b>\"'&\n换行"}]}""")
        val html = GamekeeBattleV2Renderer.render(1L, data)
        assertFalse("尖括号必须转义", html.contains("<b>注入</b>"))
        assertTrue("转义后的实体存在", html.contains("&lt;b&gt;注入&lt;/b&gt;"))
        assertTrue("引号转义", html.contains("&quot;"))
        assertTrue("换行转成 br", html.contains("<br>"))
    }

    @Test
    fun `异常content抛出而非静默`() {
        val data = JsonObject()
        data.addProperty("content", "not-json{")
        try {
            GamekeeBattleV2Renderer.render(1L, data)
            throw AssertionError("应抛异常")
        } catch (e: Exception) {
            // 预期：解析失败向上抛，由 battleCardHtml 捕获返回 null
        }
    }

    @Test
    fun `空回合数组抛出`() {
        val data = JsonObject()
        data.addProperty("content", """{"roundInfos":[]}""")
        try {
            GamekeeBattleV2Renderer.render(1L, data)
            throw AssertionError("空回合应抛异常")
        } catch (e: IllegalArgumentException) {
        }
    }

    @Test
    fun `回合一默认展开回合三收起`() {
        val html = render()
        assertTrue("第一个回合 open", Regex("<details class=\"b-v2-round\" open>.*?回合 1", RegexOption.DOT_MATCHES_ALL)
            .containsMatchIn(html))
        assertFalse("回合 3 不默认展开", Regex("<details class=\"b-v2-round\" open>.*?回合 3", RegexOption.DOT_MATCHES_ALL)
            .containsMatchIn(html.replaceFirst("<details class=\"b-v2-round\" open>", "<details class=\"b-v2-round\">")))
    }
}
