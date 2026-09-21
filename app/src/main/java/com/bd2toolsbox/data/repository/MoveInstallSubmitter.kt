package com.bd2toolsbox.data.repository

import com.bd2toolsbox.core.model.OperationError
import com.bd2toolsbox.core.model.OperationErrorKind
import com.bd2toolsbox.core.storage.JournalTarget

/**
 * Journal-gated Download/Shared submission. Unknown partial moves retain intent;
 * only a successful move and ledger commit may close the recovery window.
 * Empty/legacy target lists retain the existing batch-uninstall contract.
 */
class MoveInstallSubmitter(private val repository: InstalledModRepository) {
    suspend fun submit(
        operationId: String,
        targets: List<JournalTarget>,
        moveFiles: suspend () -> Pair<Boolean, String>,
        commitLedger: suspend () -> OperationError?
    ): ConvertedSubmitResult {
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
            val stagedError = repository.markStaged(targets)
            if (stagedError != null) {
                return ConvertedSubmitResult(
                    filesReplaced = false,
                    error = stagedError,
                    message = "暂存阶段记录失败，未触碰游戏文件：" + stagedError.message,
                    requiresReconciliation = false
                )
            }

            val intentError = repository.markTargetReplacing(targets)
            if (intentError != null) {
                return ConvertedSubmitResult(
                    filesReplaced = false,
                    error = intentError,
                    message = "写前意图记录失败，未触碰游戏文件：" + intentError.message,
                    requiresReconciliation = false
                )
            }

            val (replaced, replaceMessage) = moveFiles()
            if (!replaced) {
                return ConvertedSubmitResult(
                    filesReplaced = false,
                    error = OperationError(
                        kind = OperationErrorKind.IO,
                        message = replaceMessage.ifBlank { "装入未完成，请先核对游戏文件" }
                    ),
                    message = replaceMessage.ifBlank { "装入未完成，请先核对游戏文件" },
                    requiresReconciliation = true
                )
            }

            val replacedError = repository.markTargetReplaced(targets)
            if (replacedError != null) {
                return ConvertedSubmitResult(
                    filesReplaced = true,
                    error = replacedError,
                    message = "游戏文件已替换，但替换标记未能保存，请先核对：" + replacedError.message,
                    requiresReconciliation = true
                )
            }
            
            val ledgerError = commitLedger()
            val finishError = repository.finishSubmit(ledgerError)
            released = true
            if (finishError != null) {
                return ConvertedSubmitResult(
                    filesReplaced = true,
                    error = finishError,
                    message = "游戏文件已替换，但记账未完成，请先核对：" + finishError.message,
                    requiresReconciliation = true
                )
            }
            return ConvertedSubmitResult(
                filesReplaced = true,
                error = null,
                message = replaceMessage,
                requiresReconciliation = false
            )
        } finally {
            // Propagate exceptions/cancellation unchanged; release only this operation.
            if (!released) repository.releaseSubmit(operationId)
        }
    }
}
