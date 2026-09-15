package com.bd2toolsbox.core.fileaccess

import com.bd2toolsbox.core.model.OperationError
import com.bd2toolsbox.core.model.OperationErrorKind

/**
 * 文件访问的领域契约（T05）。
 *
 * 本文件解决的是一个**已经实测发生过**的错误：目录访问把失败与空折叠成同一形态。
 *
 * | 位置 | 旧写法 | 失败时的表现 |
 * |---|---|---|
 * | `ShizukuFileService.listBundleDirectoryPaged` | 目录不存在 → `"[]"`；`listFiles()` 返 null → 静默跳过；抛异常 → `"[]"` | 三种情况都返回 `"[]"` |
 * | `ShizukuManager.listGameBundles` / `checkLocalBundles` | `if (JSONArray(pageJson).length() < pageSize) break` | 中途一页失败返回 `"[]"` 被当成**最后一页**，提交一份看似完整、实则截断的索引 |
 *
 * 契约：
 *   1. 平台 adapter **只允许**产生 [DirectoryPage.Content] 或 [DirectoryPage.Failed]；
 *      不得把「权限被拒 / binder 断连 / 解析失败」编码成空页或 `false`。
 *   2. 列举结果是 [DirectoryListingResult.Success] 或 [DirectoryListingResult.Failure]，
 *      **类型不同**。`Success(emptyList())` 只表示「确实读到了，且目录里没有东西」。
 *   3. 失败**不是**「最后一页」：一页失败就让整次列举失败（见 [PagedDirectoryLister]）。
 *
 * 本文件不得 import `android.*` —— 有测试直接扫字段类型来守这条。
 */

/**
 * 目录列举里的一项。只带领域值，不带 `android.net.Uri`、`File` 或 `org.json` 对象。
 */
data class DirectoryEntry(
    /** 条目名（bundle 名 / 文件名）。 */
    val name: String,
    /** 附加标识（bundle 列举里的 hash 目录名）。来源目录条目为 null。 */
    val key: String? = null,
    /** 字节数；未知沿用既有协议的哨兵值 -1。 */
    val sizeBytes: Long = -1L
) {
    init {
        require(name.isNotBlank()) { "DirectoryEntry.name 不能为空" }
    }
}

/**
 * 平台 adapter 读取**一页**的结果。
 *
 * 要害在这里：adapter 不得把「读失败了」编码成「读到了空的页」。旧实现三种失败
 * 都返回字符串 `"[]"`，调用方把它当成「最后一页」，于是提交了一份截断的索引却
 * 看起来完整 —— 用户看到的是「mod 没了」，而不是「这次没读到」。
 */
sealed class DirectoryPage {
    /** 本页读取成功。[entries] 可以为空 —— 那只表示「这一页确实没有条目」。 */
    data class Content(val entries: List<DirectoryEntry>) : DirectoryPage()

    /** 本页读取失败。整次列举到此为止，**绝不放宽为「最后一页」**。 */
    data class Failed(val error: OperationError) : DirectoryPage()
}

/**
 * 目录列举的结果。
 *
 * [Success] 与 [Failure] 是不同类型：拿到 [Failure] 的调用方**没有**任何入口能从中
 * 取出一个列表，因此不可能把「读失败」提交成空索引。这是刻意的 API 设计，
 * 而不是靠调用方自觉判断。
 */
sealed class DirectoryListingResult {
    /** 读取成功。[entries] 为空 = 确实读到了且目录为空。 */
    data class Success(val entries: List<DirectoryEntry>) : DirectoryListingResult() {
        val isEmpty: Boolean get() = entries.isEmpty()
    }

    /** 读取失败。没有任何列表可取。 */
    data class Failure(val error: OperationError) : DirectoryListingResult()
}

/**
 * 分页读取的来源（平台 adapter 实现）。
 *
 * [read] 必须返回 [DirectoryPage.Content] 或 [DirectoryPage.Failed]；
 * 读不出来时必须返回 `Failed`，不能返回空 `Content` 来收场。
 */
fun interface DirectoryPageSource {
    /** 读取 [offset] 起的下一页；[limit] 是页大小。 */
    fun read(offset: Int, limit: Int): DirectoryPage
}

/**
 * 分页列举器：把一页一页的原始结果装配成 [DirectoryListingResult]。
 *
 * 终止条件是**明确的**：只有 adapter 交回「成功且不足一页」才算读完。
 * 任何一页失败 → 整次失败（[DirectoryListingResult.Failure]），已读到的页一条都不提交。
 * 这正是旧分页循环 `length() < pageSize` 缺掉的那半条契约。
 */
class PagedDirectoryLister(private val pageSize: Int = DEFAULT_PAGE_SIZE) {

    init {
        require(pageSize > 0) { "pageSize 必须为正：$pageSize" }
    }

    fun list(source: DirectoryPageSource): DirectoryListingResult {
        val all = ArrayList<DirectoryEntry>()
        var offset = 0
        while (true) {
            when (val page = source.read(offset, pageSize)) {
                is DirectoryPage.Failed -> {
                    // 失败的一页不是最后一页：不返回截断的列表，也不静默补一个空页。
                    return DirectoryListingResult.Failure(page.error)
                }
                is DirectoryPage.Content -> {
                    all.addAll(page.entries)
                    if (page.entries.size < pageSize) {
                        return DirectoryListingResult.Success(all)
                    }
                    offset += page.entries.size
                }
            }
        }
    }

    companion object {
        /** 与既有协议一致的 500：单页显著小于 binder 1MB 事务上限。 */
        const val DEFAULT_PAGE_SIZE = 500
    }
}

/**
 * 平台 adapter 观察到的失败信号。
 *
 * adapter（Android 侧）只做这一层翻译：把 `SecurityException` / `DeadObjectException` /
 * `RemoteException` / `JSONException` / `IOException` 归结成这里的信号，**不允许**自行决定
 * 「变成空列表」还是「变成 false」。信号到 [OperationErrorKind] 的映射是固定的
 * （[toOperationErrorKind]），UI 与用例只认目标类别。
 */
enum class FileAccessFailureSignal {
    /** SAF 授权被撤 / shell 权限不足。 */
    PERMISSION_DENIED,

    /** Shizuku 未运行、binder 断连、服务未绑定。 */
    SERVICE_DISCONNECTED,

    /** 目录或条目不存在。 */
    PATH_MISSING,

    /** 拿到的载荷无法解析（分页 JSON 被截断等）。 */
    PAYLOAD_MALFORMED,

    /** 单文件/单次的 I/O 失败。 */
    IO_FAILURE,

    /** 归类不出来。 */
    UNKNOWN;

    /** 固定的信号 → 错误类别映射。 */
    fun toOperationErrorKind(): OperationErrorKind = when (this) {
        PERMISSION_DENIED -> OperationErrorKind.PERMISSION_DENIED
        SERVICE_DISCONNECTED -> OperationErrorKind.NOT_CONNECTED
        PATH_MISSING -> OperationErrorKind.NOT_FOUND
        PAYLOAD_MALFORMED -> OperationErrorKind.MALFORMED_DATA
        IO_FAILURE -> OperationErrorKind.IO
        UNKNOWN -> OperationErrorKind.UNKNOWN
    }
}

/** 把 adapter 的失败信号变成领域错误（可重试性由 [OperationErrorKind] 决定）。 */
fun FileAccessFailureSignal.toError(
    message: String,
    detail: String? = null,
    target: String? = null
): OperationError = OperationError(
    kind = toOperationErrorKind(),
    message = message,
    detail = detail,
    target = target
)
