package com.bd2toolsbox.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.bd2toolsbox.MainActivity
import com.bd2toolsbox.feature.install.InstallExecutor
import com.bd2toolsbox.feature.install.MoveCommitHost
import com.bd2toolsbox.feature.install.MoveCommitRequest
import com.bd2toolsbox.feature.install.MoveCommitResult
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/**
 * 转换 / 装入期间的前台服务。
 *
 * 职责：把进程钉在前台、在通知栏显示进度，并且**亲自执行提交**（T22）。
 *
 * 为什么只搬「提交」这一段：整条流程牵着 Python 重打包、CDN 下载、Shizuku 写入、
 * 原版备份、装入账本五摊子事，全搬进服务的收益远不如风险（这一条判断没有变）。
 * 但**真正被点名的缺陷**是第 2 条：提交跑在 viewModelScope 上，用户退出界面时
 * ViewModel onCleared、协程被取消，会在游戏目录留下半个 `__data`。
 * 所以这里只把 [InstallExecutor.move] 那次提交搬进来 —— 它本来就已经是
 * 「冻结事实进、真实结果出」的形态，不读 UI 状态、不持调用方闭包。
 * 提交完成后的 UI 状态刷新仍然留在 ViewModel。
 *
 * 「放着不管就被杀」是两个原因叠出来的，所以要两处一起改：
 *   1. 没有前台服务 → 切后台后系统把整个进程当空闲后台回收
 *   2. 安装跑在 viewModelScope 上 → 用户退出界面时 ViewModel onCleared、协程被取消
 * 只做第 1 条的话，退出界面照样断在一半（v6.5 做预解包时踩过这个坑）；
 * 只做第 2 条的话，进程被杀了协程也活不下来。
 *
 * 通知里刻意**不放取消按钮**：重打包中途断掉会在游戏目录留下半个 __data，
 * 而这条流程目前也没有干净的中断点。要停就等它跑完再卸载。
 */
@AndroidEntryPoint // T16：本进程前台服务接入 Hilt；T22 注入 InstallExecutor
class InstallService : Service() {

    @Inject lateinit var installExecutor: InstallExecutor

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null

    /**
     * 生产宿主：把提交交给本服务执行（T22）。
     *
     * 服务持有自己的 scope 与前台状态，**界面销毁不会取消它** —— 这正是把提交从
     * viewModelScope 搬过来的理由。请求通过 companion 直传（同进程，避免 Binder 1MB 上限）。
     */
    class Host @Inject constructor(
        @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
    ) : MoveCommitHost {
        override suspend fun execute(request: MoveCommitRequest): MoveCommitResult? {
            clearRunResult()
            if (!run(context, request)) return null
            return runResult.first { it?.id == request.id }
        }
    }

