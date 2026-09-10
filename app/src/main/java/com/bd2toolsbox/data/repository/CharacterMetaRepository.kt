package com.bd2toolsbox.data.repository

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * 角色附加信息：性别、是否联动、中文名、头像图文件名。
 *
 * ## 数据从哪来
 *
 * 随 APK 打包的 `assets/character_meta.json`，190 套皮肤 / 83 个角色，52 KB。
 * 原始数据是从 PC 端的 BD2ModManager（bruhnn 那个，Tauri 应用）exe 里内嵌的
 * characters.json 抠出来的，版本 0.0.24，皮肤上线日期最新到 2026-07-16。
 *
 * 为什么不联网取：那个项目原先在 GitHub 上有 characters.csv，但仓库已经改成
 * Vue 前端项目，manifest 里指的 `src/data/characters.csv` 现在是 404。
 * 没有可靠的更新源，所以随包走 —— 反正这些字段只在新皮肤上线时才变。
 *
 * ## 与 characters.json 的关系
 *
 * 游戏侧那份 `characters.json`（filesDir，411 条 / 95 角色）仍是角色**全量**的
 * 唯一来源，这里只做**增强**。实测两者是包含关系：这 83 个角色全在那 95 个里面，
 * 多出来的 12 个是 NPC 与名字变体（Guild Girl、Female Researcher、Smol Liberta、
 * Spatti、Darian Silverstein 等），它们没有性别与头像，按「未知」处理 ——
 * 所以性别筛选是「筛出确定是男/女的」，不是「非男即女」。
 */
