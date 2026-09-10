package com.bd2toolsbox.service

import com.chaquo.python.PyObject
import com.chaquo.python.Python
import org.json.JSONArray
import org.json.JSONObject

/**
 * Kotlin ↔ python（main_script）的调用桥。
 *
 * python 侧的入口都返回 (bool, ...) 元组、进度走回调 —— 这里把「取模块、
 * 调函数、解元组、兜异常」的样板收进 [callMain]，每个入口只剩自己的
 * 参数表与返回值装配。
 */
object ModdingService {

    /** 调 main_script 的一个入口，返回结果列表；异常时返回 null。 */
    private fun callMain(function: String, vararg args: Any?): List<PyObject>? = try {
        Python.getInstance()
            .getModule("main_script")
            .callAttr(function, *args)
            .asList()
            .toList()
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }

    private fun progressAdapter(onProgress: (String) -> Unit): PyObject =
        PyObject.fromJava(onProgress)

    // ---------------------------------------------------------------- 下载与转换

    fun downloadBundle(hashedName: String, quality: String, outputDir: String,
                       cacheKey: String, onProgress: (String) -> Unit): Pair<Boolean, String> {
        val r = callMain("download_bundle", hashedName, quality, outputDir, cacheKey,
                         progressAdapter(onProgress))
            ?: return Pair(false, "An unknown error occurred in Kotlin during download.")
        return r[0].toBoolean() to r[1].toString()
    }

    fun repackBundle(originalBundlePath: String, moddedAssetsFolder: String,
                     outputPath: String, useAstc: Boolean,
                     onProgress: (String) -> Unit): Pair<Boolean, String> {
        val r = callMain("main", originalBundlePath, moddedAssetsFolder, outputPath,
                         useAstc, progressAdapter(onProgress))
            ?: return Pair(false, "An unknown error occurred in Kotlin.")
        return r[0].toBoolean() to r[1].toString()
    }

    /**
     * 取 catalog 里每个 bundle 的官方原版大小与内容哈希。
     *
     * 返回 bundle 名 -> (原版字节数, 内容哈希)。与 ShizukuManager.listGameBundles
     * 的本地实际值比对即可客观判断某 bundle 是干净原版还是装着 mod。
     * 离线或 catalog 不可用时返回 null。
     */
    fun getBundleMeta(outputDir: String, quality: String,
                      onProgress: (String) -> Unit): Pair<Map<String, Pair<Long, String>>, Boolean>? {
        val r = callMain("get_bundle_meta", outputDir, quality, progressAdapter(onProgress))
            ?: return null
        if (!r[0].toBoolean()) {
            onProgress("获取 bundle 元数据失败：${r[1]}")
            return null
        }
        val meta = r.getOrNull(2) ?: return null
        if (meta.toString() == "None") return null
        // python 离线降级时 r[1] 是 "degraded"：表来自磁盘缓存、可能不是最新版
        val degraded = r.getOrNull(1)?.toString() == "degraded"

        val map = buildMap {
            for ((k, v) in meta.asMap()) {
                val name = k?.toString() ?: continue
                val pair = v?.asList() ?: continue
                if (pair.size >= 2) {
                    put(name, pair[0].toLong() to pair[1].toString())
                }
            }
        }
        return map to degraded
    }

    /**
     * 取「bundle 目录名(hex) → (角色 file_id, 槽位)」这张推导表。
     *
     * catalog 的 download_key 里带着角色号（isolated-cutscene000707-group_…），
     * 而 UnityCache 的目录名就是 catalog 登记的 bundle 名，所以光凭一份 CDN
     * catalog 就能把裸 hash 还原成 char000707。给已转换产物命名兜底：不依赖
     * 游戏目录、也不依赖「扫描游戏资源」。
     *
     * 槽位与 characters.json 同套写法（cutscene / idle）。离线且磁盘上没有
     * 缓存表时返回 null（调用方退回显示 hash）。
     */
    fun getBundleHints(outputDir: String, quality: String,
                       onProgress: (String) -> Unit): Map<String, Pair<String, String>>? {
        val r = callMain("get_bundle_hints", outputDir, quality, progressAdapter(onProgress))
            ?: return null
        if (!r[0].toBoolean()) {
            onProgress("获取 bundle 命名提示失败：${r[1]}")
            return null
        }
        val hints = r.getOrNull(2) ?: return null
        if (hints.toString() == "None") return null

        return buildMap {
            for ((k, v) in hints.asMap()) {
                val name = k?.toString() ?: continue
                val pair = v?.asList() ?: continue
                if (pair.size >= 2) put(name, pair[0].toString() to pair[1].toString())
            }
        }
    }

