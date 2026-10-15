package com.bd2toolsbox.data.repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

/**
 * GameKee v3 阵容模拟器组件（`/v1/bd2Battle/detail`）的完整 HTML 渲染器。
 *
 * 同时还原：
 * 1. **一级概览卡片**：标题、Boss 信息、点赞/浏览数、各队伍首发头像（点击头像可跳入角色图鉴二级页并定位到对应服装）、打法文字说明；
 * 2. **二级交互面板**（`<details>` 展开 + 纯 CSS `input.tr + label.tl + div.tp` 多维切换，无需 JS）：
 *    - **「⚔️ 回合站位与出手轴」**：支持切换「队伍 1 / 队伍 2」及「第 1 / 3 / 5 / 7… 回合」，展示每回合 3×4 棋盘站位网格（含出手顺序角标）与本回合出手顺序、所切服装、动作类型、突破/潜能及剩余 SP；
 *    - **「🛡️ 角色属性与装备面板」**：支持切换查看队伍中每名角色的导入面板（生命/攻击/魔法/暴击/爆伤/属伤/防魔抗/觉醒刻印、持有服装列表、5 件穿戴装备的主副词条详情）。
 */
internal object GamekeeBattleV3Renderer {

    private data class CostumeInfo(
        val id: Int,
        val nameCn: String,
        val level: Int,
        val potentialNum: Int,
        val skillPotentialNum: Int,
        val icon: String?
    )

    private data class EquipOption(
        val statType: Int,
        val value: Double,
        val isPercent: Boolean
    )

    private data class EquipInfo(
        val name: String,
        val level: Int,
        val slotType: Int,
        val mainOptions: List<EquipOption>,
        val subOptions: List<EquipOption>,
        val privateOptions: List<EquipOption>
    )

    private data class CharacterPanel(
        val contentId: String,
        val name: String,
        val level: Int,
        val element: Int,
        val avatarName: String,
        val awake: Boolean,
        val imprint1: Int,
        val imprint2: Int,
        val imprint3: Int,
        val health: Double,
        val physicalDamage: Double,
        val magicDamage: Double,
        val criticalChance: Double,
        val criticalDamage: Double,
        val elementDamage: Double,
        val physicalDefense: Double,
        val magicDefense: Double,
        val elementResistance: Double,
        val costumesByOrder: List<CostumeInfo>,
        val equipments: List<EquipInfo>
    )

