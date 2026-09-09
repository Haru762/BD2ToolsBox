package com.bd2toolsbox.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import kotlin.coroutines.cancellation.CancellationException

/**
 * 兑换码板块 —— 独立于攻略的第三个 tab。
 *
 * 列表来自 GameKee 的 CDK 接口（国际服），一次请求即全量（官方列表也就十来条）。
 * 点条目即复制。添加过游戏昵称（可多个、本地持久化、删掉才停）后，每次拉到
 * 新兑换码都会自动走游戏官方兑换接口，奖励由官方直发游戏内邮箱，不用登录任何
 * 账号、不用进游戏。已过期的沉底置灰留着 —— 留着能看出「这个用过了，不是新码
 * 失效」；全部账号都处理完（成功或终局失败，如在官网已换过）的码标「已兑换」、
 * 不留兑换按钮（官方没有查询接口，只能自己记）。
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
    var accounts by remember { mutableStateOf(redeemRepo.savedUserIds()) }
    var newAccount by remember { mutableStateOf("") }
    /** 全部账号都处理完（成功或终局失败）的码：行级「已兑换」标记与一键兑换计数
     *  都按它——处理完的不留兑换按钮（留着只会误导人以为还有的换）。 */
    var settledAll by remember { mutableStateOf(setOf<String>()) }
    var redeemingCode by remember { mutableStateOf<String?>(null) }
    var batchRedeeming by remember { mutableStateOf(false) }
    var batchResult by remember { mutableStateOf<List<Pair<String, RedeemOutcome>>?>(null) }
    var history by remember { mutableStateOf<List<RedeemRecord>?>(null) }
    var showHistory by remember { mutableStateOf(false) }

    /** 从本地记录重算行级标记（读的是几 KB 的本地文件，沿用本屏既有模式）。 */
    fun refreshMarks() {
        accounts = redeemRepo.savedUserIds()
        // history 一份读进来按账号分账，别每个账号各读一遍文件
        val all = redeemRepo.loadHistory()
        settledAll = if (accounts.isEmpty()) emptySet()
        else accounts.map { redeemRepo.autoDoneCodes(it) +
                all.filter { h -> h.userId == it }.map { h -> h.code }.toSet() }
            .reduce { a, b -> a intersect b }
    }

    suspend fun load() {
        loading = true
        error = null
        try {
            items = repo.loadCdk()
            // 拉到清单就自动兑换：每个账号没处理完的码补一遍。终局失败（过期/无效/
            // 已兑换……）不重复提交，网络类失败下次拉到再试；没有账号或全处理完时静默。
            val outcomes = redeemRepo.autoRedeem(items.orEmpty())
            if (outcomes.isNotEmpty()) {
                val ok = outcomes.count { it.second.success }
                val fail = outcomes.size - ok
                Toast.makeText(
                    context,
                    if (fail == 0) "自动兑换完成：成功 $ok 条"
                    else "自动兑换完成：成功 $ok 条，失败 $fail 条",
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: CancellationException) {
            throw e   // 切 tab/退出时的协程取消不是加载失败，别吞
        } catch (e: Exception) {
            error = e.message ?: "加载失败"
        } finally {
            loading = false
        }
        refreshMarks()
    }

    LaunchedEffect(Unit) {
        load()
    }

    /** 单码手动兑换：所有还没处理完该码的账号各来一次；入口只对未处理完的码出现。 */
    fun redeemOne(item: CdkItem) {
        scope.launch {
            redeemingCode = item.code
            try {
                val results = redeemRepo.redeemCodeForAll(item.code, item.reward)
                val msg = when {
                    results.isEmpty() -> "这个码所有账号都处理完了"
                    results.size == 1 -> results.first().second.message
                    else -> "${results.count { it.second.success }} / ${results.size} 个账号成功"
                }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            } finally {
                redeemingCode = null
                refreshMarks()
            }
        }
    }

    /** 一键兑换 = 手动触发同一套自动逻辑，结果弹层逐条展示。 */
    fun redeemAll() {
        scope.launch {
            batchRedeeming = true
            try {
                batchResult = redeemRepo.autoRedeem(items.orEmpty())
            } finally {
                batchRedeeming = false
                refreshMarks()
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
        // 有兑换在跑（含拉清单后的自动兑换那一段）时收起全部单码按钮，避免并发触发官方限流
        val busy = loading || batchRedeeming || redeemingCode != null
        val pending = items.orEmpty().filterNot { it.expired || it.code in settledAll }

        OfficialRedeemSection(
            accounts = accounts,
            newAccount = newAccount,
            onNewAccount = { newAccount = it.take(24) },
            onAddAccount = {
                if (redeemRepo.addUserId(newAccount)) {
                    newAccount = ""
                    refreshMarks()
                    // 刚加的账号对这一页的码多半还没换过：直接把同一套兑换跑起来（弹层
                    // 给结果），不用等下次进页的自动兑换
                    if (items.orEmpty().any { !it.expired && it.code !in settledAll }) {
                        redeemAll()
                    }
                } else {
                    Toast.makeText(context, "账号为空，或已经添加过了", Toast.LENGTH_SHORT).show()
                }
            },
            onRemoveAccount = { acc ->
                scope.launch {
                    redeemRepo.removeUserId(acc)
                    refreshMarks()
                }
            },
            pendingCount = pending.size,
            canRedeemAll = accounts.isNotEmpty() && pending.isNotEmpty(),
            busy = busy,
            batchRedeeming = batchRedeeming,
            autoRunning = loading && items != null,
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
                            redeemable = accounts.isNotEmpty() && (!busy || redeemingCode == cdk.code),
                            redeeming = redeemingCode == cdk.code,
                            redeemed = cdk.code in settledAll,
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
 * 官方兑换区：账号列表（可多个，本地持久化，删了才停）+ 一键兑换 + 记录入口。
 * 添加过账号后，每次拉到新兑换码都会自动兑换，奖励由官方直发游戏内邮箱，不用
 * 登录、不用进游戏。兑换中或没有可换的码时按钮收起/置灰。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OfficialRedeemSection(
    accounts: List<String>,
    newAccount: String,
    onNewAccount: (String) -> Unit,
    onAddAccount: () -> Unit,
    onRemoveAccount: (String) -> Unit,
    pendingCount: Int,
    canRedeemAll: Boolean,
    busy: Boolean,
    batchRedeeming: Boolean,
    autoRunning: Boolean,
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
                "自动兑换",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            if (accounts.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    accounts.forEach { acc ->
                        AccountChip(
                            name = acc,
                            enabled = !busy,
                            onRemove = { onRemoveAccount(acc) }
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newAccount,
                    onValueChange = onNewAccount,
                    singleLine = true,
                    placeholder = {
                        Text("游戏内昵称，可添加多个", style = MaterialTheme.typography.bodySmall)
                    },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = onAddAccount,
                    enabled = !busy && newAccount.isNotBlank()
                ) { Text("添加") }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onRedeemAll, enabled = canRedeemAll && !busy) {
                    // 手动一键与进页自动兑换都给转圈——自动那段最长可达几十秒，不指示
                    // 的话用户只会看到按钮全部消失，像卡死
                    if (batchRedeeming || autoRunning) {
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
                        Text("没有待兑换的码")
                    }
                }
                Spacer(Modifier.width(10.dp))
                TextButton(onClick = onShowHistory) { Text("兑换记录") }
            }
        }
    }
}

/** 已存账号胶囊：点右侧 × 删除（删了该账号才停止自动兑换）。兑换进行中禁用。 */
@Composable
private fun AccountChip(name: String, enabled: Boolean, onRemove: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 160.dp)
            )
            Icon(
                Icons.Default.Close,
                contentDescription = "删除账号 $name",
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(enabled = enabled) { onRemove() }
                    .padding(8.dp)
            )
        }
    }
}

/** 批量兑换的结果：逐条 账号 + 码 + 成功/失败原因。 */
@Composable
private fun BatchResultDialog(
    outcomes: List<Pair<String, RedeemOutcome>>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("兑换结果") },
        text = {
            if (outcomes.isEmpty()) {
                Text("没有需要兑换的新兑换码（处理过的不再重复提交）")
            } else {
                Column {
                    if (outcomes.any { it.second.success }) {
                        Text(
                            "成功的奖励已发往游戏内邮箱，重启游戏后查收。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(outcomes, key = { "${it.first}-${it.second.code}" }) { (account, o) ->
                            Row(
                                Modifier.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        o.code,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (account.isNotBlank()) {
                                        Text(
                                            account,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
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
                    items(history, key = { "${it.at}-${it.code}-${it.userId}" }) { e ->
                        Row(Modifier.padding(vertical = 4.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(e.code, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                if (e.reward.isNotBlank() || e.userId.isNotBlank()) {
                                    Text(
                                        listOf(e.reward, e.userId).filter { it.isNotBlank() }
                                            .joinToString(" · "),
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
