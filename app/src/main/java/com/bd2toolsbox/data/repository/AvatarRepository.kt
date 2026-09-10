package com.bd2toolsbox.data.repository

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 角色头像 / 皮肤缩略图的下载与缓存。
 *
 * 图片托管在 myssal/Brown-Dust-2-Asset，文件名来自
 * [CharacterMetaRepository]（随包的 character_meta.json）—— 所以 URL 是直接拼出来的，
 * 不需要联网去查一张表再下图。
 *
 * 头像用的是 `ui/illust/illust_inven_char/` 那套**立绘半身像**（256 px），不是原先
 * `ui/icon/icon_char/` 的 Q 版小人图标（128 px）：角色列表里一眼要看的是「这个人是谁」，
 * 立绘比小人脸辨识度高得多，而且那套图覆盖了随包数据里的全部 190 套皮肤（小人图标
 * 只覆盖有独立图的那部分）。皮肤缩略图本来就走同一套立绘，两处的缓存目录与文件也
 * 就共用了。
 *
 * 三层，从快到慢：
 *   1. 内存 [decoded]  —— 来回滚动不重复解码
 *   2. 磁盘 avatars_v2/ —— 放 externalCacheDir：卸载随之清掉，也不进系统备份
 *   3. 网络
 *
 * 失败一律静默：拿不到图就返回 null，界面继续显示占位图标。头像是装饰，
 * 不该因为没网就让整屏角色列表看着像坏了 —— 但整趟预取都失败时界面会提示
 * 「请检查网络」（见 [SyncState] / [syncAll]），那是用户唯一能知道「为什么全是
 * 占位图标」的线索。失败过的记进 [failed] 不再重试 —— 否则用户上下滚动会对着
 * 一个 404 反复发请求；[syncAll] 每趟开头会清掉这些标记，所以重试是有路的。
 *
 * 不引第三方图片库（Coil / Glide）：需求就是这一处、百来张小图（立绘半身像约 75 KB），
 * 而它们会往 build 里拽一串新依赖。这点代码够用，并发数与降采样也都能自己定。
 */
class AvatarRepository private constructor(private val appContext: Context) {

    /** 启动预取的状态，给界面判断「是不是网不通」。 */
    enum class SyncState {
        /** 还没跑过。 */
        IDLE,

        /** 正在补图。 */
        RUNNING,

        /** 补齐了（或图源本来就没有的那几张不算数）。 */
        DONE,

        /** 出现网络类异常且还有没下完的 —— 界面提示「请检查网络」。 */
        FAILED
    }

    companion object {
        private const val TAG = "AvatarRepository"

        /**
         * 解码目标宽度（像素）。头像位 40 dp，在 xxhdpi（3x）下约 120 px；
         * 卡片里的缩略图 56 dp 约 168 px。
         *
         * 立绘半身像是 256 px 见方，取 128 正好是对半降采样 —— 再往上多出来的像素
         * 一处也画不到，却要让内存里每张图多占 3 倍（256²×4=256 KB vs 128²×4=64 KB，
         * 整份角色表全滚一遍就是 20 MB 与 5 MB 的差别）。
         */
        private const val TARGET_WIDTH = 128

        /** 并发下载上限。95 张一起发既容易被限流，也会把带宽吃满。 */
        private const val MAX_PARALLEL_DOWNLOADS = 4

        /**
         * 预取时连续失败多少个就收手。
         *
         * 网不通时每个目标都要等满 15 秒超时，83 个目标排下去是二十分钟的徒劳 ——
         * 连着四个都碰壁（正好一整轮并发位）就认定网络不通，把剩下的留给下一次。
         */
        private const val BAIL_AFTER_FAILURES = 4

        /**
         * 缓存目录的源版本号。
         *
         * 头像从「小人图标」换成「立绘」时换的目录名：两者文件名不同、观感也不同，
         * 混在一个目录里既分不清也白占空间。老目录（[LEGACY_CACHE_DIR]）在预取时
         * 删掉，升级后绝不会画出旧图。
         */
        private const val CACHE_DIR = "avatars_v2"
        private const val LEGACY_CACHE_DIR = "avatars"

        @Volatile
        private var instance: AvatarRepository? = null

        fun get(context: Context): AvatarRepository =
            instance ?: synchronized(this) {
                instance ?: AvatarRepository(context.applicationContext).also { instance = it }
            }
    }

