package com.bd2toolsbox.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bd2toolsbox.data.model.*
import com.bd2toolsbox.core.model.UninstallPlan
import com.bd2toolsbox.data.model.MoveState

@Composable
fun UninstallAllDialog(
    plan: UninstallPlan?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    if (plan == null) return

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = dialogContainerColor(),
        icon = {
            Icon(
                if (plan.error != null) Icons.Default.Error else Icons.Default.RestartAlt,
                contentDescription = null,
                tint = if (plan.error != null) MaterialTheme.colorScheme.error
                       else LocalContentColor.current
            )
        },
        title = {
            Text(
                when {
                    // 说「卸载失败」而不是「无法判断要卸载什么」：玩家点的是「一键卸载」，
                    // 这个动作没成功，直说最好懂。会不会让人以为文件被改坏了？
                    // 下面那句「没有对游戏目录做任何改动」就是为此留的，不能删。
                    plan.error != null -> "卸载失败"
                    plan.bundles.isEmpty() -> "没有需要卸载的 mod"
                    else -> "一键卸载全部 mod"
                }
            )
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (plan.error != null) {
                    // 这里绝不能落到下面那句「所有资源都是官方原版」——
                    // 检测没跑成功时那样说，等于告诉用户 mod 已经卸干净了。
                    Text(plan.error, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "没有对游戏目录做任何改动。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    // 技术细节收进折叠区：玩家不需要看见域名和底层报错原文，
                    // 但排查时又必须能拿到。
                    if (plan.detail != null) {
                        var expanded by remember { mutableStateOf(false) }
                        TextButton(onClick = { expanded = !expanded }) {
                            Icon(
                                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("详细原因", style = MaterialTheme.typography.bodySmall)
                        }
                        if (expanded) {
                            SelectionContainer {
                                Text(
                                    plan.detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Start,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant,
                                            RoundedCornerShape(8.dp)
                                        )
                                        .padding(12.dp)
                                )
                            }
                        }
                    }
                } else if (plan.bundles.isEmpty()) {
                    Text(
                        "游戏目录里所有资源都是官方原版，没有检测到被修改的 bundle。",
                        textAlign = TextAlign.Center
                    )
                } else {
                    Text(
                        "检测到 ${plan.bundles.size} 个被修改过的资源，将全部还原成官方原版。",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    Column(horizontalAlignment = Alignment.Start) {
                        if (plan.fromBackup > 0) {
                            Text(
                                "· ${plan.fromBackup} 个可用本地备份直接还原（无需下载）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        if (plan.needDownload > 0) {
                            Text(
                                "· ${plan.needDownload} 个没有备份，需从官方 CDN 下载" +
                                    if (plan.downloadBytes > 0) "（约 ${formatBytes(plan.downloadBytes)}）" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "还原完成后需要再点一次「装入游戏」，并重启游戏才生效。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        },
        confirmButton = {
            if (plan.error == null && plan.bundles.isNotEmpty()) {
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("开始卸载") }
            } else {
                Button(onClick = onDismiss) { Text("好") }
            }
        },
        dismissButton = {
            if (plan.error == null && plan.bundles.isNotEmpty()) {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}
@Composable
fun RenameModDialog(
    mod: ModInfo?,
    currentAlias: String,
    onConfirm: (ModInfo, String) -> Unit,
    onDismiss: () -> Unit
) {
    if (mod == null) return
    var text by remember(mod.uri) { mutableStateOf(currentAlias) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = dialogContainerColor(),
        icon = { Icon(Icons.Default.Edit, contentDescription = "重命名") },
        title = { Text("重命名") },
        text = {
            Column {
                Text(
                    "自动识别的名字：${mod.character} - ${mod.costume}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("自定义名称") },
                    placeholder = { Text("留空则用自动识别的名字") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "目标：${mod.targetHash}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(mod, text) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
@Composable
fun DeleteFolderConfirmationDialog(
    mod: ModInfo?,
    onConfirm: (ModInfo) -> Unit,
    onDismiss: () -> Unit
) {
    if (mod == null) return

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = dialogContainerColor(),
        icon = {
            Icon(
                Icons.Default.DeleteForever,
                contentDescription = "删除",
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = { Text("从手机删除？") },
        text = {
            Column {
                Text(
                    "将永久删除「${mod.name}」在手机上的文件，无法恢复。",
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "如果只是不想在列表里看到它，请改用「从列表隐藏」—— 那不会删任何文件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(mod) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("永久删除")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
@Composable
fun RemoveModConfirmationDialog(
    mod: ModInfo?,
    onConfirm: (ModInfo) -> Unit,
    onDismiss: () -> Unit
) {
    if (mod == null) return

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = dialogContainerColor(),
        icon = { Icon(Icons.Default.RemoveCircleOutline, contentDescription = "移除") },
        title = { Text("移除这个 mod？") },
        text = {
            Text(
                "将把「${mod.name}」从游戏里移除。\n\n" +
                    "如果同一个目标还装着其他 mod，会重新打包一次只保留它们；" +
                    "否则会取回官方原版盖回去。\n\n" +
                    "完成后需再点一次「装入游戏」，并重启游戏才生效。",
                textAlign = TextAlign.Center
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(mod) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("移除")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
@Composable
fun UninstallConfirmationDialog(
    targetHash: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (targetHash == null) return

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = dialogContainerColor(),
        icon = { Icon(Icons.Default.Warning, contentDescription = "警告") },
        title = { Text("确认还原") },
        text = {
            Text(
                "确定要还原这一组文件的原版内容吗？\n\n目标：${targetHash}",
                textAlign = TextAlign.Center
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(targetHash) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("确认")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
@Composable
fun UninstallDialog(
    state: UninstallState,
    moveState: MoveState,
    onMoveToGame: () -> Unit,
    onRecheckShizuku: () -> Unit,
    onDismiss: () -> Unit
) {
    if (state is UninstallState.Idle) return

    AlertDialog(
        onDismissRequest = {
            if (state !is UninstallState.Downloading) {
                onDismiss()
            }
        },
        containerColor = dialogContainerColor(),
        icon = {
            when (state) {
                is UninstallState.Downloading -> Icon(Icons.Default.Download, contentDescription = "下载中")
                is UninstallState.Finished -> Icon(Icons.Default.CheckCircle, contentDescription = "成功")
                is UninstallState.Failed -> Icon(Icons.Default.Error, contentDescription = "失败")
                else -> {}
            }
        },
        title = {
            val text = when (state) {
                is UninstallState.Downloading -> "正在还原原版文件..."
                is UninstallState.Finished -> "还原成功！"
                is UninstallState.Failed -> "还原失败"
                else -> ""
            }
            Text(text, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                // fillMaxWidth 不能少：否则 Column 收缩成最宽子项的宽度并贴左，
                // 里面的 CenterHorizontally 就只是在那一小块窄条内居中（和
                // ParallelInstallDialog 曾经踩的是同一个坑）。
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                when (state) {
                    is UninstallState.Downloading -> {
                        Text("正在下载原版文件：${state.hashedName}", textAlign = TextAlign.Center)
                        Spacer(modifier = Modifier.height(16.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(state.progressMessage, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    }
                    is UninstallState.Finished -> {
                        // command 被清掉表示已经装进游戏目录了（Download/Shared 已移空），
                        // 那时说「原版文件已保存到下载目录」就不对了。
                        Text(
                            if (state.command == null) "原版资源已装回游戏目录。"
                            else "原版文件已保存到下载（Download）目录。",
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(16.dp))
                        MoveToGameSection(
                            command = state.command,
                            shizukuAvailable = state.shizukuAvailable,
                            moveState = moveState,
                            onMoveToGame = onMoveToGame,
                            onRecheckShizuku = onRecheckShizuku
                        )
                    }
                    is UninstallState.Failed -> Text(state.error, textAlign = TextAlign.Center)
                    else -> {}
                }
            }
        },
        confirmButton = {
            if (state !is UninstallState.Downloading) {
                Button(onClick = onDismiss) {
                    Text("确定")
                }
            }
        },
        dismissButton = null
    )
}
@Composable
fun MergeSpineDialog(state: MergeState, onDismiss: () -> Unit) {
    if (state is MergeState.Idle) return

    AlertDialog(
        onDismissRequest = { if (state !is MergeState.Merging) onDismiss() },
        containerColor = dialogContainerColor(),
        icon = {
            when (state) {
                is MergeState.Merging -> Icon(Icons.Default.Merge, contentDescription = "合并中")
                is MergeState.Finished -> Icon(Icons.Default.CheckCircle, contentDescription = "成功")
                is MergeState.Failed -> Icon(Icons.Default.Error, contentDescription = "失败")
                else -> {}
            }
        },
        title = {
            Text(
                when (state) {
                    is MergeState.Merging -> "正在合并 Spine 资源..."
                    is MergeState.Finished -> "合并成功！"
                    is MergeState.Failed -> "合并失败"
                    else -> ""
                }
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                when (state) {
                    is MergeState.Merging -> {
                        Text("正在合并 Spine 资源...", textAlign = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Text(state.progressMessage,
                             style = MaterialTheme.typography.bodySmall,
                             textAlign = TextAlign.Center)
                    }
                    is MergeState.Finished -> {
                        Icon(Icons.Default.CheckCircle, contentDescription = "成功",
                             tint = MaterialTheme.colorScheme.primary,
                             modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(16.dp))
                        Text(state.message, textAlign = TextAlign.Center,
                             style = MaterialTheme.typography.bodyMedium)
                    }
                    is MergeState.Failed -> {
                        Text(state.error, textAlign = TextAlign.Center,
                             color = MaterialTheme.colorScheme.error)
                    }
                    else -> {}
                }
            }
        },
        confirmButton = {
            if (state !is MergeState.Merging) {
                Button(onClick = onDismiss) { Text("确定") }
            }
        },
        dismissButton = null
    )
}
