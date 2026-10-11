package com.bd2toolsbox.core.engine

import com.chaquo.python.PyObject
import com.chaquo.python.Python
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/** Python values stay behind this boundary; protocol tests need no native interpreter. */
interface PyCell {
    fun asBool(): Boolean
    fun asInt(): Int
    fun asLong(): Long
    fun asText(): String
    fun asMap(): Map<String, List<PyCell>>?
}

fun interface PyCaller {
    fun call(function: String, args: Array<out Any?>): List<PyCell>
}

private class PyObjectCell(private val value: PyObject?) : PyCell {
    // Chaquopy represents Python None as null. Failed metadata tuples legitimately contain it;
    // only converting a field which the protocol requires must reject it.
    private fun required(): PyObject = requireNotNull(value) { "Unexpected Python None" }
    override fun asBool(): Boolean = required().toBoolean()
    override fun asInt(): Int = required().toInt()
    override fun asLong(): Long = required().toLong()
    override fun asText(): String = required().toString()
    override fun asMap(): Map<String, List<PyCell>> = buildMap {
        for ((key, item) in required().asMap()) {
            val name = requireNotNull(key) { "Metadata key is None" }.toString()
            val fields = requireNotNull(item) { "Metadata value is None" }.asList()
            put(name, fields.map { PyObjectCell(it) })
        }
    }
}

class ChaquopyPyCaller : PyCaller {
    override fun call(function: String, args: Array<out Any?>): List<PyCell> {
        val converted = args.map { if (it is Function1<*, *>) PyObject.fromJava(it) else it }.toTypedArray()
        val result = Python.getInstance().getModule("main_script").callAttrThrows(function, *converted)
        // asList alone also accepts Python strings and lists, masking a broken tuple protocol.
        if (result == null || result.type().get("__name__")?.toString() != "tuple") {
            throw EngineBridgeException(
                function, EngineBridgeException.Phase.PROTOCOL,
                IllegalArgumentException("Expected a Python tuple"),
            )
        }
        return result.asList().map { PyObjectCell(it) }
    }
}

