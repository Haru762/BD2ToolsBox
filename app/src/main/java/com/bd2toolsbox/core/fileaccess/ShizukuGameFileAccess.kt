package com.bd2toolsbox.core.fileaccess

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.bd2toolsbox.IFileService
import com.bd2toolsbox.core.engine.ModEngine
import com.bd2toolsbox.core.model.OperationError
import com.bd2toolsbox.core.model.OperationErrorKind
import com.bd2toolsbox.core.storage.GameBundleLayout
import com.bd2toolsbox.data.model.BundleCheckResult
import com.bd2toolsbox.service.ShizukuFileService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File

/**
 * [GameFileAccess] 的 Shizuku 实现（T19）。
 *
 * 逻辑与旧 `ShizukuManager` object 完全相同（绑定/解绑/扫描编排/游戏目录操作），
 * 区别只有一处：**解绑不再由 `Activity.isFinishing` 直接触发**，而是由
 * [acquire]/[release] 维护的占用集合决定——只要还有一个 token 未释放，
 * [unbindService] 的调用就是 no-op。
 *
 * **不用 `@Inject constructor`**：与 [com.bd2toolsbox.core.engine.ChaquopyModEngine] 同样的理由
 * ——进程内只能有一份实例（否则占用计数分裂），由 [GameFileAccess.get] 的惰性单例持有，
 * `di.FileAccessModule` 显式委托到同一个实例，不让 Hilt 自己新建。
 */
class ShizukuGameFileAccess internal constructor() : GameFileAccess {

    private companion object {
        const val TAG = "ShizukuGameFileAccess"
        const val GAME_UNITY_CACHE_PATH =
            "/storage/emulated/0/Android/data/com.neowizgames.game.browndust2/files/UnityCache/"
        const val GAME_SHARED_PATH =
            "/storage/emulated/0/Android/data/com.neowizgames.game.browndust2/files/UnityCache/Shared"
        const val PERMISSION_REQUEST_CODE = 1001
        const val BIND_TIMEOUT_MS = 5000L
        const val LIST_PAGE_SIZE = 500
    }

    // ---------------------------------------------------------------- 占用计数

    /** 计数逻辑抽到 [ConnectionOccupancy]（纯逻辑，可独立测试），这里只接释放完毕后的真实 unbind。 */
    private val occupancy = ConnectionOccupancy(onAllReleased = ::unbindService)

    override fun acquire(): Any = occupancy.acquire()

    override fun release(token: Any) = occupancy.release(token)

    // ---------------------------------------------------------------- 扫描标志

    @Volatile
    private var _scanRunning: Boolean = false
    override val scanRunning: Boolean get() = _scanRunning
    override fun markScanRunning(running: Boolean) {
        _scanRunning = running
    }

    // ---------------------------------------------------------------- 绑定