    fun render(battleId: Long, data: JsonObject): String {
        val title = data.str("title").ifBlank { "阵容组件 #$battleId" }
        val desc = data.str("desc")
        val likes = data.int("like_count") ?: 0
        val views = data.int("view_count") ?: 0
        val bossObj = data.obj("boss")
        val bossName = bossObj?.str("name").orEmpty()
        val bossAttr = bossObj?.str("attr").orEmpty()
        val bossIcon = absolutize(bossObj?.str("icon"))

        // 解析角色练度面板字典 panel[contentId]
        val panels = parsePanels(data.obj("panel"))

        // 从 team 采集每个 name_code 的兜底头像和属性
        val teamFallbackAvatar = HashMap<String, String>()
        val charFallbackAttr = HashMap<String, String>()
        val teams = data.arr("team") ?: JsonArray(emptyList())
        teams.forEach { teamEl ->
            teamEl.arrOrNull()?.forEach { mEl ->
                val m = mEl.objOrNull() ?: return@forEach
                val nc = m.str("name_code")
                val av = absolutize(m.str("avatar"))
                if (nc.isNotBlank() && av != null) teamFallbackAvatar[nc] = av
                val cid = nc.substringBefore('-')
                val attr = m.str("attr")
                if (cid.isNotBlank() && attr.isNotBlank()) charFallbackAttr[cid] = attr
            }
        }

        val sb = StringBuilder("<div class=\"battle battle-v3\"><div class=\"b-head\">")
        sb.append("<span class=\"b-title\">⚔️ ").append(esc(title)).append("</span>")
        sb.append("<span class=\"b-meta\">")
        if (bossName.isNotBlank()) {
            sb.append("Boss：").append(esc(bossName))
            if (bossAttr.isNotBlank()) sb.append("（").append(esc(bossAttr)).append("）")
            sb.append(" · ")
        }
        if (likes > 0) sb.append("👍").append(likes).append(' ')
        if (views > 0) sb.append("👁").append(views)
        sb.append("</span></div>")

        // ---- 1. 一级阵容概览（点击角色头像可进入该角色图鉴二级页面并切到对应服装） ----
        teams.forEachIndexed { ti, teamEl ->
            if (teams.size > 1) {
                sb.append("<div class=\"b-team-t\">队伍 ").append(ti + 1).append("（点按角色可查看图鉴与服装技能）</div>")
            } else {
                sb.append("<div class=\"b-team-t\">首发阵容（点按角色可查看图鉴与服装技能）</div>")
            }
            sb.append("<div class=\"b-team\">")
            teamEl.arrOrNull()?.forEach { mEl ->
                val m = mEl.objOrNull() ?: return@forEach
                val nameCode = m.str("name_code")
                val cid = nameCode.substringBefore('-')
                val styleIdx = nameCode.substringAfter('-', "1").toIntOrNull() ?: 1
                val resolved = resolveSkin(cid, styleIdx, panels, teamFallbackAvatar)
                val avatar = absolutize(m.str("avatar")) ?: resolved.icon
                val attr = m.str("attr").ifBlank { charFallbackAttr[cid].orEmpty() }
                val dmg = m.str("damage")
                val lv = m.int("level") ?: resolved.level
                val href = if (cid.all { it.isDigit() } && cid.isNotBlank()) {
                    "https://www.gamekee.com/zsca2/tj/$cid.html?style=$styleIdx"
                } else null

                if (href != null) {
                    sb.append("<a class=\"b-m b-m-link\" href=\"").append(esc(href)).append("\">")
                } else {
                    sb.append("<div class=\"b-m\">")
                }
                if (avatar != null) {
                    sb.append("<img class=\"b-av\" src=\"").append(esc(avatar)).append("\" loading=\"lazy\">")
                } else {
                    sb.append("<div class=\"b-av b-av-e\">?</div>")
                }
                if (resolved.charName.isNotBlank()) {
                    sb.append("<span class=\"b-v3-cname\">").append(esc(resolved.charName)).append("</span>")
                }
                if (resolved.skinName.isNotBlank()) {
                    sb.append("<span class=\"b-v3-sname\">").append(esc(resolved.skinName)).append("</span>")
                }
                if (attr.isNotBlank()) {
                    sb.append("<span class=\"b-attr b-").append(attrClass(attr)).append("\">")
                        .append(esc(attr)).append("</span>")
                }
                if (lv != null && lv >= 0) {
                    sb.append("<span class=\"b-lv\">+").append(lv).append("</span>")
                }
                if (dmg.isNotBlank()) {
                    sb.append("<span class=\"b-dmg\">").append(esc(dmg)).append("</span>")
                }
                if (href != null) sb.append("</a>") else sb.append("</div>")
            }
            sb.append("</div>")
        }

        if (desc.isNotBlank()) {
            sb.append("<div class=\"b-desc\">").append(esc(desc).replace("\n", "<br>")).append("</div>")
        }

        // ---- 2. 二级详情面板（回合站位轴 + 角色练度与装备面板） ----
        val contentTeams = parseContentTeams(data["content"])
        if (contentTeams.isNotEmpty() || panels.isNotEmpty()) {
            val totalRounds = contentTeams.sumOf {
                it.arr("memberRoundInfos")?.size ?: 0
            }
            sb.append("<details class=\"b-v3-detail\"><summary class=\"b-v3-summary\">")
            sb.append("📋 展开阵容详情与回合轴（")
            if (contentTeams.size > 1) sb.append(contentTeams.size).append(" 支队伍 · ")
            if (totalRounds > 0) sb.append("共 ").append(totalRounds).append(" 个我方回合")
            if (panels.isNotEmpty()) sb.append(" · 含 ").append(panels.size).append(" 名角色装备面板")
            sb.append("）</summary>")

            val mainTabGroup = "b3m_$battleId"
            sb.append("<div class=\"tabs b-v3-tabs\">")

            // Tab 1: 回合站位与出手轴
            if (contentTeams.isNotEmpty()) {
                val tabRoundsId = "${mainTabGroup}_rounds"
                sb.append("<input class=\"tr\" type=\"radio\" name=\"").append(mainTabGroup)
                    .append("\" id=\"").append(tabRoundsId).append("\" checked>")
                sb.append("<label class=\"tl\" for=\"").append(tabRoundsId).append("\">⚔️ 回合站位与出手轴</label>")
                sb.append("<div class=\"tp\">")
                appendContentTeams(sb, battleId, contentTeams, panels, teamFallbackAvatar)
                sb.append("</div>")
            }

            // Tab 2: 角色练度与装备面板
            if (panels.isNotEmpty()) {
                val tabPanelId = "${mainTabGroup}_panels"
                sb.append("<input class=\"tr\" type=\"radio\" name=\"").append(mainTabGroup)
                    .append("\" id=\"").append(tabPanelId).append('"')
                if (contentTeams.isEmpty()) sb.append(" checked")
                sb.append("><label class=\"tl\" for=\"").append(tabPanelId).append("\">🛡️ 角色属性与装备（")
                    .append(panels.size).append("）</label>")
                sb.append("<div class=\"tp\">")
                appendCharacterPanels(sb, battleId, panels, teams)
                sb.append("</div>")
            }

            sb.append("</div></details>")
        }

        sb.append("<div class=\"b-foot\">阵容模拟器 v3 · 支持点按角色查看图鉴或展开切换回合/装备面板</div></div>")
        return sb.toString()
    }

