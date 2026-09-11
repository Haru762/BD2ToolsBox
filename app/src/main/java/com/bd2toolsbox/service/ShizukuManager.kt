package com.bd2toolsbox.service

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.bd2toolsbox.IFileService
import com.bd2toolsbox.data.model.BundleCheckResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import rikka.shizuku.Shizuku
import java.io.File

/**
 * Shizuku 的访问层：权限检查、UserService 绑定、游戏目录操作、bundle 扫描编排。
 *
 * App 没有权限直接写游戏目录，实际读写都由 [ShizukuFileService]（跑在
 * shell UID 的 UserService）执行；这里管它的生命周期和面向调用方的
 * 语义化接口。
 */
object ShizukuManager {

    private const val TAG = "ShizukuManager"

    /**
     * 进程级「扫描进行中」标志。扫描跑在 [com.bd2toolsbox.ui.viewmodel.MainViewModel]
     * 的 installScope 上（活过界面销毁），期间用户冷启动 app 会新建 ViewModel、
     * 走启动初始化的 [checkLocalBundles] —— 那会调 python check_scan_needed
     * 重置其全局 _scan_state，把在途扫描的累积状态搅掉。初始化检查必须先看这里。
     */
    @Volatile
    var scanRunning: Boolean = false
        private set

    /** 仅供扫描流程自己翻转；外部只读。 */
    fun markScanRunning(running: Boolean) {
        scanRunning = running
    }

    private const val GAME_UNITY_CACHE_PATH =
        "/storage/emulated/0/Android/data/com.neowizgames.game.browndust2/files/UnityCache/"
    private const val DOWNLOAD_SHARED_PATH =
        "/storage/emulated/0/Download/Shared"
    private const val GAME_SHARED_PATH =
        "/storage/emulated/0/Android/data/com.neowizgames.game.browndust2/files/UnityCache/Shared"

    private const val PERMISSION_REQUEST_CODE = 1001
    private const val BIND_TIMEOUT_MS = 5000L

