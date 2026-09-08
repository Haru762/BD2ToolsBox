
package com.example.bd2modmanager.service

import com.chaquo.python.PyObject
import com.chaquo.python.Python
import org.json.JSONArray
import org.json.JSONObject

object ModdingService {

    fun downloadBundle(hashedName: String, quality: String, outputDir: String, cacheKey: String, onProgress: (String) -> Unit): Pair<Boolean, String> {
        return try {
            val py = Python.getInstance()
            val mainScript = py.getModule("main_script")

            val result = mainScript.callAttr(
                "download_bundle",
                hashedName,
                quality,
                outputDir,
                cacheKey,
                PyObject.fromJava(onProgress)
            ).asList()

            val success = result[0].toBoolean()
            val messageOrPath = result[1].toString()
            Pair(success, messageOrPath)
        } catch (e: Exception) {
            e.printStackTrace()
            Pair(false, e.message ?: "An unknown error occurred in Kotlin during download.")
        }
    }

    fun repackBundle(originalBundlePath: String, moddedAssetsFolder: String, outputPath: String, useAstc: Boolean, onProgress: (String) -> Unit): Pair<Boolean, String> {
        return try {
            val py = Python.getInstance()
            val mainScript = py.getModule("main_script")

            // Pass the Kotlin lambda as a progress_callback to the Python function.
            val result = mainScript.callAttr(
                "main",
                originalBundlePath,
                moddedAssetsFolder,
                outputPath,
                useAstc,
                PyObject.fromJava(onProgress)
            ).asList()

            val success = result[0].toBoolean()
            val message = result[1].toString()
            Pair(success, message)
        } catch (e: Exception) {
            e.printStackTrace()
            Pair(false, e.message ?: "An unknown error occurred in Kotlin.")
        }
    }

