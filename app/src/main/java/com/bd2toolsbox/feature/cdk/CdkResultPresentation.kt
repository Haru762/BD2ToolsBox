package com.bd2toolsbox.feature.cdk

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bd2toolsbox.ui.components.StatusBadge
import com.bd2toolsbox.ui.components.StatusTone

/**
 * 兑换结果标签的语义色（T45）。
 *
 * **成功只在真有成功时出现**：全终局失败是错误色，未知/待重试是警示/信息色。
 */
fun toneFor(summary: RedeemSummary): StatusTone = when (summary.resultLabel()) {
    "已兑换" -> StatusTone.SUCCESS
    "部分成功" -> StatusTone.WARNING
    "兑换失败" -> StatusTone.ERROR
    "待重试" -> StatusTone.WARNING
    "结果未知" -> StatusTone.INFO
    else -> StatusTone.NEUTRAL
}

/**
 * 账号级兑换结果行（T45）：标签 + 数量与原因。
 *
 * 复用 T41 的 [StatusBadge]；[AccountRedeemResult.describe] 负责「数量 + 原因」，
 * 全失败时文案里**不会**出现「成功」字样。
 */
@Composable
fun RedeemAccountResultRow(
    result: AccountRedeemResult,
    modifier: Modifier = Modifier
) {
    val label = result.label()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .semantics { contentDescription = "兑换结果：${result.describe()}" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusBadge(text = label, tone = toneFor(result.summary))
        Column(Modifier.padding(start = 8.dp)) {
            Text(
                result.accountName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                result.describe().substringAfter("："),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 批量结果整体说明（T45）：全失败时说明原因与下一步，不涂成成功。
 */
fun batchResultText(batch: BatchRedeemSummary): String {
    val agg = batch.aggregate
    return when {
        agg.total == 0 -> "没有可兑换的兑换码。"
        batch.canRetry && agg.successCount + agg.alreadyRedeemedCount == 0 ->
            "本次未兑换成功：${agg.pendingCount} 项可重试、${agg.unknownCount} 项结果未知、" +
                "${agg.terminalFailureCount} 项失败。可重试的项会保留在列表里。"
        agg.allRedeemed -> "全部 ${agg.total} 项已兑换（其中 ${agg.alreadyRedeemedCount} 项此前已兑换）。"
        else -> "共 ${agg.total} 项：成功 ${agg.successCount}、已兑换 ${agg.alreadyRedeemedCount}、" +
            "失败 ${agg.terminalFailureCount}、待重试 ${agg.pendingCount}、结果未知 ${agg.unknownCount}。"
    }
}
