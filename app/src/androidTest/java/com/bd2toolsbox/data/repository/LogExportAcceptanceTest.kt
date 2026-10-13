package com.bd2toolsbox.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * T56：诊断日志导出的**端到端**（设备）。
 *
 * 计划把「日志导出」列在核心功能域里，而它的用户可见后果很直接：
 * 导出一个空文件比导出失败更糟 —— 用户以为拿到了日志，拿去排查却发现什么都没有。
 *
 * 本类走的是生产路径 `LogExporter.collect` → `exportToDownloads`（真的写进
 * MediaStore 的 Download），断言的是**内容真的落盘了**，不是「函数返回了非 null」。
 */
@RunWith(AndroidJUnit4::class)
class LogExportAcceptanceTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val exported = mutableListOf<android.net.Uri>()

    @After
    fun cleanup() {
        exported.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        exported.clear()
    }

    @Test
    fun collectedLogIsNonEmptyAndContainsThisProcessOutput() {
        val file = LogExporter.collect(context)
        try {
            assertTrue("收集到的日志文件必须存在", file.isFile)
            assertTrue("收集到的日志不能是空的（长度 ${file.length()}）", file.length() > 0)
            val text = file.readText()
            // 头部必须写明设备与版本，否则拿到日志的人不知道这是哪台机器的
            assertTrue("日志头部应含应用信息：${text.take(200)}", text.contains("com.bd2toolsbox"))
            // 本用例自己写一行，确认 logcat 真的抓到了**本进程**的输出
            android.util.Log.i("LogExportAcceptanceTest", "BD2_LOG_EXPORT_PROBE")
            Thread.sleep(1200)
            val second = LogExporter.collect(context)
            try {
                assertTrue(
                    "日志里应当能看到本进程刚写的探针行",
                    second.readText().contains("BD2_LOG_EXPORT_PROBE")
                )
            } finally {
                second.delete()
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun exportedFileInDownloadsHasExactlyTheSourceContent() {
        val source = File(context.cacheDir, "t56-log-export-source.txt")
        val payload = ("line-%d\n".format(0)).repeat(0) +
            (1..500).joinToString("") { "log line $it\n" }
        source.writeText(payload)

        val uri = LogExporter.exportToDownloads(context, source)
        assertNotNull("导出应当成功（Download 可写）", uri)
        exported += uri!!

        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        assertEquals(
            "导出文件的内容必须与源文件逐字节相同（不能是 0 字节空壳）",
            source.readBytes().size,
            bytes.size
        )
        assertEquals("内容也必须一致", payload, String(bytes, Charsets.UTF_8))

        source.delete()
    }

    @Test
    fun aMissingSourceFileFailsInsteadOfLeavingAnEmptyEntryInDownloads() {
        // 源文件不存在 → 必须明确失败，且**不能在 Download 里留下 0 字节空壳**
        val missing = File(context.cacheDir, "t56-log-export-missing-${System.nanoTime()}.txt")
        assertTrue(!missing.exists())

        val uri = LogExporter.exportToDownloads(context, missing)

        if (uri != null) {
            // 如果实现选择「建了行但内容为空也算成功」，那就是本用例要拦的行为
            val size = context.contentResolver.openInputStream(uri)!!.use { it.available().toLong() }
            exported += uri
            assertTrue("源文件不存在时不能在 Download 里留下空文件（长度 $size）", size > 0)
        }
        // 返回 null 是预期结果：调用方会提示「日志保存失败，请重试」
    }
}
