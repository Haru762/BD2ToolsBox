package com.bd2toolsbox.ui.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bd2toolsbox.data.model.*

@Composable
fun PreviewProgressDialog(
    state: PreviewState,
    onDismiss: () -> Unit,
    onHide: () -> Unit = onDismiss
) {
    when (state) {
        is PreviewState.Idle -> return
        // T43：准备阶段**必须能退出**。以前这里 onDismissRequest = { } 且 confirmButton = {}，
        // 等于把一个几秒到几十秒的任务锁在不可退出的对话框里 —— 点框外、按返回都没反应。
        // 现在「收起」只改展示：任务照跑，进度面板可重开（见 MainActivity 的进行中横幅）。
        is PreviewState.Preparing -> AlertDialog(
            onDismissRequest = onHide,
            containerColor = dialogContainerColor(),
            title = { Text("正在准备预览") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }
            },
            confirmButton = { TextButton(onClick = onHide) { Text("收起，继续处理") } }
        )
        is PreviewState.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = dialogContainerColor(),
            icon = {
                Icon(Icons.Default.Error, contentDescription = null,
                    tint = MaterialTheme.colorScheme.error)
            },
            title = { Text("无法预览") },
            text = { Text(state.error, textAlign = TextAlign.Center) },
            confirmButton = { Button(onClick = onDismiss) { Text("好") } }
        )
    }
}
@Composable
fun LedgerResetDialog(
    notice: String?,
    onDismiss: () -> Unit,
    onReconcile: (() -> Unit)? = null,
    onDiscard: (() -> Unit)? = null
) {
    if (notice == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = dialogContainerColor(),
        title = {
            Text(
                if (onReconcile != null) "上次装入未完整收尾" else "装入记录提示"
            )
        },
        text = { Text(notice, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            if (onReconcile != null) {
                Button(onClick = {
                    onDismiss()
                    onReconcile()
                }) { Text("核对并修复记录") }
            } else {
                Button(onClick = onDismiss) { Text("知道了") }
            }
        },
        dismissButton = {
            if (onReconcile != null) {
                Row {
                    if (onDiscard != null) {
                        TextButton(onClick = {
                            onDismiss()
                            onDiscard()
                        }) { Text("忽略并清除记录") }
                    }
                    TextButton(onClick = onDismiss) { Text("稍后") }
                }
            }
        }
    )
}
