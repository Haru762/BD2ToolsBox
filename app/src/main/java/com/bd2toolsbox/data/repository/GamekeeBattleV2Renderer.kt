package com.bd2toolsbox.data.repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** v2 模板的纯 HTML 渲染；不依赖 Android，便于用接口夹具做回归测试。 */
internal object GamekeeBattleV2Renderer {
    fun render(battleId: Long, data: JsonObject): String {
        val raw = requireNotNull(data["content"]) { "v2 阵容缺少 content" }
        val parsed = if (raw is JsonPrimitive) Json.parseToJsonElement(raw.content) else raw
        require(parsed is JsonObject) { "v2 阵容 content 不是对象" }
        val content = parsed
        val roundInfos = requireNotNull(content.array("roundInfos")) { "v2 阵容缺少回合" }
        require(roundInfos.size > 0) { "v2 阵容回合为空" }

        // 魔兽模式的 roundInfos 外面还套着队伍，每队有自己的 roundInfos / spSetting。
        val grouped = roundInfos.any { it is JsonObject && (it).array("roundInfos") != null }
        val groups = if (grouped) roundInfos.map {
            require(it is JsonObject) { "v2 队伍数据异常" }
            it
        } else listOf(content)
        val title = data.text("title").ifBlank { content.text("title") }
            .ifBlank { "阵容组件 #$battleId" }
        val out = StringBuilder("<div class=\"battle battle-v2\"><div class=\"b-head\">")
        out.append("<span class=\"b-title\">⚔️ ").append(esc(title))
            .append("</span><span class=\"b-meta\">v2</span></div>")

        var roundOffset = 0
        groups.forEachIndexed { groupIndex, group ->
            val rounds = requireNotNull(group.array("roundInfos")) { "v2 队伍缺少回合" }
            val battleType = group.number("currentTab") ?: content.number("currentTab") ?: 1
            val showEnemy = group.flag("isShowEnemy") ?: content.flag("isShowEnemy") ?: false
            if (grouped) {
                val groupTitle = group.text("title").ifBlank { "队伍 " + (groupIndex + 1) }
                out.append("<h4 class=\"b-v2-group\">").append(esc(groupTitle)).append("</h4>")
            }
            out.appendSettings(group.obj("spSetting") ?: content.obj("spSetting"))
            rounds.forEachIndexed { roundIndex, round ->
                require(round is JsonObject) { "v2 回合数据异常" }
                out.appendRound(
                    round, roundOffset + roundIndex, battleType, showEnemy,
                    group.obj("unionSetting") ?: content.obj("unionSetting")
                )
            }
            roundOffset += rounds.size
            if (grouped) out.appendRemark("队伍备注", group.text("remark"))
        }
        require(roundOffset > 0) { "v2 阵容没有可展示的回合" }
        out.appendRemark("阵容备注", content.text("remark").ifBlank { data.text("desc") })
        out.append("<div class=\"b-foot\">阵容模拟器 v2 · 按保存的回合数据展示</div></div>")
        return out.toString()
    }

