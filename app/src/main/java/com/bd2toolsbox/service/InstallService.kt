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
 * 转换 / 装入期间的前台服务。
 *
 * 只做一件事：把进程钉在前台，并在通知栏显示进度。转换与装入的逻辑仍留在
 * [com.bd2toolsbox.ui.viewmodel.MainViewModel] —— 那条流程牵着 Python
 * 重打包、CDN 下载、Shizuku 写入、原版备份、装入账本五摊子事，整个搬进服务的
 * 收益远不如风险。
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
class InstallService : Service() {

    companion object {
        private const val TAG = "InstallService"
        private const val CHANNEL_ID = "install"

        /** 和 PrepackService 的 1001 分开，两者可能同时挂着。 */
        private const val NOTIFICATION_ID = 1002

        const val ACTION_START = "com.bd2toolsbox.action.INSTALL_START"
        const val ACTION_UPDATE = "com.bd2toolsbox.action.INSTALL_UPDATE"
        const val ACTION_STOP = "com.bd2toolsbox.action.INSTALL_STOP"

        private const val EXTRA_DONE = "done"
        private const val EXTRA_TOTAL = "total"
        private const val EXTRA_TEXT = "text"

        /** notify 调太密会被系统限流丢弃。调用方已经节流过一层，这里再兜一次。 */
        private const val NOTIFY_INTERVAL_MS = 700L

        fun start(context: Context, total: Int) {
            send(context, ACTION_START) { putExtra(EXTRA_TOTAL, total) }
        }

        fun update(context: Context, done: Int, total: Int, text: String) {
            send(context, ACTION_UPDATE) {
                putExtra(EXTRA_DONE, done)
                putExtra(EXTRA_TOTAL, total)
                putExtra(EXTRA_TEXT, text)
            }
        }

        fun stop(context: Context) = send(context, ACTION_STOP) {}

        private inline fun send(context: Context, action: String, fill: Intent.() -> Unit) {
            val intent = Intent(context, InstallService::class.java).apply {
                this.action = action
                fill()
            }
            try {
                // 启动那一下必须用 startForegroundService（API 26+）：服务要在 5 秒内
                // 调 startForeground。后续的 UPDATE/STOP 走普通 startService 就够了 ——
                // 服务已经在前台，再走 startForegroundService 反而会在某些机型上
                // 因为「重复要求前台」被记一笔违规。
                if (action == ACTION_START && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // 进程正在退出、或服务已经停了。通知没了不影响装入本身的结果，
                // 所以只记一行，不往上抛。
                Log.w(TAG, "发送 $action 失败: ${e.message}")
            }
        }
    }

    private var lastNotifyMs = 0L
    private var total = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                total = intent.getIntExtra(EXTRA_TOTAL, 0)
                Log.i(TAG, "前台服务启动，本批 $total 个")
                // 必须先 startForeground 再干别的，否则 5 秒后系统判定违规并杀服务
                startForeground(NOTIFICATION_ID, buildNotification(0, total, "准备中…"))
            }

            ACTION_UPDATE -> {
                val done = intent.getIntExtra(EXTRA_DONE, 0)
                val t = intent.getIntExtra(EXTRA_TOTAL, total).also { total = it }
                val text = intent.getStringExtra(EXTRA_TEXT) ?: ""
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
                stopForegroundCompat()
                stopSelf()
            }
        }
        // 不要 START_STICKY：进程被杀后重启一个空 Intent 的服务毫无意义 ——
        // 真正在装的那个协程已经随进程没了，留个空通知只会误导。
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopForegroundCompat()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
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
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "stopForeground 失败: ${e.message}")
        }
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }
}
