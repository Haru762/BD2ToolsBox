package com.bd2toolsbox.data.repository

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Releases 的更新检查。
 *
 * 只有「关于」里手动点一次「检查更新」才会走这里 —— 不做启动自动检查。发现新
 * 版本后支持应用内下载安装：按本机已装包的架构自动选 release 里对应的 APK，
 * 走系统 DownloadManager 下载（通知栏有进度），完成后弹「安装」由用户确认拉起
 * 系统安装器 —— 不弹架构选择，也不再跳浏览器。
 *
 * 接口是 GitHub 的公开 API（2026-09 实测）：
 *
 *   GET https://api.github.com/repos/Haru762/BD2ToolsBox/releases/latest
 *   → { tag_name: "v0.2.4", body: "<markdown 更新说明>", html_url: ".../releases/tag/v0.2.4" }
 *
 * - 未登录调用有速率上限（每 IP 每小时 60 次），超了返回 403；仓库还没发过 release
 *   时是 404。两种都算「没拿到」，提示文案与断网一致
 * - tag_name 带 v 前缀（本项目发版都带），比较前统一去掉
 *
 * 沿用全项目惯例：HttpURLConnection + Gson，不引网络库。这里只读 release 的公开
 * 信息，不发通知、不写任何本地状态。
 */
