package com.bd2toolsbox.data.remote

import android.util.Log
import com.bd2toolsbox.core.fileaccess.AtomicFileWriter
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GameKee 的 HTTP 层（T30）。
 *
 * 从 1415 行的 `GamekeeRepository` 里拆出来：那里同时装着 HTTP、磁盘缓存、JSON 解析、
 * HTML 渲染与编排五件事，改任何一件都要在一整屏无关代码里找位置。
 *
 * **为什么放在 `data/remote` 而不是计划写的 `feature/guides/data`**：
 * 调用方 `GamekeeRepository` 在 `data/repository`，而分层规则（R2）禁止
 * `data → feature`。把 HTTP 层放进 `feature` 会立刻制造反向依赖。
 * 计划的那两个路径写于重构前，与实际分层不一致，这里按规则落位。
 *
 * 行为与拆出前**逐行一致**（同样的超时、同样的请求头、同样的「响应必须以 `{` 或 `[`
 * 开头才算有效」判据）；只是把「怎么发请求」与「怎么用响应」分开。
 */
@Singleton
class GamekeeApi @Inject constructor() {

    /**
     * GET 一个 JSON 端点。
     *
     * @param referer true 时带 `Referer`（图片/正文 CDN 会校验），
     *   否则带 `X-Requested-With` + `game-id` + `game-alias`（接口要的）。
     */
    fun getJson(url: String, referer: Boolean = false): String? =
        request(url, method = "GET", jsonBody = null, referer = referer)

    /** POST + JSON body，给兑换码这类只收 POST 的接口用。 */
    fun postJson(url: String, jsonBody: String): String? =
        request(url, method = "POST", jsonBody = jsonBody, referer = false)

    private fun request(
        url: String,
        method: String,
        jsonBody: String?,
        referer: Boolean
    ): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                if (jsonBody != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                if (referer) {
                    setRequestProperty("Referer", "https://www.gamekee.com/")
                } else {
                    setRequestProperty("X-Requested-With", "XMLHttpRequest")
                    setRequestProperty("game-id", GAME_ID)
                    setRequestProperty("game-alias", GAME_ALIAS)
                }
            }
            if (jsonBody != null) {
                conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "HTTP ${conn.responseCode}: $url")
                return null
            }
            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            if (looksLikeJson(body)) body else null
        } catch (e: Exception) {
            Log.w(TAG, "请求失败: $url (${e.message})")
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 下载一个文件到 [dest]。
     *
     * @param referer 图片 CDN（cdnimg-v2）与正文 CDN 一样校验 Referer，
     *   不带会回一个 567 状态页。
     */
    fun download(url: String, dest: File, referer: Boolean = false): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                if (referer) setRequestProperty("Referer", "https://www.gamekee.com/")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return false
            // **必须先写临时件再原子替换**，不能直接往 `dest` 写。
            //
            // 以前是 `dest.outputStream().use { input.copyTo(it) }`：连接中途断掉时
            // `copyTo` 抛异常、被下面 catch 吞成 `false`，但**半个文件已经落在 `dest` 上**。
            // 而缓存读取只判 `exists() && length() > 0`（`loadThumb` 等），
            // 于是那个截断的文件会被当成有效缓存，**而且永远不会重试**。
            // 顺带：直接写还会把上一次的有效缓存毁掉。
            // `AtomicFileWriter` 同时解决两件事：失败时删掉临时件、旧文件原样保留。
            AtomicFileWriter().write(target = dest) { out ->
                conn.inputStream.use { input -> input.copyTo(out) }
            }
            dest.length() > 0
        } catch (e: Exception) {
            Log.w(TAG, "下载失败: $url (${e.message})")
            false
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        private const val TAG = "GamekeeApi"

        /** gamekee 给棕色尘埃2分区的固定 id / 别名（接口头与 CDN 路径都要用）。 */
        const val GAME_ID = "50118"
        const val GAME_ALIAS = "zsca2"

        const val BASE_URL = "https://www.gamekee.com"
        const val CDN_CONTENT = "$BASE_URL/wiki2.0/pro/$GAME_ID/content"

        private const val UA = "Mozilla/5.0 (Linux; Android) BD2ToolsBox"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 20_000

        /**
         * CDN 偶尔会吐一个验证 HTML 页；按约定「content 必以 `{` 或 `[` 开头」判真假。
         *
         * 单独抽出来是为了能在 JVM 上直接断言这条判据 —— 它是「拿到的是数据还是
         * 拦截页」的唯一分界线，藏在真实 IO 里就没法单独验证。
         */
        fun looksLikeJson(body: String): Boolean {
            val trimmed = body.trimStart()
            return trimmed.startsWith("{") || trimmed.startsWith("[")
        }
    }
}
