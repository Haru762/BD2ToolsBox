package com.bd2toolsbox.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import com.chaquo.python.Python
import com.bd2toolsbox.MainActivity
import com.bd2toolsbox.data.repository.PreviewCacheRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * 批量预解包的前台服务。
 *
 * 这活儿原先跑在 `viewModelScope` 上：切到别的 app 仍会继续，但进程一旦被系统回收就中断。
 * 按 176 个产物的规模全解要十几分钟，期间息屏或切走较久，被回收是常态而非例外。
 * 前台服务 + 常驻通知把进程提到不易被杀的优先级，这才是长任务该有的载体。
 *
 * **为什么把循环整个搬过来，而不是只用服务「保活」**：若逻辑仍留在 ViewModel，
 * 用户退出界面时 ViewModel 会 onCleared、viewModelScope 随之取消，任务照样断——
 * 服务活着也没用。搬进来才真正与 UI 生命周期解耦。
 *
 * 进度经 [progress] 这个 companion 里的 StateFlow 回传。服务与 Activity 同进程，
 * 直接共享 Flow 即可，不必为此走 Binder 或广播。
 *
 * 中断安全性不依赖本服务：缓存是逐个提交的，所以无论被杀还是被取消，
 * 下次再点都会跳过已完成的接着做，不会白干。前台服务只是让「被杀」变得罕见。
 */
class PrepackService : Service() {

    // 这两个 data class 放在类体里而不是 companion 里：嵌套在 companion 中的类，
    // 外部得写 PrepackService.Companion.Target 才引用得到，而放这一层就是自然的
    // PrepackService.Target。Kotlin 的嵌套类默认是静态的，companion 里照样能用。

    /**
     * 一次预解包的进度。null 表示没在跑。
     *
     * [done]/[total] 是 bundle 级的整体进度，[current] 是当前那个的显示名，
     * [detail] 是它内部在干什么（「拷贝 62%」/「解包 12/48」）。
     *
     * 有 [detail] 是因为单个 bundle 要花几秒到几十秒（先从 SAF 拷几十 MB 的 __data，
     * 再跑 UnityPy 解包），期间 done 一动不动 —— 只看整体进度条会以为卡死了。
     */
    data class Progress(
        val done: Int,
        val total: Int,
        val current: String,
        val detail: String = ""
    )

    /** 待解包的一项。[treeUri] 是产物文件夹（bundle 名那层）的 SAF URI。 */
    data class Target(
        val treeUri: String,
        val bundleName: String,
        val hashDir: String,
        val size: Long,
        val displayName: String
    )

