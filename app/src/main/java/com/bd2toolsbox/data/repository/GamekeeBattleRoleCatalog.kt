package com.bd2toolsbox.data.repository

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * GameKee 角色与召唤物全量战斗字典（对应官方 `/v1/bdRoster/query-role-list-json` -> `bd2roles/data.json`）。
 *
 * 原版 Wiki 的 `v2` (`userBattleTemp`) 与 `v3` (`bd2Battle`) 阵容接口返回的 `content` 均为精简存储：
 * - `v3` 每回合只存 `content_id` 与 `currentSkin.name_code`（如 `"670435-3"`），不含服装名、技能图标、
 *   九宫格攻击范围缩略图（`strengthen.image`）、击退方向图标（`repel`）、物理/魔法图标、SP/CD 与技能描述；
 * - 必须通过本字典执行原版 `restoreBattleTeamInfosFromContent` / `getInitRoleData` 还原。
 */
internal object GamekeeBattleRoleCatalog {

    data class PotentialEntry(
        val name: String,
        val desc: String,
        val icon: String?,
        val image: String?,
        val sp: String
    )

    data class StrengthenEntry(
        val name: String,
        val image: String?,
        val sp: Int,
        val spRecover: Int,
        val cd: Int,
        val desc: String
    )

    data class SkinEntry(
        val id: Int,
        val name: String,
        val nameCode: String,
        val icon: String?,
        val repel: String?,
        val damageTypeIcon: String?,
        val skillIcon: String?,
        val skillCastIcon: String?,
        val skillName: String,
        val potentials: List<PotentialEntry>,
        val strengthens: List<StrengthenEntry>
    ) {
        fun strengthenForLevel(level: Int?): StrengthenEntry? {
            if (level == null) return strengthens.firstOrNull()
            val target = "+$level"
            return strengthens.firstOrNull { it.name == target }
                ?: strengthens.lastOrNull()
        }

        fun rangeImageFor(level: Int?, potentialCount: Int?): String? {
            val potCount = potentialCount ?: 0
            if (potCount > 0) {
                // 原版 7944 mixin：若已点出改变范围的潜能（desc 为空且带 image），优先取潜能范围图
                val rangePot = potentials.take(potCount).firstOrNull { it.desc.isBlank() && !it.image.isNullOrBlank() }
                    ?: potentials.firstOrNull { it.desc.isBlank() && !it.image.isNullOrBlank() }
                if (rangePot?.image != null && potCount >= potentials.size) {
                    return rangePot.image
                }
            }
            return strengthenForLevel(level)?.image
        }

        fun spFor(level: Int?, potentialCount: Int? = null): Int {
            val base = strengthenForLevel(level)?.sp ?: 0
            val potCount = potentialCount ?: 0
            if (level == 5 && potCount >= potentials.size && potentials.isNotEmpty()) {
                val maxEntry = strengthens.firstOrNull { it.name == "满破满潜力" }
                if (maxEntry != null && maxEntry.sp > 0) return maxEntry.sp
            }
            return base
        }
    }

    data class RoleEntry(
        val id: Int,
        val contentId: Long,
        val name: String,
        val icon: String?,
        val icon1: String?,
        val star: String,
        val starInt: Int,
        val attr: String,
        val attrIcon: String?,
        val damage: String,
        val damageTypeIcon: String?,
        val position: String,
        val skins: List<SkinEntry>
    )

    data class PetEntry(
        val id: String,
        val name: String,
        val icon: String?,
        val skillImage: String?,
        val skillName: String,
        val skillDesc: String,
        val repel: String?,
        val damageTypeIcon: String?,
        val sp: Int,
        val cd: Int
    )

    data class ResolvedRoleSkin(
        val role: RoleEntry,
        val skin: SkinEntry
    )

    private class CatalogSnapshot(
        val byNameCode: Map<String, ResolvedRoleSkin>,
        val byContentId: Map<Long, RoleEntry>,
        val byRoleId: Map<Int, RoleEntry>,
        val byRoleName: Map<String, RoleEntry>,
        val petById: Map<String, PetEntry>,
        val petByName: Map<String, PetEntry>
    )

