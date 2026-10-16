package com.bd2toolsbox.data.repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * GameKee v3 阵容模拟器组件（`/v1/bd2Battle/detail`）的移动端优先（Mobile-First）渲染器。
 *
 * 数据层：严格通过 [GamekeeBattleRoleCatalog]（官方 `bd2roles/data.json`）按 `name_code` 精确还原
 * 角色、服装、九宫格攻击范围图、技能图标、击退图标、SP/CD 与技能说明。
 *
 * 视觉与交互层（针对手机竖屏 360dp~412dp 深度重构）：
 * 1. **外层阵容卡（`.user-lineup-card`）**：
 *    - 顶栏显示阵容类型徽章、Boss 标签、最高伤害与排轴状态；
 *    - 5 列（公会战 7 列）完整 1:1 圆角头像卡（带五属性渐变底色、左上突破徽章、右上属性/物魔图标、底部角色名与服装名）；
 *    - 底部全宽展开条一键展开/收起二级详情面板（面板底部同步提供一键收起按钮）。
 * 2. **二级详情面板（上下流式布局，告别 PC 左右硬挤）**：
 *    - 顶部分段控制器：`[站位与攻击顺序 | 服装出手顺序 | 角色属性与装备]`；
 *    - **站位与攻击顺序**：横向回合轴（`第 1 回合` / `第 3 回合`… + `余 X SP` + `先发制人/调站位/调顺序`）→
 *      上方居中大尺寸 3×4（或 5×5）战术棋盘（格内直接展示出手顺位 `①~⑤`、突破等级、潜能角标、右下九宫格攻击范围缩略图与底部 SP 菱形星标）→
 *      下方按出手顺序 `1 → 5` 排列的横向行动流卡片（含头像、角色·服装名、动作/爆发徽章、右侧九宫格攻击范围图与可展开技能说明）；
 *    - **服装出手顺序**：首列冻结（Sticky）角色头像与名称 + 右侧可横滑的全回合技能矩阵；
 *    - **角色属性与装备**：现代资料卡片化展示 8 项核心面板、持有服装与 5 件装备词条。
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

    private data class RestoredMember(
        val uniqueKey: String,
        val type: String, // "role" or "pet"
        val contentId: String,
        val nameCode: String,
        val styleIdx: Int,
        val charName: String,
        val skinName: String,
        val attr: String,
        val damage: String,
        val starInt: Int,
        val currentLevel: Int?,
        val currentPotential: Int,
        val actionType: String, // "skills", "damage", "repel", "not"
        val memberIndex: Int,
        val memberPosition: Int,
        val firstRoundEnabled: Boolean,
        val chooseBurst: Int?,
        val coverUrl: String?,
        val skillCastUrl: String?,
        val skillIconUrl: String?,
        val rangeIconUrl: String?,
        val spCost: Int,
        val cdTurns: Int,
        val skillDesc: String
    )

    private data class RestoredRound(
        val members: List<RestoredMember>,
        val spRemaining: Int?,
        val bossAction: String?
    )

    private data class RestoredTeam(
        val roundTotal: Int,
        val rounds: List<RestoredRound>
    )

    fun render(battleId: Long, data: JsonObject): String {
        GamekeeBattleRoleCatalog.ensureLoaded()

        val title = data.str("title").ifBlank { "阵容组件 #$battleId" }
        val desc = data.str("desc")
        val lineupType = data.int("type") ?: 2 // 1=通用阵容, 2=魔兽阵容, 3=公会战阵容
        val isRow = data.int("is_row") == 1 || data.bool("is_row") == true
        val likes = data.int("like_count") ?: 0
        val views = data.int("view_count") ?: 0
        val updatedAt = data.long("updated_at") ?: 0L
        val maxDamageRaw = data.obj("statistics_info")?.str("max_damage")?.ifBlank { null }
            ?: data.str("max_damage").ifBlank { null }
        val maxDamageText = formatStatisticNumber(maxDamageRaw)

        val bossObj = data.obj("boss")
        val bossName = bossObj?.str("name").orEmpty()
        val bossAttr = bossObj?.str("attr").orEmpty()
        val bossIcon = absolutize(bossObj?.str("icon"))

        val userObj = data.obj("user")
        val userName = userObj?.str("username").orEmpty()
        val userAvatar = absolutize(userObj?.str("avatar"))

        val panels = parsePanels(data.obj("panel"))

        val teamFallbackAvatar = HashMap<String, String>()
        val charFallbackAttr = HashMap<String, String>()
        val charFallbackDamage = HashMap<String, String>()
        val teams = data.arr("team") ?: JsonArray(emptyList())
        teams.forEach { teamEl ->
            teamEl.arrOrNull()?.forEach { mEl ->
                val m = mEl.objOrNull() ?: return@forEach
                val nc = m.str("name_code")
                val av = absolutize(m.str("avatar"))
                if (nc.isNotBlank() && av != null) teamFallbackAvatar[nc] = av
                val cid = nc.substringBefore('-')
                val attr = m.str("attr")
                val dmg = m.str("damage")
                if (cid.isNotBlank() && attr.isNotBlank()) charFallbackAttr[cid] = attr
                if (cid.isNotBlank() && dmg.isNotBlank()) charFallbackDamage[cid] = dmg
            }
        }

        val restoredTeams = restoreTeams(
            rawContent = data["content"],
            panels = panels,
            teamFallbackAvatar = teamFallbackAvatar,
            charFallbackAttr = charFallbackAttr,
            charFallbackDamage = charFallbackDamage
        )

        val sb = StringBuilder()
        sb.append("<div class=\"gk-lineup-card user-lineup-card view battle battle-v3\">")

        // ---- 1. 顶部元信息与标题区 ----
        val (typeTagText, typeTagClass) = when (lineupType) {
            1 -> "通用阵容" to "is-main"
            2 -> "魔兽阵容" to "is-warcraft"
            3 -> "公会战阵容" to "is-guild"
            else -> "推荐阵容" to ""
        }
        sb.append("<div class=\"card-header\"><div class=\"line\"><div class=\"left\">")
        sb.append("<span class=\"type-tag ").append(typeTagClass).append("\">")
            .append(esc(typeTagText)).append("</span>")
        if ((lineupType == 2 || lineupType == 3) && bossName.isNotBlank()) {
            sb.append("<span class=\"gk-boss-chip\">")
            if (bossIcon != null) {
                sb.append("<img src=\"").append(esc(bossIcon)).append("\" alt=\"\" loading=\"lazy\">")
            }
            sb.append(esc(bossName))
            if (bossAttr.isNotBlank()) sb.append("·").append(esc(bossAttr))
            sb.append("</span>")
        }
        if (maxDamageText.isNotBlank() && maxDamageText != "0") {
            sb.append("<span class=\"gk-dmg-chip\">🔥 ").append(esc(maxDamageText)).append("</span>")
        }
        sb.append("</div><span class=\"update-time\">")
        if (updatedAt > 0L) {
            sb.append(esc(formatDate(updatedAt)))
        } else if (likes > 0) {
            sb.append("👍 ").append(likes)
        }
        sb.append("</span></div>")
        sb.append("<div class=\"gk-card-title-row\"><span class=\"b-title\">")
            .append(esc(title)).append("</span>")
        sb.append("<span class=\"gk-arrange-badge").append(if (isRow) " is-arranged" else "")
            .append("\">").append(if (isRow) "已排轴" else "阵容推荐").append("</span>")
        sb.append("</div></div>")

        // ---- 2. 队伍成员网格（完整 1:1 高清圆角头像 + 底部双行名称） ----
        val isBtype3 = lineupType == 3
        teams.forEachIndexed { ti, teamEl ->
            val members = teamEl.arrOrNull()?.mapNotNull { it.objOrNull() }
                ?.filter { it.str("type") != "pet" && it.str("star") != "pet" }
                .orEmpty()
            if (members.isEmpty()) return@forEachIndexed

            sb.append("<div class=\"group-row\">")
            if (teams.size > 1) {
                sb.append("<div class=\"group-title\">队伍 ").append(ti + 1).append("</div>")
            }
            sb.append("<div class=\"avatar-list").append(if (isBtype3) " is-btype-3" else "").append("\">")
            for (m in members) {
                val nameCode = m.str("name_code")
                val cid = nameCode.substringBefore('-')
                val styleIdx = nameCode.substringAfter('-', "1").toIntOrNull() ?: 1
                val resolved = resolveSkinMeta(
                    cid = cid,
                    nameCode = nameCode,
                    styleIdx = styleIdx,
                    panels = panels,
                    teamFallbackAvatar = teamFallbackAvatar,
                    charFallbackAttr = charFallbackAttr,
                    charFallbackDamage = charFallbackDamage
                )
                val avatar = absolutize(m.str("avatar")) ?: resolved.icon
                val attr = m.str("attr").ifBlank { resolved.attr }
                val dmg = m.str("damage").ifBlank { resolved.damage }
                val lv = m.int("level") ?: resolved.level
                val bgClass = GamekeeBattleAssets.attrBgClass(attr)
                val strenthBadge = GamekeeBattleAssets.strenthBadge(lv)
                val attrBadge = GamekeeBattleAssets.attrIcon(attr)
                val dmgBadge = GamekeeBattleAssets.damageIcon(dmg)
                val href = if (cid.all { it.isDigit() } && cid.isNotBlank()) {
                    "https://www.gamekee.com/zsca2/tj/$cid.html?style=$styleIdx"
                } else null

                if (href != null) {
                    sb.append("<a class=\"gk-role-cell\" href=\"").append(esc(href)).append("\">")
                } else {
                    sb.append("<div class=\"gk-role-cell\">")
                }
                sb.append("<div class=\"avatar-item ").append(bgClass).append("\">")
                if (avatar != null) {
                    sb.append("<img class=\"avatar\" src=\"").append(esc(avatar))
                        .append("\" alt=\"").append(esc(resolved.charName)).append("\" loading=\"lazy\">")
                } else {
                    sb.append("<div class=\"avatar b-av-e\">?</div>")
                }
                if (strenthBadge.isNotEmpty()) {
                    sb.append("<img class=\"gk-cell-strenth\" src=\"").append(strenthBadge).append("\" alt=\"+$lv\">")
                }
                sb.append("<div class=\"tag-box\">")
                if (attrBadge.isNotEmpty()) {
                    sb.append("<div class=\"role-attr-icon-box\"><img src=\"").append(attrBadge)
                        .append("\" alt=\"").append(esc(attr)).append("\"></div>")
                }
                if (dmgBadge.isNotEmpty()) {
                    sb.append("<div class=\"damage-type-icon-box\"><img class=\"filter\" src=\"")
                        .append(dmgBadge).append("\" alt=\"").append(esc(dmg)).append("\"></div>")
                }
                sb.append("</div></div>")
                if (resolved.charName.isNotBlank()) {
                    sb.append("<span class=\"b-v3-cname\">").append(esc(resolved.charName)).append("</span>")
                }
                if (resolved.skinName.isNotBlank()) {
                    sb.append("<span class=\"b-v3-sname\">").append(esc(resolved.skinName)).append("</span>")
                }
                if (href != null) sb.append("</a>") else sb.append("</div>")
            }
            sb.append("</div></div>")
        }

        // ---- 3. 打法与轴说明 (.desc) ----
        if (desc.isNotBlank()) {
            sb.append("<div class=\"desc b-desc\">").append(esc(desc).replace("\n", "<br>")).append("</div>")
        }

        // ---- 4. 作者与互动底栏 ----
        if (userName.isNotBlank() || likes > 0 || views > 0) {
            sb.append("<div class=\"bottom-line\"><div class=\"left-bottom\">")
            if (userAvatar != null) {
                sb.append("<img class=\"avatar\" src=\"").append(esc(userAvatar)).append("\" alt=\"\" loading=\"lazy\">")
            }
            if (userName.isNotBlank()) {
                sb.append("<span class=\"name\">").append(esc(userName)).append("</span>")
            }
            sb.append("</div><div class=\"right-like\">")
            if (likes > 0) sb.append("<span class=\"num\">👍 ").append(likes).append("</span>")
            if (views > 0) sb.append("<span class=\"num\">👁 ").append(views).append("</span>")
            sb.append("</div></div>")
        }

        // ---- 5. 二级详情面板（移动端上下流式交互） ----
        if (restoredTeams.isNotEmpty() || panels.isNotEmpty()) {
            val totalRounds = restoredTeams.sumOf { it.rounds.size }
            sb.append("<details class=\"b-v3-detail\"><summary class=\"b-v3-summary\">")
            sb.append("<span>⚔️ 展开阵容详情与回合轴（")
            if (restoredTeams.size > 1) sb.append(restoredTeams.size).append(" 队 · ")
            if (totalRounds > 0) sb.append(totalRounds).append(" 回合")
            if (panels.isNotEmpty()) sb.append(" · ").append(panels.size).append(" 人练度面板")
            sb.append("）</span><span class=\"gk-summary-arrow\">▾</span></summary>")

            sb.append("<div class=\"gk-detail-wrap\">")
            if (restoredTeams.isNotEmpty()) {
                appendLineupDetailTeams(sb, battleId, lineupType, restoredTeams, panels, teams)
            } else if (panels.isNotEmpty()) {
                appendCharacterPanels(sb, battleId, panels, teams)
            }
            sb.append("<div class=\"gk-collapse-bottom\" onclick=\"var d=this.closest('.b-v3-detail');if(d){d.open=false;d.scrollIntoView({behavior:'smooth',block:'nearest'});}\">")
                .append("▴ 收起阵容详情</div>")
            sb.append("</div></details>")
        }

        sb.append("</div>")
        return sb.toString()
    }

    private fun appendLineupDetailTeams(
        sb: StringBuilder,
        battleId: Long,
        lineupType: Int,
        restoredTeams: List<RestoredTeam>,
        panels: Map<String, CharacterPanel>,
        rawTeams: JsonArray
    ) {
        if (restoredTeams.size > 1) {
            val teamGroup = "b3t_$battleId"
            sb.append("<div class=\"tabs gk-team-tabs\">")
            var priorRounds = 0
            restoredTeams.forEachIndexed { tIdx, team ->
                val tid = "${teamGroup}_$tIdx"
                sb.append("<input class=\"tr\" type=\"radio\" name=\"").append(teamGroup)
                    .append("\" id=\"").append(tid).append('"')
                if (tIdx == 0) sb.append(" checked")
                sb.append("><label class=\"tl gk-team-tab\" for=\"").append(tid).append("\">队伍 ")
                    .append(tIdx + 1).append("</label><div class=\"tp gk-team-tp\">")
                appendSingleTeamDetail(
                    sb = sb,
                    battleId = battleId,
                    teamIdx = tIdx,
                    lineupType = lineupType,
                    team = team,
                    priorRounds = priorRounds,
                    panels = panels,
                    rawTeams = rawTeams
                )
                sb.append("</div>")
                priorRounds += team.roundTotal.coerceAtLeast(team.rounds.size)
            }
            sb.append("</div>")
        } else {
            appendSingleTeamDetail(
                sb = sb,
                battleId = battleId,
                teamIdx = 0,
                lineupType = lineupType,
                team = restoredTeams[0],
                priorRounds = 0,
                panels = panels,
                rawTeams = rawTeams
            )
        }
    }

    private fun appendSingleTeamDetail(
        sb: StringBuilder,
        battleId: Long,
        teamIdx: Int,
        lineupType: Int,
        team: RestoredTeam,
        priorRounds: Int,
        panels: Map<String, CharacterPanel>,
        rawTeams: JsonArray
    ) {
        val modeGroup = "b3mode_${battleId}_$teamIdx"
        val mode1Id = "${modeGroup}_1"
        val mode2Id = "${modeGroup}_2"
        val mode3Id = "${modeGroup}_3"

        sb.append("<div class=\"tabs gk-mode-tabs\">")
        sb.append("<input class=\"tr\" type=\"radio\" name=\"").append(modeGroup)
            .append("\" id=\"").append(mode1Id).append("\" checked>")
        sb.append("<label class=\"tl gk-mode-tab\" for=\"").append(mode1Id).append("\">站位与攻击顺序</label>")
        sb.append("<div class=\"tp gk-mode-tp\">")
        appendMode1RoundView(sb, battleId, teamIdx, lineupType, team, priorRounds)
        sb.append("</div>")

        sb.append("<input class=\"tr\" type=\"radio\" name=\"").append(modeGroup)
            .append("\" id=\"").append(mode2Id).append("\">")
        sb.append("<label class=\"tl gk-mode-tab\" for=\"").append(mode2Id).append("\">服装出手顺序</label>")
        sb.append("<div class=\"tp gk-mode-tp\">")
        appendMode2MatrixView(sb, lineupType, team, priorRounds)
        sb.append("</div>")

        if (panels.isNotEmpty()) {
            sb.append("<input class=\"tr\" type=\"radio\" name=\"").append(modeGroup)
                .append("\" id=\"").append(mode3Id).append("\">")
            sb.append("<label class=\"tl gk-mode-tab\" for=\"").append(mode3Id)
                .append("\">角色练度（").append(panels.size).append("）</label>")
            sb.append("<div class=\"tp gk-mode-tp\">")
            appendCharacterPanels(sb, battleId * 10 + teamIdx, panels, rawTeams)
            sb.append("</div>")
        }
        sb.append("</div>")
    }

    private fun appendMode1RoundView(
        sb: StringBuilder,
        battleId: Long,
        teamIdx: Int,
        lineupType: Int,
        team: RestoredTeam,
        priorRounds: Int
    ) {
        val rounds = team.rounds
        if (rounds.isEmpty()) {
            sb.append("<p class=\"b-v2-note\">暂无回合详情</p>")
            return
        }
        val roundGroup = "b3r_${battleId}_$teamIdx"
        sb.append("<div class=\"tabs gk-round-tabs\">")
        rounds.forEachIndexed { rIdx, round ->
            val rid = "${roundGroup}_$rIdx"
            val localTurn = rIdx * 2 + 1
            val globalTurn = (priorRounds + rIdx) * 2 + 1
            val spRem = round.spRemaining
            val badges = computeRoundChangeBadges(rounds, rIdx)

            sb.append("<input class=\"tr\" type=\"radio\" name=\"").append(roundGroup)
                .append("\" id=\"").append(rid).append('"')
            if (rIdx == 0) sb.append(" checked")
            sb.append("><label class=\"tl gk-round-tab\" for=\"").append(rid).append("\">")
            sb.append("<span class=\"gk-round-main\">第 ").append(localTurn).append(" 回合")
            if (globalTurn != localTurn) {
                sb.append(" · ").append(globalTurn).append("T")
            }
            sb.append("</span>")
            sb.append("<span class=\"gk-round-meta\">")
            if (spRem != null) {
                sb.append("<span>（余 ").append(spRem).append(" SP）</span>")
            }
            badges.forEach { b ->
                sb.append("<span class=\"gk-round-badge\">").append(esc(b)).append("</span>")
            }
            sb.append("</span>")
            sb.append("</label><div class=\"tp gk-round-tp\">")
            appendBattleBoxType1(sb, lineupType, round)
            sb.append("</div>")
        }
        sb.append("</div>")
    }

    private fun computeRoundChangeBadges(rounds: List<RestoredRound>, rIdx: Int): List<String> {
        val curr = rounds.getOrNull(rIdx)?.members.orEmpty()
        val badges = mutableListOf<String>()
        if (rIdx == 0) {
            if (curr.any { it.firstRoundEnabled }) badges.add("先发制人")
            if (curr.any { it.type == "pet" }) badges.add("召唤物")
        } else {
            val prev = rounds.getOrNull(rIdx - 1)?.members.orEmpty()
            val prevPos = prev.associate { it.uniqueKey to it.memberPosition }
            val prevIdx = prev.associate { it.uniqueKey to it.memberIndex }
            val prevPets = prev.filter { it.type == "pet" }.map { it.uniqueKey }.toSet()

            if (curr.any { it.uniqueKey in prevPos && prevPos[it.uniqueKey] != it.memberPosition }) {
                badges.add("调站位")
            }
            if (curr.any { it.uniqueKey in prevIdx && prevIdx[it.uniqueKey] != it.memberIndex }) {
                badges.add("调顺序")
            }
            if (curr.any { it.type == "pet" && it.uniqueKey !in prevPets }) {
                badges.add("召唤物")
            }
        }
        return badges
    }

    /**
     * 手机竖屏黄金上下布局：
     * 1. 上方：大尺寸居中 3×4（或 5×5）站位棋盘（格内直接标明本回合行动顺位 1~5、突破等级、攻击范围缩略图与 SP 星标）；
     * 2. 下方：按本回合出手顺位 1 → 5 排列的清晰横向行动流卡片（含头像、服装名、动作/爆发、攻击范围图与可展开技能说明）。
     */
    private fun appendBattleBoxType1(
        sb: StringBuilder,
        lineupType: Int,
        round: RestoredRound
    ) {
        val members = round.members
        if (members.isEmpty()) {
            sb.append("<p class=\"b-v2-note\">本回合无角色行动数据</p>")
            return
        }
        val sortedByAction = members.sortedBy { it.memberIndex }
        val actionOrderByKey = HashMap<String, Int>()
        sortedByAction.forEachIndexed { idx, m -> actionOrderByKey[m.uniqueKey] = idx + 1 }

        val isGuild = lineupType == 3
        val totalSlots = if (isGuild) 25 else 12
        val byPos = members.associateBy { it.memberPosition }

        sb.append("<div class=\"view-battle-box").append(if (isGuild) " is-btype-3" else "").append("\">")
        sb.append("<div class=\"gk-board-head\"><span>己方站位（面向右侧 · 右上角数字为本回合出手顺位）</span>")
        if (round.spRemaining != null) {
            sb.append("<span class=\"sp-info\">剩余 <span class=\"sp-num\">")
                .append(round.spRemaining).append("</span> SP</span>")
        }
        sb.append("</div>")

        // 1. 居中战术棋盘 (.grid.type1)
        sb.append("<div class=\"right-wrap\"><div class=\"grid type1\">")
        for (slot in 0 until totalSlots) {
            val m = byPos[slot]
            sb.append("<div class=\"grid-item\" data-slot=\"").append(slot + 1).append("\">")
            if (m != null) {
                val bgClass = if (m.actionType == "not") "empty-not" else GamekeeBattleAssets.attrBgClass(m.attr)
                val strenthIcon = GamekeeBattleAssets.strenthBadge(m.currentLevel)
                val burstIcon = GamekeeBattleAssets.burstBadge(m.chooseBurst)
                val burstCount = if (m.chooseBurst in 1..3) m.chooseBurst ?: 0 else 0
                val orderNum = actionOrderByKey[m.uniqueKey] ?: (m.memberIndex + 1)

                sb.append("<div class=\"grid-role-cell-wrap ").append(bgClass).append("\">")
                if (m.coverUrl != null) {
                    sb.append("<img class=\"cover\" src=\"").append(esc(m.coverUrl))
                        .append("\" alt=\"").append(esc(m.charName)).append("\" loading=\"lazy\">")
                }
                // 左上角：突破等级 (+0 ~ +5)
                if (m.type == "role" && strenthIcon.isNotEmpty()) {
                    sb.append("<div class=\"strenth-icon\"><img src=\"").append(strenthIcon).append("\" alt=\"\"></div>")
                }
                // 右上角：醒目的本回合出手顺位徽章 (1 ~ 5)
                sb.append("<div class=\"gk-board-order\">").append(orderNum).append("</div>")
                // 右侧：爆裂图标
                if (burstIcon.isNotEmpty()) {
                    sb.append("<div class=\"burst-icon\"><img src=\"").append(burstIcon).append("\" alt=\"\"></div>")
                }
                // 右下角：九宫格攻击范围缩略图
                if (!m.rangeIconUrl.isNullOrBlank()) {
                    sb.append("<div class=\"skill-position\"><img src=\"").append(esc(m.rangeIconUrl))
                        .append("\" alt=\"\" loading=\"lazy\"></div>")
                }
                // 底部：角色名 + SP 消耗菱形星标
                sb.append("<div class=\"sp-stars\">")
                sb.append("<span class=\"gk-board-name\">").append(esc(m.charName)).append("</span>")
                sb.append("<div class=\"gk-stars-row\">")
                if (m.actionType != "skills") {
                    sb.append("<div class=\"star default-star\"></div>")
                } else {
                    repeat(m.spCost.coerceIn(0, 10)) {
                        sb.append("<div class=\"star\"></div>")
                    }
                }
                repeat(burstCount) {
                    sb.append("<div class=\"star burst-star\"></div>")
                }
                sb.append("</div></div></div>")
            } else {
                sb.append("<div class=\"empty\" aria-label=\"空位\"><span class=\"gk-slot-num\">")
                    .append(slot + 1).append("</span></div>")
            }
            sb.append("</div>")
        }
        sb.append("</div>")

        if (!round.bossAction.isNullOrBlank()) {
            val isStandby = round.bossAction == "待命"
            sb.append("<div class=\"boss-action-info\"><img class=\"boss-icon\" src=\"")
                .append(if (isStandby) GamekeeBattleAssets.LANCELOT_STANDBY else GamekeeBattleAssets.LANCELOT_ACTIVE)
                .append("\" alt=\"\"><div class=\"action-content\"><div class=\"infos\"><div class=\"name\">")
                .append(if (lineupType == 3) "兰斯洛特行动 - " else "BOSS行动 - ")
                .append(esc(round.bossAction)).append("</div></div></div></div>")
        }
        sb.append("</div></div>")

        // 2. 下方：按出手顺位 1 -> 5 排列的清晰行动流卡片列表
        sb.append("<div class=\"gk-round-actions-title\">本回合行动顺序（1 → ").append(sortedByAction.size).append("）</div>")
        sb.append("<ol class=\"b-v2-actions gk-action-list\">")
        sortedByAction.forEachIndexed { idx, m ->
            val actionLabel = when (m.actionType) {
                "skills" -> if (m.chooseBurst != null && m.chooseBurst > 0) "释放技能（爆发 ${m.chooseBurst}）" else "释放技能"
                "damage" -> "普通攻击"
                "repel" -> "击退"
                "dead" -> "待命 / 无法战斗"
                else -> m.actionType.ifBlank { "行动" }
            }
            val actionBadgeClass = when (m.actionType) {
                "skills" -> if (m.chooseBurst != null && m.chooseBurst > 0) "is-burst" else "is-skill"
                "repel" -> "is-repel"
                else -> "is-normal"
            }
            val href = if (m.contentId.all { it.isDigit() } && m.contentId.isNotBlank()) {
                "https://www.gamekee.com/zsca2/tj/${m.contentId}.html?style=${m.styleIdx}"
            } else null
            val bgClass = GamekeeBattleAssets.attrBgClass(m.attr)

            sb.append("<li class=\"gk-action-card\"><div class=\"b-v2-action\">")
            sb.append("<div class=\"gk-act-order\">").append(idx + 1).append("</div>")
            if (m.coverUrl != null) {
                sb.append("<img class=\"b-v2-avatar ").append(bgClass).append("\" src=\"")
                    .append(esc(m.coverUrl)).append("\" alt=\"\" loading=\"lazy\">")
            } else {
                sb.append("<span class=\"b-v2-avatar b-v2-no-avatar\">?</span>")
            }
            sb.append("<div class=\"b-v2-action-info\">")
            sb.append("<div class=\"gk-act-top\">")
            if (href != null) {
                sb.append("<a class=\"gk-act-name\" href=\"").append(esc(href)).append("\"><strong>")
                    .append(esc(m.charName.ifBlank { "角色 #${m.contentId}" })).append("</strong></a>")
            } else {
                sb.append("<strong class=\"gk-act-name\">").append(esc(m.charName.ifBlank { "角色 #${m.contentId}" })).append("</strong>")
            }
            if (m.skinName.isNotBlank()) {
                sb.append("<span class=\"b-v2-skin\"> · ").append(esc(m.skinName)).append("</span>")
            }
            sb.append("</div>")

            sb.append("<div class=\"gk-act-badges\">")
            sb.append("<span class=\"gk-act-tag ").append(actionBadgeClass).append("\">")
                .append(esc(actionLabel)).append("</span>")
            if (m.firstRoundEnabled) {
                sb.append("<span class=\"b-v3-tag\">先发制人</span>")
            }
            if (m.actionType == "skills" && m.spCost > 0) {
                sb.append("<span class=\"gk-act-meta\">SP ").append(m.spCost)
                if (m.cdTurns > 0) sb.append(" · CD ").append(m.cdTurns).append("T")
                sb.append("</span>")
            }
            if (m.currentLevel != null && m.currentLevel >= 0) {
                sb.append("<span class=\"gk-act-meta\">突破 +").append(m.currentLevel)
                if (m.currentPotential >= 0 && m.type == "role") {
                    sb.append(" · 技能潜能 ").append(m.currentPotential)
                }
                sb.append("</span>")
            }
            sb.append("</div>")

            if (m.actionType == "skills" && m.skillDesc.isNotBlank()) {
                sb.append("<details class=\"gk-tippy-fold\"><summary>查看技能说明 ▾</summary>")
                sb.append("<div class=\"gk-tippy-desc\">").append(esc(m.skillDesc).replace("\n", "<br>"))
                    .append("</div></details>")
            }
            sb.append("</div>")

            // 右侧直接展示攻击范围九宫格缩略图与圆形技能图标
            if (!m.rangeIconUrl.isNullOrBlank() || !m.skillIconUrl.isNullOrBlank()) {
                sb.append("<div class=\"gk-act-right\">")
                if (!m.rangeIconUrl.isNullOrBlank()) {
                    sb.append("<img class=\"gk-act-range\" src=\"").append(esc(m.rangeIconUrl))
                        .append("\" alt=\"攻击范围\" loading=\"lazy\">")
                }
                sb.append("</div>")
            }
            sb.append("</div></li>")
        }
        sb.append("</ol>")
    }

    /**
     * 模式 2：服装出手顺序（首列 Sticky 冻结角色 + 右侧横滑全回合矩阵）。
     */
    private fun appendMode2MatrixView(
        sb: StringBuilder,
        lineupType: Int,
        team: RestoredTeam,
        priorRounds: Int
    ) {
        val rounds = team.rounds
        if (rounds.isEmpty()) {
            sb.append("<p class=\"b-v2-note\">暂无回合详情</p>")
            return
        }
        val matrixMembers = LinkedHashMap<String, RestoredMember>()
        for (r in rounds) {
            for (m in r.members) {
                if (m.uniqueKey.isNotBlank() && m.uniqueKey !in matrixMembers) {
                    matrixMembers[m.uniqueKey] = m
                }
            }
        }
        val memberList = matrixMembers.values.toList()

        sb.append("<div class=\"gk-matrix-table-wrap\"><div class=\"grid type2 gk-matrix-table\">")
        // 表头行
        sb.append("<div class=\"gk-matrix-tr gk-matrix-thead\">")
        sb.append("<div class=\"gk-matrix-sticky-col gk-matrix-th\">角色 \\ 回合</div>")
        rounds.forEachIndexed { rIdx, r ->
            val turnNum = (priorRounds + rIdx) * 2 + 1
            sb.append("<div class=\"gk-matrix-td gk-matrix-th\"><div>第").append(turnNum).append("回合</div>")
            if (r.spRemaining != null) {
                sb.append("<div class=\"gk-matrix-sp\">余").append(r.spRemaining).append("SP</div>")
            }
            sb.append("</div>")
        }
        sb.append("</div>")

        // 每个角色一行
        for (m in memberList) {
            val roleBg = GamekeeBattleAssets.attrBgClass(m.attr)
            sb.append("<div class=\"gk-matrix-tr matrix-row\">")
            sb.append("<div class=\"gk-matrix-sticky-col\">")
            if (m.coverUrl != null) {
                sb.append("<img class=\"gk-matrix-role-av ").append(roleBg).append("\" src=\"")
                    .append(esc(m.coverUrl)).append("\" alt=\"\" loading=\"lazy\">")
            }
            sb.append("<span class=\"gk-matrix-role-name\">").append(esc(m.charName)).append("</span></div>")

            for (r in rounds) {
                val rm = r.members.firstOrNull { it.uniqueKey == m.uniqueKey }
                sb.append("<div class=\"gk-matrix-td grid-item small\">")
                if (rm != null && rm.actionType != "not") {
                    if (rm.actionType == "skills") {
                        val bgClass = GamekeeBattleAssets.attrBgClass(rm.attr)
                        sb.append("<div class=\"gk-matrix-skill-cell ").append(bgClass).append("\">")
                        if (rm.coverUrl != null) {
                            sb.append("<img class=\"cover\" src=\"").append(esc(rm.coverUrl))
                                .append("\" alt=\"\" loading=\"lazy\">")
                        }
                        if (!rm.skillIconUrl.isNullOrBlank()) {
                            sb.append("<div class=\"skill\"><img src=\"").append(esc(rm.skillIconUrl))
                                .append("\" alt=\"\" loading=\"lazy\"></div>")
                        }
                        if (rm.spCost > 0) {
                            sb.append("<span class=\"gk-matrix-cost\">-").append(rm.spCost).append("SP</span>")
                        }
                        if (rm.skinName.isNotBlank()) {
                            sb.append("<span class=\"gk-matrix-sname\">").append(esc(rm.skinName)).append("</span>")
                        }
                        sb.append("</div>")
                    } else {
                        val actionText = when (rm.actionType) {
                            "damage" -> "普通攻击"
                            "repel" -> "击退"
                            else -> "待命"
                        }
                        sb.append("<div class=\"normal-card\">")
                        if (!rm.skillIconUrl.isNullOrBlank()) {
                            sb.append("<img class=\"icon\" src=\"").append(esc(rm.skillIconUrl))
                                .append("\" alt=\"\" loading=\"lazy\">")
                        }
                        sb.append("<span class=\"text\">").append(actionText).append("</span></div>")
                    }
                } else {
                    sb.append("<div class=\"empty\"></div>")
                }
                sb.append("</div>")
            }
            sb.append("</div>")
        }
        sb.append("</div></div>")
    }

    /**
     * 模式 3：角色练度与装备面板（现代游戏资料卡，告别简陋表格）。
     */
    private fun appendCharacterPanels(
        sb: StringBuilder,
        groupKey: Long,
        panels: Map<String, CharacterPanel>,
        teams: JsonArray
    ) {
        val orderedIds = LinkedHashSet<String>()
        teams.forEach { teamEl ->
            teamEl.arrOrNull()?.forEach { mEl ->
                val cid = mEl.objOrNull()?.str("name_code")?.substringBefore('-').orEmpty()
                if (cid in panels) orderedIds.add(cid)
            }
        }
        orderedIds.addAll(panels.keys)

        val charGroup = "b3p_$groupKey"
        sb.append("<div class=\"tabs gk-panel-tabs\">")
        orderedIds.forEachIndexed { idx, cid ->
            val p = panels[cid] ?: return@forEachIndexed
            val pid = "${charGroup}_$cid"
            sb.append("<input class=\"tr\" type=\"radio\" name=\"").append(charGroup)
                .append("\" id=\"").append(pid).append('"')
            if (idx == 0) sb.append(" checked")
            sb.append("><label class=\"tl gk-panel-tab\" for=\"").append(pid).append("\">")
                .append(esc(p.name.ifBlank { "#$cid" })).append("</label><div class=\"tp gk-panel-tp\">")

            val href = "https://www.gamekee.com/zsca2/tj/$cid.html"
            sb.append("<div class=\"gk-pcard-header\"><div><strong class=\"gk-pcard-name\">")
                .append(esc(p.name)).append("</strong><span class=\"gk-pcard-sub\">Lv.").append(p.level)
            if (p.awake) {
                sb.append(" · 已觉醒 · 刻印 ").append(p.imprint1).append("/")
                    .append(p.imprint2).append("/").append(p.imprint3)
            }
            sb.append("</span></div><a class=\"gk-pcard-link\" href=\"").append(esc(href))
                .append("\">查看角色图鉴 →</a></div>")

            // 8 项核心属性网格卡
            sb.append("<div class=\"gk-stats-grid\">")
            appendStatBox(sb, "生命力", fmtNum(p.health), highlight = false)
            val isMagic = p.magicDamage > p.physicalDamage
            appendStatBox(
                sb,
                if (isMagic) "魔法力" else "攻击力",
                fmtNum(if (isMagic) p.magicDamage else p.physicalDamage),
                highlight = true
            )
            appendStatBox(sb, "暴击率", fmtPct(p.criticalChance), highlight = false)
            appendStatBox(sb, "暴击伤害", fmtPct(p.criticalDamage), highlight = true)
            appendStatBox(sb, "属性伤害", fmtPct(p.elementDamage), highlight = true)
            appendStatBox(sb, "属性抵抗", fmtPct(p.elementResistance), highlight = false)
            appendStatBox(sb, "防御力", fmtPct(p.physicalDefense), highlight = false)
            appendStatBox(sb, "魔法抵抗", fmtPct(p.magicDefense), highlight = false)
            sb.append("</div>")

            if (p.costumesByOrder.isNotEmpty()) {
                sb.append("<div class=\"gk-sub-title\">持有服装</div><div class=\"gk-costume-strip\">")
                p.costumesByOrder.forEachIndexed { cIdx, c ->
                    val styleHref = "https://www.gamekee.com/zsca2/tj/$cid.html?style=${cIdx + 1}"
                    sb.append("<a class=\"gk-costume-chip\" href=\"").append(esc(styleHref)).append("\">")
                    if (c.icon != null) {
                        sb.append("<img src=\"").append(esc(c.icon)).append("\" alt=\"\" loading=\"lazy\">")
                    }
                    sb.append("<div class=\"gk-costume-meta\"><span class=\"b-v3-sname\">")
                        .append(esc(c.nameCn)).append("</span><span class=\"b-lv\">+")
                        .append(c.level).append(" · 潜").append(c.skillPotentialNum)
                        .append("</span></div></a>")
                }
                sb.append("</div>")
            }

            if (p.equipments.isNotEmpty()) {
                sb.append("<div class=\"gk-sub-title\">穿戴装备（").append(p.equipments.size).append(" 件）</div>")
                sb.append("<div class=\"gk-equip-list\">")
                for (eq in p.equipments) {
                    sb.append("<div class=\"gk-equip-card\"><div class=\"gk-equip-name\"><strong>")
                        .append(esc(eq.name)).append("</strong>")
                    if (eq.level > 0) sb.append(" <span class=\"gk-equip-lv\">+").append(eq.level).append("</span>")
                    sb.append("</div><div class=\"gk-equip-opts\">")
                    if (eq.privateOptions.isNotEmpty()) {
                        sb.append("<span class=\"gk-opt-chip is-priv\">专武：")
                            .append(esc(eq.privateOptions.joinToString("/") { formatEquipOpt(it) }))
                            .append("</span>")
                    }
                    if (eq.mainOptions.isNotEmpty()) {
                        sb.append("<span class=\"gk-opt-chip is-main\">主：")
                            .append(esc(eq.mainOptions.joinToString("/") { formatEquipOpt(it) }))
                            .append("</span>")
                    }
                    if (eq.subOptions.isNotEmpty()) {
                        sb.append("<span class=\"gk-opt-chip is-sub\">副：")
                            .append(esc(eq.subOptions.joinToString("/") { formatEquipOpt(it) }))
                            .append("</span>")
                    }
                    sb.append("</div></div>")
                }
                sb.append("</div>")
            }

            sb.append("</div>")
        }
        sb.append("</div>")
    }

    private fun appendStatBox(sb: StringBuilder, label: String, value: String, highlight: Boolean) {
        sb.append("<div class=\"gk-stat-box").append(if (highlight) " is-hl" else "").append("\">")
            .append("<span class=\"gk-stat-label\">").append(esc(label)).append("</span>")
            .append("<span class=\"gk-stat-val\">").append(esc(value)).append("</span></div>")
    }

    private data class ResolvedSkinMeta(
        val charName: String,
        val skinName: String,
        val attr: String,
        val damage: String,
        val starInt: Int,
        val icon: String?,
        val skillCastIcon: String?,
        val skillIcon: String?,
        val repelIcon: String?,
        val damageTypeIcon: String?,
        val rangeIcon: String?,
        val spCost: Int,
        val cdTurns: Int,
        val skillDesc: String,
        val level: Int?
    )

    private fun resolveSkinMeta(
        cid: String,
        nameCode: String,
        styleIdx: Int,
        currentLevel: Int? = null,
        currentPotential: Int? = null,
        panels: Map<String, CharacterPanel>,
        teamFallbackAvatar: Map<String, String>,
        charFallbackAttr: Map<String, String>,
        charFallbackDamage: Map<String, String>
    ): ResolvedSkinMeta {
        val exact = GamekeeBattleRoleCatalog.findSkinByNameCode(nameCode)
        val roleEntry = exact?.role ?: GamekeeBattleRoleCatalog.findRoleByContentId(cid.toLongOrNull())
        val skinEntry = exact?.skin

        val p = panels[cid]
        val panelCostume = if (skinEntry != null) {
            p?.costumesByOrder?.firstOrNull { it.nameCn == skinEntry.name }
        } else {
            p?.costumesByOrder?.getOrNull(styleIdx - 1) ?: p?.costumesByOrder?.lastOrNull()
        }

        val effectiveLevel = currentLevel ?: panelCostume?.level
        val strengthen = skinEntry?.strengthenForLevel(effectiveLevel)
        val fallbackIcon = teamFallbackAvatar[nameCode] ?: teamFallbackAvatar["$cid-$styleIdx"]

        return ResolvedSkinMeta(
            charName = roleEntry?.name?.ifBlank { null } ?: p?.name.orEmpty(),
            skinName = skinEntry?.name?.ifBlank { null } ?: panelCostume?.nameCn.orEmpty(),
            attr = roleEntry?.attr?.ifBlank { null } ?: charFallbackAttr[cid].orEmpty(),
            damage = roleEntry?.damage?.ifBlank { null } ?: charFallbackDamage[cid].orEmpty(),
            starInt = roleEntry?.starInt ?: 5,
            icon = skinEntry?.icon ?: panelCostume?.icon ?: fallbackIcon ?: roleEntry?.icon1,
            skillCastIcon = skinEntry?.skillCastIcon ?: skinEntry?.icon ?: panelCostume?.icon ?: fallbackIcon,
            skillIcon = skinEntry?.skillIcon,
            repelIcon = skinEntry?.repel,
            damageTypeIcon = skinEntry?.damageTypeIcon ?: roleEntry?.damageTypeIcon,
            rangeIcon = skinEntry?.rangeImageFor(effectiveLevel, currentPotential),
            spCost = skinEntry?.spFor(effectiveLevel, currentPotential) ?: 0,
            cdTurns = strengthen?.cd ?: 0,
            skillDesc = strengthen?.desc.orEmpty(),
            level = effectiveLevel
        )
    }

    private fun restoreTeams(
        rawContent: JsonElement?,
        panels: Map<String, CharacterPanel>,
        teamFallbackAvatar: Map<String, String>,
        charFallbackAttr: Map<String, String>,
        charFallbackDamage: Map<String, String>
    ): List<RestoredTeam> {
        val teamObjs = parseContentTeams(rawContent)
        return teamObjs.map { tObj ->
            val rounds = tObj.arr("memberRoundInfos")?.mapNotNull { rEl ->
                val rObj = rEl.objOrNull() ?: return@mapNotNull null
                val spRem = rObj.int("spRemaining") ?: rObj.int("sp_remaining")
                val bossAct = rObj.str("bossAction").ifBlank { rObj.str("boss_action") }.ifBlank { null }
                val members = rObj.arr("member")?.mapNotNull { mEl ->
                    val m = mEl.objOrNull() ?: return@mapNotNull null
                    val type = m.str("type").ifBlank { "role" }
                    val actionType = m.str("actionType").ifBlank { "skills" }
                    val memberIndex = m.int("memberIndex") ?: 0
                    val memberPosition = m.int("memberPosition") ?: -1
                    val firstRound = m.bool("firstRoundEnabled") == true
                    val chooseBurst = m.int("choose_burst")

                    if (type == "pet") {
                        val petId = m.str("id")
                        val petName = m.str("name")
                        val pet = GamekeeBattleRoleCatalog.findPet(petId, petName)
                        val icon = absolutize(m.str("icon")) ?: pet?.icon
                        val actIcon = when (actionType) {
                            "skills" -> pet?.skillImage
                            "damage" -> pet?.damageTypeIcon
                            "repel" -> pet?.repel
                            else -> null
                        }
                        RestoredMember(
                            uniqueKey = m.str("uniqueKey").ifBlank { petId.ifBlank { petName } },
                            type = "pet",
                            contentId = "",
                            nameCode = "",
                            styleIdx = 1,
                            charName = pet?.name?.ifBlank { null } ?: petName.ifBlank { "召唤物" },
                            skinName = pet?.skillName.orEmpty(),
                            attr = if ((pet?.name ?: petName).contains("魔法增幅器")) "风" else "水",
                            damage = "",
                            starInt = 5,
                            currentLevel = null,
                            currentPotential = 0,
                            actionType = actionType,
                            memberIndex = memberIndex,
                            memberPosition = memberPosition,
                            firstRoundEnabled = firstRound,
                            chooseBurst = null,
                            coverUrl = icon,
                            skillCastUrl = icon,
                            skillIconUrl = actIcon,
                            rangeIconUrl = actIcon,
                            spCost = pet?.sp ?: 0,
                            cdTurns = pet?.cd ?: 0,
                            skillDesc = pet?.skillDesc.orEmpty()
                        )
                    } else {
                        val nameCode = m.obj("currentSkin")?.str("name_code")?.ifBlank { null }
                            ?: m.str("name_code")
                        val cid = m.str("content_id").ifBlank { nameCode.substringBefore('-') }
                        val styleIdx = nameCode.substringAfter('-', "1").toIntOrNull() ?: 1
                        val lv = m.int("currentLevel")
                        val pot = m.int("currentPotential") ?: 0
                        val meta = resolveSkinMeta(
                            cid = cid,
                            nameCode = nameCode.ifBlank { "$cid-$styleIdx" },
                            styleIdx = styleIdx,
                            currentLevel = lv,
                            currentPotential = pot,
                            panels = panels,
                            teamFallbackAvatar = teamFallbackAvatar,
                            charFallbackAttr = charFallbackAttr,
                            charFallbackDamage = charFallbackDamage
                        )
                        val fallbackDmgIcon = GamekeeBattleAssets.damageIcon(meta.damage).ifBlank { null }
                        val skillIcon = when (actionType) {
                            "skills" -> meta.skillIcon
                            "damage" -> meta.damageTypeIcon ?: fallbackDmgIcon
                            "repel" -> meta.repelIcon
                            else -> null
                        }
                        val rangeIcon = when (actionType) {
                            "skills" -> meta.rangeIcon
                            "damage" -> meta.damageTypeIcon ?: fallbackDmgIcon
                            "repel" -> meta.repelIcon
                            else -> null
                        }
                        RestoredMember(
                            uniqueKey = m.str("uniqueKey").ifBlank { "$cid-$memberPosition" },
                            type = "role",
                            contentId = cid,
                            nameCode = nameCode,
                            styleIdx = styleIdx,
                            charName = meta.charName,
                            skinName = meta.skinName,
                            attr = meta.attr,
                            damage = meta.damage,
                            starInt = meta.starInt,
                            currentLevel = meta.level,
                            currentPotential = pot,
                            actionType = actionType,
                            memberIndex = memberIndex,
                            memberPosition = memberPosition,
                            firstRoundEnabled = firstRound,
                            chooseBurst = chooseBurst,
                            coverUrl = meta.icon,
                            skillCastUrl = if (actionType == "skills") meta.skillCastIcon else meta.icon,
                            skillIconUrl = skillIcon,
                            rangeIconUrl = rangeIcon,
                            spCost = if (actionType == "skills") meta.spCost else 0,
                            cdTurns = meta.cdTurns,
                            skillDesc = meta.skillDesc
                        )
                    }
                }.orEmpty()
                RestoredRound(
                    members = members,
                    spRemaining = spRem?.coerceIn(0, 999),
                    bossAction = bossAct
                )
            }.orEmpty()
            RestoredTeam(
                roundTotal = (tObj.int("roundTotal") ?: rounds.size).coerceAtLeast(1),
                rounds = rounds
            )
        }
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

    private fun formatStatisticNumber(raw: String?): String {
        val v = raw?.toDoubleOrNull() ?: return ""
        if (!v.isFinite() || v <= 0.0) return "0"
        return when {
            v >= 1e8 -> "${formatCompactUnit(v / 1e8)}亿"
            v >= 1e4 -> "${formatCompactUnit(v / 1e4)}万"
            else -> v.toLong().toString()
        }
    }

    private fun formatCompactUnit(v: Double): String {
        if (v >= 100.0) return Math.round(v).toString()
        return String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
    }

    private fun formatDate(epochSec: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(epochSec * 1000L))

    private fun fmtNum(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString()
        else String.format(Locale.US, "%.1f", v)

    private fun fmtPct(v: Double): String =
        String.format(Locale.US, "%.1f%%", v * 100.0).removeSuffix(".0%") +
            if (String.format(Locale.US, "%.1f%%", v * 100.0).endsWith(".0%")) "%" else ""

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
    private fun JsonObject.long(k: String): Long? = str(k).toLongOrNull()
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