    private fun appendContentTeams(
        sb: StringBuilder,
        battleId: Long,
        contentTeams: List<JsonObject>,
        panels: Map<String, CharacterPanel>,
        teamFallbackAvatar: Map<String, String>
    ) {
        if (contentTeams.size > 1) {
            val teamGroup = "b3t_$battleId"
            sb.append("<div class=\"tabs\">")
            contentTeams.forEachIndexed { tIdx, teamObj ->
                val tid = "${teamGroup}_$tIdx"
                sb.append("<input class=\"tr\" type=\"radio\" name=\"").append(teamGroup)
                    .append("\" id=\"").append(tid).append('"')
                if (tIdx == 0) sb.append(" checked")
                sb.append("><label class=\"tl\" for=\"").append(tid).append("\">队伍 ")
                    .append(tIdx + 1).append("</label><div class=\"tp\">")
                appendTeamRounds(sb, battleId, tIdx, teamObj, panels, teamFallbackAvatar)
                sb.append("</div>")
            }
            sb.append("</div>")
        } else {
            appendTeamRounds(sb, battleId, 0, contentTeams[0], panels, teamFallbackAvatar)
        }
    }

    private fun appendTeamRounds(
        sb: StringBuilder,
        battleId: Long,
        teamIdx: Int,
        teamObj: JsonObject,
        panels: Map<String, CharacterPanel>,
        teamFallbackAvatar: Map<String, String>
    ) {
        val rounds = teamObj.arr("memberRoundInfos")?.mapNotNull { it.objOrNull() }.orEmpty()
        if (rounds.isEmpty()) {
            sb.append("<p class=\"b-v2-note\">暂无回合详情</p>")
            return
        }
        val roundGroup = "b3r_${battleId}_$teamIdx"
        sb.append("<div class=\"tabs\">")
        rounds.forEachIndexed { rIdx, roundObj ->
            val rid = "${roundGroup}_$rIdx"
            val turnNumber = rIdx * 2 + 1
            val spRem = roundObj.int("spRemaining")
            sb.append("<input class=\"tr\" type=\"radio\" name=\"").append(roundGroup)
                .append("\" id=\"").append(rid).append('"')
            if (rIdx == 0) sb.append(" checked")
            sb.append("><label class=\"tl\" for=\"").append(rid).append("\">第 ")
                .append(turnNumber).append(" 回合")
            if (spRem != null) sb.append("（余 ").append(spRem).append(" SP）")
            sb.append("</label><div class=\"tp\">")
            appendSingleRound(sb, roundObj, panels, teamFallbackAvatar)
            sb.append("</div>")
        }
        sb.append("</div>")
    }

