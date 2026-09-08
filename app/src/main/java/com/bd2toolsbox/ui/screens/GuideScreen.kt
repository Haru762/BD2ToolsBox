package com.bd2toolsbox.ui.screens

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.bd2toolsbox.data.model.GuideArticle
import com.bd2toolsbox.data.model.GuideCategory
import com.bd2toolsbox.data.repository.GamekeeRepository
import com.bd2toolsbox.ui.viewmodel.GuideViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「攻略」板块 —— GameKee 棕色尘埃2分区（zsca2）的站内阅读器。
 *
 * 布局沿用「按角色」页的双栏思路：左栏是页面树（gamekee 的 wiki 是一棵由页面
 * 组成的树，叶子条目各绑定一篇文章），点目录展开/收起并把子页面列到右栏，
 * 点叶子直接打开文章；右栏默认显示「最近更新」，也可切到搜索。
 *
 * 正文不嵌 gamekee 的页面（广告/登录墙都不可控），而是拉取接口数据在仓库层
 * 渲染成 HTML，由底部的 [GuideArticleViewer] 全屏展示。WebView 里的站内
 * 文章链接会被拦回应用内继续读，外链交给系统浏览器。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideScreen(viewModel: GuideViewModel) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { viewModel.initialize(context) }

    val tree by viewModel.tree.collectAsState()
    val treeLoading by viewModel.treeLoading.collectAsState()
    val treeError by viewModel.treeError.collectAsState()
    val selectedNode by viewModel.selectedNode.collectAsState()
    val selectedName by viewModel.selectedName.collectAsState()
    val articles by viewModel.articles.collectAsState()
    val listLoading by viewModel.listLoading.collectAsState()
    val loadingMore by viewModel.loadingMore.collectAsState()
    val endReached by viewModel.endReached.collectAsState()
    val listError by viewModel.listError.collectAsState()
    val searchActive by viewModel.searchActive.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val cacheUsage by viewModel.cacheUsage.collectAsState()
    val detail by viewModel.detail.collectAsState()

    // 下拉刷新：重拉分类树（树每次进入也会自动拉新，这是即时的手动入口）
    val pullToRefreshState = rememberPullToRefreshState()
    if (pullToRefreshState.isRefreshing) {
        LaunchedEffect(true) { viewModel.refreshTree() }
    }
    LaunchedEffect(treeLoading) {
        if (!treeLoading) pullToRefreshState.endRefresh()
    }

    // 竖屏（手机）走单栏导航：分类树与文章列表各占全屏、来回切换；横屏保留
    // 双栏 —— 122dp 侧栏在横屏够用，在竖屏却会吃掉三分之一屏宽，分类名
    // 挤成省略号、正文列表也被压窄，这是「手机上看着不顺手」的根源。
    val narrow = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    // 单栏模式当前页面：false = 文章列表（默认 —— 进来就能读最近更新），
    // true = 全屏分类树
    var treePane by remember { mutableStateOf(false) }

    // 树的展开状态。跨 tab 切换要不要记住无所谓，remember 即可；
    // 默认不摊开任何一级 —— 一进来看全目录，比摊开一大串更好扫。
    val expanded = remember { mutableStateListOf<Long>() }

    // 返回键按层级回退：正文 → 分类树（竖屏）→ 退出搜索，都关了才交给系统
    BackHandler(
        enabled = detail != GuideViewModel.DetailState.Closed ||
                (narrow && treePane) || searchActive
    ) {
        when {
            detail != GuideViewModel.DetailState.Closed -> viewModel.backFromArticle()
            narrow && treePane -> treePane = false
            else -> viewModel.setSearchActive(false)
        }
    }

    // 展开后的平铺分类。remember 必须放在 LazyColumn 外面 —— LazyListScope 的
    // lambda 不是 composable 上下文，状态读取进不去。
    val flat = remember(tree.categories, expanded.size) {
        flattenTree(tree.categories, expanded.toSet())
    }

    fun onTreeNode(node: FlatNode) {
        if (node.hasChildren) {
            if (node.expanded) expanded.remove(node.category.id)
            else expanded.add(node.category.id)
        }
        viewModel.selectNode(node.category)
        if (narrow) treePane = false   // 竖屏选完分类即回列表页看内容
    }

    Box(Modifier.fillMaxSize().nestedScroll(pullToRefreshState.nestedScrollConnection)) {
    if (narrow && treePane) {
        // ------------------------------------------------ 竖屏：全屏分类树
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 14.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { treePane = false }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回列表")
                }
                Text(
                    "全部分类",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            CategoryTree(
                modifier = Modifier.weight(1f),
                flat = flat,
                selectedNode = selectedNode,
                searchActive = searchActive,
                cacheUsage = cacheUsage,
                onRecent = {
                    viewModel.selectRecent()
                    treePane = false
                },
                onNode = ::onTreeNode,
                onClearCache = { viewModel.clearGuideCache() }
            )
        }
    } else {
        Row(Modifier.fillMaxSize()) {
            // ------------------------------------------------------ 左栏：分类
            if (!narrow) {
                CategoryTree(
                    modifier = Modifier.width(122.dp),
                    flat = flat,
                    selectedNode = selectedNode,
                    searchActive = searchActive,
                    cacheUsage = cacheUsage,
                    onRecent = { viewModel.selectRecent() },
                    onNode = ::onTreeNode,
                    onClearCache = { viewModel.clearGuideCache() }
                )
                VerticalDivider(Modifier.fillMaxHeight().width(1.dp))
            }

            // ------------------------------------------------------ 右栏：列表
            Column(Modifier.weight(1f)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = if (narrow) 4.dp else 14.dp, end = 4.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (narrow) {
                        IconButton(onClick = { treePane = true }) {
                            Icon(Icons.Default.Menu, contentDescription = "选择分类")
                        }
                    }
                    Text(
                        selectedName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { viewModel.setSearchActive(!searchActive) }) {
                        Icon(
                            if (searchActive) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = if (searchActive) "退出搜索" else "搜索攻略"
                        )
                    }
                }

                AnimatedVisibility(searchActive) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { viewModel.setSearchQuery(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 4.dp),
                        placeholder = { Text("搜标题，例如：魔兽 / 潜能 / 抽卡") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { viewModel.submitSearch() }),
                        trailingIcon = {
                            IconButton(onClick = { viewModel.submitSearch() }) {
                                Icon(Icons.Default.Search, "搜索")
                            }
                        }
                    )
                }

                ArticleList(
                    articles = articles,
                    loading = listLoading,
                    loadingMore = loadingMore,
                    endReached = endReached,
                    error = listError,
                    treeLoading = treeLoading,
                    treeError = treeError,
                    searchActive = searchActive,
                    searchQuery = searchQuery,
                    narrow = narrow,
                    onLoadMore = { viewModel.loadMore() },
                    onRetryList = { viewModel.retryList() },
                    onRetryTree = { viewModel.refreshTree() },
                    onOpen = { viewModel.openArticle(it, keepCurrentInHistory = false) }
                )
            }
        }
    }
        PullToRefreshContainer(
            modifier = Modifier.align(Alignment.TopCenter),
            state = pullToRefreshState,
        )
    }


    // ---------------------------------------------------------- 阅读层
    when (val d = detail) {
        GuideViewModel.DetailState.Closed -> Unit
        else -> GuideArticleViewer(
            state = d,
            onBack = { viewModel.backFromArticle() },
            onOpenLink = { article -> viewModel.openArticle(article) }
        )
    }
}

