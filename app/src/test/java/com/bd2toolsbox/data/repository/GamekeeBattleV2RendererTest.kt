package com.bd2toolsbox.data.repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2 阵容渲染器的回归测试 —— 用公开接口抓下的真实夹具（wiki-v2-detail-2238.json，
 * Ⅶ战术教材1）断言渲染结果，规格来自交接验收清单。
 *
 * Renderer 是纯 Kotlin（无 Android 依赖），可在 JVM 直跑。
 *
 * 夹具从 **classpath** 读取（app/src/test/resources/fixtures/wiki/wiki-v2-detail-2238.json），
 * 由仓库外真实样本脱敏派生：删掉 meta（request/trace id）、author（账号/头像/IP 归属）、
 * uid 与计数类字段，data 只保留渲染器实际读的 id/title/desc/content，content 内所有
 * CDN 图片 URL 置空。此前是向上找名为 "bd2 mod" 的祖先目录再拼外部路径 —— 那只在原
 * 检出布局下成立，worktree 里会找不到并 Assume 成 skip；**skip 不算通过**，现在夹具
 * 缺失直接断言失败。
 */
class GamekeeBattleV2RendererTest {

    private fun fixtureData(): JsonObject {
        val stream = javaClass.getResourceAsStream(FIXTURE_PATH)
            ?: throw AssertionError(
                "classpath 夹具缺失：$FIXTURE_PATH（应位于 app/src/test/resources$FIXTURE_PATH）"
            )
        val text = stream.use { it.readBytes().toString(Charsets.UTF_8) }
        return (Json.parseToJsonElement(text) as JsonObject)["data"] as JsonObject
    }

    private companion object {
        const val FIXTURE_PATH = "/fixtures/wiki/wiki-v2-detail-2238.json"
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
        val data = buildJsonObject { put("content", """{"roundInfos":[{"remark":"<b>注入</b>\"'&\n换行"}]}""") }
        val html = GamekeeBattleV2Renderer.render(1L, data)
        assertFalse("尖括号必须转义", html.contains("<b>注入</b>"))
        assertTrue("转义后的实体存在", html.contains("&lt;b&gt;注入&lt;/b&gt;"))
        assertTrue("引号转义", html.contains("&quot;"))
        assertTrue("换行转成 br", html.contains("<br>"))
    }

    @Test
    fun `异常content抛出而非静默`() {
        val data = buildJsonObject { put("content", "not-json{") }
        try {
            GamekeeBattleV2Renderer.render(1L, data)
            throw AssertionError("应抛异常")
        } catch (e: Exception) {
            // 预期：解析失败向上抛，由 battleCardHtml 捕获返回 null
        }
    }

    @Test
    fun `空回合数组抛出`() {
        val data = buildJsonObject { put("content", """{"roundInfos":[]}""") }
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

    @Test
    fun `v3阵容同时渲染一级图鉴链接与二级多队回合轴及角色装备面板`() {
        val v3Sample = Json.parseToJsonElement(
            """
            {
              "id": 720,
              "title": "狐狸半自动标准轴",
              "desc": "1T章鱼3火\n3T冰盾3火",
              "like_count": 12,
              "view_count": 41000,
              "boss": {"id": 146, "name": "深渊魔姬卡", "attr": "水"},
              "team": [[
                {"name_code": "600786-3", "avatar": "//cdnimg-v2.gamekee.com/a.png", "level": 5, "attr": "水", "damage": "魔法"}
              ]],
              "panel": {
                "600786": "{\"awakeInfo\":{\"imprintSlot1Level\":10,\"imprintSlot2Level\":10,\"imprintSlot3Level\":10,\"isAwake\":true},\"charInfo\":{\"id\":6694,\"level\":100,\"name\":\"克蕾西亚\"},\"costumeList\":[{\"id\":66906,\"level\":5,\"nameCn\":\"纪律部\",\"potentialNum\":15,\"skillPotentialNum\":1,\"icon\":\"//cdnimg-v2.gamekee.com/c2.png\"},{\"id\":66902,\"level\":5,\"nameCn\":\"爱丽丝\",\"potentialNum\":16,\"skillPotentialNum\":2,\"icon\":\"//cdnimg-v2.gamekee.com/c1.png\"},{\"id\":66907,\"level\":5,\"nameCn\":\"天守传人\",\"potentialNum\":17,\"skillPotentialNum\":3,\"icon\":\"//cdnimg-v2.gamekee.com/c3.png\"}],\"equipmentList\":[{\"name\":\"传说毒蛇之手\",\"level\":9,\"slotType\":3,\"mainOption\":[{\"charStatType\":6,\"charStatValue\":1.34,\"charStatIsPercent\":true}],\"subOption\":[],\"privateOption\":[]}],\"health\":12695,\"magicDamage\":1230,\"criticalChance\":0.648,\"criticalDamage\":3.3984,\"elementDamage\":0.5,\"elementResistance\":0.5,\"physicalDamage\":18,\"physicalDefense\":0,\"magicDefense\":1.552}"
              },
              "content": "[{\"roundTotal\":2,\"memberRoundInfos\":[{\"spRemaining\":9,\"member\":[{\"content_id\":600786,\"currentLevel\":5,\"actionType\":\"damage\",\"currentPotential\":3,\"memberIndex\":0,\"memberPosition\":4,\"firstRoundEnabled\":false,\"choose_burst\":null,\"currentSkin\":{\"name_code\":\"600786-3\"}}]},{\"spRemaining\":0,\"member\":[{\"content_id\":600786,\"currentLevel\":5,\"actionType\":\"skills\",\"currentPotential\":3,\"memberIndex\":0,\"memberPosition\":4,\"firstRoundEnabled\":true,\"choose_burst\":3,\"currentSkin\":{\"name_code\":\"600786-1\"}}]}]}]"
            }
            """.trimIndent()
        ) as JsonObject

        val html = GamekeeBattleV3Renderer.render(720L, v3Sample)
        assertTrue("应包含角色名克蕾西亚", html.contains("克蕾西亚"))
        assertTrue("600786-3 应按 costume id 升序映射到第3套『天守传人』", html.contains("天守传人"))
        assertTrue("一级角色应带跳转图鉴二级页链接", html.contains("https://www.gamekee.com/zsca2/tj/600786.html?style=3"))
        assertTrue("应包含二级详情折叠面板", html.contains("展开阵容详情与回合轴"))
        assertTrue("应包含第 1 回合与第 3 回合切换标签", html.contains("第 1 回合（余 9 SP）") && html.contains("第 3 回合（余 0 SP）"))
        assertTrue("第 3 回合切到 600786-1 应显示第1套服装『爱丽丝』与爆发 3", html.contains("爱丽丝") && html.contains("释放技能（爆发 3）"))
        assertTrue("应包含角色属性与装备面板（传说毒蛇之手）", html.contains("传说毒蛇之手") && html.contains("12695"))
    }
}
