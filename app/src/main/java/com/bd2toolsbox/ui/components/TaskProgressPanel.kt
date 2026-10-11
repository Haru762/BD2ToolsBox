package com.bd2toolsbox.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
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

/**
 * 任务进度面板（T41）。
 *
 * 契约：
 * - 进度由**任务宿主**持有（T21/T22–T24 的协调器/executor），面板只显示，不驱动业务；
 * - [progress] 为 `null` = 不确定进度（转圈）；`0f..1f` = 确定进度（进度条）；
 * - 取消只在**安全阶段**开放：由 [cancelEnabled] 决定，禁用时按钮可见但不可点
 *   （不用「隐藏」冒充「不可取消」）；
 * - 文案含当前目标与细粒度阶段，避免只有一个不动的百分比。
 */
@Composable
fun TaskProgressPanel(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    progress: Float? = null,
    cancelLabel: String = "取消",
    cancelEnabled: Boolean = false,
    onCancel: (() -> Unit)? = null
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "任务进度：$title" },
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (onCancel != null) {
                    TextButton(onClick = onCancel, enabled = cancelEnabled) { Text(cancelLabel) }
                }
            }
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            when {
                progress == null -> CircularProgressIndicator(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .size(20.dp),
                    strokeWidth = 2.dp
                )
                else -> LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
            }
        }
    }
}
