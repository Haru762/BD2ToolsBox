package com.bd2toolsbox.service

import com.bd2toolsbox.core.fileaccess.DirectoryListingResult
import com.bd2toolsbox.core.fileaccess.GameFileAccess
import com.bd2toolsbox.data.model.BundleCheckResult

/**
 * Shizuku 访问层的旧静态门面（T19：委托给 [ShizukuGameFileAccess]）。
 *
 * 真正的绑定/解绑/占用生命周期已经搬到 [ShizukuGameFileAccess]——它是 `@Singleton`，
 * 由 Hilt 提供**唯一**实例；这个 object 只做参数/返回值转换，保留旧调用点（散布在
 * `MainViewModel` 20 余处）不必在本任务内全部迁移到新接口。T20 收尾时会把调用点
 * 迁到构造注入的 [ShizukuGameFileAccess]/[com.bd2toolsbox.core.fileaccess.GameFileAccess]，
 * 到时这个 object 可以删除。
 *
 * **不再**由 `Activity.isFinishing` 直接触发解绑：旧行为是「页面销毁 = 无条件解绑」，
 * 会切断仍在 installScope/ScanService 里跑着的任务的连接。新模型下，[MainActivity]
 * 在 onDestroy 时只释放自己持有的一个 token（[acquireConnection]/[releaseConnection]），
 * 底层连接是否真正断开由 [com.bd2toolsbox.core.fileaccess.ShizukuGameFileAccess]
 * 内部的占用计数决定。
 */
object ShizukuManager {

    /**
     * 进程内唯一实例，与 Hilt 提供给构造注入方的是同一个（都走 [GameFileAccess.get]），
     * 不能自己构造一份新的——与 [com.bd2toolsbox.core.engine.ModEngine.get]
     * 的做法一致（T18 已验证过这个模式）。
     */
    private val delegate = GameFileAccess.get()

    // ---------------------------------------------------------------- 占用（T19 新增）

    /** [MainActivity] 在 onCreate 时占用一次连接，onDestroy 释放；语义见类注释。 */
    fun acquireConnection(): Any = delegate.acquire()

    fun releaseConnection(token: Any) = delegate.release(token)

    // ---------------------------------------------------------------- 扫描标志

    /** 进程级「扫描进行中」标志，语义不变。 */
    val scanRunning: Boolean get() = delegate.scanRunning

    fun markScanRunning(running: Boolean) = delegate.markScanRunning(running)

    // ---------------------------------------------------------------- 可用性与权限

    fun isAvailable(): Boolean = delegate.isAvailable()
    fun isRunning(): Boolean = delegate.isRunning()
    fun hasPermission(): Boolean = delegate.hasPermission()
    fun requestPermission(requestCode: Int) = delegate.requestPermission(requestCode)

    // ---------------------------------------------------------------- 服务绑定

    /**
     * 旧签名是「主动解绑」。新模型下这只是「释放本次占用」——保留方法名与调用方式，
     * 但不再无条件断开：仍有其他占用者时是 no-op。**唯一调用点在 [MainActivity]**，
     * 且已经改为配对 token 使用，这个无参重载只保留给测试/工具类场景。
     */
    fun unbindService() {
        val token = delegate.acquire()
        delegate.release(token) // 立即释放刚占的这一份；不影响其他真实占用者
    }

    // ---------------------------------------------------------------- 游戏目录操作

    suspend fun copyLocalBundleToCache(bundleName: String, hashDir: String, destPath: String): Boolean =
        delegate.copyLocalBundleToCache(bundleName, hashDir, destPath)

    suspend fun copyGameDataForRecovery(bundleName: String, hashDir: String?, destPath: String): Boolean =
        delegate.copyGameDataForRecovery(bundleName, hashDir, destPath)

    /**
     * 旧签名返回 `Map<String, Pair<String, Long>>?`：`null` 同时代表「不可用」
     * 和「某页失败」。新接口 [ShizukuGameFileAccess.listGameBundles] 已经用
     * [DirectoryListingResult] 区分二者；这里转换回旧形态，保持
     * `MainViewModel.refreshBundleCleanStates` 等调用点不必改动。
     * 转换后的信息损失（具体失败原因）留给 T20 迁移调用点时找回。
     */
    suspend fun listGameBundles(): Map<String, Pair<String, Long>>? =
        when (val result = delegate.listGameBundles()) {
            is DirectoryListingResult.Success ->
                result.entries.associate { it.name to (it.key.orEmpty() to it.sizeBytes) }
            is DirectoryListingResult.Failure -> null
        }

    suspend fun installConvertedBundle(sourceDir: String, bundleName: String): Pair<Boolean, String> =
        delegate.installConvertedBundle(sourceDir, bundleName)

    // ---------------------------------------------------------------- bundle 扫描编排

    suspend fun checkLocalBundles(
        outputDir: String,
        onProgress: (String) -> Unit
    ): BundleCheckResult? = delegate.checkLocalBundles(outputDir, onProgress)

    suspend fun executeBundleScan(
        outputDir: String,
        cacheDir: String,
        checkResult: BundleCheckResult,
        onBundleProgress: (currentIndex: Int, total: Int, bundleName: String, message: String) -> Unit
    ): Triple<Boolean, Int, Int> = delegate.executeBundleScan(outputDir, cacheDir, checkResult, onBundleProgress)
}