    private fun appendSingleRound(
        sb: StringBuilder,
        roundObj: JsonObject,
        panels: Map<String, CharacterPanel>,
        teamFallbackAvatar: Map<String, String>
    ) {
        val members = roundObj.arr("member")?.mapNotNull { it.objOrNull() }.orEmpty()
        if (members.isEmpty()) {
            sb.append("<p class=\"b-v2-note\">本回合无角色行动数据</p>")
            return
        }
        val byPos = members.associateBy { it.int("memberPosition") ?: -1 }
        val maxPos = members.maxOfOrNull { it.int("memberPosition") ?: 0 } ?: 11
        val cols = if (maxPos >= 12) 5 else 4
        val totalCells = if (cols == 5) 15 else 12

        sb.append("<div class=\"b-team-t\">己方站位（面向右侧 · 右上角数字为本回合行动顺序）</div>")
        sb.append("<div class=\"b-v2-grid")
        if (cols == 5) sb.append(" b-v2-grid-5")
        sb.append("\">")
        for (slot in 0 until totalCells) {
            val m = byPos[slot]
            sb.append("<div class=\"b-v2-cell")
            if (m == null) sb.append(" b-v2-empty")
            sb.append("\" data-slot=\"").append(slot + 1).append("\">")
            if (m != null) {
                val order = (m.int("memberIndex") ?: 0) + 1
                val cid = m.str("content_id")
                val nameCode = m.obj("currentSkin")?.str("name_code").orEmpty()
                val styleIdx = nameCode.substringAfter('-', "1").toIntOrNull() ?: 1
                val skin = resolveSkin(cid, styleIdx, panels, teamFallbackAvatar)
                sb.append("<span class=\"b-v2-order\">").append(order).append("</span>")
                if (skin.icon != null) {
                    sb.append("<img class=\"b-v2-avatar\" src=\"").append(esc(skin.icon))
                        .append("\" alt=\"").append(esc(skin.charName)).append("\" loading=\"lazy\">")
                } else {
                    sb.append("<span class=\"b-v2-avatar b-v2-no-avatar\">?</span>")
                }
                sb.append("<span class=\"b-v2-name\">")
                    .append(esc(skin.charName.ifBlank { "#$cid" })).append("</span>")
            } else {
                sb.append("<span aria-label=\"空位\">·</span>")
            }
            sb.append("</div>")
        }
        sb.append("</div>")

        // 出手顺序列表
        val sortedActions = members.sortedBy { it.int("memberIndex") ?: 0 }
        sb.append("<div class=\"b-team-t\">本回合行动顺序与服装切换</div>")
        sb.append("<ol class=\"b-v2-actions\">")
        for (m in sortedActions) {
            val cid = m.str("content_id")
            val nameCode = m.obj("currentSkin")?.str("name_code").orEmpty()
            val styleIdx = nameCode.substringAfter('-', "1").toIntOrNull() ?: 1
            val skin = resolveSkin(cid, styleIdx, panels, teamFallbackAvatar)
            val actionType = m.str("actionType")
            val burst = m.int("choose_burst")
            val firstRound = m.bool("firstRoundEnabled") == true
            val lv = m.int("currentLevel") ?: skin.level
            val pot = m.int("currentPotential")
            val actionLabel = when (actionType) {
                "skills" -> if (burst != null && burst > 0) "释放技能（爆发 $burst）" else "释放技能"
                "damage" -> "普通攻击"
                "repel" -> "击退"
                "dead" -> "待命 / 无法战斗"
                else -> actionType.ifBlank { "行动" }
            }
            val href = if (cid.all { it.isDigit() } && cid.isNotBlank()) {
                "https://www.gamekee.com/zsca2/tj/$cid.html?style=$styleIdx"
            } else null

            sb.append("<li><div class=\"b-v2-action\">")
            if (skin.icon != null) {
                sb.append("<img class=\"b-v2-avatar\" src=\"").append(esc(skin.icon))
                    .append("\" alt=\"\" loading=\"lazy\">")
            } else {
                sb.append("<span class=\"b-v2-avatar b-v2-no-avatar\">?</span>")
            }
            sb.append("<div class=\"b-v2-action-info\">")
            if (href != null) {
                sb.append("<a href=\"").append(esc(href)).append("\"><strong>")
                    .append(esc(skin.charName.ifBlank { "角色 #$cid" })).append("</strong></a>")
            } else {
                sb.append("<strong>").append(esc(skin.charName.ifBlank { "角色 #$cid" })).append("</strong>")
            }
            if (skin.skinName.isNotBlank()) {
                sb.append("<span class=\"b-v2-skin\"> · ").append(esc(skin.skinName)).append("</span>")
            }
            sb.append("<div>").append(esc(actionLabel))
            if (firstRound) sb.append(" · <span class=\"b-v3-tag\">先发制人</span>")
            sb.append("</div>")
            val metaParts = mutableListOf<String>()
            if (lv != null && lv >= 0) metaParts.add("突破 +$lv")
            if (pot != null && pot >= 0) metaParts.add("技能潜能 $pot")
            if (metaParts.isNotEmpty()) {
                sb.append("<div class=\"b-v2-note\">").append(esc(metaParts.joinToString(" · "))).append("</div>")
            }
            sb.append("</div></div></li>")
        }
        sb.append("</ol>")
    }

