package com.bd2toolsbox.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bd2toolsbox.data.model.InstallJob
import com.bd2toolsbox.data.model.JobStatus

/** 设置弹层里的目录选择行：标签 + 当前值 + 文件夹图标，整行可点。 */
@Composable
fun SelectionRow(label: String, value: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium,
             color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.FolderOpen, contentDescription = "选择",
                 tint = MaterialTheme.colorScheme.secondary)
        }
    }
}

/** 转换/装入弹层里的一条任务：状态图标 + 进度消息 + 进行中的进度条。 */
@Composable
fun InstallJobRow(installJob: InstallJob) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text("目标：${installJob.job.hashedName.take(12)}...",
             style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val statusIcon = when (installJob.status) {
                is JobStatus.Pending -> Icons.Default.Schedule
                is JobStatus.Downloading -> Icons.Default.Download
                is JobStatus.Installing -> Icons.Default.Build
                is JobStatus.Finished -> Icons.Default.CheckCircle
                is JobStatus.Failed -> Icons.Default.Error
            }
            Icon(statusIcon, contentDescription = "状态", modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                val progressMessage = when (val status = installJob.status) {
                    is JobStatus.Downloading -> status.progressMessage
                    is JobStatus.Installing -> status.progressMessage
                    is JobStatus.Failed -> status.displayMessage
                    is JobStatus.Finished -> "转换成功！"
                    is JobStatus.Pending -> "排队等待中..."
                }
                Text(progressMessage, style = MaterialTheme.typography.bodySmall)
                if (installJob.status is JobStatus.Downloading ||
                    installJob.status is JobStatus.Installing) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