    private fun StringBuilder.appendRound(
        round: JsonObject,
        roundIndex: Int,
        battleType: Int,
        showEnemy: Boolean,
        defaultUnion: JsonObject?
    ) {
        val positions = round.array("ourPositionData") ?: JsonArray(emptyList())
        val actions = round.array("ourSortData")?.mapNotNull {
            it as? JsonObject
        }?.filter { hasActor(it) }.orEmpty()
        // 官网普通/魔兽为 4 列，联合为 5 列。保留数组里的空位，不能按角色 index 排序。
        val columns = if (battleType == 2) 5 else 4
        append("<details class=\"b-v2-round\"")
        if (roundIndex == 0) append(" open")
        append("><summary>回合 ").append(roundIndex * 2 + 1).append("</summary>")
        append("<div class=\"b-team-t\">己方站位 · 面向右侧 · 角标为行动顺序</div>")
        if (positions.size > 0) appendGrid(positions, columns, actions)
        else append("<p class=\"b-v2-note\">未提供己方站位数据</p>")

        val enemies = round.array("enemyData")
        if (showEnemy && enemies != null && enemies.size > 0) {
            append("<details class=\"b-v2-enemy\"><summary>敌方站位</summary>")
            appendGrid(enemies, columns, emptyList(), enemy = true)
            append("</details>")
        }

        append("<div class=\"b-team-t\">行动顺序</div>")
        if (actions.isEmpty()) {
            append("<p class=\"b-v2-note\">未提供行动顺序</p>")
        } else {
            append("<ol class=\"b-v2-actions\">")
            actions.forEach { appendAction(it) }
            append("</ol>")
        }
        round.number("extraAdd")?.takeIf { it != 0 }?.let {
            append("<div class=\"b-v2-note\">本回合额外 SP：").append(it).append("</div>")
        }
        if (battleType == 2) {
            val union = round.obj("unionSetting") ?: defaultUnion
            if (union != null) {
                val action = if (union.text("actionType") == "dead") "待命" else union.text("skill")
                if (action.isNotBlank()) {
                    append("<div class=\"b-v2-note\">兰斯洛特：").append(esc(action))
                    if (union.text("actionType") != "dead") {
                        union.number("level")?.let { append(" · Lv").append(it) }
                    }
                    append("</div>")
                }
            }
        }
        appendRemark("回合备注", round.text("remark"))
        append("</details>")
    }

    private fun StringBuilder.appendGrid(
        cells: JsonArray,
        columns: Int,
        actions: List<JsonObject>,
        enemy: Boolean = false
    ) {
        append("<div class=\"b-v2-grid")
        if (columns == 5) append(" b-v2-grid-5")
        append("\">")
        cells.forEachIndexed { index, element ->
            val actor = element as? JsonObject
            val occupied = hasActor(actor)
            append("<div class=\"b-v2-cell")
            if (!occupied) append(" b-v2-empty")
            append("\" data-slot=\"").append(index + 1).append("\" title=\"第")
                .append(index / columns + 1).append("行第").append(index % columns + 1).append("列\">")
            if (occupied && actor != null) {
                val order = actions.indexOfFirst { sameActor(actor, it) }
                if (order >= 0) {
                    append("<span class=\"b-v2-order\" aria-label=\"行动顺序 ")
                        .append(order + 1).append("\">").append(order + 1).append("</span>")
                }
                appendAvatar(actor, enemy)
                append("<span class=\"b-v2-name\">").append(esc(actorName(actor, enemy))).append("</span>")
            } else {
                append("<span aria-label=\"空位\">·</span>")
            }
            append("</div>")
        }
        append("</div>")
    }

    private fun StringBuilder.appendAction(actor: JsonObject) {
        val skin = actor.obj("currentSkin")
        val actionType = actor.text("actionType")
        val actionLabel = when (actionType) {
            "skills" -> "技能"
            "damage" -> "攻击"
            "repel" -> "击退"
            "dead" -> "无法战斗"
            else -> actionType.ifBlank { "动作未注明" }
        }
        append("<li><div class=\"b-v2-action\">")
        appendAvatar(actor)
        append("<div class=\"b-v2-action-info\"><strong>").append(esc(actorName(actor)))
            .append("</strong>")
        skin?.text("name")?.takeIf { it.isNotBlank() }?.let {
            append("<span class=\"b-v2-skin\"> · ").append(esc(it)).append("</span>")
        }
        append("<div>").append(esc(actionLabel))
        if (actionType == "skills") {
            val skillName = skin?.text("skill_name").orEmpty().ifBlank { actor.text("skill_name") }
            if (skillName.isNotBlank()) append("：").append(esc(skillName))
        }
        append("</div>")
        val details = mutableListOf<String>()
        actor.obj("currentStrengthen")?.text("name")?.takeIf { it.isNotBlank() }?.let { details.add(it) }
        actor.array("currentPotential")?.size?.takeIf { it > 0 }?.let { details.add("潜力 " + it + " 项") }
        if (actionType == "skills") {
            val calc = actor.obj("calcData")
            val sp = calc?.text("sp").orEmpty().ifBlank {
                if (actor.text("type") == "pet") actor.text("sp") else ""
            }
            if (sp.isNotBlank()) details.add("SP " + sp)
            calc?.number("cd")?.takeIf { it > 0 }?.let { details.add("CD " + it) }
        }
        if (details.isNotEmpty()) {
            append("<div class=\"b-v2-note\">").append(esc(details.joinToString(" · "))).append("</div>")
        }
        append("</div></div></li>")
    }

