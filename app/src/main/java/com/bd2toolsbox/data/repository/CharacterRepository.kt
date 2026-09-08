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
 */
class CharacterRepository(private val context: Context) {

    companion object {
        private const val CHARACTERS_JSON = "characters.json"
        private const val MOD_CACHE = "mod_cache.json"
    }

    /** file_id → 该角色的全部皮肤条目（idle / cutscene 各一条）。 */
    private var characterLut: Map<String, List<CharacterInfo>> = emptyMap()

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

    fun hasLocalCharactersJson(): Boolean =
        File(context.filesDir, CHARACTERS_JSON).exists()

    /**
     * 解析 characters.json 为查找表。兼容两种历史格式：
     * 新版 {version, characters: [...]}、旧版裸数组。
     */
    private suspend fun parseCharacterJson(): Map<String, List<CharacterInfo>> =
        withContext(Dispatchers.IO) {
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
