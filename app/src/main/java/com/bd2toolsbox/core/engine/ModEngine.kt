package com.bd2toolsbox.core.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * Kotlin ↔ Python（main_script）引擎适配层（T18）。
 *
 * 把原 `service.ModdingService` 的静态桥收敛成**可注入**的接口：集中「取模块、装参数、
 * 解元组、校验返回、兜异常」的样板，让 PyObject 只存在于实现内部，绝不穿透到
 * ViewModel/UI。方法名、参数顺序、SD/HD、ASTC、resolver 语义与旧桥逐一相同。
 *
 * ## 类型化边界
 * 每个入口返回 Kotlin 原生类型（`Pair`/`Triple`/`JSONObject`/`String`），不返回 `PyObject`。
 * 桥接和解码错误统一保留函数名、阶段与原因；有失败值的入口返回 `(false, message)`
 * 或 `null`。扫描检查抛 [EngineBridgeException]，绝不把失败伪装成成功的空名单。
 *
 * ## 取消
 * [unpackBundle] 的 `onProgress` 返回 `true` 表示请求取消——Python 侧每个资产检查一次，
 * 尽快干净退出。这是**可取消**入口；其余入口是不可中断的整段调用（调用方用协程
 * 取消包住，但 Python 调用本身不会中途停）。协程的 `CancellationException` 由调用方
 * 处理，本层不把它吞成一般失败（本层只捕获 `Exception` 的**非取消**分支——见实现）。
 *
 * ## 生命周期
 * Python 解释器由 [com.bd2toolsbox.core.python.PythonRuntime.ensureStarted] 按使用懒启动、
 * 全进程一次；本引擎不负责启动，只在解释器就绪后取模块调用。
 */
interface ModEngine {

    // ---------------------------------------------------------------- 下载与转换

    fun downloadBundle(
        hashedName: String, quality: String, outputDir: String,
        cacheKey: String, onProgress: (String) -> Unit,
    ): Pair<Boolean, String>

    fun repackBundle(
        originalBundlePath: String, moddedAssetsFolder: String,
        outputPath: String, useAstc: Boolean, onProgress: (String) -> Unit,
    ): Pair<Boolean, String>

    fun getBundleMeta(
        outputDir: String, quality: String, onProgress: (String) -> Unit,
    ): Pair<Map<String, Pair<Long, String>>, Boolean>?

    fun getBundleHints(
        outputDir: String, quality: String, onProgress: (String) -> Unit,
    ): Map<String, Pair<String, String>>?

    fun restoreBundle(
        originalBundlePath: String, outputPath: String, onProgress: (String) -> Unit,
    ): Pair<Boolean, String>

    fun unpackBundle(
        bundlePath: String, outputDir: String, fast: Boolean = false,
        onProgress: (String) -> Boolean,
    ): Pair<Boolean, String>

    fun validateBundle(bundlePath: String): Pair<Boolean, String>

    // ---------------------------------------------------------------- mod 解析

    fun resolveModFiles(
        fileNamesJson: String, outputDir: String, quality: String, onProgress: (String) -> Unit,
    ): Pair<Boolean, JSONObject?>

    fun resolveModBatch(
        modsJson: String, outputDir: String, quality: String, onProgress: (String) -> Unit,
    ): Pair<Boolean, JSONArray?>

    fun mergeSpineAssets(modPath: String, onProgress: (String) -> Unit): Pair<Boolean, String>

    // ---------------------------------------------------------------- 本地扫描（三步 API）

    fun checkScanNeeded(outputDir: String, bundleListJson: String, onProgress: (String) -> Unit): String

    fun scanSingleBundle(
        bundleName: String, bundleHash: String, tempDataPath: String, onProgress: (String) -> Unit,
    ): Triple<Boolean, Int, String>

    fun checkpointScan(outputDir: String): Pair<Boolean, String>

    fun finalizeScan(outputDir: String, onProgress: (String) -> Unit): Pair<Boolean, String>

    companion object {
        private val default: ModEngine by lazy { ChaquopyModEngine() }

        /**
         * T20 清理前的唯一进程级实例。Hilt provider 也委托到这里，不能再通过
         * install 替换实例，否则早期静态调用方与后续注入方会持有不同引擎。
         * 构造引擎不启动 Python；运行时仍由 PythonRuntime 按需启动。
         */
        @JvmStatic
        fun get(): ModEngine = default
    }
}
