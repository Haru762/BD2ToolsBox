package com.bd2toolsbox.data.repository

import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.bd2toolsbox.core.images.BoundedImageCache
import com.bd2toolsbox.core.images.BoundedStringCache
import com.bd2toolsbox.data.model.CdkItem
import com.bd2toolsbox.data.model.GuideArticle
import com.bd2toolsbox.data.model.GuideCategory
import com.bd2toolsbox.data.model.GuideComment
import com.bd2toolsbox.data.model.GuideDetail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

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
@Singleton
class GamekeeRepository @Inject constructor(
    @param:ApplicationContext private val appContext: android.content.Context,
    /** T30：HTTP 层已拆到 [com.bd2toolsbox.data.remote.GamekeeApi]。 */
    private val api: com.bd2toolsbox.data.remote.GamekeeApi,
    /** T30：磁盘缓存的位置与读写已拆到 [com.bd2toolsbox.data.cache.GuideCacheStore]。 */
    private val cache: com.bd2toolsbox.data.cache.GuideCacheStore
) {

    companion object {
        private const val TAG = "GamekeeRepository"

        /** 缩略图内存上限（T34）：256px 缩略图约 256 KB/张，8 MB ≈ 32 张。 */
        const val THUMB_MEMORY_BUDGET_BYTES = 8L * 1024 * 1024

        /** 阵容正文内存上限（T34）：一篇几十 KB，4 MB ≈ 40~80 篇。 */
        const val HTML_MEMORY_BUDGET_BYTES = 4L * 1024 * 1024

        /** gamekee 给棕色尘埃2分区的固定 id / 别名（接口头与 CDN 路径都要用）。 */
        // T30：这三个常量与 HTTP 层一起搬到了 GamekeeApi，这里保留同名别名，
        // 让本文件里的字符串拼接调用点一行都不用改。
        private const val GAME_ID = com.bd2toolsbox.data.remote.GamekeeApi.GAME_ID
        private const val GAME_ALIAS = com.bd2toolsbox.data.remote.GamekeeApi.GAME_ALIAS
        private const val API = com.bd2toolsbox.data.remote.GamekeeApi.BASE_URL
        private const val CDN_CONTENT = "https://api-cdn.gamekee.com/wiki2.0/pro/$GAME_ID/content"

        /** 正文缓存头部的渲染器版本前缀。改渲染逻辑时同步递增；缓存里缺失即视为
         *  旧渲染，自动失效重拉（见 loadArticleDetail）。
         *  v3：评论正文解析重做 —— 表情标记 `_(… […])` 换成表情图、发言上传的
         *  图片排到文字下方（v2 缓存里这两样都是转义后的代码文本，必须重渲染）。
         *  v4：接入 v2 阵容接口，淘汰旧版组件无法显示的错误占位。
         *  v5：v3 阵容接入完整二级回合站位轴与角色装备面板，editor_type 3 接入多皮肤 styleData 选项卡。
         *  v6：1:1 复刻 GameKee 原版阵容卡片与 LineupDetail 二级视图（接入 bd2roles 字典与原版组件样式）。
         *  v7：针对手机竖屏深度重构阵容卡片与二级详情 UI（上下流式棋盘+行动卡、Sticky 冻结矩阵、现代资料卡面板）。 */
        private const val RENDERER_STAMP = "<!--renderer:v7"

        /** 渲染器戳后面跟着的内容版本号（来自 content_cdn 的 ?v=），两者都对上
         *  才复用缓存 —— 官方改了文章版本号就变，缓存自动过期。 */
        private const val CONTENT_STAMP = "|v="

        /** 暂时失败的阵容不应让整篇正文永久命中缓存；离线仍可阅读已下载部分。 */
        private const val BATTLE_UNAVAILABLE_STAMP = "<!--battle:unavailable-->"

        /** 表情表磁盘缓存的有效期。表情包很少增补，一天一次足够；过期只用旧表兜底。 */
        private const val EMOJI_TTL_MS = 24 * 60 * 60 * 1000L

        /** 评论里最多排几张上传图（网页端也是 3 张 + 「+N」角标）。 */
        private const val COMMENT_IMG_MAX = 3

        /** 列表分页大小。gamekee 页面端也是 10 条一页。 */
        const val PAGE_SIZE = 10

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

    // T30：路径与读写都在 GuideCacheStore；这里保留同名转发，调用点逐行不变。
    private val cacheDir: File get() = cache.root
    private val treeFile: File get() = cache.treeFile
    private val articleDir: File get() = cache.articleDir
    private val thumbDir: File get() = cache.thumbDir

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
                    val cdnPath = Json.parseToJsonElement(dict).asJsonObject
                        .get("data")?.takeIf { it.isJsonObject }?.asJsonObject
                        ?.get("cdn_path")?.takeIf { it.isJsonPrimitive }?.asString
                    absolutize(cdnPath)?.let { httpGet(it, referer = true) }
                } catch (_: Exception) {
                    null
                }
            }
            val data = parseTree(
                Json.parseToJsonElement(treeBody),
                dictBody?.let { Json.parseToJsonElement(it) },
                iconsBody?.let { Json.parseToJsonElement(it) }
            )
            treeCache = data
            // 缓存写不进去不影响使用（writeAtomic 内部吞掉异常）
            cache.writeAtomic(
                treeFile,
                """{"entry":$treeBody,"dict":${dictBody ?: "null"},"icons":${iconsBody ?: "null"}}"""
            )
            return@withContext data
        }

        // 在线拉不到（离线 / 接口抖动）：退磁盘缓存，保证还能看
        treeCache?.let { return@withContext it }
        try {
            if (treeFile.exists()) {
                val cachedPair = Json.parseToJsonElement(treeFile.readText()).asJsonObject
                return@withContext parseTree(
                    requireNotNull(cachedPair["entry"]), cachedPair["dict"], cachedPair["icons"]
                ).also { treeCache = it }
            }
        } catch (e: Exception) {
            Log.w(TAG, "树缓存解析失败", e)
            treeFile.delete()
        }
        throw RuntimeException("无法连接 GameKee，请检查网络")
    }

    private fun parseTree(
        root: JsonElement,
        dictRoot: JsonElement?,
        iconsRoot: JsonElement?
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
        val arr = Json.parseToJsonElement(body).asJsonObject
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
            val arr = Json.parseToJsonElement(body).asJsonObject
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
            val cached = cache.articleFile(article.id)
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
            val detail = Json.parseToJsonElement(detailBody).asJsonObject
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
                !cachedHtml.contains(BATTLE_UNAVAILABLE_STAMP) &&
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
            val cdnJson = Json.parseToJsonElement(cdnBody).asJsonObject

            val inner = cdnJson.get("content")?.takeIf { it.isJsonPrimitive }?.asString
                ?: throw RuntimeException("正文为空")

            val bodyHtml = when {
                contentType == 1 || inner.trimStart().startsWith("[") -> {
                    val blocks = Json.parseToJsonElement(inner).asJsonArray
                    val refs = collectBattleRefs(blocks)
                    if (refs.isNotEmpty()) {
                        GamekeeBattleRoleCatalog.ensureLoaded(appContext)
                    }
                    // v2/v3 使用不同接口和 ID 空间，预取及渲染都用 (version, id) 查表。
                    val battleCards = HashMap<BattleRef, String>()
                    refs.forEach { ref ->
                        battleCardHtml(ref)?.let { battleCards[ref] = it }
                    }
                    renderSlateBlocks(blocks, ArticleRenderContext(
                        battleCards, "$API/$GAME_ALIAS/" + article.id + ".html"
                    ))
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
            // 表情表只在真有评论时拉：评论里的 `_(… […])` 标记靠它换图
            val emojis = if (comments.isEmpty()) emptyMap() else loadEmojiMap()
            val html = renderArticleDocument(title, bodyHtml, article.comments, comments, contentVersion, emojis)
            cache.writeAtomic(cached, html)
            GuideDetail(article.copy(title = title), html, System.currentTimeMillis())
        }

    /**
     * 列表页缩略图。三层缓存与头像一致（内存 → guides/thumbs/ → 网络）。
     *
     * T34：内存这一层换成**按字节计上限**的 LRU。以前是无上限的
     * `ConcurrentHashMap<String, ImageBitmap>` —— 滑一遍长列表就把每张解码图
     * 永久留在内存里（256px 缩略图约 256 KB/张），直到用户手动清缓存。
     */
    private val thumbs = BoundedImageCache(THUMB_MEMORY_BUDGET_BYTES)

    suspend fun loadThumb(article: GuideArticle): ImageBitmap? {
        val url = article.thumb ?: return null
        thumbs.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            val name = "${article.id}.img"
            val file = cache.thumbFile(name)
            if (file.exists() && file.length() > 0) {
                decodeScaled(file)?.let { thumbs.put(url, it); return@withContext it }
                file.delete()
            }
            if (!download(url, file, referer = true)) {
                file.delete()
                return@withContext null
            }
            val bmp = decodeScaled(file)
            if (bmp != null) thumbs.put(url, bmp)
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

    private val battleDir: File get() = cache.battleDir

    /** v2 模板 ID 与 v3 阵容 ID 不属于同一命名空间。 */
    private data class BattleRef(val version: Int, val id: Long)

    // T34：同样无上限（一篇正文几十 KB）→ 换成有上限的 LRU。
    private val battleHtmlMemory = BoundedStringCache(HTML_MEMORY_BUDGET_BYTES)

    /** 表情表（[loadEmojiMap]）的落盘文件与内存副本。 */
    private val emojiFile: File get() = cache.emojiFile

    @Volatile
    private var emojiMapMemory: Map<String, String>? = null

    /** 每篇文章独立持有预取结果、原文链接和页签编号，避免并发加载时互相覆盖。 */
    private class ArticleRenderContext(
        val battleCards: Map<BattleRef, String>,
        val articleUrl: String,
        var tabsGroupCounter: Int = 0
    )

    private fun parseBattleRefs(data: JsonObject?): List<BattleRef> {
        if (data == null) return emptyList()
        val version = data.get("version")?.takeIf { it.isJsonPrimitive }?.asString?.toIntOrNull()
            ?: return emptyList()
        if (version != 2 && version != 3) return emptyList()
        val rawIds = data.get("tempId")?.takeIf { it.isJsonPrimitive }?.asString?.ifBlank { null }
            ?: data.get("battleIds")?.takeIf { it.isJsonPrimitive }?.asString
            ?: return emptyList()
        return rawIds.split(',')
            .mapNotNull { it.trim().toLongOrNull() }
            .filter { it > 0L }
            .map { BattleRef(version, it) }
    }

    /** 递归收集 Slate 块树中受支持的阵容引用，包含 tabs / 折叠块中的组件。 */
    private fun collectBattleRefs(
        node: JsonElement,
        into: MutableSet<BattleRef> = linkedSetOf()
    ): Set<BattleRef> {
        when {
            node.isJsonObject -> {
                val o = node.asJsonObject
                if (o.get("type")?.takeIf { it.isJsonPrimitive }?.asString == "battle-array") {
                    val data = o.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
                    into.addAll(parseBattleRefs(data))
                }
                o.entries.forEach { collectBattleRefs(it.value, into) }
            }
            node.isJsonArray -> node.asJsonArray.forEach { collectBattleRefs(it, into) }
        }
        return into
    }

    /**
     * v2: GET /v1/userBattleTemp/detail?id=...，回合记录在 data.content JSON 字符串中。
     * v3: POST /v1/bd2Battle/detail，继续使用已有 team 渲染。
     * 只有成功的卡片才进入缓存；失败由文章渲染器就地生成带原文链接的提示。
     */
    private suspend fun battleCardHtml(ref: BattleRef): String? = withContext(Dispatchers.IO) {
        battleHtmlMemory.get(ref)?.let { return@withContext it }
        val key = "v7_" + ref.version + "_" + ref.id
        val cached = cache.battleFile(key)
        if (cached.exists() && cached.length() > 0) {
            try {
                return@withContext cached.readText().also { battleHtmlMemory.put(ref, it) }
            } catch (e: Exception) {
                Log.w(TAG, "阵容缓存读取失败: $ref", e)
                cached.delete()
            }
        }

        val body = when (ref.version) {
            2 -> httpGet("$API/v1/userBattleTemp/detail?id=" + ref.id)
            3 -> httpPostJson("$API/v1/bd2Battle/detail", "{\"id\":" + ref.id + "}")
            else -> null
        } ?: return@withContext null
        val html = try {
            val response = Json.parseToJsonElement(body).asJsonObject
            val code = response.get("code")?.takeIf { it.isJsonPrimitive }?.asString?.toIntOrNull()
            if (code != 0) {
                Log.w(TAG, "阵容接口未返回成功: $ref (code=$code)")
                return@withContext null
            }
            val data = response.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
                ?: return@withContext null
            if (ref.version == 2) GamekeeBattleV2Renderer.render(ref.id, data)
            else GamekeeBattleV3Renderer.render(ref.id, data)
        } catch (e: Exception) {
            Log.w(TAG, "阵容解析失败: $ref", e)
            return@withContext null
        }
        cache.writeAtomic(cached, html)
        battleHtmlMemory.put(ref, html)
        html
    }

    private fun fallbackBattleCard(battleId: Long, reason: String, articleUrl: String): String {
        val link = "<a href=\"" + esc(articleUrl) + "\">在网页版查看原文</a>"
        return "<div class=\"battle b-fallback\"><div class=\"b-head\"><span class=\"b-title\">⚔️ 阵容组件 #$battleId</span>" +
            "<span class=\"b-meta\">" + esc(reason) + " · $link</span></div></div>"
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
        val teams = data.get("team")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray(emptyList())
        teams.forEachIndexed { ti, teamEl ->
            if (teams.size > 1) sb.append("<div class=\"b-team-t\">队伍").append(ti + 1).append("</div>")
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
                    Json.parseToJsonElement(body).asJsonObject
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

    // ---------------------------------------------------------------- 评论表情表

    /**
     * 评论正文里 `_(尤里表情包 [78])` 这种表情贴图的对照表。
     *
     * 标记原文直接就是图 —— 接口（网页端表情面板同款）里逐个对上：
     *   GET /v1/emojiGroup/queryList
     *     → data[].children[] { name: "_(尤里表情包 [78])", icon: "//cdnimg-v2…" }
     * name 就是标记原文、icon 就是图，照抄即可。**必须查表，不能自己按编号拼**：
     * 实测标记里不只有数字，还有 `_(K站 [震惊])`（中文词）和
     * `_(其他角色表情包 [16··])`（双点）这类形态 —— 全表 4 组 151 个，
     * 抓到的 34 处标记 100% 命中。
     *
     * 缓存：内存 → guides/emoji.json（[EMOJI_TTL_MS] 内直接用，表情表很少增补）
     * → 网络（拉到就覆盖写盘）。网络失败时磁盘旧表无条件顶上（表情图很稳定，
     * 旧表也比没有强），两样都没有才返回空表。
     *
     * TODO 正文（Slate/HTML）不替换表情标记：抽样的三篇正文（editor_type 1/3）
     * 一个标记都没有，网页端也只在对评论调 replaceEmoji。哪天正文里真出现
     * `_(… […])`，把 [ArticleRenderContext] 那套「渲染前预置、渲染时查表」照搬过来
     * 接进 renderRuns 即可。
     */
    private suspend fun loadEmojiMap(): Map<String, String> = withContext(Dispatchers.IO) {
        emojiMapMemory?.let { return@withContext it }
        val diskMap = readEmojiFile()
        if (diskMap != null && System.currentTimeMillis() - emojiFile.lastModified() < EMOJI_TTL_MS) {
            emojiMapMemory = diskMap
            return@withContext diskMap
        }
        val body = httpGet("$API/v1/emojiGroup/queryList")
        val fresh = body?.let { parseEmojiMap(it) }
        if (fresh != null && fresh.isNotEmpty()) {
            // 写不进缓存不影响这次使用（writeAtomic 内部吞掉异常）
            cache.writeAtomic(emojiFile, body)
            emojiMapMemory = fresh
            return@withContext fresh
        }
        // 在线拉不到（离线/接口抖动）：旧表顶上。空表不写内存，下次开文章还能再试
        if (diskMap != null && diskMap.isNotEmpty()) {
            emojiMapMemory = diskMap
            return@withContext diskMap
        }
        emptyMap()
    }

    private fun readEmojiFile(): Map<String, String>? = try {
        if (emojiFile.exists() && emojiFile.length() > 0) parseEmojiMap(emojiFile.readText()) else null
    } catch (e: Exception) {
        Log.w(TAG, "表情表缓存读取失败", e)
        emojiFile.delete()
        null
    }

    private fun parseEmojiMap(body: String): Map<String, String>? = try {
        val arr = Json.parseToJsonElement(body).asJsonObject
            .get("data")?.takeIf { it.isJsonArray }?.asJsonArray
        if (arr == null) null else {
            val map = HashMap<String, String>()
            for (g in arr) {
                val children = g.takeIf { it.isJsonObject }?.asJsonObject
                    ?.get("children")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?: continue
                for (c in children) {
                    val o = c.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                    val name = o.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
                    if (!name.startsWith("_(")) continue
                    val icon = absolutize(o.get("icon")?.takeIf { it.isJsonPrimitive }?.asString)
                        ?: continue
                    map[name] = icon
                }
            }
            map
        }
    } catch (e: Exception) {
        Log.w(TAG, "表情表解析失败", e)
        null
    }

    // ---------------------------------------------------------------- 阵容组件 / 评论结束

    // ---------------------------------------------------------------- Slate → HTML

    /**
     * 把 gamekee 新编辑器的 Slate 块 JSON 翻译成 HTML。
     *
     * 只覆盖攻略里实际出现的块型；没见过的类型按「渲染其子块」兜底，
     * 保证新块型上线时页面不至于整篇空白。battle-array（阵容组件）在
     * 渲染前已由 [loadArticleDetail] 把数据预取进 [ArticleRenderContext]，
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
    private fun renderTableRows(rows: JsonArray, isStyleSheet: Boolean = false): String {
        val sb = StringBuilder("<table class=\"gktable\">")
        for (rowEl in rows) {
            if (!rowEl.isJsonArray) continue
            val cells = rowEl.asJsonArray.mapNotNull { if (it.isJsonObject) it.asJsonObject else null }
            if (cells.isEmpty()) continue
            val firstText = cells.firstOrNull()?.get("value")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
            // 过滤内部编辑器专用的空配置行 / Live2D 原始 JSON 行 / 音频文件原始行
            if (isStyleSheet && (
                    firstText.contains("live 2d", ignoreCase = true) ||
                    firstText.contains("live2D", ignoreCase = true) ||
                    firstText in setOf("服装类别", "服装完整立绘", "服装普通概率", "服装up概率", "服装UP时间", "服装毕业破度", "超链接")
                )
            ) {
                continue
            }
            val nonBlankCount = cells.count { c ->
                val t = c.get("type")?.takeIf { it.isJsonPrimitive }?.asString ?: "text"
                val v = c.get("value")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
                v.isNotEmpty() && t != "live2d" && !v.startsWith("{")
            }
            if (nonBlankCount == 0) continue
            // 若整行只有第一个标题单元格有字、后面全是空的模板占位行，跳过
            if (isStyleSheet && cells.size > 1 && nonBlankCount == 1 && firstText.isNotEmpty()) {
                continue
            }

            sb.append("<tr>")
            for (cell in cells) {
                val type = cell.get("type")?.takeIf { it.isJsonPrimitive }?.asString ?: "text"
                if (type == "live2d") continue
                val value = cell.get("value")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                if (value.trimStart().startsWith("{\"") && value.length > 80) continue
                val color = cell.get("cellColor")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                val bg = if (color.length > 1) " style=\"background:$color\"" else ""
                sb.append("<td$bg>")
                when {
                    type == "image" && value.isNotBlank() -> {
                        absolutize(value)?.let { url ->
                            sb.append("<img src=\"").append(esc(url)).append("\" loading=\"lazy\">")
                        }
                    }
                    type == "video" || type == "audio" -> {
                        absolutize(value)?.let { url ->
                            sb.append("<a href=\"").append(esc(url)).append("\">[查看媒体]</a>")
                        }
                    }
                    else -> sb.append(esc(value).replace("\n", "<br>"))
                }
                sb.append("</td>")
            }
            sb.append("</tr>")
        }
        return sb.append("</table>").toString()
    }

    private fun extractCostumeTabTitle(styleObj: JsonObject, index: Int): String {
        val defaultName = styleObj.get("name")?.takeIf { it.isJsonPrimitive }?.asString
            ?.takeIf { it.isNotBlank() } ?: "服装 ${index + 1}"
        val rows = styleObj.get("data")?.takeIf { it.isJsonArray }?.asJsonArray ?: return defaultName
        val firstRow = rows.firstOrNull()?.takeIf { it.isJsonArray }?.asJsonArray ?: return defaultName
        val costumeName = firstRow.getOrNull(1)?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("value")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        return if (costumeName.isNotEmpty()) "服装 ${index + 1}：$costumeName" else defaultName
    }

    private fun renderTableDocument(inner: String): String {
        return try {
            val rootObj = Json.parseToJsonElement(inner).asJsonObject
            val baseRows = rootObj.get("baseData")?.takeIf { it.isJsonArray }?.asJsonArray
                ?: return inner
            val styleList = rootObj.get("styleData")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { if (it.isJsonObject) it.asJsonObject else null }
                .orEmpty()

            if (styleList.isEmpty()) {
                return renderTableRows(baseRows, isStyleSheet = false)
            }

            // 含 styleData（多套服装详情）的角色图鉴页：顶部生成纯 CSS 选项卡切换「基础信息」与各套服装
            val sb = StringBuilder("<div class=\"tabs tj-style-tabs\">")
            sb.append("<input class=\"tr\" type=\"radio\" name=\"tj_style_group\" id=\"tj_style_0\" checked>")
            sb.append("<label class=\"tl\" for=\"tj_style_0\">📋 基础属性与专武</label>")
            sb.append("<div class=\"tp\">").append(renderTableRows(baseRows, isStyleSheet = false)).append("</div>")

            styleList.forEachIndexed { idx, styleObj ->
                val tabId = "tj_style_${idx + 1}"
                val tabTitle = extractCostumeTabTitle(styleObj, idx)
                val dataRows = styleObj.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
                if (dataRows != null && dataRows.size > 0) {
                    sb.append("<input class=\"tr\" type=\"radio\" name=\"tj_style_group\" id=\"")
                        .append(tabId).append("\">")
                    sb.append("<label class=\"tl\" for=\"").append(tabId).append("\">👗 ")
                        .append(esc(tabTitle)).append("</label>")
                    sb.append("<div class=\"tp\">")
                        .append(renderTableRows(dataRows, isStyleSheet = true))
                        .append("</div>")
                }
            }
            sb.append("</div>").toString()
        } catch (e: Exception) {
            Log.w(TAG, "表格页解析失败，退回原文", e)
            inner
        }
    }

    private fun renderSlateBlocks(blocks: JsonArray, context: ArticleRenderContext): String {
        val sb = StringBuilder()
        for (b in blocks) sb.append(renderBlock(b.asJsonObject, context))
        return sb.toString()
    }

    private fun renderBlock(b: JsonObject, context: ArticleRenderContext): String {
        val children = b.get("children")?.takeIf { it.isJsonArray }?.asJsonArray
        fun renderChildren(): String {
            val out = StringBuilder()
            children?.forEach { c ->
                val o = c.asJsonObject
                out.append(if (o.has("type") && o["type"]?.isJsonPrimitive == true) renderBlock(o, context)
                           else renderRuns(o))
            }
            return out.toString()
        }
        return when (b.get("type")?.asString) {
            "paragraph" -> "<p${blockAttrs(b)}>${inlineChildren(b)}</p>"
            "header1", "header2", "header3", "header4", "header5", "header6" ->
                "<h${b.getValue("type").asString.takeLast(1)}>${inlineChildren(b)}</h${b.getValue("type").asString.takeLast(1)}>"
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
                val gid = "tabs" + context.tabsGroupCounter++
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
                        sb.append(renderBlock(c.asJsonObject, context))
                        panelLen += sb.length - before
                    }
                    // 空页签只描述当前内容，不推断官网或源数据已经失效。
                    if (panelLen == 0) {
                        sb.append("<span class=\"tp-e\">此页签暂无可显示的内容</span>")
                    }
                    sb.append("</div>")
                }
                sb.append("</div>").toString()
            }
            "battle-array" -> {
                val data = b.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
                val refs = parseBattleRefs(data)
                val tempId = data?.get("tempId")?.takeIf { it.isJsonPrimitive }?.asString?.toLongOrNull()
                when {
                    refs.isNotEmpty() -> refs.joinToString("\n") { ref ->
                        context.battleCards[ref] ?: (BATTLE_UNAVAILABLE_STAMP +
                            fallbackBattleCard(ref.id, "阵容暂时无法加载，请刷新重试", context.articleUrl))
                    }
                    tempId != null && tempId > 0 ->
                        fallbackBattleCard(tempId, "暂不支持此版本的阵容组件", context.articleUrl)
                    else -> "" // 无引用的空组件
                }
            }
            "table" -> "<table>${renderChildren()}</table>"
            "table-row" -> "<tr>${renderChildren()}</tr>"
            "table-cell" -> "<td>${renderChildren()}</td>"
            else -> if (children != null && children.size > 0)
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

    private fun renderRuns(children: JsonElement): String {
        val sb = StringBuilder()
        if (!children.isJsonArray) return sb.toString()
        for (c in children.asJsonArray) {
            val o = c.asJsonObject
            when {
                o.get("type")?.asString == "image" -> {
                    val src = absolutize(o.get("src")?.takeIf { it.isJsonPrimitive }?.asString)
                    if (src != null) {
                        val style = o["style"] as? JsonObject
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
                        .append(renderRuns(o.get("children") ?: JsonNull))
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
                    var text = esc(o.getValue("text").asString)
                    if (text.isEmpty()) continue
                    if (o.get("bold")?.takeIf { it.isJsonPrimitive }?.asBoolean == true)
                        text = "<b>$text</b>"
                    if (o.get("italic")?.takeIf { it.isJsonPrimitive }?.asBoolean == true)
                        text = "<i>$text</i>"
                    if (o.get("underline")?.takeIf { it.isJsonPrimitive }?.asBoolean == true)
                        text = "<u>$text</u>"
                    if (text != esc(o.getValue("text").asString) ||
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
     * [emojis] 是评论表情标记的对照表（见 [loadEmojiMap]）。
     */
    private fun renderArticleDocument(
        title: String,
        body: String,
        commentCount: Int,
        comments: List<GuideComment>,
        contentVersion: String,
        emojis: Map<String, String>
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
                append("</div><div class=\"c-t\">").append(commentContentHtml(c.content, emojis))
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
$RENDERER_STAMP$CONTENT_STAMP$contentVersion-->
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
  /* 阵容组件卡（移动端竖屏优先设计 + GameKee 官方视觉元素） */
  .zs-red { background:linear-gradient(180deg,#884044,#55282c) !important; }
  .zs-blue { background:linear-gradient(180deg,#486880,#263542) !important; }
  .zs-green { background:linear-gradient(180deg,#497856,#26402d) !important; }
  .zs-yellow { background:linear-gradient(180deg,#8f8048,#5c522e) !important; }
  .zs-purple { background:linear-gradient(180deg,#624c75,#352940) !important; }
  .empty-not { background:rgba(105,127,139,.14) !important; }

  .user-lineup-card { background:#f7f8fa; border:1px solid #e2e6ed;
                      border-radius:14px; box-sizing:border-box; max-width:100%; min-width:0;
                      padding:14px; margin:.9em 0; box-shadow:0 2px 8px rgba(15,23,42,.04); }
  .card-header { display:flex; flex-direction:column; gap:6px; margin-bottom:12px; min-width:0; }
  .card-header .line { display:flex; justify-content:space-between; align-items:center; width:100%; gap:6px; }
  .card-header .line .left { display:flex; align-items:center; flex-wrap:wrap; flex:1; gap:6px; min-width:0; }
  .card-header .line .update-time { color:#8a94a6; font-size:11px; font-weight:500; white-space:nowrap; }
  .type-tag { display:inline-flex; align-items:center; font-weight:700; font-size:11px;
              padding:2px 8px; border-radius:6px; line-height:1.4; }
  .type-tag.is-main { background:#fff3e6; color:#b85d19; }
  .type-tag.is-warcraft { background:#ffeaea; color:#c92a2a; }
  .type-tag.is-guild { background:#f3e8ff; color:#6b21a8; }
  .gk-boss-chip { display:inline-flex; align-items:center; gap:4px; background:#fff; border:1px solid #e5e7eb;
                  border-radius:6px; padding:1px 7px; font-size:11px; font-weight:600; color:#374151; }
  .gk-boss-chip img { width:15px !important; height:15px !important; border-radius:3px !important; object-fit:contain; }
  .gk-dmg-chip { display:inline-flex; align-items:center; background:#fff7ed; border:1px solid #fed7aa;
                 color:#c2410c; border-radius:6px; padding:1px 7px; font-size:11px; font-weight:700; }
  .gk-card-title-row { display:flex; justify-content:space-between; align-items:center; gap:8px; margin-top:2px; }
  .gk-card-title-row .b-title { font-size:16px; font-weight:800; color:#111827; line-height:1.35; flex:1; }
  .gk-arrange-badge { font-size:11px; font-weight:600; padding:2px 8px; border-radius:99px;
                      background:#eef2f6; color:#64748b; flex-shrink:0; }
  .gk-arrange-badge.is-arranged { background:#e0f2fe; color:#0284c7; }

  .group-row + .group-row { margin-top:10px; }
  .group-title { color:#64748b; font-size:12px; font-weight:700; margin-bottom:6px; }
  .avatar-list { display:grid; gap:8px; grid-template-columns:repeat(5,minmax(0,1fr)); max-width:100%; min-width:0; }
  .avatar-list.is-btype-3 { grid-template-columns:repeat(7,minmax(0,1fr)); gap:4px; }
  .gk-role-cell { display:flex; flex-direction:column; align-items:center; text-decoration:none; color:inherit; min-width:0; }
  .avatar-list .avatar-item { border-radius:10px; display:flex; width:100%; aspect-ratio:1/1;
                              overflow:hidden; position:relative; align-items:center; justify-content:center;
                              box-shadow:0 2px 5px rgba(0,0,0,.12); }
  .avatar-list .avatar { width:100% !important; height:100% !important; max-width:none !important;
                         max-height:none !important; border-radius:0 !important; display:block;
                         object-fit:cover; }
  .gk-cell-strenth { position:absolute; left:0; top:0; width:24px !important; height:19px !important;
                     max-width:none !important; object-fit:contain; z-index:2; border-radius:0 !important; }
  .tag-box { display:flex; position:absolute; right:3px; top:3px; flex-direction:column; align-items:flex-end; gap:2px; z-index:2; }
  .tag-box .damage-type-icon-box, .tag-box .role-attr-icon-box {
      background:rgba(0,0,0,.55); border-radius:4px; display:flex; width:15px; height:15px;
      align-items:center; justify-content:center; }
  .tag-box .damage-type-icon-box img, .tag-box .role-attr-icon-box img {
      width:12px !important; height:12px !important; max-width:none !important; object-fit:contain; border-radius:0 !important; }
  .tag-box .filter { filter:brightness(2); }

  .user-lineup-card .desc { color:#334155; font-size:13px; line-height:1.6; margin-top:12px;
                            background:#fff; border-left:3px solid #3b82f6; border-radius:6px; padding:8px 10px; }

  .bottom-line { display:flex; margin-top:10px; align-items:center; justify-content:space-between; flex-wrap:wrap; gap:8px; }
  .bottom-line .left-bottom { display:flex; align-items:center; gap:6px; }
  .bottom-line .left-bottom .avatar { width:20px !important; height:20px !important; border-radius:50% !important; object-fit:cover; }
  .bottom-line .left-bottom .name { color:#475569; font-size:12px; font-weight:600; }
  .bottom-line .right-like { display:flex; gap:10px; color:#64748b; font-size:12px; }

  /* 二级展开按钮与面板容器 */
  .b-v3-detail { margin-top:12px; border:0; padding:0; }
  .b-v3-summary { list-style:none; display:flex; justify-content:space-between; align-items:center;
                  background:#eff6ff; border:1px solid #bfdbfe; color:#1d4ed8; border-radius:10px;
                  padding:9px 12px; font-size:13px; font-weight:700; cursor:pointer; user-select:none; }
  .b-v3-summary::-webkit-details-marker { display:none; }
  .b-v3-detail[open] > .b-v3-summary .gk-summary-arrow { transform:rotate(180deg); }
  .gk-summary-arrow { transition:transform .2s; font-size:14px; }
  .gk-detail-wrap { background:#fff; border-radius:12px; padding:12px 10px; margin-top:8px;
                    border:1px solid #e2e8f0; }
  .gk-collapse-bottom { margin-top:12px; padding:8px; text-align:center; background:#f1f5f9;
                        border-radius:8px; color:#475569; font-size:12px; font-weight:700; cursor:pointer; }

  /* 二级面板：清晰的层级化分段 Tab */
  .gk-team-tabs > .tl { padding:5px 14px; border-radius:8px; font-size:13px; font-weight:700; margin-right:6px; }
  .gk-team-tabs > .tp { border:0; padding:6px 0 0; margin:0; }
  .gk-mode-tabs > .tl { padding:5px 11px; border-radius:8px; font-size:12px; font-weight:600; margin:0 4px 6px 0; }
  .gk-mode-tabs > .tr:checked + .tl { background:#1e293b; color:#fff; }
  .gk-mode-tabs > .tp { border:0; padding:4px 0 0; margin:0; }

  .gk-round-tabs > .tl.gk-round-tab {
      display:inline-flex !important; flex-direction:column; align-items:flex-start;
      padding:6px 10px !important; margin:0 6px 6px 0; border-radius:8px;
      background:#f8fafc; border:1px solid #e2e8f0; color:#334155; line-height:1.25; vertical-align:top; }
  .gk-round-tabs > .tr:checked + .tl.gk-round-tab {
      background:#eff6ff; border-color:#3b82f6; color:#1d4ed8; }
  .gk-round-main { font-size:12px; font-weight:700; }
  .gk-round-meta { display:flex; flex-wrap:wrap; gap:3px; font-size:10px; opacity:.9; margin-top:2px; align-items:center; }
  .gk-round-badge { background:#f59e0b; color:#fff; border-radius:4px; padding:0 4px; font-weight:600; }
  .gk-round-tabs > .tp.gk-round-tp { border:0; padding:6px 0 0; margin:0; }

  /* 手机竖屏居中大尺寸 3x4 / 5x5 战术棋盘 */
  .view-battle-box { display:flex; flex-direction:column; gap:8px; width:100%; padding:4px 0; }
  .gk-board-head { display:flex; justify-content:space-between; align-items:center; font-size:12px; color:#64748b; }
  .gk-board-head .sp-info { color:#1e293b; font-weight:700; font-size:12px; }
  .gk-board-head .sp-num { color:#dc2626; font-size:14px; font-weight:800; }
  .view-battle-box .right-wrap { width:100%; }
  .view-battle-box .right-wrap .grid.type1 {
      display:grid; gap:8px; grid-template-columns:repeat(4,minmax(0,1fr));
      max-width:320px; margin:0 auto; }
  .view-battle-box.is-btype-3 .right-wrap .grid.type1 {
      gap:5px; grid-template-columns:repeat(5,minmax(0,1fr)); max-width:340px; }
  .view-battle-box .right-wrap .grid-item {
      background:#f1f5f9; border:1px solid #cbd5e1; border-radius:8px;
      overflow:hidden; position:relative; width:100%; aspect-ratio:5/6; }
  .view-battle-box .right-wrap .grid-role-cell-wrap {
      width:100%; height:100%; position:relative; overflow:hidden; }
  .view-battle-box .cover {
      width:100% !important; height:100% !important; max-width:none !important; max-height:none !important;
      object-fit:cover; display:block; border-radius:0 !important; }
  .view-battle-box .right-wrap .empty {
      width:100%; height:100%; position:relative;
      background:repeating-linear-gradient(-45deg,rgba(148,163,184,.08),rgba(148,163,184,.08) 6px,transparent 6px,transparent 12px); }
  .gk-slot-num { position:absolute; left:50%; top:50%; transform:translate(-50%,-50%);
                 font-size:12px; color:#94a3b8; font-weight:700; }
  .view-battle-box .right-wrap .strenth-icon { left:0; top:0; width:24px; height:19px; position:absolute; z-index:2; }
  .view-battle-box .right-wrap .strenth-icon img { width:100% !important; height:100% !important; max-width:none !important; object-fit:contain; }
  .gk-board-order { position:absolute; right:3px; top:3px; min-width:18px; height:18px; line-height:18px;
                    border-radius:5px; background:rgba(15,23,42,.85); border:1px solid rgba(255,255,255,.4);
                    color:#fff; font-size:11px; font-weight:800; text-align:center; z-index:3; }
  .view-battle-box .right-wrap .burst-icon { right:2px; top:23px; width:20px; height:20px; position:absolute; z-index:2; }
  .view-battle-box .right-wrap .burst-icon img { width:100% !important; height:100% !important; max-width:none !important; object-fit:contain; }
  .view-battle-box .right-wrap .skill-position {
      bottom:18px; right:3px; width:24px; height:24px; position:absolute; z-index:3;
      background:rgba(15,23,42,.75); border-radius:4px; padding:2px; box-sizing:border-box; }
  .view-battle-box .right-wrap .skill-position img { width:100% !important; height:100% !important; max-width:none !important; object-fit:contain; }
  .view-battle-box .right-wrap .sp-stars {
      background:linear-gradient(180deg,transparent,rgba(15,23,42,.82) 45%,#0f172a);
      bottom:0; left:0; width:100%; padding:10px 3px 3px; position:absolute;
      display:flex; flex-direction:column; align-items:center; gap:2px; box-sizing:border-box; z-index:2; }
  .gk-board-name { color:#fff; font-size:10px; font-weight:700; line-height:1.1;
                   max-width:100%; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
  .gk-stars-row { display:flex; gap:2px; justify-content:center; align-items:center; min-height:6px; }
  .view-battle-box .right-wrap .sp-stars .star {
      background:linear-gradient(180deg,#f59e0b,#fbbf24);
      clip-path:polygon(50% 0,100% 50%,50% 100%,0 50%); width:6px; height:6px; flex-shrink:0; }
  .view-battle-box .right-wrap .sp-stars .star.default-star {
      background:url(${GamekeeBattleAssets.STAR_DEFAULT}) 50%/100% 100% no-repeat;
      clip-path:none; width:7px; height:7px; }
  .view-battle-box .right-wrap .sp-stars .star.burst-star {
      background:url(${GamekeeBattleAssets.STAR_BURST}) 50%/100% 100% no-repeat;
      clip-path:none; width:7px; height:7px; }
  .view-battle-box .right-wrap .boss-action-info {
      background:#f1f5f9; border-radius:8px; display:flex; align-items:center;
      height:38px; margin-top:8px; overflow:hidden; }
  .view-battle-box .right-wrap .boss-action-info .boss-icon { width:60px !important; height:38px !important; object-fit:cover; }
  .view-battle-box .right-wrap .boss-action-info .action-content { padding:4px 10px; font-size:12px; font-weight:700; }

  /* 下方：按顺位 1->5 排列的清晰行动流卡片 */
  .gk-round-actions-title { font-size:12px; font-weight:700; color:#475569; margin:12px 0 6px; }
  .gk-action-list { list-style:none !important; padding:0 !important; margin:0 !important; display:flex; flex-direction:column; gap:6px; }
  .gk-action-card { margin:0 !important; background:#f8fafc; border:1px solid #e2e8f0; border-radius:10px; padding:8px 10px; }
  .gk-action-card .b-v2-action { display:flex; align-items:center; gap:8px; }
  .gk-act-order { width:20px; height:20px; border-radius:6px; background:#1e293b; color:#fff;
                  font-size:11px; font-weight:800; display:flex; align-items:center; justify-content:center; flex-shrink:0; }
  .gk-action-card .b-v2-avatar { width:42px !important; height:42px !important; border-radius:8px !important; object-fit:cover; flex-shrink:0; }
  .gk-act-top { display:flex; align-items:baseline; flex-wrap:wrap; gap:2px; line-height:1.3; }
  .gk-act-name { font-size:13px; color:#0f172a; text-decoration:none; }
  .gk-act-badges { display:flex; flex-wrap:wrap; align-items:center; gap:4px; margin-top:3px; }
  .gk-act-tag { font-size:11px; font-weight:700; padding:1px 6px; border-radius:4px; }
  .gk-act-tag.is-skill { background:#dbeafe; color:#1d4ed8; }
  .gk-act-tag.is-burst { background:#fef3c7; color:#b45309; }
  .gk-act-tag.is-repel { background:#dcfce7; color:#15803d; }
  .gk-act-tag.is-normal { background:#e2e8f0; color:#475569; }
  .gk-act-meta { font-size:11px; color:#64748b; }
  .gk-act-right { flex-shrink:0; margin-left:auto; }
  .gk-act-range { width:34px !important; height:34px !important; object-fit:contain;
                  background:#1e293b; padding:3px; border-radius:6px !important; display:block; }
  .gk-tippy-fold { margin-top:4px; font-size:11px; }
  .gk-tippy-fold > summary { cursor:pointer; color:#2563eb; font-weight:600; }
  .gk-tippy-desc { font-size:12px; line-height:1.5; color:#334155; background:#fff;
                   border-radius:6px; padding:6px 8px; margin-top:4px; border:1px solid #e2e8f0; }

  /* 模式 2：首列 Sticky 冻结的全回合技能矩阵 */
  .gk-matrix-table-wrap { overflow-x:auto; border:1px solid #e2e8f0; border-radius:10px; background:#fff; }
  .gk-matrix-table { display:table; width:max-content; min-width:100%; border-collapse:collapse; }
  .gk-matrix-tr { display:flex; border-bottom:1px solid #f1f5f9; }
  .gk-matrix-tr:last-child { border-bottom:0; }
  .gk-matrix-thead { background:#f8fafc; font-weight:700; }
  .gk-matrix-sticky-col { position:sticky; left:0; z-index:3; width:82px; flex-shrink:0;
                          background:#f8fafc; border-right:1px solid #e2e8f0; padding:6px;
                          display:flex; flex-direction:column; align-items:center; justify-content:center; gap:3px; }
  .gk-matrix-role-av { width:36px !important; height:36px !important; border-radius:6px !important; object-fit:cover; }
  .gk-matrix-role-name { font-size:11px; font-weight:700; color:#1e293b; max-width:74px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
  .gk-matrix-td { width:64px; flex-shrink:0; padding:4px; display:flex; flex-direction:column;
                  align-items:center; justify-content:center; border-right:1px solid #f1f5f9; box-sizing:border-box; }
  .gk-matrix-th { font-size:11px; color:#475569; padding:6px 4px; text-align:center; line-height:1.25; }
  .gk-matrix-sp { font-size:10px; color:#dc2626; font-weight:600; }
  .gk-matrix-skill-cell { width:54px; height:54px; border-radius:8px; position:relative; overflow:hidden; }
  .gk-matrix-skill-cell .cover { width:100% !important; height:100% !important; object-fit:cover; }
  .gk-matrix-skill-cell .skill { position:absolute; right:2px; top:2px; width:15px; height:15px;
                                 border-radius:50%; background:rgba(0,0,0,.6); overflow:hidden; }
  .gk-matrix-skill-cell .skill img { width:100% !important; height:100% !important; object-fit:contain; }
  .gk-matrix-cost { position:absolute; left:2px; top:2px; background:rgba(15,23,42,.8); color:#fbbf24;
                    font-size:9px; font-weight:700; padding:0 3px; border-radius:3px; line-height:1.3; }
  .gk-matrix-sname { position:absolute; bottom:0; left:0; width:100%; background:rgba(15,23,42,.78);
                     color:#fff; font-size:9px; text-align:center; line-height:1.3;
                     overflow:hidden; text-overflow:ellipsis; white-space:nowrap; padding:0 2px; box-sizing:border-box; }
  .gk-matrix-td .normal-card { width:54px; height:54px; border-radius:8px; background:#334155;
                               display:flex; flex-direction:column; align-items:center; justify-content:center; gap:2px; }
  .gk-matrix-td .normal-card .icon { width:16px !important; height:16px !important; object-fit:contain; }
  .gk-matrix-td .normal-card .text { color:#e2e8f0; font-size:10px; font-weight:600; }

  /* 模式 3：角色练度与装备资料卡 */
  .gk-panel-tabs > .tp.gk-panel-tp { border:0; padding:8px 0 0; margin:0; }
  .gk-pcard-header { display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap;
                     gap:6px; background:#f8fafc; border:1px solid #e2e8f0; border-radius:10px; padding:8px 10px; margin-bottom:8px; }
  .gk-pcard-name { font-size:15px; color:#0f172a; margin-right:6px; }
  .gk-pcard-sub { font-size:11px; color:#64748b; }
  .gk-pcard-link { font-size:11px; font-weight:700; color:#2563eb; text-decoration:none; }
  .gk-stats-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:6px; margin-bottom:10px; }
  .gk-stat-box { display:flex; justify-content:space-between; align-items:center; background:#f8fafc;
                 border:1px solid #e2e8f0; border-radius:8px; padding:6px 10px; font-size:12px; }
  .gk-stat-box.is-hl { background:#fffbeb; border-color:#fde68a; }
  .gk-stat-label { color:#64748b; }
  .gk-stat-val { font-weight:800; color:#0f172a; }
  .gk-stat-box.is-hl .gk-stat-val { color:#b45309; }
  .gk-sub-title { font-size:12px; font-weight:700; color:#475569; margin:10px 0 6px; }
  .gk-costume-strip { display:flex; flex-wrap:wrap; gap:6px; margin-bottom:10px; }
  .gk-costume-chip { display:flex; align-items:center; gap:6px; background:#f8fafc; border:1px solid #e2e8f0;
                     border-radius:8px; padding:4px 8px 4px 4px; text-decoration:none; color:inherit; }
  .gk-costume-chip img { width:34px !important; height:34px !important; border-radius:6px !important; object-fit:cover; }
  .gk-costume-meta { display:flex; flex-direction:column; line-height:1.25; }
  .gk-equip-list { display:flex; flex-direction:column; gap:6px; }
  .gk-equip-card { background:#f8fafc; border:1px solid #e2e8f0; border-radius:8px; padding:7px 10px; }
  .gk-equip-name { font-size:12px; color:#0f172a; margin-bottom:4px; }
  .gk-equip-lv { color:#d97706; font-weight:800; }
  .gk-equip-opts { display:flex; flex-wrap:wrap; gap:4px; }
  .gk-opt-chip { font-size:11px; padding:1px 6px; border-radius:4px; line-height:1.4; }
  .gk-opt-chip.is-priv { background:#fef3c7; color:#92400e; font-weight:600; }
  .gk-opt-chip.is-main { background:#e0f2fe; color:#075985; font-weight:600; }
  .gk-opt-chip.is-sub { background:#f1f5f9; color:#475569; }

  .battle { border:1px solid #d9c89a; background:#fdf9ef; border-radius:10px;
            padding:10px 12px; margin:.8em 0; }
  .battle.user-lineup-card { background:#f7f8fa; border-color:#e2e6ed; }
  .b-head { display:flex; justify-content:space-between; align-items:baseline;
            gap:8px; margin-bottom:6px; }
  .b-title { font-weight:700; color:#5c4a1e; }
  .user-lineup-card .b-title { color:#111827; }
  .b-meta { font-size:.8em; color:#9a8a5e; white-space:nowrap; }
  .b-team-t { font-size:.85em; color:#9a8a5e; margin:4px 0 2px; }
  .b-team { display:flex; flex-wrap:wrap; gap:8px; margin:.3em 0; }
  .b-m { display:flex; flex-direction:column; align-items:center; width:72px; text-decoration:none; color:inherit; }
  .b-m-link { cursor:pointer; border-radius:8px; padding:2px; }
  .b-v3-cname { font-size:11px; font-weight:700; color:#1e293b; margin-top:4px; max-width:100%; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
  .b-v3-sname { font-size:10px; color:#64748b; max-width:100%; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
  .b-v3-tag { display:inline-block; font-size:10px; font-weight:700; background:#f59e0b; color:#fff; border-radius:4px; padding:1px 5px; }
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
  /* v2 阵容：保留网格位置，原生 details 切换回合，无需脚本或全局控件 ID。 */
  .battle-v2 .b-head, .b-fallback .b-head { flex-wrap:wrap; }
  .battle-v2 .b-meta, .b-fallback .b-meta { white-space:normal; }
  .battle-v2 .b-team-t { color:#665a40; }
  .b-v2-group { font-size:1em; margin:1em 0 .4em; }
  .b-v2-round { border-top:1px solid #d9c89a; margin-top:8px; }
  .b-v2-round > summary, .b-v2-enemy > summary { cursor:pointer; padding:10px 0;
      min-height:44px; box-sizing:border-box; font-weight:600; color:#5c4a1e; }
  .b-v2-round > summary:focus-visible, .b-v2-enemy > summary:focus-visible {
      outline:2px solid #5c4a1e; outline-offset:2px; }
  .b-v2-grid { display:grid; grid-template-columns:repeat(4,minmax(44px,1fr));
      gap:4px; overflow-x:auto; margin:.4em 0; }
  .b-v2-grid-5 { grid-template-columns:repeat(5,minmax(44px,1fr)); }
  .b-v2-cell { position:relative; display:flex; flex-direction:column; align-items:center;
      justify-content:center; min-width:0; min-height:76px; padding:6px 3px;
      box-sizing:border-box; border:1px solid #e0d6b8; border-radius:6px; background:#fff; }
  .b-v2-empty { background:transparent; color:#807051; }
  .b-v2-order { position:absolute; top:2px; right:2px; min-width:16px; padding:0 2px;
      border-radius:3px; background:#5c4a1e; color:#fff; font-size:12px; line-height:18px; text-align:center; }
  .b-v2-avatar { width:32px !important; height:32px !important; flex:none;
      object-fit:cover; border-radius:6px; }
  .b-v2-no-avatar { display:flex; align-items:center; justify-content:center; color:#665a40; }
  .b-v2-name { margin-top:4px; font-size:.875em; line-height:1.4;
      text-align:center; overflow-wrap:anywhere; }
  .b-v2-actions { padding-inline-start:24px; margin:.4em 0; }
  .b-v2-actions li { margin:8px 0; }
  .b-v2-action { display:flex; gap:8px; align-items:flex-start; }
  .b-v2-action-info { min-width:0; overflow-wrap:anywhere; }
  .b-v2-note, .b-v2-skin { font-size:.875em; line-height:1.6; color:#665a40; }
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
  /* 评论里的表情贴图：网页端按 40px 显示、整条只有表情时放大到 60px，
     这里用 em 跟着正文字号走（2.5em ≈ 40px / 3.75em ≈ 60px）。
     object-fit 保住原图比例（表情包有 741×706、2376×1080 各种尺寸） */
  .c-e { width:2.5em !important; height:2.5em !important; margin:0 2px;
         vertical-align:middle; object-fit:contain; }
  .c-e-lg { width:3.75em !important; height:3.75em !important; }
  /* 评论里上传的图（网页端最多排 3 张 + 「+N」角标）。网页端三张等宽横排，
     这里没有点开大图的能力，所以按张数给宽度：一张看大点、三张才排一列 */
  .c-imgs { display:flex; flex-wrap:wrap; gap:6px; align-items:flex-start;
            margin:.35em 0 0; }
  .c-imgs img { height:auto !important; }
  .c-imgs-1 img { max-width:64% !important; }
  .c-imgs-2 img { max-width:48% !important; }
  .c-imgs-3 img { max-width:31% !important; }
  .c-imgs-more { font-size:.85em; color:#999; align-self:center; }
</style>
</head>
<body>$body$commentsHtml</body>
</html>"""
    }

    private fun formatCommentDate(epochSeconds: Long): String {
        val commentDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return commentDateFormat.format(Date(epochSeconds * 1000))
    }

    // ---------------------------------------------------------------- 评论正文 → HTML

    /** 评论表情标记，与网页端 replaceEmoji 的正则一致（惰性取到最近的右括号）。 */
    private val EMOJI_MARKER = Regex("_\\((.*?)\\)")

    /** 评论里发言上传的图片（`<div class="comment-img-wrapper">…<img src="//cdn…">`）。 */
    private val COMMENT_IMG = Regex("(?i)<img\\b[^>]*\\bsrc\\s*=\\s*[\"']([^\"']+)[\"']")

    /** 整段只有表情图时网页端会放大表情，用这个判定。 */
    private val ONLY_EMOJI_IMG = Regex("^\\s*(<img\\b[^>]*>\\s*)+$")

    /**
     * 一条评论的正文 → HTML。
     *
     * 评论 content 里混着三种东西，以前一律 esc 出来，用户看到的就是那「一块代码」：
     *   1) 文字，可能带 `<br>` 换行与 `&nbsp;` 这类实体；
     *   2) 发言时上传的图：`<div class="comment-img-wrapper"><div class="img-item">
     *      <img src="//cdnimg-v2…">…`，网页端也是单独抽出来排在文字下方（最多 3 张）；
     *   3) 表情贴图标记 `_(尤里表情包 [78])`，按 [loadEmojiMap] 查表换图。
     *
     * 顺序照网页端（deleteHtmlStr → replaceEmoji）：先只留文字（`<br>` 留作换行），
     * 实体解码后再 esc，最后在**转义过的文字上**替换表情标记 —— esc 不动
     * `_ ( ) [ ]`，标记能原样匹配，换进去的 `<img>` 也不会被二次转义；
     * 表里查不到的标记直接删干净（网页端同样是 `|| ""`），不留 `_(… […])` 残渣。
     */
    private fun commentContentHtml(raw: String, emojis: Map<String, String>): String {
        if (raw.isBlank()) return ""
        // 上传图先抽出来。src 的值取不到引号字符，塞进属性不会撑破结构；
        // 只认 https（cdnimg-v2 要 Referer，WebView 的 base 就是 gamekee，能过）
        val images = COMMENT_IMG.findAll(raw)
            .mapNotNull { absolutize(it.groupValues[1]) }
            .filter { it.startsWith("https://") }
            .toList()
        val text = decodeEntities(
            raw.replace(Regex("(?i)<br\\s*/?>"), "\n")
                .replace(Regex("(?s)<!--.*?-->"), "")
                // 只删「像标签的」：`<` 后面得跟字母才当标签。网页端是
                // /<(?!br\s*\/?)[^>]+>/（< 后面什么都吃），用户写
                // 「伤害 < 3 且 > 2」会被吃掉半句；这里收紧到字母开头，
                // 真标签（<div/<img/<span…）照样删干净
                .replace(Regex("</?[a-zA-Z][^>]*>"), "")
        )
        val escaped = esc(text)
        var body = escaped.replace(EMOJI_MARKER) { m ->
            emojis[m.value]?.let { emojiImg(it) } ?: ""
        }
        // 整条评论只有表情：网页端把 40px 放大到 60px，这里跟着放大一档。
        // body 里的 class="c-e" 只会来自上面刚生成的 <img>（用户文字里的引号
        // 已经被 esc 成 &quot;），替换是安全的
        if (ONLY_EMOJI_IMG.matches(body)) body = body.replace("class=\"c-e\"", "class=\"c-e c-e-lg\"")
        val sb = StringBuilder(body.replace("\n", "<br>"))
        if (images.isNotEmpty()) {
            val shown = images.take(COMMENT_IMG_MAX)
            sb.append("<div class=\"c-imgs c-imgs-").append(shown.size).append("\">")
            shown.forEach {
                sb.append("<img src=\"").append(esc(it)).append("\" alt=\"\" loading=\"lazy\">")
            }
            if (images.size > COMMENT_IMG_MAX) {
                sb.append("<span class=\"c-imgs-more\">+").append(images.size - COMMENT_IMG_MAX).append("</span>")
            }
            sb.append("</div>")
        }
        return sb.toString()
    }

    private fun emojiImg(url: String): String =
        "<img class=\"c-e\" src=\"" + esc(url) + "\" alt=\"\" loading=\"lazy\">"

    /**
     * 评论里的 HTML 实体。实体要**在 esc 之前**解掉：`&nbsp;` 得变回空格，
     * 否则 esc 会把 `&` 转成 `&amp;`，页面上原样显示一串 `&nbsp;` 代码。
     * 只认常见名字与数字实体，其余原样留着（宁可显示原文也不乱删字符）。
     */
    private fun decodeEntities(s: String): String = COMMENT_ENTITY.replace(s) { m ->
        when (val e = m.groupValues[1]) {
            "nbsp" -> " "
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            "amp" -> "&"
            else -> {
                val cp = when {
                    e.startsWith("#x", true) -> e.drop(2).toIntOrNull(16)
                    e.startsWith("#") -> e.drop(1).toIntOrNull()
                    else -> null
                }
                if (cp != null && cp in 1..0x10FFFF) String(Character.toChars(cp)) else m.value
            }
        }
    }

    private val COMMENT_ENTITY = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);")

    // ---------------------------------------------------------------- HTTP

    /**
     * 基础 GET。gamekee 的wiki 接口要分区身份头 + XHR 头；CDN 只要 Referer。
     * 返回 body 字符串，失败（网络/非 200/拿回的是验证 HTML 而非 JSON）返回 null。
     */
    // HTTP 层见 [com.bd2toolsbox.data.remote.GamekeeApi]（T30 拆出）。
    // 这里保留同名薄转发，调用点与拆分前逐行一致。
    private fun httpGet(url: String, referer: Boolean = false): String? = api.getJson(url, referer)

    private fun httpPostJson(url: String, jsonBody: String): String? = api.postJson(url, jsonBody)

    private fun download(url: String, dest: File, referer: Boolean = false): Boolean =
        api.download(url, dest, referer)

    // ---------------------------------------------------------------- 缓存管理

    /** guides/ 缓存占用（字节）与文件数，给攻略页的清空入口显示。 */
    fun usage(): Pair<Long, Int> = cache.usage()

    fun clearCache() {
        cache.clear()
        treeCache = null
        thumbs.clear()
        battleHtmlMemory.clear()
        emojiMapMemory = null
    }

    // HTML 转义放到最后：kotlin.text.escapeHtml 不存在，自己来，只处理会破结构的几个
    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}

// ---------------------------------------------------------------------------
// T35：kotlinx JsonElement 上的只读访问器，名字与旧 Gson 调用点保持一致，
// 让动态 Wiki JSON 的读取代码逐行不变（类型换成 kotlinx）。
// ---------------------------------------------------------------------------

private val JsonElement.isJsonObject: Boolean get() = this is JsonObject
private val JsonElement.isJsonArray: Boolean get() = this is JsonArray
private val JsonElement.isJsonPrimitive: Boolean get() = this is JsonPrimitive
private val JsonElement.asJsonObject: JsonObject get() = this as JsonObject
private val JsonElement.asJsonArray: JsonArray get() = this as JsonArray
private val JsonElement.asJsonPrimitive: JsonPrimitive get() = this as JsonPrimitive

private val JsonElement.asString: String get() = (this as JsonPrimitive).content
private val JsonElement.asLong: Long get() = (this as JsonPrimitive).content.toLong()
private val JsonElement.asInt: Int get() = (this as JsonPrimitive).content.toInt()
private val JsonElement.asBoolean: Boolean get() = (this as JsonPrimitive).content.toBoolean()

private fun JsonObject.has(key: String): Boolean = containsKey(key)