    private val meta by lazy { CharacterMetaRepository.get(appContext) }
    private val downloadGate = Semaphore(MAX_PARALLEL_DOWNLOADS)

    /** 预取同一时刻只跑一趟；重复调用只是再扫一遍文件在不在，不会重复下。 */
    private val syncMutex = Mutex()

    private val _syncState = MutableStateFlow(SyncState.IDLE)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    /** 缓存键 -> 已解码的图。键就是图片文件名（含扩展名），天然唯一。 */
    private val decoded = ConcurrentHashMap<String, ImageBitmap>()
    private val failed = ConcurrentHashMap<String, Boolean>()

    private val cacheDir: File
        get() = File(appContext.externalCacheDir ?: appContext.cacheDir, CACHE_DIR)

    // ---------------------------------------------------------------- 解析出 URL

    /** 角色的头像（立绘半身像）。表还没建好时返回 null 且不触发建表 —— 见 [peekAvatar]。 */
    fun avatarFor(character: String): Pair<String, String>? =
        targetOf(meta.forCharacter(character)?.representative)

    /**
     * 同 [avatarFor]，但**表未就绪时直接返回 null、不触发加载**。
     *
     * 建表要读并解析 52 KB 的 assets json，放在 composition 里会卡首帧。
     * 列表第一行走协程那条路把表建起来，之后每一行都能同步命中，
     * 图也就能首帧直接画出来 —— 否则来回滚动时每张图都要先闪一下占位图。
     */
    fun peekAvatar(character: String): Pair<String, String>? =
        targetOf(meta.peekCharacter(character)?.representative)

    /** 角色表里每个角色的头像目标（代表皮肤的那张立绘），供 [syncAll] 遍历。 */
    fun avatarTargets(): List<Pair<String, String>> =
        meta.allRepresentatives().mapNotNull { targetOf(it) }

