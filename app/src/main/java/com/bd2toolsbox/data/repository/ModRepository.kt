package com.bd2toolsbox.data.repository

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.bd2toolsbox.core.fileaccess.SafeArchivePaths
import com.bd2toolsbox.core.python.PythonRuntime
import com.bd2toolsbox.data.model.MatchStrategy
import com.bd2toolsbox.data.model.ModCacheInfo
import com.bd2toolsbox.data.model.ModDetails
import com.bd2toolsbox.data.model.ModInfo
import com.bd2toolsbox.data.model.ModKind
import com.bd2toolsbox.data.model.ResolutionState
import com.bd2toolsbox.data.model.ResolvedTarget
import com.bd2toolsbox.core.engine.ModEngine
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * mod 源目录的扫描与识别。
 *
 * 职责是把一个 SAF 目录树里的 mod 找出来（[discoverMods]），并把每个 mod
 * 的文件名交给 python 侧的 resolver 解析成「目标 bundle」（[scanMods]）。
 * 解析结果带磁盘缓存（mod_cache.json）：索引戳、目录时间及子文件签名都未变，
 * 且上次已有匹配结果时才复用；未识别条目在重扫时重试。
 *
 * 缓存何时整体作废：mod → bundle 的映射来自 local_bundle_index.json（游戏
 * 目录扫描的产物），游戏更新后映射会变。因此缓存里记录着建缓存时的索引
 * 戳（文件 mtime），索引戳对不上就重解析 —— 精确到「索引真的变了」，
 * 不会因为无关的文件写入而误伤。
 *
 * 目录里能识别出两类东西（详见 [discoverMods]）：
 *  - **PC mod 源文件**：目录直接躺着 skel/json/atlas/png 的就是一个 mod，
 *    不再往下钻；zip 任意层级都算
 *  - **已转换的安卓产物**：结构 <bundle 名>/<hash>/__data，看到 __data 时
 *    回溯认定父目录是一个产物条目
 */
