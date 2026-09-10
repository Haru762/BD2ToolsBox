package com.bd2toolsbox.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bd2toolsbox.data.model.ModCategory
import com.bd2toolsbox.data.model.ModInfo
import com.bd2toolsbox.data.model.ModInstallState
import com.bd2toolsbox.data.model.categoryOf
import com.bd2toolsbox.data.model.isUnknownCharacter
import com.bd2toolsbox.data.repository.CharacterMetaRepository
import com.bd2toolsbox.ui.components.CharacterAvatar
import kotlinx.coroutines.launch

/**
 * 一个角色在列表里的呈现数据。
 *
 * [counts] 按类别统计该角色有多少个 mod，[installedCount] 是其中生效中的个数。
 * 两者都从已扫描到的 mod 算出来；角色本身则来自角色表，所以没有 mod 的角色也会列出。
 */
data class CharacterEntry(
    val name: String,
    val counts: Map<ModCategory, Int>,
    val installedCount: Int,
    /** NPC（商店/路人模型）：列表里作为单独分区排在最下面，不与可玩角色混排。 */
    val isNpc: Boolean = false
) {
    val total: Int get() = counts.values.sum()
    val hasMods: Boolean get() = total > 0
}

/**
 * 角色列表的筛选维度。
 *
 * 性别与联动来自随包的 `character_meta.json`（见 [CharacterMetaRepository]）——
 * 它覆盖 83 个角色，那 12 个 NPC（Guild Girl、Female Researcher 等）没有性别，
 * 所以「男性 + 女性」不等于全部，这三项都是「筛出确定符合的」。
 */
enum class CharacterFilter(val label: String, val ready: Boolean) {
    ALL("全部", true),
    HAS_MODS("有 mod", true),
    INSTALLED("已生效", true),
    MALE("男性", true),
    FEMALE("女性", true),
    COLLAB("联动", true),
}

/**
 * 按角色浏览的主界面。
 *
 * 起因：原先按「已转换产物 / PC 源文件」分成两页，那是按 mod 所处的加工阶段分的，
 * 而用户想的是「这个角色这套皮肤我有没有 mod、装没装」——「转换了没有」只是中间状态。
 * 所以这里以角色为主轴，转换与否降级成条目上的一个 tag（见 [CharacterSheet]）。
 *
 * 角色全量来自角色表（95 个），不只列有 mod 的 —— 这样也能看出自己还缺谁。
 */
