package com.bd2toolsbox.data.repository

import com.bd2toolsbox.core.model.OperationError
import com.bd2toolsbox.core.model.OperationErrorKind
import com.bd2toolsbox.core.storage.JournalTarget
import kotlinx.coroutines.CancellationException

/**
 * 一次「转换产物装入游戏」的提交结果（T08）。
 *
 * 刻意把**物理事实**与**记账事实**分成两条相互独立的信息：
 *
 * - [filesReplaced] —— 游戏文件到底换没换。一旦为真，用户的下一步动作（卸载/还原）
 *   就必须建立在「文件已经是 mod 版」上。
 * - [requiresReconciliation] —— 磁盘状态是否**需要人工核对**。它为真的场景比 [filesReplaced]
 *   更宽：`replaceTarget` 返回 `false`（可能已部分写入、无法清干净）、中途抛异常/被取消
 *   （写前意图已落盘、文件换没换不确定）、文件换了但账本没记，都算。
 *
 * 两者独立为真或为假：文件换了但记账失败（[filesReplaced]=true、[error]!=null）、
 * 文件没换但意图已落盘需要核对（[filesReplaced]=false、[requiresReconciliation]=true）
 * 都是真实状态，合并成一个布尔会把它们抹平。
 */
data class ConvertedSubmitResult(
    val filesReplaced: Boolean,
    val error: OperationError?,
    val message: String,
    val requiresReconciliation: Boolean
)

/**
 * 把一次「转换产物 → 游戏目录」的提交按**写前意图 → 换文件 → 记账**的顺序编排，
 * 让崩溃/取消留下的磁盘状态始终可核对（T08）。
 *
 * ## 顺序（每一步的成功是下一步的前提）
 *
 * 1. [InstalledModRepository.beginSubmit] 占位并落 `PLANNED`（同时是门禁：在途/待核对会拒绝）；
 * 2. [InstalledModRepository.markStaged] 产物就位；
 * 3. **[InstalledModRepository.markTargetReplacing] 把「即将替换」的写前意图持久化成功**，
 *    才允许真正调用 `replaceTarget`。意图没落盘就**零副作用返回**（不动文件）；
 * 4. `replaceTarget` 返回 true，才落 [InstalledModRepository.markTargetReplaced]；
 * 5. `commitLedger` 记账，再由 [InstalledModRepository.finishSubmit] 收尾；
 * 6. `finally` 里**只释放本次 operationId 所属**的所有权（token）。
 *
 * `replaceTarget` 返回 `false` 可能意味着**部分写入无法清干净** —— 保留意图、不记账、需核对。
 * 抛异常/被取消：保留意图证据、释放 token、**重抛**。
 */
class ConvertedInstallSubmitter(private val repository: InstalledModRepository) {

    suspend fun submit(
        operationId: String,
        target: JournalTarget,
        replaceTarget: suspend () -> Pair<Boolean, String>,
        commitLedger: () -> OperationError?
    ): ConvertedSubmitResult {
        val targets = listOf(target)
        val facts = target.recoveryFacts
        if (facts == null || facts.expectedContentSha256.isBlank() || facts.ledgerUpserts.isEmpty()) {
            return ConvertedSubmitResult(
                filesReplaced = false,
                error = OperationError(
                    kind = OperationErrorKind.INVALID_INPUT,
                    message = "提交失败：恢复事实不完整 (recoveryFacts 缺失或无预期数据)",
                    target = target.bundleName
                ),
                message = "提交失败：恢复事实不完整",
                requiresReconciliation = true
            )
        }

        // ① 门禁 + PLANNED。被拒（在途/待核对）→ 一个回调都不触发、文件不动。
        val gate = repository.beginSubmit(operationId, targets)
        if (gate != null) {
            return ConvertedSubmitResult(
                filesReplaced = false,
                error = gate,
                message = gate.message,
                requiresReconciliation = true
            )
        }

        var released = false
        try {
            // ② 产物就位。staged 失败 → 零 replace/ledger，文件未动。
            val stagedError = repository.markStaged(targets)
            if (stagedError != null) {
                return ConvertedSubmitResult(
                    filesReplaced = false,
                    error = stagedError,
                    message = "产物阶段未能持久化，未触碰游戏文件：" + stagedError.message,
                    requiresReconciliation = false
                )
            }

            // ③ 写前意图必须先持久化成功，才允许真正动文件。没落盘 → 零副作用返回。
            val intentError = repository.markTargetReplacing(targets)
            if (intentError != null) {
                return ConvertedSubmitResult(
                    filesReplaced = false,
                    error = intentError,
                    message = "写前意图未能持久化，未触碰游戏文件：" + intentError.message,
                    requiresReconciliation = false
                )
            }

            // ④ 真正换文件。异常/取消在此逃逸 —— 意图已落盘，证据留在磁盘上。
            val (replaced, replaceMessage) = replaceTarget()
            if (!replaced) {
                // false：可能部分写入无法清干净。保留意图（不清 journal）、不记账、需核对。
                return ConvertedSubmitResult(
                    filesReplaced = false,
                    error = OperationError(
                        kind = OperationErrorKind.IO,
                        message = replaceMessage.ifBlank { "换文件未成功" },
                        target = target.bundleName
                    ),
                    message = replaceMessage.ifBlank { "换文件未成功，磁盘状态需核对" },
                    requiresReconciliation = true
                )
            }

            // ⑤ 文件已换成功 → 落 TARGET_REPLACED，再记账。
            // 标记失败时**文件已物理写入**：filesReplaced=true + 需核对，零 ledger，保留真实 intent。
            val replacedError = repository.markTargetReplaced(targets)
            if (replacedError != null) {
                return ConvertedSubmitResult(
                    filesReplaced = true,
                    error = replacedError,
                    message = "游戏文件已替换，但替换标记未能持久化，请先核对：" + replacedError.message,
                    requiresReconciliation = true
                )
            }
            val ledgerError = commitLedger()

            // ⑥ 收尾：账本成功 → 写 LEDGER_COMMITTED + 清 journal（token 释放交给 finishSubmit）；
            // 账本失败 → 保留窗口。无论如何 finishSubmit 已清内存 token，无需 finally 再释放。
            val finishError = repository.finishSubmit(ledgerError)
            released = true
            if (finishError != null) {
                // 物理已换、记账/收尾失败：filesReplaced=true、带错、需核对，证据保留。
                return ConvertedSubmitResult(
                    filesReplaced = true,
                    error = finishError,
                    message = "游戏文件已替换，但提交尚未完整收尾，请先核对：" + finishError.message,
                    requiresReconciliation = true
                )
            }
            return ConvertedSubmitResult(
                filesReplaced = true,
                error = null,
                message = replaceMessage.ifBlank { "已装入" },
                requiresReconciliation = false
            )
        } catch (e: CancellationException) {
            // 取消也要释放 token，但**保留意图证据**（不清 journal），并重抛。
            if (!released) repository.releaseSubmit(operationId)
            throw e
        } catch (e: Throwable) {
            if (!released) repository.releaseSubmit(operationId)
            throw e
        } finally {
            // 兜底：任何未走到 finishSubmit 的正常返回路径也要释放本次 token。
            if (!released) repository.releaseSubmit(operationId)
        }
    }
}
