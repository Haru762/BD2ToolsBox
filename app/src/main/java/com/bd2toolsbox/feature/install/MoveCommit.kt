package com.bd2toolsbox.feature.install

import com.bd2toolsbox.core.storage.JournalTarget
import com.bd2toolsbox.data.repository.ConvertedSubmitResult

/**
 * 一次「转换产物 → 游戏目录」提交的**冻结输入**（T22）。
 *
 * 全部是值类型、不含 UI 状态、不含调用方闭包 —— 这样它才能被交给另一个宿主执行，
 * 而结果与「谁在跑」无关。
 */
data class MoveCommitRequest(
    val id: String,
    val sharedPath: String,
    val stagingPath: String,
    val targets: List<JournalTarget>,
    val removals: Set<String>
)

/** 提交的真实结果。`submission == null` 时 [failure] 必须非空。 */
data class MoveCommitResult(
    val id: String,
    val submission: ConvertedSubmitResult?,
    val ledgerNotices: List<LedgerNotice>,
    val failure: String?
)

/**
 * 提交执行的**宿主**（T22）。
 *
 * 为什么要有这层接口：提交跑在 `viewModelScope` 上时，用户退出界面会触发 `onCleared`
 * 取消协程，在游戏目录留下半个 `__data`。所以生产实现把执行交给 `InstallService`
 * （自己的 scope + 前台服务，界面销毁不影响它）。
 *
 * 而 JVM 用例需要在没有 Android 服务的前提下验证**提交语义本身**（冻结事实、备份门禁、
 * 账本、暂存清理）—— 它们注入 [DirectMoveCommitHost]。两者的差别只在「谁跑」，
 * 提交语义是同一份代码。
 */
interface MoveCommitHost {
    /**
     * 执行一次提交。
     *
     * 返回 `null` 表示宿主**没能开始跑**（例如系统拒绝启动前台服务）—— 调用方必须据此
     * 停下并告诉用户，不能照常当成功。返回值非 null 时一定带真实结果：
     * 要么 `submission` 非空，要么 `failure` 非空。
     */
    suspend fun execute(request: MoveCommitRequest): MoveCommitResult?
}

/**
 * 直接在调用方协程里执行（JVM 用例与任何「没有 Android 服务」的场合）。
 *
 * 生产**不用**它：它没有前台服务、也活在调用方的 scope 上，正是要修的那个问题。
 */
class DirectMoveCommitHost(
    private val executor: InstallExecutor
) : MoveCommitHost {
    override suspend fun execute(request: MoveCommitRequest): MoveCommitResult {
        return try {
            val executed = executor.move(
                id = com.bd2toolsbox.core.model.OperationId(request.id),
                shared = java.io.File(request.sharedPath),
                stagingRoot = java.io.File(request.stagingPath),
                targets = request.targets,
                restoredTargets = request.removals
            )
            MoveCommitResult(request.id, executed.submission, executed.ledgerNotices, null)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // 取消不是失败：如实向上传，别把它变成一条「装入失败」的用户提示
            throw cancelled
        } catch (e: Exception) {
            // [MoveCommitHost.execute] 的契约是「要么 submission 非空，要么 failure 非空」。
            // `InstallExecutor.move` 对**提交前**的校验失败（源文件已改变、目标路径无效、
            // 没有可提交目标等）是抛 `IOException` 的，不接就会让本实现直接违约 ——
            // `InstallService` 那边一直是接住的，这里此前没有。T56 端到端验收发现。
            //
            // 这里**不写日志**：feature 层不许依赖 `android.util.Log`（架构规则 R4）。
            // 失败原因已经进 [MoveCommitResult.failure]，生产路径上的 `InstallService`
            // 也会自己记一笔，不会丢。
            MoveCommitResult(request.id, null, emptyList(), e.message ?: e.javaClass.simpleName)
        }
    }
}