@Composable
fun CharacterScreen(
    entries: List<CharacterEntry>,
    filter: CharacterFilter,
    onPickCharacter: (String) -> Unit,
    avatarSyncFailed: Boolean = false,
    onRetryAvatarSync: () -> Unit = {}
) {
    val context = LocalContext.current
    val meta = remember(context) { CharacterMetaRepository.get(context) }

    val shown = remember(entries, filter, meta) {
        when (filter) {
            CharacterFilter.HAS_MODS -> entries.filter { it.hasMods }
            CharacterFilter.INSTALLED -> entries.filter { it.installedCount > 0 }
            // 附加数据里查不到的角色（那 12 个 NPC）在这三项下不出现 ——
            // 与其猜一个，不如让「男性」就只有确定是男性的那些。
            CharacterFilter.MALE -> entries.filter { meta.forCharacter(it.name)?.isMale == true }
            CharacterFilter.FEMALE -> entries.filter { meta.forCharacter(it.name)?.isFemale == true }
            CharacterFilter.COLLAB -> entries.filter { meta.forCharacter(it.name)?.isCollab == true }
            else -> entries
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 筛选原先是这里的一整行 chip，已收进顶栏的筛选图标（点开是二级菜单）——
        // 六个 chip 占掉一行高度，而它们并非每次都要改。

        // 这里必须用 if/else 而不是「空就 return@Column」：在 Composable 的 lambda 里提前
        // 返回会让 slot table 的 group 无法正确闭合，实测直接崩在
        // SlotTableKt.key（ArrayIndexOutOfBoundsException）。
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    when (filter) {
                        CharacterFilter.HAS_MODS -> "还没有任何 mod\n选好 mod 文件夹后会自动归到各角色下"
                        CharacterFilter.INSTALLED -> "还没有生效中的 mod"
                        CharacterFilter.MALE -> "没有匹配的男性角色"
                        CharacterFilter.FEMALE -> "没有匹配的女性角色"
                        CharacterFilter.COLLAB -> "没有匹配的联动角色"
                        else -> "角色表读取失败，请重启应用重试"
                    },
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            val listState = rememberLazyListState()
            val scope = rememberCoroutineScope()
            // NPC（商店/路人）不参与可玩角色的首字母混排，从整份里拆出来单独沉底：
            // 数据层已按「可玩在前、NPC 在后」排好序，这里只按 isNpc 切成两段渲染
            val playable = remember(shown) { shown.filterNot { it.isNpc } }
            val npc = remember(shown) { shown.filter { it.isNpc } }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Row(Modifier.fillMaxSize()) {
                    LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                        items(playable, key = { it.name }) { entry ->
                            CharacterRow(entry = entry, onClick = { onPickCharacter(entry.name) })
                            HorizontalDivider()
                        }
                        // 分区头独立占一格（key 固定，与角色名不会撞）；有 NPC 才插，免得空头占高度
                        if (npc.isNotEmpty()) {
                            item(key = "npc-header") {
                                Text(
                                    "NPC（非可玩角色）",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
                                )
                            }
                            items(npc, key = { it.name }) { entry ->
                                CharacterRow(entry = entry, onClick = { onPickCharacter(entry.name) })
                                HorizontalDivider()
                            }
                        }
                    }
                    // 95 个角色靠手划太慢，右侧给一条首字母索引
                    AlphabetIndex(
                        letters = remember(shown) { shown.map { initialOf(it.name) }.distinct() },
                        onPick = { letter ->
                            var target = playable.indexOfFirst { initialOf(it.name) == letter }
                            // 该首字母只在 NPC 分区出现时，去 npc 段里找；渲染序列里 npc 段
                            // 前有 header 占了 1 格，下标要加 playable.size + 1 才指得准
                            if (target < 0) {
                                val i = npc.indexOfFirst { initialOf(it.name) == letter }
                                if (i >= 0) target = playable.size + 1 + i
                            }
                            if (target >= 0) scope.launch { listState.animateScrollToItem(target) }
                        }
                    )
                }

                // 整趟预取都没拿到图时才出现，压在列表正中：一片灰人形图标总得有个说法。
                // 它只画不拦：底下的列表照常滚、照常点，已缓存的头像也照常显示在四周。
                if (avatarSyncFailed) {
                    AvatarSyncHint(onRetry = onRetryAvatarSync)
                }
            }
        }
    }
}

/**
 * 「请检查网络」提示。
 *
 * 头像图源在 GitHub（raw.githubusercontent），国内直连常常整趟都拿不到 ——
 * 那时列表里是一整屏灰人形，看起来跟功能坏了一模一样。这里说清原因，
 * 并留一条重试的路：用户把网络弄好之后不该只能靠重启应用。
 */
