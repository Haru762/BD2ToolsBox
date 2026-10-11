package com.bd2toolsbox.feature.mods

import com.bd2toolsbox.data.model.BundleCheckResult

/**
 * 一次游戏索引扫描的**冻结输入**（T23）。
 *
 * 只带路径与已冻结的检查结果，不读 UI 状态、不持调用方闭包。
 */
data class ScanRunRequest(
    val outputDir: String,
    val cacheDir: String,
    val checkResult: BundleCheckResult
)

/** 扫描过程中的一帧进度。 */
data class ScanRunProgress(
    val currentIndex: Int,
    val total: Int,
    val bundleName: String,
    val message: String
)

/**
 * 扫描执行的**宿主**（T23）。
 *
 * 与 [com.bd2toolsbox.feature.install.MoveCommitHost] 同一理由：扫描原先跑在
 * `viewModelScope` 上，用户退出界面会触发 `onCleared` 取消协程，扫到一半的索引
 * 就断在那里。生产实现把执行交给 `ScanService`（自己的 scope + 前台服务 +
 * 通知栏进度，界面死活无关）。
 *
 * JVM 用例注入 [DirectScanRunHost] 验证扫描语义本身；生产路径由设备用例覆盖。
 */
interface ScanRunHost {
    /**
     * 执行扫描。[onProgress] 由宿主转发到通知栏与界面状态。
     *
     * 返回 null 表示宿主没能开始跑（前台服务被拒），调用方必须据此停下。
     */
    suspend fun run(
        request: ScanRunRequest,
        onProgress: (ScanRunProgress) -> Unit
    ): ScanRun?
}

/**
 * 直接在调用方协程里执行（JVM 用例与没有 Android 服务的场合）。
 *
 * 生产**不用**它：它活在调用方的 scope 上，正是要修的那个问题。
 */
class DirectScanRunHost(
    private val executor: ScanExecutor
) : ScanRunHost {
    override suspend fun run(
        request: ScanRunRequest,
        onProgress: (ScanRunProgress) -> Unit
    ): ScanRun = executor.run(
        outputDir = request.outputDir,
        cacheDir = request.cacheDir,
        checkResult = request.checkResult,
        onProgress = { index, total, bundleName, message ->
            onProgress(ScanRunProgress(index, total, bundleName, message))
        }
    )
}