class UpdateRepository private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "UpdateRepository"

        /** GitHub 的「最新 release」端点。 */
        private const val ENDPOINT =
            "https://api.github.com/repos/Haru762/BD2ToolsBox/releases/latest"

        /** 拿不到 html_url 时的兜底 —— 地址总得有个能打开的。 */
        private const val RELEASES_PAGE = "https://github.com/Haru762/BD2ToolsBox/releases"

        /** GitHub 要求带 UA，缺了会被拒；内容无所谓，报上自己是谁即可。 */
        private const val UA = "Mozilla/5.0 (Linux; Android) BD2ToolsBox"

        /** 下载 id 的 prefs 键。 */
        private const val KEY_DL_ID = "download_id"

        @Volatile
        private var instance: UpdateRepository? = null

        fun get(context: Context): UpdateRepository =
            instance ?: synchronized(this) {
                instance ?: UpdateRepository(context.applicationContext).also { instance = it }
            }

        /**
         * 版本号比较，a 比 b 新返回正数。
         *
         * 分段按数值比，不按字符串 —— "0.2.10" 要比 "0.2.9" 新，字符串比刚好反过来
         * （'1' < '9'）。每段只取开头的数字（"4-rc1" 当 4），缺失的段当 0
         * （"0.2" 与 "0.2.0" 同版）。预发布后缀不参与比较：0.2.4-rc1 与 0.2.4 视为
         * 同一版，免得正式版发出来了还被 rc 挡在门外。
         */
        fun compare(a: String, b: String): Int {
            val pa = a.trim().removePrefix("v").split('.')
            val pb = b.trim().removePrefix("v").split('.')
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val x = pa.getOrNull(i).orEmpty().takeWhile { it.isDigit() }.toIntOrNull() ?: 0
                val y = pb.getOrNull(i).orEmpty().takeWhile { it.isDigit() }.toIntOrNull() ?: 0
                if (x != y) return x.compareTo(y)
            }
            return 0
        }
    }

    /** 一个 release 里 App 用得到的那点东西。 */
    data class Release(
        /** 去掉 v 前缀的版本号，如 "0.2.4"。 */
        val version: String,
        /** release 正文，markdown 原文（展示前的轻量去语法在 UI 层做）。 */
        val notes: String,
        /** release 页地址，应用内下载失败时仍可人工兜底。 */
        val htmlUrl: String,
        /** 这个 release 附带的全部 APK 资产。 */
        val apkAssets: List<ApkAsset> = emptyList()
    )

    /** release 里的一个 APK 资产。 */
    data class ApkAsset(
        val name: String,
        val url: String,
        val bytes: Long
    )

    /**
     * 手机上这版的 versionName（发版时由 APP_VERSION_NAME 注入，debug 包是 build.gradle
     * 里的默认值）。读不到时返回 "?"，界面上照常显示，不至于整行消失。
     */
    fun installedVersion(): String = try {
        appContext.packageManager
            .getPackageInfo(appContext.packageName, 0).versionName?.trim().orEmpty()
            .ifEmpty { "?" }
    } catch (e: Exception) {
        Log.w(TAG, "读不到自身版本号", e)
        "?"
    }

    /** [version] 是否比手机上装的这版更新。 */
    fun isNewerThanInstalled(version: String): Boolean = compare(version, installedVersion()) > 0

    /**
     * 拉最新 release。网络、HTTP、格式任何一步出问题都返回 null —— 调用方只需要
     * 知道「没拿到」（提示文案是统一的「连不上 GitHub」）。
     */
    suspend fun fetchLatest(): Release? = withContext(Dispatchers.IO) {
        val body = httpGet(ENDPOINT) ?: return@withContext null
        parseRelease(body)
    }

    private fun parseRelease(body: String): Release? = try {
        val root = JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject
        val version = root?.get("tag_name")?.takeIf { it.isJsonPrimitive }?.asString
            .orEmpty().trim().removePrefix("v").removePrefix("V").trim()
        when {
            root == null || version.isEmpty() -> {
                Log.w(TAG, "release 响应里没有可用的 tag_name")
                null
            }
            else -> Release(
                version = version,
                // body 可能是 JsonNull（release 没写说明），那也算空串
                notes = root.get("body")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                htmlUrl = root.get("html_url")?.takeIf { it.isJsonPrimitive }?.asString
                    ?.takeIf { it.isNotBlank() } ?: RELEASES_PAGE,
                apkAssets = parseApkAssets(root)
            )
        }
    } catch (e: Exception) {
        Log.w(TAG, "release 响应解析失败", e)
        null
    }

    /** 一次 GET。非 200 与网络异常都返回 null。 */
    private fun httpGet(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 15000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            val status = conn.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "HTTP $status: $url")
                return null
            }
            conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            Log.w(TAG, "请求失败: $url (${e.message})")
            null
        } finally {
            conn?.disconnect()
        }
    }

    // ---------------------------------------------------------------- 应用内下载

    private fun dlPrefs() =
        appContext.getSharedPreferences("update_download", Context.MODE_PRIVATE)

    /** 上一次排队的下载 id；-1 表示没有。进程死过也还在（DownloadManager 是系统的）。 */
    fun currentDownloadId(): Long = dlPrefs().getLong(KEY_DL_ID, -1L)

    /** id 对应的下载是否已成功完成。 */
    fun isDownloadSuccessful(id: Long): Boolean {
        val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return false
        return try {
            dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                val col = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                c.moveToFirst() && c.getInt(col) == DownloadManager.STATUS_SUCCESSFUL
            }
        } catch (e: Exception) {
            Log.w(TAG, "查询下载状态失败", e)
            false
        }
    }

    /** 本 app 发起的下载是否仍在进行（含排队/暂停）。 */
    fun hasActiveDownload(): Boolean {
        val id = currentDownloadId()
        if (id == -1L) return false
        val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return false
        return try {
            dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                val col = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                var active = false
                while (c.moveToNext()) {
                    when (c.getInt(col)) {
                        DownloadManager.STATUS_PENDING,
                        DownloadManager.STATUS_RUNNING,
                        DownloadManager.STATUS_PAUSED -> active = true
                    }
                }
                active
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 排队下载与本机架构对应的 APK。返回资产（名与字节数给完成后的提示用）；
     * release 没带 APK 或已有下载在进行中返回 null，由调用方提示。
     */
    fun startApkDownload(release: Release): ApkAsset? {
        val asset = pickApkAsset(release) ?: return null
        if (hasActiveDownload()) return null
        val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return null
        // 旧包清掉，updates/ 只留当前这次——目录不至于越积越大
        updateDir().listFiles()?.forEach { it.delete() }
        val req = DownloadManager.Request(Uri.parse(asset.url))
            .setTitle(asset.name)
            .setDescription("BD2 ToolsBox ${release.version}")
            .setMimeType("application/vnd.android.package-archive")
            .setDestinationInExternalFilesDir(appContext, null, "updates/" + asset.name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        dlPrefs().edit().putLong(KEY_DL_ID, dm.enqueue(req)).apply()
        return asset
    }

    /** release 该下的那个包是否已经下好（文件在且非空）。 */
    fun isApkDownloaded(release: Release): Boolean {
        val asset = pickApkAsset(release) ?: return false
        val f = File(updateDir(), asset.name)
        return f.isFile && f.length() > 0
    }

    /** 下载完成后的 APK 文件；没有或空文件返回 null。 */
    fun downloadedApkFile(): File? =
        updateDir().listFiles()?.firstOrNull { it.isFile && it.length() > 0 }

    internal fun updateDir(): File =
        File(appContext.getExternalFilesDir(null), "updates").apply { mkdirs() }

    /**
     * 这个 release 里该给本机下哪个包：与本机已装包同架构优先（按
     * SUPPORTED_ABIS 首位匹配文件名），没有对应的退到通用包，再没有退第一个。
     * 不做选择界面——用户装的是什么包是明确的，替他选对就行。
     */
    private fun pickApkAsset(release: Release): ApkAsset? {
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        return release.apkAssets.firstOrNull { abi.isNotEmpty() && it.name.contains(abi) }
            ?: release.apkAssets.firstOrNull { it.name.contains("universal") }
            ?: release.apkAssets.firstOrNull()
    }

    private fun parseApkAssets(root: com.google.gson.JsonObject): List<ApkAsset> = try {
        root.get("assets")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { el ->
                val a = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val name = a.get("name")?.takeIf { it.isJsonPrimitive }?.asString
                    ?.takeIf { it.endsWith(".apk") } ?: return@mapNotNull null
                val url = a.get("browser_download_url")?.takeIf { it.isJsonPrimitive }?.asString
                    ?: return@mapNotNull null
                ApkAsset(name, url, a.get("size")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L)
            }.orEmpty()
    } catch (e: Exception) {
        Log.w(TAG, "assets 解析失败", e)
        emptyList()
    }
}
