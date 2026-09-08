package com.bd2toolsbox.data.repository

import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.bd2toolsbox.data.model.CdkItem
import com.bd2toolsbox.data.model.GuideArticle
import com.bd2toolsbox.data.model.GuideCategory
import com.bd2toolsbox.data.model.GuideComment
import com.bd2toolsbox.data.model.GuideDetail
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * GameKee「棕色尘埃2」分区（https://www.gamekee.com/zsca2）攻略数据的读取与缓存。
 *
 * gamekee 的页面是 SPA，正文全部走它的 JSON 接口，本仓库直连接口取数。所有请求
 * 都实测验证过（2026-09）：
 *
 *   分类树   GET /v1/wiki/entry?sub_id=0                  → {new_update_list, entry_list, admin_list}
 *   文章列表 GET /v1/content/pageList?entry_id=&page=&limit=
 *   文章详情 GET /v1/content/detail/{id}                  → title 等 + content_cdn（正文在 CDN 上）
 *   正文     GET api-cdn.gamekee.com/wiki2.0/pro/50118/content/{id}.json
 *   搜索     GET /v1/content/searchArticle?keyword=&page=&limit=
 *
 * 两个接口的鉴权只是「报上分区身份」：列表类接口要求 `game-id: 50118` 与
 * `game-alias: zsca2` 两个请求头，缺了会 404；正文 CDN 要求 `Referer: gamekee.com`，
 * 否则返回一个验证 HTML 页而不是 JSON。另外所有 wiki 接口都要带
 * `X-Requested-With: XMLHttpRequest`（网页端的 axios 默认头），没有也拿不到数据。
 *
 * 正文有两种格式，由 detail 的 editor_type 区分：
 *   1（新编辑器）：CDN JSON 的 content 是一段 Slate 富文本块 JSON 字符串，
 *     逐块翻译成 HTML（见 [renderSlateBlocks]）；
 *   其余（旧编辑器）：content 直接就是 HTML，原样使用。
 * 统一转成一份完整 HTML 文档交给 WebView 展示。
 *
 * 缓存：
 *   分类树 → guides/tree.json，6 小时内直接用（分类结构不常变，没必要每次进板块都拉）；
 *   文章正文 → guides/articles/{id}.html，永久有效（攻略更新不频繁，要新内容时
 *   详情页有刷新按钮）；列表与搜索不做缓存，请求都很小。
 * 全部落在外部缓存目录 guides/ 下，用户可在攻略页一键清空。
 *
 * 沿用全项目的选择：不引 OkHttp/Retrofit/Coil，[HttpURLConnection] + Gson 够用，
 * 缩略图下载照抄 [AvatarRepository] 的三层结构（内存 → 磁盘 → 网络）。
 */
class GamekeeRepository private constructor(private val appContext: android.content.Context) {

    companion object {
        private const val TAG = "GamekeeRepository"

        /** gamekee 给棕色尘埃2分区的固定 id / 别名（接口头与 CDN 路径都要用）。 */
        private const val GAME_ID = "50118"
        private const val GAME_ALIAS = "zsca2"

        private const val API = "https://www.gamekee.com"
        private const val CDN_CONTENT = "https://api-cdn.gamekee.com/wiki2.0/pro/$GAME_ID/content"

        private const val UA = "Mozilla/5.0 (Linux; Android) BD2ToolsBox"

        /** 正文缓存头部的渲染器版本前缀。改渲染逻辑时同步递增；缓存里缺失即视为
         *  旧渲染，自动失效重拉（见 loadArticleDetail）。 */
        private const val RENDERER_STAMP = "<!--renderer:v2"

        /** 渲染器戳后面跟着的内容版本号（来自 content_cdn 的 ?v=），两者都对上
         *  才复用缓存 —— 官方改了文章版本号就变，缓存自动过期。 */
        private const val CONTENT_STAMP = "|v="

        /** 列表分页大小。gamekee 页面端也是 10 条一页。 */
        const val PAGE_SIZE = 10

        @Volatile
        private var instance: GamekeeRepository? = null

        fun get(context: android.content.Context): GamekeeRepository =
            instance ?: synchronized(this) {
                instance ?: GamekeeRepository(context.applicationContext).also { instance = it }
            }

        /** 把图片地址补全成可加载的绝对地址。
         * `//cdn...` 补 https；已是 http:// 的升级成 https —— WebView 默认禁止
         * 明文 HTTP（如 QQ 头像 thirdqq.qlogo.cn），不升级就是一路裂图，
         * 而这些图源实际都支持 https（实测）。
         */
        private fun absolutize(url: String?): String? {
            val u = url?.trim()?.takeIf { it.isNotBlank() } ?: return null
            return when {
                u.startsWith("//") -> "https:$u"
                u.startsWith("http://") -> "https://${u.removePrefix("http://")}"
                u.startsWith("https") -> u
                else -> "https://www.gamekee.com$u"
            }
        }
    }

    private val cacheDir: File
        get() = File(appContext.externalCacheDir ?: appContext.cacheDir, "guides")

    private val treeFile: File get() = File(cacheDir, "tree.json")
    private val articleDir: File get() = File(cacheDir, "articles")
    private val thumbDir: File get() = File(cacheDir, "thumbs")

    // ---------------------------------------------------------------- 分类树

    /** 分类树 + 最近更新列表。最近更新在进入「攻略」板块时几乎必看，所以跟树放一次请求里。 */
    data class TreeData(val categories: List<GuideCategory>, val recent: List<GuideArticle>)

    private var treeCache: TreeData? = null

