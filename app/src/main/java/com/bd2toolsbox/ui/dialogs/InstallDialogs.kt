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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
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
import com.bd2toolsbox.core.model.ConvertedOverwritePlan
import com.bd2toolsbox.core.model.PrepackPlan
import com.bd2toolsbox.ui.components.InstallJobRow
import com.bd2toolsbox.ui.components.SelectionRow
import com.bd2toolsbox.service.PrepackService
import android.net.Uri
import com.bd2toolsbox.data.model.MoveState

@Composable
fun PrepackPlanDialog(
    plan: PrepackPlan?,
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
@Composable
private fun InGameResultSection(
    moveState: MoveState,
    successFallback: String,
    anyInstalled: Boolean
) {
    val context = LocalContext.current
    // Direct install can end partially or wholly failed; never paint that as a green success.
    val failure = moveState as? MoveState.Failed
    val tint = if (failure != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary

    Icon(
        if (failure != null) Icons.Default.Error else Icons.Default.CheckCircle,
        contentDescription = if (failure != null) "未全部装入" else "成功",
        tint = tint,
        modifier = Modifier.size(36.dp)
    )
    Spacer(Modifier.height(8.dp))
    Text(
        failure?.error ?: (moveState as? MoveState.Success)?.message ?: successFallback,
        style = MaterialTheme.typography.bodyMedium,
        color = tint,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(12.dp))
    // Nothing committed → nothing new to see in game; partial success still offers launch.
    val gameIntent = remember {
        context.packageManager.getLaunchIntentForPackage("com.neowizgames.game.browndust2")
    }
    if (gameIntent != null && anyInstalled) {
        Button(onClick = { context.startActivity(gameIntent) }) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("启动游戏")
        }
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
                                    text = String.format(java.util.Locale.US, "总耗时 %.1f 秒", elapsedSeconds),
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
                                    successFallback = "已装入游戏目录，重启游戏后生效。",
                                    anyInstalled = finalResult.successfulJobs > 0
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

                            // 未完成的备份（T07 整改裁定 3 附加）：与「失败的分组」分开列，
                            // 因为含义不同 —— 这些 mod 装上了，只是卸载时没法本地还原。
                            if (finalResult.unfinishedBackups.isNotEmpty()) {
                                Spacer(Modifier.height(16.dp))
                                UnfinishedBackupList(finalResult.unfinishedBackups)
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
@Composable
private fun UnfinishedBackupList(details: List<FailedJobInfo>) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Text(
        "未完成的备份：",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.tertiary,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "这些分组没能存下原版备份，装入本身不受影响；卸载时需重新下载原版。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(8.dp))
    details.forEach { unfinished ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(8.dp))
                .clickable {
                    clipboardManager.setText(AnnotatedString(unfinished.error))
                    Toast.makeText(context, "信息已复制！", Toast.LENGTH_SHORT).show()
                }
                .padding(12.dp)
        ) {
            Text(
                text = "分组：${unfinished.hashedName.take(16)}...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(Modifier.height(4.dp))
            SelectionContainer {
                Text(
                    text = unfinished.error,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                )
            }
        }
    }
}
@Composable
fun ConvertedOverwriteDialog(
    plan: ConvertedOverwritePlan?,
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
                is BundleScanState.Confirmation -> "需要建立文件索引"
                is BundleScanState.Scanning -> "正在建立文件索引..."
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
                        // -1/-1 是「界面重建恢复」的不确定态：扫描在途但总进度未知
                        // （installScope 里的扫描循环下次回调会带回真实数字）
                        if (s.currentIndex >= 0 && s.totalCount > 0) {
                            Text(
                                "正在扫描 ${s.currentIndex + 1} / ${s.totalCount}",
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center
                            )
                        } else {
                            Text(
                                "正在扫描",
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        // 不确定态（total<=0，界面重建恢复）用 indeterminate 版本：
                        // 进度未知时画假数字反而误导
                        if (s.totalCount > 0) {
                            LinearProgressIndicator(
                                progress = { (s.currentIndex + 1).toFloat()
                                    .coerceAtMost(s.totalCount.toFloat()) / s.totalCount.toFloat() },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
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
                                "有 ${s.failedCount} 个文件索引失败（不影响其余的）。",
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
