package com.bd2toolsbox.core.operations

import com.bd2toolsbox.core.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 接纳任务、串行化同目标写入并发布真实结果。宿主在自己的 scope 中调用 [execute]；
 * 本类不 launch、不缓存执行闭包，页面观察者的消失不会结束宿主的工作。
 *
 * [OperationRequest.targets] 必须使用跨操作一致的资源标识（例如 bundle 名），不能用
 * MOD URI 代替共享 bundle。清缓存暂用全局排他，避免「当前缓存列表」漏掉正在生成的槽。
 * 持久提交协议仍由 executor 在 [Execution.commit] 内执行，内存状态不代替磁盘 journal。
 */
@Singleton
class OperationCoordinator @Inject constructor(private val store: OperationStateStore) {
    fun submit(request: OperationRequest): OperationAdmission = synchronized(store.monitor) {
        val duplicate = store.states.value[request.id]
        val conflict = store.requests.values.firstOrNull {
            it.kind == OperationKind.CLEAR_CACHE || request.kind == OperationKind.CLEAR_CACHE ||
                it.targets.any { target -> target in request.targets }
        }
        val conflictingId = duplicate?.id ?: conflict?.id
        if (conflictingId != null) {
            return@synchronized OperationAdmission.Rejected(
                conflictingId,
                OperationError(OperationErrorKind.BUSY, "任务标识或目标正被另一任务占用，请等待任务收尾")
            )
        }
        val snapshot = request.copy(targets = Collections.unmodifiableList(ArrayList(request.targets)))
        store.requests[request.id] = snapshot
        store.publish(OperationState(request.id, request.kind, OperationStage.QUEUED, totalTargets = snapshot.targets.size))
        OperationAdmission.Accepted(request.id)
    }

    /** null 表示不存在可领取的参数或已被另一宿主领取；绝不凭空补一个成功结果。 */
    suspend fun execute(id: OperationId, work: suspend Execution.() -> Unit): OperationState? {
        val request = synchronized(store.monitor) {
            val state = store.states.value[id] ?: return null
            if (state.stage != OperationStage.QUEUED) return null
            val request = store.requests[id] ?: return null
            store.publish(state.copy(stage = OperationStage.PREPARING))
            request
        }
        var error: OperationError? = null
        var cancelled: CancellationException? = null
        lateinit var result: OperationState
        try {
            // 等执行器创建的子任务真正收尾后再释放占用；引擎观察这一层的取消，
            // 既包含宿主取消，也包含执行器子任务失败导致的取消。
            coroutineScope {
                val execution = Execution(request, currentCoroutineContext()[Job])
                execution.checkpoint()
                execution.phase(OperationStage.RUNNING)
                execution.work()
                execution.checkpoint()
            }
        } catch (e: CancellationException) {
            cancelled = e
        } catch (e: Throwable) {
            error = OperationError(
                kind = when (e) {
                    is IOException -> OperationErrorKind.IO
                    is SecurityException -> OperationErrorKind.PERMISSION_DENIED
                    else -> OperationErrorKind.UNKNOWN
                },
                message = e.message?.takeIf { it.isNotBlank() } ?: "任务执行失败",
                detail = e.javaClass.simpleName
            )
            if (e !is Exception) throw e
        } finally {
            synchronized(store.monitor) {
                val state = store.states.value.getValue(id)
                val wasCancelled = cancelled != null || state.cancellationRequested
                val incomplete = state.completedTargets != state.totalTargets
                val finalError = error ?: if (!wasCancelled && incomplete) {
                    OperationError(OperationErrorKind.UNKNOWN, "任务结束但仍有目标未报告结果，请核对后重试")
                } else null
                val stage = when {
                    wasCancelled -> if (state.cancellationRequested) OperationStage.CANCELLED else OperationStage.INTERRUPTED
                    finalError != null || state.failedTargets.isNotEmpty() ->
                        if (state.committedTargets.isNotEmpty()) OperationStage.PARTIALLY_FAILED else OperationStage.FAILED
                    else -> OperationStage.COMPLETED
                }
                result = state.copy(stage = stage, error = finalError)
                store.requests.remove(id)
                store.publish(result)
            }
        }
        // 宿主取消遵守协程传播规则；用户取消请求则作为任务的正常终态返回给 Service。
        if (cancelled != null && !currentCoroutineContext().isActive) throw cancelled
        return result
    }

