package com.bd2toolsbox.ui.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bd2toolsbox.data.model.GuideArticle
import com.bd2toolsbox.data.model.GuideCategory
import com.bd2toolsbox.data.model.GuideDetail
import com.bd2toolsbox.data.repository.GamekeeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 「攻略」板块的状态。刻意独立于 [MainViewModel]：那一个已经两千多行、全与 mod
 * 处理相关，攻略只是个在线阅读器，两边唯一的交集是没有 —— 分开互不拖累。
 *
 * 导航模型跟 gamekee 网站一致：wiki 是「一棵由页面组成的树」，叶子条目绑定
 * 一篇文章（contentId），点开即读；目录条目展开后，右栏列出它下面所有可读的
 * 子页面。右栏的另外两种内容是「最近更新」（随树一次带回）和搜索结果
 * （走 gamekee 的搜索接口，带分页）。
 */
class GuideViewModel : ViewModel() {

    companion object {
        private const val TAG = "GuideViewModel"

        /** 侧栏第一项「最近更新」的虚拟节点 id。真实分类 id 都是正数，不会撞上。 */
        const val NODE_RECENT = -1L
    }

    private lateinit var repo: GamekeeRepository

    // ---------------------------------------------------------------- 分类树

    data class TreeState(
        val categories: List<GuideCategory> = emptyList(),
        val recent: List<GuideArticle> = emptyList()
    )

    private val _tree = MutableStateFlow(TreeState())
    val tree: StateFlow<TreeState> = _tree.asStateFlow()

    private val _treeLoading = MutableStateFlow(false)
    val treeLoading: StateFlow<Boolean> = _treeLoading.asStateFlow()

    private val _treeError = MutableStateFlow<String?>(null)
    val treeError: StateFlow<String?> = _treeError.asStateFlow()

    /** 条目 id → 父条目的直接子节点列表，用于把右栏填成「同级的可读页面」。 */
    private var childrenOf: Map<Long, List<GuideCategory>> = emptyMap()

    // ---------------------------------------------------------------- 右栏列表

    /** 当前选中的侧栏节点 id；[NODE_RECENT] 表示「最近更新」。仅用于侧栏高亮。 */
    private val _selectedNode = MutableStateFlow(NODE_RECENT)
    val selectedNode: StateFlow<Long> = _selectedNode.asStateFlow()

    private val _selectedName = MutableStateFlow("最近更新")
    val selectedName: StateFlow<String> = _selectedName.asStateFlow()

    private val _articles = MutableStateFlow<List<GuideArticle>>(emptyList())
    val articles: StateFlow<List<GuideArticle>> = _articles.asStateFlow()

    private val _listLoading = MutableStateFlow(false)
    val listLoading: StateFlow<Boolean> = _listLoading.asStateFlow()

    private val _loadingMore = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _loadingMore.asStateFlow()

    private val _endReached = MutableStateFlow(true)
    val endReached: StateFlow<Boolean> = _endReached.asStateFlow()

    private val _listError = MutableStateFlow<String?>(null)
    val listError: StateFlow<String?> = _listError.asStateFlow()

    private var searchPage = 1
    private var searchKeyword = ""
    private var searching = false

    // ---------------------------------------------------------------- 搜索

    private val _searchActive = MutableStateFlow(false)
    val searchActive: StateFlow<Boolean> = _searchActive.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // ---------------------------------------------------------------- 正文阅读

    sealed interface DetailState {
        data object Closed : DetailState
        data class Loading(val article: GuideArticle) : DetailState
        data class Ready(val detail: GuideDetail, val fromCache: Boolean) : DetailState
        data class Failed(val article: GuideArticle, val message: String) : DetailState
    }

    private val _detail = MutableStateFlow<DetailState>(DetailState.Closed)
    val detail: StateFlow<DetailState> = _detail.asStateFlow()

    /** 阅读层里点站内链接跳走的文章，返回键先逐层退回这里。 */
    private val detailHistory = ArrayDeque<GuideDetail>()

    // ---------------------------------------------------------------- 缓存

    private val _cacheUsage = MutableStateFlow(0L to 0)
    val cacheUsage: StateFlow<Pair<Long, Int>> = _cacheUsage.asStateFlow()

    fun initialize(context: Context) {
        if (!::repo.isInitialized) {
            repo = GamekeeRepository.get(context)
            refreshCacheUsage()
        }
        // 每次进入攻略 tab 都拉新树（用户要求实时）—— ViewModel 挂在 Activity 上，
        // tab 切回来时 GuideScreen 重新组合、LaunchedEffect 再调一次这里
        refreshTree()
    }

    // ---------------------------------------------------------------- 树

    fun refreshTree() {
        if (_treeLoading.value) return
        _treeLoading.value = true
        _treeError.value = null
        viewModelScope.launch {
            try {
                val t = repo.loadTree()
                childrenOf = buildMap {
                    fun walk(nodes: List<GuideCategory>) {
                        for (n in nodes) {
                            getOrPut(n.id) { emptyList() }
                            put(n.id, n.children)
                            walk(n.children)
                        }
                    }
                    walk(t.categories)
                }
                _tree.value = TreeState(t.categories, t.recent)
                // 「最近更新」是默认节点：树到了就把它填上，用户一进来
                // 就有内容可看，不用再发一次请求
                if (_selectedNode.value == NODE_RECENT && !searching) {
                    _articles.value = t.recent
                    _endReached.value = true
                }
            } catch (e: Exception) {
                Log.w(TAG, "分类树加载失败", e)
                _treeError.value = e.message ?: "加载失败"
            } finally {
                _treeLoading.value = false
            }
        }
    }

    // ---------------------------------------------------------------- 右栏动作

