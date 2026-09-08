package com.bd2toolsbox.data.repository

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.json.JSONObject
import java.io.File

/**
 * 把 bundle 的 hash 名翻译成人能看懂的名字。
 *
 * 已转换的安卓产物只有 `00044c1c0b4b673e127e271e219f70b2` 这种目录名，分组标题里也一样是裸 hash，
 * 对玩家等于乱码。但 app 手上已经有足够的线索把它还原成角色名：
 *
 * ```
 * 00044c1c0b4b673e…                                    (bundle 名)
 *   → local_bundle_index.json 的 scannedBundles         → cutscene_char000707.skel
 *   → 剥掉 cutscene_ 前缀与尾部 _N                        → char000707
 *   → characters.json                                   → Eclipse / Beach Vacation / cutscene
 *   → 「Eclipse - Beach Vacation（过场动画）」
 * ```
 *
 * `scannedBundles` 本身就是 `{bundle名: {hash, assets}}` 的正向索引，所以是 O(1) 查询，
 * 不需要为此再建反向表。实测 176 个产物里 173 个能自动命名（98%），剩下的是角色表还没收录的
 * 新角色 —— 那些退回显示 hash，交给用户手动重命名。
 *
 * 显示优先级：**用户别名 > 自动命名 > bundle hash**。
 */
class BundleNameResolver(private val context: Context) {

    companion object {
        private const val ALIAS_FILENAME = "bundle_aliases.json"
        private const val INDEX_FILENAME = "local_bundle_index.json"
        private const val CHARACTERS_FILENAME = "characters.json"
    }

    private val gson = Gson()

    /** 自动命名的结果。[matched] 为 false 表示没认出来，只能显示 hash。 */
    data class Resolved(
        val character: String,
        val costume: String,
        val type: String,
        val assetKey: String?,
        val matched: Boolean
    )

    // ---------------------------------------------------------------- 索引

    /** bundle 名 -> 该 bundle 里的资源名列表。来自 scannedBundles，懒加载后缓存。 */
    private var bundleAssets: Map<String, List<String>>? = null

    /** file_id -> (character, costume, type)。来自 characters.json。 */
    private var charactersByFileId: Map<String, Triple<String, String, String>>? = null

    /**
     * bundle 名 -> (character, costume, type)。来自 characters.json 的 `hashed_name` 字段。
     *
     * 这是首选路径：`hashed_name` 存的就是 bundle 目录名，一步到位，而且 characters.json
     * 在 app 启动时就会拉下来，**必然存在**。相比之下 scannedBundles 那条路要先跑完几分钟的
     * 「扫描游戏资源」才有 local_bundle_index.json —— 没扫描过（或清过 app 数据）就全军覆没，
     * 表现为所有产物都显示「未识别」。实测 176 个产物里这条路命中 172 个（98%）。
     */
    private var charactersByBundle: Map<String, Triple<String, String, String>>? = null