    /** 不取消宿主 Job：阻塞的 Python 调用通过 Execution.cancellationRequested 在安全点退出。 */
    fun requestCancellation(id: OperationId): Boolean = synchronized(store.monitor) {
        val state = store.states.value[id] ?: return@synchronized false
        if (state.isTerminal || !state.kind.cancellable || state.cancellationRequested) return@synchronized false
        if (state.stage == OperationStage.QUEUED) {
            store.requests.remove(id)
            store.publish(state.copy(stage = OperationStage.CANCELLED, cancellationRequested = true))
        } else {
            store.publish(state.copy(cancellationRequested = true))
        }
        true
    }

    /** 前台服务启动被拒时，释放尚未领取的任务；迟到的拒绝不能终止已在运行的宿主。 */
    fun failToStart(id: OperationId, error: OperationError): Boolean = synchronized(store.monitor) {
        val state = store.states.value[id] ?: return@synchronized false
        if (state.stage != OperationStage.QUEUED) return@synchronized false
        store.requests.remove(id)
        store.publish(state.copy(stage = OperationStage.FAILED, error = error))
        true
    }

    /** 用户确认终态后释放结果；进行中的参数与占用不能被 UI 清掉。 */
    fun acknowledge(id: OperationId): Boolean = synchronized(store.monitor) {
        if (store.states.value[id]?.isTerminal != true) return@synchronized false
        store.remove(id)
        true
    }

    inner class Execution internal constructor(val request: OperationRequest, private val hostJob: Job?) {
        /** 给阻塞引擎的安全点回调；原始取消请求仍在 OperationState 中供 UI 显示。 */
        val cancellationRequested: Boolean
            get() = synchronized(store.monitor) {
                val state = activeState()
                state.stage != OperationStage.COMMITTING &&
                    (hostJob?.isActive == false || state.cancellationRequested)
            }

        fun checkpoint() = synchronized(store.monitor) {
            val state = activeState()
            if (state.stage != OperationStage.COMMITTING) {
                hostJob?.ensureActive()
                if (state.cancellationRequested) throw CancellationException("用户请求取消任务")
            }
        }

        fun phase(stage: OperationStage, message: String = "") = synchronized(store.monitor) {
            val state = activeState()
            check(state.stage != OperationStage.COMMITTING) { "提交临界区不能切换阶段" }
            require(stage == OperationStage.RUNNING ||
                (stage in request.kind.stages && stage in listOf(OperationStage.ENUMERATING, OperationStage.PARSING))) {
                "执行器不能直接设置阶段 $stage"
            }
            store.publish(state.copy(stage = stage, progressMessage = message))
        }

        /**
         * 校验目标和取消请求后，保护「文件替换 + journal/账本落盘」整段。
         * action 失败不登记成功；父 Job 取消也必须先保全已提交事实再释放资源。
         */
        suspend fun commit(target: String, action: suspend () -> Unit) {
            checkpoint()
            withContext(NonCancellable) {
                val previousStage = synchronized(store.monitor) {
                    checkpoint()
                    val state = unreportedTarget(target)
                    check(state.stage != OperationStage.COMMITTING) { "同一任务不能同时提交两个目标" }
                    store.publish(state.copy(stage = OperationStage.COMMITTING))
                    state.stage
                }
                try {
                    action()
                    synchronized(store.monitor) {
                        val state = activeState()
                        store.publish(state.copy(committedTargets = state.committedTargets + target))
                    }
                } finally {
                    synchronized(store.monitor) {
                        store.publish(activeState().copy(stage = previousStage))
                    }
                }
            }
        }

        fun fail(target: String, error: OperationError) = record(TargetOutcome(target, TargetStatus.FAILED, error))
        fun skip(target: String) = record(TargetOutcome(target, TargetStatus.SKIPPED))

        private fun record(outcome: TargetOutcome) = synchronized(store.monitor) {
            val state = unreportedTarget(outcome.target)
            check(state.stage != OperationStage.COMMITTING) { "提交临界区内不能另报目标结果" }
            store.publish(when (outcome.status) {
                TargetStatus.FAILED -> state.copy(failedTargets = state.failedTargets + outcome)
                TargetStatus.SKIPPED -> state.copy(skippedTargets = state.skippedTargets + outcome.target)
                TargetStatus.COMMITTED -> error("成功目标只能由 commit 登记")
            })
        }

        private fun unreportedTarget(target: String): OperationState {
            val state = activeState()
            require(target in request.targets) { "目标不属于本任务：$target" }
            check(target !in state.committedTargets && target !in state.skippedTargets &&
                state.failedTargets.none { it.target == target }) { "目标已经报告结果：$target" }
            return state
        }

        private fun activeState(): OperationState {
            check(store.requests[request.id] === request) { "执行句柄不属于本次运行" }
            val state = store.states.value.getValue(request.id)
            check(!state.isTerminal && state.stage != OperationStage.QUEUED) { "任务不在执行中" }
            return state
        }
    }
}