    private var fileService: IFileService? = null
    private val bindMutex = Mutex()

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName("com.bd2toolsbox", ShizukuFileService::class.java.name)
    )
        // 守护模式：同一份 args 只对应一个服务进程，重复绑定复用它。
        //
        // 原先 .daemon(false) 本意「用完就退」，但没人调解绑，且服务在 shell UID
        // 下、force-stop app 带不走它 —— 结果每次启动都留一个约 130MB 的孤儿
        // 进程（实测从 12 个涨到 13 个、合计 1.66GB，模拟器随后失去响应）。
        // 孤儿的 binder 已断、Shizuku 也不再认它们，解绑收不回来，只能从根上
        // 不产生孤儿：守护模式下无论 app 怎么退出，下次启动都复用那一个进程。
        .daemon(true)
        .processNameSuffix("file_service")
        .debuggable(android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.R)
        .tag("file_service")
        // v3: copyDirectory 加了源目录存在性检查；listBundleDirectory 返回带 __data
        //     的 size。AIDL 签名未变但实现变了，必须升版本号让 Shizuku 丢弃旧 dex。
        // v4: 守护模式（见上）。版本变化 = Shizuku 起新进程。
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

    // ---------------------------------------------------------------- 可用性与权限

    /** Shizuku 已运行且已授权。 */
    fun isAvailable(): Boolean = isRunning() && hasPermission()

    /** Shizuku 进程在跑（不看权限）。 */
    fun isRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Exception) {
        false
    }

    /** 已取得授权。 */
    fun hasPermission(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) {
        false
    }

    /** 发起授权请求。 */
    fun requestPermission(requestCode: Int) {
        Shizuku.requestPermission(requestCode)
    }

    /**
     * 操作游戏目录前的前置检查：运行 → 授权 → 绑定服务，三关全过返回
     * 服务实例；哪关没过返回对应的用户可读错误。
     */
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

    // ---------------------------------------------------------------- 服务绑定

    /** 绑定 UserService 拿 IFileService；已绑定直接复用。绑定带 5 秒超时防挂死。 */
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

    /** 解绑 UserService。 */
    fun unbindService() {
        try {
            Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        fileService = null
    }

    // ---------------------------------------------------------------- 游戏目录操作

    /**
     * 把游戏本地的原版 bundle 复制到 [destPath]，作为重打包基底。
     *
     * 仅应在干净检测判定该 bundle 为「干净原版」后调用 —— 本地那份若已装着
     * mod，拿它当基底会把贴图叠上去。成功即省一次 CDN 下载；失败返回 false，
     * 调用方回退到 downloadBundle。
     */
    suspend fun copyLocalBundleToCache(bundleName: String, hashDir: String, destPath: String): Boolean =
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

    /**
     * 列出游戏 Shared/ 下每个 bundle 的 (hash 目录名, __data 字节数)。
     * 供干净检测与 catalog 权威值比对。Shizuku 不可用返回 null ——
     * 是「无法校验」，不是「都干净」，调用方必须区分这两者。
     */
    suspend fun listGameBundles(): Map<String, Pair<String, Long>>? = withContext(Dispatchers.IO) {
        try {
            if (!isAvailable()) return@withContext null
            val service = ensureServiceBound() ?: return@withContext null
            parseBundleListing(service.listBundleDirectory(GAME_SHARED_PATH))
        } catch (e: Exception) {
            Log.e(TAG, "Error listing game bundles", e)
            null
        }
    }

    private fun parseBundleListing(json: String): Map<String, Pair<String, Long>> {
        val arr = JSONArray(json)
        val out = HashMap<String, Pair<String, Long>>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name").ifBlank { null } ?: continue
            val hash = o.optString("hash").ifBlank { null } ?: continue
            out[name] = hash to o.optLong("size", -1L)
        }
        return out
    }

    /**
     * 把一个已转换好的 bundle 目录整体拷进游戏的 Shared/ 下。
     *
     * [sourceDir] 必须是 Shizuku 服务（shell UID）能读到的**真实路径**，不能是
     * SAF 的 content:// Uri —— SAF 权限授予的是 app，没法传递给独立进程的
     * shell 服务。调用方要么把 documentId 还原成 /storage/emulated/0/… 真实
     * 路径，要么先落到 externalCacheDir 再传进来。
     */
    suspend fun installConvertedBundle(sourceDir: String, bundleName: String): Pair<Boolean, String> =
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

    /**
     * 把 Download/Shared 目录搬进游戏 UnityCache（转换产物的「一键装入游戏」）。
     * 拷贝成功后清掉 Download 侧的源目录。
     */
    suspend fun moveDownloadToGame(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val (service, error) = requireService()
        if (service == null) return@withContext Pair(false, error!!)
        try {
            val sourceDir = File(DOWNLOAD_SHARED_PATH)
            if (!sourceDir.isDirectory) {
                return@withContext Pair(false, "未找到 Download/Shared 目录，可能已被装入或被清理。")
            }

            Log.d(TAG, "Starting to copy using Shizuku")
            val ok = service.copyDirectory(DOWNLOAD_SHARED_PATH, GAME_UNITY_CACHE_PATH + "Shared")
            if (ok) {
                Log.d(TAG, "Copy successful, cleaning up source")
                sourceDir.deleteRecursively()
                Pair(true, "已成功装入游戏目录！")
            } else {
                Log.e(TAG, "Copy failed in ShizukuFileService")
                Pair(false, "复制文件到游戏目录失败。")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during Shizuku file operation", e)
            Pair(false, "出错：${e.message}")
        }
    }

    // ---------------------------------------------------------------- bundle 扫描编排

    /**
     * 扫描 Phase 1：列游戏目录、对照缓存得出待扫名单。
     * 不执行实际扫描，结果交给调用方决定是否继续（弹确认框）。
     * 失败或游戏目录无 bundle 返回 null。
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

            onProgress("Listing local game bundles...")
            val bundleListJson = service.listBundleDirectory(GAME_SHARED_PATH)
            val bundleList = JSONArray(bundleListJson)
            if (bundleList.length() == 0) {
                onProgress("No bundles found in game directory.")
                return@withContext null
            }
            onProgress("Found ${bundleList.length()} bundles. Checking cache...")

            // python 侧对照缓存给出待扫名单
            val needsScanJson = ModdingService.checkScanNeeded(outputDir, bundleListJson, onProgress)
            val needsScan = JSONArray(needsScanJson)

            val hashMap = HashMap<String, String>(bundleList.length())
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
            Log.e(TAG, "Error checking local bundles", e)
            onProgress("Error: ${e.message}")
            null
        }
    }

    /**
     * 扫描 Phase 2：逐个 bundle「Shizuku 拷到临时目录 → python 解析」，
     * 最后 finalize 落盘索引。应在用户确认后调用。
     *
     * @return Triple<成功, 扫描数, 失败数>
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
                // 没有要扫的，只用缓存数据收个尾
                ModdingService.finalizeScan(outputDir) { }
                return@withContext Triple(true, 0, 0)
            }

            val tempDir = File(cacheDir, "scan_temp").apply { mkdirs() }
            val tempDataFile = File(tempDir, "__data")

            var scanned = 0
            var failed = 0
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
                val (ok, _, _) = ModdingService.scanSingleBundle(
                    bundleName, bundleHash, tempDataFile.absolutePath
                ) { msg -> onBundleProgress(i, total, bundleName, msg) }
                if (ok) scanned++ else failed++

                tempDataFile.delete()     // 立刻清，60MB 级文件不留过夜
            }
            tempDir.deleteRecursively()

            onBundleProgress(total, total, "", "Saving index...")
            val (finalSuccess, _) = ModdingService.finalizeScan(outputDir) { msg ->
                onBundleProgress(total, total, "", msg)
            }
            Triple(finalSuccess, scanned, failed)
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning local bundles", e)
            Triple(false, 0, 0)
        }
    }

    /** 便捷入口：一次完成列举 + 扫描 + 落盘（内部走分段 API）。 */
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
}
