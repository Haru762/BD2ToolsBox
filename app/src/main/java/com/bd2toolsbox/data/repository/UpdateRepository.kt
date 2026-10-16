package com.bd2toolsbox.data.repository

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

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
@Singleton
class UpdateRepository @Inject constructor(@ApplicationContext private val appContext: Context) {

    companion object {
        private const val TAG = "UpdateRepository"

        /** GitHub 的「最新正式版 release」端点（仅 GA）。 */
        private const val ENDPOINT_LATEST =
            "https://api.github.com/repos/Haru762/BD2ToolsBox/releases/latest"

        /** GitHub 的 release 列表端点（含 Pre-release 测试版与正式版）。 */
        private const val ENDPOINT_LIST =
            "https://api.github.com/repos/Haru762/BD2ToolsBox/releases?per_page=10"

        /** 拿不到 html_url 时的兜底 —— 地址总得有个能打开的。 */
        private const val RELEASES_PAGE = "https://github.com/Haru762/BD2ToolsBox/releases"

        /** GitHub 要求带 UA，缺了会被拒；内容无所谓，报上自己是谁即可。 */
        private const val UA = "Mozilla/5.0 (Linux; Android) BD2ToolsBox"

        /** 下载 id、期望文件元信息及更新通道的 prefs 键。 */
        private const val KEY_DL_ID = "download_id"
        private const val KEY_DL_EXPECTED_BYTES = "download_expected_bytes"
        private const val KEY_DL_FILE_NAME = "download_file_name"
        private const val KEY_BETA_CHANNEL = "beta_channel_enabled"

        /**
         * 版本号比较，a 比 b 新返回正数。
         *
         * 1. 先按主版本分段数值比较（"0.3.10" > "0.3.9"）；
         * 2. 主版本相同时：
         *    - 正式版（无 `-` 预发布后缀）高于预发布版（"0.3.5" > "0.3.5-beta.2"）；
         *    - 两个预发布版之间按后缀中的数字序列依次比较（"0.3.5-beta.2" > "0.3.5-beta.1"）。
         */
        fun compare(a: String, b: String): Int {
            val cleanA = a.trim().removePrefix("v").removePrefix("V").trim()
            val cleanB = b.trim().removePrefix("v").removePrefix("V").trim()
            val mainA = cleanA.substringBefore('-')
            val mainB = cleanB.substringBefore('-')
            val pa = mainA.split('.')
            val pb = mainB.split('.')
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val x = pa.getOrNull(i).orEmpty().takeWhile { it.isDigit() }.toIntOrNull() ?: 0
                val y = pb.getOrNull(i).orEmpty().takeWhile { it.isDigit() }.toIntOrNull() ?: 0
                if (x != y) return x.compareTo(y)
            }
            val preA = cleanA.substringAfter('-', "")
            val preB = cleanB.substringAfter('-', "")
            if (preA.isEmpty() && preB.isEmpty()) return 0
            if (preA.isEmpty()) return 1 // 正式版 > 同主版本预发布版
            if (preB.isEmpty()) return -1
            val numsA = Regex("\\d+").findAll(preA).mapNotNull { it.value.toIntOrNull() }.toList()
            val numsB = Regex("\\d+").findAll(preB).mapNotNull { it.value.toIntOrNull() }.toList()
            for (i in 0 until maxOf(numsA.size, numsB.size)) {
                val x = numsA.getOrNull(i) ?: 0
                val y = numsB.getOrNull(i) ?: 0
                if (x != y) return x.compareTo(y)
            }
            return preA.compareTo(preB, ignoreCase = true)
        }
    }

    /** 一个 release 里 App 用得到的那点东西。 */
    data class Release(
        /** 去掉 v 前缀的版本号，如 "0.3.5" 或 "0.3.5-beta.1"。 */
        val version: String,
        /** release 正文，markdown 原文（展示前的轻量去语法在 UI 层做）。 */
        val notes: String,
        /** release 页地址，应用内下载失败时仍可人工兜底。 */
        val htmlUrl: String,
        /** 这个 release 附带的全部 APK 资产。 */
        val apkAssets: List<ApkAsset> = emptyList(),
        /** 是否为 Beta 预发布版本。 */
        val isPrerelease: Boolean = false
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

    /** 是否启用了 Beta 测试版更新通道（默认关闭，仅接收 GA 稳定版）。 */
    fun isBetaChannelEnabled(): Boolean =
        dlPrefs().getBoolean(KEY_BETA_CHANNEL, installedVersion().contains("-beta", ignoreCase = true) || installedVersion().contains("-preview", ignoreCase = true))

    fun setBetaChannelEnabled(enabled: Boolean) {
        dlPrefs().edit().putBoolean(KEY_BETA_CHANNEL, enabled).apply()
    }

    /**
     * 拉最新 release。
     * - GA 通道（默认）：请求 `/releases/latest`；
     * - Beta 通道：请求 `/releases?per_page=10`，包含 `prerelease` 测试版与正式版，取版本号最高的一版。
     */
    suspend fun fetchLatest(includeBeta: Boolean = isBetaChannelEnabled()): Release? = withContext(Dispatchers.IO) {
        if (includeBeta) {
            val listBody = httpGet(ENDPOINT_LIST)
            val newest = listBody?.let { parseReleaseList(it) }
            if (newest != null) return@withContext newest
        }
        val body = httpGet(ENDPOINT_LATEST) ?: return@withContext null
        parseRelease(body)
    }

    internal fun parseReleaseList(body: String): Release? = try {
        val arr = Json.parseToJsonElement(body) as? JsonArray
        arr?.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val isDraft = (obj["draft"] as? JsonPrimitive)?.content == "true"
            if (isDraft) null else parseReleaseObject(obj)
        }?.maxWithOrNull { a, b -> compare(a.version, b.version) }
    } catch (e: Exception) {
        Log.w(TAG, "releases 列表解析失败", e)
        null
    }

    private fun parseRelease(body: String): Release? = try {
        val root = Json.parseToJsonElement(body) as? JsonObject
        if (root == null) null else parseReleaseObject(root)
    } catch (e: Exception) {
        Log.w(TAG, "release 响应解析失败", e)
        null
    }

    private fun parseReleaseObject(root: JsonObject): Release? {
        val version = (root["tag_name"] as? JsonPrimitive)?.content
            .orEmpty().trim().removePrefix("v").removePrefix("V").trim()
        if (version.isEmpty()) {
            Log.w(TAG, "release 响应里没有可用的 tag_name")
            return null
        }
        val isPre = (root["prerelease"] as? JsonPrimitive)?.content == "true" ||
            version.contains("-beta", ignoreCase = true) ||
            version.contains("-rc", ignoreCase = true)
        return Release(
            version = version,
            notes = (root["body"] as? JsonPrimitive)?.content.orEmpty(),
            htmlUrl = (root["html_url"] as? JsonPrimitive)?.content
                ?.takeIf { it.isNotBlank() } ?: RELEASES_PAGE,
            apkAssets = parseApkAssets(root),
            isPrerelease = isPre
        )
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
        dlPrefs().edit()
            .putLong(KEY_DL_ID, dm.enqueue(req))
            .putLong(KEY_DL_EXPECTED_BYTES, asset.bytes)
            .putString(KEY_DL_FILE_NAME, asset.name)
            .apply()
        return asset
    }

    /**
     * release 该下的那个包是否已经完整下好。
     *
     * 严禁只判 `f.length() > 0`：DownloadManager 直接在目标路径边下边写，
     * 断网留下的半截文件或正在下载中的文件都有 `length() > 0`。
     * 必须同时确认：无进行中下载、字节数与 release 元数据一致（若已知）、
     * 且若有 DownloadManager 记录则状态为 STATUS_SUCCESSFUL。
     */
    fun isApkDownloaded(release: Release): Boolean {
        if (hasActiveDownload()) return false
        val asset = pickApkAsset(release) ?: return false
        val f = File(updateDir(), asset.name)
        if (!f.isFile || f.length() <= 0) return false
        if (asset.bytes > 0 && f.length() != asset.bytes) return false
        val id = currentDownloadId()
        if (id != -1L && !isDownloadSuccessful(id)) return false
        return true
    }

    /** 下载完成后的完整 APK 文件；下载未完成、空文件或字节数不符均返回 null。 */
    fun downloadedApkFile(): File? {
        if (hasActiveDownload()) return null
        val prefs = dlPrefs()
        val expectedBytes = prefs.getLong(KEY_DL_EXPECTED_BYTES, -1L)
        val expectedName = prefs.getString(KEY_DL_FILE_NAME, null)
        val candidate = if (!expectedName.isNullOrBlank()) {
            File(updateDir(), expectedName).takeIf { it.isFile }
        } else {
            updateDir().listFiles()?.firstOrNull { it.isFile && it.length() > 0 }
        } ?: return null
        if (candidate.length() <= 0) return null
        if (expectedBytes > 0 && candidate.length() != expectedBytes) return null
        return candidate
    }

    internal fun updateDir(): File =
        File(appContext.getExternalFilesDir(null), "updates").apply { mkdirs() }

    internal var supportedAbisProvider: () -> List<String> = {
        Build.SUPPORTED_ABIS?.toList().orEmpty()
    }

    /**
     * 这个 release 里该给本机下哪个包：与本机已装包同架构优先（按
     * SUPPORTED_ABIS 首位匹配文件名），没有对应的退到通用包，再没有退第一个。
     * 不做选择界面——用户装的是什么包是明确的，替他选对就行。
     */
    private fun pickApkAsset(release: Release): ApkAsset? {
        // T32：按设备 ABI 选包；没有匹配资产就返回 null（明确失败），
        // 不回退 universal、不退回错误架构。选择规则见 ApkSelector。
        return com.bd2toolsbox.feature.update.ApkSelector.select(
            release.apkAssets,
            supportedAbisProvider()
        )
    }

    private fun parseApkAssets(root: JsonObject): List<ApkAsset> = try {
        (root["assets"] as? JsonArray)
            ?.mapNotNull { el ->
                val a = el as? JsonObject ?: return@mapNotNull null
                val name = (a["name"] as? JsonPrimitive)?.content
                    ?.takeIf { it.endsWith(".apk") } ?: return@mapNotNull null
                val url = (a["browser_download_url"] as? JsonPrimitive)?.content
                    ?: return@mapNotNull null
                ApkAsset(name, url, (a["size"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L)
            }.orEmpty()
    } catch (e: Exception) {
        Log.w(TAG, "assets 解析失败", e)
        emptyList()
    }
}
