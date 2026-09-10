package com.bd2toolsbox.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bd2toolsbox.data.model.ModCategory
import com.bd2toolsbox.data.model.ModInfo
import com.bd2toolsbox.data.model.ModInstallState
import com.bd2toolsbox.data.model.ModKind
import com.bd2toolsbox.data.model.categoryOf
import com.bd2toolsbox.data.repository.CharacterMetaRepository
import com.bd2toolsbox.ui.components.CharacterAvatar
import com.bd2toolsbox.ui.components.SkinThumbnail

/**
 * 一个可操作的条目 —— 同一套皮肤的「PC 源文件」与「已转换产物」合并后的结果。
 *
 * 合并的理由：两者是同一个东西的两个阶段，分开列会让同一套皮肤出现两行，
 * 正是原先按加工阶段分两页的毛病换个地方重现。这里把「转换了没有」降为一个 tag。
 *
 * [source] 是 PC 源文件，[converted] 是已转换产物，至少有一个非空。
 * 装入时优先用产物（不必重打包），没有产物就走转换流程。
 */
data class SkinEntry(
    val costume: String,
    val category: ModCategory,
    val targetHash: String?,
    val source: ModInfo?,
    val converted: ModInfo?,
    /** 同一个 bundle 被多少个 mod 指向（含其他角色的），用于说明共用关系 */
    val sharedTotal: Int
) {
    /** 拿来做预览、装入等操作的代表条目。产物优先 —— 它不需要重打包。 */
    val primary: ModInfo get() = converted ?: source!!
    val installed: Boolean
        get() = source?.installState == ModInstallState.INSTALLED ||
                converted?.installState == ModInstallState.INSTALLED
    val hasConverted: Boolean get() = converted != null
    val onlyConverted: Boolean get() = converted != null && source == null
}

