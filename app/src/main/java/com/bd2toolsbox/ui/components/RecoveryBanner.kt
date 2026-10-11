package com.bd2toolsbox.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bd2toolsbox.core.operations.RecoveryDisposition

/**
 * 启动恢复处置的**用户可见结论**（T25）。
 *
 * 把 [RecoveryDisposition] 的四态翻成人话，并且**不把四态压成两态**：
 * 「中断」「待核对」「结果未知」各自说清下一步，`Committed`（事实完整、只是 journal
 * 没清）不进横幅 —— 已经闭合的窗口不该再打扰用户。
 *
 * 与账本提示（`notifyLedgerNotice`）分工：那边是「这次启动发现的事实」，这边是
 * 「按操作细分的处置与下一步」；两者都出现时用户会看到两条，这是有意的。
 */
object RecoveryPresentation {

    /** 需要在界面上说一声的那些处置（Committed 与空列表都不打扰用户）。 */
    fun actionable(dispositions: List<RecoveryDisposition>): List<RecoveryDisposition> =
        dispositions.filterNot { it is RecoveryDisposition.Committed }

    /** 是否需要给用户看。 */
    fun shouldShow(dispositions: List<RecoveryDisposition>): Boolean =
        actionable(dispositions).isNotEmpty()

    /** 横幅标题：按最严重的形态定调，不笼统说「出错了」。 */
    fun title(dispositions: List<RecoveryDisposition>): String {
        val actionable = actionable(dispositions)
        return when {
            actionable.any { it is RecoveryDisposition.NeedsReconciliation } -> "有装入没核对完"
            actionable.any { it is RecoveryDisposition.Interrupted } -> "上次操作被打断"
            actionable.any { it is RecoveryDisposition.Unknown } -> "装入记录读不出来"
            else -> ""
        }
    }

    /** 正文：逐条给出「是什么 + 下一步」。 */
    fun message(dispositions: List<RecoveryDisposition>): String {
        val lines = actionable(dispositions).map { d ->
            when (d) {
                is RecoveryDisposition.Interrupted ->
                    "· 被打断的操作 ${d.operationId}：涉及 ${d.bundleNames.size} 个资源包，" +
                        "游戏文件可能没换、也可能换了一半。请先确认游戏能正常启动，再用" +
                        "「核对并修复记录」按目标内容处理。"
                is RecoveryDisposition.NeedsReconciliation ->
                    "· 待核对的操作 ${d.operationId}：游戏文件已换成新的，但装入记录没来得及更新" +
                        "（${d.bundleNames.size} 个资源包）。请用「核对并修复记录」补账，" +
                        "**不要重新安装**。"
                is RecoveryDisposition.Unknown ->
                    "· 有一条装入记录读不出来（${d.message}）。已保留原文件，未做任何自动动作；" +
                        "请检查存储权限后重试。"
                is RecoveryDisposition.Committed -> ""
            }
        }.filter { it.isNotEmpty() }
        return lines.joinToString("\n")
    }
}

/**
 * 启动恢复横幅（T25）。
 *
 * 用 [StatusTone.WARNING] 而不是错误色：这些都是「需要你看一眼并按提示处理」，
 * 不是「已经坏了」。只有 [RecoveryDisposition.Unknown] 才算真的不知道。
 */
@Composable
fun RecoveryBanner(
    dispositions: List<RecoveryDisposition>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!RecoveryPresentation.shouldShow(dispositions)) return
    val hasUnknown = RecoveryPresentation.actionable(dispositions)
        .any { it is RecoveryDisposition.Unknown }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .semantics { contentDescription = "启动恢复：" + RecoveryPresentation.title(dispositions) },
        color = if (hasUnknown) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    RecoveryPresentation.title(dispositions),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (hasUnknown) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onDismiss) { Text("知道了") }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                RecoveryPresentation.message(dispositions),
                style = MaterialTheme.typography.bodySmall,
                color = if (hasUnknown) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}