    private fun appendCharacterPanels(
        sb: StringBuilder,
        battleId: Long,
        panels: Map<String, CharacterPanel>,
        teams: JsonArray
    ) {
        // 按队伍出场顺序排列角色面板
        val orderedIds = LinkedHashSet<String>()
        teams.forEach { teamEl ->
            teamEl.arrOrNull()?.forEach { mEl ->
                val cid = mEl.objOrNull()?.str("name_code")?.substringBefore('-').orEmpty()
                if (cid in panels) orderedIds.add(cid)
            }
        }
        orderedIds.addAll(panels.keys)

        val charGroup = "b3p_$battleId"
        sb.append("<div class=\"tabs\">")
        orderedIds.forEachIndexed { idx, cid ->
            val p = panels[cid] ?: return@forEachIndexed
            val pid = "${charGroup}_$cid"
            sb.append("<input class=\"tr\" type=\"radio\" name=\"").append(charGroup)
                .append("\" id=\"").append(pid).append('"')
            if (idx == 0) sb.append(" checked")
            sb.append("><label class=\"tl\" for=\"").append(pid).append("\">")
                .append(esc(p.name.ifBlank { "#$cid" })).append("</label><div class=\"tp\">")

            // 1. 角色属性概览
            val href = "https://www.gamekee.com/zsca2/tj/$cid.html"
            sb.append("<div class=\"b-v3-phead\"><strong>").append(esc(p.name))
                .append("</strong>（Lv.").append(p.level)
            if (p.awake) {
                sb.append(" · 已觉醒 · 刻印 ").append(p.imprint1).append("/")
                    .append(p.imprint2).append("/").append(p.imprint3)
            }
            sb.append("） · <a href=\"").append(esc(href)).append("\">查看角色图鉴 →</a></div>")

            sb.append("<table class=\"gktable b-v3-ptable\">")
            sb.append("<tr><td>生命力</td><td>").append(fmtNum(p.health))
                .append("</td><td>攻击 / 魔法力</td><td>")
                .append(fmtNum(p.physicalDamage)).append(" / ").append(fmtNum(p.magicDamage))
                .append("</td></tr>")
            sb.append("<tr><td>致命率 / 致命伤害</td><td>")
                .append(fmtPct(p.criticalChance)).append(" / ").append(fmtPct(p.criticalDamage))
                .append("</td><td>属性伤害 / 抵抗</td><td>")
                .append(fmtPct(p.elementDamage)).append(" / ").append(fmtPct(p.elementResistance))
                .append("</td></tr>")
            sb.append("<tr><td>防御力 / 魔法抵抗</td><td colspan=\"3\">")
                .append(fmtPct(p.physicalDefense)).append(" / ").append(fmtPct(p.magicDefense))
                .append("</td></tr>")
            sb.append("</table>")

            // 2. 持有服装列表
            if (p.costumesByOrder.isNotEmpty()) {
                sb.append("<div class=\"b-team-t\">持有服装（按图鉴顺序）</div><div class=\"b-team\">")
                p.costumesByOrder.forEachIndexed { cIdx, c ->
                    val styleHref = "https://www.gamekee.com/zsca2/tj/$cid.html?style=${cIdx + 1}"
                    sb.append("<a class=\"b-m b-m-link\" href=\"").append(esc(styleHref)).append("\">")
                    if (c.icon != null) {
                        sb.append("<img class=\"b-av\" src=\"").append(esc(c.icon)).append("\" loading=\"lazy\">")
                    } else {
                        sb.append("<div class=\"b-av b-av-e\">?</div>")
                    }
                    sb.append("<span class=\"b-v3-sname\">").append(esc(c.nameCn)).append("</span>")
                    sb.append("<span class=\"b-lv\">+").append(c.level)
                        .append(" · 潜").append(c.skillPotentialNum).append("</span></a>")
                }
                sb.append("</div>")
            }

            // 3. 穿戴装备列表
            if (p.equipments.isNotEmpty()) {
                sb.append("<div class=\"b-team-t\">穿戴装备（").append(p.equipments.size).append(" 件）</div>")
                sb.append("<table class=\"gktable b-v3-ptable\">")
                for (eq in p.equipments) {
                    sb.append("<tr><td><strong>").append(esc(eq.name)).append("</strong>")
                    if (eq.level > 0) sb.append(" +").append(eq.level)
                    sb.append("</td><td>")
                    val opts = mutableListOf<String>()
                    if (eq.privateOptions.isNotEmpty()) {
                        opts.add("专武：" + eq.privateOptions.joinToString("/") { formatEquipOpt(it) })
                    }
                    if (eq.mainOptions.isNotEmpty()) {
                        opts.add("主：" + eq.mainOptions.joinToString("/") { formatEquipOpt(it) })
                    }
                    if (eq.subOptions.isNotEmpty()) {
                        opts.add("副：" + eq.subOptions.joinToString("/") { formatEquipOpt(it) })
                    }
                    sb.append(esc(opts.joinToString(" ｜ "))).append("</td></tr>")
                }
                sb.append("</table>")
            }

            sb.append("</div>")
        }
        sb.append("</div>")
    }

