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
 * GameKee v3 阵容模拟器组件（`/v1/bd2Battle/detail`）的原版 1:1 渲染器。
 *
 * 严格对齐 GameKee 官方前端模块：
 * - `BattleEditorUserLineupCard` (Module `14705`, scope `48279985`)：外层阵容概览卡片
 * - `LineupDetail` (Module `49968`, scope `59c527eb`)：二级详情面板
 * - `TestViewTeamList` (Module `6511`, scope `7d2d3089`)：多队伍切换与首发成员栏
 * - `TestViewTypeSwitch` (Module `7617`, scope `5d8dc463`)：「站位与攻击顺序」/「服装出手顺序」视图切换
 * - `TestViewRound` (Module `10750`, scope `667ccff2`)：奇数回合轴与「先发制人 / 调站位 / 调顺序 / 召唤物」提示
 * - `TestViewBattleBox` (Module `40012`, scope `52de4d64`)：左行动列 + 右 3×4/5×5 站位棋盘（含攻击范围图、突破、潜能、爆裂、SP 星标）与全回合服装出手矩阵
 * - `RoleSkinDetail` / `BattleRoleTippy`：角色练度装备面板与服装技能详情
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

        // 解析角色练度面板字典 panel[contentId]
        val panels = parsePanels(data.obj("panel"))

        // 从 team 采集每个 name_code 的兜底头像、属性、伤害类型
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

        // 原版 restoreBattleTeamInfosFromContent：通过 bd2roles 字典 + panel 还原完整队伍与回合数据
        val restoredTeams = restoreTeams(
            rawContent = data["content"],
            panels = panels,
            teamFallbackAvatar = teamFallbackAvatar,
            charFallbackAttr = charFallbackAttr,
            charFallbackDamage = charFallbackDamage
        )

        val sb = StringBuilder()
        sb.append("<div class=\"gk-lineup-card user-lineup-card view battle battle-v3\">")

        // ---- 1. 原版顶栏 (.card-header) ----
        val (typeTagText, typeTagClass) = when (lineupType) {
            1 -> "通用阵容" to "is-main"
            2 -> "魔兽阵容" to "is-warcraft"
            3 -> "公会战阵容" to "is-guild"
            else -> "#阵容" to ""
        }
        sb.append("<div class=\"card-header\"><div class=\"line\"><div class=\"left\">")
        sb.append("<div class=\"type-tag ").append(typeTagClass).append("\">")
            .append(esc(typeTagText))
            .append(" <span class=\"lineup-id-tag\">#").append(battleId).append("</span></div>")
        sb.append("</div><span class=\"update-time\">")
        if (updatedAt > 0L) {
            sb.append(esc(formatDate(updatedAt))).append("更新")
        } else {
            if (likes > 0) sb.append("👍").append(likes).append(' ')
            if (views > 0) sb.append("👁").append(views)
        }
        sb.append("</span></div></div>")

        // ---- 2. 原版队伍头像网格 (.group-row > .avatar-list) ----
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
                val lvBadge = GamekeeBattleAssets.skillLvBadge(lv)
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
                sb.append("<div class=\"avatar-item\">")
                if (avatar != null) {
                    sb.append("<img class=\"avatar ").append(bgClass).append("\" src=\"")
                        .append(esc(avatar)).append("\" alt=\"").append(esc(resolved.charName))
                        .append("\" loading=\"lazy\">")
                } else {
                    sb.append("<div class=\"avatar ").append(bgClass).append(" b-av-e\">?</div>")
                }
                sb.append("<div class=\"tag-box\">")
                if (lvBadge.isNotEmpty()) {
                    sb.append("<img class=\"skill-icon\" src=\"").append(lvBadge).append("\" alt=\"+$lv\">")
                }
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

        // ---- 3. 原版标题栏 (.title) ----
        sb.append("<div class=\"title\"><span class=\"text b-title\">").append(esc(title)).append("</span>")
        if (restoredTeams.isNotEmpty() || panels.isNotEmpty()) {
            sb.append("<span class=\"right-btn\" onclick=\"var d=this.closest('.gk-lineup-card').querySelector('.b-v3-detail');if(d)d.open=!d.open;\">")
                .append("<span>攻击顺序</span> ›</span>")
        }
        sb.append("</div>")

        // ---- 4. 原版描述文本 (.desc) ----
        if (desc.isNotBlank()) {
            sb.append("<div class=\"desc b-desc\">").append(esc(desc).replace("\n", "<br>")).append("</div>")
        }

        // ---- 5. 原版 Boss 与排轴信息栏 (.boss-row) ----
        sb.append("<div class=\"boss-row\">")
        if ((lineupType == 2 || lineupType == 3) && bossName.isNotBlank()) {
            sb.append("<div class=\"card-item\">")
            if (bossIcon != null) {
                sb.append("<div class=\"boss-name-icon\"><img src=\"").append(esc(bossIcon))
                    .append("\" alt=\"\" loading=\"lazy\"></div>")
            }
            sb.append("<div class=\"boss-name\">").append(esc(bossName))
            if (bossAttr.isNotBlank()) sb.append("（").append(esc(bossAttr)).append("）")
            sb.append("</div></div>")
        } else if (lineupType == 1) {
            sb.append("<div class=\"card-item\"><div class=\"boss-name-icon icon\"><img src=\"")
                .append(GamekeeBattleAssets.BOSS_GENERAL)
                .append("\" alt=\"\"></div><div class=\"boss-name\">通用模式</div></div>")
        }
        if (maxDamageText.isNotBlank() && maxDamageText != "0") {
            sb.append("<div class=\"card-item\"><div class=\"boss-name-icon icon\"><img src=\"")
                .append(GamekeeBattleAssets.BOSS_MAX_DMG)
                .append("\" alt=\"\"></div><div class=\"boss-name\">最高伤害 ").append(esc(maxDamageText))
                .append("</div></div>")
        }
        sb.append("<div class=\"card-item\"><div class=\"boss-name-icon icon\"><img src=\"")
            .append(if (isRow) GamekeeBattleAssets.BOSS_ARRANGED else GamekeeBattleAssets.BOSS_UNARRANGED)
            .append("\" alt=\"\"></div><div class=\"boss-name\">")
            .append(if (isRow) "已排轴" else "未排轴")
            .append("</div></div>")
        sb.append("</div>")

        // ---- 6. 原版底栏 (.bottom-line) ----
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

        // ---- 7. 原版二级详情面板 (LineupDetail：ViewTeamList + ViewTypeSwitch + ViewRound + ViewBattleBox + RoleSkinDetail) ----
        if (restoredTeams.isNotEmpty() || panels.isNotEmpty()) {
            val totalRounds = restoredTeams.sumOf { it.rounds.size }
            sb.append("<details class=\"b-v3-detail\"><summary class=\"b-v3-summary\">")
            sb.append("⚔️ 展开阵容详情与回合轴（")
            if (restoredTeams.size > 1) sb.append(restoredTeams.size).append(" 支队伍 · ")
            if (totalRounds > 0) sb.append("共 ").append(totalRounds).append(" 个我方回合")
            if (panels.isNotEmpty()) sb.append(" · 含 ").append(panels.size).append(" 名角色练度与装备")
            sb.append("）</summary>")

            sb.append("<div class=\"gk-detail-wrap\">")
            if (restoredTeams.isNotEmpty()) {
                appendLineupDetailTeams(sb, battleId, lineupType, restoredTeams, panels, teams)
            } else if (panels.isNotEmpty()) {
                appendCharacterPanels(sb, battleId, panels, teams)
            }
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
        // 1. 顶部首发成员行 (TestViewTeamList .member-row)
        val firstRoundRoleMembers = team.rounds.firstOrNull()?.members
            ?.filter { it.type != "pet" }
            ?.take(if (lineupType == 3) 7 else 5)
            .orEmpty()
        if (firstRoundRoleMembers.isNotEmpty()) {
            sb.append("<div class=\"gk-member-row\">")
            for (m in firstRoundRoleMembers) {
                val bgClass = GamekeeBattleAssets.attrBgClass(m.attr)
                val lvBadge = GamekeeBattleAssets.skillLvBadge(m.currentLevel)
                val attrBadge = GamekeeBattleAssets.attrIcon(m.attr)
                val dmgBadge = GamekeeBattleAssets.damageIcon(m.damage)
                val href = if (m.contentId.all { it.isDigit() } && m.contentId.isNotBlank()) {
                    "https://www.gamekee.com/zsca2/tj/${m.contentId}.html?style=${m.styleIdx}"
                } else null
                if (href != null) {
                    sb.append("<a class=\"gk-member-item\" href=\"").append(esc(href)).append("\">")
                } else {
                    sb.append("<div class=\"gk-member-item\">")
                }
                if (m.coverUrl != null) {
                    sb.append("<img class=\"cover ").append(bgClass).append("\" src=\"")
                        .append(esc(m.coverUrl)).append("\" alt=\"\" loading=\"lazy\">")
                } else {
                    sb.append("<div class=\"cover ").append(bgClass).append(" b-av-e\">?</div>")
                }
                sb.append("<div class=\"tag-box\">")
                if (lvBadge.isNotEmpty()) {
                    sb.append("<img class=\"skill-icon\" src=\"").append(lvBadge).append("\" alt=\"\">")
                }
                if (attrBadge.isNotEmpty()) {
                    sb.append("<div class=\"role-attr-icon-box\"><img src=\"").append(attrBadge).append("\" alt=\"\"></div>")
                }
                if (dmgBadge.isNotEmpty()) {
                    sb.append("<div class=\"damage-type-icon-box\"><img class=\"filter\" src=\"").append(dmgBadge).append("\" alt=\"\"></div>")
                }
                sb.append("</div>")
                if (href != null) sb.append("</a>") else sb.append("</div>")
            }
            sb.append("</div>")
        }

        // 2. 原版双视图切换栏 (TestViewTypeSwitch：站位与攻击顺序 | 服装出手顺序 | 角色属性与装备)
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
                .append("\">角色属性与装备（").append(panels.size).append("）</label>")
            sb.append("<div class=\"tp gk-mode-tp\">")
            appendCharacterPanels(sb, battleId * 10 + teamIdx, panels, rawTeams)
            sb.append("</div>")
        }
        sb.append("</div>")
    }

    /**
     * 原版模式 1：站位与攻击顺序（TestViewRound + TestViewBattleBox battleType=1）。
     */
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
            // 同时计算小队内奇数回合 (1, 3, 5...) 与跨队累计回合，保证文案清晰且兼容单测
            val localTurn = rIdx * 2 + 1
            val globalTurn = (priorRounds + rIdx) * 2 + 1
            val spRem = round.spRemaining
            val badges = computeRoundChangeBadges(rounds, rIdx)

            sb.append("<input class=\"tr\" type=\"radio\" name=\"").append(roundGroup)
                .append("\" id=\"").append(rid).append('"')
            if (rIdx == 0) sb.append(" checked")
            sb.append("><label class=\"tl gk-round-tab\" for=\"").append(rid).append("\">")
            sb.append("<span class=\"gk-round-label\">第 ").append(localTurn).append(" 回合")
            if (globalTurn != localTurn) {
                sb.append(" (总").append(globalTurn).append("T)")
            }
            if (spRem != null) {
                sb.append("（余 ").append(spRem).append(" SP）")
            }
            sb.append("</span>")
            if (badges.isNotEmpty()) {
                sb.append("<span class=\"gk-round-sub\">")
                badges.forEach { b -> sb.append("<span>").append(esc(b)).append("</span>") }
                sb.append("</span>")
            }
            sb.append("</label><div class=\"tp gk-round-tp\">")
            appendBattleBoxType1(sb, lineupType, round)
            sb.append("</div>")
        }
        sb.append("</div>")
    }

    /**
     * 原版 TestViewRound 的回合变化检测逻辑（hasFirstRoundSwitch / hasChangedPosition / hasChangedIndex / hasChangedPet）。
     */
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
     * 原版 TestViewBattleBox (battleType = 1)：
     * 左列行动条 (.left-list) + 右侧 3×4 / 5×5 站位棋盘 (.grid.type1) + 底部剩余 SP 与本回合技能详情。
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
        val isGuild = lineupType == 3
        val totalSlots = if (isGuild) 25 else 12
        val byPos = members.associateBy { it.memberPosition }

        sb.append("<div class=\"view-battle-box").append(if (isGuild) " is-btype-3" else "").append("\">")

        // 1. 左列：按出手顺序排列的行动条 (.left-list)
        sb.append("<div class=\"left-list\">")
        sortedByAction.forEachIndexed { idx, m ->
            val bgClass = if (m.actionType == "not") "empty-not" else GamekeeBattleAssets.attrBgClass(m.attr)
            val skillCover = m.skillCastUrl ?: m.coverUrl
            val burstIcon = GamekeeBattleAssets.burstBadge(m.chooseBurst)
            sb.append("<div class=\"left-item\">")
            sb.append("<div class=\"order\">").append(idx + 1).append("</div>")
            if (skillCover != null) {
                sb.append("<img class=\"cover ").append(bgClass).append("\" src=\"")
                    .append(esc(skillCover)).append("\" alt=\"\" loading=\"lazy\">")
            } else {
                sb.append("<div class=\"cover ").append(bgClass).append("\"></div>")
            }
            if (!m.skillIconUrl.isNullOrBlank()) {
                sb.append("<div class=\"skill\"><img src=\"").append(esc(m.skillIconUrl))
                    .append("\" alt=\"\" loading=\"lazy\"></div>")
            }
            if (burstIcon.isNotEmpty()) {
                sb.append("<div class=\"burst-icon\"><img src=\"").append(burstIcon).append("\" alt=\"\"></div>")
            }
            sb.append("</div>")
        }
        sb.append("</div>")

        // 2. 右侧：3×4 (12格) 或 5×5 (25格) 站位棋盘 (.right-wrap > .grid.type1)
        sb.append("<div class=\"right-wrap\"><div class=\"grid type1\">")
        for (slot in 0 until totalSlots) {
            val m = byPos[slot]
            sb.append("<div class=\"grid-item\" data-slot=\"").append(slot + 1).append("\">")
            if (m != null) {
                val bgClass = if (m.actionType == "not") "empty-not" else GamekeeBattleAssets.attrBgClass(m.attr)
                val strenthIcon = GamekeeBattleAssets.strenthBadge(m.currentLevel)
                val cornerIcon = GamekeeBattleAssets.potentialCornerBadge(m.starInt, m.currentPotential)
                val burstIcon = GamekeeBattleAssets.burstBadge(m.chooseBurst)
                val burstCount = if (m.chooseBurst in 1..3) m.chooseBurst ?: 0 else 0

                sb.append("<div class=\"grid-role-cell-wrap\">")
                if (m.coverUrl != null) {
                    sb.append("<img class=\"cover ").append(bgClass).append("\" src=\"")
                        .append(esc(m.coverUrl)).append("\" alt=\"").append(esc(m.charName))
                        .append("\" loading=\"lazy\">")
                } else {
                    sb.append("<div class=\"cover ").append(bgClass).append("\"></div>")
                }
                if (!m.rangeIconUrl.isNullOrBlank()) {
                    sb.append("<div class=\"skill-position\"><img src=\"").append(esc(m.rangeIconUrl))
                        .append("\" alt=\"\" loading=\"lazy\"></div>")
                }
                if (m.type == "role") {
                    if (strenthIcon.isNotEmpty()) {
                        sb.append("<div class=\"strenth-icon\"><img src=\"").append(strenthIcon).append("\" alt=\"\"></div>")
                    }
                    if (cornerIcon.isNotEmpty()) {
                        sb.append("<div class=\"corner-icon\"><img src=\"").append(cornerIcon).append("\" alt=\"\"></div>")
                    }
                    if (burstIcon.isNotEmpty()) {
                        sb.append("<div class=\"burst-icon\"><img src=\"").append(burstIcon).append("\" alt=\"\"></div>")
                    }
                }
                sb.append("<div class=\"sp-stars\">")
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
                sb.append("</div></div>")
            } else {
                sb.append("<div class=\"empty\" aria-label=\"空位\"><span class=\"gk-slot-num\">")
                    .append(slot + 1).append("</span></div>")
            }
            sb.append("</div>")
        }
        sb.append("</div>")

        // 公会战兰斯洛特行动条或 Boss 行动条
        if (!round.bossAction.isNullOrBlank()) {
            val isStandby = round.bossAction == "待命"
            sb.append("<div class=\"boss-action-info\"><img class=\"boss-icon\" src=\"")
                .append(if (isStandby) GamekeeBattleAssets.LANCELOT_STANDBY else GamekeeBattleAssets.LANCELOT_ACTIVE)
                .append("\" alt=\"\"><div class=\"action-content\"><div class=\"infos\"><div class=\"name\">")
                .append(if (lineupType == 3) "兰斯洛特行动 - " else "BOSS行动 - ")
                .append(esc(round.bossAction)).append("</div></div></div></div>")
        }

        // 右下角本回合剩余 SP
        if (round.spRemaining != null) {
            sb.append("<div class=\"sp-info\">本回合剩余 <span class=\"sp-num\">")
                .append(round.spRemaining).append("</span> SP</div>")
        }
        sb.append("</div></div>")

        // 3. 本回合出手动作与服装技能明细（含攻击范围预览、SP/CD 与技能效果说明，对应原版 BattleRoleTippy）
        sb.append("<div class=\"gk-round-actions-title\">本回合出手顺序与技能详情</div>")
        sb.append("<ol class=\"b-v2-actions gk-action-list\">")
        for (m in sortedByAction) {
            val actionLabel = when (m.actionType) {
                "skills" -> if (m.chooseBurst != null && m.chooseBurst > 0) "释放技能（爆发 ${m.chooseBurst}）" else "释放技能"
                "damage" -> "普通攻击"
                "repel" -> "击退"
                "dead" -> "待命 / 无法战斗"
                else -> m.actionType.ifBlank { "行动" }
            }
            val href = if (m.contentId.all { it.isDigit() } && m.contentId.isNotBlank()) {
                "https://www.gamekee.com/zsca2/tj/${m.contentId}.html?style=${m.styleIdx}"
            } else null

            sb.append("<li><div class=\"b-v2-action\">")
            if (m.coverUrl != null) {
                val bgClass = GamekeeBattleAssets.attrBgClass(m.attr)
                sb.append("<img class=\"b-v2-avatar ").append(bgClass).append("\" src=\"")
                    .append(esc(m.coverUrl)).append("\" alt=\"\" loading=\"lazy\">")
            } else {
                sb.append("<span class=\"b-v2-avatar b-v2-no-avatar\">?</span>")
            }
            sb.append("<div class=\"b-v2-action-info\">")
            if (href != null) {
                sb.append("<a href=\"").append(esc(href)).append("\"><strong>")
                    .append(esc(m.charName.ifBlank { "角色 #${m.contentId}" })).append("</strong></a>")
            } else {
                sb.append("<strong>").append(esc(m.charName.ifBlank { "角色 #${m.contentId}" })).append("</strong>")
            }
            if (m.skinName.isNotBlank()) {
                sb.append("<span class=\"b-v2-skin\"> · ").append(esc(m.skinName)).append("</span>")
            }
            sb.append("<div>").append(esc(actionLabel))
            if (m.firstRoundEnabled) sb.append(" · <span class=\"b-v3-tag\">先发制人</span>")
            if (m.actionType == "skills" && m.spCost > 0) {
                sb.append(" · <span class=\"b-v2-note\">SP ").append(m.spCost)
                if (m.cdTurns > 0) sb.append(" / CD ").append(m.cdTurns).append("T")
                sb.append("</span>")
            }
            sb.append("</div>")
            val metaParts = mutableListOf<String>()
            if (m.currentLevel != null && m.currentLevel >= 0) metaParts.add("突破 +${m.currentLevel}")
            if (m.currentPotential >= 0 && m.type == "role") metaParts.add("技能潜能 ${m.currentPotential}")
            if (metaParts.isNotEmpty()) {
                sb.append("<div class=\"b-v2-note\">").append(esc(metaParts.joinToString(" · "))).append("</div>")
            }
            if (m.actionType == "skills" && m.skillDesc.isNotBlank()) {
                sb.append("<details class=\"gk-tippy-fold\"><summary>查看技能范围与效果说明</summary>")
                sb.append("<div class=\"gk-tippy-body\">")
                if (!m.rangeIconUrl.isNullOrBlank()) {
                    sb.append("<img class=\"gk-tippy-range\" src=\"").append(esc(m.rangeIconUrl))
                        .append("\" alt=\"技能范围\" loading=\"lazy\">")
                }
                sb.append("<div class=\"gk-tippy-desc\">").append(esc(m.skillDesc).replace("\n", "<br>"))
                    .append("</div></div></details>")
            }
            sb.append("</div></div></li>")
        }
        sb.append("</ol>")
    }

    /**
     * 原版模式 2：服装出手顺序（TestViewBattleBox battleType=2 全回合技能轴矩阵）。
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
        // 收集该队伍所有参战角色（保持首次出场顺序）
        val matrixMembers = LinkedHashMap<String, RestoredMember>()
        for (r in rounds) {
            for (m in r.members) {
                if (m.uniqueKey.isNotBlank() && m.uniqueKey !in matrixMembers) {
                    matrixMembers[m.uniqueKey] = m
                }
            }
        }
        val memberList = matrixMembers.values.toList()

        sb.append("<div class=\"gk-matrix-scroll\"><div class=\"gk-matrix-header\">")
        sb.append("<div class=\"gk-matrix-corner\">角色 \\ 回合</div>")
        rounds.forEachIndexed { rIdx, _ ->
            val turnNum = (priorRounds + rIdx) * 2 + 1
            sb.append("<div class=\"gk-matrix-round-col\">第").append(turnNum).append("回合</div>")
        }
        sb.append("</div>")

        sb.append("<div class=\"view-battle-box").append(if (lineupType == 3) " is-btype-3" else "").append("\">")
        // 左列固定角色头像
        sb.append("<div class=\"left-list\">")
        for (m in memberList) {
            val bgClass = GamekeeBattleAssets.attrBgClass(m.attr)
            sb.append("<div class=\"left-item\">")
            if (m.coverUrl != null) {
                sb.append("<img class=\"cover ").append(bgClass).append("\" src=\"")
                    .append(esc(m.coverUrl)).append("\" alt=\"\" loading=\"lazy\">")
            } else {
                sb.append("<div class=\"cover ").append(bgClass).append("\"></div>")
            }
            sb.append("</div>")
        }
        sb.append("</div>")

        // 右侧 N 回合技能矩阵 (.grid.type2)
        sb.append("<div class=\"right-wrap\"><div class=\"grid type2\">")
        for (m in memberList) {
            sb.append("<div class=\"matrix-row\">")
            for (r in rounds) {
                val rm = r.members.firstOrNull { it.uniqueKey == m.uniqueKey }
                sb.append("<div class=\"grid-item small\">")
                if (rm != null && rm.actionType != "not") {
                    if (rm.actionType == "skills") {
                        val bgClass = GamekeeBattleAssets.attrBgClass(rm.attr)
                        val strenthIcon = GamekeeBattleAssets.strenthBadge(rm.currentLevel)
                        val cornerIcon = GamekeeBattleAssets.potentialCornerBadge(rm.starInt, rm.currentPotential)
                        val burstIcon = GamekeeBattleAssets.burstBadge(rm.chooseBurst)
                        val burstCount = if (rm.chooseBurst in 1..3) rm.chooseBurst ?: 0 else 0
                        if (rm.coverUrl != null) {
                            sb.append("<img class=\"cover ").append(bgClass).append("\" src=\"")
                                .append(esc(rm.coverUrl)).append("\" alt=\"\" loading=\"lazy\">")
                        }
                        if (!rm.skillIconUrl.isNullOrBlank()) {
                            sb.append("<div class=\"skill\"><img src=\"").append(esc(rm.skillIconUrl))
                                .append("\" alt=\"\" loading=\"lazy\"></div>")
                        }
                        if (rm.type == "role") {
                            if (strenthIcon.isNotEmpty()) {
                                sb.append("<div class=\"strenth-icon\"><img src=\"").append(strenthIcon).append("\" alt=\"\"></div>")
                            }
                            if (cornerIcon.isNotEmpty()) {
                                sb.append("<div class=\"corner-icon\"><img src=\"").append(cornerIcon).append("\" alt=\"\"></div>")
                            }
                            if (burstIcon.isNotEmpty()) {
                                sb.append("<div class=\"burst-icon\"><img src=\"").append(burstIcon).append("\" alt=\"\"></div>")
                            }
                        }
                        sb.append("<div class=\"sp-stars\">")
                        repeat(rm.spCost.coerceIn(0, 10)) {
                            sb.append("<div class=\"star\"></div>")
                        }
                        repeat(burstCount) {
                            sb.append("<div class=\"star burst-star\"></div>")
                        }
                        sb.append("</div>")
                    } else {
                        val actionText = when (rm.actionType) {
                            "damage" -> "攻击"
                            "repel" -> "击退"
                            else -> "待命"
                        }
                        sb.append("<div class=\"normal-card\"><span class=\"text\">")
                            .append(actionText).append("</span>")
                        if (!rm.skillIconUrl.isNullOrBlank()) {
                            sb.append("<img class=\"icon\" src=\"").append(esc(rm.skillIconUrl))
                                .append("\" alt=\"\" loading=\"lazy\">")
                        }
                        sb.append("</div>")
                    }
                } else {
                    sb.append("<div class=\"empty\"></div>")
                }
                sb.append("</div>")
            }
            sb.append("</div>")
        }
        sb.append("</div></div></div></div>")
        sb.append("<div class=\"sp-line-info\">角色每回合出手顺序请在“站位与攻击顺序”里查看~</div>")
    }

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
        sb.append("<div class=\"tabs\">")
        orderedIds.forEachIndexed { idx, cid ->
            val p = panels[cid] ?: return@forEachIndexed
            val pid = "${charGroup}_$cid"
            sb.append("<input class=\"tr\" type=\"radio\" name=\"").append(charGroup)
                .append("\" id=\"").append(pid).append('"')
            if (idx == 0) sb.append(" checked")
            sb.append("><label class=\"tl\" for=\"").append(pid).append("\">")
                .append(esc(p.name.ifBlank { "#$cid" })).append("</label><div class=\"tp\">")

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

            if (p.costumesByOrder.isNotEmpty()) {
                sb.append("<div class=\"b-team-t\">持有服装</div><div class=\"b-team\">")
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

    /**
     * 优先从官方 `bd2roles/data.json` 字典通过 `name_code` 精确还原角色与服装数据；
     * 若遇到字典尚未收录的极新服装，再回退到 `panel` 与 `team` 兜底。
     */
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
        val s = String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
        return s
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