    private var fileService: IFileService? = null
    private val bindMutex = Mutex()

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName("com.bd2toolsbox", ShizukuFileService::class.java.name)
    )
        .daemon(true)
        .processNameSuffix("file_service")
        .debuggable(android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.R)
        .tag("file_service")
        // v7：listBundleDirectoryPaged 返回值从裸数组改为 envelope
        // (`{"ok":..,"entries"/"error":..}`)，区分「确实为空」与「读失败」（T19）。
        // AIDL 签名未变但返回值语义改了，必须升版号让 Shizuku 丢弃旧 dex（与 v3/v5/v6 同一条规则）。
        .version(7)

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

    override fun isAvailable(): Boolean = isRunning() && hasPermission()

    override fun isRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Exception) {
        false
    }

    override fun hasPermission(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) {
        false
    }

    override fun requestPermission(requestCode: Int) {
        Shizuku.requestPermission(requestCode)
    }

    private suspend fun requireService(): Pair<IFileService?, String?> {
        if (!isRunning()) {
            return null to "Shizuku 未运行，请先启动 Shizuku 再重试。"
        }
        if (!hasPermission()) {
            withContext(Dispatchers.Main) { requestPermission(PERMISSION_REQUEST_CODE) }
            return null to "已发起 Shizuku 授权请求，请在弹窗中允许后再试一次。"
        }
        val service = ensureServiceBound()
        return service to ("无法连接 Shizuku 文件服务，请重启 Shizuku 后重试。".takeIf { service == null })
    }

    private suspend fun ensureServiceBound(): IFileService? {
        fileService?.let { return it }
        return bindMutex.withLock {
            fileService?.let { return@withLock it }

            val deferred = CompletableDeferred<Boolean>()
            pendingBind = deferred
            try {
                Log.d(TAG, "Binding user service...")
                Shizuku.bindUserService(userServiceArgs, serviceConnection)
                val bound = withTimeoutOrNull(BIND_TIMEOUT_MS) { deferred.await() }
                if (bound == true) {
                    Log.d(TAG, "User service bound successfully")
                    fileService
                } else {
                    Log.d(TAG, "Timeout waiting for service bind")
                    pendingBind = null
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind service", e)
                pendingBind = null
                null
            }
        }
    }

    /**
     * 解绑 UserService。**只在没有任何占用 token 时才真正生效**——
     * [release] 是唯一的入口，外部不直接调用这个方法。
     */
    private fun unbindService() {
        try {
            Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        fileService = null
    }

    // ---------------------------------------------------------------- 游戏目录操作

    override suspend fun copyLocalBundleToCache(bundleName: String, hashDir: String, destPath: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                if (!isAvailable()) return@withContext false
                val service = ensureServiceBound() ?: return@withContext false
                val gamePath = "$GAME_SHARED_PATH/$bundleName/$hashDir/__data"
                val ok = service.copyFile(gamePath, destPath)
                if (!ok) Log.w(TAG, "Local bundle copy failed: $gamePath")
                ok
            } catch (e: Exception) {
                Log.e(TAG, "Error copying local bundle $bundleName", e)
                false
            }
        }

    override suspend fun copyGameDataForRecovery(bundleName: String, hashDir: String?, destPath: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                if (!isAvailable()) return@withContext false
                val service = ensureServiceBound() ?: return@withContext false
                val gamePath = GameBundleLayout.remoteDataPath(GAME_SHARED_PATH, bundleName, hashDir)
                val ok = service.copyFile(gamePath, destPath)
                if (!ok) Log.w(TAG, "Recovery copy failed: $gamePath")
                ok
            } catch (e: Exception) {
                Log.e(TAG, "Error copying game data for recovery $bundleName", e)
                false
            }
        }

    /**
     * 用 [PagedDirectoryLister] 驱动真实分页协议：一页失败即整次失败，
     * 不再把「不可用」「某页失败」「解析失败」都折叠成同一个 `null`。
     */
    override suspend fun listGameBundles(): DirectoryListingResult = withContext(Dispatchers.IO) {
        if (!isAvailable()) {
            return@withContext DirectoryListingResult.Failure(
                OperationError(OperationErrorKind.NOT_CONNECTED, "Shizuku 不可用，无法读取游戏目录")
            )
        }
        val service = ensureServiceBound() ?: return@withContext DirectoryListingResult.Failure(
            OperationError(OperationErrorKind.NOT_CONNECTED, "无法连接 Shizuku 文件服务")
        )
        val result = PagedDirectoryLister(LIST_PAGE_SIZE).list { offset, limit ->
            try {
                val pageJson = service.listBundleDirectoryPaged(GAME_SHARED_PATH, offset, limit)
                val envelope = JSONObject(pageJson)
                if (!envelope.optBoolean("ok", false)) {
                    return@list DirectoryPage.Failed(
                        OperationError(
                            OperationErrorKind.IO,
                            "游戏目录列举失败（offset=$offset）：${envelope.optString("error", "unknown")}"
                        )
                    )
                }
                val arr = envelope.optJSONArray("entries") ?: JSONArray()
                val entries = (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val name = o.optString("name").ifBlank { null } ?: return@mapNotNull null
                    val hash = o.optString("hash").ifBlank { null } ?: return@mapNotNull null
                    DirectoryEntry(name = name, key = hash, sizeBytes = o.optLong("size", -1L))
                }
                DirectoryPage.Content(entries)
            } catch (e: Exception) {
                Log.e(TAG, "Error listing game bundles page at offset $offset", e)
                DirectoryPage.Failed(
                    OperationError(
                        OperationErrorKind.MALFORMED_DATA,
                        "游戏目录列举失败（offset=$offset）：${e.message}",
                        detail = e.toString()
                    )
                )
            }
        }
        if (result is DirectoryListingResult.Failure) {
            Log.e(TAG, "Error listing game bundles: ${result.error.message}")
        }
        result
    }

    override suspend fun installConvertedBundle(sourceDir: String, bundleName: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val (service, error) = requireService()
            if (service == null) return@withContext Pair(false, error!!)
            try {
                val ok = service.copyDirectory(sourceDir, "$GAME_SHARED_PATH/$bundleName")
                if (ok) Pair(true, "已装入游戏目录")
                else Pair(false, "拷贝到游戏目录失败：$bundleName")
            } catch (e: Exception) {
                Log.e(TAG, "Error installing converted bundle $bundleName", e)
                Pair(false, "出错：${e.message}")
            }
        }

    // ---------------------------------------------------------------- bundle 扫描编排

    override suspend fun checkLocalBundles(
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

            onProgress("Listing local game bundles...")
            val bundleList = JSONArray()
            var offset = 0
            while (true) {
                val pageJson = service.listBundleDirectoryPaged(GAME_SHARED_PATH, offset, LIST_PAGE_SIZE)
                val envelope = JSONObject(pageJson)
                if (!envelope.optBoolean("ok", false)) {
                    onProgress("Failed to list game bundles: ${envelope.optString("error", "unknown")}")
                    return@withContext null
                }
                val page = envelope.optJSONArray("entries") ?: JSONArray()
                for (i in 0 until page.length()) bundleList.put(page.get(i))
                if (page.length() < LIST_PAGE_SIZE) break
                offset += LIST_PAGE_SIZE
            }
            if (bundleList.length() == 0) {
                onProgress("No bundles found in game directory.")
                return@withContext null
            }
            onProgress("Found ${bundleList.length()} bundles. Checking cache...")

            val needsScanJson = ModEngine.get().checkScanNeeded(outputDir, bundleList.toString(), onProgress)
            val needsScan = JSONArray(needsScanJson)

            val hashMap = HashMap<String, String>(bundleList.length())
            for (i in 0 until bundleList.length()) {
                val obj = bundleList.getJSONObject(i)
                hashMap[obj.getString("name")] = obj.getString("hash")
            }

            BundleCheckResult(
                bundleListJson = bundleList.toString(),
                needsScanJson = needsScanJson,
                hashMap = hashMap,
                needsScanCount = needsScan.length()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error checking local bundles", e)
            onProgress("Error: ${e.message}")
            null
        }
    }

    override suspend fun executeBundleScan(
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
                ModEngine.get().finalizeScan(outputDir) { }
                return@withContext Triple(true, 0, 0)
            }

            val tempDir = File(cacheDir, "scan_temp").apply { mkdirs() }
            val tempDataFile = File(tempDir, "__data")

            var scanned = 0
            var failed = 0
            val checkpointEvery = 10
            for (i in 0 until total) {
                val bundleName = needsScan.getString(i)
                val bundleHash = checkResult.hashMap[bundleName] ?: continue

                onBundleProgress(i, total, bundleName, "Copying $bundleName...")
                val copied = service.copyFile(
                    "$GAME_SHARED_PATH/$bundleName/$bundleHash/__data",
                    tempDataFile.absolutePath
                )
                if (!copied) {
                    Log.w(TAG, "Failed to copy bundle $bundleName, skipping")
                    failed++
                    continue
                }

                onBundleProgress(i, total, bundleName, "Scanning $bundleName...")
                val (ok, _, _) = ModEngine.get().scanSingleBundle(
                    bundleName, bundleHash, tempDataFile.absolutePath
                ) { msg -> onBundleProgress(i, total, bundleName, msg) }
                if (ok) scanned++ else failed++

                tempDataFile.delete()

                if ((i + 1) % checkpointEvery == 0) {
                    ModEngine.get().checkpointScan(outputDir)
                }
            }
            tempDir.deleteRecursively()

            onBundleProgress(total, total, "", "Saving index...")
            val (finalSuccess, _) = ModEngine.get().finalizeScan(outputDir) { msg ->
                onBundleProgress(total, total, "", msg)
            }
            Triple(finalSuccess, scanned, failed)
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning local bundles", e)
            Triple(false, 0, 0)
        }
    }
}
