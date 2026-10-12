package com.bd2toolsbox.data.remote

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * T56：下载失败**不能留下半个文件**，也**不能毁掉已有的有效缓存**。
 *
 * ## 为什么值得单独一个类
 *
 * `GamekeeApi.download` 以前是 `dest.outputStream().use { input.copyTo(it) }` ——
 * 直接往最终路径写。连接中途断掉时 `copyTo` 抛异常、被 catch 吞成 `false`，
 * 但**半个文件已经落在 `dest` 上**。而缓存读取只判 `exists() && length() > 0`
 * （`loadThumb` 等），于是那个截断的文件会被当成有效缓存，**而且永远不会重试**；
 * 顺带还会把上一次的有效缓存毁掉。
 *
 * 这里用**真的会断的 HTTP 响应**来验，不是 mock：本地起一个 ServerSocket，
 * 声明 `Content-Length` 很大、只发一小段就关连接 —— 这就是真实断网时的形状。
 */
@RunWith(AndroidJUnit4::class)
class GamekeeDownloadAtomicityTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir: File by lazy {
        File(context.cacheDir, "t56-download-atomic").apply { mkdirs() }
    }

    private val servers = mutableListOf<ServerSocket>()

    /**
     * 服务器**真的被连上过**吗。
     *
     * 不加这条断言，失败用例会因为「根本没连上」（比如明文被策略拦掉）而通过 ——
     * 那与「连上了、传了一半断了」是两回事，却给出同一个结果。实测踩过：
     * 没有 debug 的明文豁免时，成功用例失败、失败用例却全「过」。
     */
    private val accepted = java.util.concurrent.atomic.AtomicBoolean(false)

    @After
    fun tearDown() {
        servers.forEach { runCatching { it.close() } }
        servers.clear()
        dir.deleteRecursively()
    }

    /** 起一个「发一半就断」的服务器，返回它的 URL。 */
    private fun startTruncatingServer(declaredLength: Int, actuallySend: Int): String {
        val server = ServerSocket(0)
        servers += server
        thread(isDaemon = true) {
            try {
                val socket: Socket = server.accept()
                accepted.set(true)
                socket.use { s ->
                    // 读掉请求头
                    val input = s.getInputStream()
                    val buffer = ByteArray(4096)
                    val deadline = System.currentTimeMillis() + 5000
                    while (System.currentTimeMillis() < deadline) {
                        if (input.available() > 0) {
                            val n = input.read(buffer)
                            if (n <= 0) break
                            if (String(buffer, 0, n).contains("\r\n\r\n")) break
                        } else {
                            Thread.sleep(10)
                        }
                    }
                    val out = s.getOutputStream()
                    out.write(
                        (
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: image/png\r\n" +
                                "Content-Length: $declaredLength\r\n" +
                                "\r\n"
                            ).toByteArray(Charsets.US_ASCII)
                    )
                    out.write(ByteArray(actuallySend) { 0x41 })
                    out.flush()
                    // 不发完就关 —— 客户端会读到 EOF，copyTo 抛异常
                }
            } catch (_: Exception) {
                // 服务器被关掉是正常的
            }
        }
        return "http://127.0.0.1:${server.localPort}/half.png"
    }

    @Test
    fun aTruncatedDownloadLeavesNoFileBehind() {
        val url = startTruncatingServer(declaredLength = 200_000, actuallySend = 64)
        val dest = File(dir, "thumb-half.png")

        val ok = GamekeeApi().download(url, dest)

        assertTrue("服务器必须真的被连上过，否则这个用例什么都没验到", accepted.get())
        assertFalse("发一半就断的下载不能报成功", ok)
        assertFalse("不能在目标路径留下半个文件：${dest.length()} 字节", dest.exists())
        val leftovers = dir.listFiles()?.filter { it.name != dest.name }?.map { it.name } ?: emptyList()
        assertEquals("不能在目录里留下临时件：$leftovers", emptyList<String>(), leftovers)
    }

    @Test
    fun aFailedDownloadDoesNotDestroyAnExistingGoodCache() {
        val dest = File(dir, "thumb-good.png")
        val good = ByteArray(4096) { 0x5A }
        dest.writeBytes(good)
        val before = dest.readBytes()

        // 同一个路径、一次注定失败的下载
        val url = startTruncatingServer(declaredLength = 200_000, actuallySend = 64)
        val ok = GamekeeApi().download(url, dest)

        assertTrue("服务器必须真的被连上过", accepted.get())
        assertFalse("失败的下载不能报成功", ok)
        assertTrue("目标必须还在", dest.exists())
        assertTrue(
            "已有的有效缓存不能被失败的下载毁掉（长度 ${dest.length()}）",
            dest.readBytes().contentEquals(before)
        )
    }

    @Test
    fun anUnreachableHostFailsWithoutCreatingAnything() {
        val dest = File(dir, "thumb-unreachable.png")
        // 端口 1 上不会有服务
        val ok = GamekeeApi().download("http://127.0.0.1:1/nope.png", dest)
        assertFalse("连不上的地址必须返回失败", ok)
        assertFalse("连不上时不该创建目标", dest.exists())
        val leftovers = dir.listFiles()?.map { it.name } ?: emptyList()
        assertEquals("不该留下任何文件：$leftovers", emptyList<String>(), leftovers)
    }

    @Test
    fun aSuccessfulDownloadStillLandsAtomically() {
        // 正常路径也要验：改完之后成功的下载必须照常落盘、内容完整
        val payload = ByteArray(32_768) { (it % 251).toByte() }
        val server = ServerSocket(0)
        servers += server
        thread(isDaemon = true) {
            try {
                server.accept().also { accepted.set(true) }.use { s ->
                    val input = s.getInputStream()
                    val buffer = ByteArray(4096)
                    while (input.available() == 0) Thread.sleep(10)
                    input.read(buffer)
                    val out = s.getOutputStream()
                    out.write(
                        (
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: image/png\r\n" +
                                "Content-Length: ${payload.size}\r\n" +
                                "\r\n"
                            ).toByteArray(Charsets.US_ASCII)
                    )
                    out.write(payload)
                    out.flush()
                }
            } catch (_: Exception) {
            }
        }
        val dest = File(dir, "thumb-ok.png")

        val ok = GamekeeApi().download("http://127.0.0.1:${server.localPort}/ok.png", dest)

        assertTrue("服务器必须真的被连上过", accepted.get())
        assertTrue("正常下载必须成功", ok)
        assertTrue("内容必须完整", dest.readBytes().contentEquals(payload))
        val leftovers = dir.listFiles()?.filter { it.name != dest.name }?.map { it.name } ?: emptyList()
        assertEquals("成功后不该留下临时件：$leftovers", emptyList<String>(), leftovers)
    }
}