/**
 * 角色卡片 —— 点开某个角色后看到的东西。
 *
 * 按「过场动画 / 立绘 / 心契之约」分三段，段内**平铺**，有多少列多少，不折叠。
 * 每条给出皮肤名、来源、状态 tag，以及「预览」和「装入 / 卸载」两个动作。
 *
 * 「装入」内部自动判断要不要先转换 —— 用户不该为了装一个 mod 先自己跨过「转换」这道门。
 *
 * 未装入的条目左侧有勾选框，选中后顶部出现一条操作栏，可以一次装好几套皮肤 ——
 * 一个角色常有五六个皮肤想一起装，逐个点「装入」要等五六轮转换。
 * 已装入的条目不给勾选框：它们能做的是「卸载」，和批量装入混在一起语义会打架。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterSheet(
    characterName: String,
    entries: List<SkinEntry>,
    onPreview: (ModInfo) -> Unit,
    onInstall: (SkinEntry) -> Unit,
    onInstallBatch: (List<SkinEntry>) -> Unit,
    onRemove: (ModInfo) -> Unit,
    onDismiss: () -> Unit
) {
    // 与设置面板同一个坑：M3 1.2.1 的 BottomSheetDefaults.ContainerColor 取自
    // colorScheme.surface，壁纸模式下那是透明的，面板会连着底下的列表一起透出来。
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        val context = LocalContext.current
        val meta = remember(context) { CharacterMetaRepository.get(context) }
        val nameCn = remember(characterName) {
            meta.forCharacter(characterName)?.nameCn.orEmpty()
        }

        // 选中集用 mod 的 uri 而不是下标：entries 会随扫描结果重建，
        // 下标会指到别的皮肤上去。卡片关掉即清空，不必往 ViewModel 里存。
        // 用 mutableStateListOf 而不是 mutableStateSetOf：后者要 Compose 1.7，
        // 这个工程的 BOM 是 2024.06。卡片里十几行，contains 是 O(n) 也无所谓。
        val selected = remember { mutableStateListOf<String>() }
        val selectable = remember(entries) { entries.filterNot { it.installed } }
        val chosen = remember(entries, selected.size) {
            selectable.filter { it.primary.uri.toString() in selected }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CharacterAvatar(characterName = characterName, size = 44.dp, cornerRadius = 10.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        characterName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (nameCn.isNotBlank()) {
                        Text(
                            nameCn,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (entries.isNotEmpty()) {
                    Text(
                        "${entries.size} 项",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 选中后出现的操作栏。跟着内容滚（卡片里一般十几行，滚不了多远），
            // 换成固定吸顶要给 ModalBottomSheet 定高，代价大于收益。
            if (chosen.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "已选 ${chosen.size} 项",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { selected.clear() }) { Text("取消") }
                        Button(onClick = { onInstallBatch(chosen) }) {
                            Text("装入 ${chosen.size} 项")
                        }
                    }
                }
            } else if (selectable.size > 1) {
                // 没选东西时给一个「全选」入口。只有一条可装时不显示 ——
                // 那时直接点那行的「装入」更快。
                TextButton(
                    onClick = {
                        selectable.forEach {
                            val k = it.primary.uri.toString()
                            if (k !in selected) selected.add(k)
                        }
                    },
                    modifier = Modifier.padding(start = 12.dp)
                ) {
                    Text("全选 ${selectable.size} 项未装入的", style = MaterialTheme.typography.labelMedium)
                }
            }

            // 同样不能用「空就 return@Column」——Composable lambda 里提前返回会让
            // slot table 的 group 无法闭合，实测直接崩。用 if/else 表达。
            if (entries.isEmpty()) {
                Text(
                    "这个角色还没有 mod。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 32.dp)
                )
            } else {
                ModCategory.entries.forEach { cat ->
                    val inCat = entries.filter { it.category == cat }
                    if (inCat.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                cat.label,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            HorizontalDivider(modifier = Modifier.padding(start = 10.dp).weight(1f))
                        }

                        // 共用同一资源包时说明一下。修复「分次装入会互相覆盖」之后它们已能共存，
                        // 所以这是陈述事实、解释为什么会看到别人的名字，不是警告。
                        val shared = inCat.firstOrNull { it.sharedTotal > 1 }
                        if (shared != null) {
                            Text(
                                "这一类的资源与其他角色打包在一起（共 ${shared.sharedTotal} 个候选），" +
                                    "可以各装各的、互不影响。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 6.dp)
                            )
                        }

                        inCat.forEach { e ->
                            val key = e.primary.uri.toString()
                            SkinRow(
                                entry = e,
                                checked = key in selected,
                                onCheckedChange = { on ->
                                    if (on) { if (key !in selected) selected.add(key) } else selected.remove(key)
                                },
                                onPreview = { onPreview(e.primary) },
                                onInstall = { onInstall(e) },
                                onRemove = { onRemove(e.primary) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SkinRow(
    entry: SkinEntry,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onPreview: () -> Unit,
    onInstall: () -> Unit,
    onRemove: () -> Unit
) {
    val context = LocalContext.current
    val meta = remember(context) { CharacterMetaRepository.get(context) }
    // 卡片是点开才建的（一次最多十几行），不像 95 行的列表那么怕卡首帧，
    // 所以这里可以直接查、必要时顺手把表建起来。
    val fileId = entry.primary.resolvedFamilyKey
    val costumeCn = remember(fileId) {
        (meta.forFileId(fileId) ?: meta.forDatingId(fileId))?.costumeCn.orEmpty()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 20.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 已装入的不给勾选框，占位保持缩略图对齐。它们的动作是「卸载」，
        // 混进批量装入的选择里只会让「装入 N 项」的 N 不可信。
        if (entry.installed) {
            Spacer(Modifier.width(48.dp))
        } else {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        }

        SkinThumbnail(fileId = fileId, size = 44.dp, contentDescription = entry.costume)

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                // 有中文皮肤名就并排显示。皮肤名是用户在游戏里看到的说法，
                // 而英文名才对得上 mod 文件夹，两个都留着。
                if (costumeCn.isBlank()) entry.costume else "${entry.costume}  $costumeCn",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                entry.primary.name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            StatusTag(entry)
        }

        // 预览改成显式按钮。以前只能长按条目触发，那个入口太隐蔽，多数人不会发现。
        TextButton(onClick = onPreview, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Icon(Icons.Default.PlayCircleOutline, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(3.dp))
            Text("预览", style = MaterialTheme.typography.labelMedium)
        }

        if (entry.installed) {
            TextButton(
                onClick = onRemove,
                contentPadding = PaddingValues(horizontal = 8.dp),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) { Text("卸载", style = MaterialTheme.typography.labelMedium) }
        } else {
            Button(onClick = onInstall, contentPadding = PaddingValues(horizontal = 14.dp)) {
                Text("装入", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun StatusTag(entry: SkinEntry) {
    val (label, color) = when {
        entry.installed -> "生效中" to MaterialTheme.colorScheme.primary
        entry.onlyConverted -> "仅产物" to MaterialTheme.colorScheme.tertiary
        entry.hasConverted -> "已转换" to MaterialTheme.colorScheme.secondary
        else -> "未转换" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
        )
    }
}

/**
 * 把某个角色的 mod 汇总成卡片条目：同一 bundle + 同一资源身份的源与产物合并成一条。
 *
 * 合并键用 (targetHash, resolvedFamilyKey) 而不是只用 targetHash —— 一个 bundle 里
 * 常有多个角色/多个资源（实测某立绘包被 45 个角色共用），只按 bundle 合并会把不相干的
 * 东西并成一条。
 *
 * 同一键下若有多个 PC 源（不同作者的同款），各自单独成条 —— 它们是竞争关系，
 * 用户要在其中挑一个，并成一条就没法选了。
 */