    @Volatile
    private var snapshot: CatalogSnapshot? = null

    fun ensureLoaded(appContext: Context? = null, overrideJson: String? = null) {
        if (!overrideJson.isNullOrBlank()) {
            parseCatalog(overrideJson)?.let { snapshot = it; return }
        }
        if (snapshot != null) return
        synchronized(this) {
            if (snapshot != null) return
            val raw = loadRawJson(appContext) ?: return
            parseCatalog(raw)?.let { snapshot = it }
        }
    }

    fun findSkinByNameCode(nameCode: String?): ResolvedRoleSkin? {
        if (nameCode.isNullOrBlank()) return null
        ensureLoaded()
        return snapshot?.byNameCode?.get(nameCode.trim())
    }

    fun findRoleByContentId(contentId: Long?): RoleEntry? {
        if (contentId == null || contentId <= 0L) return null
        ensureLoaded()
        return snapshot?.byContentId?.get(contentId)
    }

    fun findRoleByIdOrName(id: Int?, name: String?): RoleEntry? {
        ensureLoaded()
        val s = snapshot ?: return null
        if (id != null && id > 0) {
            s.byRoleId[id]?.let { return it }
        }
        if (!name.isNullOrBlank()) {
            s.byRoleName[name.trim()]?.let { return it }
        }
        return null
    }

    fun findPet(id: String?, name: String?): PetEntry? {
        ensureLoaded()
        val s = snapshot ?: return null
        if (!id.isNullOrBlank()) {
            s.petById[id.trim()]?.let { return it }
        }
        if (!name.isNullOrBlank()) {
            s.petByName[name.trim()]?.let { return it }
        }
        return null
    }

