package com.bd2toolsbox.core.fileaccess

import com.bd2toolsbox.data.model.BundleCheckResult

/**
 * 对游戏目录的访问契约（T19）。
 *
 * 把 `ShizukuManager` 的静态桥收敛成**可注入**的接口，并把「连接何时释放」从
 * 「页面是否结束」改成「是否还有客户端在用」。方法名、参数、返回语义与旧的
 * `ShizukuManager` 逐一相同；这不是重写扫描/安装算法，只是把它们的连接生命周期
 * 从 Activity 手上移交给调用方显式声明的占用区间。
 *
 * ## 占用模型
 * 调用方在开始一段需要连接的工作前调用 [acquire]，工作结束后调用 [release]（同一个
 * [token] 各自一次，多次 release 同一个 token 是安全的 no-op）。底层连接只在**最后一个
 * token 释放时**才真正断开；只要还有 token 未释放，连接必须保持可用，不因页面销毁
 * 而被切断。
 *
 * 各业务方法内部自己经由 [acquire]/[release] 包一层，调用方不必在每次方法调用前后
 * 手动占用——只有需要让连接**跨越单次挂起函数调用**存活（例如扫描循环、批量装入）
 * 时才需要调用方自己持有 token。
 */
interface GameFileAccess {

    /**
     * 占用一次连接。返回的 token 只用于配对 [release]，不代表已经绑定成功——
     * 绑定发生在实际调用某个业务方法时；这里只登记「有人在用」，用于决定
     * 连接何时可以释放。
     */
    fun acquire(): Any

    /** 释放一次占用；[token] 必须是 [acquire] 返回的对象。重复释放同一个 token 是 no-op。 */
    fun release(token: Any)

    // ---------------------------------------------------------------- 可用性与权限

    fun isAvailable(): Boolean
    fun isRunning(): Boolean
    fun hasPermission(): Boolean
    fun requestPermission(requestCode: Int)

    // ---------------------------------------------------------------- 游戏目录操作

    suspend fun copyLocalBundleToCache(bundleName: String, hashDir: String, destPath: String): Boolean

    suspend fun copyGameDataForRecovery(bundleName: String, hashDir: String?, destPath: String): Boolean

    /**
     * 列出游戏 Shared/ 下每个 bundle 的 (hash 目录名, __data 字节数)。
     *
     * 旧 `ShizukuManager.listGameBundles` 返回 `Map?`，`null` 同时代表「不可用」
     * 和「某一页读取失败」——调用方分不清。这里改用 [DirectoryListingResult]：
     * [DirectoryListingResult.Failure] 明确是失败，[DirectoryListingResult.Success]
     * 才可能拿到列表（可以是空表）。
     */
    suspend fun listGameBundles(): DirectoryListingResult

    suspend fun installConvertedBundle(sourceDir: String, bundleName: String): Pair<Boolean, String>

    // ---------------------------------------------------------------- bundle 扫描编排

    suspend fun checkLocalBundles(outputDir: String, onProgress: (String) -> Unit): BundleCheckResult?

    suspend fun executeBundleScan(
        outputDir: String,
        cacheDir: String,
        checkResult: BundleCheckResult,
        onBundleProgress: (currentIndex: Int, total: Int, bundleName: String, message: String) -> Unit
    ): Triple<Boolean, Int, Int>

    /** 进程级「扫描进行中」标志；语义与旧 `ShizukuManager.scanRunning` 相同。 */
    val scanRunning: Boolean
    fun markScanRunning(running: Boolean)

    companion object {
        private val default: GameFileAccess by lazy { ShizukuGameFileAccess() }

        /**
         * 进程内唯一实例。与 [com.bd2toolsbox.core.engine.ModEngine.get] 同样的理由：
         * 旧静态门面 [com.bd2toolsbox.service.ShizukuManager] 与 Hilt 提供的实例必须是同一个，
         * 否则两边各自认为自己是唯一持有者，占用计数/连接状态分裂。构造 [ShizukuGameFileAccess]
         * 不做 Android/Shizuku 初始化，零副作用，静态门面可以安全持有它。
         */
        @JvmStatic
        fun get(): GameFileAccess = default
    }
}
