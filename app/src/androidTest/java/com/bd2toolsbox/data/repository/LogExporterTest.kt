package com.bd2toolsbox.data.repository

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * LogExporter 的契约：把自己进程的 logcat 收进一个文本文件，
 * 供用户在报告问题时导出（真机排查全靠它 —— PrepackService 的逐条
 * 「预解包 N/M」日志就是典型场景）。
 */
@RunWith(AndroidJUnit4::class)
class LogExporterTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun collectedLogContainsJustWrittenMarker() {
        val marker = "bd-log-export-marker-${UUID.randomUUID()}"
        android.util.Log.i("LogExporterTest", marker)

        val file = LogExporter.collect(context)
        try {
            val text = file.readText()
            assertTrue("导出的日志必须包含刚写入的标记行\n${text.takeLast(2000)}",
                text.contains(marker))
        } finally {
            file.delete()
        }
    }

    @Test
    fun collectedLogHasDiagnosticHeader() {
        android.util.Log.i("LogExporterTest", "header-check")
        val file = LogExporter.collect(context)
        try {
            val text = file.readText()
            // 头部要自含：版本、机型、Android 版本 —— 用户发过来时不依赖上下文
            assertTrue("应包含应用版本号", text.contains("versionName=") ||
                    text.contains("version="))
            assertTrue("应包含机型", text.contains("device=") || text.contains("model="))
            assertTrue("应包含 Android 版本", text.contains("android=") ||
                    text.contains("sdk="))
        } finally {
            file.delete()
        }
    }

    @Test
    fun collectedLogIsPlainTextFileWithReasonableSize() {
        val file = LogExporter.collect(context)
        try {
            assertTrue("应为 .txt 文本文件", file.name.endsWith(".txt"))
            assertTrue("文件不该为空", file.length() > 0)
            // logcat 环形缓冲区通常几百 KB~1MB；异常巨大说明收集逻辑失控
            assertTrue("不该超过 8MB（实际 ${file.length() / 1048576.0}MB）",
                file.length() < 8 * 1024 * 1024)
        } finally {
            file.delete()
        }
    }
}
