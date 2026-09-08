package com.bd2toolsbox.data.repository

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Spine 运行时（pixi-spine）的按需下载。
 *
 * **为什么不打包进 APK。** pixi-spine 用的是 Esoteric Software 的 SPINE-LICENSE，
 * 那是一份专有的 source-available 许可：要求每个使用者自己持有 Spine Editor 授权。
 * 本项目按 GPLv3 分发，而 GPL 不允许对下游接收者附加这类额外条件 —— 两者不兼容。
 * 所以 APK 里不带它，改成首次用「预览动画」时从 npm CDN 取一份放进私有目录：
 * 我们分发的是 GPL 代码，Spine 运行时是用户自己从上游获取的。
 *
 * 这和 Linux 发行版不打包专有编解码器、让用户自行安装是同一个路子。
 *
 * 放 filesDir 而不是 cacheDir：它是 1.2 MB 的必需组件，被系统清缓存清掉的话
 * 用户得重新下一次（而这个 CDN 在国内未必通），不值当省这点空间。
 */
class SpineRuntimeRepository private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "SpineRuntime"

        /** 跟原先随包那份对齐。换版本要连带验一次预览，pixi-spine 的 API 跨大版本会变。 */
        private const val VERSION = "3.1.2"

        /** UMD 构建。npm 包里没有 dist/pixi-spine.js，只有 .umd.js 这一份浏览器可直接 <script> 引的。 */
        private const val SOURCE_URL =
            "https://cdn.jsdelivr.net/npm/pixi-spine@$VERSION/dist/pixi-spine.umd.js"

        /**
         * 完整的包约 1.21 MB。低于这个数说明拿到的不是它 ——
         * 常见情况是运营商/网关返回了一页 HTML 错误页，那玩意儿几 KB，
         * 直接当 js 加载会在 WebView 里报语法错误，比「没下到」更难查。
         */
        private const val MIN_BYTES = 900_000L

        @Volatile
        private var instance: SpineRuntimeRepository? = null

        fun get(context: Context): SpineRuntimeRepository =
            instance ?: synchronized(this) {
                instance ?: SpineRuntimeRepository(context.applicationContext).also { instance = it }
            }
    }

    /** 落地路径带版本号：换版本时不会拿旧文件当新的用。 */
    val file: File
        get() = File(appContext.filesDir, "spine-runtime/pixi-spine-$VERSION.js")

    fun isReady(): Boolean = file.exists() && file.length() >= MIN_BYTES

    /**
     * 确保运行时就绪。
     *
     * @return null 表示可以用了；非 null 是一句**给用户看**的失败原因。
     *   这里不能像头像那样静默失败 —— 没有运行时，预览就是一片白，
     *   必须把「为什么」和「怎么办」直接说出来。
     */
    suspend fun ensure(): String? {
        if (isReady()) return null
        return withContext(Dispatchers.IO) { download() }
    }

    private fun download(): String? {
        var conn: HttpURLConnection? = null
        // 先写 .part 再改名。半个文件若直接叫最终名，下次 isReady 可能因为
        // 已经超过 MIN_BYTES 而误判为可用，然后在 WebView 里断在中间报错。
        val part = File(file.parentFile, file.name + ".part")
        return try {
            file.parentFile?.mkdirs()
            conn = (URL(SOURCE_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "BD2ToolsBox")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "HTTP ${conn.responseCode}: $SOURCE_URL")
                return "下载 Spine 运行时失败（HTTP ${conn.responseCode}）。\n" +
                        "它不随安装包分发，需要联网取一次（约 1.2 MB）。"
            }
            conn.inputStream.use { input ->
                part.outputStream().use { out -> input.copyTo(out) }
            }
            if (part.length() < MIN_BYTES) {
                val got = part.length()
                part.delete()
                Log.w(TAG, "文件过小: $got B")
                return "取到的文件不完整（只有 ${got / 1024} KB，应为约 1180 KB）。\n" +
                        "多半是网络中途被拦或返回了错误页，换个网络再试。"
            }
            file.delete()
            if (!part.renameTo(file)) {
                part.delete()
                return "写入失败，请检查存储空间。"
            }
            Log.i(TAG, "Spine 运行时就绪: ${file.length()} B")
            null
        } catch (e: Exception) {
            part.delete()
            Log.w(TAG, "下载失败: ${e.message}", e)
            "连不上 Spine 运行时的下载源（cdn.jsdelivr.net）。\n" +
                    "预览动画需要先取一次这个组件（约 1.2 MB），装好后就不用再下。\n" +
                    "国内网络可能需要代理。也可以手动把 pixi-spine.umd.js 放到：\n" +
                    file.absolutePath
        } finally {
            conn?.disconnect()
        }
    }

    /** 占用字节数，给设置页显示。没下过就是 0。 */
    fun usage(): Long = if (file.exists()) file.length() else 0L

    fun clear() {
        file.parentFile?.listFiles()?.forEach { it.delete() }
    }
}