fun buildSkinEntries(characterName: String, allMods: List<ModInfo>): List<SkinEntry> {
    // 异常条目不进皮肤页：装入入口（installSingle 等）都会拦它，出现在这里只会
    // 让用户点了「装入」然后毫无反应。角色表更新/用户删源文件后会自然归位。
    // 「待更新」同理（游戏更新后 hash 目录名落后）：它的更新入口在「全部」视图的
    // 待更新区，在这里出现同样只会点了没反应。
    val mine = allMods.filter {
        it.character.trim() == characterName && it.defect == null && it.outdatedCurrentHash == null
    }
    if (mine.isEmpty()) return emptyList()

    // 每个 bundle 总共被多少 mod 指向（跨角色），用于说明共用关系
    val sharedTotals = allMods.groupingBy { it.targetHash ?: "" }.eachCount()

    val sources = mine.filter { it.kind == ModKind.PC_SOURCE }
    val converted = mine.filter { it.kind == ModKind.CONVERTED_BUNDLE }
    val usedConverted = HashSet<String>()

    val out = ArrayList<SkinEntry>()
    for (s in sources) {
        val key = s.targetHash to s.resolvedFamilyKey
        // 找一个还没被认领的同键产物配给它
        val c = converted.firstOrNull {
            it.uri.toString() !in usedConverted &&
                it.targetHash == key.first && it.resolvedFamilyKey == key.second
        }
        if (c != null) usedConverted.add(c.uri.toString())
        out.add(
            SkinEntry(
                costume = s.costume.trim().ifBlank { s.name },
                category = categoryOf(s.type, s.costume),
                targetHash = s.targetHash,
                source = s,
                converted = c,
                sharedTotal = sharedTotals[s.targetHash ?: ""] ?: 1
            )
        )
    }
    // 配不上任何源文件的产物单独列出来 —— 源可能已被删除或换了目录
    for (c in converted) {
        if (c.uri.toString() in usedConverted) continue
        out.add(
            SkinEntry(
                costume = c.costume.trim().ifBlank { c.name },
                category = categoryOf(c.type, c.costume),
                targetHash = c.targetHash,
                source = null,
                converted = c,
                sharedTotal = sharedTotals[c.targetHash ?: ""] ?: 1
            )
        )
    }
    return out.sortedWith(compareBy({ it.category.ordinal }, { it.costume.lowercase() }))
}
