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

/**
 * 游戏资源扫描期间的前台服务：把进程钉在前台，并在通知栏显示进度条。
 *
 * 全量扫描一次要 20–40 分钟，用户几乎必然会切出去干别的 —— 没有这条服务，
 * 切后台后整个进程被当空闲后台回收，扫描断在一半。与 [InstallService] 同构
 * （那条流程踩过的坑这里不重踩，见它的类注释）；扫描逻辑仍留在
 * [com.bd2toolsbox.ui.viewmodel.MainViewModel]，服务只负责通知与保活。
 *
 * 与 InstallService 的两点差异：
 *  - 扫描**完成时要留一条可划掉的摘要通知**（成功/失败数）。装入完成时用户
 *    本来就盯着 app，扫描完成时用户多半在别的 app 里 —— 通知是唯一能让
 *    他知道「可以回来了」的渠道。
 *  - 重复 start 不重置进度：扫描中途的 start 只可能来自异常路径（误触、
 *    界面重建后的初始化检查），归零会让用户以为扫描从头来过。
 */
class ScanService : Service() {

    companion object {
        private const val TAG = "ScanService"
        private const val CHANNEL_ID = "scan"

        /** InstallService 是 1002、PrepackService 是 1001；扫描与装入不同时挂也可能重叠换界面，分开。 */
        const val NOTIFICATION_ID = 1003

        const val ACTION_START = "com.bd2toolsbox.action.SCAN_START"
        const val ACTION_UPDATE = "com.bd2toolsbox.action.SCAN_UPDATE"
        const val ACTION_FINISH = "com.bd2toolsbox.action.SCAN_FINISH"
        const val ACTION_STOP = "com.bd2toolsbox.action.SCAN_STOP"

        private const val EXTRA_DONE = "done"
        private const val EXTRA_TOTAL = "total"
        private const val EXTRA_BUNDLE = "bundle"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_FAILED = "failed"

        /** notify 调太密会被系统限流丢弃，这里兜一层。 */
        private const val NOTIFY_INTERVAL_MS = 700L

        fun start(context: Context, total: Int) {
            send(context, ACTION_START) { putExtra(EXTRA_TOTAL, total) }
        }

        fun update(context: Context, done: Int, total: Int, bundleName: String, text: String) {
            send(context, ACTION_UPDATE) {
                putExtra(EXTRA_DONE, done)
                putExtra(EXTRA_TOTAL, total)
                putExtra(EXTRA_BUNDLE, bundleName)
                putExtra(EXTRA_TEXT, text)
            }
        }

        /** 扫描结束：把通知换成可划掉的摘要（成功/失败数），不再常驻。 */
        fun finish(context: Context, succeeded: Int, failed: Int) {
            send(context, ACTION_FINISH) {
                putExtra(EXTRA_DONE, succeeded)
                putExtra(EXTRA_FAILED, failed)
            }
        }

        fun stop(context: Context) = send(context, ACTION_STOP) {}

        private inline fun send(context: Context, action: String, fill: Intent.() -> Unit) {
            val intent = Intent(context, ScanService::class.java).apply {
                this.action = action
                fill()
            }
            try {
                // 与 InstallService 同理：只有 START 需要 startForegroundService（5 秒内
                // 必须调 startForeground），后续走普通 startService。
                if (action == ACTION_START && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // 通知没了不影响扫描本身的结果，只记一行。
                Log.w(TAG, "发送 $action 失败: ${e.message}")
            }
        }
    }

    private var lastNotifyMs = 0L
    private var total = 0
    private var started = false
    private var done = 0
    private var bundleName = ""
    private var text = ""
    /** finish 已发过摘要：onDestroy 清退时要跳过，否则摘要把刚挂上去就被删。 */
    private var summaryPosted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!started) {
                    started = true
                    done = 0
                    bundleName = ""
                    text = ""
                }
                total = intent.getIntExtra(EXTRA_TOTAL, total)
                Log.i(TAG, "前台服务启动，本批 $total 个")
                // 每次 startForegroundService 都要求 5 秒内兑现一次 startForeground，
                // 没兑现系统就按 ForegroundServiceDidNotStartInTime 杀进程。重复 START
                // （扫描进行中又来一次）也要调 —— startForeground 幂等，只是重申前台
                // 状态；进度字段保留当前值，不归零。
                startForeground(NOTIFICATION_ID, buildProgressNotification())
            }

            ACTION_UPDATE -> {
                if (!started) {
                    // 没经过 START 就来 UPDATE（异常路径）：当没收到，别凭空挂通知
                } else {
                    done = intent.getIntExtra(EXTRA_DONE, done)
                    total = intent.getIntExtra(EXTRA_TOTAL, total)
                    bundleName = intent.getStringExtra(EXTRA_BUNDLE) ?: ""
                    text = intent.getStringExtra(EXTRA_TEXT) ?: ""
                    val now = System.currentTimeMillis()
                    // 最后一个完成（done == total）时不节流
                    if (now - lastNotifyMs >= NOTIFY_INTERVAL_MS || (total > 0 && done >= total)) {
                        lastNotifyMs = now
                        getSystemService(NotificationManager::class.java)
                            ?.notify(NOTIFICATION_ID, buildProgressNotification())
                    }
                }
            }

            ACTION_FINISH -> {
                val succeeded = intent.getIntExtra(EXTRA_DONE, 0)
                val failed = intent.getIntExtra(EXTRA_FAILED, 0)
                Log.i(TAG, "扫描收尾：成功 $succeeded 失败 $failed")
                summaryPosted = true
                postSummary(succeeded, failed)
                stopSelf()
            }

            ACTION_STOP -> {
                Log.i(TAG, "前台服务收尾")
                stopForegroundCompat()
                stopSelf()
            }
        }
        // 同 InstallService：进程被杀后重启空服务毫无意义，真正在扫的协程已随进程没了
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // finish 路径不能在这里清退：摘要刚 post 完 stopSelf 就触发 onDestroy，
        // 无差别 cancel 会把它一并删掉（测试抓到的时序）。摘要要留着给用户看，
        // 下次 START 用同 id 覆盖它。
        if (!summaryPosted) stopForegroundCompat()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "资源扫描",
                // LOW：常驻进度条不出声不弹横幅
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "扫描游戏资源建立索引时显示进度" }
        )
    }

    private fun buildProgressNotification(): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), flags
        )
        val content = listOf(bundleName, text)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
            .ifBlank { "处理中…" }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (total > 0) "正在扫描游戏资源 $done/$total" else "正在扫描游戏资源")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(total, done, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** 完成摘要：可划掉、不常驻。内容要自含 —— 用户没盯着进度，回来只看这一条。 */
    private fun postSummary(succeeded: Int, failed: Int) {
        // 先撤下常驻进度条（它由 startForeground 关联，STOP_FOREGROUND_REMOVE 只收它），
        // 再用同 id post 一条普通通知 —— 顺序反了会闪一条「空」的过渡帧
        stopForegroundCompat()
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), flags
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("资源扫描完成")
            .setContentText("成功 $succeeded 个，失败 $failed 个。回到应用后请重新扫描 mod 文件夹。")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, n)
    }

    /** 与 InstallService.stopForegroundCompat 同构：收掉 startForeground 关联的通知。 */
    private fun stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "stopForeground 失败: ${e.message}")
        }
        // STOP_FOREGROUND_REMOVE 只撤销「由 startForeground 关联的」那一条；进度通知
        // （notify 覆盖更新的）和 finish 的摘要都是独立通知，必须再显式 cancel ——
        // 少这一步的话摘要会一直挂在通知栏（InstallService 注释里踩过的同一个坑）。
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }
}
