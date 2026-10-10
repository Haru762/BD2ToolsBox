package com.bd2toolsbox.feature.mods

import com.bd2toolsbox.core.fileaccess.GameFileAccess
import com.bd2toolsbox.data.model.BundleCheckResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 游戏 bundle 索引扫描的检查结果。
 *
 * 与「来源 MOD 扫描」分开建模：这里只回答「游戏目录里的 bundle 索引是否需要重建」，
 * 不涉及 mod 源目录、SAF 授权或 mod 列表状态。四种结果互不冒充 —— 尤其
 * [Failed] 不等于「没有需要扫描的 bundle」。
 */
sealed interface ScanCheck {
    /** 需要扫描，携带已冻结的检查结果（bundle 列表 + 待扫清单）。 */
    data class NeedsScan(val checkResult: BundleCheckResult) : ScanCheck

    /** 索引已是最新，无需重扫。 */
    object UpToDate : ScanCheck

    /** Shizuku 不可用（未运行/未授权）。 */
    object NotConnected : ScanCheck

    /** 已连接但读取/检查失败。 */
    data class Failed(val message: String) : ScanCheck
}

/** 游戏 bundle 索引扫描的执行结果。 */
sealed interface ScanRun {
    data class Finished(val scanned: Int, val failed: Int) : ScanRun
    data class Failed(val message: String) : ScanRun
}

/**
 * 游戏 bundle 索引扫描的执行器（T23）。
 *
 * 把原先散在 `MainViewModel` 里的 check / execute 编排收进一处：ViewModel 只提交意图、
 * 观察状态，真正的目录列举、缓存比对与扫描循环都由 [GameFileAccess] 适配层执行。
 * 扫描全程自己持有一份连接占用，不依赖页面是否还在。
 *
 * 不拥有后台 scope：由宿主（ViewModel/Service）在自己的 scope 里调用 [check] / [run]。
 */
@Singleton
class ScanExecutor @Inject constructor(
    private val access: GameFileAccess
) {
    /**
     * 检查是否需要重建索引。不持有连接占用（与旧 `requestGameScan` 的 check 阶段一致）。
     */
    suspend fun check(outputDir: String, onProgress: (String) -> Unit = {}): ScanCheck {
        if (!access.isAvailable()) return ScanCheck.NotConnected
        val result = access.checkLocalBundles(outputDir, onProgress)
            ?: return ScanCheck.Failed("读取游戏资源目录失败")
        return if (result.needsScanCount == 0) ScanCheck.UpToDate else ScanCheck.NeedsScan(result)
    }

    /**
     * 执行扫描。全程持有一份连接占用，结束后释放 —— 页面销毁不影响扫描。
     *
     * [onProgress] 由调用方转发到通知栏与界面状态；执行器不直接依赖 Service/UI。
     */
    suspend fun run(
        outputDir: String,
        cacheDir: String,
        checkResult: BundleCheckResult,
        onProgress: (currentIndex: Int, total: Int, bundleName: String, message: String) -> Unit
    ): ScanRun {
        val connection = access.acquire()
        try {
            val (success, scanned, failed) = access.executeBundleScan(
                outputDir = outputDir,
                cacheDir = cacheDir,
                checkResult = checkResult,
                onBundleProgress = onProgress
            )
            return if (success) ScanRun.Finished(scanned, failed) else ScanRun.Failed("建立文件索引失败。")
        } finally {
            access.release(connection)
        }
    }
}