/**
 * 分类树。横屏是左侧 122dp 窄栏；竖屏占满全屏（分类名不用再挤省略号）。
 * 结构：最近更新入口 + 树 + 底部数据来源/缓存（内容每次进入自动拉新，无刷新按钮）。
 */
@Composable
private fun CategoryTree(
    modifier: Modifier = Modifier,
    flat: List<FlatNode>,
    selectedNode: Long?,
    searchActive: Boolean,
    cacheUsage: Pair<Long, Int>,
    onRecent: () -> Unit,
    onNode: (FlatNode) -> Unit,
    onClearCache: () -> Unit
) {
    LazyColumn(
        modifier = modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        item {
            SideRow(
                label = "最近更新",
                depth = 0,
                selected = selectedNode == GuideViewModel.NODE_RECENT && !searchActive,
                hasChildren = false,
                expanded = false,
                onClick = onRecent
            )
        }
        items(flat, key = { it.category.id }) { node ->
            SideRow(
                label = node.category.name,
                depth = node.depth,
                selected = selectedNode == node.category.id && !searchActive,
                hasChildren = node.hasChildren,
                expanded = node.expanded,
                onClick = { onNode(node) }
            )
        }
        item {
            // 底部的说明与缓存入口。树每次进入自动拉新（离线退缓存），无手动刷新按钮。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp, bottom = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "数据来自 GameKee\n棕色尘埃2分区",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                if (cacheUsage.second > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "缓存 ${formatCacheSize(cacheUsage.first)} · 点按清空",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .clickable(onClick = onClearCache)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 侧栏

private data class FlatNode(
    val category: GuideCategory,
    val depth: Int,
    val hasChildren: Boolean,
    val expanded: Boolean
)

private fun flattenTree(
    categories: List<GuideCategory>,
    expanded: Set<Long>,
    depth: Int = 0
): List<FlatNode> = categories.flatMap { c ->
    val isOpen = c.id in expanded
    buildList {
        add(FlatNode(c, depth, c.children.isNotEmpty(), isOpen))
        if (isOpen && c.children.isNotEmpty()) {
            addAll(flattenTree(c.children, expanded, depth + 1))
        }
    }
}

@Composable
private fun SideRow(
    label: String,
    depth: Int,
    selected: Boolean,
    hasChildren: Boolean,
    expanded: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else Color.Transparent
            )
            .padding(start = (10 + depth * 12).dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (hasChildren) {
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

// ---------------------------------------------------------------- 文章列表

@Composable
private fun ArticleList(
    articles: List<GuideArticle>,
    loading: Boolean,
    loadingMore: Boolean,
    endReached: Boolean,
    error: String?,
    treeLoading: Boolean,
    treeError: String?,
    searchActive: Boolean,
    searchQuery: String,
    narrow: Boolean = false,
    onLoadMore: () -> Unit,
    onRetryList: () -> Unit,
    onRetryTree: () -> Unit,
    onOpen: (GuideArticle) -> Unit
) {
    when {
        // 分类树还没就绪（首次进入）——它决定侧栏，也决定列表有没有得看
        treeLoading && articles.isEmpty() -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        treeError != null && articles.isEmpty() -> {
            EmptyHint(
                text = "分类加载失败：$treeError",
                action = "重试",
                onAction = onRetryTree
            )
        }
        searchActive && searchQuery.isBlank() -> {
            EmptyHint(text = "输入关键字，按回车搜索攻略标题")
        }
        loading && articles.isEmpty() -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        error != null && articles.isEmpty() -> {
            EmptyHint(text = "加载失败：$error", action = "重试", onAction = onRetryList)
        }
        articles.isEmpty() -> {
            EmptyHint(
                text = if (searchActive) "没有搜到「$searchQuery」相关的攻略"
                       else if (narrow)
                           "这个分类下没有可直接阅读的页面\n点左上角「选择分类」看它的子分类"
                       else "这个分类下没有可直接阅读的页面\n展开左侧的子分类继续找"
            )
        }
        else -> {
            val listState = rememberLazyListState()
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(articles, key = { it.id }) { article ->
                    ArticleRow(article = article, onClick = { onOpen(article) })
                    HorizontalDivider()
                }
                item(key = "footer") {
                    when {
                        loadingMore -> Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        }
                        !endReached -> TextButton(
                            onClick = onLoadMore,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("加载更多") }
                        else -> Text(
                            "— 到底了 —",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            // 快滚到底部时自动续一页，免得每屏都要伸手点一次「加载更多」。
            // footer 项本身也算内容，所以倒数第二项出现就该续了。
            LaunchedEffect(listState, endReached) {
                snapshotFlow {
                    val info = listState.layoutInfo
                    val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                    last >= info.totalItemsCount - 2
                }.collect { nearEnd ->
                    if (nearEnd && !endReached && !loading && !loadingMore) onLoadMore()
                }
            }
            // 切换列表内容后回到顶部，否则新分类会停在上一屏滚到的位置
            LaunchedEffect(articles.firstOrNull()?.id) {
                listState.scrollToItem(0)
            }
        }
    }
}

@Composable
private fun ArticleRow(article: GuideArticle, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GuideThumb(article)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                article.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (article.summary.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    article.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatDate(article.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (article.comments > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "💬 ${article.comments}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** 列表缩略图。拿不到图（无缩略 / 离线）就用一个书页图标占位，不阻塞列表。 */
@Composable
private fun GuideThumb(article: GuideArticle) {
    val context = LocalContext.current
    val repo = remember(context) { GamekeeRepository.get(context) }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, article) {
        value = repo.loadThumb(article)
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width = 86.dp, height = 60.dp)
                .clip(RoundedCornerShape(8.dp))
        )
    } else {
        Box(
            modifier = Modifier
                .size(width = 86.dp, height = 60.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Article,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmptyHint(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (action != null && onAction != null) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onAction) { Text(action) }
            }
        }
    }
}

// ---------------------------------------------------------------- 阅读层

/**
 * 全屏文章阅读器。
 *
 * 用 WebView 而不是把 HTML 解析成 Compose：攻略里有表格、折叠块、双栏布局和
 * 大量行内样式，解析端复刻一遍工作量不可控，WebView 一次到位还自带链接处理。
 * JS 刻意关着 —— 正文是纯静态 HTML，交互组件（阵容模拟器）本来就没法本地跑。
 */
@Composable
private fun GuideArticleViewer(
    state: GuideViewModel.DetailState,
    onBack: () -> Unit,
    onOpenLink: (GuideArticle) -> Unit
) {
    val context = LocalContext.current
    val bgColor = MaterialTheme.colorScheme.background
    var progress by remember { mutableFloatStateOf(1f) }

    val html = (state as? GuideViewModel.DetailState.Ready)?.detail?.html
    val title = when (state) {
        is GuideViewModel.DetailState.Ready -> state.detail.article.title
        is GuideViewModel.DetailState.Loading -> state.article.title
        is GuideViewModel.DetailState.Failed -> state.article.title
        GuideViewModel.DetailState.Closed -> ""
    }
    val browserUrl = when (state) {
        is GuideViewModel.DetailState.Ready ->
            "https://www.gamekee.com/zsca2/${state.detail.article.id}.html"
        is GuideViewModel.DetailState.Loading ->
            "https://www.gamekee.com/zsca2/${state.article.id}.html"
        is GuideViewModel.DetailState.Failed ->
            "https://www.gamekee.com/zsca2/${state.article.id}.html"
        GuideViewModel.DetailState.Closed -> null
    }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // 顶栏：返回 │ 标题 │ 刷新 │ 浏览器。缓存正文永远离线可读，
            // 刷新按钮负责「攻略作者更新了，我要最新的」。
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (browserUrl != null) {
                    IconButton(onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(browserUrl))
                        )
                    }) {
                        Icon(Icons.Default.OpenInBrowser, contentDescription = "在浏览器打开")
                    }
                }
            }
            if (progress < 1f) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            when (state) {
                is GuideViewModel.DetailState.Ready -> {
                    // key 在正文上：换文章就换一个全新 WebView，免去「同一个实例
                    // 二次 load」的时序判断；站内跳转后的返回也不受残留滚动位置影响。
                    key(html) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    // 底色跟界面走，否则加载前的默认白块会在深色
                                    // 配色下突兀地闪一下。
                                    setBackgroundColor(bgColor.toArgb())
                                    settings.javaScriptEnabled = false
                                    webViewClient = object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(
                                            view: WebView,
                                            request: WebResourceRequest
                                        ): Boolean {
                                            val url = request.url.toString()
                                            // 站内文章引用（mention-doc）→ 拦回应用内继续读
                                            val inApp = Regex(
                                                "https?://www\\.gamekee\\.com/zsca2/(\\d+)\\.html"
                                            ).find(url)
                                            if (inApp != null) {
                                                onOpenLink(
                                                    GuideArticle(
                                                        id = inApp.groupValues[1].toLong(),
                                                        title = "", summary = "",
                                                        thumb = null, updatedAt = 0, comments = 0
                                                    )
                                                )
                                                return true
                                            }
                                            if (url.startsWith("https://")) return false
                                            // 其余 scheme 交给系统；打不开就算了，别留在应用里卡死
                                            return try {
                                                ctx.startActivity(
                                                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                                ); true
                                            } catch (_: Exception) { true }
                                        }
                                    }
                                    webChromeClient = object : android.webkit.WebChromeClient() {
                                        override fun onProgressChanged(view: WebView?, p: Int) {
                                            progress = p / 100f
                                        }
                                    }
                                }
                            },
                            update = { web ->
                                if (html != null) {
                                    // base 给成 gamekee，正文里的 //cdn... 相对协议图片才能解析
                                    web.loadDataWithBaseURL(
                                        "https://www.gamekee.com/",
                                        html, "text/html", "utf-8", null
                                    )
                                }
                            }
                        )
                    }
                }
                is GuideViewModel.DetailState.Loading -> Box(
                    Modifier.fillMaxSize(), contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
                is GuideViewModel.DetailState.Failed -> Box(
                    Modifier.fillMaxSize(), contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "正文加载失败：${state.message}",
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        Row {
                            OutlinedButton(onClick = { onOpenLink(state.article) }) { Text("重试") }
                            Spacer(Modifier.width(10.dp))
                            if (browserUrl != null) {
                                OutlinedButton(onClick = {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(browserUrl))
                                    )
                                }) { Text("网页版") }
                            }
                        }
                    }
                }
                GuideViewModel.DetailState.Closed -> Unit
            }
        }
    }
}

// ---------------------------------------------------------------- 工具

private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

private fun formatDate(epochSeconds: Long): String =
    if (epochSeconds <= 0) "" else dateFormat.format(Date(epochSeconds * 1000))

private fun formatCacheSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024)
    bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
