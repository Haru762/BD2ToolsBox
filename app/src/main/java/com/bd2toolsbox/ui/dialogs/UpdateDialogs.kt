package com.bd2toolsbox.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bd2toolsbox.data.model.*
import com.bd2toolsbox.data.repository.UpdateRepository

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
@Composable
fun ImageViewerDialog(
    imagePaths: List<String>,
    onDismiss: () -> Unit
) {
    if (imagePaths.isEmpty()) return
    var index by remember { mutableIntStateOf(0) }
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
