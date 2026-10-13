package com.bd2toolsbox.ui.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bd2toolsbox.data.model.*
import com.bd2toolsbox.core.model.BackupPurge
import com.bd2toolsbox.core.model.PurgeOutcome
import com.bd2toolsbox.ui.components.AppDialog
import android.net.Uri

@Composable
fun BackupManageDialog(
    enabled: Boolean,
    usage: Pair<Long, Int>,
    onToggle: (Boolean) -> Unit,
    onConfirmClear: (String) -> Unit,
    blocker: String?,
    result: PurgeOutcome?,
    onAcknowledgeResult: () -> Unit,
    onDismiss: () -> Unit
) {
    var confirming by remember { mutableStateOf(false) }
    // 确认时的目标集合指纹：执行前复核，变了就要求重新确认
    var confirmedFingerprint by remember { mutableStateOf<String?>(null) }

    if (result != null) {
        AppDialog(
            title = "清空原版备份",
            message = BackupPurge.resultText(result, formatBytes(result.remainingBytes)),
            confirmLabel = "知道了",
            onConfirm = onAcknowledgeResult,
            dismissLabel = "关闭",
            onDismiss = onAcknowledgeResult
        )
        return
    }

    if (confirming) {
        AppDialog(
            title = BackupPurge.DIALOG_TITLE,
            message = buildString {
                append(BackupPurge.consequence(usage.second, formatBytes(usage.first)))
                if (blocker != null) {
                    append("\n\n")
                    append(blocker)
                }
            },
            confirmLabel = BackupPurge.CONFIRM_LABEL,
            onConfirm = if (blocker == null) {
                {
                    // 复核交给调用方：指纹不一致时它会要求重新确认（回调再进一次确认步骤）
                    onConfirmClear(confirmedFingerprint.orEmpty())
                    confirming = false
                }
            } else {
                null
            },
            dismissLabel = BackupPurge.CANCEL_LABEL,
            onDismiss = { confirming = false }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = dialogContainerColor(),
        icon = { Icon(Icons.Default.Save, contentDescription = null) },
        title = { Text("原版备份") },
        text = {
            Column {
                Text(
                    "卸载时可直接拷回原版，不会预先占用空间。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("装入前自动备份", modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = onToggle)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "当前已备份 ${usage.second} 个，占用 ${formatBytes(usage.first)}",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (usage.second > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "清空后，卸载 mod 将改为从官方 CDN 重新下载原版。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("完成") } },
        dismissButton = {
            if (usage.second > 0) {
                TextButton(
                    onClick = {
                        confirmedFingerprint = null
                        confirming = true
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("清空备份") }
            }
        }
    )
}
@Composable
fun ModSourceDirsDialog(
    dirs: List<Uri>,
    mods: List<ModInfo>,
    onAdd: () -> Unit,
    onRemove: (Uri) -> Unit,
    onDismiss: () -> Unit
) {
    // SAF 的子文档 URI 一定以所属 tree 的 URI 打头，前缀匹配就能归属，
    // 不必让扫描过程额外记一份来源。
    val counts = remember(dirs, mods) {
        dirs.associateWith { d ->
            val prefix = "$d/document/"
            mods.count { it.uri.toString().startsWith(prefix) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = dialogContainerColor(),
        title = { Text("mod 文件夹") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (dirs.isEmpty()) "还没有添加任何目录。"
                    else "下面 ${dirs.size} 个目录里的 mod 会合并显示在同一个列表里。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                // 目录一多就得能滚，但不能让它把整个框顶到屏幕外，所以限高。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    dirs.forEach { uri ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.FolderOpen, null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    dirLabel(uri),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2
                                )
                                Text(
                                    "${counts[uri] ?: 0} 个 mod",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { onRemove(uri) }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "移除这个目录",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onAdd, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("添加文件夹")
                }
                Text(
                    "移除只是不再扫描它，手机上的文件不会被删。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } }
    )
}
@Composable
fun LostSourceDirsDialog(
    dirs: List<String>?,
    onDismiss: () -> Unit,
    onReselect: () -> Unit
) {
    if (dirs.isNullOrEmpty()) return
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = dialogContainerColor(),
        title = { Text("文件夹访问授权已失效") },
        text = {
            Column {
                Text(
                    "下面的 mod 文件夹不能再读取（授权被系统或你撤销了），已从列表移除。" +
                        "文件本身没有被删除，重新选择一次文件夹即可恢复。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                dirs.forEach {
                    Text("• " + dirLabel(Uri.parse(it)), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onReselect()
                onDismiss()
            }) { Text("重新选择文件夹") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("知道了") } }
    )
}
private fun dirLabel(uri: Uri): String {
    // pathSegments 已经是解码后的，形如 "primary:Download/mods"
    val segs = uri.pathSegments
    val i = segs.indexOf("tree")
    val raw = if (i >= 0 && i + 1 < segs.size) segs[i + 1] else return uri.toString()
    val volume = raw.substringBefore(':', "")
    val rel = raw.substringAfter(':', raw)
    val root = when {
        // "primary" 是系统给内置存储的固定卷名；其余是 SD 卡/U 盘的卷 ID
        volume == "primary" -> "内部存储"
        volume.isEmpty() -> ""
        else -> "SD 卡($volume)"
    }
    return listOf(root, rel).filter { it.isNotEmpty() }.joinToString("/")
        .ifEmpty { uri.toString() }
}
