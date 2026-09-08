package com.example.bd2modmanager.data.repository

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.chaquo.python.Python
import com.example.bd2modmanager.data.model.MatchStrategy
import com.example.bd2modmanager.data.model.ModCacheInfo
import com.example.bd2modmanager.data.model.ModDetails
import com.example.bd2modmanager.data.model.ModInfo
import com.example.bd2modmanager.data.model.ModKind
import com.example.bd2modmanager.data.model.ResolutionState
import com.example.bd2modmanager.data.model.ResolvedTarget
import com.example.bd2modmanager.service.ModdingService
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

class ModRepository(
    private val context: Context,
    private val characterRepository: CharacterRepository
) {

    private val nameResolver = BundleNameResolver(context)

    companion object {
        private const val MOD_CACHE_FILENAME = "mod_cache.json"

        /**
         * 递归查找 mod 的最大深度（相对所选目录）。
         * 常见的「合集/作者/mod」只有两三层，6 层留足余量又能挡住误选大目录的情况。
         */
        /**
         * 递归深度上限。
         *
         * 实测用户的收藏里已有 mod 位于第 6 层（从所选目录算起），而目录结构还没整理完，
         * 所以留出余量。上限存在的意义只是防止误选存储根目录时把整部手机翻一遍 ——
         * 真正兜底的是 [MAX_DIRS_VISITED]。
         */
        private const val MAX_SCAN_DEPTH = 8

        /** 目录访问上限：万一用户选了存储根目录，也不会把整个手机走一遍。 */
        private const val MAX_DIRS_VISITED = 3000

        /** 目录里直接躺着这些后缀的文件，就认定这个目录本身是一个 mod。 */
        private val MOD_ASSET_EXTENSIONS = setOf("skel", "json", "atlas", "png", "jpg", "jpeg")
    }

    private fun shouldIgnoreModEntry(entryName: String?): Boolean {
        val name = entryName?.substringAfterLast('/')?.trim()?.lowercase() ?: return true
        return name.isEmpty() || name == ".modfile" || name.endsWith(".modfile")
    }

    private val gson = Gson()

    private data class ScannedModCandidate(
        val uriString: String,
        val lastModified: Long,
        val name: String,
        val uri: Uri,
        val isDirectory: Boolean,
        val modDetails: ModDetails
    )

    /** SAF 目录下的一个子项（文件或目录）。 */
    private data class ChildDoc(
        val docId: String,
        val name: String,
        val isDirectory: Boolean,
        val lastModified: Long,
        val size: Long = 0L
    )

    /**
     * 递归找到的一个 mod。
     * [displayName] 带相对路径（如 `合集A/levia_idle`），这样不同子目录下的同名 mod 不会混淆。
     * [fileNames] 是目录 mod 的直接子文件名，扫描时顺手带出来，省掉一次重复查询；zip 为空。
     */
    private data class DiscoveredMod(
        val uri: Uri,
        val displayName: String,
        val lastModified: Long,
        val isDirectory: Boolean,
        val fileNames: List<String>,
        val kind: ModKind = ModKind.PC_SOURCE,
        /** 仅已转换产物：hash 目录名 */
        val hashDir: String? = null,
        /** 仅已转换产物：__data 字节数 */
        val dataSize: Long = 0L
    )

    private fun isModAssetFile(name: String): Boolean {
        if (shouldIgnoreModEntry(name)) return false
        return name.substringAfterLast('.', "").lowercase() in MOD_ASSET_EXTENSIONS
    }

    /** 列出一个目录的直接子项。每个目录只查一次。 */
    private fun listChildren(treeRootUri: Uri, parentDocId: String): List<ChildDoc> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeRootUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE
        )
        val out = mutableListOf<ChildDoc>()
        try {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val modifiedCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(idCol) ?: continue
                    val name = cursor.getString(nameCol) ?: continue
                    val mime = cursor.getString(mimeCol) ?: ""
                    out.add(
                        ChildDoc(
                            docId = docId,
                            name = name,
                            isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                            lastModified = cursor.getLong(modifiedCol),
                            size = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else 0L
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return out
    }

    /** 查询某个 document 自身的名字与修改时间（用于所选根目录本身就是一个 mod 的情况）。 */
    private fun queryDocumentSelf(treeRootUri: Uri, docId: String): ChildDoc? {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeRootUri, docId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val name = cursor.getString(0) ?: docId.substringAfterLast('/')
                val mime = cursor.getString(1) ?: ""
                ChildDoc(
                    docId = docId,
                    name = name,
                    isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                    lastModified = cursor.getLong(2)
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 从所选目录往下找出所有 mod，同时识别两类东西：
     *
     * - **PC mod 源文件**：目录直接含有 [MOD_ASSET_EXTENSIONS] 里的文件，就是一个 mod，
     *   且不再往下钻（mod 目录里的 `.old` 备份等子目录因此天然被跳过）。zip 任意层级都算。
     * - **已转换的安卓产物**：结构是 `<bundle名>/<hash>/__data`。递归到 `<hash>` 这层看到
     *   `__data` 时，回溯认定**父目录**是一个产物条目 —— 这样零额外查询，也不必假设
     *   「第一个子目录就是 hash 目录」。
     *
     * 于是「选一个大文件夹，里面每个小文件夹是一个 mod」、「大文件夹 → 合集 → mod」、
     * 「直接选中单个 mod 文件夹」、以及「选中一整个 Shared 产物目录」都能识别。
     */
    private fun discoverMods(treeRootUri: Uri): List<DiscoveredMod> {
        val found = mutableListOf<DiscoveredMod>()
        val convertedSeen = mutableSetOf<String>()
        var visited = 0

        fun walk(
            docId: String,
            dirName: String,
            dirLastModified: Long,
            relPath: String,
            depth: Int,
            parentDocId: String?,
            parentName: String?,
            parentLastModified: Long
        ) {
            if (visited >= MAX_DIRS_VISITED) return
            visited++

            val children = listChildren(treeRootUri, docId)
            val files = children.filter { !it.isDirectory }

            // 已转换产物：当前目录直接躺着 __data，说明这层是 hash 目录、父目录才是 bundle
            val dataFile = files.firstOrNull { it.name == "__data" }
            if (dataFile != null) {
                if (parentDocId != null && convertedSeen.add(parentDocId)) {
                    found.add(
                        DiscoveredMod(
                            uri = DocumentsContract.buildDocumentUriUsingTree(treeRootUri, parentDocId),
                            displayName = parentName ?: dirName,
                            lastModified = parentLastModified,
                            isDirectory = true,
                            fileNames = emptyList(),
                            kind = ModKind.CONVERTED_BUNDLE,
                            hashDir = dirName,
                            dataSize = dataFile.size
                        )
                    )
                }
                return
            }

            // zip 包无需看内容，任意层级都直接当一个 mod
            files.filter { it.name.endsWith(".zip", ignoreCase = true) }.forEach { zip ->
                val label = zip.name.dropLast(4)
                found.add(
                    DiscoveredMod(
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeRootUri, zip.docId),
                        displayName = if (relPath.isEmpty()) label else "$relPath/$label",
                        lastModified = zip.lastModified,
                        isDirectory = false,
                        fileNames = emptyList()
                    )
                )
            }

            val ownFileNames = files.map { it.name }.filter { !shouldIgnoreModEntry(it) }
            if (ownFileNames.any { isModAssetFile(it) }) {
                found.add(
                    DiscoveredMod(
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeRootUri, docId),
                        displayName = relPath.ifEmpty { dirName },
                        lastModified = dirLastModified,
                        isDirectory = true,
                        fileNames = ownFileNames
                    )
                )
                return
            }

            if (depth >= MAX_SCAN_DEPTH) return
            children.asSequence()
                .filter { it.isDirectory && !it.name.startsWith(".") }
                .forEach { sub ->
                    walk(
                        docId = sub.docId,
                        dirName = sub.name,
                        dirLastModified = sub.lastModified,
                        relPath = if (relPath.isEmpty()) sub.name else "$relPath/${sub.name}",
                        depth = depth + 1,
                        parentDocId = docId,
                        parentName = if (relPath.isEmpty()) dirName else relPath,
                        parentLastModified = dirLastModified
                    )
                }
        }

        val rootDocId = DocumentsContract.getTreeDocumentId(treeRootUri)
        val rootSelf = queryDocumentSelf(treeRootUri, rootDocId)
        walk(
            docId = rootDocId,
            dirName = rootSelf?.name ?: rootDocId.substringAfterLast('/'),
            dirLastModified = rootSelf?.lastModified ?: 0L,
            relPath = "",
            depth = 0,
            parentDocId = null,
            parentName = null,
            parentLastModified = 0L
        )
        return found
    }

    suspend fun scanMods(dirUri: Uri): List<ModInfo> {
        return withContext(Dispatchers.IO) {
            val existingCache = loadModCache()
            val newCache = mutableMapOf<String, ModCacheInfo>()
            val tempModsList = mutableListOf<ModInfo>()
            val candidates = mutableListOf<ScannedModCandidate>()

            // 递归找出所有 mod（详见 discoverMods）。每个目录只查一次 ContentResolver，
            // 而且目录 mod 的文件名在遍历时就拿到了，不再需要逐个 mod 二次查询。
            for (mod in discoverMods(dirUri)) {
                val uriString = mod.uri.toString()

                // 已转换产物不走 resolver：它本身就是打包好的 bundle，目录名即目标 bundle 名，
                // 没有需要解析的散装资源。名字交给 BundleNameResolver 从索引反查。
                if (mod.kind == ModKind.CONVERTED_BUNDLE) {
                    val bundleName = mod.displayName.substringAfterLast('/')
                    val resolved = nameResolver.resolve(bundleName)
                    // 名字残缺的产物装进游戏也不会被加载（路径对不上），必须和"新角色查不到"
                    // 区分开，直接告诉用户去重新拷贝，而不是让他对着"未识别"猜。
                    val truncated = nameResolver.looksTruncated(bundleName)
                    tempModsList.add(
                        ModInfo(
                            name = nameResolver.displayName(bundleName),
                            character = if (truncated) "目录名不完整" else resolved.character,
                            costume = if (truncated) "装入不会生效，请重新拷贝" else resolved.costume,
                            type = if (resolved.matched) resolved.type else "已转换",
                            isEnabled = false,
                            uri = mod.uri,
                            targetHashedName = bundleName,
                            isDirectory = true,
                            resolutionState = if (truncated) ResolutionState.INVALID
                                              else ResolutionState.KNOWN,
                            targetHash = bundleName,
                            resolvedFamilyKey = resolved.assetKey?.let {
                                it.substringBeforeLast('.')
                            },
                            errorReason = if (truncated)
                                "bundle 目录名应为 32 位十六进制，实际只有 ${bundleName.length} 位。" +
                                "常见原因：用 adb 从含中文的路径 push（adb 会按 GBK 误解路径而截断名字）。" +
                                "改用不含中文的路径重新拷贝即可。"
                            else null,
                            kind = ModKind.CONVERTED_BUNDLE,
                            convertedHashDir = mod.hashDir,
                            convertedDataSize = mod.dataSize
                        )
                    )
                    continue
                }

                val cachedInfo = existingCache[uriString]

                if (cachedInfo != null && cachedInfo.lastModified == mod.lastModified) {
                    newCache[uriString] = cachedInfo
                    tempModsList.add(
                        ModInfo(
                            name = cachedInfo.name,
                            character = cachedInfo.character,
                            costume = cachedInfo.costume,
                            type = cachedInfo.type,
                            isEnabled = false,
                            uri = mod.uri,
                            targetHashedName = cachedInfo.targetHashedName,
                            isDirectory = cachedInfo.isDirectory,
                            resolutionState = cachedInfo.resolutionState,
                            targetHash = cachedInfo.targetHash,
                            resolvedFamilyKey = cachedInfo.resolvedFamilyKey,
                            unresolvedFiles = cachedInfo.unresolvedFiles,
                            errorReason = cachedInfo.errorReason
                        )
                    )
                } else {
                    val modDetails = if (mod.isDirectory) {
                        ModDetails(
                            mod.fileNames.firstNotNullOfOrNull { characterRepository.extractFileId(it) },
                            mod.fileNames
                        )
                    } else {
                        extractModDetailsFromUri(mod.uri)
                    }
                    candidates.add(
                        ScannedModCandidate(
                            uriString = uriString,
                            lastModified = mod.lastModified,
                            name = mod.displayName,
                            uri = mod.uri,
                            isDirectory = mod.isDirectory,
                            modDetails = modDetails
                        )
                    )
                }
            }

            if (candidates.isNotEmpty()) {
                if (!Python.isStarted()) {
                    Python.start(com.chaquo.python.android.AndroidPlatform(context))
                }

                val batchPayload = JSONArray().apply {
                    candidates.forEachIndexed { index, candidate ->
                        put(JSONObject().apply {
                            put("id", index)
                            put("fileNames", JSONArray(candidate.modDetails.fileNames))
                        })
                    }
                }

                val prefs = context.getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
                val selectedQuality = prefs.getString("selected_quality", "HD") ?: "HD"

                val (batchSuccess, batchResults) = ModdingService.resolveModBatch(
                    batchPayload.toString(),
                    context.filesDir.absolutePath,
                    selectedQuality
                ) { }

                val resultsById = mutableMapOf<Int, JSONObject>()
                if (batchSuccess && batchResults != null) {
                    for (i in 0 until batchResults.length()) {
                        val item = batchResults.optJSONObject(i) ?: continue
                        val id = item.optInt("id", -1)
                        val result = item.optJSONObject("result") ?: continue
                        if (id >= 0) resultsById[id] = result
                    }
                }

                candidates.forEachIndexed { index, candidate ->
                    val resolvePayload = resultsById[index] ?: buildResolverFallback(candidate.modDetails.fileNames)
                    val resolutionState = parseResolutionState(resolvePayload)
                    val targetHash = resolvePayload.optString("targetHash").ifBlank { null }
                    val resolvedFamilyKey = resolvePayload.optString("resolvedFamilyKey").ifBlank { null }
                    val unresolvedFiles = jsonArrayToStringList(resolvePayload.optJSONArray("unresolvedFiles"))
                    val errorReason = resolvePayload.optString("errorReason").ifBlank { null }
                    val resolvedTargets = parseResolvedTargets(resolvePayload.optJSONArray("resolvedTargets"))
                    val bestMatch = characterRepository.findBestMatch(candidate.modDetails.fileId, candidate.modDetails.fileNames)

                    val displayCharacter: String
                    val displayCostume: String
                    val displayType: String
                    when {
                        resolutionState == ResolutionState.INVALID -> {
                            displayCharacter = "Invalid Mod"
                            displayCostume = "Split Required"
                            displayType = "invalid"
                        }
                        resolutionState == ResolutionState.UNKNOWN -> {
                            displayCharacter = "Unknown"
                            displayCostume = "Unknown"
                            displayType = "unknown"
                        }
                        bestMatch != null -> {
                            displayCharacter = bestMatch.character
                            displayCostume = bestMatch.costume
                            displayType = bestMatch.type
                        }
                        else -> {
                            displayCharacter = "Other"
                            displayCostume = "Other"
                            displayType = "misc"
                        }
                    }

                    val resolvedHash = targetHash
                    val newCacheInfo = ModCacheInfo(
                        uriString = candidate.uriString,
                        lastModified = candidate.lastModified,
                        name = candidate.name,
                        character = displayCharacter,
                        costume = displayCostume,
                        type = displayType,
                        targetHashedName = resolvedHash,
                        isDirectory = candidate.isDirectory,
                        resolutionState = resolutionState,
                        targetHash = resolvedHash,
                        resolvedFamilyKey = resolvedFamilyKey,
                        unresolvedFiles = unresolvedFiles,
                        errorReason = errorReason
                    )
                    newCache[candidate.uriString] = newCacheInfo

                    tempModsList.add(
                        ModInfo(
                            name = candidate.name,
                            character = displayCharacter,
                            costume = displayCostume,
                            type = displayType,
                            isEnabled = false,
                            uri = candidate.uri,
                            targetHashedName = resolvedHash,
                            isDirectory = candidate.isDirectory,
                            resolutionState = resolutionState,
                            targetHash = resolvedHash,
                            resolvedFamilyKey = resolvedFamilyKey,
                            resolvedTargets = resolvedTargets,
                            unresolvedFiles = unresolvedFiles,
                            errorReason = errorReason
                        )
                    )
                }
            }

            saveModCache(newCache)
            tempModsList.sortedBy { it.name }
        }
    }

    private fun getModCacheFile(): File {
        return File(context.filesDir, MOD_CACHE_FILENAME)
    }

    private fun loadModCache(): Map<String, ModCacheInfo> {
        val cacheFile = getModCacheFile()
        if (!cacheFile.exists()) return emptyMap()

        val indexFile = File(context.filesDir, "local_bundle_index.json")
        if (indexFile.exists() && indexFile.lastModified() > cacheFile.lastModified()) {
            // Unify: If the bundle index updated, all mod cache entries are potentially stale
            return emptyMap()
        }

        return try {
            val json = cacheFile.readText()
            val type = object : TypeToken<Map<String, ModCacheInfo>>() {}.type
            gson.fromJson(json, type) ?: emptyMap()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyMap()
        }
    }

    private fun saveModCache(cache: Map<String, ModCacheInfo>) {
        try {
            val cacheFile = getModCacheFile()
            val json = gson.toJson(cache)
            cacheFile.writeText(json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun extractModDetailsFromUri(zipUri: Uri): ModDetails {
        val fileNames = mutableListOf<String>()
        var fileId: String? = null
        try {
            context.contentResolver.openInputStream(zipUri)?.use {
                ZipInputStream(it).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && !shouldIgnoreModEntry(entry.name)) {
                            fileNames.add(entry.name)
                            if (fileId == null) fileId = characterRepository.extractFileId(entry.name)
                        }
                        entry = zis.nextEntry
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return ModDetails(fileId, fileNames)
    }

    private fun buildResolverFallback(fileNames: List<String>): JSONObject {
        return JSONObject().apply {
            put("resolutionState", ResolutionState.UNKNOWN.name)
            put("errorReason", "Resolver failed")
            put("unresolvedFiles", JSONArray(fileNames))
            put("resolvedTargets", JSONArray())
        }
    }

    private fun parseResolutionState(payload: JSONObject): ResolutionState {
        return try {
            ResolutionState.valueOf(payload.optString("resolutionState", ResolutionState.UNKNOWN.name))
        } catch (_: Exception) {
            ResolutionState.UNKNOWN
        }
    }

    private fun jsonArrayToStringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                add(array.optString(i))
            }
        }
    }

    private fun parseResolvedTargets(array: JSONArray?): List<ResolvedTarget> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val candidates = jsonArrayToStringList(obj.optJSONArray("normalizedCandidates"))
                val strategy = try {
                    MatchStrategy.valueOf(obj.optString("matchStrategy", MatchStrategy.NONE.name))
                } catch (_: Exception) {
                    MatchStrategy.NONE
                }
                add(
                    ResolvedTarget(
                        originalFileName = obj.optString("originalFileName"),
                        normalizedCandidates = candidates,
                        resolvedAssetKey = obj.optString("resolvedAssetKey").ifBlank { null },
                        resolvedBundleName = obj.optString("resolvedBundleName").ifBlank { null },
                        resolvedBundlePath = obj.optString("resolvedBundlePath").ifBlank { null },
                        assetType = obj.optString("assetType").ifBlank { null },
                        targetHash = obj.optString("targetHash").ifBlank { null },
                        familyKey = obj.optString("familyKey").ifBlank { null },
                        matchStrategy = strategy,
                        confidence = obj.optDouble("confidence", 0.0).toFloat()
                    )
                )
            }
        }
    }
}
