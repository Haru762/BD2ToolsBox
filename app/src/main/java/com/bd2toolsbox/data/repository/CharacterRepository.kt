package com.bd2toolsbox.data.repository

import android.content.Context
import com.bd2toolsbox.data.model.CharacterInfo
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 角色表（characters.json）的读取与匹配。
 *
 * characters.json 由 python 侧从 catalog + 角色元数据网站生成，这里管三件事：
 * 触发刷新（[updateCharacterData]）、解析成 file_id → 角色信息的查找表、
 * 以及把 mod 文件名匹配到具体角色皮肤（[findBestMatch]）。
 *
 * 生产路径之外还有一条兜底：随包带着一份对照表（assets/characters.json），
 * filesDir 里没有时由 [seedCharactersJsonFromAssets] 铺过去。刷新逻辑不变 ——
 * 在线刷新成功照样覆盖它。
 */
class CharacterRepository(private val context: Context) {

    companion object {
        private const val CHARACTERS_JSON = "characters.json"
        private const val MOD_CACHE = "mod_cache.json"

        /** 随包兜底副本的 assets 路径。 */
        private const val ASSET_CHARACTERS_JSON = "characters.json"

        /** 铺底只落一次盘；并发进来的调用在这里排队。 */
        private val seedLock = Any()
    }

    /** file_id → 该角色的全部皮肤条目（idle / cutscene 各一条）。 */
    private var characterLut: Map<String, List<CharacterInfo>> = emptyMap()

    init {
        // 构造即铺底。读取方不止这里：BundleNameResolver 也直接读 filesDir 那份，
        // 而它的查找表是懒加载后长期缓存的（invalidate() 全工程没有调用方），
        // 所以铺底必须早于任何读取方第一次取值 —— 晚一步，那个实例就会一直
        // 拿着空表显示「未识别」，直到进程重启。
        seedCharactersJsonFromAssets()
    }

