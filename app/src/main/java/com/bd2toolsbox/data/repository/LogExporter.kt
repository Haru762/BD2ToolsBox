package com.bd2toolsbox.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断日志导出：把应用自己进程的 logcat（含 Kotlin 侧 Log 与 Python 侧
 * stdout/stderr —— Chaquopy 把它们重定向到 python.stdout/python.stderr 标签）
 * 收进一个文本文件，存到 Download 并交给系统分享面板。
 *
 * 真机排查全靠它：比如「预解包哪些目标没缓存」——PrepackService 每个目标
 * 都有 `预解包 N/M: 名字` 一行日志，用户导出一份就能定位，不用接电脑。
 *
 * 读取自己进程的日志不需要 READ_LOGS 权限（API 16+ 起 `logcat --pid=自己`
 * 允许读自己的条目）。进程重启前的日志属于旧 pid —— 丢掉就丢掉：崩溃后
 * 的现场有系统崩溃收集兜底，这里要的是「刚才那次运行发生了什么」。
 */
object LogExporter {

    private const val TAG = "LogExporter"

    /** logcat 命令超时：环形缓冲区就几百 KB，卡住说明环境异常，别让 UI 干等。 */
    private const val LOGCAT_TIMEOUT_MS = 10_000L

    /**
     * 收集日志到 cacheDir 下的临时文件。返回该文件（调用方负责用完删除）。
     * 任何一步失败都保证返回一个带头部+错误说明的文件 —— 用户点了导出，
     * 至少要拿回「为什么导不出来」。
     */
    fun collect(context: Context): File {
        val header = buildHeader(context)
        val out = File(context.cacheDir,
            "bd2_log_${System.currentTimeMillis()}.txt")

        // --pid 只取自己进程；-v time 带时间戳；缓冲区默认 main 就够
        // （crash 缓冲区的内容对「刚才那次运行」没用）
        val log = try {
            runLogcat(arrayOf("logcat", "-d", "-v", "time",
                              "--pid=${android.os.Process.myPid()}"))
        } catch (e: Exception) {
            Log.w(TAG, "logcat 收集失败", e)
            "（logcat 收集失败：${e.message}）"
        }

        out.writeText(header + "\n" + log)
        return out
    }

    /**
     * 存进公共 Download 目录（MediaStore，API 29+ 无需权限；旧版本走
     * 传统路径）。返回写入的 Uri，失败返回 null。
     */
    fun exportToDownloads(context: Context, content: File): Uri? {
        val name = "BD2ToolsBox日志_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.txt"
        var uri: Uri? = null
        return try {
            val values = android.content.ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            }
            uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            // 先插入再写是 MediaStore 的固定用法，但**写不成就必须把那一行删掉**。
            //
            // 以前是 `openOutputStream(uri)?.use { ... }`：流为 null 时 `?.use` 被跳过，
            // 然后照样 `return uri` —— 用户在 Download 里看到一个 0 字节的空日志，
            // 而 App 报的是「已保存」。写一半抛异常时那一行也不会被删，同样留个空壳。
            val written = context.contentResolver.openOutputStream(uri)?.use { os ->
                content.inputStream().use { it.copyTo(os) }
                true
            } ?: false
            if (!written) {
                throw java.io.IOException("打不开输出流")
            }
            uri
        } catch (e: Exception) {
            Log.w(TAG, "导出到 Download 失败", e)
            // 把刚才插进去的那一行收回，不给用户留空壳
            uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            null
        }
    }

    /** 拉起系统分享面板，把刚存的日志发出去（QQ/微信/网盘随意）。 */
    fun share(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // app context 起分享面板必须带 NEW_TASK
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(Intent.createChooser(intent, "分享诊断日志")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w(TAG, "分享面板拉起失败", e)
        }
    }

    /** 头部自含诊断上下文：用户发来这份文件时不需要任何额外信息。 */
    private fun buildHeader(context: Context): String {
        val pm = context.packageManager
        val version = try {
            val info = pm.getPackageInfo(context.packageName, 0)
            "versionName=${info.versionName} versionCode=${info.longVersionCode}"
        } catch (e: Exception) {
            "version=unknown"
        }
        return """
            ===== BD2 ToolsBox 诊断日志 =====
            $version
            device=${Build.MANUFACTURER} ${Build.MODEL}
            android=${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})
            time=${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}
            pid=${android.os.Process.myPid()}
            =================================

        """.trimIndent()
    }

    private fun runLogcat(command: Array<String>): String {
        val process = ProcessBuilder(*command)
            .redirectErrorStream(true)
            .start()
        val buffer = StringBuilder()
        val readerThread = Thread {
            runCatching {
                process.inputStream.bufferedReader().use { reader ->
                    val chars = CharArray(4096)
                    while (true) {
                        val n = reader.read(chars)
                        if (n < 0) break
                        synchronized(buffer) { buffer.append(chars, 0, n) }
                    }
                }
            }
        }.apply {
            isDaemon = true
            name = "bd2-logcat-reader"
            start()
        }
        return try {
            val finished = process.waitFor(
                LOGCAT_TIMEOUT_MS,
                java.util.concurrent.TimeUnit.MILLISECONDS
            )
            if (!finished) {
                process.destroyForcibly()
            }
            readerThread.join(1_000L)
            val output = synchronized(buffer) { buffer.toString() }
            if (!finished) {
                output + "\n（logcat 超时被截断）"
            } else {
                output
            }
        } finally {
            process.destroy()
        }
    }
}
