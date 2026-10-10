package com.bd2toolsbox.feature.cdk

import com.bd2toolsbox.data.model.RedeemOutcome

/**
 * 一条兑换码的处理结局（T31）。
 *
 * 刻意把「成功」「服务器已兑换」「终局失败」「可重试失败」「未处理」「未知」分开：
 * 「全部处理完」不等于「全部成功」，网络超时/取消/解析失败也不能冒充成功或确定终局失败。
 */
enum class RedeemDisposition {
    SUCCESS,
    ALREADY_REDEEMED,
    TERMINAL_FAILURE,
    RETRYABLE_FAILURE,
    NOT_ATTEMPTED,
    UNKNOWN
}

/**
 * 把一次官方响应映射成处置结局。
 *
 * - `success` → [RedeemDisposition.SUCCESS]；
 * - `AlreadyUsed` → [RedeemDisposition.ALREADY_REDEEMED]（服务器已兑换，不是失败）；
 * - `UnknownResult`（响应到了但解析不了）→ [RedeemDisposition.UNKNOWN]；
 * - 非可重试的确定性错误 → [RedeemDisposition.TERMINAL_FAILURE]；
 * - 其余 → [RedeemDisposition.RETRYABLE_FAILURE]。
 */
fun dispositionOf(outcome: RedeemOutcome): RedeemDisposition = when {
    outcome.success -> RedeemDisposition.SUCCESS
    outcome.errorCode == "AlreadyUsed" -> RedeemDisposition.ALREADY_REDEEMED
    outcome.errorCode == "UnknownResult" -> RedeemDisposition.UNKNOWN
    !outcome.retryable -> RedeemDisposition.TERMINAL_FAILURE
    else -> RedeemDisposition.RETRYABLE_FAILURE
}

/**
 * 一批兑换码的汇总（T31）。
 *
 * [settled] 与 [allRedeemed] 都要求 `total > 0`：**空集合不能显示「全部兑换成功」**。
 * [settled] 允许「全是终局失败」（已处理完，但 [successCount] 必须为 0）。
 */
data class RedeemSummary(
    val successCount: Int = 0,
    val alreadyRedeemedCount: Int = 0,
    val terminalFailureCount: Int = 0,
    /** 未处理 + 可重试失败。 */
    val pendingCount: Int = 0,
    val unknownCount: Int = 0
) {
    val total: Int
        get() = successCount + alreadyRedeemedCount + terminalFailureCount + pendingCount + unknownCount

    /** 所有条目都已落定：没有待处理、没有未知。空集合不算落定。 */
    val settled: Boolean get() = total > 0 && pendingCount == 0 && unknownCount == 0

    /** 全部已兑换（成功或服务器已兑换）。空集合**不**算。 */
    val allRedeemed: Boolean
        get() = total > 0 && successCount + alreadyRedeemedCount == total
}

/** 按处置结局汇总。 */
fun summarizeRedemption(dispositions: Collection<RedeemDisposition>): RedeemSummary {
    var success = 0
    var already = 0
    var terminal = 0
    var pending = 0
    var unknown = 0
    for (d in dispositions) {
        when (d) {
            RedeemDisposition.SUCCESS -> success++
            RedeemDisposition.ALREADY_REDEEMED -> already++
            RedeemDisposition.TERMINAL_FAILURE -> terminal++
            RedeemDisposition.RETRYABLE_FAILURE, RedeemDisposition.NOT_ATTEMPTED -> pending++
            RedeemDisposition.UNKNOWN -> unknown++
        }
    }
    return RedeemSummary(success, already, terminal, pending, unknown)
}

/** 便捷：直接从官方响应集合汇总。 */
fun summarizeOutcomes(outcomes: Collection<RedeemOutcome>): RedeemSummary =
    summarizeRedemption(outcomes.map { dispositionOf(it) })