    /**
     * 把官方原版 bundle 转成游戏缓存格式（卸载 mod 用）。
     *
     * 不能直接拷 CDN 下载的文件：那是压缩包，与 UnityCache 里的 __data 编码不同，
     * 游戏读不了。也不能借 repackBundle —— 它没有资源被替换时会直接判失败。
     */
    fun restoreBundle(originalBundlePath: String, outputPath: String,
                      onProgress: (String) -> Unit): Pair<Boolean, String> {
        val r = callMain("restore_bundle", originalBundlePath, outputPath,
                         progressAdapter(onProgress))
            ?: return Pair(false, "restore_bundle 调用失败")
        return r[0].toBoolean() to r[1].toString()
    }

    /**
     * 解包 bundle。
     *
     * @param fast 预览用：只导出 png/atlas/skel，PNG 低压缩写盘，明显更快。
     *             解包工具应传 false，保持完整导出与原有压缩率。
     */
    fun unpackBundle(bundlePath: String, outputDir: String, fast: Boolean = false,
                     onProgress: (String) -> Unit): Pair<Boolean, String> {
        val r = callMain("unpack_bundle", bundlePath, outputDir, progressAdapter(onProgress), fast)
            ?: return Pair(false, "An unknown error occurred in Kotlin during unpack.")
        return r[0].toBoolean() to r[1].toString()
    }

    /**
     * 装入前完整校验一个产物 __data（外来预转换产物用）。
     *
     * python 侧分两层：UnityFS 头部快检 + UnityPy 完整加载（块解压、对象表解析）。
     * 只认文件路径 —— SAF 里的产物由调用方先中转到 app 私有目录再传进来。
     */
    fun validateBundle(bundlePath: String): Pair<Boolean, String> {
        val r = callMain("validate_bundle", bundlePath)
            ?: return Pair(false, "validate_bundle 调用失败")
        return r[0].toBoolean() to r[1].toString()
    }

    // ---------------------------------------------------------------- mod 解析

    fun resolveModFiles(fileNamesJson: String, outputDir: String, quality: String,
                        onProgress: (String) -> Unit): Pair<Boolean, JSONObject?> {
        val r = callMain("resolve_mod_files", fileNamesJson, outputDir, quality,
                         progressAdapter(onProgress))
            ?: return Pair(false, null)
        val success = r[0].toBoolean()
        return success to success.takeIf { it }?.let { JSONObject(r[1].toString()) }
    }

    fun resolveModBatch(modsJson: String, outputDir: String, quality: String,
                        onProgress: (String) -> Unit): Pair<Boolean, JSONArray?> {
        val r = callMain("resolve_mod_batch", modsJson, outputDir, quality,
                         progressAdapter(onProgress))
            ?: return Pair(false, null)
        val success = r[0].toBoolean()
        return success to success.takeIf { it }?.let { JSONArray(r[1].toString()) }
    }

    fun mergeSpineAssets(modPath: String, onProgress: (String) -> Unit): Pair<Boolean, String> {
        val r = callMain("merge_spine_assets", modPath, progressAdapter(onProgress))
            ?: return Pair(false, "An unknown error occurred in Kotlin during spine merge.")
        return r[0].toBoolean() to r[1].toString()
    }

    // ---------------------------------------------------------------- 本地扫描（三步 API）

    /** Step 1：待扫 bundle 名单（JSON 数组串）；异常返回空名单。 */
    fun checkScanNeeded(outputDir: String, bundleListJson: String,
                        onProgress: (String) -> Unit): String =
        callMain("check_scan_needed", outputDir, bundleListJson, progressAdapter(onProgress))
            ?.firstOrNull()?.toString() ?: "[]"

    /** Step 2：扫一个临时路径上的 __data。返回 (成功, 资产数, 消息)。 */
    fun scanSingleBundle(bundleName: String, bundleHash: String, tempDataPath: String,
                         onProgress: (String) -> Unit): Triple<Boolean, Int, String> {
        val r = callMain("scan_single_bundle", bundleName, bundleHash, tempDataPath,
                         progressAdapter(onProgress))
            ?: return Triple(false, 0, "Unknown error during bundle scan.")
        return Triple(r[0].toBoolean(), r[1].toInt(), r[2].toString())
    }

    /** Step 3：合并缓存与新扫结果、索引落盘。返回 (成功, 消息)。 */
    fun finalizeScan(outputDir: String, onProgress: (String) -> Unit): Pair<Boolean, String> {
        val r = callMain("finalize_scan", outputDir, progressAdapter(onProgress))
            ?: return Pair(false, "Unknown error during scan finalization.")
        return r[0].toBoolean() to r[1].toString()
    }
}
