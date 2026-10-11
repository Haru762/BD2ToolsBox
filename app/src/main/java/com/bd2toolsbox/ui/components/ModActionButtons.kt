package com.bd2toolsbox.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * MOD 行的操作入口（T42）。
 *
 * 契约：
 * - **预览与「更多」是明确可见的入口**，长按只是熟手捷径，不是唯一入口；
 * - 预览按钮与「更多」按钮**互不重叠**：点预览不触发选择/删除，点更多不启动预览；
 * - 预览不可用时**说明原因**（`previewDisabledReason`），按钮仍可见但禁用，
 *   不用「看起来可点但什么也不做」的假按钮；
 * - 语义名固定：「预览」「更多操作」，TalkBack 与测试可辨识。
 */
@Composable
fun ModActionButtons(
    onPreview: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    previewEnabled: Boolean = true,
    previewDisabledReason: String? = null
) {
    Row(modifier = modifier) {
        val previewDescription = if (previewEnabled) {
            "预览"
        } else {
            "预览不可用：" + (previewDisabledReason ?: "该资源暂不支持直接预览")
        }
        IconButton(
            onClick = onPreview,
            enabled = previewEnabled,
            modifier = Modifier.semantics { contentDescription = previewDescription }
        ) {
            Icon(
                Icons.Default.Visibility,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (previewEnabled) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            )
        }
        IconButton(
            onClick = onMore,
            modifier = Modifier.semantics { contentDescription = "更多操作" }
        ) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
