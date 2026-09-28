package com.bd2toolsbox.core.engine

import com.chaquo.python.PyObject
import com.chaquo.python.Python
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Python 返回元组里的一个单元，脱去 [PyObject]（T18）。
 *
 * 协议解码只依赖这个中性接口，[PyObject] 因此**完全**封闭在 [ChaquopyPyCaller] 里——
 * 既满足「PyObject 不穿透」，也让 [EngineProtocolTest] 在纯 JVM 下用简单 fake 驱动解码，
 * 不必 mock Chaquopy 的 native 方法（`PyObject.toString()` 是 native，无法 mock）。
 */
interface PyCell {
    fun asBool(): Boolean
    fun asInt(): Int
    fun asLong(): Long
    fun asText(): String
    /** map 值展开成 cell 列表；非 map 返回 null。 */
    fun asMap(): Map<String, List<PyCell>>?
}

/**
 * 取一次 main_script 入口的原始返回（cell 列表）；解释器/调用细节都在实现里。
 * 抽成可注入 seam，生产用 [ChaquopyPyCaller]，测试用 fake。
 */
fun interface PyCaller {
    fun call(function: String, args: Array<out Any?>): List<PyCell>
}

/** [PyObject] 到 [PyCell] 的适配（唯一触碰 PyObject 的地方）。 */
private class PyObjectCell(private val po: PyObject) : PyCell {
    override fun asBool(): Boolean = po.toBoolean()
    override fun asInt(): Int = po.toInt()
    override fun asLong(): Long = po.toLong()
    override fun asText(): String = po.toString()
    override fun asMap(): Map<String, List<PyCell>>? = try {
        buildMap {
            for ((k, v) in po.asMap()) {
                val name = k?.toString() ?: continue
                val list = v?.asList()?.map { PyObjectCell(it) } ?: continue
                put(name, list)
            }
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * 生产实现：真正走 Chaquopy 的 main_script 模块。
 *
 * 进入这里才把进度回调（`Function1`）转成 [PyObject]——`PyObject.fromJava` 需要
 * 解释器已启动，放到最后一刻做，使上层不需在组装参数时就碰 Python。
 */
@Singleton
class ChaquopyPyCaller @Inject constructor() : PyCaller {
    override fun call(function: String, args: Array<out Any?>): List<PyCell> {
        val converted = args.map { if (it is Function1<*, *>) PyObject.fromJava(it) else it }.toTypedArray()
        return Python.getInstance()
            .getModule("main_script")
            .callAttr(function, *converted)
            .asList()
            .map { PyObjectCell(it) }
    }
}

/**
 * [ModEngine] 的 Chaquopy 实现（T18）。
 *
 * 从原 `service.ModdingService` 静态桥逐字搬迁——方法名、参数顺序、返回装配、失败值、
 * SD/HD/ASTC/resolver 语义都不变，只是从 `object` 变成可注入的类，且解码基于 [PyCell]。
 */
@Singleton
class ChaquopyModEngine @Inject constructor(
    private val pyCaller: PyCaller,
) : ModEngine {

    /** 无参构造：兜底给 [ModEngine.get] 的进程级默认用（未经 Hilt 时）。 */
    constructor() : this(ChaquopyPyCaller())

    /** 调 main_script 的一个入口，返回结果 cell 列表；异常时返回 null。
     *  协程取消（[CancellationException]）**不**吞成一般失败：直接重新抛出，让上层协程
     *  正常走取消流程，而不是被记成「未知错误」。 */
    private fun callMain(function: String, vararg args: Any?): List<PyCell>? = try {
        pyCaller.call(function, args)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }

    // ---------------------------------------------------------------- 下载与转换

    override fun downloadBundle(
        hashedName: String, quality: String, outputDir: String,
        cacheKey: String, onProgress: (String) -> Unit,
    ): Pair<Boolean, String> {
        val r = callMain("download_bundle", hashedName, quality, outputDir, cacheKey, onProgress)
            ?: return Pair(false, "An unknown error occurred in Kotlin during download.")
        return r[0].asBool() to r[1].asText()
    }

    override fun repackBundle(
        originalBundlePath: String, moddedAssetsFolder: String,
        outputPath: String, useAstc: Boolean, onProgress: (String) -> Unit,
    ): Pair<Boolean, String> {
        val r = callMain("main", originalBundlePath, moddedAssetsFolder, outputPath, useAstc, onProgress)
            ?: return Pair(false, "An unknown error occurred in Kotlin.")
        return r[0].asBool() to r[1].asText()
    }

    override fun getBundleMeta(
        outputDir: String, quality: String, onProgress: (String) -> Unit,
    ): Pair<Map<String, Pair<Long, String>>, Boolean>? {
        val r = callMain("get_bundle_meta", outputDir, quality, onProgress)
            ?: return null
        if (!r[0].asBool()) {
            onProgress("获取 bundle 元数据失败：${r[1].asText()}")
            return null
        }
        val meta = r.getOrNull(2) ?: return null
        if (meta.asText() == "None") return null
        // python 离线降级时 r[1] 是 "degraded"：表来自磁盘缓存、可能不是最新版
        val degraded = r.getOrNull(1)?.asText() == "degraded"

        val map = buildMap {
            for ((name, pair) in meta.asMap() ?: return@buildMap) {
                if (pair.size >= 2) put(name, pair[0].asLong() to pair[1].asText())
            }
        }
        return map to degraded
    }

    override fun getBundleHints(
        outputDir: String, quality: String, onProgress: (String) -> Unit,
    ): Map<String, Pair<String, String>>? {
        val r = callMain("get_bundle_hints", outputDir, quality, onProgress)
            ?: return null
        if (!r[0].asBool()) {
            onProgress("获取 bundle 命名提示失败：${r[1].asText()}")
            return null
        }
        val hints = r.getOrNull(2) ?: return null
        if (hints.asText() == "None") return null

        return buildMap {
            for ((name, pair) in hints.asMap() ?: return@buildMap) {
                if (pair.size >= 2) put(name, pair[0].asText() to pair[1].asText())
            }
        }
    }

    override fun restoreBundle(
        originalBundlePath: String, outputPath: String, onProgress: (String) -> Unit,
    ): Pair<Boolean, String> {
        val r = callMain("restore_bundle", originalBundlePath, outputPath, onProgress)
            ?: return Pair(false, "restore_bundle 调用失败")
        return r[0].asBool() to r[1].asText()
    }

    override fun unpackBundle(
        bundlePath: String, outputDir: String, fast: Boolean,
        onProgress: (String) -> Boolean,
    ): Pair<Boolean, String> {
        val r = callMain("unpack_bundle", bundlePath, outputDir, onProgress, fast)
            ?: return Pair(false, "An unknown error occurred in Kotlin during unpack.")
        return r[0].asBool() to r[1].asText()
    }

    override fun validateBundle(bundlePath: String): Pair<Boolean, String> {
        val r = callMain("validate_bundle", bundlePath)
            ?: return Pair(false, "validate_bundle 调用失败")
        return r[0].asBool() to r[1].asText()
    }

    // ---------------------------------------------------------------- mod 解析

    override fun resolveModFiles(
        fileNamesJson: String, outputDir: String, quality: String, onProgress: (String) -> Unit,
    ): Pair<Boolean, JSONObject?> {
        val r = callMain("resolve_mod_files", fileNamesJson, outputDir, quality, onProgress)
            ?: return Pair(false, null)
        val success = r[0].asBool()
        return success to success.takeIf { it }?.let { JSONObject(r[1].asText()) }
    }

    override fun resolveModBatch(
        modsJson: String, outputDir: String, quality: String, onProgress: (String) -> Unit,
    ): Pair<Boolean, JSONArray?> {
        val r = callMain("resolve_mod_batch", modsJson, outputDir, quality, onProgress)
            ?: return Pair(false, null)
        val success = r[0].asBool()
        return success to success.takeIf { it }?.let { JSONArray(r[1].asText()) }
    }

    override fun mergeSpineAssets(modPath: String, onProgress: (String) -> Unit): Pair<Boolean, String> {
        val r = callMain("merge_spine_assets", modPath, onProgress)
            ?: return Pair(false, "An unknown error occurred in Kotlin during spine merge.")
        return r[0].asBool() to r[1].asText()
    }

    // ---------------------------------------------------------------- 本地扫描（三步 API）

    override fun checkScanNeeded(outputDir: String, bundleListJson: String, onProgress: (String) -> Unit): String =
        callMain("check_scan_needed", outputDir, bundleListJson, onProgress)
            ?.firstOrNull()?.asText() ?: "[]"

    override fun scanSingleBundle(
        bundleName: String, bundleHash: String, tempDataPath: String, onProgress: (String) -> Unit,
    ): Triple<Boolean, Int, String> {
        val r = callMain("scan_single_bundle", bundleName, bundleHash, tempDataPath, onProgress)
            ?: return Triple(false, 0, "Unknown error during bundle scan.")
        return Triple(r[0].asBool(), r[1].asInt(), r[2].asText())
    }

    override fun checkpointScan(outputDir: String): Pair<Boolean, String> {
        val r = callMain("checkpoint_scan", outputDir)
            ?: return Pair(false, "Unknown error during scan checkpoint.")
        return r[0].asBool() to r[1].asText()
    }

    override fun finalizeScan(outputDir: String, onProgress: (String) -> Unit): Pair<Boolean, String> {
        val r = callMain("finalize_scan", outputDir, onProgress)
            ?: return Pair(false, "Unknown error during scan finalization.")
        return r[0].asBool() to r[1].asText()
    }
}