    /**
     * 取分类树。**每次都拉新**（用户要求实时）——分类树、最近更新、映射 dict
     * 三份一次取齐；网络失败退磁盘缓存（离线还能看），两样都失败才抛。
     *
     * 一次取三份：
     *   /v1/wiki/entry                    —— 树结构 + 最近更新（树是精简版，无图标）
     *   /v1/entry/query-entry-list-from-cdn —— entry→文章 映射 dict + 图标全量表的 CDN 地址
     *   cdn_path 指向的 list.json          —— 全量条目表，取每个条目的 icon 图标
     *
     * 映射 dict 是关键：gamekee 叶子条目绑定的是另一套编号的文章
     * （如条目 210758 绑文章 697421），没有它就无法从树点进正文；pageList 接口
     * 实测忽略 entry_id、永远返回同一份全局列表，做不了分类列表。
     * list.json 只当「id→图标」查找表用（树结构仍以 wiki/entry 为准，已验证）。
     * dict / 图标拉不到不算致命：没有它们叶子点不进文章、列表退占位图标，
     * 但树和最近更新还能看。
     */
    suspend fun loadTree(): TreeData = withContext(Dispatchers.IO) {
        val treeBody = httpGet("$API/v1/wiki/entry?sub_id=0")
        if (treeBody != null) {
            val dictBody = httpGet("$API/v1/entry/query-entry-list-from-cdn")
            val iconsBody = dictBody?.let { dict ->
                try {
                    val cdnPath = JsonParser.parseString(dict).asJsonObject
                        .get("data")?.takeIf { it.isJsonObject }?.asJsonObject
                        ?.get("cdn_path")?.takeIf { it.isJsonPrimitive }?.asString
                    absolutize(cdnPath)?.let { httpGet(it, referer = true) }
                } catch (_: Exception) {
                    null
                }
            }
            val data = parseTree(
                JsonParser.parseString(treeBody),
                dictBody?.let { JsonParser.parseString(it) },
                iconsBody?.let { JsonParser.parseString(it) }
            )
            treeCache = data
            try {
                cacheDir.mkdirs()
                val tmp = File(cacheDir, "tree.json.part")
                tmp.writeText(
                    """{"entry":$treeBody,"dict":${dictBody ?: "null"},"icons":${iconsBody ?: "null"}}"""
                )
                tmp.renameTo(treeFile)
            } catch (_: Exception) {
                // 缓存写不进去不影响使用
            }
            return@withContext data
        }

        // 在线拉不到（离线 / 接口抖动）：退磁盘缓存，保证还能看
        treeCache?.let { return@withContext it }
        try {
            if (treeFile.exists()) {
                val cachedPair = JsonParser.parseString(treeFile.readText()).asJsonObject
                return@withContext parseTree(
                    cachedPair.get("entry"), cachedPair.get("dict"), cachedPair.get("icons")
                ).also { treeCache = it }
            }
        } catch (e: Exception) {
            Log.w(TAG, "树缓存解析失败", e)
            treeFile.delete()
        }
        throw RuntimeException("无法连接 GameKee，请检查网络")
    }

    private fun parseTree(
        root: com.google.gson.JsonElement,
        dictRoot: com.google.gson.JsonElement?,
        iconsRoot: com.google.gson.JsonElement?
    ): TreeData {
        // 取数一律用 get + takeIf 而不是 getAsJsonArray/getAsJsonObject：
        // 后者遇到字段为 JsonNull（gamekee 的叶子节点就是 "child":null）时
        // 抛 ClassCastException，而不是返回 null —— 实测栽过这个坑
        val data = root.asJsonObject.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw RuntimeException("GameKee 返回了意外的数据格式")
        // dict: entryId → 文章 id。结构与示例：
        // {"dict":[{"id":210758,"c_id":697421,"e_tp":1}, ...]}
        val contentIds = HashMap<Long, Long>()
        dictRoot?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("dict")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.forEach { el ->
                val o = el.asJsonObject
                val id = o.get("id")?.takeIf { it.isJsonPrimitive }?.asLong ?: return@forEach
                val cid = o.get("c_id")?.takeIf { it.isJsonPrimitive }?.asLong ?: return@forEach
                if (id > 0 && cid > 0) contentIds[id] = cid
            }
        // icons: entryId → 图标（CDN 全量表，与 dict 同源的 cdn_path 文件，
        // 顶层是数组）。只抽映射，树结构仍以 wiki/entry 为准
        val icons = HashMap<Long, String>()
        iconsRoot?.takeIf { it.isJsonArray }?.asJsonArray
            ?.forEach { walkEntryTreeForIcons(it.asJsonObject, icons) }
        val categories = data.get("entry_list")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { top -> parseCategory(top.asJsonObject, contentIds, icons) }
            .orEmpty()
        val recent = data.get("new_update_list")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.map { parseArticle(it.asJsonObject) }
            .orEmpty()
        return TreeData(categories, recent)
    }

    /** 从 CDN 全量表里只抽 id→icon 映射，表本身的结构不作为树的来源。 */
    private fun walkEntryTreeForIcons(node: JsonObject, into: MutableMap<Long, String>) {
        val id = node.get("id")?.takeIf { it.isJsonPrimitive }?.asLong ?: return
        node.get("icon")?.takeIf { it.isJsonPrimitive }?.asString
            ?.takeIf { it.isNotBlank() }
            ?.let { absolutize(it)?.let { u -> into[id] = u } }
        node.get("child")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.forEach { walkEntryTreeForIcons(it.asJsonObject, into) }
    }

    /**
     * 不收录进 App 的栏目。按名字排除而不是按 id：wiki 重建栏目时 id 会变，
     * 名字才是稳定的标识。目前只排除「第三方合作」（代充/加速器/模拟器/
     * 云手机等推广内容）—— 工具类 App 里挂这些既没用也容易被认为是广告位。
     */
    private val EXCLUDED_CATEGORY_NAMES = setOf("第三方合作")

    /**
     * 从跳转链接里提取文章 id。另一类叶子不走 dict 映射，而是在树上挂着
     * jump_url 直接指向文章（如服装测评系列 → /zsca2/718485?tab=fzpc，
     * tj/600786.html 同理）。tab 参数忽略 —— 文内选项卡全部渲染，用户自己切。
     */
    private val JUMP_ARTICLE = Regex("""/(\d+)(?:\.html)?(?:\?|$)""")

    private fun parseCategory(
        node: JsonObject,
        contentIds: Map<Long, Long>,
        icons: Map<Long, String>
    ): GuideCategory? {
        if (node.get("status")?.takeIf { it.isJsonPrimitive }?.asInt == 0) return null
        val name = node.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        if (name in EXCLUDED_CATEGORY_NAMES) return null
        val id = node.get("id")?.takeIf { it.isJsonPrimitive }?.asLong ?: return null
        val children = node.get("child")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { c -> parseCategory(c.asJsonObject, contentIds, icons) }
            .orEmpty()
        // dict 优先；没有映射时看 jump_url（部分条目两边都没有，那就是
        // gamekee 侧没配好的死条目，保持 contentId=0）
        val jumpContentId = node.get("jump_url")?.takeIf { it.isJsonPrimitive }?.asString
            ?.let { JUMP_ARTICLE.find(it)?.groupValues?.get(1)?.toLongOrNull() } ?: 0L
        return GuideCategory(
            id = id,
            name = name,
            contentId = contentIds[id] ?: jumpContentId,
            icon = icons[id],
            children = children
        )
    }

