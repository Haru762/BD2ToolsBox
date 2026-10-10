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
    val lastSummary: RedeemSummary = RedeemSummary()
) {
    val busy: Boolean get() = loading || batchRedeeming || redeemingCode != null
}
