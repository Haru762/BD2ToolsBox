package com.bd2toolsbox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 状态语义（T41）。**状态标签与操作按钮是两回事**：
 * - 标签只表达「现在是什么状态」，不可点击、不承担动作；
 * - 动作由按钮承担（[AppDialog] 的确认/重试、列表行上的操作）。
 *
 * 语义色一律取自 [MaterialTheme.colorScheme]，明暗模式下同一语义保持一致的对比关系；
 * 不在各页散落自定义 alpha/hex。
 */
enum class StatusTone(val label: String) {
    NEUTRAL("中性"),
    INFO("信息"),
    SUCCESS("成功"),
    WARNING("警告"),
    ERROR("错误");

    companion object {
        /** 安装状态的语义映射（唯一落点，页面不各自判断）。 */
        fun forInstallState(state: String?): StatusTone = when (state) {
            "INSTALLED" -> SUCCESS
            "NOT_INSTALLED" -> NEUTRAL
            "NEEDS_REAPPLY", "MODIFIED" -> WARNING
            "FAILED", "INVALID" -> ERROR
            else -> INFO
        }
    }
}

/**
 * 状态标签：语义化、不可点击、明暗可读。
 *
 * [tone] 决定配色；文字用对应的 on*Container 色，保证明暗下都可辨识。
 */
@Composable
fun StatusBadge(
    text: String,
    tone: StatusTone = StatusTone.NEUTRAL,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val container: Color = when (tone) {
        StatusTone.NEUTRAL -> scheme.surfaceVariant
        StatusTone.INFO -> scheme.secondaryContainer
        StatusTone.SUCCESS -> scheme.tertiaryContainer
        StatusTone.WARNING -> scheme.secondaryContainer
        StatusTone.ERROR -> scheme.errorContainer
    }
    val content: Color = when (tone) {
        StatusTone.NEUTRAL -> scheme.onSurfaceVariant
        StatusTone.INFO -> scheme.onSecondaryContainer
        StatusTone.SUCCESS -> scheme.onTertiaryContainer
        StatusTone.WARNING -> scheme.onSecondaryContainer
        StatusTone.ERROR -> scheme.onErrorContainer
    }
    Text(
        text = text,
        color = content,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        modifier = modifier
            .background(container.copy(alpha = 1f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
            // 语义名称：读屏与测试都能拿到「状态：<文案>」
            .semantics { contentDescription = "状态：$text" }
    )
}