@Composable
private fun AvatarSyncHint(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .shadow(4.dp, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onRetry)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "请检查网络",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "头像图源在 GitHub，取不到时显示占位图标",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "点按重试",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

private fun initialOf(name: String): Char {
    val c = name.trim().firstOrNull() ?: '#'
    return if (c.isLetter()) c.uppercaseChar() else '#'
}

@Composable
private fun CharacterRow(entry: CharacterEntry, onClick: () -> Unit) {
    val context = LocalContext.current
    val meta = remember(context) { CharacterMetaRepository.get(context) }
    // peek 而非 forCharacter：这里在 composition 里，不能为了一个中文名去读 52 KB 的
    // assets json。表由筛选/头像那条路在协程里建好，之后这里就是 O(1) 命中。
    val nameCn = remember(entry.name) { meta.peekCharacter(entry.name)?.nameCn.orEmpty() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CharacterAvatar(characterName = entry.name, size = 40.dp)

        Spacer(Modifier.width(14.dp))

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (entry.hasMods) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (entry.hasMods) Color.Unspecified
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // 中文名跟在英文名后面而不是取代它：mod 的文件夹名、bundle、
                // 搜索关键字全是英文，只显示中文反而对不上手里的文件。
                if (nameCn.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        nameCn,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (entry.hasMods) {
                Spacer(Modifier.height(3.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ModCategory.entries.forEach { cat ->
                        val n = entry.counts[cat] ?: 0
                        if (n > 0) CategoryBadge(cat, n)
                    }
                }
            }
        }

        if (entry.installedCount > 0) {
            Badge(containerColor = MaterialTheme.colorScheme.primary) {
                Text("生效 ${entry.installedCount}")
            }
        } else if (entry.hasMods) {
            Text(
                "${entry.total} 项",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CategoryBadge(category: ModCategory, count: Int) {
    // 只取类别名的头一个字（过/立/心/其），横向空间有限而三类一眼可辨
    val short = category.label.take(1)
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            "$short$count",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
        )
    }
}

/**
 * 右侧首字母索引条。
 *
 * Compose 没有内置滚动条，所以自己画。做成字母而不是纯滑块，是因为角色名是英文、
 * 用户找的是「Justia 在哪」，按字母比按百分比位置直观。支持点按与竖向拖动。
 */
@Composable
private fun AlphabetIndex(letters: List<Char>, onPick: (Char) -> Unit) {
    if (letters.size < 2) return
    var height by remember { mutableStateOf(1) }
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(26.dp)
            .padding(vertical = 6.dp)
            .onSizeChanged { height = it.height.coerceAtLeast(1) }
            .pointerInput(letters) {
                detectVerticalDragGestures { change, _ ->
                    val i = ((change.position.y / height) * letters.size)
                        .toInt().coerceIn(0, letters.size - 1)
                    onPick(letters[i])
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        letters.forEach { ch ->
            Text(
                ch.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onPick(ch) }
            )
        }
    }
}

/**
 * 把 mod 列表汇总成角色条目。
 *
 * 角色全量来自角色表，mod 数量从已扫描的 mod 里统计；认不出角色的 mod 不在这里出现
 * （它们归到「全部」视图，见 ModScreen 的未识别分组）。扫描判为异常的同样不进 ——
 * 它连装都没法装，处理入口在「全部」视图顶部的异常区，不是这里。
 */
fun buildCharacterEntries(
    allCharacters: List<String>,
    npcCharacters: List<String>,
    mods: List<ModInfo>
): List<CharacterEntry> {
    val counts = HashMap<String, HashMap<ModCategory, Int>>()
    val installed = HashMap<String, Int>()
    for (m in mods) {
        if (isUnknownCharacter(m.character)) continue
        // 异常条目同样跳过：它不在任何角色下正常生效，计入只会让「这个角色有几个 mod」
        // 虚高 —— 处理在「全部」视图顶部的异常区（删除源文件），按角色计数不带它。
        // 「待更新」（游戏更新后 hash 目录名落后）也一样：它的处理入口是「全部」视图
        // 的待更新区（一键改名），在这里既看不见更新按钮、装入又会被拦。
        if (m.defect != null || m.outdatedCurrentHash != null) continue
        val name = m.character.trim()
        val cat = categoryOf(m.type, m.costume)
        counts.getOrPut(name) { HashMap() }.merge(cat, 1, Int::plus)
        if (m.installState == ModInstallState.INSTALLED) {
            installed.merge(name, 1, Int::plus)
        }
    }
    // 角色表里没收录、但 mod 解析出了名字的，也补进来，否则它的 mod 无处可寻
    val names = LinkedHashSet(allCharacters).apply { addAll(counts.keys) }
    val playable = names.map { n ->
        CharacterEntry(name = n, counts = counts[n] ?: emptyMap(), installedCount = installed[n] ?: 0)
    }.sortedBy { it.name.lowercase() }
    // NPC 分区：排在可玩角色之后。npc 名单里没收录、但 mod 解析出 npc 名字的
    // （characters.json 之外的），同样补进来
    val npcNames = LinkedHashSet(npcCharacters).apply {
        addAll(counts.keys.filter { it !in names })
        // counts 里的补录名字已含在上一行的差集里；能玩名单里没有的都是补录
    }.filter { it !in allCharacters }
    val npc = npcNames.map { n ->
        CharacterEntry(
            name = n, counts = counts[n] ?: emptyMap(),
            installedCount = installed[n] ?: 0, isNpc = true
        )
    }.sortedBy { it.name.lowercase() }
    return playable + npc
}