    private fun StringBuilder.appendAvatar(actor: JsonObject, enemy: Boolean = false) {
        val url = listOf(
            actor.obj("currentSkin")?.text("icon").orEmpty(), actor.text("cover_url"), actor.text("icon")
        ).asSequence().mapNotNull { imageUrl(it) }.firstOrNull()
        if (url != null) {
            append("<img class=\"b-v2-avatar\" src=\"").append(esc(url))
                .append("\" alt=\"").append(esc(actorName(actor, enemy))).append("\" loading=\"lazy\">")
        } else {
            append("<span class=\"b-v2-avatar b-v2-no-avatar\" aria-hidden=\"true\">?</span>")
        }
    }

    private fun StringBuilder.appendSettings(settings: JsonObject?) {
        if (settings == null) return
        val values = mutableListOf<String>()
        settings.number("total")?.let { values.add("初始 SP " + it) }
        settings.number("roundAdd")?.let { values.add("每回合恢复 SP " + it) }
        if (values.isNotEmpty()) {
            append("<div class=\"b-v2-note\">").append(esc(values.joinToString(" · "))).append("</div>")
        }
    }

    private fun StringBuilder.appendRemark(label: String, value: String) {
        val text = value.trim()
        if (text.isEmpty() || text == "请输入文本") return
        append("<div class=\"b-desc\"><strong>").append(esc(label)).append("：</strong><br>")
            .append(esc(text).replace("\n", "<br>")).append("</div>")
    }

    private fun hasActor(actor: JsonObject?): Boolean {
        if (actor == null || actor.text("type") == "0") return false
        return actor.text("type").isNotBlank() || actor.text("name").isNotBlank() || actor.obj("currentSkin") != null
    }

    private fun sameActor(first: JsonObject, second: JsonObject): Boolean {
        val key = first.text("uniqueKey")
        if (key.isNotBlank()) return key == second.text("uniqueKey")
        val id = first.text("id")
        return id.isNotBlank() && id == second.text("id") && first.text("type") == second.text("type")
    }

    private fun actorName(actor: JsonObject, enemy: Boolean = false): String =
        actor.text("name").ifBlank { actor.obj("currentSkin")?.text("role_name").orEmpty() }
            .ifBlank { if (enemy) "敌方" else "未命名角色" }

    private fun imageUrl(value: String): String? = when {
        value.startsWith("//") -> "https:$value"
        value.startsWith("https://") -> value
        value.startsWith("http://") -> "https://" + value.removePrefix("http://")
        else -> null
    }

    private fun JsonObject.text(name: String): String =
        (this[name] as? JsonPrimitive)?.content.orEmpty()

    private fun JsonObject.number(name: String): Int? = text(name).toIntOrNull()

    private fun JsonObject.flag(name: String): Boolean? = when (text(name)) {
        "true", "1" -> true
        "false", "0" -> false
        else -> null
    }

    private fun JsonObject.obj(name: String): JsonObject? =
        this[name] as? JsonObject

    private fun JsonObject.array(name: String): JsonArray? =
        this[name] as? JsonArray

    private fun esc(value: String): String = value.replace("&", "&amp;")
        .replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