    /**
     * 取 catalog 里每个 bundle 的官方原版大小与内容哈希。
     *
     * 返回 bundle 名 -> (原版字节数, 内容哈希)。与 [ShizukuManager.listGameBundles]
     * 的本地实际值比对即可客观判断某 bundle 是干净原版还是装着 mod。
     * 离线或 catalog 不可用时返回 null。
     */
    fun getBundleMeta(outputDir: String, quality: String, onProgress: (String) -> Unit):
            Map<String, Pair<Long, String>>? {
        return try {
            val py = Python.getInstance()
            val mainScript = py.getModule("main_script")

            val result = mainScript.callAttr(
                "get_bundle_meta",
                outputDir,
                quality,
                PyObject.fromJava(onProgress)
            ).asList()

            if (!result[0].toBoolean()) {
                onProgress("获取 bundle 元数据失败：${result[1]}")
                return null
            }
            val metaObj = result[2]
            if (metaObj == null || metaObj.toString() == "None") return null

            val out = HashMap<String, Pair<Long, String>>()
            for ((k, v) in metaObj.asMap()) {
                val name = k?.toString() ?: continue
                val pair = v?.asList() ?: continue
                if (pair.size < 2) continue
                out[name] = pair[0].toLong() to pair[1].toString()
            }
            out
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 把官方原版 bundle 转成游戏缓存格式（卸载 mod 用）。
     *
     * 不能直接拷 CDN 下载的文件：那是压缩包，与 UnityCache 里的 __data 编码不同，
     * 游戏读不了。也不能借 repackBundle —— 它没有资源被替换时会直接判失败。
     */
    fun restoreBundle(originalBundlePath: String, outputPath: String, onProgress: (String) -> Unit): Pair<Boolean, String> {
        return try {
            val py = Python.getInstance()
            val mainScript = py.getModule("main_script")

            val result = mainScript.callAttr(
                "restore_bundle",
                originalBundlePath,
                outputPath,
                PyObject.fromJava(onProgress)
            ).asList()

            Pair(result[0].toBoolean(), result[1].toString())
        } catch (e: Exception) {
            e.printStackTrace()
            Pair(false, e.message ?: "restore_bundle 调用失败")
        }
    }

    /**
     * 解包 bundle。
     *
     * @param fast 预览用：只导出 png/atlas/skel，PNG 低压缩写盘，明显更快。
     *             解包工具应传 false，保持完整导出与原有压缩率。
     */
    fun unpackBundle(bundlePath: String, outputDir: String, fast: Boolean = false, onProgress: (String) -> Unit): Pair<Boolean, String> {
        return try {
            val py = Python.getInstance()
            val mainScript = py.getModule("main_script")

            val result = mainScript.callAttr(
                "unpack_bundle",
                bundlePath,
                outputDir,
                PyObject.fromJava(onProgress),
                fast
            ).asList()

            val success = result[0].toBoolean()
            val message = result[1].toString()
            Pair(success, message)
        } catch (e: Exception) {
            e.printStackTrace()
            Pair(false, e.message ?: "An unknown error occurred in Kotlin during unpack.")
        }
    }

    fun resolveModFiles(fileNamesJson: String, outputDir: String, quality: String, onProgress: (String) -> Unit): Pair<Boolean, JSONObject?> {
        return try {
            val py = Python.getInstance()
            val mainScript = py.getModule("main_script")

            val result = mainScript.callAttr(
                "resolve_mod_files",
                fileNamesJson,
                outputDir,
                quality,
                PyObject.fromJava(onProgress)
            ).asList()

            val success = result[0].toBoolean()
            val payload = result[1].toString()
            Pair(success, if (success) JSONObject(payload) else null)
        } catch (e: Exception) {
            e.printStackTrace()
            Pair(false, null)
        }
    }

    fun resolveModBatch(modsJson: String, outputDir: String, quality: String, onProgress: (String) -> Unit): Pair<Boolean, JSONArray?> {
        return try {
            val py = Python.getInstance()
            val mainScript = py.getModule("main_script")

            val result = mainScript.callAttr(
                "resolve_mod_batch",
                modsJson,
                outputDir,
                quality,
                PyObject.fromJava(onProgress)
            ).asList()

            val success = result[0].toBoolean()
            val payload = result[1].toString()
            Pair(success, if (success) JSONArray(payload) else null)
        } catch (e: Exception) {
            e.printStackTrace()
            Pair(false, null)
        }
    }

    fun mergeSpineAssets(modPath: String, onProgress: (String) -> Unit): Pair<Boolean, String> {
        return try {
            val py = Python.getInstance()
            val mainScript = py.getModule("main_script")

            val result = mainScript.callAttr(
                "merge_spine_assets",
                modPath,
                PyObject.fromJava(onProgress)
            ).asList()

            val success = result[0].toBoolean()
            val message = result[1].toString()
            Pair(success, message)
        } catch (e: Exception) {
            e.printStackTrace()
            Pair(false, e.message ?: "An unknown error occurred in Kotlin during spine merge.")
        }
    }

    // --- Local bundle scanning (three-step API) ---

    fun checkScanNeeded(outputDir: String, bundleListJson: String, onProgress: (String) -> Unit): String {
        return try {
            val py = Python.getInstance()
            val mainScript = py.getModule("main_script")

            val result = mainScript.callAttr(
                "check_scan_needed",
                outputDir,
                bundleListJson,
                PyObject.fromJava(onProgress)
            ).toString()

            result
        } catch (e: Exception) {
            e.printStackTrace()
            "[]"
        }
    }

    fun scanSingleBundle(bundleName: String, bundleHash: String, tempDataPath: String, onProgress: (String) -> Unit): Triple<Boolean, Int, String> {
        return try {
            val py = Python.getInstance()
            val mainScript = py.getModule("main_script")

            val result = mainScript.callAttr(
                "scan_single_bundle",
                bundleName,
                bundleHash,
                tempDataPath,
                PyObject.fromJava(onProgress)
            ).asList()

            val success = result[0].toBoolean()
            val assetCount = result[1].toInt()
            val message = result[2].toString()
            Triple(success, assetCount, message)
        } catch (e: Exception) {
            e.printStackTrace()
            Triple(false, 0, e.message ?: "Unknown error during bundle scan.")
        }
    }

    fun finalizeScan(outputDir: String, onProgress: (String) -> Unit): Pair<Boolean, String> {
        return try {
            val py = Python.getInstance()
            val mainScript = py.getModule("main_script")

            val result = mainScript.callAttr(
                "finalize_scan",
                outputDir,
                PyObject.fromJava(onProgress)
            ).asList()

            val success = result[0].toBoolean()
            val message = result[1].toString()
            Pair(success, message)
        } catch (e: Exception) {
            e.printStackTrace()
            Pair(false, e.message ?: "Unknown error during scan finalization.")
        }
    }
}