    /**
     * 刷新角色表。返回 "SUCCESS" / "SKIPPED" / "FAILED"。
     *
     * characters.json 换新版本意味着角色 → bundle 的映射可能变了，mod 缓存
     * 随之作废（删除 mod_cache.json，下一轮扫描重新解析）。
     */
    suspend fun updateCharacterData(quality: String): String = withContext(Dispatchers.IO) {
        var status = "FAILED"
        try {
            if (!Python.isStarted()) {
                Python.start(com.chaquo.python.android.AndroidPlatform(context))
            }
            val mainScript = Python.getInstance().getModule("main_script")
            val result = mainScript
                .callAttr("update_character_data", context.filesDir.absolutePath, quality)
                .asList()
            when (result[0].toString()) {
                "SUCCESS" -> {
                    println("Successfully ran scraper and saved characters.json: ${result[1]}")
                    File(context.filesDir, MOD_CACHE).takeIf { it.exists() }?.let {
                        it.delete()
                        println("Deleted mod cache to force re-scan.")
                    }
                    status = "SUCCESS"
                }
                "SKIPPED" -> {
                    println("Scraper skipped: ${result[1]}")
                    status = "SKIPPED"
                }
                else -> {
                    println("Scraper script failed: ${result[1]}. Will use local version if available.")
                    status = "FAILED"
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            println("Failed to execute scraper python script, will use local version. Error: ${e.message}")
        }
        characterLut = parseCharacterJson()
        status
    }

    fun hasLocalCharactersJson(): Boolean {
        // 先铺底再看：新装 app 断网首启时，这一步就是「有表可读」的唯一来源
        seedCharactersJsonFromAssets()
        return File(context.filesDir, CHARACTERS_JSON).length() > 0L
    }

    /**
     * 把随包的 characters.json 铺到 filesDir，只在本地没有时。
     *
     * 这份表原本完全靠运行时生成：抓 browndust2modding.pages.dev 的角色表格 +
     * 下载 CDN catalog 拼装。新装的 app 一旦断网、或者那个站点挂了，filesDir
     * 里就是空的，角色名全塌成「未识别」，「按角色浏览」整页空白。随包带一份
     * 等于把「联网」从首启的必需项降级成可选项：有网照常刷新覆盖，没网也有
     * 数据可读。
     *
     * 已有副本一律不动 —— 用户手上那份可能比随包的更新。随包文件自身也要能
     * 解析出非空 characters 数组才落盘：一个损坏的 assets 若被原样铺过去，
     * 上层会以为「本地有表」，反而把兜底逻辑短路掉，比不铺更糟。
     *
     * 随包那份的来路：拿一份本地 catalog 喂 character_scraper 的
     * scrape_and_save_from_catalog，产物覆盖 assets/characters.json。`version`
     * 要填该 catalog 对应的真实 CDN 版本号 —— 首启时在线刷新会拿它跟 CDN 当前
     * 版本比对，一致就直接 SKIPPED，连 63 MB 的 catalog 都省了；填假值则会每次
     * 首启都白下一遍。游戏大版本更新后重跑一次即可。
     */
    private fun seedCharactersJsonFromAssets() {
        val target = File(context.filesDir, CHARACTERS_JSON)
        if (target.length() > 0L) return
        synchronized(seedLock) {
            if (target.length() > 0L) return
            val text = try {
                context.assets.open(ASSET_CHARACTERS_JSON).bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                // assets 里没有或读不动：维持原状，仍旧走运行时生成
                e.printStackTrace()
                return
            }
            val count = try {
                JSONObject(text).optJSONArray("characters")?.length() ?: 0
            } catch (e: Exception) {
                e.printStackTrace()
                0
            }
            if (count == 0) {
                println("内置 $ASSET_CHARACTERS_JSON 解析不出条目，跳过铺底")
                return
            }
            try {
                // 先写临时文件再重命名：中途被打断也不会留下半截角色表
                val tmp = File(context.filesDir, "$CHARACTERS_JSON.tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                println("已从 assets 铺入内置角色表：$count 条")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 解析 characters.json 为查找表。兼容两种历史格式：
     * 新版 {version, characters: [...]}、旧版裸数组。
     *
     * 刷新失败（离线 / 站点挂）时文件仍是随包铺下的那份，查找表照样有值。
     */
    private suspend fun parseCharacterJson(): Map<String, List<CharacterInfo>> =
        withContext(Dispatchers.IO) {
            seedCharactersJsonFromAssets()
            val file = File(context.filesDir, CHARACTERS_JSON)
            if (!file.exists()) return@withContext emptyMap()
            val text = try {
                file.readText()
            } catch (e: Exception) {
                e.printStackTrace(); ""
            }
            if (text.isEmpty()) return@withContext emptyMap()

            val lut = mutableMapOf<String, MutableList<CharacterInfo>>()
            fun absorb(arr: JSONArray) {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val info = CharacterInfo(
                        obj.getString("character"),
                        obj.getString("costume"),
                        obj.getString("type"),
                        obj.getString("hashed_name")
                    )
                    lut.getOrPut(obj.getString("file_id").lowercase()) { mutableListOf() }.add(info)
                }
            }
            try {
                absorb(JSONObject(text).getJSONArray("characters"))
            } catch (_: Exception) {
                try {
                    absorb(JSONArray(text))
                } catch (e2: Exception) {
                    e2.printStackTrace()
                }
            }
            lut
        }

    /**
     * 把一个 mod（file_id + 文件名）匹配到具体皮肤条目。
     *
     * 同一 file_id 可能有多条（idle + cutscene）：文件名带 cutscene 字样的
     * 优先取 cutscene 条；否则取唯一有哈希的、再退到 idle、最后随便一条。
     */
    fun findBestMatch(fileId: String?, fileNames: List<String>): CharacterInfo? {
        if (fileId == null) return null
        val candidates = characterLut[fileId] ?: return null
        if (candidates.size <= 1) return candidates.firstOrNull()

        if (fileNames.any { it.contains("cutscene", ignoreCase = true) }) {
            candidates.find { it.type == "cutscene" }?.let { return it }
        }

        candidates.filter { !it.hashedName.isNullOrBlank() }.singleOrNull()?.let { return it }
        candidates.find { it.type == "idle" }?.let { return it }
        return candidates.first()
    }

    /** 从 mod 文件名里抽出 file_id（如 char000104），与 python 侧的正则保持同款。 */
    fun extractFileId(entryName: String): String? =
        "(char\\d{6}|illust_dating\\d+|illust_special\\d+|illust_talk\\d+|npc\\d+|specialillust\\w+|storypack\\w+|\\bRhythmHitAnim\\b)"
            .toRegex(RegexOption.IGNORE_CASE)
            .find(entryName)?.value?.lowercase()
}
