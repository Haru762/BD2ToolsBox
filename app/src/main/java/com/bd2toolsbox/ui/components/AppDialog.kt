package com.bd2toolsbox.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 统一结果/确认弹窗（T41）。
 *
 * 契约：
 * - **重要结果不只靠 Toast 或颜色**：需要用户确认或必须知晓的结果走这里；
 * - **普通提示不滥用阻断弹窗**：一行提示能用 Snackbar/Toast 就不弹窗；
 * - 主次按钮位置固定：确认在右（强调色），取消/关闭在左；
 * - 失败是否可重试由 [onRetry] 是否非空表达（`null` = 不可重试），
 *   文案由调用方给，避免把「重试」按钮和「确定」混为一谈。
 *
 * 配色/文字全部走 [MaterialTheme] 语义色，明暗模式一致。
 */
@Composable
fun AppDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    confirmLabel: String = "确定",
    onConfirm: (() -> Unit)? = null,
    dismissLabel: String = "关闭",
    retryLabel: String = "重试",
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            if (onRetry != null) {
                TextButton(onClick = onRetry) { Text(retryLabel) }
            }
            if (onConfirm != null) {
                TextButton(onClick = onConfirm) { Text(confirmLabel) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissLabel) }
        }
    )
}