    private fun parseArticle(o: JsonObject): GuideArticle = GuideArticle(
        id = o.get("id")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
        title = o.get("title")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
        summary = o.get("summary")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
        // thumb 是逗号分隔的图片列表（首图 / 二图 / 三图），列表里只需要第一张
        thumb = absolutize(
            o.get("thumb")?.takeIf { it.isJsonPrimitive }?.asString
                ?.split(',')?.firstOrNull { it.isNotBlank() }
        ),
        updatedAt = o.get("updated_at")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
        comments = o.get("comment_count")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
    )

    // ---------------------------------------------------------------- 兑换码

    /**
     * 当前可用的兑换码。接口是 POST + JSON body（GET 会 404），server_id 来自
     * 分区的「区服」数据（12 = 国际服，棕尘2 只分这一个区）。返回即全量列表
     * （实测 10 条左右），无翻页。
     */
    suspend fun loadCdk(): List<CdkItem> = withContext(Dispatchers.IO) {
        val body = httpPostJson("$API/v1/game/cdk2/queryByServerIdPageList", """{"server_id":12}""")
            ?: throw RuntimeException("无法连接 GameKee，请检查网络")
        val arr = JsonParser.parseString(body).asJsonObject
            .get("data")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return@withContext emptyList()
        val now = System.currentTimeMillis() / 1000
        val items = arr.mapNotNull { el ->
            val o = el.asJsonObject
            val code = o.get("code")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
            val endAt = o.get("end_at")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
            CdkItem(
                code = code,
                reward = o.get("content")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                endAt = endAt,
                expired = endAt in 1..now
            )
        }
        // 有效期近的排前面，已过期沉底；都没过期的按官方返回顺序（新的在前）
        items.sortedWith(compareBy({ it.expired }, { if (it.endAt > 0) it.endAt else Long.MAX_VALUE }))
    }

    // ---------------------------------------------------------------- 兑换码结束

    // ---------------------------------------------------------------- 文章搜索

    /**
     * 标题搜索。分页结构与其它接口一致；keyword 为 gamekee 全站搜索（不限定分区），
     * 实测返回的多为本区文章，偶尔混有其他区结果 —— 详情页打开失败时用户还有
     * 「网页版」兜底，这里不再按区过滤。
     */
    data class ArticlePage(val articles: List<GuideArticle>, val hasMore: Boolean)

    suspend fun searchArticles(keyword: String, page: Int = 1): ArticlePage =
        withContext(Dispatchers.IO) {
            val kw = URLEncoder.encode(keyword, "UTF-8")
            val body = httpGet(
                "$API/v1/content/searchArticle?keyword=$kw&page=$page&limit=$PAGE_SIZE"
            ) ?: throw RuntimeException("无法连接 GameKee，请检查网络")
            val arr = JsonParser.parseString(body).asJsonObject
                .get("data")?.takeIf { it.isJsonArray }?.asJsonArray
                ?: return@withContext ArticlePage(emptyList(), false)
            val items = arr.mapNotNull { el ->
                val a = parseArticle(el.asJsonObject)
                if (a.id == 0L || a.title.isBlank()) null else a
            }
            ArticlePage(items, items.size >= PAGE_SIZE)
        }

    // ---------------------------------------------------------------- 文章详情

    /**
     * 取一篇文章并渲染成完整 HTML。优先读本地正文缓存；[force] 时绕过缓存重新
     * 拉取并覆盖。
     */
    suspend fun loadArticleDetail(article: GuideArticle): GuideDetail =
        withContext(Dispatchers.IO) {
            // 每次打开都校验实时性（用户要求）：detail 接口很小，content_cdn 的
            // ?v= 参数是 gamekee 的内容版本号——与缓存记录一致就秒用缓存，
            // 不一致（官方改过文章）才重拉 CDN 正文重渲染。
            val cached = File(articleDir, "${article.id}.html")
            val cachedHtml = try {
                if (cached.exists() && cached.length() > 0) cached.readText() else null
            } catch (e: Exception) {
                Log.w(TAG, "正文缓存读取失败: ${article.id}", e)
                cached.delete()
                null
            }

            val detailBody = httpGet("$API/v1/content/detail/${article.id}")
            if (detailBody == null) {
                // 在线校验失败（离线）：缓存能用就用，离线阅读保住
                if (cachedHtml != null && cachedHtml.contains(RENDERER_STAMP)) {
                    return@withContext GuideDetail(
                        article, cachedHtml, cached.lastModified()
                    )
                }
                throw RuntimeException("无法连接 GameKee，请检查网络")
            }
            // data 可能为 null（文章被删/接口异常），见 parseTree 处的说明
            val detail = JsonParser.parseString(detailBody).asJsonObject
                .get("data")?.takeIf { it.isJsonObject }?.asJsonObject
                ?: throw RuntimeException("文章不存在或已被删除")
            val title = detail.get("title")?.takeIf { it.isJsonPrimitive }?.asString ?: article.title
            val contentType = detail.get("editor_type")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0

            // 内容版本：content_cdn 的 v 参数（形如 20260908），缺了退 updated_at
            val cdnUrlRaw = detail.get("content_cdn")?.takeIf { it.isJsonPrimitive }?.asString
            val contentVersion = cdnUrlRaw?.substringAfterLast("?v=", "")
                ?.takeIf { it.isNotBlank() }
                ?: detail.get("updated_at")?.takeIf { it.isJsonPrimitive }?.asLong?.toString()
                ?: ""

            // 缓存命中：渲染器版本 + 内容版本都对上才复用
            if (cachedHtml != null && cachedHtml.contains(RENDERER_STAMP) &&
                contentVersion.isNotBlank() && cachedHtml.contains("$CONTENT_STAMP$contentVersion")
            ) {
                return@withContext GuideDetail(
                    article, cachedHtml, cached.lastModified()
                )
            }

            // 正文一律在 CDN 上（content 字段恒为空），CDN 只对带 Referer 的请求吐 JSON
            val cdnUrl = absolutize(cdnUrlRaw)
            val cdnBody = cdnUrl?.let { httpGet(it, referer = true) }
                ?: throw RuntimeException("正文下载失败，可稍后重试")
            val cdnJson = JsonParser.parseString(cdnBody).asJsonObject

            val inner = cdnJson.get("content")?.takeIf { it.isJsonPrimitive }?.asString
                ?: throw RuntimeException("正文为空")

            val bodyHtml = when {
                contentType == 1 || inner.trimStart().startsWith("[") -> {
                    val blocks = JsonParser.parseString(inner).asJsonArray
                    // battle-array（阵容组件）需要逐个拉阵容数据，先把块里用到的
                    // 阵容 id 都收集出来预取（带磁盘缓存），渲染时查表拼 HTML。
                    // 只有 version=3 的新版组件能取到数据 —— 实测网页版也只请求这些，
                    // 旧版（version=2）连官网自己都不再渲染，统一走「网页版查看」兜底卡。
                    val battleIds = collectBattleIds(blocks)
                    currentBattleCache.clear()
                    battleIds.forEach { id ->
                        currentBattleCache[id] = battleCardHtml(id)
                    }
                    // battle-array 块渲染时需要指回原文页面，先记下再说
                    currentArticleUrl = "https://www.gamekee.com/$GAME_ALIAS/${article.id}.html"
                    try {
                        renderSlateBlocks(blocks)
                    } finally {
                        currentArticleUrl = null
                    }
                }
                // editor_type 3（表格编辑器）：角色档案/测评这类表格页（2026-09 起
                // 的新格式）。不认它的话整坨 JSON 会被当 HTML 塞进 WebView，用户
                // 看到的就是一屏「网页代码」
                contentType == 3 || inner.trimStart().startsWith("{\"baseData\"") ->
                    renderTableDocument(inner)
                else -> {
                    // 旧编辑器：本身就是 HTML。裁掉脚本与 iframe，WebView 里用不上也不该跑；
                    // 顺带把 http 图片升级成 https（WebView 禁明文，不升就是裂图）
                    inner.replace(Regex("(?is)<(script|iframe)[^>]*>.*?</\\1>"), "")
                        .replace(Regex("(?i)(\\bsrc\\s*=\\s*[\"'])http://"), "$1https://")
                }
            }

            // 评论区拼进同一份文档：渲染是本地行为，和正文一起进磁盘缓存。
            // 树导航进来的条目没有评论数，统一以实际拉到的为准（拉满 3 页或到底）
            val comments = loadComments(article.id)
            val html = renderArticleDocument(title, bodyHtml, article.comments, comments, contentVersion)
            try {
                articleDir.mkdirs()
                val tmp = File(articleDir, "${article.id}.html.part")
                tmp.writeText(html)
                tmp.renameTo(cached)
            } catch (_: Exception) {
            }
            GuideDetail(article.copy(title = title), html, System.currentTimeMillis())
        }

