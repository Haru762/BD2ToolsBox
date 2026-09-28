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
 * 桥接层错误（取不到模块、调用抛异常、返回元组非法）被兜成各入口约定的失败值
 * （`(false, message)` / `null` / 空名单），并保留诊断（`printStackTrace`）。
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
        @Volatile
        private var default: ModEngine? = null

        /**
         * 进程级默认引擎（T18 → T20 清理）。
         *
         * ShizukuManager（全局 object）、BundleNameResolver、ModRepository 这些**非 Hilt 管理**的
         * 调用方经此取到与 Hilt `@Singleton` **同一个** [ChaquopyModEngine] 实例——它由
         * `di.EngineModule` 在提供单例时调 [install] 注册。这是明确标注的临时兼容入口，
         * 委托对象唯一（就是那个单例）；T19/T20 把 ShizukuManager 等改注入后删除本访问器。
         *
         * 未安装时兜底为一个新的 [ChaquopyModEngine]（例如测试或极早期调用），行为一致。
         */
        @JvmStatic
        fun get(): ModEngine = default ?: synchronized(this) {
            default ?: ChaquopyModEngine().also { default = it }
        }

        /** 由 DI 模块在构造单例时调用，保证静态访问器与注入拿到同一实例。 */
        @JvmStatic
        fun install(engine: ModEngine) {
            synchronized(this) { default = engine }
        }
    }
}
