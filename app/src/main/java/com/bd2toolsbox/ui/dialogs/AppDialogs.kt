package com.bd2toolsbox.ui.dialogs

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bd2toolsbox.data.model.*
import com.bd2toolsbox.data.repository.UpdateRepository
import com.bd2toolsbox.ui.components.InstallJobRow
import com.bd2toolsbox.ui.components.SelectionRow
import com.bd2toolsbox.service.PrepackService
import com.bd2toolsbox.ui.viewmodel.MainViewModel
import android.net.Uri
import com.bd2toolsbox.data.model.MoveState


/**
 * 一键卸载的确认框。
 *
 * 把代价摊开说清楚再让用户点：有备份的能本地秒还原，没备份的要下载多少。
 * 卸载的判断依据是干净检测（对比 catalog 的原版字节数），所以手动拷进游戏目录的 mod
 * 同样会被算进来 —— 这点也写在文案里，免得用户以为只卸 app 自己装的。
 */
/**
 * 批量预解包的确认框 —— 先把空间账算给用户看，再让他决定。
 *
 * 上百个产物全解可能上 GB，贸然开跑很容易把存储塞满。膨胀比优先用已有缓存实测出来的，
 * 有样本时会注明，没样本时用保守估计并说清这是估算。
 */
@Composable
fun PrepackPlanDialog(
    plan: MainViewModel.PrepackPlan?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    if (plan == null) return

    val nothingToDo = plan.todo == 0
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = dialogContainerColor(),
        icon = {
            Icon(
                if (plan.enoughSpace || nothingToDo) Icons.Default.Unarchive else Icons.Default.Warning,
                contentDescription = null,
                tint = if (plan.enoughSpace || nothingToDo) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.error
            )
        },
        title = {
            Text(
                when {
                    nothingToDo -> "已经全部解包过了"
                    !plan.enoughSpace -> "空间可能不足"
                    else -> "批量预解包"
                }
            )
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (nothingToDo) {
                    Text(
                        "全部 ${plan.total} 个产物都已缓存，预览会直接秒开。",
                        textAlign = TextAlign.Center
                    )
                } else {
                    Text(
                        "共 ${plan.total} 个产物，其中 ${plan.alreadyCached} 个已缓存，" +
                            "本次需解包 ${plan.todo} 个。",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    Column(horizontalAlignment = Alignment.Start) {
                        Text(
                            "· 预计占用 ${formatBytes(plan.estimatedBytes)}" +
                                if (plan.ratioFromSamples) "（按已有缓存实测比例）" else "（估算）",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "· 当前可用 ${formatBytes(plan.freeBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (plan.enoughSpace) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (plan.enoughSpace) {
                            "已完成的部分会保留，中断后再点会接着做。"
                        } else {
                            "预计占用已接近或超过可用空间，建议先清理存储。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        },
        confirmButton = {
            if (nothingToDo) {
                Button(onClick = onDismiss) { Text("好") }
            } else {
                Button(
                    onClick = onConfirm,
                    colors = if (plan.enoughSpace) ButtonDefaults.buttonColors()
                             else ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(if (plan.enoughSpace) "开始" else "仍要继续") }
            }
        },
        dismissButton = {
            if (!nothingToDo) TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * 批量预解包的进度框。可取消，已完成的部分会保留。
 *
 * 两个出口要分清，这是曾经写错过的地方：
 *   「后台运行」[onHide]   —— 只关掉这个框，任务照跑（前台服务化的意义就在这）
 *   「停止」  [onCancel]   —— 真的中断任务
 *
 * 早先只有「停止」，onDismissRequest 还是空实现 —— 于是文案写着「可以关掉这个提示」
 * 却根本关不掉，点外面、按返回键都没反应，想关只能点「停止」把任务一起停掉。
 */
@Composable
fun PrepackProgressDialog(
    progress: PrepackService.Progress?,
    onCancel: () -> Unit,
    onHide: () -> Unit
) {
    if (progress == null) return
    val (done, total, current, detail) = progress

    AlertDialog(
        // 点框外与按返回键都走「后台运行」，而不是什么都不做。
        // 进度在通知栏和设置里都看得到，关掉不会让人失去线索。
        onDismissRequest = onHide,
        containerColor = dialogContainerColor(),
        title = { Text("正在预解包 $done / $total") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                LinearProgressIndicator(
                    progress = { if (total > 0) done.toFloat() / total else 0f },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text(current, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                if (detail.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    // 单个 bundle 要花几秒到几十秒，整体进度条那阵子一动不动。
                    // 这一行 + 下面那条不定长进度条就是为了让「还在干活」看得出来。
                    Text(
                        detail,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                    )
                }
            }
        },
        // 「后台运行」放 confirm 位：它才是这个框的常规出路，
        // 「停止」是破坏性的，放 dismiss 位并染成错误色。
        confirmButton = {
            TextButton(onClick = onHide) { Text("后台运行") }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Text("停止") }
        }
    )
}

@Composable
fun UninstallAllDialog(
    plan: MainViewModel.UninstallPlan?,
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

/** 原版备份管理：开关、占用、清理。 */
@Composable
fun BackupManageDialog(
    enabled: Boolean,
    usage: Pair<Long, Int>,
    onToggle: (Boolean) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
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
                    onClick = onClear,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("清空备份") }
            }
        }
    )
}

/** 预览准备中/失败。产物要先解包，耗时几秒，不能像 PC mod 那样静默处理。 */
@Composable
fun PreviewProgressDialog(state: PreviewState, onDismiss: () -> Unit) {
    when (state) {
        is PreviewState.Idle -> return
        is PreviewState.Preparing -> AlertDialog(
            onDismissRequest = { },
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
            confirmButton = {}
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

/**
 * 弹框统一底色（本文件里每个 AlertDialog 都要显式传）。
 *
 * 不能吃默认值：M3 1.2.1 的 AlertDialogDefaults.containerColor 走
 * DialogTokens.ContainerColor = ColorSchemeKeyTokens.Surface，也就是 colorScheme.surface，
 * 而壁纸模式（Theme 的 transparentBackground）正把 surface 抽成了透明 —— 不指定的话
 * 弹框会连着壁纸和底下的列表一起透出来，两层字叠在一起。给一个不透明的容器色即可，
 * 与开不开壁纸无关（M3 新规范里对话框本来就是 surfaceContainerHigh）。
 */
@Composable
private fun dialogContainerColor() = MaterialTheme.colorScheme.surfaceContainerHigh

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format("%.2f GB", bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024)
    bytes >= 1024L -> String.format("%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/** 解包工具弹层：选文件 → 解包 → 结果。产物落 Download/outputs。 */
@Composable
fun UnpackDialog(
    unpackState: UnpackState,
    inputFile: Uri?,
    onSetInputFile: (Uri) -> Unit,
    onInitiateUnpack: () -> Unit,
    onResetState: () -> Unit,
    onDismiss: () -> Unit
) {
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> uri?.let(onSetInputFile) }
    )

    AlertDialog(
        onDismissRequest = { if (unpackState !is UnpackState.Unpacking) resetAndDismiss(onResetState, onDismiss) },
        containerColor = dialogContainerColor(),
        icon = { Icon(Icons.Default.Unarchive, contentDescription = "解包工具") },
        title = { Text("解包工具") },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                when (val state = unpackState) {
                    is UnpackState.Idle -> Column(
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        SelectionRow(
                            label = "输入文件：",
                            value = inputFile?.lastPathSegment ?: "未选择",
                            onClick = { filePicker.launch(arrayOf("*/*")) }
                        )
                        Text(
                            "输出将保存到 Download/outputs/",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is UnpackState.Unpacking -> {
                        Text("正在解包...", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(16.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(16.dp))
                        Text(
                            state.progressMessage,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    is UnpackState.Finished -> ResultBlock(
                        icon = Icons.Default.CheckCircle,
                        title = "成功！",
                        message = state.message
                    )
                    is UnpackState.Failed -> ResultBlock(
                        icon = Icons.Default.Error,
                        title = "操作失败",
                        message = state.error,
                        isError = true
                    )
                }
            }
        },
        confirmButton = {
            if (unpackState is UnpackState.Idle) {
                Button(onClick = onInitiateUnpack, enabled = inputFile != null) {
                    Text("解包")
                }
            }
        },
        dismissButton = {
            if (unpackState !is UnpackState.Unpacking) {
                TextButton(onClick = { resetAndDismiss(onResetState, onDismiss) }) { Text("关闭") }
            }
        }
    )
}

/** 关闭 = 重置状态 + 收弹层（两个动作总是一起发生，收进一个函数防漏）。 */
private fun resetAndDismiss(onResetState: () -> Unit, onDismiss: () -> Unit) {
    onResetState()
    onDismiss()
}

/** 结束态（成功/失败）的统一版式：大图标 + 标题 + 明细（message 为空时不占位）。 */
@Composable
private fun ResultBlock(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    message: String = "",
    isError: Boolean = false
) {
    val tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(48.dp))
    Spacer(Modifier.height(16.dp))
    Text(title, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    if (message.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) tint else androidx.compose.ui.graphics.Color.Unspecified
        )
    }
}

/**
 * 「装入游戏目录」区块，安装完成与还原完成两个对话框共用。
 *
 * 设计要点：无 Shizuku 时不再把 root shell 命令摆在主视觉位置。
 * 那条命令在未 root 的真机上**根本无处可运行**（Android 11 起 /Android/data
 * 对 shell 和第三方文件管理器都受限），而这个分支的真实触发原因通常只是
 * 「Shizuku 掉了」——所以这里直接引导用户去启动 Shizuku 再回来继续，
 * 命令则折叠收起，留给少数有 root 的用户。
 */
/**
 * 「装入游戏目录」那一段。
 *
 * 只用于**产物落在 Download/Shared、需要再装一次**那条路。已转换产物直拷进游戏目录的
 * 那条路没有这一步，走 [InGameResultSection]。
 *
 * [command] 是手动装入的命令文本，同时兼作「Download/Shared 里还有没有待装内容」的标志：
 * 装入成功后调用方会把它置 null（见 MainViewModel.moveFilesToGame），这里据此收掉
 * 「一键装入游戏 / 重试装入 / 手动装入方法」——那时源目录已被移空，再点只会报
 * 「未找到 Download/Shared 目录」。成功提示与「启动游戏」不受影响，照常显示。
 */
@Composable
private fun MoveToGameSection(
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

/**
 * 已经直接进了游戏目录的那条路的结果区 —— 只报结果，不给任何「装入」入口。
 *
 * 与 [MoveToGameSection] 分开写而不是加个开关：那个函数的每一个分支都在处理
 * 「Download/Shared 里的东西怎么弄进游戏」，而这里根本没有那一步。硬塞进去
 * 就得在四个分支里各加一次 if，下次改动照样会漏。
 */
@Composable
private fun InGameResultSection(
    moveState: MoveState,
    successFallback: String
) {
    val context = LocalContext.current

    Icon(
        Icons.Default.CheckCircle,
        contentDescription = "成功",
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(36.dp)
    )
    Spacer(Modifier.height(8.dp))
    Text(
        (moveState as? MoveState.Success)?.message ?: successFallback,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center
    )
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

/** 「打开 Shizuku」+「重新检测」两个引导按钮。 */
@Composable
private fun ShizukuGuideButtons(
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

/** 折叠起来的手动装入说明，默认收起，只给有 root 的用户。 */
@Composable
private fun ManualInstallDetails(
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

@Composable
fun ParallelInstallDialog(
    installJobs: List<InstallJob>,
    finalResult: FinalInstallResult?,
    moveState: MoveState,
    onMoveToGame: () -> Unit,
    onRecheckShizuku: () -> Unit,
    onDismiss: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = { if (finalResult != null) onDismiss() },
        containerColor = dialogContainerColor(),
        modifier = Modifier
            // 处理中固定 80% 屏高：任务行是一条条冒出来的，高度不固定的话
            // 对话框会随之上下跳。出结果后只剩几行字，再撑到 80% 就是一大片空白，
            // 所以改成「最多 80%」，让框贴着内容收。
            .then(
                if (finalResult == null) Modifier.fillMaxHeight(0.8f)
                else Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.8f).dp)
            )
            .fillMaxWidth(0.95f),
        title = {
            // 按角色装单个时也走这个对话框，那时说「批量转换」既不批量也没转换
            // （产物是直接拷进去的），所以按实际条数取措辞。
            Text(
                when {
                    finalResult == null -> "正在处理"
                    installJobs.size <= 1 -> "装入完成"
                    else -> "批量转换完成"
                },
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        },
        text = {
            Column(
                // 结果态不撑满高度，否则「确定」被顶到框底、中间空一大块
                modifier = if (finalResult == null) Modifier.fillMaxSize()
                           else Modifier.fillMaxWidth()
            ) {
                // 这两层都必须 fillMaxWidth。少了的话 Column 的宽度会收缩成
                // 「最宽的那个子项」的宽度，然后整块贴着左边 —— 里面的
                // CenterHorizontally 只是在那一小块窄条内居中，看上去就是全体偏左。
                // （实测偏得很厉害：对话框内容区中心在 x=540，而各行中心都在 287。）
                Box(
                    modifier = Modifier
                        .weight(1f, fill = finalResult == null)
                        .fillMaxWidth()
                ) {
                    if (finalResult == null) {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(installJobs, key = { it.job.hashedName }) { installJob ->
                                InstallJobRow(installJob)
                            }
                        }
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                "结果：成功 ${finalResult.successfulJobs} 个，失败 ${finalResult.failedJobs} 个。",
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center
                            )
                            // 总耗时
                            if (finalResult.elapsedTimeMs > 0) {
                                val elapsedSeconds = finalResult.elapsedTimeMs / 1000.0
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = String.format("总耗时 %.1f 秒", elapsedSeconds),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            
                            if (finalResult.alreadyInGame) {
                                // 产物直拷进游戏目录这条路没有「装入游戏」这一步。
                                // 只报结果，不给任何装入入口 —— Download/Shared 里
                                // 本来就没东西，给了按钮点下去只会报「找不到目录」。
                                Spacer(Modifier.height(16.dp))
                                InGameResultSection(
                                    moveState = moveState,
                                    successFallback = "已装入游戏目录，重启游戏后生效。"
                                )
                            } else {
                                Spacer(Modifier.height(16.dp))
                                MoveToGameSection(
                                    command = finalResult.command,
                                    shizukuAvailable = finalResult.shizukuAvailable,
                                    moveState = moveState,
                                    onMoveToGame = onMoveToGame,
                                    onRecheckShizuku = onRecheckShizuku,
                                    showLaunchGameOnSuccess = true
                                )
                            }
                            
                            // 失败明细（点条目复制错误日志）
                            if (finalResult.failedJobDetails.isNotEmpty()) {
                                Spacer(Modifier.height(16.dp))
                                FailedJobList(finalResult.failedJobDetails)
                            }
                        }
                    }
                }

                if (finalResult != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        // 整个框都是居中排布，「确定」再靠右会显得跟上面各行不是一体的
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Button(onClick = onDismiss) { Text("确定") }
                    }
                }
            }
        },
        confirmButton = {}
    )
}

/**
 * 转换失败的任务明细。每条是一张可点按的错误卡：点一下复制完整错误日志 ——
 * 排查时用户要把它贴给别人，长按选择太费劲。
 */
@Composable
private fun FailedJobList(details: List<FailedJobInfo>) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Text(
        "失败的分组：",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(8.dp))
    details.forEach { failedJob ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp))
                .clickable {
                    clipboardManager.setText(AnnotatedString(failedJob.error))
                    Toast.makeText(context, "错误日志已复制！", Toast.LENGTH_SHORT).show()
                }
                .padding(12.dp)
        ) {
            Text(
                text = "分组：${failedJob.hashedName.take(16)}...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.height(4.dp))
            SelectionContainer {
                Text(
                    text = failedJob.error,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                )
            }
        }
    }
}

/**
 * 移除单个 mod 的确认。
 *
 * 说明里点出两件用户否则会困惑的事：安卓改的是游戏缓存，所以移除要重新处理一次文件、
 * 并且要重启游戏才看得到效果 —— 不像 PC 端删个文件就完事。
 */
/** 给已转换产物起个自己看得懂的名字。留空即恢复自动识别的名字。 */
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

/**
 * 从手机删除 mod 文件夹的确认。
 *
 * 这是整个 app 里唯一会真正动用户文件的操作，所以确认文案要够重：写明不可恢复，
 * 并且把「只是不想看到它」的替代路径直接指出来。
 */
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

/** 图集合并（自救工具）的进度弹层。合并中不可关；结束/失败显示结果。 */
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

/**
 * 游戏更新了但本地资源没跟上（characters.json 换了版本、游戏目录的 bundle 却没变）
 * 时的提醒。用户要做的：去更新游戏并进一次游戏拉资源，再回到本 app。
 */
@Composable
fun VersionMismatchWarningDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = dialogContainerColor(),
        icon = {
            Icon(Icons.Default.Warning, contentDescription = "警告",
                 tint = MaterialTheme.colorScheme.error)
        },
        title = { Text("游戏资源需要更新") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("检测到游戏出了新版本，但手机上的游戏资源还没更新。", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "请先去更新游戏并启动一次（让它下载好最新资源），然后重新打开本应用。",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("确定") } },
        dismissButton = null
    )
}

@Composable
fun BundleScanDialog(
    state: BundleScanState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    if (state is BundleScanState.Idle) return

    AlertDialog(
        onDismissRequest = {
            if (state !is BundleScanState.Scanning) {
                onDismiss()
            }
        },
        containerColor = dialogContainerColor(),
        icon = {
            when (state) {
                is BundleScanState.Confirmation -> Icon(Icons.Default.FindInPage, contentDescription = "待扫描")
                is BundleScanState.Scanning -> Icon(Icons.Default.Search, contentDescription = "扫描中")
                is BundleScanState.Finished -> Icon(Icons.Default.CheckCircle, contentDescription = "完成")
                is BundleScanState.Failed -> Icon(Icons.Default.Error, contentDescription = "错误")
                else -> {}
            }
        },
        title = {
            val text = when (state) {
                is BundleScanState.Confirmation -> "需要扫描游戏资源"
                is BundleScanState.Scanning -> "正在扫描游戏资源..."
                is BundleScanState.Finished -> "扫描完成"
                is BundleScanState.Failed -> "扫描失败"
                else -> ""
            }
            Text(text)
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                when (val s = state) {
                    is BundleScanState.Confirmation -> {
                        Text(
                            "检测到 ${s.bundleCount} 个新增或已更新的游戏资源，需要扫描建立索引。",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "扫描耗时取决于资源数量。也可以跳过并使用上次的缓存索引。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                    is BundleScanState.Scanning -> {
                        Text(
                            "正在扫描 ${s.currentIndex + 1} / ${s.totalCount}",
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(16.dp))
                        LinearProgressIndicator(
                            progress = { s.currentIndex.toFloat() / s.totalCount.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))
                        if (s.currentBundle.isNotEmpty()) {
                            Text(
                                "正在扫描：${s.currentBundle}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                maxLines = 1
                            )
                        }
                    }
                    is BundleScanState.Finished -> {
                        ResultBlock(
                            icon = Icons.Default.CheckCircle,
                            title = s.message
                        )
                        if (s.failedCount > 0) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "有 ${s.failedCount} 个资源扫描失败（不影响其余的）。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    is BundleScanState.Failed -> ResultBlock(
                        icon = Icons.Default.Error,
                        title = s.error,
                        message = "没有对游戏目录做任何改动。",
                        isError = true
                    )
                    else -> {}
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // 确认页给「跳过」；其余页只有主按钮（Spacer 占位维持 SpaceBetween）
                if (state is BundleScanState.Confirmation) {
                    TextButton(onClick = onDismiss) { Text("跳过") }
                } else {
                    Spacer(Modifier.width(1.dp))
                }
                when (state) {
                    is BundleScanState.Confirmation ->
                        Button(onClick = onConfirm) { Text("开始扫描") }
                    is BundleScanState.Finished, is BundleScanState.Failed ->
                        Button(onClick = onDismiss) { Text("确定") }
                    else -> {}
                }
            }
        },
        dismissButton = null
    )
}


/**
 * 旧格式账本被清空的一次性告知。
 *
 * 不能静默清空：用户装过的 mod 会暂时显示成「被其他工具修改」，不解释一句，
 * 看起来就像 app 把记录弄丢了。
 */
@Composable
fun LedgerResetDialog(notice: String?, onDismiss: () -> Unit) {
    if (notice == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = dialogContainerColor(),
        title = { Text("装入记录已重置") },
        text = { Text(notice, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { Button(onClick = onDismiss) { Text("知道了") } }
    )
}

/**
 * 「装产物会覆盖同包已装 mod」的预警。
 *
 * 产物是打包好的完整 bundle，直接覆盖写进游戏目录、没有重打包环节，所以无法像
 * 转换流程那样把同包已装的 mod 并进去 —— 只能在动手前把损失说清楚。
 */
@Composable
fun ConvertedOverwriteDialog(
    plan: MainViewModel.ConvertedOverwritePlan?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    if (plan == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = dialogContainerColor(),
        title = { Text("会覆盖同一资源包里的 ${plan.total} 个 mod") },
        text = {
            Column {
                Text(
                    "游戏把多个角色的资源打包在一起，而「直接装入」是整包覆盖，" +
                        "没法把已装的合并进去。继续的话这些会被还原成原版：",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                plan.casualties.forEach { (hash, names) ->
                    names.take(6).forEach {
                        Text("· $it", style = MaterialTheme.typography.bodySmall)
                    }
                    if (names.size > 6) {
                        Text("· 还有 ${names.size - 6} 个…", style = MaterialTheme.typography.bodySmall)
                    }
                    if (hash in plan.recoverable) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "这些 mod 的源文件都还在 —— 改用「转换所选」可以把它们一起重新打包、都保住。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) { Text("仍要装入") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/**
 * mod 文件夹管理。
 *
 * 早先只认一个目录 —— 换一个，上一个里的 mod 就从列表里消失了。用户的 mod 天然分散
 * （下载的堆在 Download、自己做的在别处），于是改成「记住扫过的每一个目录、合并成一份列表」。
 * 有了「多个」就必须有地方看见它们、能单独去掉某一个，这个框就是那个地方。
 *
 * 每行右边的数字是「这个目录贡献了几个 mod」：目录路径长得都差不多（.../Download/xxx），
 * 光看路径很难认出哪个是想删的那个，数量能当第二个线索。
 *
 * 「移除」只是不再扫描它、并把 SAF 的持久权限放掉，手机上的文件一个都不动 ——
 * 这句必须写在框里，否则没人敢点。
 */
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

/**
 * 把 SAF 的 tree URI 翻成人能读的路径。
 *
 * `content://com.android.externalstorage.documents/tree/primary%3ADownload%2Fmods`
 * → 「内部存储/Download/mods」。原样显示 URI 的话，一屏里几个目录长得完全一样，
 * 用户没法判断该移除哪个。
 *
 * 认不出格式就退回原始 URI —— 难看，但至少不是空白。
 */
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

/**
 * 「发现新版本」。
 *
 * 只在用户手动点过「检查更新」、且 GitHub 上的 tag 确实比本机新时才出现（判断在
 * [UpdateRepository] 里做）。这里不做应用内下载安装 —— 正文给一段更新说明，
 * 点「下载并安装」在应用内下载（架构按本机自动选），完成后另弹安装确认框。
 *
 * 正文是 release 的 markdown 原文，这里只做轻量去语法（见 [releaseNotesText]）：
 * 弹窗里没法渲染 markdown，也没有可点的链接（复制地址比点一个打不开的链接有用）。
 */
@Composable
fun UpdateDialog(
    release: UpdateRepository.Release?,
    onDownload: (UpdateRepository.Release) -> Unit,
    onIgnore: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (release == null) return

    val notes = remember(release.notes) { releaseNotesText(release.notes) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = dialogContainerColor(),
        icon = {
            Icon(
                Icons.Default.SystemUpdate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text("发现新版本 ${release.version}") },
        text = {
            // 更新说明长短不可控（从一句话到整篇公告），限高 + 滚动，
            // 否则一条长 release 能把按钮顶出屏幕。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    notes.ifBlank { "这个版本没有写更新说明，可以去 release 页看看。" },
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            Button(onClick = { release?.let(onDownload) }) { Text("下载并安装") }
        },
        dismissButton = {
            Row {
                // 忽略此版本：不再自动弹（手动检查仍会出）
                TextButton(onClick = { release?.let { onIgnore(it.version) } }) {
                    Text("忽略此版本")
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

/**
 * 贴图静态查看器：无骨架 mod（立绘/壁纸等贴图替换型）的预览兜底。
 * spine 放不了动画，至少把图亮出来。点图片翻页，按钮关闭。
 *
 * 大贴图（spine 图集页常见 2048²/4096²）按屏幕宽降采样解码，避免一张
 * 图就把查看器撑爆——跟预解包同一个教训。
 */
@Composable
fun ImageViewerDialog(
    imagePaths: List<String>,
    onDismiss: () -> Unit
) {
    if (imagePaths.isEmpty()) return
    var index by remember { mutableStateOf(0) }
    var bitmap by remember(imagePaths, index) { mutableStateOf<android.graphics.Bitmap?>(null) }
    val context = LocalContext.current
    val density = LocalDensity.current

    // 当前页解码（IO 不可挂主线程，LaunchedEffect 内解码 + 按屏宽降采样）
    LaunchedEffect(imagePaths, index) {
        bitmap = withContext(Dispatchers.IO) {
            try {
                val opts = android.graphics.BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                android.graphics.BitmapFactory.decodeFile(imagePaths[index], opts)
                val screenW = with(density) {
                    android.os.Build.VERSION.SDK_INT.let {
                        context.resources.displayMetrics.widthPixels
                    }
                }
                var sample = 1
                while (opts.outWidth / (sample * 2) >= screenW) sample *= 2
                android.graphics.BitmapFactory.decodeFile(
                    imagePaths[index],
                    android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = dialogContainerColor(),
        title = { Text("贴图 ${index + 1}/${imagePaths.size}") },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(Color.Black)
                    .clickable {
                        // 点图翻页；最后一页再点关闭
                        if (index < imagePaths.size - 1) index++ else onDismiss()
                    },
                contentAlignment = Alignment.Center
            ) {
                bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } ?: CircularProgressIndicator(modifier = Modifier.size(28.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

/**
 * 更新包下载完成后的「安装」确认框。安装动作由 Activity 层执行（要拉系统
 * 安装器与未知来源授权，都需要 Activity 上下文）。
 */
@Composable
fun UpdateReadyDialog(
    apkName: String?,
    onInstall: () -> Unit,
    onDismiss: () -> Unit
) {
    if (apkName == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = dialogContainerColor(),
        icon = {
            Icon(
                Icons.Default.SystemUpdate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text("更新包已就绪") },
        text = {
            Text(apkName, style = MaterialTheme.typography.bodySmall)
        },
        confirmButton = { Button(onClick = onInstall) { Text("安装") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** markdown 行内链接：[文字](地址) → 文字（地址）。 */
private val MD_LINK = Regex("""\[([^\[\]]*)\]\(([^()\s]*)\)""")

/**
 * release 正文的轻量去 markdown。
 *
 * 「轻量」是刻意的：弹窗里只要可读，不需要还原格式。标题去掉井号、列表换成中点、
 * 行内链接展开成「文字（地址）」、去掉 `**` 与反引号。表格、图片这些一概不处理 ——
 * 认不出的语法原样留着，至少信息不丢。
 */
private fun releaseNotesText(raw: String): String = raw
    .lineSequence()
    .joinToString("\n") { line ->
        val linked = MD_LINK.replace(line) { m ->
            val label = m.groupValues[1].trim()
            val url = m.groupValues[2].trim()
            if (label.isEmpty()) url else "$label（$url）"
        }
        val trimmed = linked.trimStart()
        when {
            trimmed.startsWith("### ") -> trimmed.removePrefix("### ")
            trimmed.startsWith("## ") -> trimmed.removePrefix("## ")
            trimmed.startsWith("# ") -> trimmed.removePrefix("# ")
            trimmed.startsWith("* ") || trimmed.startsWith("- ") -> "· " + trimmed.drop(2)
            else -> linked
        }.replace("**", "").replace("`", "")
    }
    .trim()