    companion object {
        private const val TAG = "PrepackService"
        private const val CHANNEL_ID = "prepack"
        private const val NOTIFICATION_ID = 1001

        /** 界面细进度的最小推送间隔（毫秒）。每推一次触发一次重组，不节流会刷爆。 */
        private const val PUSH_INTERVAL_MS = 150L

        /** 通知的最小更新间隔。notify 调太密会被系统限流丢弃，也没必要那么勤。 */
        private const val NOTIFY_INTERVAL_MS = 800L

        const val ACTION_START = "com.bd2toolsbox.action.PREPACK_START"
        const val ACTION_CANCEL = "com.bd2toolsbox.action.PREPACK_CANCEL"

        private val _progress = MutableStateFlow<Progress?>(null)
        val progress: StateFlow<Progress?> = _progress.asStateFlow()

        /**
         * 待办目标的进程内交接。曾经走 Intent extras，但 1044 个目标（URI 里还带
         * 中文路径的百分号转义）会把 Binder 事务顶过 1MB 上限 —— startForegroundService
         * 直接抛 TransactionTooLargeException，任务根本起不来。服务与本 app 同进程
         * （manifest 无 android:process），companion 直传没有任何跨进程代价。
         * onStartCommand 取走后置空；START_NOT_STICKY 不会被系统重启，没有「重启后
         * 丢失」的窗口。
         */
        @Volatile
        private var pendingTargets: List<Target>? = null

        fun start(context: Context, targets: List<Target>) {
            if (targets.isEmpty()) return
            pendingTargets = targets
            val intent = Intent(context, PrepackService::class.java).apply { action = ACTION_START }
            // API 26+ 起后台启动的服务必须用 startForegroundService，且服务要在 5 秒内
            // 调 startForeground。这里由用户点击触发，app 在前台，不受 Android 12 的
            // 「后台不得启动前台服务」限制。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancel(context: Context) {
            val intent = Intent(context, PrepackService::class.java).apply {
                action = ACTION_CANCEL
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                // 服务已经不在了，进度归零就够了
                _progress.value = null
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null

    /**
     * 取消旗标。worker.cancel() 只能取消协程，Chaquopy 的 python 调用是
     * 同步阻塞——靠这个旗标让进度回调（python 每个资产调一次）把取消
     * 传进 python，当前 mod 立刻收手，不用等它解完。
     */
    @Volatile
    private var cancelRequested = false
    private lateinit var previewCache: PreviewCacheRepository

    override fun onCreate() {
        super.onCreate()
        previewCache = PreviewCacheRepository(applicationContext)
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            Log.d(TAG, "收到取消")
            cancelRequested = true
            worker?.cancel()
            worker = null
            _progress.value = null
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        // 目标从 companion 取（见 start() 的注释，Intent 装不下大批量）。取走即清，
        // 重复的 start 意图不会把同一批目标解两遍。
        val targets = pendingTargets.also { pendingTargets = null }.orEmpty()
        if (targets.isEmpty()) {
            // 经 startForegroundService 拉起的服务必须先挂通知再退，否则系统按
            // 「5 秒内没 startForeground」直接判死。这里挂上随即收掉，通知一闪而过。
            startForeground(NOTIFICATION_ID, buildNotification(0, 0, ""))
            stopSelf()
            return START_NOT_STICKY
        }

        // 必须先 startForeground 再干活，否则 5 秒后系统会判定违规
        startForeground(NOTIFICATION_ID, buildNotification(0, targets.size, "准备中…"))

        // 已经在跑就不重复启动 —— 重复点击只会互相抢 CPU
        if (worker?.isActive == true) {
            Log.d(TAG, "已有任务在跑，忽略本次请求")
            return START_NOT_STICKY
        }

        worker = scope.launch { run(targets) }
        return START_NOT_STICKY
    }

    private suspend fun run(targets: List<Target>) {
        cancelRequested = false
        val ctx = applicationContext
        try {
            if (!Python.isStarted()) {
                Python.start(com.chaquo.python.android.AndroidPlatform(ctx))
            }

            var done = 0
            for (t in targets) {
                // 必须查「当前协程」而不是 scope：取消走的是 worker.cancel()，
                // 那只取消这个 Job，scope 自身仍然是 active 的。而循环体内 unpackBundle
                // 是阻塞调用、没有挂起点，取消不会自动抛 CancellationException ——
                // 不显式检查的话「取消」按钮会完全失效，任务照跑到底。
                if (!currentCoroutineContext().isActive) {
                    Log.d(TAG, "已取消，停在 $done/${targets.size}")
                    break
                }
                // 每个 mod 一行日志：真机上预解包被系统杀掉时（LMK/厂商省电），
                // 进度条只停在最后一个 mod，这行日志是唯一的死因线索。
                Log.i(TAG, "预解包 ${done + 1}/${targets.size}: ${t.displayName}")
                _progress.value = Progress(done, targets.size, t.displayName, "准备中…")
                notify(done, targets.size, t.displayName, "准备中…")

                val tmp = File(ctx.cacheDir, "prepack_tmp")
                try {
                    tmp.parentFile?.mkdirs()
                    val doc = DocumentFile.fromTreeUri(ctx, Uri.parse(t.treeUri))
                        ?.findFile(t.hashDir)?.findFile("__data")
                    if (doc == null) { done++; continue }
                    // 这里不能写 `?: run { done++; return@run }` —— 那个 run 是 stdlib 的，
                    // return@run 只跳出那个小 lambda，然后照样往下走去解包一个空的 tmp，
                    // 而且 done 会被加两次、进度错乱。（原 ViewModel 版就是这么写的。）
                    val ins = ctx.contentResolver.openInputStream(doc.uri)
                    if (ins == null) { done++; continue }

                    // 手动循环而不是 copyTo：这一段要搬几十 MB，是「看着卡住」的主要来源，
                    // 得能报出百分比。顺带每块都查一次取消，让「停止」在大文件中途也灵敏。
                    ins.use { input ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(256 * 1024)
                            var copied = 0L
                            var lastPush = 0L
                            while (true) {
                                if (!currentCoroutineContext().isActive) break
                                val read = input.read(buf)
                                if (read <= 0) break
                                out.write(buf, 0, read)
                                copied += read
                                // 节流：StateFlow 每刷一次就触发一次重组，
                                // 256 KB 一块的话不节流会一秒刷几十次。
                                val now = System.currentTimeMillis()
                                if (now - lastPush >= PUSH_INTERVAL_MS) {
                                    lastPush = now
                                    val pct = if (t.size > 0)
                                        (copied * 100 / t.size).coerceIn(0, 100) else 0
                                    push(done, targets.size, t.displayName, "读取 $pct%")
                                }
                            }
                        }
                    }

                    val outDir = previewCache.prepareDir(t.bundleName, t.hashDir)
                    var lastPush = 0L
                    val (ok, _) = ModdingService.unpackBundle(
                        tmp.absolutePath, outDir.absolutePath, fast = true
                    ) { line ->
                        // Python 侧每个 asset 报一次，形如
                        // "Processing asset 12/48: illust_charxxxx"。原先这个回调是空的 ——
                        // 进度明明有，只是被丢掉了，于是解包那几十秒界面全静止。
                        val now = System.currentTimeMillis()
                        if (now - lastPush >= PUSH_INTERVAL_MS) {
                            lastPush = now
                            push(done, targets.size, t.displayName, describeUnpack(line))
                        }
                        // 返回取消旗标（lambda 最后一行即返回值；回调跑在
                        // python 线程上，不能查协程 isActive，读旗标）
                        cancelRequested
                    }
                    if (ok) {
                        val files = outDir.listFiles()?.filter { it.isFile } ?: emptyList()
                        val hasSkel = files.any {
                            it.name.endsWith(".skel", true) || it.name.endsWith(".json", true)
                        }
                        val hasAtlas = files.any { it.name.endsWith(".atlas", true) }
                        // 提交条件必须与 isValid 完全一致（skel + atlas + size）：
                        // 此前只查 skel，skel-but-no-atlas 的产物 commit 了却永远
                        // isValid=false，每次预解包都重解。无预览价值的统一走负缓存
                        // —— 否则它们永远不被跳过，每轮都从头解一遍（真机实测
                        // 卡在列表开头那二十几个包的就是这类）。
                        if (t.size > 0 && hasSkel && hasAtlas) {
                            previewCache.commit(t.bundleName, t.hashDir, t.size)
                        } else {
                            previewCache.delete(t.bundleName, t.hashDir)
                            previewCache.markNoPreview(t.bundleName, t.hashDir, t.size)
                        }
                    } else {
                        // 解包失败可能是临时的（磁盘满/进程被杀），不记负缓存，留重试机会
                        previewCache.delete(t.bundleName, t.hashDir)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    previewCache.delete(t.bundleName, t.hashDir)
                } finally {
                    if (tmp.exists()) tmp.delete()
                }
                done++
                _progress.value = Progress(done, targets.size, t.displayName)
                notify(done, targets.size, t.displayName)
            }
            Log.d(TAG, "批量预解包结束: $done/${targets.size}")
        } finally {
            // 无论正常结束、出错还是被取消，都要把进度清掉并收掉服务，
            // 否则通知会一直挂着，用户以为还在跑
            _progress.value = null
            stopForegroundCompat()
            stopSelf()
        }
    }

    // ---------------------------------------------------------------- 进度上报

    /**
     * 推一次细进度（界面 + 通知）。
     *
     * 通知另做一层节流：NotificationManager.notify 调太密会被系统限流丢弃，
     * 而且通知栏那行字疯狂闪也不好看。界面用 [PUSH_INTERVAL_MS]，通知用
     * [NOTIFY_INTERVAL_MS]（更长）。
     */
    private var lastNotifyMs = 0L

    private fun push(done: Int, total: Int, current: String, detail: String) {
        _progress.value = Progress(done, total, current, detail)
        val now = System.currentTimeMillis()
        if (now - lastNotifyMs >= NOTIFY_INTERVAL_MS) {
            lastNotifyMs = now
            notify(done, total, current, detail)
        }
    }

    /**
     * 把 Python 那句英文进度转成一句中文。
     *
     * 上游报的形如 "Processing asset 12/48: illust_charxxxx"，
     * 也有 "Starting to unpack ..." / "Successfully loaded bundle. Found 48 assets."。
     * 认不出来的一律说「解包中…」—— 与其把英文原文丢给用户，不如给个稳定的说法。
     */
    private fun describeUnpack(line: String): String {
        val m = Regex("""asset\s+(\d+)\s*/\s*(\d+)""", RegexOption.IGNORE_CASE).find(line)
        if (m != null) return "解包 ${m.groupValues[1]}/${m.groupValues[2]}"
        if (line.contains("loaded bundle", true)) return "读取资源表…"
        if (line.contains("complete", true)) return "收尾…"
        return "解包中…"
    }

    // ---------------------------------------------------------------- 通知

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        // IMPORTANCE_LOW：要常驻但不该响铃或弹横幅，它只是个进度指示
        val ch = NotificationChannel(CHANNEL_ID, "批量预解包", NotificationManager.IMPORTANCE_LOW)
        ch.description = "显示批量预解包的进度"
        ch.setShowBadge(false)
        mgr.createNotificationChannel(ch)
    }

    private fun buildNotification(
        done: Int,
        total: Int,
        current: String,
        detail: String = ""
    ): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), flags
        )
        val cancelIntent = PendingIntent.getService(
            this, 1,
            Intent(this, PrepackService::class.java).apply { action = ACTION_CANCEL },
            flags
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("批量预解包 $done/$total")
            .setContentText(if (detail.isBlank()) current else "$current · $detail")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(total, done, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "取消", cancelIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun notify(done: Int, total: Int, current: String, detail: String = "") {
        try {
            val mgr = getSystemService(NotificationManager::class.java) ?: return
            mgr.notify(NOTIFICATION_ID, buildNotification(done, total, current, detail))
        } catch (e: Exception) {
            // 用户拒了通知权限（Android 13+）时会走到这里。任务照跑，只是看不见进度——
            // 前台服务的保活效果不依赖通知能否显示。
            Log.d(TAG, "更新通知失败（可能未授予通知权限）: ${e.message}")
        }
    }

    private fun stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            // 还得显式 cancel 一次。STOP_FOREGROUND_REMOVE 只撤销「由 startForeground
            // 关联的」那条通知，而进度是用 NotificationManager.notify() 按同一 id 覆盖
            // 更新的 —— 覆盖之后它就成了一条独立通知，stopForeground 撤不掉。
            // 实测漏这一步的后果：任务结束后通知一直挂着，且因为 setOngoing(true)
            // 用户连划都划不掉（flags 从 0x6a 变 0xa，只是少了前台服务标志）。
            getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        _progress.value = null
    }
}