class CharacterMetaRepository private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "CharacterMeta"
        private const val ASSET_NAME = "character_meta.json"

        /** 方形头像图标，约 10 KB —— 列表里 40 dp 的头像位用它。 */
        private const val HEAD_BASE =
            "https://raw.githubusercontent.com/myssal/Brown-Dust-2-Asset/" +
                    "refs/heads/master/ui/icon/icon_char/"

        /** 立绘缩略图，约 70 KB —— 角色卡片里每套皮肤那一行用它。 */
        private const val ILLUST_BASE =
            "https://raw.githubusercontent.com/myssal/Brown-Dust-2-Asset/" +
                    "refs/heads/master/ui/illust/illust_inven_char/"

        @Volatile
        private var instance: CharacterMetaRepository? = null

        fun get(context: Context): CharacterMetaRepository =
            instance ?: synchronized(this) {
                instance ?: CharacterMetaRepository(context.applicationContext)
                    .also { instance = it }
            }
    }

    /** 一套皮肤的附加信息。 */
    data class Costume(
        val costumeId: String,
        val character: String,
        val characterCn: String,
        val costume: String,
        val costumeCn: String,
        val gender: String,
        val isCollab: Boolean,
        val datingId: String,
        val headImage: String,
        val illustImage: String,
        val element: String,
        val grade: Int,
        val skinType: String
    ) {
        val headUrl: String? get() = headImage.takeIf { it.isNotBlank() }?.let { HEAD_BASE + it }
        val illustUrl: String? get() = illustImage.takeIf { it.isNotBlank() }?.let { ILLUST_BASE + it }
    }

    /** 一个角色的汇总信息（取该角色各套皮肤的共有属性 + 一套代表皮肤）。 */
    data class CharacterMeta(
        val name: String,
        val nameCn: String,
        val gender: String,
        val isCollab: Boolean,
        val element: String,
        /** 代表皮肤：按 costume_id 最小的那套，也就是初始形象。 */
        val representative: Costume?
    ) {
        val isMale: Boolean get() = gender.equals("male", true)
        val isFemale: Boolean get() = gender.equals("female", true)
    }

    @Volatile
    private var byCostumeId: Map<String, Costume>? = null

    @Volatile
    private var byCharacter: Map<String, CharacterMeta>? = null

    @Volatile
    private var dataVersion: String = ""

    private fun ensureLoaded() {
        if (byCostumeId != null) return
        synchronized(this) {
            if (byCostumeId != null) return
            val costumes = HashMap<String, Costume>()
            try {
                val text = appContext.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
                val root = JSONObject(text)
                dataVersion = root.optString("dataVersion")
                val arr = root.optJSONArray("costumes")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val cid = o.optString("cid").trim()
                        if (cid.isBlank()) continue
                        costumes[cid] = Costume(
                            costumeId = cid,
                            character = o.optString("char").trim(),
                            characterCn = o.optString("charCn").trim(),
                            costume = o.optString("costume").trim(),
                            costumeCn = o.optString("costumeCn").trim(),
                            gender = o.optString("gender").trim(),
                            isCollab = o.optBoolean("collab"),
                            datingId = o.optString("dating").trim(),
                            headImage = o.optString("head").trim(),
                            illustImage = o.optString("illust").trim(),
                            element = o.optString("element").trim(),
                            grade = o.optInt("grade"),
                            skinType = o.optString("skin").trim()
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "读取 $ASSET_NAME 失败，性别/联动筛选与头像将不可用", e)
            }

            // 汇总到角色。同一角色的各套皮肤性别/联动一致，取第一套即可；
            // 代表皮肤取 costume_id 最小的那套（编号按上线顺序发，最小的是初始形象）。
            val chars = HashMap<String, CharacterMeta>()
            costumes.values.groupBy { it.character }.forEach { (name, list) ->
                if (name.isBlank()) return@forEach
                val rep = list.minByOrNull { it.costumeId }
                chars[name] = CharacterMeta(
                    name = name,
                    nameCn = list.firstOrNull { it.characterCn.isNotBlank() }?.characterCn ?: "",
                    gender = list.firstOrNull { it.gender.isNotBlank() }?.gender ?: "",
                    isCollab = list.any { it.isCollab },
                    element = list.firstOrNull { it.element.isNotBlank() }?.element ?: "",
                    representative = rep
                )
            }

            byCostumeId = costumes
            byCharacter = chars
            Log.d(TAG, "角色附加信息载入: ${costumes.size} 套皮肤 / ${chars.size} 个角色（数据版本 $dataVersion）")
        }
    }

    // ---------------------------------------------------------------- 查询

    fun forCharacter(name: String): CharacterMeta? {
        ensureLoaded()
        return byCharacter?.get(name.trim())
    }

    /** 表已就绪时同步查，未就绪返回 null 且不触发加载 —— 给 Compose 首帧用。 */
    fun peekCharacter(name: String): CharacterMeta? = byCharacter?.get(name.trim())

    /**
     * 每个角色的代表皮肤（初始形象），供头像预取遍历。
     *
     * 顺序按角色名排（而不是哈希表那套随机序）：预取的先后顺序每次启动都一样，
     * 半途断网时补齐的是同样那批，不至于这次有这张下次没有。
     */
    fun allRepresentatives(): List<Costume> {
        ensureLoaded()
        return byCharacter?.values
            ?.mapNotNull { it.representative }
            ?.sortedBy { it.character.lowercase() }
            ?: emptyList()
    }

    fun forCostumeId(costumeId: String): Costume? {
        ensureLoaded()
        return byCostumeId?.get(costumeId.trim())
    }

    /**
     * 从资源名/file_id 里剥出 costume_id。
     *
     * mod 侧的 file_id 形如 `char000708`、`cutscene_char004091_1`、`illust_dating16`，
     * 而这里的 costume_id 是纯六位数字（`000708`）。所以取 `char` 后面那六位数字。
     * 取不到就返回 null —— 心契之约的 `illust_datingNN` 没有 char 段，那类走
     * [forDatingId]。
     */
    fun costumeIdFromFileId(fileId: String?): String? {
        val s = fileId?.lowercase() ?: return null
        val m = Regex("char(\\d{6})").find(s) ?: return null
        return m.groupValues[1]
    }

    fun forFileId(fileId: String?): Costume? {
        val cid = costumeIdFromFileId(fileId) ?: return null
        return forCostumeId(cid)
    }

    /** 心契之约按 dating_id 反查（`illust_dating16` -> dating_id "16"）。 */
    fun forDatingId(fileId: String?): Costume? {
        val s = fileId?.lowercase() ?: return null
        val m = Regex("dating(\\d+)").find(s) ?: return null
        val id = m.groupValues[1]
        ensureLoaded()
        return byCostumeId?.values?.firstOrNull {
            it.datingId.isNotBlank() && it.datingId.trimStart('0').ifEmpty { "0" } ==
                    id.trimStart('0').ifEmpty { "0" }
        }
    }

    /** 数据版本，给设置页显示「附加信息来自哪一版」。 */
    fun version(): String {
        ensureLoaded()
        return dataVersion
    }

    fun counts(): Triple<Int, Int, Int> {
        ensureLoaded()
        val chars = byCharacter?.values ?: return Triple(0, 0, 0)
        return Triple(
            chars.count { it.isMale },
            chars.count { it.isFemale },
            chars.count { it.isCollab }
        )
    }
}
