package com.bd2toolsbox.data.repository

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * 角色头像 / 皮肤缩略图的下载与缓存。
 *
 * 图片托管在 myssal/Brown-Dust-2-Asset，文件名来自
 * [CharacterMetaRepository]（随包的 character_meta.json）—— 所以 URL 是直接拼出来的，
 * 不需要联网去查一张表再下图。
 *
 * 三层，从快到慢：
 *   1. 内存 [decoded]  —— 来回滚动不重复解码
 *   2. 磁盘 avatars/   —— 放 externalCacheDir：卸载随之清掉，也不进系统备份
 *   3. 网络
 *
 * 失败一律静默：拿不到图就返回 null，界面继续显示占位图标。头像是装饰，
 * 不该因为没网就让整屏角色列表看着像坏了。失败过的记进 [failed] 不再重试 ——
 * 否则用户上下滚动会对着一个 404 反复发请求。
 *
 * 不引第三方图片库（Coil / Glide）：需求就是这一处、百来张小图（方形头像约 10 KB），
 * 而它们会往 build 里拽一串新依赖。这点代码够用，并发数与降采样也都能自己定。
 */
class AvatarRepository private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "AvatarRepository"

        /**
         * 解码目标宽度（像素）。头像位 40 dp，在 xxhdpi（3x）下约 120 px；
         * 卡片里的缩略图 56 dp 约 168 px。原图头像本就只有 100 px 上下，
         * 所以这个值实际只对大的立绘图起作用。
         */
        private const val TARGET_WIDTH = 200

        /** 并发下载上限。95 张一起发既容易被限流，也会把带宽吃满。 */
        private const val MAX_PARALLEL_DOWNLOADS = 4

        @Volatile
        private var instance: AvatarRepository? = null

        fun get(context: Context): AvatarRepository =
            instance ?: synchronized(this) {
                instance ?: AvatarRepository(context.applicationContext).also { instance = it }
            }
    }

    private val meta by lazy { CharacterMetaRepository.get(appContext) }
    private val downloadGate = Semaphore(MAX_PARALLEL_DOWNLOADS)

    /** 缓存键 -> 已解码的图。键就是图片文件名（含扩展名），天然唯一。 */
    private val decoded = ConcurrentHashMap<String, ImageBitmap>()
    private val failed = ConcurrentHashMap<String, Boolean>()

    private val cacheDir: File
        get() = File(appContext.externalCacheDir ?: appContext.cacheDir, "avatars")

    // ---------------------------------------------------------------- 解析出 URL

    /** 角色的方形头像。表还没建好时返回 null 且不触发建表 —— 见 [peekHead]。 */
    fun headFor(character: String): Pair<String, String>? {
        val m = meta.forCharacter(character) ?: return null
        val c = m.representative ?: return null
        val url = c.headUrl ?: return null
        return c.headImage to url
    }

    /**
     * 同 [headFor]，但**表未就绪时直接返回 null、不触发加载**。
     *
     * 建表要读并解析 52 KB 的 assets json，放在 composition 里会卡首帧。
     * 列表第一行走协程那条路把表建起来，之后每一行都能同步命中，
     * 图也就能首帧直接画出来 —— 否则来回滚动时每张图都要先闪一下占位图。
     */
    fun peekHead(character: String): Pair<String, String>? {
        val m = meta.peekCharacter(character) ?: return null
        val c = m.representative ?: return null
        val url = c.headUrl ?: return null
        return c.headImage to url
    }

    /** 某套皮肤的立绘缩略图，按 mod 的 file_id 反查。 */
    fun illustForFileId(fileId: String?): Pair<String, String>? {
        val c = meta.forFileId(fileId) ?: meta.forDatingId(fileId) ?: return null
        val url = c.illustUrl ?: return null
        return c.illustImage to url
    }

    // ---------------------------------------------------------------- 取图

    /** 内存里已有就立刻返回，供 Composable 首帧直接画、不闪占位图。 */
    fun peek(key: String?): ImageBitmap? = key?.let { decoded[it] }

    /**
     * 取图。内存 → 磁盘 → 网络，逐层回退；任何一步失败都返回 null。
     *
     * [key] 是图片文件名，[url] 是完整地址（两者都由上面那几个解析方法给出）。
     */
    suspend fun load(key: String, url: String): ImageBitmap? {
        decoded[key]?.let { return it }
        if (failed.containsKey(key)) return null

        return withContext(Dispatchers.IO) {
            val file = File(cacheDir, key)

            if (file.exists() && file.length() > 0) {
                decodeFile(file)?.let {
                    decoded[key] = it
                    return@withContext it
                }
                // 解不出来说明那份是坏的（半个文件之类），删掉重下
                file.delete()
            }

            val ok = downloadGate.withPermit { download(url, file) }
            if (!ok) {
                failed[key] = true
                return@withContext null
            }
            val bmp = decodeFile(file)
            if (bmp == null) {
                file.delete()
                failed[key] = true
                return@withContext null
            }
            decoded[key] = bmp
            bmp
        }
    }

    private fun decodeFile(file: File): ImageBitmap? = try {
        // 先只读尺寸，据此定降采样倍数，避免把大图整张读进内存再缩
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET_WIDTH) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeFile(file.absolutePath, opts)?.asImageBitmap()
    } catch (e: Exception) {
        Log.w(TAG, "解码失败: ${file.name}", e)
        null
    } catch (e: OutOfMemoryError) {
        // 图片解码是 OOM 高发处，而这只是个装饰性头像 —— 宁可不显示也不能崩
        Log.w(TAG, "解码内存不足: ${file.name}")
        null
    }

    private fun download(url: String, dest: File): Boolean {
        var conn: HttpURLConnection? = null
        // 先写 .part 再改名：中途断网留下的半个文件若直接叫最终名，
        // 下次会被当成有效缓存，然后永远解码失败。
        val part = File(dest.parentFile, dest.name + ".part")
        return try {
            dest.parentFile?.mkdirs()
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 20000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "BD2ToolsBox")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "HTTP ${conn.responseCode}: $url")
                return false
            }
            conn.inputStream.use { input ->
                part.outputStream().use { out -> input.copyTo(out) }
            }
            if (part.length() <= 0) {
                part.delete()
                return false
            }
            dest.delete()
            part.renameTo(dest)
        } catch (e: Exception) {
            Log.w(TAG, "下载失败: $url (${e.message})")
            part.delete()
            false
        } finally {
            conn?.disconnect()
        }
    }

    // ---------------------------------------------------------------- 缓存管理

    /** 头像缓存占用（字节）与文件数，给设置页显示。 */
    fun usage(): Pair<Long, Int> {
        val files = cacheDir.listFiles()?.filterNot { it.name.endsWith(".part") }
            ?: return 0L to 0
        return files.sumOf { it.length() } to files.size
    }

    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
        decoded.clear()
        failed.clear()
    }

    /** 让失败过的重新有机会（比如用户刚连上网），已下好的图不动。 */
    fun retryFailed() {
        failed.clear()
    }
}