class ModRepository(
    private val context: Context,
    private val characterRepository: CharacterRepository
) {

    private val nameResolver = BundleNameResolver(context)

    companion object {
        private const val TAG = "ModRepository"

        /** mod 解析缓存的文件名与结构版本。 */
        private const val MOD_CACHE_FILENAME = "mod_cache.json"
        private const val MOD_CACHE_SCHEMA = 2

        /**
         * 递归查找 mod 的最大深度（相对所选目录）。实测用户收藏里有 mod 位于
         * 第 6 层且目录还在整理中，留余量；真正的兜底是 [MAX_DIRS_VISITED]。
         */
        private const val MAX_SCAN_DEPTH = 8

        /** 防止误扫整部手机；触及边界必须报告未完成，不能当作完整结果。 */
        private const val MAX_DIRS_VISITED = 15000

        /** 目录里直接躺着这些后缀的文件，就认定这个目录本身是一个 mod。 */
        private val MOD_ASSET_EXTENSIONS = setOf("skel", "json", "atlas", "png", "jpg", "jpeg")
    }

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private val modCacheSerializer = MapSerializer(String.serializer(), ModCacheInfo.serializer())

    // ---------------------------------------------------------------- 数据形状

    /** 扫描未完成时不提交残缺结果，由调用方保留原列表并提供重试。 */
    class IncompleteScanException(message: String, cause: Throwable? = null) : IOException(message, cause)

    /** 一次扫描中需要交给 python 解析的候选。 */
    private data class PendingResolve(
        val uriString: String,
        val lastModified: Long,
        val name: String,
        val uri: Uri,
        val isDirectory: Boolean,
        val modDetails: ModDetails,
        val sourceSignature: String
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
     * [displayName] 带相对路径（如 `合集A/levia_idle`），不同子目录下的同名 mod 不会混淆。
     * [fileNames] 是目录 mod 的直接子文件名，扫描时顺手带出来省掉重复查询；zip 为空。
     */
    private data class DiscoveredMod(
        val uri: Uri,
        val displayName: String,
        val lastModified: Long,
        val isDirectory: Boolean,
        val fileNames: List<String>,
        val sourceSignature: String = "",
        val kind: ModKind = ModKind.PC_SOURCE,
        /** 仅已转换产物：hash 目录名 */
        val hashDir: String? = null,
        /** 仅已转换产物：__data 字节数 */
        val dataSize: Long = 0L,
        /** 仅已转换产物：__data 的 document uri，扫描期文件头校验直接开流用 */
        val dataUri: Uri? = null
    )

    // ---------------------------------------------------------------- SAF 遍历

    private fun shouldIgnoreModEntry(entryName: String?): Boolean {
        val name = entryName?.substringAfterLast('/')?.trim()?.lowercase() ?: return true
        // T62：`.nomedia` 媒体扫描隔离标记与编辑器备份文件不参与 MOD 枚举/签名/重打包输入。
        return name.isEmpty() || name == ".modfile" || name.endsWith(".modfile") ||
            name.endsWith(".png~") || name.endsWith(".jsonbk") || name.endsWith(".skelbk") ||
            name == "desktop.ini" ||
            com.bd2toolsbox.core.storage.MediaExclusionManager.shouldSkipInModScan(name)
    }

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
            val cursorResult = context.contentResolver.query(childrenUri, projection, null, null, null)
                ?: throw IncompleteScanException("目录读取失败，请检查文件夹权限后重试")
            cursorResult.use { cursor ->
                val extras = cursor.extras
                if (extras.getBoolean(DocumentsContract.EXTRA_LOADING, false) ||
                    !extras.getString(DocumentsContract.EXTRA_ERROR).isNullOrBlank()
                ) {
                    throw IncompleteScanException("文件夹尚未读完，请稍后重试")
                }
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
            throw IncompleteScanException("目录读取失败，请检查文件夹权限后重试", e)
        }
        return out
    }

    /** 用已枚举的子文件名、大小和时间戳判定变化，不依赖父目录 mtime。 */
    private fun sourceSignature(files: List<ChildDoc>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        files.sortedBy { it.name }.forEach { file ->
            val text = "${file.name.length}:${file.name}:${file.size}:${file.lastModified}\n"
            digest.update(text.toByteArray(Charsets.UTF_8))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 查询某个 document 自身的名字与修改时间（所选根目录本身就是一个 mod 的情况）。 */
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
                ChildDoc(
                    docId = docId,
                    name = cursor.getString(0) ?: docId.substringAfterLast('/'),
                    isDirectory = cursor.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR,
                    lastModified = cursor.getLong(2)
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 从所选目录往下找出所有 mod。
     *
     * 「选一个大文件夹，里面每个小文件夹是一个 mod」「大文件夹 → 合集 → mod」
     * 「直接选中单个 mod 文件夹」「选中一整个 Shared 产物目录」都能识别。
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
            if (visited >= MAX_DIRS_VISITED) {
                throw IncompleteScanException("目录数量超过扫描上限，请分开添加子文件夹")
            }
            visited++

            val children = listChildren(treeRootUri, docId)
            val files = children.filter { !it.isDirectory }

            // 已转换产物：当前目录直接躺着 __data → 这层是 hash 目录、父目录才是 bundle
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
                            dataSize = dataFile.size,
                            dataUri = DocumentsContract.buildDocumentUriUsingTree(treeRootUri, dataFile.docId)
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
                        fileNames = emptyList(),
                        sourceSignature = sourceSignature(listOf(zip))
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
                        fileNames = ownFileNames,
                        sourceSignature = sourceSignature(files.filterNot { shouldIgnoreModEntry(it.name) })
                    )
                )
                return      // mod 目录里的 .old 备份等子目录天然被跳过
            }

            // 好感度 / 亲密度 / 壁纸 Mod 结构：顶层放 <Target>.modfile，贴图放在 textures/ 子目录。
            // 将当前目录整体识别为一个 Mod（避免钻入 textures/ 丢失目录名与 .modfile 目标信息）。
            val modfileStems = files.mapNotNull { f ->
                val low = f.name.trim().lowercase()
                if (low.endsWith(".modfile") && low != ".modfile") low.removeSuffix(".modfile") else null
            }
            val texturesDir = children.firstOrNull { it.isDirectory && it.name.equals("textures", ignoreCase = true) }
            if (modfileStems.isNotEmpty() && texturesDir != null) {
                val texChildren = listChildren(treeRootUri, texturesDir.docId)
                val texFiles = texChildren.filter { !it.isDirectory && !shouldIgnoreModEntry(it.name) && isModAssetFile(it.name) }
                if (texFiles.isNotEmpty()) {
                    val texNames = texFiles.map { it.name }
                    val primaryStem = modfileStems.first()
                    val combinedNames = if (
                        primaryStem.endsWith("_atlas_eventpackgui") ||
                        primaryStem.matches(Regex("^pack\\d+_title\\d+$"))
                    ) {
                        listOf("$primaryStem.png")
                    } else {
                        buildList {
                            addAll(texNames)
                            if (texNames.none { it.startsWith("specialillust", ignoreCase = true) || it.startsWith("sepcialillust", ignoreCase = true) }) {
                                modfileStems.forEach { add("$it.png") }
                            }
                        }
                    }
                    found.add(
                        DiscoveredMod(
                            uri = DocumentsContract.buildDocumentUriUsingTree(treeRootUri, docId),
                            displayName = relPath.ifEmpty { dirName },
                            lastModified = maxOf(dirLastModified, texturesDir.lastModified),
                            isDirectory = true,
                            fileNames = combinedNames,
                            sourceSignature = sourceSignature(files + texFiles)
                        )
                    )
                    return
                }
            }

            val subdirectories = children.filter { it.isDirectory && !it.name.startsWith(".") }
            if (depth >= MAX_SCAN_DEPTH && subdirectories.isNotEmpty()) {
                throw IncompleteScanException("文件夹层级过深，请直接添加内层文件夹")
            }
            subdirectories.forEach { sub ->
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

    // ---------------------------------------------------------------- 扫描 + 解析

    /**
     * 扫描一个目录：发现 mod → 命中缓存的直接复用，其余批量交给 python 解析。
     *
     * 返回的列表按名称排序。缓存写回是全量的（本目录扫到的 + 其他目录仍
     * 有效的……不，这里只写本目录扫到的 —— 多目录的合并在 MainViewModel 层）。
     */
    suspend fun scanMods(dirUri: Uri): List<ModInfo> = withContext(Dispatchers.IO) {
        val indexStamp = currentIndexStamp()
        val cached = loadModCache()
        nameResolver.invalidate()
        val newCache = mutableMapOf<String, ModCacheInfo>()
        val results = mutableListOf<ModInfo>()
        val pending = mutableListOf<PendingResolve>()

        // 其他源目录的缓存条目原样保留：它们的目录会在各自的 scanMods 里刷新。
        // 旧实现只写当前目录的条目，多目录时每次扫描都把别的目录冲掉 —— 下次
        // 进入全部 miss、整轮重解析，就是「列表不能持久化」的主根因。
        val treeDocId = DocumentsContract.getTreeDocumentId(dirUri)
        val thisTreePrefix = "content://${dirUri.authority}/tree/${Uri.encode(treeDocId)}/"
        cached.entries.forEach { (uriString, info) ->
            if (!uriString.startsWith(thisTreePrefix)) {
                newCache[uriString] = info
            }
        }

        for (mod in discoverMods(dirUri)) {
            val uriString = mod.uri.toString()

            // 已转换产物不走 resolver：目录名即 bundle 名，名字由 BundleNameResolver 反查
            if (mod.kind == ModKind.CONVERTED_BUNDLE) {
                val bundleName = mod.displayName.substringAfterLast('/')
                val resolved = nameResolver.resolve(bundleName)
                // 名字残缺的产物装进游戏也不会被加载（路径对不上），必须和
                // 「新角色查不到」区分开，直接告诉用户去重新拷贝
                val truncated = nameResolver.looksTruncated(bundleName)
                results.add(
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
                        resolvedFamilyKey = resolved.assetKey?.let { it.substringBeforeLast('.') },
                        errorReason = if (truncated)
                            "bundle 目录名应为 32 位十六进制，实际只有 ${bundleName.length} 位。" +
                                "常见原因：用 adb 从含中文的路径 push（adb 会按 GBK 误解路径而截断名字）。" +
                                "改用不含中文的路径重新拷贝即可。"
                        else null,
                        kind = ModKind.CONVERTED_BUNDLE,
                        convertedHashDir = mod.hashDir,
                        convertedDataSize = mod.dataSize,
                        convertedDataUri = mod.dataUri?.toString()
                    )
                )
                continue
            }

            val hit = cached.entries[uriString]
            if (hit != null && hit.lastModified == mod.lastModified &&
                hit.sourceSignature == mod.sourceSignature &&
                hit.resolutionState != ResolutionState.UNKNOWN
            ) {
                newCache[uriString] = hit
                results.add(
                    ModInfo(
                        name = hit.name,
                        character = hit.character,
                        costume = hit.costume,
                        type = hit.type,
                        isEnabled = false,
                        uri = mod.uri,
                        targetHashedName = hit.targetHashedName,
                        isDirectory = hit.isDirectory,
                        resolutionState = hit.resolutionState,
                        targetHash = hit.targetHash,
                        resolvedFamilyKey = hit.resolvedFamilyKey,
                        resolvedTargets = hit.resolvedTargets.orEmpty(),
                        unresolvedFiles = hit.unresolvedFiles.orEmpty(),
                        errorReason = hit.errorReason
                    )
                )
            } else {
                val details = if (mod.isDirectory) {
                    ModDetails(
                        mod.fileNames.firstNotNullOfOrNull { characterRepository.extractFileId(it) },
                        mod.fileNames
                    )
                } else {
                    extractModDetailsFromUri(mod.uri)
                }
                pending.add(
                    PendingResolve(
                        uriString = uriString,
                        lastModified = mod.lastModified,
                        name = mod.displayName,
                        uri = mod.uri,
                        isDirectory = mod.isDirectory,
                        modDetails = details,
                        sourceSignature = mod.sourceSignature
                    )
                )
            }
        }

        if (pending.isNotEmpty()) {
            resolvePending(pending, newCache, results)
        }

        saveModCache(newCache, indexStamp)
        results.sortedBy { it.name }
    }

    /** 把待解析的候选批量交给 python resolver，结果写回缓存与列表。 */
    private fun resolvePending(
        pending: List<PendingResolve>,
        intoCache: MutableMap<String, ModCacheInfo>,
        intoResults: MutableList<ModInfo>
    ) {
        PythonRuntime.ensureStarted(context)

        val payload = JSONArray().apply {
            pending.forEachIndexed { index, candidate ->
                put(JSONObject().apply {
                    put("id", index)
                    put("fileNames", JSONArray(candidate.modDetails.fileNames))
                })
            }
        }

        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val quality = prefs.getString("selected_quality", "HD") ?: "HD"

        val (batchSuccess, batchResults) = ModEngine.get().resolveModBatch(
            payload.toString(),
            context.filesDir.absolutePath,
            quality
        ) { }

        val byId = mutableMapOf<Int, JSONObject>()
        if (batchSuccess && batchResults != null) {
            for (i in 0 until batchResults.length()) {
                val item = batchResults.optJSONObject(i) ?: continue
                val id = item.optInt("id", -1)
                val result = item.optJSONObject("result") ?: continue
                if (id >= 0) byId[id] = result
            }
        }

        pending.forEachIndexed { index, candidate ->
            val resolved = byId[index] ?: resolverFallback(candidate.modDetails.fileNames)
            val state = parseResolutionState(resolved)
            val targetHash = resolved.optString("targetHash").ifBlank { null }
            val familyKey = resolved.optString("resolvedFamilyKey").ifBlank { null }
            val unresolved = jsonArrayToStringList(resolved.optJSONArray("unresolvedFiles"))
            val errorReason = resolved.optString("errorReason").ifBlank { null }
            val resolvedTargets = parseResolvedTargets(resolved.optJSONArray("resolvedTargets"))
            val bestMatch = characterRepository.findBestMatch(
                candidate.modDetails.fileId, candidate.modDetails.fileNames
            ) ?: characterRepository.findMatchByDisplayName(candidate.name)

            val character: String
            val costume: String
            val type: String
            when {
                state == ResolutionState.INVALID -> {
                    character = "Invalid Mod"; costume = "Split Required"; type = "invalid"
                }
                state == ResolutionState.UNKNOWN -> {
                    character = "Unknown"; costume = "Unknown"; type = "unknown"
                }
                bestMatch != null -> {
                    character = bestMatch.character; costume = bestMatch.costume; type = bestMatch.type
                }
                else -> {
                    character = "Other"; costume = "Other"; type = "misc"
                }
            }

            intoCache[candidate.uriString] = ModCacheInfo(
                uriString = candidate.uriString,
                lastModified = candidate.lastModified,
                name = candidate.name,
                character = character,
                costume = costume,
                type = type,
                targetHashedName = targetHash,
                isDirectory = candidate.isDirectory,
                resolutionState = state,
                targetHash = targetHash,
                resolvedFamilyKey = familyKey,
                unresolvedFiles = unresolved,
                errorReason = errorReason,
                sourceSignature = candidate.sourceSignature,
                resolvedTargets = resolvedTargets
            )

            intoResults.add(
                ModInfo(
                    name = candidate.name,
                    character = character,
                    costume = costume,
                    type = type,
                    isEnabled = false,
                    uri = candidate.uri,
                    targetHashedName = targetHash,
                    isDirectory = candidate.isDirectory,
                    resolutionState = state,
                    targetHash = targetHash,
                    resolvedFamilyKey = familyKey,
                    resolvedTargets = resolvedTargets,
                    unresolvedFiles = unresolved,
                    errorReason = errorReason
                )
            )
        }
    }

    private fun resolverFallback(fileNames: List<String>): JSONObject =
        JSONObject().apply {
            put("resolutionState", ResolutionState.UNKNOWN.name)
            put("errorReason", "Resolver failed")
            put("unresolvedFiles", JSONArray(fileNames))
            put("resolvedTargets", JSONArray())
        }

    // ---------------------------------------------------------------- 缓存

    /** 磁盘缓存的包装：索引戳 + 条目表。旧格式（裸 map）读不进来，自动重建。 */
    private data class ModCache(val indexStamp: Long, val entries: Map<String, ModCacheInfo>)

    private fun modCacheFile(): File = File(context.filesDir, MOD_CACHE_FILENAME)

    /** 本地 bundle 索引的戳：索引不存在记 0。索引变了（游戏更新重扫）映射就可能变。 */
    private fun currentIndexStamp(): Long =
        File(context.filesDir, "local_bundle_index.json").let {
            if (it.exists()) it.lastModified() else 0L
        }

    private fun loadModCache(): ModCache {
        val file = modCacheFile()
        if (!file.exists()) return ModCache(0L, emptyMap())
        return try {
            val root = Json.parseToJsonElement(file.readText()) as? JsonObject
                ?: return ModCache(0L, emptyMap())
            val schema = (root["schemaVersion"] as? JsonPrimitive)?.takeIf { !it.isString }
                ?.content?.toIntOrNull()
            if (schema != MOD_CACHE_SCHEMA) {
                return ModCache(0L, emptyMap())
            }
            val stamp = (root["indexStamp"] as? JsonPrimitive)?.takeIf { !it.isString }
                ?.content?.toLongOrNull() ?: 0L
            if (stamp != currentIndexStamp()) return ModCache(0L, emptyMap())
            val entries = root["entries"] as? JsonObject ?: return ModCache(0L, emptyMap())
            ModCache(stamp, json.decodeFromJsonElement(modCacheSerializer, entries))
        } catch (e: Exception) {
            e.printStackTrace()
            ModCache(0L, emptyMap())
        }
    }

    private fun saveModCache(cache: Map<String, ModCacheInfo>, indexStamp: Long) {
        try {
            val root = buildJsonObject {
                put("schemaVersion", MOD_CACHE_SCHEMA)
                put("indexStamp", indexStamp)
                put("entries", json.encodeToJsonElement(modCacheSerializer, cache))
            }
            val text = root.toString()
            val tmp = File(context.filesDir, MOD_CACHE_FILENAME + ".part")
            tmp.writeText(text)
            if (!tmp.renameTo(modCacheFile())) {
                // 极少数文件系统 rename 失败，退回直写
                modCacheFile().writeText(text)
                tmp.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ---------------------------------------------------------------- zip 细节

    private fun extractModDetailsFromUri(zipUri: Uri): ModDetails {
        val fileNames = mutableListOf<String>()
        val modfileStems = mutableListOf<String>()
        var fileId: String? = null
        try {
            context.contentResolver.openInputStream(zipUri)?.use {
                ZipInputStream(it).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val leaf = entry.name.substringAfterLast('/').trim().lowercase()
                        if (!entry.isDirectory && leaf.endsWith(".modfile") && leaf != ".modfile") {
                            if (SafeArchivePaths.entryNameRejectionReason(entry.name) == null) {
                                modfileStems.add(leaf.removeSuffix(".modfile"))
                            }
                        } else if (!entry.isDirectory && !shouldIgnoreModEntry(entry.name)) {
                            val reason = SafeArchivePaths.entryNameRejectionReason(entry.name)
                            if (reason == null) {
                                fileNames.add(entry.name)
                                if (fileId == null) {
                                    fileId = characterRepository.extractFileId(entry.name)
                                }
                            } else {
                                Log.w(
                                    TAG,
                                    "扫描 mod 时跳过不安全的归档条目名" +
                                        "${SafeArchivePaths.describeForLog(entry.name)}：$reason" +
                                        "（不参与 bundle 判定；该条目不会进入导入清单）"
                                )
                            }
                        }
                        entry = zis.nextEntry
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (modfileStems.isNotEmpty() && fileId == null) {
            val primaryStem = modfileStems.first()
            if (primaryStem.endsWith("_atlas_eventpackgui") || primaryStem.matches(Regex("^pack\\d+_title\\d+$"))) {
                fileNames.clear()
                fileNames.add("$primaryStem.png")
            } else {
                modfileStems.forEach { fileNames.add("$it.png") }
            }
            fileId = modfileStems.firstNotNullOfOrNull { characterRepository.extractFileId(it) }
        }
        return ModDetails(fileId, fileNames)
    }

    // ---------------------------------------------------------------- JSON 辅助

    private fun parseResolutionState(payload: JSONObject): ResolutionState = try {
        ResolutionState.valueOf(payload.optString("resolutionState", ResolutionState.UNKNOWN.name))
    } catch (_: Exception) {
        ResolutionState.UNKNOWN
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