    companion object {
        private const val TAG = "InstallService"
        const val CHANNEL_ID = "install"
        /** 和 PrepackService 的 1001 分开，两者可能同时挂着。 */
        const val NOTIFICATION_ID = 1002

        const val ACTION_START = "com.bd2toolsbox.action.INSTALL_START"
        const val ACTION_UPDATE = "com.bd2toolsbox.action.INSTALL_UPDATE"
        const val ACTION_STOP = "com.bd2toolsbox.action.INSTALL_STOP"
        const val ACTION_RUN = "com.bd2toolsbox.action.INSTALL_RUN"

        @Volatile
        private var pendingRun: MoveCommitRequest? = null

        private val _runResult = MutableStateFlow<MoveCommitResult?>(null)
        val runResult: StateFlow<MoveCommitResult?> = _runResult.asStateFlow()

        fun clearRunResult() {
            _runResult.value = null
        }

        /**
         * 把一次提交交给服务执行（T22）。返回 false 表示前台服务启动被拒 ——
         * 调用方必须据此停下并告诉用户，不能照常开跑。
         */
        fun run(context: Context, request: MoveCommitRequest): Boolean {
            pendingRun = request
            val intent = Intent(context, InstallService::class.java).apply {
                action = ACTION_RUN
                putExtra(EXTRA_TOTAL, request.targets.size)
            }
            return try {
                context.startForegroundService(intent)
                true
            } catch (e: Exception) {
                pendingRun = null
                Log.w(TAG, "提交任务的前台服务启动被拒: ${e.message}")
                false
            }
        }

        private const val EXTRA_DONE = "done"
        private const val EXTRA_TOTAL = "total"
        private const val EXTRA_TEXT = "text"

        /** notify 调太密会被系统限流丢弃。调用方已经节流过一层，这里再兜一次。 */
        private const val NOTIFY_INTERVAL_MS = 700L

        /**
         * 返回 false 表示系统拒绝启动前台服务（T15）：Android 12+ 从后台调用会抛
         * `ForegroundServiceStartNotAllowedException`。调用方必须据此停下、告诉用户，
         * 不能照常开跑 —— 没有前台服务，切后台后进程随时可能被回收。
         */
        fun start(context: Context, total: Int): Boolean =
            send(context, ACTION_START) { putExtra(EXTRA_TOTAL, total) }

        fun update(context: Context, done: Int, total: Int, text: String) {
            send(context, ACTION_UPDATE) {
                putExtra(EXTRA_DONE, done)
                putExtra(EXTRA_TOTAL, total)
                putExtra(EXTRA_TEXT, text)
            }
        }

        fun stop(context: Context) {
            send(context, ACTION_STOP) {}
        }

        private inline fun send(context: Context, action: String, fill: Intent.() -> Unit): Boolean {
            val intent = Intent(context, InstallService::class.java).apply {
                this.action = action
                fill()
            }
            return try {
                // 启动那一下必须用 startForegroundService（API 26+）：服务要在 5 秒内
                // 调 startForeground。后续的 UPDATE/STOP 走普通 startService 就够了 ——
                // 服务已经在前台，再走 startForegroundService 反而会在某些机型上
                // 因为「重复要求前台」被记一笔违规。
                if (action == ACTION_START) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            } catch (e: Exception) {
                // START 失败由调用方处理（见 start）；UPDATE/STOP 失败多半是进程正在
                // 退出或服务已经停了，通知没了不影响装入本身的结果，只记一行。
                Log.w(TAG, "发送 $action 失败: ${e.message}")
                false
            }
        }
    }

    private var lastNotifyMs = 0L
    private var total = 0
    private var lastDone = 0
    private var lastText = ""

    /** 是否已进入前台。重复 start 不能再次 startForeground（会把进度归零）。 */
    private var foregroundStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RUN -> {
                val request = pendingRun
                pendingRun = null
                if (request == null) {
                    // 没有冻结输入就没有可执行的东西：不谎报「正在装入」
                    Log.w(TAG, "收到 ACTION_RUN 但没有待执行任务，忽略")
                    stopForegroundCompat()
                    stopSelf()
                    return START_NOT_STICKY
                }
                total = request.targets.size
                if (!foregroundStarted) {
                    foregroundStarted = true
                    startForeground(NOTIFICATION_ID, buildNotification(0, total, "正在装入…"))
                }
                worker?.cancel()
                worker = scope.launch { executeRun(request) }
            }

            ACTION_START -> {
                total = intent.getIntExtra(EXTRA_TOTAL, total)
                if (foregroundStarted) {
                    // T26：重复 start（误触/界面重建/系统重投递）不得把进度归零 ——
                    // 与 ScanService 同一契约。只按新总数重绘当前进度。
                    Log.i(TAG, "前台服务已在跑，重复 start 忽略重置（本批 $total 个）")
                    getSystemService(NotificationManager::class.java)
                        ?.notify(NOTIFICATION_ID, buildNotification(lastDone, total, lastText))
                } else {
                    Log.i(TAG, "前台服务启动，本批 $total 个")
                    foregroundStarted = true
                    // 必须先 startForeground 再干别的，否则 5 秒后系统判定违规并杀服务
                    startForeground(NOTIFICATION_ID, buildNotification(0, total, "准备中…"))
                }
            }

