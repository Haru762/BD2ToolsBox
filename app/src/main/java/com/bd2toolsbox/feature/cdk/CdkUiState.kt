package com.bd2toolsbox.feature.cdk

import com.bd2toolsbox.data.model.CdkItem
import com.bd2toolsbox.data.model.RedeemRecord

/**
 * 兑换页的状态（T31）。Screen 只读它并上报事件，不再自己创建仓储、加载历史或发起批量兑换。
 */
data class CdkUiState(
    val accounts: List<String> = emptyList(),
    val items: List<CdkItem>? = null,
    val error: String? = null,
    val loading: Boolean = false,
    val batchRedeeming: Boolean = false,
    val redeemingCode: String? = null,
    /** 所有账号都已处理完的码（成功 ∪ 终局失败）。 */
    val settledCodes: Set<String> = emptySet(),
    val history: List<RedeemRecord> = emptyList(),
    val lastSummary: RedeemSummary = RedeemSummary(),
    /**
     * 上一次批量/单码提交的**账号维度**结果（T45）。
     *
     * [lastSummary] 是全账号合计；这里保留每个账号各自的数量与原因，
     * 界面按账号逐行展示，不再把「已处理完」渲染成「已兑换」。
     */
    val lastPerAccount: List<AccountRedeemResult> = emptyList()
) {
    val busy: Boolean get() = loading || batchRedeeming || redeemingCode != null

    /** 上次结果的账号维度汇总（无结果时为 null，界面据此不显示结果区）。 */
    val lastBatch: BatchRedeemSummary?
        get() = if (lastPerAccount.isEmpty()) null else BatchRedeemSummary(lastPerAccount)

    /**
     * 是否允许「重试」：由结果性质决定（还有可重试/未知项），不看按钮会不会误导。
     */
    val canRetry: Boolean get() = lastSummary.canRetry
}