    private data class ResolvedSkin(
        val charName: String,
        val skinName: String,
        val icon: String?,
        val level: Int?
    )

    private fun resolveSkin(
        cid: String,
        styleIdx: Int,
        panels: Map<String, CharacterPanel>,
        teamFallbackAvatar: Map<String, String>
    ): ResolvedSkin {
        val p = panels[cid]
        val costume = p?.costumesByOrder?.getOrNull(styleIdx - 1)
            ?: p?.costumesByOrder?.lastOrNull()
        val fallbackIcon = teamFallbackAvatar["$cid-$styleIdx"]
        return ResolvedSkin(
            charName = p?.name.orEmpty(),
            skinName = costume?.nameCn.orEmpty(),
            icon = costume?.icon ?: fallbackIcon,
            level = costume?.level
        )
    }

    private fun parseContentTeams(raw: JsonElement?): List<JsonObject> {
        if (raw == null) return emptyList()
        val parsed = try {
            if (raw is JsonPrimitive && raw.isString) Json.parseToJsonElement(raw.content) else raw
        } catch (_: Exception) {
            return emptyList()
        }
        return parsed.arrOrNull()?.mapNotNull { it.objOrNull() }.orEmpty()
    }

    private fun parsePanels(panelObj: JsonObject?): Map<String, CharacterPanel> {
        if (panelObj == null) return emptyMap()
        val out = LinkedHashMap<String, CharacterPanel>()
        for ((cid, rawEl) in panelObj) {
            val pObj = try {
                when (rawEl) {
                    is JsonPrimitive -> if (rawEl.isString && rawEl.content.isNotBlank()) {
                        Json.parseToJsonElement(rawEl.content).objOrNull()
                    } else null
                    is JsonObject -> rawEl
                    else -> null
                }
            } catch (_: Exception) {
                null
            } ?: continue

            val charInfo = pObj.obj("charInfo")
            val awakeInfo = pObj.obj("awakeInfo")
            val costumes = pObj.arr("costumeList")?.mapNotNull { el ->
                val o = el.objOrNull() ?: return@mapNotNull null
                CostumeInfo(
                    id = o.int("id") ?: 0,
                    nameCn = o.str("nameCn"),
                    level = o.int("level") ?: 0,
                    potentialNum = o.int("potentialNum") ?: 0,
                    skillPotentialNum = o.int("skillPotentialNum") ?: 0,
                    icon = absolutize(o.str("icon"))
                )
            }?.sortedBy { it.id }.orEmpty()

            val equips = pObj.arr("equipmentList")?.mapNotNull { el ->
                val o = el.objOrNull() ?: return@mapNotNull null
                EquipInfo(
                    name = o.str("name"),
                    level = o.int("level") ?: 0,
                    slotType = o.int("slotType") ?: 0,
                    mainOptions = parseEquipOptions(o.arr("mainOption")),
                    subOptions = parseEquipOptions(o.arr("subOption")),
                    privateOptions = parseEquipOptions(o.arr("privateOption"))
                )
            }?.sortedBy { it.slotType }.orEmpty()

            out[cid] = CharacterPanel(
                contentId = cid,
                name = charInfo?.str("name").orEmpty(),
                level = charInfo?.int("level") ?: 100,
                element = charInfo?.int("element") ?: 0,
                avatarName = charInfo?.str("avatarName").orEmpty(),
                awake = awakeInfo?.bool("isAwake") == true,
                imprint1 = awakeInfo?.int("imprintSlot1Level") ?: 0,
                imprint2 = awakeInfo?.int("imprintSlot2Level") ?: 0,
                imprint3 = awakeInfo?.int("imprintSlot3Level") ?: 0,
                health = pObj.double("health") ?: 0.0,
                physicalDamage = pObj.double("physicalDamage") ?: 0.0,
                magicDamage = pObj.double("magicDamage") ?: 0.0,
                criticalChance = pObj.double("criticalChance") ?: 0.0,
                criticalDamage = pObj.double("criticalDamage") ?: 0.0,
                elementDamage = pObj.double("elementDamage") ?: 0.0,
                physicalDefense = pObj.double("physicalDefense") ?: 0.0,
                magicDefense = pObj.double("magicDefense") ?: 0.0,
                elementResistance = pObj.double("elementResistance") ?: 0.0,
                costumesByOrder = costumes,
                equipments = equips
            )
        }
        return out
    }