            ACTION_UPDATE -> {
                val done = intent.getIntExtra(EXTRA_DONE, 0)
                val t = intent.getIntExtra(EXTRA_TOTAL, total).also { total = it }
                val text = intent.getStringExtra(EXTRA_TEXT) ?: ""
                lastDone = done
                lastText = text
                val now = System.currentTimeMillis()
                // 最后一个完成（done == total）时不节流：那是用户最关心的一帧
                if (now - lastNotifyMs >= NOTIFY_INTERVAL_MS || (t > 0 && done >= t)) {
                    lastNotifyMs = now
                    getSystemService(NotificationManager::class.java)
                        ?.notify(NOTIFICATION_ID, buildNotification(done, t, text))
                }
            }

            ACTION_STOP -> {
                Log.i(TAG, "前台服务收尾")
                foregroundStarted = false
                lastDone = 0
                lastText = ""
                stopForegroundCompat()
                stopSelf()
            }
        }
        // 不要 START_STICKY：进程被杀后重启一个空 Intent 的服务毫无意义 ——
        // 真正在装的那个协程已经随进程没了，留个空通知只会误导。
        return START_NOT_STICKY
    }

    /**
     * 真正执行提交（T22）。
     *
     * 跑在服务自己的 scope 上，**不随 ViewModel 销毁而取消** —— 这正是把这段搬进来的理由。
     * 结果（含失败原因）一律落进 [RunResult]，由 ViewModel 决定怎么向用户交代；
     * 这里不吞异常、也不谎报成功。
     */
    private suspend fun executeRun(request: MoveCommitRequest) {
        val result = try {
            val executed = installExecutor.move(
                id = com.bd2toolsbox.core.model.OperationId(request.id),
                shared = File(request.sharedPath),
                stagingRoot = File(request.stagingPath),
                targets = request.targets,
                restoredTargets = request.removals
            )
            MoveCommitResult(request.id, executed.submission, executed.ledgerNotices, null)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // 服务被系统停掉时的取消：如实记录，不当作成功
            MoveCommitResult(request.id, null, emptyList(), "装入被中断：服务已停止")
        } catch (e: Exception) {
            Log.e(TAG, "提交执行失败", e)
            MoveCommitResult(request.id, null, emptyList(), e.message ?: e.javaClass.simpleName)
        }
        _runResult.value = result
        // 提交是这条前台服务的唯一目的，做完即收场（通知与前台状态一起收）
        foregroundStarted = false
        stopForegroundCompat()
        stopSelf()
    }

    override fun onDestroy() {
        foregroundStarted = false
        stopForegroundCompat()
        super.onDestroy()
    }

    private fun createChannel() {
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "转换与装入",
                // LOW：常驻进度条不该出声也不该弹横幅，它只是让你知道还在跑
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "转换 mod 并装入游戏时显示进度" }
        )
    }

    private fun buildNotification(done: Int, total: Int, text: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE or Intent.FLAG_ACTIVITY_NEW_TASK
        // SINGLE_TOP 不能加：它的位值(0x20000000)落在 PendingIntent 保留位区间，
        // 与 UPDATE_CURRENT 组合后 getActivity 静默返回 null —— 通知点不动
        // （模拟器探针实测：base ✓ +NEW_TASK ✓ +both null）。NEW_TASK 本身
        // 已让已有实例置顶，足够。
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), flags
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (total > 0) "正在转换并装入 $done/$total" else "正在转换并装入")
            .setContentText(text.ifBlank { "处理中…" })
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(total, done, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /**
     * 收掉通知。STOP_FOREGROUND_REMOVE 只撤销「由 startForeground 关联的」那一条，
     * 而进度是用 notify() 按同 id 覆盖更新的、覆盖后它成了独立通知，
     * 所以必须再显式 cancel 一次 —— 少这一步的话跑完还挂着且划不掉
     * （PrepackService 那边实测过 flags 从 0x6a 变 0xa 仍在）。
     */
    private fun stopForegroundCompat() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.w(TAG, "stopForeground 失败: ${e.message}")
        }
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }
}
