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

    /**
     * 是否值得重试（T45）。
     *
     * **由结果性质决定**，不看「按钮会不会误导」：只有仍可重试的失败/未处理/结果未知
     * 才允许重试；全成功或全终局失败时重试没有意义。
     */
    val canRetry: Boolean get() = pendingCount > 0 || unknownCount > 0
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

// ------------------------------------------------------------------ T45：账号维度

/** 单个账号在一批兑换码上的结果。 */
data class AccountRedeemResult(
    val accountId: String,
    val accountName: String,
    val summary: RedeemSummary
)

/**
 * 批量兑换的**账号维度**汇总（T45）。
 *
 * 汇总只用于展示统计，**不覆盖历史数据**：历史仍由 [com.bd2toolsbox.data.model.RedeemRecord]
 * 独立持有；这里算出来的东西不写回历史。
 */
data class BatchRedeemSummary(val perAccount: List<AccountRedeemResult>) {

    /** 全账号合计。 */
    val aggregate: RedeemSummary
        get() = perAccount.fold(RedeemSummary()) { acc, r ->
            RedeemSummary(
                successCount = acc.successCount + r.summary.successCount,
                alreadyRedeemedCount = acc.alreadyRedeemedCount + r.summary.alreadyRedeemedCount,
                terminalFailureCount = acc.terminalFailureCount + r.summary.terminalFailureCount,
                pendingCount = acc.pendingCount + r.summary.pendingCount,
                unknownCount = acc.unknownCount + r.summary.unknownCount
            )
        }

    /** 有可重试内容的账号（入口是否允许重试的依据）。 */
    val retryableAccounts: List<AccountRedeemResult>
        get() = perAccount.filter { it.summary.canRetry }

    val canRetry: Boolean get() = retryableAccounts.isNotEmpty()
}

/**
 * 行级/账号级标签（T45 的核心修复）。
 *
 * **绝不因为「都处理完了」就输出成功**：成功标签只在真的有成功时出现；
 * 全终局失败给失败标签，混合给「部分成功」。
 */
fun RedeemSummary.resultLabel(): String = when {
    total == 0 -> "未处理"
    // 有未知就不能声称失败也不能声称成功 —— 未知优先。
    successCount == 0 && alreadyRedeemedCount == 0 && unknownCount > 0 -> "结果未知"
    successCount == 0 && alreadyRedeemedCount == 0 && terminalFailureCount > 0 && !canRetry ->
        "兑换失败"
    successCount == 0 && alreadyRedeemedCount == 0 -> "待重试"
    allRedeemed -> "已兑换"
    else -> "部分成功"
}

/**
 * 账号级说明文案：数量 + 原因。全失败时**必须**带原因，不出现成功字样。
 */
fun AccountRedeemResult.describe(): String {
    val s = summary
    val parts = buildList {
        if (s.successCount > 0) add("成功 ${s.successCount}")
        if (s.alreadyRedeemedCount > 0) add("已兑换 ${s.alreadyRedeemedCount}")
        if (s.terminalFailureCount > 0) add("失败 ${s.terminalFailureCount}")
        if (s.pendingCount > 0) add("待重试 ${s.pendingCount}")
        if (s.unknownCount > 0) add("结果未知 ${s.unknownCount}")
    }
    return "$accountName：" + if (parts.isEmpty()) "未处理" else parts.joinToString("，")
}

/** 账号级标签：与 [describe] 一致，成功只在真有成功时出现。 */
fun AccountRedeemResult.label(): String = summary.resultLabel()