    /** 列表页缩略图。三层缓存与头像一致（内存 → guides/thumbs/ → 网络）。 */
    private val thumbs = ConcurrentHashMap<String, ImageBitmap>()

    suspend fun loadThumb(article: GuideArticle): ImageBitmap? {
        val url = article.thumb ?: return null
        thumbs[url]?.let { return it }
        return withContext(Dispatchers.IO) {
            val name = "${article.id}.img"
            val file = File(thumbDir, name)
            if (file.exists() && file.length() > 0) {
                decodeScaled(file)?.let { thumbs[url] = it; return@withContext it }
                file.delete()
            }
            if (!download(url, file, referer = true)) {
                file.delete()
                return@withContext null
            }
            val bmp = decodeScaled(file)
            if (bmp != null) thumbs[url] = bmp
            bmp
        }
    }

    /** 列表里的缩略图很小，直接按 256px 目标降采样，省内存也省解码时间。 */
    private fun decodeScaled(file: File): ImageBitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 256) sample *= 2
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
        })?.asImageBitmap()
    } catch (e: Exception) {
        Log.w(TAG, "缩略图解码失败: ${file.name}", e)
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    // ---------------------------------------------------------------- 阵容组件 / 评论

    private val battleDir: File get() = File(cacheDir, "battles")
    private val battleHtmlMemory = ConcurrentHashMap<Long, String>()

    /** 正在渲染的文章用到的阵容卡 HTML，渲染前由 loadArticleDetail 预取填充。 */
    private val currentBattleCache = HashMap<Long, String>()

    /** 当前渲染文章的网页地址（battle-array 兜底卡要链接回原文）。 */
    private var currentArticleUrl: String? = null

    /** 选项卡组的递增编号，保证一篇文章内多组 tabs 的 radio name 不串。 */
    private var tabsGroupCounter = 0

    /** 从 Slate 块树里收集 battle-array 组件引用的阵容 id（仅新版 version=3）。 */
    private fun collectBattleIds(node: com.google.gson.JsonElement, into: MutableSet<Long> = mutableSetOf()): Set<Long> {
        when {
            node.isJsonObject -> {
                val o = node.asJsonObject
                if (o.get("type")?.asString == "battle-array") {
                    val data = o.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
                    val version = data?.get("version")?.takeIf { it.isJsonPrimitive }?.asInt
                    val tempId = data?.get("tempId")?.takeIf { it.isJsonPrimitive }?.asLong
                    if (version == 3 && tempId != null && tempId > 0) into.add(tempId)
                }
                o.entrySet().forEach { collectBattleIds(it.value, into) }
            }
            node.isJsonArray -> node.asJsonArray.forEach { collectBattleIds(it, into) }
        }
        return into
    }

    /**
     * 一个阵容组件的展示卡 HTML（带磁盘缓存）。
     *
     * 数据来自 POST /v1/bd2Battle/detail {"id": 阵容id}：team 是「队伍 → 成员」
     * 两层数组，每个成员自带头像图、属性（暗/水/风/光/火）、伤害类型、练度等级，
     * 足够拼一张可读的阵容卡 —— 角色名要另拉 2MB 的全量名册才映射得出来，不值得。
     */
    private suspend fun battleCardHtml(battleId: Long): String =
        withContext(Dispatchers.IO) {
            battleHtmlMemory[battleId]?.let { return@withContext it }
            val cached = File(battleDir, "$battleId.html")
            if (cached.exists() && cached.length() > 0) {
                try {
                    return@withContext cached.readText().also { battleHtmlMemory[battleId] = it }
                } catch (e: Exception) {
                    Log.w(TAG, "阵容缓存读取失败: $battleId", e)
                    cached.delete()
                }
            }

            val body = httpPostJson("$API/v1/bd2Battle/detail", """{"id":$battleId}""")
            val html = if (body == null) {
                fallbackBattleCard(battleId, "阵容数据加载失败")
            } else {
                try {
                    val data = JsonParser.parseString(body).asJsonObject
                        .get("data")?.takeIf { it.isJsonObject }?.asJsonObject
                    if (data == null) fallbackBattleCard(battleId, "阵容不存在")
                    else buildBattleCard(battleId, data)
                } catch (e: Exception) {
                    Log.w(TAG, "阵容解析失败: $battleId", e)
                    fallbackBattleCard(battleId, "阵容数据异常")
                }
            }
            try {
                battleDir.mkdirs()
                val tmp = File(battleDir, "$battleId.html.part")
                tmp.writeText(html)
                tmp.renameTo(cached)
            } catch (_: Exception) {
            }
            battleHtmlMemory[battleId] = html
            html
        }

    private fun fallbackBattleCard(battleId: Long, reason: String): String {
        val url = currentArticleUrl
        val link = if (url != null) "<a href=\"${esc(url)}\">在网页版查看</a>" else ""
        return "<div class=\"battle\"><div class=\"b-head\"><span class=\"b-title\">⚔️ 阵容组件 #$battleId</span>" +
            "<span class=\"b-meta\">$reason" + (if (link.isNotBlank()) " · $link" else "") + "</span></div></div>"
    }

    private fun buildBattleCard(battleId: Long, data: JsonObject): String {
        val title = data.get("title")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val desc = data.get("desc")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val likes = data.get("like_count")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
        val views = data.get("view_count")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0

        val sb = StringBuilder("<div class=\"battle\"><div class=\"b-head\">")
        if (title.isNotBlank()) sb.append("<span class=\"b-title\">⚔️ ").append(esc(title)).append("</span>")
        sb.append("<span class=\"b-meta\">")
        if (likes > 0) sb.append("👍").append(likes).append(' ')
        if (views > 0) sb.append("👁").append(views)
        sb.append("</span></div>")

        // team 是「队伍 → 成员」两层；单队不标号，多队标 队伍1/2
        val teams = data.get("team")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
        teams.forEachIndexed { ti, teamEl ->
            if (teams.size() > 1) sb.append("<div class=\"b-team-t\">队伍").append(ti + 1).append("</div>")
            sb.append("<div class=\"b-team\">")
            teamEl.takeIf { it.isJsonArray }?.asJsonArray?.forEach { mEl ->
                val m = mEl.asJsonObject
                val avatar = absolutize(m.get("avatar")?.takeIf { it.isJsonPrimitive }?.asString)
                val attr = m.get("attr")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                val dmg = m.get("damage")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                val lv = m.get("level")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                sb.append("<div class=\"b-m\">")
                if (avatar != null) {
                    sb.append("<img class=\"b-av\" src=\"").append(esc(avatar)).append("\" loading=\"lazy\">")
                } else {
                    sb.append("<div class=\"b-av b-av-e\">?</div>")
                }
                if (attr.isNotBlank()) sb.append("<span class=\"b-attr b-").append(attrClass(attr)).append("\">").append(esc(attr)).append("</span>")
                if (lv > 0) sb.append("<span class=\"b-lv\">Lv").append(lv).append("</span>")
                if (dmg.isNotBlank()) sb.append("<span class=\"b-dmg\">").append(esc(dmg)).append("</span>")
                sb.append("</div>")
            }
            sb.append("</div>")
        }
        if (desc.isNotBlank()) {
            sb.append("<div class=\"b-desc\">").append(esc(desc).replace("\n", "<br>")).append("</div>")
        }
        sb.append("<div class=\"b-foot\">阵容模拟器组件 · 已在应用内还原</div></div>")
        return sb.toString()
    }

    private fun attrClass(attr: String) = when {
        attr.contains("火") -> "huo"
        attr.contains("水") -> "shui"
        attr.contains("风") -> "feng"
        attr.contains("光") -> "guang"
        attr.contains("暗") -> "an"
        else -> "wu"
    }

    /**
     * 文章评论区。评论接口一页 20 条、纯平铺（pid/to_user 表回复关系），
     * 最多拉 3 页足够覆盖热评；总条数用文章详情里的 comment_count 做标题。
     */
    suspend fun loadComments(contentId: Long, maxPages: Int = 3): List<GuideComment> =
        withContext(Dispatchers.IO) {
            val out = mutableListOf<GuideComment>()
            for (page in 1..maxPages) {
                val body = httpGet(
                    "$API/v1/comment/pageList?limit=20&page_no=$page&page_total=1&total=0&content_id=$contentId"
                ) ?: break
                val arr = try {
                    JsonParser.parseString(body).asJsonObject
                        .get("data")?.takeIf { it.isJsonArray }?.asJsonArray
                } catch (e: Exception) {
                    Log.w(TAG, "评论解析失败: $contentId p$page", e); null
                } ?: break
                val items = arr.mapNotNull { el ->
                    val o = el.asJsonObject
                    val id = o.get("id")?.takeIf { it.isJsonPrimitive }?.asLong ?: return@mapNotNull null
                    val user = o.get("user")?.takeIf { it.isJsonObject }?.asJsonObject
                    val toUser = o.get("to_user")?.takeIf { it.isJsonObject }?.asJsonObject
                    GuideComment(
                        id = id,
                        author = user?.get("username")?.takeIf { it.isJsonPrimitive }?.asString ?: "匿名",
                        avatar = absolutize(user?.get("avatar")?.takeIf { it.isJsonPrimitive }?.asString),
                        content = o.get("content")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                        replyTo = toUser?.get("username")?.takeIf { it.isJsonPrimitive }?.asString,
                        createdAt = o.get("created_at")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
                        likes = o.get("like_count")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                    )
                }
                out += items
                if (items.size < 20) break
            }
            out
        }

    // ---------------------------------------------------------------- 阵容组件 / 评论结束

    // ---------------------------------------------------------------- Slate → HTML

    /**
     * 把 gamekee 新编辑器的 Slate 块 JSON 翻译成 HTML。
     *
     * 只覆盖攻略里实际出现的块型；没见过的类型按「渲染其子块」兜底，
     * 保证新块型上线时页面不至于整篇空白。battle-array（阵容组件）在
     * 渲染前已由 [loadArticleDetail] 把数据预取进 [currentBattleCache]，
     * 这里只查表拼卡。
     */
    /**
     * editor_type 3（表格编辑器）→ HTML 表格。
     *
     * content 是 {"baseData":[[单元格…],…]}：外层数组是行，每个单元格
     * {"type":"text","value":"…","cellColor":"#…"} 或
     * {"type":"image","value":"//cdn…"}（实测只这两种，无跨行跨列）。
     * cellColor 是表头/字段名的背景色，照搬。解析失败退回原文 ——
     * 旧路径把 JSON 当 HTML 展示虽然难看，但不至于崩。
     */
    private fun renderTableDocument(inner: String): String {
        return try {
            val rows = JsonParser.parseString(inner).asJsonObject
                .get("baseData")?.takeIf { it.isJsonArray }?.asJsonArray
                ?: return inner
            val sb = StringBuilder("<table class=\"gktable\">")
            for (rowEl in rows) {
                if (!rowEl.isJsonArray) continue
                sb.append("<tr>")
                for (cellEl in rowEl.asJsonArray) {
                    if (!cellEl.isJsonObject) {
                        sb.append("<td></td>")
                        continue
                    }
                    val cell = cellEl.asJsonObject
                    val type = cell.get("type")?.takeIf { it.isJsonPrimitive }?.asString ?: "text"
                    val value = cell.get("value")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                    val color = cell.get("cellColor")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                    val bg = if (color.length > 1) " style=\"background:$color\"" else ""
                    sb.append("<td$bg>")
                    if (type == "image" && value.isNotBlank()) {
                        absolutize(value)?.let { url ->
                            sb.append("<img src=\"").append(esc(url)).append("\" loading=\"lazy\">")
                        }
                    } else {
                        sb.append(esc(value).replace("\n", "<br>"))
                    }
                    sb.append("</td>")
                }
                sb.append("</tr>")
            }
            sb.append("</table>").toString()
        } catch (e: Exception) {
            Log.w(TAG, "表格页解析失败，退回原文", e)
            inner
        }
    }

    private fun renderSlateBlocks(blocks: JsonArray): String {
        val sb = StringBuilder()
        for (b in blocks) sb.append(renderBlock(b.asJsonObject))
        return sb.toString()
    }

    private fun renderBlock(b: JsonObject): String {
        val children = b.get("children")?.takeIf { it.isJsonArray }?.asJsonArray
        fun renderChildren(): String {
            val out = StringBuilder()
            children?.forEach { c ->
                val o = c.asJsonObject
                out.append(if (o.has("type") && o.get("type").isJsonPrimitive) renderBlock(o)
                           else renderRuns(o))
            }
            return out.toString()
        }
        return when (b.get("type")?.asString) {
            "paragraph" -> "<p${blockAttrs(b)}>${inlineChildren(b)}</p>"
            "header1", "header2", "header3", "header4", "header5", "header6" ->
                "<h${b["type"].asString.takeLast(1)}>${inlineChildren(b)}</h${b["type"].asString.takeLast(1)}>"
            "divider" -> "<hr>"
            "highlight-block" -> {
                val emoji = b.get("emoji")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                val border = b.get("borderColor")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                val bg = b.get("backgroundColor")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                "<div class=\"hl\" style=\"border-color:$border;background:$bg\">" +
                    (if (emoji.isNotBlank()) "<span class=\"hl-e\">$emoji</span>" else "") +
                    renderChildren() + "</div>"
            }
            "flod" -> {
                val title = b.get("title")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                "<details class=\"fold\"><summary>${esc(title)}</summary>${renderChildren()}</details>"
            }
            "grid" -> "<div class=\"grid\">${renderChildren()}</div>"
            "grid-column" -> {
                val w = b.get("width")?.takeIf { it.isJsonPrimitive }?.asString ?: "50%"
                "<div class=\"col\" style=\"flex-basis:$w\">${renderChildren()}</div>"
            }
            "tabs" -> {
                // 纯 CSS 选项卡（radio + label，WebView 不开 JS 也能切换）：
                // 每组一个递增编号保证 radio name 不串组，结构必须是
                // input+label+panel 三元组相邻，选择器才命中
                val gid = "tabs${tabsGroupCounter++}"
                val sb = StringBuilder("<div class=\"tabs\">")
                children?.forEachIndexed { idx, t ->
                    val tab = t.asJsonObject
                    val label = tab.get("title")?.takeIf { it.isJsonPrimitive }?.asString ?: "页签${idx + 1}"
                    sb.append("<input class=\"tr\" type=\"radio\" name=\"").append(gid)
                        .append("\" id=\"").append(gid).append('-').append(idx).append('"')
                    if (idx == 0) sb.append(" checked")
                    sb.append("><label class=\"tl\" for=\"").append(gid).append('-').append(idx)
                        .append("\">").append(esc(label)).append("</label><div class=\"tp\">")
                    var panelLen = 0
                    tab.get("children")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { c ->
                        val before = sb.length
                        sb.append(renderBlock(c.asJsonObject))
                        panelLen += sb.length - before
                    }
                    // 旧版阵容组件（version<3）官网也不再渲染，页签会是空的；
                    // 说明一句，免得用户以为坏了
                    if (panelLen == 0) {
                        sb.append("<span class=\"tp-e\">此页签的阵容组件在官方数据里已失效（网页版同样无法显示）</span>")
                    }
                    sb.append("</div>")
                }
                sb.append("</div>").toString()
            }
            "battle-array" -> {
                val data = b.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
                val version = data?.get("version")?.takeIf { it.isJsonPrimitive }?.asInt
                val tempId = data?.get("tempId")?.takeIf { it.isJsonPrimitive }?.asLong
                when {
                    tempId != null && version == 3 ->
                        currentBattleCache[tempId]
                            ?: "<div class=\"battle\"><div class=\"b-head\"><span class=\"b-title\">⚔️ 阵容组件 #$tempId</span><span class=\"b-meta\">数据未预取</span></div></div>"
                    tempId != null -> {
                        // 旧版组件（version<3）连网页版都不再渲染，给个说明卡
                        val url = currentArticleUrl
                        val link = if (url != null) "<a href=\"${esc(url)}\">在网页版查看原文</a>" else ""
                        "<div class=\"array-card\">⚔️ 这里原本有一个旧版阵容组件，本地无法还原$link</div>"
                    }
                    else -> "" // 无 data 的空组件，官网也不渲染
                }
            }
            "table" -> "<table>${renderChildren()}</table>"
            "table-row" -> "<tr>${renderChildren()}</tr>"
            "table-cell" -> "<td>${renderChildren()}</td>"
            else -> if (children != null && children.size() > 0)
                "<p${blockAttrs(b)}>${inlineChildren(b)}</p>"
            else ""
        }
    }

    private fun blockAttrs(b: JsonObject): String {
        val align = b.get("textAlign")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        return if (align.isNotBlank()) " style=\"text-align:$align\"" else ""
    }

    /**
     * 一个段落块里的行内内容：纯文本 run、行内图片、外链、站内文章引用
     * （mention-doc）。文本 run 的粗体/颜色/字号都内联进 span。
     */
    private fun inlineChildren(block: JsonObject): String {
        val children = block.get("children")?.takeIf { it.isJsonArray }?.asJsonArray ?: return ""
        return renderRuns(children)
    }

    private fun renderRuns(children: com.google.gson.JsonElement): String {
        val sb = StringBuilder()
        if (!children.isJsonArray) return sb.toString()
        for (c in children.asJsonArray) {
            val o = c.asJsonObject
            when {
                o.get("type")?.asString == "image" -> {
                    val src = absolutize(o.get("src")?.takeIf { it.isJsonPrimitive }?.asString)
                    if (src != null) {
                        val style = o.getAsJsonObject("style")
                        val w = style?.get("width")?.takeIf { it.isJsonPrimitive }?.asString
                        sb.append("<img src=\"").append(esc(src)).append("\" alt=\"")
                            .append(esc(o.get("alt")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()))
                            .append("\" loading=\"lazy\"")
                        if (w != null) sb.append(" style=\"width:").append(esc(w)).append("\"")
                        sb.append(">")
                    }
                }
                o.get("type")?.asString == "link" -> {
                    val url = o.get("url")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
                    sb.append("<a href=\"").append(esc(url)).append("\">")
                        .append(renderRuns(o.get("children") ?: com.google.gson.JsonNull.INSTANCE))
                        .append("</a>")
                }
                o.get("type")?.asString == "mention-doc" -> {
                    val url = o.get("url")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
                    val label = o.get("content")?.takeIf { it.isJsonPrimitive }?.asString
                        ?: o.get("children")?.takeIf { it.isJsonArray }?.asJsonArray
                            ?.firstOrNull()
                            ?.takeIf { it.isJsonObject }?.asJsonObject
                            ?.get("text")?.takeIf { it.isJsonPrimitive }?.asString
                        ?: "相关攻略"
                    sb.append("<a class=\"mention\" href=\"").append(esc(url)).append("\">")
                        .append("📄 ").append(esc(label)).append("</a>")
                }
                o.has("text") -> {
                    var text = esc(o.get("text").asString)
                    if (text.isEmpty()) continue
                    if (o.get("bold")?.takeIf { it.isJsonPrimitive }?.asBoolean == true)
                        text = "<b>$text</b>"
                    if (o.get("italic")?.takeIf { it.isJsonPrimitive }?.asBoolean == true)
                        text = "<i>$text</i>"
                    if (o.get("underline")?.takeIf { it.isJsonPrimitive }?.asBoolean == true)
                        text = "<u>$text</u>"
                    if (text != esc(o.get("text").asString) ||
                        o.has("color") || o.has("fontSize") ||
                        o.has("bgColor") || o.has("background")
                    ) {
                        val color = o.get("color")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                        val size = o.get("fontSize")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                        val bg = o.get("bgColor")?.takeIf { it.isJsonPrimitive }?.asString
                            ?: o.get("background")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                        val style = buildString {
                            if (color.isNotBlank()) append("color:$color;")
                            if (size.isNotBlank()) append("font-size:$size;")
                            if (bg.isNotBlank()) append("background:$bg;")
                        }.trimEnd(';')
                        text = if (style.isNotEmpty()) "<span style=\"$style\">$text</span>"
                               else text
                    }
                    sb.append(text)
                }
            }
        }
        return sb.toString()
    }

    /**
     * 包成完整 HTML 文档。样式跟着 gamekee 正文的假设走（浅色底），并把固定宽度的
     * 图片压回容器宽度内 —— Slate 里图片常带 700px+ 的写死宽度。
     *
     * [commentsHtml] 非空时拼在正文末尾 —— 评论区跟着正文一起进缓存，离线可重读。
     */
    private fun renderArticleDocument(
        title: String,
        body: String,
        commentCount: Int,
        comments: List<GuideComment>,
        contentVersion: String
    ): String {
        val commentsHtml = if (comments.isEmpty()) "" else buildString {
            append("<div class=\"cmt\"><div class=\"cmt-h\">评论")
            // 树导航进来的文章没有预置评论数，用实际拉到的条数兜底
            val shownCount = if (commentCount > 0) commentCount else comments.size
            append("（$shownCount）")
            append("</div>")
            for (c in comments) {
                append("<div class=\"c-i\">")
                if (c.avatar != null) {
                    append("<img class=\"c-av\" src=\"").append(esc(c.avatar)).append("\" loading=\"lazy\">")
                } else {
                    append("<span class=\"c-av c-av-e\"></span>")
                }
                append("<div class=\"c-b\"><div class=\"c-u\">").append(esc(c.author))
                if (c.replyTo != null && c.replyTo != c.author) {
                    append(" <span class=\"c-to\">回复 @").append(esc(c.replyTo)).append("</span>")
                }
                append("</div><div class=\"c-t\">").append(esc(c.content).replace("\n", "<br>"))
                    .append("</div><div class=\"c-m\">")
                if (c.createdAt > 0) append(formatCommentDate(c.createdAt)).append(" · ")
                if (c.likes > 0) append("👍 ").append(c.likes)
                append("</div></div></div>")
            }
            if (commentCount > comments.size) {
                append("<div class=\"c-more\">仅加载了前 ").append(comments.size)
                    .append(" 条，剩余评论可在网页版查看</div>")
            }
            append("</div>")
        }

        return """<!doctype html>
<!--renderer:v2$CONTENT_STAMP$contentVersion-->
<html lang="zh-cn">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<style>
  body { font-family:-apple-system,'Noto Sans SC','Microsoft YaHei',sans-serif;
         font-size:16px; line-height:1.75; color:#222; background:transparent;
         margin:0; padding:16px 14px 40px; word-break:break-word; }
  img { max-width:100% !important; height:auto !important; border-radius:6px; }
  p { margin:.5em 0; }
  h4,h5 { margin:1.2em 0 .4em; }
  a { color:#1b6bb5; word-break:break-all; }
  hr { border:0; border-top:1px solid #ddd; margin:1.2em 0; }
  table { border-collapse:collapse; margin:.8em 0; }
  /* editor_type 3 的表格页（角色档案/服装测评）：gamekee 给字段名单元格
     上了底色，行内图片压一压宽度，别把表格撑破屏 */
  .gktable { border-collapse:collapse; margin:.4em 0; width:100%; }
  .gktable td { border:1px solid #ccc; padding:5px 9px; font-size:.92em;
                vertical-align:middle; word-break:break-word; }
  .gktable img { max-width:160px !important; height:auto !important;
                 border-radius:6px; }
  td,th { border:1px solid #ccc; padding:4px 8px; font-size:.92em; }
  .hl { border:1px solid #83a8fa; border-radius:8px; padding:10px 12px; margin:.8em 0; }
  .hl-e { font-size:1.2em; margin-right:4px; }
  .fold { border:1px solid #e3e3e3; border-radius:8px; padding:8px 12px; margin:.8em 0; }
  .fold summary { font-weight:600; cursor:pointer; }
  .grid { display:flex; gap:10px; flex-wrap:wrap; margin:.6em 0; }
  .col { flex:1 1 40%; min-width:140px; }
  .mention { display:inline-block; background:#eef3fb; border-radius:6px;
             padding:0 6px; margin:1px 0; text-decoration:none; }
  .array-card { border:1px dashed #c9a15a; background:#fdf6e9; border-radius:8px;
                padding:10px 12px; margin:.8em 0; color:#7a5c1e; }
  /* 选项卡：radio+label 纯 CSS 实现，无 JS 也可切换。结构必须是
     input.tr + label.tl + div.tp 三元组相邻 */
  .tabs .tr { display:none; }
  .tabs .tl { display:inline-block; padding:4px 12px; margin:2px 4px 2px 0;
              border-radius:14px; background:#f0f0f0; color:#555;
              font-size:.9em; cursor:pointer; }
  .tabs .tr:checked + .tl { background:#4a6ea9; color:#fff; font-weight:600; }
  .tabs .tp { display:none; border:1px solid #e3e3e3; border-radius:8px;
              padding:8px 12px; margin:.4em 0 .8em; }
  .tabs .tr:checked + .tl + .tp { display:block; }
  .tp-e { font-size:.82em; color:#b08540; }
  /* 阵容组件卡 */
  .battle { border:1px solid #d9c89a; background:#fdf9ef; border-radius:10px;
            padding:10px 12px; margin:.8em 0; }
  .b-head { display:flex; justify-content:space-between; align-items:baseline;
            gap:8px; margin-bottom:6px; }
  .b-title { font-weight:700; color:#5c4a1e; }
  .b-meta { font-size:.8em; color:#9a8a5e; white-space:nowrap; }
  .b-team-t { font-size:.85em; color:#9a8a5e; margin:4px 0 2px; }
  .b-team { display:flex; flex-wrap:wrap; gap:8px; margin:.3em 0; }
  .b-m { display:flex; flex-direction:column; align-items:center; width:64px; }
  /* 组件图的尺寸要用 !important 压过全局的 height:auto，否则头像会被拉成椭圆 */
  .b-av { width:56px !important; height:56px !important; border-radius:8px;
          border:1px solid #e0d6b8; object-fit:cover; background:#fff; }
  .b-av-e { display:flex; align-items:center; justify-content:center; color:#b3a877; }
  .b-attr { font-size:.72em; color:#fff; border-radius:4px; padding:0 6px; margin-top:2px; }
  .b-huo { background:#d9534f; } .b-shui { background:#4272c4; }
  .b-feng { background:#4a9c52; } .b-guang { background:#c8971d; }
  .b-an { background:#7d55b8; } .b-wu { background:#9aa0a6; }
  .b-lv { font-size:.72em; color:#8a8a8a; }
  .b-dmg { font-size:.72em; color:#7a6a3a; }
  .b-desc { font-size:.9em; color:#5a5240; background:#f7f2e2; border-radius:6px;
            padding:6px 8px; margin-top:6px; line-height:1.6; }
  .b-foot { font-size:.72em; color:#b3a877; margin-top:6px; }
  /* 评论区 */
  .cmt { border-top:2px solid #eee; margin-top:1.6em; padding-top:.6em; }
  .cmt-h { font-weight:700; font-size:1.05em; margin-bottom:.6em; }
  .c-i { display:flex; gap:10px; padding:.5em 0; border-bottom:1px solid #f2f2f2; }
  .c-av { width:32px !important; height:32px !important; border-radius:50%;
          flex:none; object-fit:cover; background:#e8e8e8; }
  .c-av-e { display:inline-block; background:#e4e4e4; }
  .c-b { flex:1; min-width:0; }
  .c-u { font-size:.85em; color:#7a7a7a; }
  .c-to { color:#b08540; font-size:.92em; }
  .c-t { margin:.1em 0; }
  .c-m { font-size:.78em; color:#aaa; }
  .c-more { font-size:.85em; color:#999; text-align:center; padding:.6em 0 .2em; }
</style>
</head>
<body>$body$commentsHtml</body>
</html>"""
    }

    private val commentDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    private fun formatCommentDate(epochSeconds: Long): String =
        commentDateFormat.format(Date(epochSeconds * 1000))

    // ---------------------------------------------------------------- HTTP

    /**
     * 基础 GET。gamekee 的wiki 接口要分区身份头 + XHR 头；CDN 只要 Referer。
     * 返回 body 字符串，失败（网络/非 200/拿回的是验证 HTML 而非 JSON）返回 null。
     */
    private fun httpGet(url: String, referer: Boolean = false): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 20000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                if (referer) {
                    setRequestProperty("Referer", "https://www.gamekee.com/")
                } else {
                    setRequestProperty("X-Requested-With", "XMLHttpRequest")
                    setRequestProperty("game-id", GAME_ID)
                    setRequestProperty("game-alias", GAME_ALIAS)
                }
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "HTTP ${conn.responseCode}: $url")
                return null
            }
            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            // CDN 偶尔会吐一个验证 HTML 页，按约定「content 必以 { 或 [ 开头」判真假
            val trimmed = body.trimStart()
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) body else null
        } catch (e: Exception) {
            Log.w(TAG, "请求失败: $url (${e.message})")
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** POST + JSON body 版的 [httpGet]，给兑换码这类只收 POST 的接口用。 */
    private fun httpPostJson(url: String, jsonBody: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 20000
                instanceFollowRedirects = true
                doOutput = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("X-Requested-With", "XMLHttpRequest")
                setRequestProperty("game-id", GAME_ID)
                setRequestProperty("game-alias", GAME_ALIAS)
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "HTTP ${conn.responseCode}: $url")
                return null
            }
            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val trimmed = body.trimStart()
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) body else null
        } catch (e: Exception) {
            Log.w(TAG, "请求失败: $url (${e.message})")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun download(url: String, dest: File, referer: Boolean = false): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            dest.parentFile?.mkdirs()
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 20000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                // gamekee 的图片 CDN（cdnimg-v2）与正文 CDN 一样校验 Referer，
                // 不带会回一个 567 状态页
                if (referer) setRequestProperty("Referer", "https://www.gamekee.com/")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return false
            conn.inputStream.use { input ->
                dest.outputStream().use { out -> input.copyTo(out) }
            }
            dest.length() > 0
        } catch (e: Exception) {
            Log.w(TAG, "下载失败: $url (${e.message})")
            false
        } finally {
            conn?.disconnect()
        }
    }

    // ---------------------------------------------------------------- 缓存管理

    /** guides/ 缓存占用（字节）与文件数，给攻略页的清空入口显示。 */
    fun usage(): Pair<Long, Int> {
        val files = cacheDir.walkTopDown().filter { it.isFile }
            .filterNot { it.name.endsWith(".part") }.toList()
        return files.sumOf { it.length() } to files.size
    }

    fun clearCache() {
        cacheDir.deleteRecursively()
        treeCache = null
        thumbs.clear()
    }

    // HTML 转义放到最后：kotlin.text.escapeHtml 不存在，自己来，只处理会破结构的几个
    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