    private fun parseEquipOptions(arr: JsonArray?): List<EquipOption> =
        arr?.mapNotNull { el ->
            val o = el.objOrNull() ?: return@mapNotNull null
            EquipOption(
                statType = o.int("charStatType") ?: -1,
                value = o.double("charStatValue") ?: 0.0,
                isPercent = o.bool("charStatIsPercent") == true
            )
        }.orEmpty()

    private fun formatEquipOpt(opt: EquipOption): String {
        val label = when (opt.statType) {
            0 -> "生命"
            1 -> "物攻"
            2 -> "魔攻"
            3 -> "防御"
            4 -> "魔抗"
            5 -> "暴击"
            6 -> "爆伤"
            else -> "属性"
        }
        val valStr = if (opt.isPercent) fmtPct(opt.value) else fmtNum(opt.value)
        return "$label+$valStr"
    }

    private fun fmtNum(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString()
        else String.format(Locale.US, "%.1f", v)

    private fun fmtPct(v: Double): String =
        String.format(Locale.US, "%.1f%%", v * 100.0).removeSuffix(".0%") +
            if (String.format(Locale.US, "%.1f%%", v * 100.0).endsWith(".0%")) "%" else ""

    private fun attrClass(attr: String) = when {
        attr.contains("火") -> "huo"
        attr.contains("水") -> "shui"
        attr.contains("风") -> "feng"
        attr.contains("光") -> "guang"
        attr.contains("暗") -> "an"
        else -> "wu"
    }

    private fun absolutize(url: String?): String? {
        val u = url?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return when {
            u.startsWith("//") -> "https:$u"
            u.startsWith("http://") -> "https://${u.removePrefix("http://")}"
            u.startsWith("https://") -> u
            else -> null
        }
    }

    private fun JsonElement?.objOrNull(): JsonObject? = this as? JsonObject
    private fun JsonElement?.arrOrNull(): JsonArray? = this as? JsonArray
    private fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
    private fun JsonObject.arr(k: String): JsonArray? = this[k] as? JsonArray
    private fun JsonObject.str(k: String): String = (this[k] as? JsonPrimitive)?.content.orEmpty()
    private fun JsonObject.int(k: String): Int? = str(k).toIntOrNull()
    private fun JsonObject.double(k: String): Double? = str(k).toDoubleOrNull()
    private fun JsonObject.bool(k: String): Boolean? = when (str(k)) {
        "true", "1" -> true
        "false", "0" -> false
        else -> null
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