    /** 把一组树节点转成右栏条目：绑定了文章的叶子才可读，图标用条目自带的那张。 */
    private fun List<GuideCategory>.asReadableArticles(): List<GuideArticle> =
        filter { it.contentId > 0 }
            .map {
                GuideArticle(
                    id = it.contentId, title = it.name, summary = "",
                    thumb = it.icon,
                    updatedAt = 0, comments = 0
                )
            }

    fun selectRecent() {
        if (_selectedNode.value == NODE_RECENT && !searchActive.value) return
        exitSearch()
        _selectedNode.value = NODE_RECENT
        _selectedName.value = "最近更新"
        _listError.value = null
        _articles.value = _tree.value.recent
        _endReached.value = true
    }

    /**
     * 点侧栏节点。
     * 叶子（绑定文章）→ 右栏列出同级可读页面，并直接打开文章 —— 与网页版一致；
     * 目录 → 展开交给界面处理，右栏列出它的子页面；两者都没有则给空态提示。
     */
    fun selectNode(node: GuideCategory) {
        exitSearch()
        _selectedNode.value = node.id
        _selectedName.value = node.name
        _listError.value = null
        val readable = if (node.contentId > 0) {
            childrenOf.entries.firstOrNull { node.id in it.value.map { c -> c.id } }
                ?.value.orEmpty().asReadableArticles()
                .ifEmpty { listOf(GuideArticle(node.contentId, node.name, "", null, 0, 0)) }
        } else {
            node.children.asReadableArticles()
        }
        _articles.value = readable
        _endReached.value = true

        if (node.contentId > 0) {
            openArticle(_articles.value.firstOrNull { it.id == node.contentId }
                ?: GuideArticle(node.contentId, node.name, "", null, 0, 0),
                keepCurrentInHistory = false)
        }
    }

    fun retryList() {
        if (searching) loadSearchPage(reset = true) else selectRecent()
    }

    fun loadMore() {
        if (!searching || _listLoading.value || _loadingMore.value || _endReached.value) return
        loadSearchPage(reset = false)
    }

    private fun loadSearchPage(reset: Boolean) {
        val pageToLoad = if (reset) 1 else searchPage
        if (reset) {
            _listLoading.value = true
            _listError.value = null
        } else {
            _loadingMore.value = true
        }
        viewModelScope.launch {
            try {
                val page = repo.searchArticles(searchKeyword, pageToLoad)
                _articles.value = if (reset) page.articles else _articles.value + page.articles
                _endReached.value = !page.hasMore
                searchPage = pageToLoad + 1
            } catch (e: Exception) {
                Log.w(TAG, "搜索失败", e)
                if (reset) _articles.value = emptyList()
                _listError.value = e.message ?: "加载失败"
            } finally {
                _listLoading.value = false
                _loadingMore.value = false
            }
        }
    }

    // ---------------------------------------------------------------- 搜索

    fun setSearchActive(active: Boolean) {
        _searchActive.value = active
        if (!active) exitSearch()
    }

    fun setSearchQuery(q: String) {
        _searchQuery.value = q
    }

    /** 提交搜索。空关键字不动作。 */
    fun submitSearch() {
        val q = _searchQuery.value.trim()
        if (q.isEmpty()) return
        searching = true
        searchKeyword = q
        searchPage = 1
        _selectedName.value = "搜索：$q"
        loadSearchPage(reset = true)
    }

    /** 退出搜索态并回到进入搜索前的节点内容。 */
    private fun exitSearch() {
        _searchActive.value = false
        _searchQuery.value = ""
        if (!searching) return
        searching = false
        if (_selectedNode.value == NODE_RECENT) {
            _articles.value = _tree.value.recent
            _selectedName.value = "最近更新"
            _endReached.value = true
        } else {
            // 找回当前节点，按目录/叶子重新填充（纯本地，无网络）
            _tree.value.categories.firstOrNull { it.id == _selectedNode.value }?.let {
                selectNode(it)
            } ?: selectRecent()
        }
    }

    // ---------------------------------------------------------------- 正文阅读

    fun openArticle(article: GuideArticle, keepCurrentInHistory: Boolean = true) {
        val current = _detail.value
        if (keepCurrentInHistory && current is DetailState.Ready) {
            detailHistory.addLast(current.detail)
        }
        _detail.value = DetailState.Loading(article)
        viewModelScope.launch {
            try {
                _detail.value = DetailState.Ready(repo.loadArticleDetail(article), fromCache = false)
            } catch (e: Exception) {
                Log.w(TAG, "正文加载失败: ${article.id}", e)
                _detail.value = DetailState.Failed(article, e.message ?: "加载失败")
            }
        }
    }

    /**
     * 阅读层返回。先退站内跳转的历史，退无可退才收起整个阅读层。
     * 返回 true 表示消费了这次返回（还有东西可退）。
     */
    fun backFromArticle(): Boolean {
        val prev = detailHistory.removeLastOrNull()
        if (prev != null) {
            _detail.value = DetailState.Ready(prev, fromCache = true)
            return true
        }
        val closed = _detail.value != DetailState.Closed
        _detail.value = DetailState.Closed
        return closed
    }

    fun dismissArticle() {
        detailHistory.clear()
        _detail.value = DetailState.Closed
    }

    // ---------------------------------------------------------------- 缓存

    fun refreshCacheUsage() {
        if (!::repo.isInitialized) return
        viewModelScope.launch {
            _cacheUsage.value = repo.usage()
        }
    }

    fun clearGuideCache() {
        if (!::repo.isInitialized) return
        viewModelScope.launch {
            repo.clearCache()
            _cacheUsage.value = 0L to 0
        }
    }
}