/** Preserves Python names/argument order; validates the entire response before returning success. */
class ChaquopyModEngine(
    private val pyCaller: PyCaller,
) : ModEngine {
    constructor() : this(ChaquopyPyCaller())

    private class Progress<T>(private val callback: (String) -> T) : (String) -> T {
        @Volatile var cancellation: CancellationException? = null
            private set

        override fun invoke(message: String): T {
            cancellation?.let { throw it }
            try {
                return callback(message)
            } catch (e: CancellationException) {
                cancellation = e
                throw e
            }
        }
    }

    private fun checkCallbackCancellation(args: Array<out Any?>) {
        // Python's broad except clauses can swallow a Java callback exception. Preserve it on
        // the Kotlin side and rethrow when the native call returns, before decoding any success.
        for (arg in args) (arg as? Progress<*>)?.cancellation?.let { throw it }
    }

    private inline fun <T> callMain(
        function: String,
        arity: Int,
        vararg args: Any?,
        onFailure: (EngineBridgeException) -> T,
        decode: (List<PyCell>) -> T,
    ): T {
        var phase = EngineBridgeException.Phase.CALL
        try {
            val cells = pyCaller.call(function, args)
            checkCallbackCancellation(args)
            phase = EngineBridgeException.Phase.PROTOCOL
            require(cells.size == arity) { "Expected $arity tuple cells, got ${cells.size}" }
            return decode(cells)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            checkCallbackCancellation(args)
            val failure = e as? EngineBridgeException ?: EngineBridgeException(function, phase, e)
            failure.printStackTrace()
            return onFailure(failure)
        }
    }

    private fun callPair(function: String, vararg args: Any?): Pair<Boolean, String> =
        callMain(function, 2, *args, onFailure = { false to it.message.orEmpty() }) { cells ->
            cells[0].asBool() to cells[1].asText()
        }

    override fun downloadBundle(
        hashedName: String, quality: String, outputDir: String,
        cacheKey: String, onProgress: (String) -> Unit,
    ): Pair<Boolean, String> = callPair("download_bundle", hashedName, quality, outputDir, cacheKey, Progress(onProgress))

    override fun repackBundle(
        originalBundlePath: String, moddedAssetsFolder: String,
        outputPath: String, useAstc: Boolean, onProgress: (String) -> Unit,
    ): Pair<Boolean, String> = callPair("main", originalBundlePath, moddedAssetsFolder, outputPath, useAstc, Progress(onProgress))

    override fun getBundleMeta(
        outputDir: String, quality: String, onProgress: (String) -> Unit,
    ): Pair<Map<String, Pair<Long, String>>, Boolean>? =
        callMain("get_bundle_meta", 3, outputDir, quality, Progress(onProgress), onFailure = { null }) { cells ->
            if (!cells[0].asBool()) {
                onProgress("获取 bundle 元数据失败：${cells[1].asText()}")
                return@callMain null
            }
            val degraded = cells[1].asText() == "degraded"
            val raw = requireNotNull(cells[2].asMap()) { "Expected a metadata map" }
            val decoded = raw.mapValues { (_, fields) ->
                require(fields.size == 2) { "Metadata entry must have size and hash" }
                fields[0].asLong() to fields[1].asText()
            }
            decoded to degraded
        }

    override fun getBundleHints(
        outputDir: String, quality: String, onProgress: (String) -> Unit,
    ): Map<String, Pair<String, String>>? =
        callMain("get_bundle_hints", 3, outputDir, quality, Progress(onProgress), onFailure = { null }) { cells ->
            if (!cells[0].asBool()) {
                onProgress("获取 bundle 命名提示失败：${cells[1].asText()}")
                return@callMain null
            }
            val raw = requireNotNull(cells[2].asMap()) { "Expected a hints map" }
            raw.mapValues { (_, fields) ->
                require(fields.size == 2) { "Hint entry must have file ID and slot" }
                fields[0].asText() to fields[1].asText()
            }
        }

    override fun restoreBundle(
        originalBundlePath: String, outputPath: String, onProgress: (String) -> Unit,
    ): Pair<Boolean, String> = callPair("restore_bundle", originalBundlePath, outputPath, Progress(onProgress))

    override fun unpackBundle(
        bundlePath: String, outputDir: String, fast: Boolean,
        onProgress: (String) -> Boolean,
    ): Pair<Boolean, String> = callPair("unpack_bundle", bundlePath, outputDir, Progress(onProgress), fast)

    override fun validateBundle(bundlePath: String): Pair<Boolean, String> = callPair("validate_bundle", bundlePath)

    override fun resolveModFiles(
        fileNamesJson: String, outputDir: String, quality: String, onProgress: (String) -> Unit,
    ): Pair<Boolean, JSONObject?> =
        callMain("resolve_mod_files", 2, fileNamesJson, outputDir, quality, Progress(onProgress),
            onFailure = { false to null }) { cells ->
            if (!cells[0].asBool()) return@callMain false to null
            val json = cells[1].asText()
            require(Json.parseToJsonElement(json) is JsonObject) { "Expected a resolver JSON object" }
            true to JSONObject(json)
        }

    override fun resolveModBatch(
        modsJson: String, outputDir: String, quality: String, onProgress: (String) -> Unit,
    ): Pair<Boolean, JSONArray?> =
        callMain("resolve_mod_batch", 2, modsJson, outputDir, quality, Progress(onProgress),
            onFailure = { false to null }) { cells ->
            if (!cells[0].asBool()) return@callMain false to null
            val json = cells[1].asText()
            require(Json.parseToJsonElement(json) is JsonArray) { "Expected a resolver JSON array" }
            true to JSONArray(json)
        }

    override fun mergeSpineAssets(modPath: String, onProgress: (String) -> Unit): Pair<Boolean, String> =
        callPair("merge_spine_assets", modPath, Progress(onProgress))

    // A failed scan check has no valid String sentinel: "[]" would incorrectly mean up-to-date.
    override fun checkScanNeeded(outputDir: String, bundleListJson: String, onProgress: (String) -> Unit): String =
        callMain("check_scan_needed", 1, outputDir, bundleListJson, Progress(onProgress), onFailure = { throw it }) { cells ->
            val json = cells[0].asText()
            val parsed = Json.parseToJsonElement(json)
            require(parsed is JsonArray && parsed.all {
                it is JsonPrimitive && it.isString
            }) { "Expected a scan list of bundle names" }
            json
        }

    override fun scanSingleBundle(
        bundleName: String, bundleHash: String, tempDataPath: String, onProgress: (String) -> Unit,
    ): Triple<Boolean, Int, String> =
        callMain("scan_single_bundle", 3, bundleName, bundleHash, tempDataPath, Progress(onProgress),
            onFailure = { Triple(false, 0, it.message.orEmpty()) }) { cells ->
            Triple(cells[0].asBool(), cells[1].asInt(), cells[2].asText())
        }

    override fun checkpointScan(outputDir: String): Pair<Boolean, String> = callPair("checkpoint_scan", outputDir)

    override fun finalizeScan(outputDir: String, onProgress: (String) -> Unit): Pair<Boolean, String> =
        callPair("finalize_scan", outputDir, Progress(onProgress))
}