    private fun loadRawJson(appContext: Context?): String? {
        if (appContext != null) {
            try {
                return appContext.assets.open("bd2_roles_battle.json")
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
            } catch (_: Exception) {
            }
        }
        // JVM 单元测试兜底：从 classpath 或仓库 assets 目录直接读取
        try {
            javaClass.classLoader?.getResourceAsStream("bd2_roles_battle.json")?.let { stream ->
                return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
        } catch (_: Exception) {
        }
        val candidates = listOf(
            File("src/main/assets/bd2_roles_battle.json"),
            File("app/src/main/assets/bd2_roles_battle.json"),
            File("worktrees/bdroid-android-refactor/app/src/main/assets/bd2_roles_battle.json")
        )
        for (f in candidates) {
            if (f.isFile && f.length() > 0) {
                return runCatching { f.readText(Charsets.UTF_8) }.getOrNull()
            }
        }
        return null
    }

    private fun parseCatalog(raw: String): CatalogSnapshot? = try {
        val root = Json.parseToJsonElement(raw) as? JsonObject ?: return null
        val byNameCode = HashMap<String, ResolvedRoleSkin>(256)
        val byContentId = HashMap<Long, RoleEntry>(128)
        val byRoleId = HashMap<Int, RoleEntry>(128)
        val byRoleName = HashMap<String, RoleEntry>(128)

        root.arr("role")?.forEach { groupEl ->
            val groupObj = groupEl as? JsonObject ?: return@forEach
            val groupStar = groupObj.str("star")
            groupObj.arr("children")?.forEach { roleEl ->
                val r = roleEl as? JsonObject ?: return@forEach
                val roleId = r.int("id") ?: 0
                val cid = r.long("content_id") ?: 0L
                val rName = r.str("name")
                val starStr = r.str("star").ifBlank { groupStar }
                val starInt = starStr.filter { it.isDigit() }.toIntOrNull() ?: 5
                val rDmgIcon = absolutize(r.str("damage_type"))
                val skins = r.arr("skin")?.mapNotNull { skinEl ->
                    val s = skinEl as? JsonObject ?: return@mapNotNull null
                    val skillsObj = s.obj("skills")
                    val potentials = skillsObj?.arr("potential")?.mapNotNull { pEl ->
                        val p = pEl as? JsonObject ?: return@mapNotNull null
                        PotentialEntry(
                            name = p.str("name"),
                            desc = p.str("desc"),
                            icon = absolutize(p.str("icon")),
                            image = absolutize(p.str("image")),
                            sp = p.str("sp")
                        )
                    }.orEmpty()
                    val strengthens = skillsObj?.arr("strengthen")?.mapNotNull { stEl ->
                        val st = stEl as? JsonObject ?: return@mapNotNull null
                        StrengthenEntry(
                            name = st.str("name"),
                            image = absolutize(st.str("image")),
                            sp = st.int("sp") ?: 0,
                            spRecover = st.int("sp_recover") ?: 0,
                            cd = st.int("cd") ?: 0,
                            desc = st.str("desc")
                        )
                    }.orEmpty()
                    SkinEntry(
                        id = s.int("id") ?: 0,
                        name = s.str("name"),
                        nameCode = s.str("name_code"),
                        icon = absolutize(s.str("icon")),
                        repel = absolutize(s.str("repel")),
                        damageTypeIcon = absolutize(s.str("damage_type")) ?: rDmgIcon,
                        skillIcon = absolutize(s.str("skill_icon")),
                        skillCastIcon = absolutize(s.str("skill_cast_icon")),
                        skillName = s.str("skill_name"),
                        potentials = potentials,
                        strengthens = strengthens
                    )
                }.orEmpty()

                val roleEntry = RoleEntry(
                    id = roleId,
                    contentId = cid,
                    name = rName,
                    icon = absolutize(r.str("icon")),
                    icon1 = absolutize(r.str("icon_1")),
                    star = starStr,
                    starInt = starInt,
                    attr = r.str("attr"),
                    attrIcon = absolutize(r.str("attr_icon")),
                    damage = r.str("damage"),
                    damageTypeIcon = rDmgIcon,
                    position = r.str("position"),
                    skins = skins
                )
                if (cid > 0L) byContentId[cid] = roleEntry
                if (roleId > 0) byRoleId[roleId] = roleEntry
                if (rName.isNotBlank()) byRoleName[rName] = roleEntry
                for (sk in skins) {
                    if (sk.nameCode.isNotBlank()) {
                        byNameCode[sk.nameCode] = ResolvedRoleSkin(roleEntry, sk)
                    }
                }
            }
        }

        val petById = HashMap<String, PetEntry>(16)
        val petByName = HashMap<String, PetEntry>(16)
        root.arr("pet")?.forEachIndexed { idx, petEl ->
            val p = petEl as? JsonObject ?: return@forEachIndexed
            val pid = p.str("id").ifBlank { "pet_${idx + 1}" }
            val pName = p.str("name")
            val entry = PetEntry(
                id = pid,
                name = pName,
                icon = absolutize(p.str("icon")),
                skillImage = absolutize(p.str("skill_image")),
                skillName = p.str("skill_name"),
                skillDesc = p.str("skill_desc"),
                repel = absolutize(p.str("repel")),
                damageTypeIcon = absolutize(p.str("damage_type")),
                sp = p.int("sp") ?: 0,
                cd = p.int("cd") ?: 0
            )
            petById[pid] = entry
            if (pName.isNotBlank()) petByName[pName] = entry
        }

        CatalogSnapshot(
            byNameCode = byNameCode,
            byContentId = byContentId,
            byRoleId = byRoleId,
            byRoleName = byRoleName,
            petById = petById,
            petByName = petByName
        )
    } catch (_: Exception) {
        null
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

    private fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
    private fun JsonObject.arr(k: String): JsonArray? = this[k] as? JsonArray
    private fun JsonObject.str(k: String): String = (this[k] as? JsonPrimitive)?.content.orEmpty()
    private fun JsonObject.int(k: String): Int? = str(k).toIntOrNull()
    private fun JsonObject.long(k: String): Long? = str(k).toLongOrNull()
}
