package com.example.bd2modmanager.ui.screens

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.bd2modmanager.data.model.ModCategory
import com.example.bd2modmanager.data.model.ModInfo
import com.example.bd2modmanager.data.model.ModInstallState
import com.example.bd2modmanager.data.model.categoryOf
import com.example.bd2modmanager.data.model.isUnknownCharacter
import com.example.bd2modmanager.data.repository.CharacterMetaRepository
import com.example.bd2modmanager.ui.components.CharacterAvatar
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
    val installedCount: Int
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
    onPickCharacter: (String) -> Unit
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
                        else -> "角色表还没准备好\n首次启动需要联网下载一次"
                    },
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            val listState = rememberLazyListState()
            val scope = rememberCoroutineScope()
            Row(Modifier.fillMaxSize()) {
                LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                    items(shown, key = { it.name }) { entry ->
                        CharacterRow(entry = entry, onClick = { onPickCharacter(entry.name) })
                        HorizontalDivider()
                    }
                }
                // 95 个角色靠手划太慢，右侧给一条首字母索引
                AlphabetIndex(
                    letters = remember(shown) { shown.map { initialOf(it.name) }.distinct() },
                    onPick = { letter ->
                        val idx = shown.indexOfFirst { initialOf(it.name) == letter }
                        if (idx >= 0) scope.launch { listState.animateScrollToItem(idx) }
                    }
                )
            }
        }
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
 * （它们归到「全部」视图，见 ModScreen 的未识别分组）。
 */
fun buildCharacterEntries(allCharacters: List<String>, mods: List<ModInfo>): List<CharacterEntry> {
    val counts = HashMap<String, HashMap<ModCategory, Int>>()
    val installed = HashMap<String, Int>()
    for (m in mods) {
        if (isUnknownCharacter(m.character)) continue
        val name = m.character.trim()
        val cat = categoryOf(m.type, m.costume)
        counts.getOrPut(name) { HashMap() }.merge(cat, 1, Int::plus)
        if (m.installState == ModInstallState.INSTALLED) {
            installed.merge(name, 1, Int::plus)
        }
    }
    // 角色表里没收录、但 mod 解析出了名字的，也补进来，否则它的 mod 无处可寻
    val names = LinkedHashSet(allCharacters).apply { addAll(counts.keys) }
    return names.map { n ->
        CharacterEntry(
            name = n,
            counts = counts[n] ?: emptyMap(),
            installedCount = installed[n] ?: 0
        )
    }.sortedBy { it.name.lowercase() }
}