    private fun targetOf(costume: CharacterMetaRepository.Costume?): Pair<String, String>? {
        val c = costume ?: return null
        val url = c.illustUrl ?: return null
        return c.illustImage to url
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

            val outcome = downloadGate.withPermit { download(url, file) }
            if (outcome != Outcome.OK) {
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

    // ---------------------------------------------------------------- 启动预取

    /**
     * 把角色表里全部角色的头像补齐到本地磁盘（启动后跑一趟）。
     *
     * **只下不解码**：83 张头像全解进内存要五兆上下，而用户一次只看得到屏幕上那十几行 ——
     * 解码留给 [load]，真正要画的时候再做。这样预取换来的「滚到哪都有图」不必拿内存换。
     *
     * 幂等可重入：[syncMutex] 保证同一时刻只跑一趟；已经下好的直接跳过，
     * 重复调用最多只是多扫一遍文件在不在（不发请求）。
     *
     * 失败的口径：**出现网络类异常、且仍有没下完的** —— 单纯的 404（图源里就没这张）
     * 不算：那是数据问题，对着它提示「请检查网络」只会把人引到错的方向。
     * 网络明显不通时连着 [BAIL_AFTER_FAILURES] 张就收手，不让用户白等二十分钟。
     */
    suspend fun syncAll() {
        syncMutex.withLock {
            purgeLegacyCache()
            val targets = withContext(Dispatchers.IO) { avatarTargets() }
            // 上一趟没网留下的「不再重试」不该拦住这一趟；404 那类也会被清掉，
            // 代价只是下次滚到它时多发一个请求，换来的是「重试」真的能重试。
            failed.clear()
            val missing = withContext(Dispatchers.IO) {
                targets.filterNot { isCached(it.first) }
            }
            if (missing.isEmpty()) {
                _syncState.value = SyncState.DONE
                return@withLock
            }

            _syncState.value = SyncState.RUNNING
            val networkFailures = AtomicInteger(0)
            val consecutive = AtomicInteger(0)

            coroutineScope {
                missing.map { (key, url) ->
                    async(Dispatchers.IO) {
                        if (consecutive.get() >= BAIL_AFTER_FAILURES) return@async
                        when (cacheOnly(key, url)) {
                            Outcome.OK -> consecutive.set(0)
                            // 服务器答了话（404/403）说明网是通的，不该记进「网络不通」
                            Outcome.HTTP_ERROR -> consecutive.set(0)
                            Outcome.NETWORK_ERROR -> {
                                consecutive.incrementAndGet()
                                networkFailures.incrementAndGet()
                            }
                        }
                    }
                }.awaitAll()
            }

            val stillMissing = withContext(Dispatchers.IO) {
                targets.count { !isCached(it.first) }
            }
            // 失败口径：网络类异常 **且仍有缺张** 才算失败。瞬时抖动（比如 83 张里
            // 1 张超时、滚动时又被 load() 补上了）不算 —— 只看 networkFailures 会把
            // 「其实已经齐了」挂成永久 FAILED，提示条压着列表还不会自动消失。单纯
            // 404 的缺口不算失败 —— 那是图源里没有，跟用户的网没关系。
            _syncState.value =
                if (networkFailures.get() > 0 && stillMissing > 0) SyncState.FAILED
                else SyncState.DONE
            Log.d(
                TAG,
                "头像预取：目标 ${targets.size}，本趟缺 ${missing.size}，" +
                        "网络异常 ${networkFailures.get()}，仍未补齐 $stillMissing"
            )
        }
    }

    /** 磁盘上已经有这张（且不是半个文件）就算拿到，不必再看内容。 */
    private fun isCached(key: String): Boolean {
        val f = File(cacheDir, key)
        return f.exists() && f.length() > 0
    }

    /** 预取专用：只把图落到磁盘，不进内存。 */
    private suspend fun cacheOnly(key: String, url: String): Outcome {
        if (decoded.containsKey(key)) return Outcome.OK
        if (isCached(key)) return Outcome.OK
        val file = File(cacheDir, key)
        return downloadGate.withPermit { download(url, file) }
    }

    /** 换源（小人图标 → 立绘）时留下的旧目录，删掉，别让它白占着磁盘。 */
    private fun purgeLegacyCache() {
        try {
            val legacy = File(appContext.externalCacheDir ?: appContext.cacheDir, LEGACY_CACHE_DIR)
            if (!legacy.exists()) return
            val n = legacy.listFiles()?.size ?: 0
            if (legacy.deleteRecursively()) {
                Log.d(TAG, "已清掉旧版（小人图标）头像缓存 $n 个文件")
            }
        } catch (e: Exception) {
            Log.w(TAG, "清理旧头像缓存失败", e)
        }
    }

    // ---------------------------------------------------------------- 解码与下载

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

    /**
     * 一次下载的结局。
     *
     * 把「网络不通」和「图源没有这张」分开，是为了让界面能只说该说的那句：
     * 404 满屏时提示「请检查网络」等于让用户去修一个没坏的东西。
     */
    private enum class Outcome { OK, HTTP_ERROR, NETWORK_ERROR }

    private fun download(url: String, dest: File): Outcome {
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
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "HTTP $code: $url")
                // 5xx 与 429/408 是「服务器现在给不了」，与连不上同类；其余
                // （404/403）是图源本来就没有这张，当数据问题，不报成网络失败。
                return if (code >= 500 || code == 408 || code == 429) Outcome.NETWORK_ERROR
                       else Outcome.HTTP_ERROR
            }
            conn.inputStream.use { input ->
                part.outputStream().use { out -> input.copyTo(out) }
            }
            if (part.length() <= 0) {
                part.delete()
                return Outcome.HTTP_ERROR
            }
            dest.delete()
            if (part.renameTo(dest)) Outcome.OK else {
                part.delete()
                Outcome.HTTP_ERROR
            }
        } catch (e: IOException) {
            // 连不上、超时、连接被重置 —— 国内直连 raw.githubusercontent 的常态都落在这里
            Log.w(TAG, "下载失败（网络）: $url (${e.message})")
            part.delete()
            Outcome.NETWORK_ERROR
        } catch (e: Exception) {
            Log.w(TAG, "下载失败: $url (${e.message})")
            part.delete()
            Outcome.HTTP_ERROR
        } finally {
            conn?.disconnect()
        }
    }
}