    private fun loadCharactersByBundle(): Map<String, Triple<String, String, String>> {
        charactersByBundle?.let { return it }
        val out = HashMap<String, Triple<String, String, String>>()
        val f = File(context.filesDir, CHARACTERS_FILENAME)
        if (f.exists()) {
            try {
                val arr = JSONObject(f.readText()).optJSONArray("characters")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val hashed = o.optString("hashed_name").ifBlank { null } ?: continue
                        // 同一个 bundle 可能对应多条（立绘 / 过场动画各一条）；
                        // 先到先得即可，两者的角色与皮肤是一样的
                        if (out.containsKey(hashed)) continue
                        out[hashed] = Triple(
                            o.optString("character").ifBlank { "未知角色" },
                            o.optString("costume").ifBlank { "未知皮肤" },
                            o.optString("type").ifBlank { "" }
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        charactersByBundle = out
        return out
    }

    private fun loadBundleAssets(): Map<String, List<String>> {
        bundleAssets?.let { return it }
        val f = File(context.filesDir, INDEX_FILENAME)
        val out = HashMap<String, List<String>>()
        if (f.exists()) {
            try {
                // 索引可达 20MB+，只取需要的那一段，避免整棵树都建成对象
                val scanned = JSONObject(f.readText()).optJSONObject("scannedBundles")
                if (scanned != null) {
                    val keys = scanned.keys()
                    while (keys.hasNext()) {
                        val bundleName = keys.next()
                        val assets = scanned.optJSONObject(bundleName)?.optJSONArray("assets")
                            ?: continue
                        val list = ArrayList<String>(assets.length())
                        for (i in 0 until assets.length()) {
                            assets.optString(i).takeIf { it.isNotBlank() }?.let { list.add(it) }
                        }
                        if (list.isNotEmpty()) out[bundleName] = list
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        bundleAssets = out
        return out
    }

    private fun loadCharacters(): Map<String, Triple<String, String, String>> {
        charactersByFileId?.let { return it }
        val f = File(context.filesDir, CHARACTERS_FILENAME)
        val out = HashMap<String, Triple<String, String, String>>()
        if (f.exists()) {
            try {
                val arr = JSONObject(f.readText()).optJSONArray("characters")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val fid = o.optString("file_id").ifBlank { null } ?: continue
                        out[fid] = Triple(
                            o.optString("character").ifBlank { "未知角色" },
                            o.optString("costume").ifBlank { "未知皮肤" },
                            o.optString("type").ifBlank { "" }
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        charactersByFileId = out
        return out
    }

    /** 索引或角色表更新后调用，下次查询会重新加载。 */
    fun invalidate() {
        bundleAssets = null
        charactersByFileId = null
        charactersByBundle = null
        allCharacters = null
        npcCharacters = null
    }

    /** 角色表里的全部角色名，已排序、已去重。 */
    private var allCharacters: List<String>? = null
    private var npcCharacters: List<String>? = null

    /**
     * 列出角色表收录的所有角色名。
     *
     * 给「按角色浏览」的主界面用 —— 它要显示全部角色（包括你还没有 mod 的），
     * 所以不能只从已扫描到的 mod 里凑。
     *
     * 会剔除 `Unknown Character` 这类占位值（实测角色表 411 条里有 27 条是它），
     * 那不是真角色，混进列表只会多出一个点开必然为空的条目。
     */
    fun listAllCharacters(): List<String> {
        allCharacters?.let { return it }
        loadCharacterNames()
        return allCharacters ?: emptyList()
    }

    /**
     * NPC 角色名单（商店/路人模型，如 Roche、Eleanor）。判定规则：一个名字
     * 只有当它的**全部** file_id 都是 npc 前缀时才算 NPC —— 像 Ailee 既有可玩
     * 条目又有 npc000004，归可玩。这份名单在「按角色」列表里作为单独分区
     * 排在最下面（见 CharacterScreen），不再混在可玩角色中间。
     */
    fun listNpcCharacters(): List<String> {
        npcCharacters?.let { return it }
        loadCharacterNames()
        return npcCharacters ?: emptyList()
    }

    /** 一次遍历分出可玩 / NPC 两份名单（按 file_id 前缀），各自缓存。 */
    private fun loadCharacterNames() {
        val f = File(context.filesDir, CHARACTERS_FILENAME)
        if (!f.exists()) return
        val playable = LinkedHashSet<String>()
        val npcOnly = LinkedHashSet<String>()
        // 名字可能同时出现在可玩与 npc 条目里；先收集 npc 侧名字，
        // 最后把「有可玩条目」的名字从 npc 侧剔除
        val npcCandidates = HashSet<String>()
        try {
            val arr = JSONObject(f.readText()).optJSONArray("characters")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val name = o.optString("character").trim()
                    if (name.isBlank() || name.equals("Unknown Character", true) ||
                        name.equals("Unknown", true)
                    ) continue
                    if (o.optString("file_id").startsWith("npc")) {
                        npcCandidates.add(name)
                        npcOnly.add(name)
                    } else {
                        playable.add(name)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return
        }
        // 双身份名字归可玩（Ailee 这类）
        npcOnly.removeAll(playable)
        allCharacters = playable.sortedBy { it.lowercase() }
        npcCharacters = npcOnly.sortedBy { it.lowercase() }
    }

    // ---------------------------------------------------------------- 解析

    /**
     * 从资源名剥出 characters.json 用的 file_id。
     *
     * `cutscene_char000707_2.skel` -> `char000707`
     * `char061092 back1.png`       -> `char061092`   （立绘 bundle 常见这种带空格的分部名）
     * `char060501-back1.png`       -> `char060501`
     */
    private fun fileIdOf(assetName: String): String {
        var stem = assetName.substringBeforeLast('.')
        if (stem.startsWith("cutscene_", ignoreCase = true)) stem = stem.substring(9)
        // 立绘的分部贴图叫 "charXXXXXX back1" / "charXXXXXX-back1"，file_id 只到第一段
        stem = stem.split(' ', '-').first()
        return stem.replace(Regex("_\\d+$"), "")
    }

    /**
     * bundle 目录名是否像被截断过。
     *
     * 游戏的 bundle 目录名一律是 32 位小写 hex。实测过一种真实的坏情况：用 adb 从含中文的
     * 路径 push 时，adb 会按 GBK 误解路径，落地的目录名被截成 `08994bcd105a53be4fef557`
     * 这样的残名。这种 mod 不只是认不出名字 —— 装进游戏也不会被加载，因为路径根本对不上。
     * 所以要跟"新角色查不到"区分开，明确告诉用户去重新拷贝。
     */
    fun looksTruncated(bundleName: String): Boolean =
        !bundleName.matches(Regex("^[0-9a-f]{32}$"))

    fun resolve(bundleName: String): Resolved {
        // 路线一：characters.json 的 hashed_name 直查。不依赖「扫描游戏资源」，命中率 98%。
        loadCharactersByBundle()[bundleName]?.let { info ->
            val kind = when {
                info.third.equals("cutscene", true) -> "过场动画"
                info.third.equals("idle", true) -> "立绘"
                else -> info.third
            }
            return Resolved(info.first, info.second, kind, null, matched = true)
        }

        // 路线二：从游戏资源索引里翻出这个 bundle 装了哪些资源，再按 file_id 查角色表。
        // 能补上 hashed_name 没覆盖到的那部分，但前提是用户扫描过游戏资源。
        val assets = loadBundleAssets()[bundleName].orEmpty()
        // .skel 最能代表这个 bundle 改的是谁；没有就退而看其他资源
        val ordered = assets.filter { it.endsWith(".skel", true) } +
                assets.filterNot { it.endsWith(".skel", true) }

        val chars = loadCharacters()
        for (asset in ordered) {
            val info = chars[fileIdOf(asset)] ?: continue
            val isCutscene = asset.startsWith("cutscene_", ignoreCase = true)
            val kind = when {
                isCutscene -> "过场动画"
                info.third.equals("idle", true) -> "立绘"
                else -> info.third
            }
            return Resolved(info.first, info.second, kind, asset, matched = true)
        }
        return Resolved("未识别", ordered.firstOrNull()?.let { fileIdOf(it) } ?: bundleName,
            "", ordered.firstOrNull(), matched = false)
    }

    /** 拼成一行显示名，如「Eclipse - Beach Vacation（过场动画）」。 */
    fun displayName(bundleName: String): String {
        aliasOf(bundleName)?.let { return it }
        val r = resolve(bundleName)
        if (!r.matched) return bundleName
        val suffix = if (r.type.isBlank()) "" else "（${r.type}）"
        return "${r.character} - ${r.costume}$suffix"
    }

    // ---------------------------------------------------------------- 别名

    private fun aliasFile(): File = File(context.filesDir, ALIAS_FILENAME)

    fun loadAliases(): Map<String, String> {
        val f = aliasFile()
        if (!f.exists()) return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, String>>() {}.type
            gson.fromJson<Map<String, String>>(f.readText(), type) ?: emptyMap()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyMap()
        }
    }

    fun aliasOf(bundleName: String): String? = loadAliases()[bundleName]?.takeIf { it.isNotBlank() }

    /** 传空字符串即清除别名，回到自动命名。 */
    fun setAlias(bundleName: String, alias: String) {
        val current = loadAliases().toMutableMap()
        if (alias.isBlank()) current.remove(bundleName) else current[bundleName] = alias.trim()
        try {
            aliasFile().writeText(gson.toJson(current))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
