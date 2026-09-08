package com.bd2toolsbox.service

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.bd2toolsbox.IFileService
import com.bd2toolsbox.data.model.BundleCheckResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import android.util.Log
import org.json.JSONArray
import java.io.File

object ShizukuManager {

    private const val GAME_UNITY_CACHE_PATH =
        "/storage/emulated/0/Android/data/com.neowizgames.game.browndust2/files/UnityCache/"
    private const val DOWNLOAD_SHARED_PATH =
        "/storage/emulated/0/Download/Shared"
    private const val GAME_SHARED_PATH =
        "/storage/emulated/0/Android/data/com.neowizgames.game.browndust2/files/UnityCache/Shared"

    private var fileService: IFileService? = null
    private val bindMutex = Mutex()

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(
            "com.bd2toolsbox",
            ShizukuFileService::class.java.name
        )
    )
        // 守护模式：同一份 args 永远只对应一个服务进程，重复绑定会复用它。
        //
        // 原先是 .daemon(false)，本意是「用完就退」，但没有任何地方调用解绑，而且服务跑在
        // shell UID 下、force-stop app 带不走它。结果每次启动都留下一个约 130 MB 的孤儿
        // 进程：实测启动一次就从 12 个涨到 13 个、合计 1.66 GB，模拟器随后直接失去响应。
        //
        // 孤儿进程的 binder 已断，Shizuku 自己也不再认得它们，所以解绑收不回来 —— 只能
        // 从根上不产生孤儿。守护模式下无论 app 怎么退出（返回键、划掉、被系统杀），
        // 下次启动都复用那一个进程，占用恒定。
        .daemon(true)
        .processNameSuffix("file_service")
        .debuggable(android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.R)
        .tag("file_service")
        // v3: copyDirectory 增加源目录存在性检查（原本源不存在时会静默建出空目录并报成功）；
        // listBundleDirectory 的返回值带 __data 的 size 字段。AIDL 签名未变但实现变了，
        // 提升版本号确保 Shizuku 不会复用装着旧 dex 的服务进程。
        // v4: 改为守护模式（见上）。version 变化会让 Shizuku 丢弃旧记录、起一个干净的。
        .version(4)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            fileService = IFileService.Stub.asInterface(service)
            pendingBind?.complete(true)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            fileService = null
        }
    }

    private var pendingBind: CompletableDeferred<Boolean>? = null

    /**
     * 檢查 Shizuku 是否安裝且運行中，並且已授權
     */
    fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 把游戏本地的原版 bundle 复制到 [destPath]，作为重打包的基底。
     *
     * 仅应在干净检测判定该 bundle 为「干净原版」后调用 —— 若本地那份已经装着 mod，
     * 拿它当基底会把贴图叠加上去。调用方负责先做判定。
     *
     * 成功即省掉一次 CDN 下载；失败返回 false，调用方回退到 downloadBundle。
     */
    suspend fun copyLocalBundleToCache(
        bundleName: String,
        hashDir: String,
        destPath: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!isAvailable()) return@withContext false
            val service = ensureServiceBound() ?: return@withContext false
            val gamePath = "$GAME_SHARED_PATH/$bundleName/$hashDir/__data"
            val ok = service.copyFile(gamePath, destPath)
            if (!ok) Log.w("ShizukuManager", "Local bundle copy failed: $gamePath")
            ok
        } catch (e: Exception) {
            Log.e("ShizukuManager", "Error copying local bundle $bundleName", e)
            false
        }
    }

    /**
     * 列出游戏 Shared/ 下每个 bundle 的 (名字, hash 目录名, __data 字节数)。
     * 供干净检测与 catalog 的权威值比对。Shizuku 不可用时返回 null（无法校验，
     * 而不是谎称干净）。
     */
    suspend fun listGameBundles(): Map<String, Pair<String, Long>>? = withContext(Dispatchers.IO) {
        try {
            if (!isAvailable()) return@withContext null
            val service = ensureServiceBound() ?: return@withContext null
            val json = JSONArray(service.listBundleDirectory(GAME_SHARED_PATH))
            val out = HashMap<String, Pair<String, Long>>(json.length())
            for (i in 0 until json.length()) {
                val o = json.optJSONObject(i) ?: continue
                val name = o.optString("name").ifBlank { null } ?: continue
                val hash = o.optString("hash").ifBlank { null } ?: continue
                out[name] = hash to o.optLong("size", -1L)
            }
            out
        } catch (e: Exception) {
            Log.e("ShizukuManager", "Error listing game bundles", e)
            null
        }
    }

    /**
     * 把一个已转换好的 bundle 目录整体拷进游戏的 Shared/ 下。
     *
     * [sourceDir] 必须是 Shizuku 服务（shell UID）能读到的**真实路径**，不能是 SAF 的
     * content:// Uri —— SAF 权限授予的是 app，没法传递给独立进程的 shell 服务。
     * 调用方要么先把 documentId 还原成 /storage/emulated/0/… 的真实路径，要么先落到
     * externalCacheDir 再传进来。
     *
     * @param bundleName 目标 bundle 名，即游戏 Shared/ 下的那一层目录名
     */
    suspend fun installConvertedBundle(sourceDir: String, bundleName: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            try {
                if (!isRunning()) {
                    return@withContext Pair(false, "Shizuku 未运行，请先启动 Shizuku 再重试。")
                }
                if (!hasPermission()) {
                    withContext(Dispatchers.Main) { requestPermission(1001) }
                    return@withContext Pair(false, "已发起 Shizuku 授权请求，请允许后再试一次。")
                }
                val service = ensureServiceBound()
                    ?: return@withContext Pair(false, "无法连接 Shizuku 文件服务，请重启 Shizuku 后重试。")

                val ok = service.copyDirectory(sourceDir, "$GAME_SHARED_PATH/$bundleName")
                if (ok) Pair(true, "已装入游戏目录")
                else Pair(false, "拷贝到游戏目录失败：$bundleName")
            } catch (e: Exception) {
                Log.e("ShizukuManager", "Error installing converted bundle $bundleName", e)
                Pair(false, "出错：${e.message}")
            }
        }

    /**
     * 檢查 Shizuku 是否運行中（不檢查權限）
     */
    fun isRunning(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 檢查是否已取得 Shizuku 權限
     */
    fun hasPermission(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 請求 Shizuku 權限
     */
    fun requestPermission(requestCode: Int) {
        Shizuku.requestPermission(requestCode)
    }

    /**
     * 綁定 UserService，取得 IFileService 實例
     */
    private suspend fun ensureServiceBound(): IFileService? {
        if (fileService != null) return fileService

        return bindMutex.withLock {
            if (fileService != null) return@withLock fileService

            val deferred = CompletableDeferred<Boolean>()
            pendingBind = deferred

            try {
                Log.d("ShizukuManager", "Binding user service...")
                Shizuku.bindUserService(userServiceArgs, serviceConnection)
                
                // Add 5 second timeout to prevent hanging forever
                val bound = withTimeoutOrNull(5000) {
                    deferred.await()
                }
                
                if (bound == true) {
                    Log.d("ShizukuManager", "User service bound successfully")
                    fileService
                } else {
                    Log.d("ShizukuManager", "Timeout waiting for service bind")
                    pendingBind = null
                    null
                }
            } catch (e: Exception) {
                Log.e("ShizukuManager", "Failed to bind service", e)
                pendingBind = null
                null
            }
        }
    }

    /**
     * 將 Download/Shared 目錄搬移到遊戲的 UnityCache 目錄
     * @return Pair<成功, 錯誤訊息>
     */
    suspend fun moveDownloadToGame(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            if (!isRunning()) {
                return@withContext Pair(false, "Shizuku 未运行，请先启动 Shizuku 再重试。")
            }

            if (!hasPermission()) {
                withContext(Dispatchers.Main) {
                    requestPermission(1001)
                }
                return@withContext Pair(false, "已发起 Shizuku 授权请求，请在弹窗中允许后再点一次「一键装入游戏」。")
            }

            val service = ensureServiceBound()
                ?: return@withContext Pair(false, "无法连接 Shizuku 文件服务，请重启 Shizuku 后重试。")

            val sourceDir = java.io.File(DOWNLOAD_SHARED_PATH)
            if (!sourceDir.exists() || !sourceDir.isDirectory) {
                return@withContext Pair(false, "未找到 Download/Shared 目录，可能已被装入或被清理。")
            }

            Log.d("ShizukuManager", "Starting to copy using Shizuku")
            val success = service.copyDirectory(DOWNLOAD_SHARED_PATH, GAME_UNITY_CACHE_PATH + "Shared")

            if (success) {
                Log.d("ShizukuManager", "Copy successful, cleaning up source")
                sourceDir.deleteRecursively()
                Pair(true, "已成功装入游戏目录！")
            } else {
                Log.e("ShizukuManager", "Copy failed in ShizukuFileService")
                Pair(false, "复制文件到游戏目录失败。")
            }
        } catch (e: Exception) {
            Log.e("ShizukuManager", "Error during Shizuku file operation", e)
            Pair(false, "出错：${e.message}")
        }
    }

    /**
     * Phase 1: 列舉遊戲目錄並檢查哪些 bundle 需要掃描。
     * 不執行實際掃描，只回傳結果讓呼叫端決定是否繼續。
     *
     * @param outputDir  App 可寫入的目錄（通常是 context.filesDir）
     * @param onProgress 進度回報
     * @return BundleCheckResult? — null 表示失敗或無 bundle
     */
    suspend fun checkLocalBundles(
        outputDir: String,
        onProgress: (String) -> Unit
    ): BundleCheckResult? = withContext(Dispatchers.IO) {
        try {
            if (!isAvailable()) {
                onProgress("Shizuku is not available. Skipping local bundle scan.")
                return@withContext null
            }

            val service = ensureServiceBound()
            if (service == null) {
                onProgress("Failed to connect to Shizuku file service.")
                return@withContext null
            }

            // Step 1: List bundles via Shizuku
            onProgress("Listing local game bundles...")
            val bundleListJson = service.listBundleDirectory(GAME_SHARED_PATH)

            val bundleList = JSONArray(bundleListJson)
            if (bundleList.length() == 0) {
                onProgress("No bundles found in game directory.")
                return@withContext null
            }

            onProgress("Found ${bundleList.length()} bundles. Checking cache...")

            // Step 2: Check which bundles need scanning (Python side)
            val needsScanJson = ModdingService.checkScanNeeded(outputDir, bundleListJson, onProgress)
            val needsScan = JSONArray(needsScanJson)

            // Build hash lookup from the full bundle list
            val hashMap = mutableMapOf<String, String>()
            for (i in 0 until bundleList.length()) {
                val obj = bundleList.getJSONObject(i)
                hashMap[obj.getString("name")] = obj.getString("hash")
            }

            BundleCheckResult(
                bundleListJson = bundleListJson,
                needsScanJson = needsScanJson,
                hashMap = hashMap,
                needsScanCount = needsScan.length()
            )
        } catch (e: Exception) {
            Log.e("ShizukuManager", "Error checking local bundles", e)
            onProgress("Error: ${e.message}")
            null
        }
    }

    /**
     * Phase 2: 執行實際的 bundle 掃描並儲存索引。
     * 應在使用者確認後呼叫。
     *
     * @param outputDir         App 可寫入的目錄
     * @param cacheDir          App 的暫存目錄
     * @param checkResult       Phase 1 回傳的結果
     * @param onBundleProgress  每個 bundle 掃描時的進度 callback
     * @return Triple<成功, 掃描數量, 失敗數量>
     */
    suspend fun executeBundleScan(
        outputDir: String,
        cacheDir: String,
        checkResult: BundleCheckResult,
        onBundleProgress: (currentIndex: Int, total: Int, bundleName: String, message: String) -> Unit
    ): Triple<Boolean, Int, Int> = withContext(Dispatchers.IO) {
        try {
            val service = ensureServiceBound()
                ?: return@withContext Triple(false, 0, 0)

            val needsScan = JSONArray(checkResult.needsScanJson)
            val total = needsScan.length()

            if (total == 0) {
                // Nothing to scan, just finalize with cached data
                ModdingService.finalizeScan(outputDir) { }
                return@withContext Triple(true, 0, 0)
            }

            val tempDir = File(cacheDir, "scan_temp")
            tempDir.mkdirs()
            val tempDataFile = File(tempDir, "__data")

            var scannedCount = 0
            var failedCount = 0

            for (i in 0 until total) {
                val bundleName = needsScan.getString(i)
                val bundleHash = checkResult.hashMap[bundleName] ?: continue

                onBundleProgress(i, total, bundleName, "Copying $bundleName...")

                // Copy __data from game dir to temp via Shizuku
                val gamePath = "$GAME_SHARED_PATH/$bundleName/$bundleHash/__data"
                val copySuccess = service.copyFile(gamePath, tempDataFile.absolutePath)

                if (!copySuccess) {
                    Log.w("ShizukuManager", "Failed to copy bundle $bundleName, skipping")
                    failedCount++
                    continue
                }

                onBundleProgress(i, total, bundleName, "Scanning $bundleName...")

                // Scan via Python
                val (scanSuccess, _, _) = ModdingService.scanSingleBundle(
                    bundleName, bundleHash, tempDataFile.absolutePath
                ) { msg -> onBundleProgress(i, total, bundleName, msg) }

                if (scanSuccess) scannedCount++ else failedCount++

                // Clean up temp file immediately
                tempDataFile.delete()
            }

            // Clean up temp directory
            tempDir.deleteRecursively()

            // Finalize and save index
            onBundleProgress(total, total, "", "Saving index...")
            val (finalSuccess, _) = ModdingService.finalizeScan(outputDir) { msg ->
                onBundleProgress(total, total, "", msg)
            }

            Triple(finalSuccess, scannedCount, failedCount)
        } catch (e: Exception) {
            Log.e("ShizukuManager", "Error scanning local bundles", e)
            Triple(false, 0, 0)
        }
    }

    /**
     * 便捷方法：一次完成列舉 + 掃描 + 儲存。
     * 保留向後兼容，內部使用分段 API。
     */
    suspend fun scanLocalBundles(
        outputDir: String,
        cacheDir: String,
        onProgress: (String) -> Unit
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val checkResult = checkLocalBundles(outputDir, onProgress)
            ?: return@withContext Pair(false, "Failed to check local bundles.")

        if (checkResult.needsScanCount == 0) {
            onProgress("All bundles are up to date. Finalizing...")
            return@withContext ModdingService.finalizeScan(outputDir, onProgress)
        }

        onProgress("${checkResult.needsScanCount} bundles need scanning...")
        val (success, scanned, failed) = executeBundleScan(outputDir, cacheDir, checkResult) { idx, total, name, msg ->
            onProgress("Scanning ${idx + 1}/$total: $name - $msg")
        }

        Pair(success, "Scanned: $scanned, Failed: $failed")
    }

    /**
     * 解綁 UserService
     */
    fun unbindService() {
        try {
            Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        fileService = null
    }
}
