package com.bd2toolsbox.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bd2toolsbox.data.model.CdkItem
import com.bd2toolsbox.data.model.RedeemOutcome
import com.bd2toolsbox.data.model.RedeemRecord
import com.bd2toolsbox.data.repository.Bd2RedeemRepository
import com.bd2toolsbox.data.repository.GamekeeRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 兑换码板块 —— 独立于攻略的第三个 tab。
 *
 * 列表来自 GameKee 的 CDK 接口（国际服），一次请求即全量（官方列表也就十来条）。
 * 点条目即复制；填过游戏昵称后每条还能直接「兑换」—— 走游戏官方兑换接口，
 * 奖励由官方直发游戏内邮箱，不用登录任何账号、不用进游戏。已过期的沉底
 * 置灰留着 —— 留着能看出「这个用过了，不是新码失效」；本地记录已兑换成功的
 * 标「已兑换」（官方没有查询接口，只能自己记）。
 */
@Composable
fun CdkScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember(context) { GamekeeRepository.get(context) }
    val redeemRepo = remember(context) { Bd2RedeemRepository.get(context) }
    val clipboard = LocalClipboardManager.current

    var items by remember { mutableStateOf<List<CdkItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    // ---- 官方兑换状态 ----
    var userId by remember { mutableStateOf(redeemRepo.savedUserId()) }
    var redeemed by remember { mutableStateOf(setOf<String>()) }
    var redeemingCode by remember { mutableStateOf<String?>(null) }
    var batchRedeeming by remember { mutableStateOf(false) }
    var batchResult by remember { mutableStateOf<List<RedeemOutcome>?>(null) }
    var history by remember { mutableStateOf<List<RedeemRecord>?>(null) }
    var showHistory by remember { mutableStateOf(false) }

    suspend fun load() {
        loading = true
        error = null
        try {
            items = repo.loadCdk()
        } catch (e: Exception) {
            error = e.message ?: "加载失败"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        load()
        redeemed = redeemRepo.loadHistory().map { it.code }.toSet()
    }

    /** 单码兑换，结果走 Toast；成功后该行标「已兑换」。 */
    fun redeemOne(item: CdkItem) {
        scope.launch {
            redeemingCode = item.code
            try {
                val outcome = redeemRepo.redeem(userId, item.code, item.reward)
                if (outcome.success) redeemed = redeemed + item.code
                Toast.makeText(context, outcome.message, Toast.LENGTH_LONG).show()
            } finally {
                redeemingCode = null
            }
        }
    }

    /** 批量：未过期且本地没换成的码全部交给官方，结果弹层逐条展示。 */
    fun redeemAll() {
        scope.launch {
            batchRedeeming = true
            try {
                val targets = items.orEmpty().filterNot { it.expired || it.code in redeemed }
                batchResult =
                    if (targets.isEmpty()) emptyList()
                    else redeemRepo.redeemAll(userId, targets)
                redeemed = redeemRepo.loadHistory().map { it.code }.toSet()
            } finally {
                batchRedeeming = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "兑换码",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "来自 GameKee · 点条目即复制",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { scope.launch { load() } }, enabled = !loading) {
                Icon(Icons.Default.Refresh, contentDescription = "刷新")
            }
        }

        // ---- 官方兑换区 ----
        // 有兑换在跑时收起全部单码按钮，避免并发触发官方限流
        val busy = batchRedeeming || redeemingCode != null
        val pending = items.orEmpty().filterNot { it.expired || it.code in redeemed }

        OfficialRedeemSection(
            userId = userId,
            onUserId = {
                userId = it
                redeemRepo.saveUserId(it)
            },
            pendingCount = pending.size,
            canRedeemAll = userId.isNotBlank() && pending.isNotEmpty(),
            batchRedeeming = batchRedeeming,
            onRedeemAll = { redeemAll() },
            onShowHistory = {
                scope.launch {
                    history = redeemRepo.loadHistory()
                    showHistory = true
                }
            }
        )

        if (batchResult != null) {
            BatchResultDialog(batchResult!!) { batchResult = null }
        }
        if (showHistory) {
            HistoryDialog(history) { showHistory = false }
        }

        when {
            loading && items == null -> Box(
                Modifier.fillMaxSize(), contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
            error != null && items == null -> Box(
                Modifier.fillMaxSize(), contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "加载失败：$error",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = { scope.launch { load() } }) { Text("重试") }
                }
            }
            items.isNullOrEmpty() -> Box(
                Modifier.fillMaxSize(), contentAlignment = Alignment.Center
            ) {
                Text(
                    "当前没有可用的兑换码",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            else -> {
                val valid = items!!.filterNot { it.expired }
                val expired = items!!.filter { it.expired }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(valid, key = { it.code }) { cdk ->
                        CdkRow(
                            cdk = cdk,
                            redeemable = userId.isNotBlank() && (!busy || redeemingCode == cdk.code),
                            redeeming = redeemingCode == cdk.code,
                            redeemed = cdk.code in redeemed,
                            onClick = {
                                clipboard.setText(AnnotatedString(cdk.code))
                                Toast.makeText(context, "已复制 ${cdk.code}", Toast.LENGTH_SHORT).show()
                            },
                            onRedeem = { redeemOne(cdk) }
                        )
                        HorizontalDivider()
                    }
                    if (expired.isNotEmpty()) {
                        item(key = "expired-head") {
                            Text(
                                "已过期（留着对照，不能再换）",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
                            )
                        }
                        items(expired, key = { "e-" + it.code }) { cdk ->
                            CdkRow(cdk, enabled = false)
                            HorizontalDivider()
                        }
                    }
                    item(key = "footer") {
                        Text(
                            "数据来自 GameKee 棕色尘埃2分区 · 兑换走游戏官方接口",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CdkRow(
    cdk: CdkItem,
    enabled: Boolean = true,
    redeemable: Boolean = false,
    redeeming: Boolean = false,
    redeemed: Boolean = false,
    onClick: () -> Unit = {},
    onRedeem: () -> Unit = {}
) {
    val expiry = if (cdk.endAt <= 0) "长期有效" else "有效期至 ${formatDate(cdk.endAt)}"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.CardGiftcard,
            contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(
                cdk.code,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (cdk.reward.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    cdk.reward,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                expiry,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (enabled) {
            if (redeemed) {
                RedeemChip(text = "已兑换", emphasized = false)
                Spacer(Modifier.width(6.dp))
            } else if (redeemable) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.clickable(enabled = !redeeming, onClick = onRedeem)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (redeeming) {
                            CircularProgressIndicator(
                                Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "兑换中",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        } else {
                            Text(
                                "兑换",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
                Spacer(Modifier.width(6.dp))
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.ContentCopy, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "复制",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }
}

/** 右侧小胶囊（「已兑换」这类静态标签用）。 */
@Composable
private fun RedeemChip(text: String, emphasized: Boolean) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (emphasized) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = if (emphasized) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

private fun formatDate(epochSeconds: Long): String =
    dateFormat.format(Date(epochSeconds * 1000))

/**
 * 官方兑换区：一个昵称输入框 + 一键兑换 + 记录入口。官方接口不需要任何登录，
 * 昵称记在本地，下次进来还在。兑换中或没有可换的码时按钮收起/置灰。
 */
@Composable
private fun OfficialRedeemSection(
    userId: String,
    onUserId: (String) -> Unit,
    pendingCount: Int,
    canRedeemAll: Boolean,
    batchRedeeming: Boolean,
    onRedeemAll: () -> Unit,
    onShowHistory: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                "自动兑换（官方通道）",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "填游戏内昵称即可，奖励由官方直发游戏内邮箱，不用登录、不用进游戏。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = userId,
                onValueChange = { onUserId(it.take(24)) },
                singleLine = true,
                placeholder = { Text("游戏内昵称", style = MaterialTheme.typography.bodySmall) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onRedeemAll, enabled = canRedeemAll && !batchRedeeming) {
                    if (batchRedeeming) {
                        CircularProgressIndicator(
                            Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("兑换中…")
                    } else if (pendingCount > 0) {
                        Text("一键兑换（$pendingCount 个可用）")
                    } else {
                        Text("一键兑换全部可用码")
                    }
                }
                Spacer(Modifier.width(10.dp))
                TextButton(onClick = onShowHistory) { Text("兑换记录") }
            }
        }
    }
}

/** 批量兑换的结果：逐条 成功 / 失败原因。 */
@Composable
private fun BatchResultDialog(outcomes: List<RedeemOutcome>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("兑换结果") },
        text = {
            if (outcomes.isEmpty()) {
                Text("没有需要兑换的新兑换码（已兑换过的不重复提交）")
            } else {
                Column {
                    if (outcomes.any { it.success }) {
                        Text(
                            "成功的奖励已发往游戏内邮箱，重启游戏后查收。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(outcomes, key = { it.code }) { o ->
                            Row(
                                Modifier.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    o.code,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    if (o.success) "成功" else o.message,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (o.success) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.error
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("好") } }
    )
}

/** 本地兑换记录。官方没有查询接口，只记成功记录（新 → 旧）。 */
@Composable
private fun HistoryDialog(history: List<RedeemRecord>?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("兑换记录") },
        text = {
            when {
                history == null -> Box(
                    Modifier.size(200.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator(Modifier.size(32.dp)) }
                history.isEmpty() -> Text("还没有成功兑换的记录")
                else -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(history, key = { "${it.at}-${it.code}" }) { e ->
                        Row(Modifier.padding(vertical = 4.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(e.code, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                if (e.reward.isNotBlank()) {
                                    Text(
                                        e.reward,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Text(
                                formatDate(e.at),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}
