package com.bd2toolsbox.ui.dialogs

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bd2toolsbox.data.model.*
import com.bd2toolsbox.ui.viewmodel.MainViewModel
import com.bd2toolsbox.data.model.MoveState

@Composable
internal fun dialogContainerColor() = MaterialTheme.colorScheme.surfaceContainerHigh
internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.2f GB", bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024)
    bytes >= 1024L -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
internal fun resetAndDismiss(onResetState: () -> Unit, onDismiss: () -> Unit) {
    onResetState()
    onDismiss()
}
@Composable
internal fun MoveToGameSection(
    command: String?,
    shizukuAvailable: Boolean,
    moveState: MoveState,
    onMoveToGame: () -> Unit,
    onRecheckShizuku: () -> Unit,
    showLaunchGameOnSuccess: Boolean = false
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    when (moveState) {
        is MoveState.Success -> {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = "成功",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                moveState.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "需重启游戏后生效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (showLaunchGameOnSuccess) {
                Spacer(Modifier.height(12.dp))
                val gameIntent = remember {
                    context.packageManager.getLaunchIntentForPackage("com.neowizgames.game.browndust2")
                }
                if (gameIntent != null) {
                    Button(onClick = { context.startActivity(gameIntent) }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("启动游戏")
                    }
                }
            }
        }

        is MoveState.Moving -> {
            Text("正在装入游戏目录...", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        is MoveState.Failed -> {
            Icon(
                Icons.Default.Error,
                contentDescription = "失败",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(36.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                moveState.error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            ShizukuGuideButtons(onMoveToGame = onMoveToGame, onRecheckShizuku = onRecheckShizuku, canRetryDirectly = true)
            if (command != null) {
                Spacer(Modifier.height(8.dp))
                ManualInstallDetails(command = command, clipboardManager = clipboardManager, context = context)
            }
        }

        is MoveState.Idle -> {
            // command 为 null = Download/Shared 已被移空，没有待装内容。
            // 这时不该再给任何「装入」入口，点了只会报「未找到 Download/Shared」。
            if (command == null) {
                Text(
                    "已装入游戏目录，无待装内容。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            } else if (shizukuAvailable) {
                Button(onClick = onMoveToGame) {
                    Icon(Icons.Default.DriveFileMove, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("一键装入游戏")
                }
                Spacer(Modifier.height(8.dp))
                ManualInstallDetails(command = command, clipboardManager = clipboardManager, context = context)
            } else {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = "提示",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Shizuku 未运行，无法自动装入游戏目录。",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "启动 Shizuku 后回到这里点「重新检测」即可继续，无需重跑转换。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
                ShizukuGuideButtons(onMoveToGame = onMoveToGame, onRecheckShizuku = onRecheckShizuku, canRetryDirectly = false)
                Spacer(Modifier.height(12.dp))
                Text(
                    "转换产物已保存在 Download/Shared",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                ManualInstallDetails(command = command, clipboardManager = clipboardManager, context = context)
            }
        }
    }
}
@Composable
internal fun ShizukuGuideButtons(
    onMoveToGame: () -> Unit,
    onRecheckShizuku: () -> Unit,
    canRetryDirectly: Boolean
) {
    val context = LocalContext.current
    val shizukuIntent = remember {
        context.packageManager.getLaunchIntentForPackage(MainViewModel.SHIZUKU_PACKAGE)
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (shizukuIntent != null) {
            Button(onClick = { context.startActivity(shizukuIntent) }) {
                Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("打开 Shizuku")
            }
        }
        if (canRetryDirectly) {
            Button(onClick = onMoveToGame) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("重试装入")
            }
        } else {
            Button(onClick = onRecheckShizuku) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("重新检测")
            }
        }
    }

    if (shizukuIntent == null) {
        Spacer(Modifier.height(8.dp))
        Text(
            "未检测到 Shizuku 应用，请先安装并激活 Shizuku。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
    }
}
@Composable
internal fun ManualInstallDetails(
    command: String,
    clipboardManager: androidx.compose.ui.platform.ClipboardManager,
    context: android.content.Context
) {
    var expanded by remember { mutableStateOf(false) }

    TextButton(onClick = { expanded = !expanded }) {
        Icon(
            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text("手动装入方法（需 root）", style = MaterialTheme.typography.bodySmall)
    }

    if (!expanded) return

    Text(
        "把 Download/Shared 里的内容合并到游戏目录：\n" +
            "Android/data/com.neowizgames.game.browndust2/files/UnityCache/Shared/\n\n" +
            "可用 MT 管理器等 root 文件管理器操作，或在 root shell 中执行：",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = {
            clipboardManager.setText(AnnotatedString(command))
            Toast.makeText(context, "命令已复制！", Toast.LENGTH_SHORT).show()
        }
    ) {
        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("复制命令")
    }
    Spacer(Modifier.height(8.dp))
    SelectionContainer {
        Text(
            text = command,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                .padding(12.dp)
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        )
    }
}
