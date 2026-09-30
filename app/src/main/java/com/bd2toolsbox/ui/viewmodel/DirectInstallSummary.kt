package com.bd2toolsbox.ui.viewmodel

import com.bd2toolsbox.data.model.MoveState

/**
 * Final state of a direct converted install (files go straight into the game directory).
 *
 * The three outcomes must stay distinguishable for the user:
 * - everything committed → Success;
 * - some committed, some not → Failed, but the message says the committed ones are in effect;
 * - nothing committed → Failed.
 * Targets that need reconciliation (game file may already be replaced but bookkeeping is not
 * complete) are called out separately: they are not ordinary "retry" failures.
 * Per-target error text lives in the failed-job list, not in this headline.
 */
internal fun directInstallOutcome(committed: Int, failed: Int, needsReconciliation: Int): MoveState {
    require(committed >= 0 && failed >= 0 && needsReconciliation in 0..failed)
    if (failed == 0) return MoveState.Success("已装入 $committed 个，重启游戏后生效。")
    val reconcile = if (needsReconciliation > 0) {
        "其中 $needsReconciliation 个可能已改动游戏文件，请先核对，不要直接重试。"
    } else ""
    return if (committed > 0) {
        MoveState.Failed("已装入 $committed 个（重启游戏后生效），$failed 个未装入。$reconcile")
    } else {
        MoveState.Failed("没有装入成功，$failed 个未装入。$reconcile")
    }
}
