package com.bd2toolsbox.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bd2toolsbox.data.model.ModInfo
import com.bd2toolsbox.data.model.ModInstallState
import com.bd2toolsbox.data.model.ModKind
import android.net.Uri
import com.bd2toolsbox.data.model.ModCategory
import com.bd2toolsbox.data.model.categoryOf
import com.bd2toolsbox.data.model.isUnknownCharacter
import com.bd2toolsbox.data.model.ResolutionState
import com.bd2toolsbox.ui.viewmodel.MainViewModel
import com.valentinilk.shimmer.shimmer

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ModScreen(
    viewModel: MainViewModel,
    /**
     * 这一页只显示哪一类 mod；null 表示不过滤、两类混排。
     *
     * 「按角色」视图把同一套皮肤的源与产物合并成一条，所以它不能再被加工阶段切开 ——
     * 那样同一套皮肤又会分裂成两行，正是原先按阶段分两页的毛病换个地方重现。
     * 「全部」视图传 null，批量多选转换仍在这里做。
     */
    kindFilter: ModKind?,
    onSelectModSource: () -> Unit,
    onUninstallRequest: (String) -> Unit,
    onRemoveModRequest: (ModInfo) -> Unit,
    onBackupManageRequest: () -> Unit,
    onRenameRequest: (ModInfo) -> Unit,
    onDeleteFolderRequest: (ModInfo) -> Unit,
    onInstallConverted: (List<ModInfo>) -> Unit,
    onRequestNotification: () -> Unit,
    onUnpackRequest: () -> Unit
) {
    val modSourceDirectoryUri by viewModel.modSourceDirectoryUri.collectAsState()
    // 先按 tab 的类型收窄，再做原有的分组。后续所有计数（全选三态、筛选 chip）都基于
    // 这份收窄后的列表，否则会出现「本页看不到的条目也被算进全选」这类错位。
    val modsList = viewModel.filteredModsList.collectAsState().value
        .filter { kindFilter == null || it.kind == kindFilter }
    val allMods = viewModel.modsList.collectAsState().value
        .filter { kindFilter == null || it.kind == kindFilter }
    val stateFilter by viewModel.stateFilter.collectAsState()
    // 一级按角色、二级按目标 bundle 的两级结构。
    // 分组本身不便宜（301 条要过好几遍），而列表在选中、滚动时会频繁重组，所以缓存住。
    val characterGroups = remember(modsList) { buildCharacterGroups(modsList) }
    val selectedMods by viewModel.selectedMods.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val showShimmer by viewModel.showShimmer.collectAsState()
    val context = LocalContext.current
    val isSearchActive by viewModel.isSearchActive.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedQuality by viewModel.selectedQuality.collectAsState()


    val pullToRefreshState = rememberPullToRefreshState()
    if (pullToRefreshState.isRefreshing) {
        LaunchedEffect(true) {
            // 下拉刷新重扫的是「记住的所有目录」，不是当前第一个 ——
            // 多目录合并之后，只扫一个会让另外几个目录的 mod 凭空消失。
            viewModel.rescanAllModSources()
        }
    }

    LaunchedEffect(isLoading) {
        if (!isLoading) {
            pullToRefreshState.endRefresh()
        }
    }

    Scaffold(
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val selectedQuality by viewModel.selectedQuality.collectAsState()
                var qualityExpanded by remember { mutableStateOf(false) }
                // 画质选择器已挪到顶部工具栏（与 ASTC 开关并列），这里不再放：
                // 一是它原先只在选中 mod 后才出现，而画质影响的是解析与下载，属于开工前
                // 就该定的事，为了改画质先随便勾一个 mod 是反的；二是悬浮按钮列一多，
                // 横屏（垂直空间仅 1080）时会把「直接装入」挤出屏幕外，实测点不到。
                
                // 原先这里有个「合并 Spine」按钮，已移除：
                // repacker.py 在转换时本就会自动检测并合并超出的贴图页（见 repacker.py:458），
                // 那个按钮只是把同一件事挪到手动，而且会就地改写用户的原始 mod 文件
                // （删掉 .png/.atlas、旧文件挪进 .old）。对玩家既看不懂又有破坏性。
                AnimatedVisibility(visible = modSourceDirectoryUri != null && selectedMods.isEmpty()) {
                    FloatingActionButton(
                        onClick = onUnpackRequest,
                    ) {
                        Icon(Icons.Default.Unarchive, contentDescription = "解包工具")
                    }
                }
                AnimatedVisibility(visible = selectedMods.isNotEmpty()) {
                    // 已转换产物不需要重打包，直接放进游戏目录即可，所以按钮文案与动作都不同。
                    // 混选时以「有没有 PC mod」为准：PC mod 必须走转换流程。
                    val chosen = modsList.filter { it.uri in selectedMods }
                    val allConverted = chosen.isNotEmpty() &&
                            chosen.all { it.kind == ModKind.CONVERTED_BUNDLE }
                    ExtendedFloatingActionButton(
                        onClick = {
                            // 转换/装入挂在前台服务上（防止放着不管被系统回收），
                            // 通知是它唯一的可见进度，所以动手前先要一次权限。
                            onRequestNotification()
                            if (allConverted) onInstallConverted(chosen)
                            else viewModel.initiateBatchRepack(context)
                        },
                        icon = {
                            Icon(
                                if (allConverted) Icons.Default.DriveFileMove else Icons.Default.Done,
                                contentDescription = null
                            )
                        },
                        text = { Text(if (allConverted) "直接装入" else "转换所选") }
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 注意这里不再用「未选目录就整屏显示欢迎页」的写法。
            // 那样会把顶栏的目录入口和设置一起挡掉，用户在选目录之前什么都看不到、也调不了设置。
            // 现在骨架照常渲染，引导只占列表区域（见下方 needsFolder 分支）。
            Box(modifier = Modifier.nestedScroll(pullToRefreshState.nestedScrollConnection)) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background)
                            ) {
                                Box(
                                    modifier = Modifier.size(width = 48.dp, height = 40.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    // 必须用 modsList（搜索过滤后的）而不是 allModsList：
                                    // toggleSelectAll() 操作的就是过滤后的列表，若这里拿全量计数，
                                    // 搜索状态下全选会显示成半选态，用户再点一次反而全部取消。
                                    val allModsCount = modsList.count {
                                        it.resolutionState == ResolutionState.KNOWN
                                    }
                                    val selectedModsCount = selectedMods.size
                                    val checkboxState = when {
                                        selectedModsCount == 0 -> ToggleableState.Off
                                        selectedModsCount == allModsCount && allModsCount > 0 -> ToggleableState.On
                                        selectedModsCount > 0 -> ToggleableState.Indeterminate
                                        else -> ToggleableState.Off
                                    }
                                    TriStateCheckbox(
                                        state = checkboxState,
                                        onClick = { viewModel.toggleSelectAll() }
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(4.dp))

                            // 换目录的入口原先在这里。它管的是全局的 mod 源目录、
                            // 两个视图都用得着，所以已上移到 MainActivity 的顶栏（左上角文件夹图标），
                            // 这里不再重复放一个。

                            BoxWithConstraints(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                val transition = updateTransition(isSearchActive, label = "search_transition")
                                val collapsedSearchWidth = 40.dp

                                // ASTC 开关原先在这里占了半条横幅，现已迁入设置的「转换设置」。
                                // 顶栏只留与当前列表强相关的东西（全选、搜索、状态筛选），
                                // 腾出的宽度给搜索框 —— 上百个 mod 时搜索才是高频操作。
                                val searchCardWidth by transition.animateDp(
                                    label = "search_card_width",
                                    transitionSpec = { tween(350) }
                                ) { active ->
                                    if (active) maxWidth else collapsedSearchWidth
                                }

                                val searchCornerRadius by transition.animateDp(
                                    label = "search_card_corner_radius",
                                    transitionSpec = { tween(350) }
                                ) { active ->
                                    if (active) 16.dp else 20.dp
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    ElevatedCard(
                                        modifier = Modifier.size(width = searchCardWidth, height = 40.dp),
                                        shape = RoundedCornerShape(searchCornerRadius),
                                        onClick = { if (!isSearchActive) viewModel.setSearchActive(true) },
                                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxSize(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.End
                                        ) {
                                            AnimatedVisibility(visible = isSearchActive, modifier = Modifier.weight(1f)) {
                                                BasicTextField(
                                                    value = searchQuery,
                                                    onValueChange = viewModel::onSearchQueryChanged,
                                                    modifier = Modifier.padding(start = 16.dp, end = 8.dp),
                                                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    ),
                                                    singleLine = true,
                                                    decorationBox = { innerTextField ->
                                                        if (searchQuery.isEmpty()) {
                                                            Text(
                                                                "按名称搜索...",
                                                                style = MaterialTheme.typography.bodyMedium,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                        }
                                                        innerTextField()
                                                    }
                                                )
                                            }
                                            IconButton(onClick = { viewModel.setSearchActive(!isSearchActive) }) {
                                                Icon(
                                                    imageVector = if (isSearchActive) Icons.Default.Close else Icons.Default.Search,
                                                    contentDescription = "搜索"
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        StateFilterRow(
                            allMods = allMods,
                            current = stateFilter,
                            onSelect = { viewModel.setStateFilter(it) }
                        )

                        if (modSourceDirectoryUri == null) {
                            WelcomeScreen(onSelectModSource)
                        } else if (showShimmer) {
                            ShimmerLoadingScreen()
                        } else if (modsList.isEmpty()) {
                            if (searchQuery.isNotEmpty()) {
                                NoSearchResultsScreen(searchQuery)
                            } else if (stateFilter != null) {
                                NoFilterResultsScreen()
                            } else {
                                // kindFilter 现在恒为 null（「全部」视图两类混排），
                                // 但空态文案仍按 kind 分岔留着 —— 万一以后又要单看某一类，改回来只是传个参数。
                                EmptyModsScreen(kindFilter)
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                characterGroups.forEach { cg ->
                                    // 只有角色这一级 sticky。两级都 sticky 的话，Compose 会让
                                    // 后出现的 header 把前一个顶走 —— 角色名一滚就没了，
                                    // 而「当前看的是谁」恰恰是这个列表最需要一直可见的信息。
                                    stickyHeader(key = "char:${cg.character}") {
                                        CharacterHeader(group = cg)
                                    }
                                    // 同一角色内按类别聚拢，类别变化处插一条轻量分隔。
                                    // 不给类别单独做一层标题：那会多出一级缩进，横屏尤其浪费，
                                    // 而用分隔条 + 组标题上的徽章已经能把三类分清。
                                    var lastCategory: ModCategory? = null
                                    cg.groups.forEach { bg ->
                                        if (bg.category != lastCategory) {
                                            item(key = "cat:${cg.character}:${bg.category.name}") {
                                                CategoryLabel(category = bg.category)
                                            }
                                            lastCategory = bg.category
                                        }
                                        item(key = "grp:${cg.character}:${bg.hash}") {
                                            BundleGroupHeader(
                                                group = bg,
                                                selectedMods = selectedMods,
                                                onUninstall = { onUninstallRequest(bg.hash) },
                                                onToggleAll = {
                                                    viewModel.toggleSelectAllForUris(
                                                        bg.mods.map { it.uri }.toSet()
                                                    )
                                                }
                                            )
                                        }
                                        items(
                                            items = bg.mods,
                                            key = { mod -> mod.uri.toString() }
                                        ) { modInfo ->
                                            ModCard(
                                                modInfo = modInfo,
                                                isSelected = modInfo.uri in selectedMods,
                                                onToggleSelection = { viewModel.toggleModSelection(modInfo.uri) },
                                                onLongPress = { viewModel.prepareAndShowPreview(context, modInfo) },
                                                // 只有确实生效中的才给移除入口 —— 未装的没什么可移除，
                                                // 状态未知时贸然还原反而可能盖掉别的东西
                                                onRemove = if (modInfo.installState == ModInstallState.INSTALLED) {
                                                    { onRemoveModRequest(modInfo) }
                                                } else null,
                                                // 重命名只对已转换产物有意义：PC mod 的名字就是文件夹名，
                                                // 产物的名字才是从 hash 反查出来的、可能需要人工订正
                                                onRename = if (modInfo.kind == ModKind.CONVERTED_BUNDLE) {
                                                    { onRenameRequest(modInfo) }
                                                } else null,
                                                onHide = { viewModel.hideMod(modInfo) },
                                                onDeleteFolder = { onDeleteFolderRequest(modInfo) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    PullToRefreshContainer(
                        modifier = Modifier.align(Alignment.TopCenter),
                        state = pullToRefreshState,
                    )
            }
        }
    }
}

@Composable
private fun AutoShrinkText(
    text: String,
    modifier: Modifier = Modifier,
    maxFontSize: TextUnit = 20.sp,
    minFontSize: TextUnit = 12.sp,
    fontWeight: FontWeight? = null,
    color: androidx.compose.ui.graphics.Color = LocalContentColor.current
) {
    var currentFontSize by remember(text) { mutableStateOf(maxFontSize) }

    Text(
        text = text,
        modifier = modifier,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        fontSize = currentFontSize,
        fontWeight = fontWeight,
        color = color,
        style = LocalTextStyle.current.copy(color = color),
        onTextLayout = { result ->
            if (result.hasVisualOverflow && currentFontSize > minFontSize) {
                currentFontSize = (currentFontSize.value - 1f).coerceAtLeast(minFontSize.value).sp
            }
        }
    )
}

/**
 * 状态筛选。上百个 mod 时，「当前生效的是哪些」比「一共有哪些」更常被问到。
 *
 * 只显示实际存在的状态，且计数取自未过滤的全量列表 —— 否则选中某个筛选后其余计数会
 * 全变 0，看起来像 mod 消失了。
 */
@Composable
private fun StateFilterRow(
    allMods: List<ModInfo>,
    current: ModInstallState?,
    onSelect: (ModInstallState?) -> Unit
) {
    if (allMods.isEmpty()) return

    val counts = remember(allMods) {
        allMods.groupingBy { it.installState }.eachCount()
    }
    val candidates = listOf(
        ModInstallState.INSTALLED to "生效中",
        ModInstallState.STALE to "需重新应用",
        ModInstallState.MODIFIED_BY_OTHER to "被其他工具修改",
        ModInstallState.NOT_INSTALLED to "未装",
        ModInstallState.UNVERIFIED to "未校验",
    ).filter { (counts[it.first] ?: 0) > 0 }

    // 只有一种状态时筛选没有意义
    if (candidates.size < 2) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = current == null,
            onClick = { onSelect(null) },
            label = { Text("全部 ${allMods.size}", style = MaterialTheme.typography.labelMedium) }
        )
        candidates.forEach { (state, label) ->
            FilterChip(
                selected = current == state,
                onClick = { onSelect(state) },
                label = {
                    Text("$label ${counts[state]}", style = MaterialTheme.typography.labelMedium)
                }
            )
        }
    }
}

@Composable
fun NoFilterResultsScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.FilterAltOff, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.secondary)
        Spacer(modifier = Modifier.height(24.dp))
        Text("该状态下没有 mod", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "点上方的「全部」可以取消筛选。",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun NoSearchResultsScreen(query: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.SearchOff, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.secondary)
        Spacer(modifier = Modifier.height(24.dp))
        Text("无结果", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "没有名称、角色或皮肤包含「$query」的 mod。",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun WelcomeScreen(onSelectFolder: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(24.dp))
        Text("欢迎使用！", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "首先，请选择你存放 Brown Dust 2 mod 的文件夹",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onSelectFolder) {
            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("选择 mod 文件夹")
        }
    }
}

@Composable
fun EmptyModsScreen(kind: ModKind?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            when (kind) {
                ModKind.CONVERTED_BUNDLE -> Icons.Default.PhoneAndroid
                ModKind.PC_SOURCE -> Icons.Default.Computer
                null -> Icons.Default.FolderOpen
            },
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.secondary
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            when (kind) {
                ModKind.CONVERTED_BUNDLE -> "还没有已转换的 mod"
                ModKind.PC_SOURCE -> "还没有 PC 版 mod"
                null -> "还没有任何 mod"
            },
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            if (kind == null) {
                "两种 mod 都能放进同一个文件夹，会自动归到各角色下。PC 版源文件是每个 mod 一个" +
                    "文件夹（含 .skel 或 .json、.atlas、.png，.zip 也行）；已转换产物是 " +
                    "Shared/<bundle>/<hash>/__data 那种结构。子文件夹会自动往下找，选合集总目录也可以。"
            } else if (kind == ModKind.CONVERTED_BUNDLE) {
                "这一页收的是已经转换好的安卓 mod —— 也就是 Shared/<bundle>/<hash>/__data 那种结构，" +
                    "勾选后可直接装入游戏、不需要再转换。\n\n" +
                    "把这样的 Shared 文件夹放进 mod 目录即可。\n\n" +
                    "如果你手上是 PC 版 mod（.skel/.atlas/.png 散文件），请切到「PC」页。"
            } else {
                "这一页收的是 PC 版 mod 源文件，装之前需要先转换成安卓格式。\n\n" +
                    "每个 mod 一个文件夹，里面含 .skel（或 .json）、.atlas、.png；" +
                    ".zip 也可以。子文件夹会自动往下找，所以选合集总目录也行。\n\n" +
                    "注意：mod 内的文件名必须全小写。"
            },
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ModCard(
    modInfo: ModInfo,
    isSelected: Boolean,
    onToggleSelection: () -> Unit,
    onLongPress: () -> Unit,
    onRemove: (() -> Unit)? = null,
    onRename: (() -> Unit)? = null,
    onHide: (() -> Unit)? = null,
    onDeleteFolder: (() -> Unit)? = null
) {
    val isSelectable = modInfo.resolutionState == ResolutionState.KNOWN
    val elevation by animateDpAsState(if (isSelected) 4.dp else 1.dp, label = "elevation")
    var menuOpen by remember { mutableStateOf(false) }

    ElevatedCard(
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .clip(CardDefaults.shape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { if (isSelectable) onToggleSelection() },
                    onLongPress = { menuOpen = true }
                )
            }
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { if (isSelectable) onToggleSelection() },
                enabled = isSelectable
            )
            Spacer(Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = modInfo.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    InstallStateBadge(modInfo.installState)
                }
                Text(
                    text = modSubtitle(modInfo),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                // 无效条目把原因摊开写清楚（比如目录名被截断），否则用户只能对着
                // "未识别"猜。正常条目不占这一行。
                if (modInfo.resolutionState == ResolutionState.INVALID && !modInfo.errorReason.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = modInfo.errorReason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            if (onRemove != null) {
                IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.RemoveCircleOutline,
                        contentDescription = "移除此 mod",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(4.dp))
            }
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        text = if (modInfo.kind == ModKind.CONVERTED_BUNDLE) "已转换"
                               else modInfo.type.uppercase(),
                        style = MaterialTheme.typography.labelSmall
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = typeIconOf(modInfo.type),
                        contentDescription = modInfo.type,
                        modifier = Modifier.size(14.dp)
                    )
                },
                modifier = Modifier.heightIn(max = 24.dp)
            )
        }

        ModCardMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            onPreview = onLongPress,
            onRename = onRename,
            onRemove = onRemove,
            onHide = onHide,
            onDeleteFolder = onDeleteFolder
        )
    }
}

/** 副标题：角色 - 皮肤；产物带体积。 */
private fun modSubtitle(modInfo: ModInfo): String {
    val base = "${modInfo.character} - ${modInfo.costume}"
    return if (modInfo.kind == ModKind.CONVERTED_BUNDLE && modInfo.convertedDataSize > 0) {
        "$base · ${formatSize(modInfo.convertedDataSize)}"
    } else {
        base
    }
}

/** mod 类型的小图标（中文旧值与新英文值都认）。 */
private fun typeIconOf(type: String) = when (type.lowercase()) {
    "idle", "立绘" -> Icons.Default.Person
    "cutscene", "过场动画" -> Icons.Default.Movie
    else -> Icons.Default.Category
}

/**
 * 长按菜单。各项按需出现（回调为 null 就不显示），点击后先收菜单再执行动作。
 */
@Composable
private fun ModCardMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onPreview: () -> Unit,
    onRename: (() -> Unit)?,
    onRemove: (() -> Unit)?,
    onHide: (() -> Unit)?,
    onDeleteFolder: (() -> Unit)?
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("预览动画") },
            leadingIcon = { Icon(Icons.Default.PlayArrow, null) },
            onClick = { onDismiss(); onPreview() }
        )
        if (onRename != null) {
            DropdownMenuItem(
                text = { Text("重命名") },
                leadingIcon = { Icon(Icons.Default.Edit, null) },
                onClick = { onDismiss(); onRename() }
            )
        }
        // 卸载单个 mod：只有确实生效中的才给这一项，否则没有可还原的东西。
        // 右侧那个小图标容易点不准，长按菜单是更好落的入口。
        if (onRemove != null) {
            DropdownMenuItem(
                text = { Text("卸载这个 mod（还原原版）") },
                leadingIcon = {
                    Icon(Icons.Default.RestartAlt, null, tint = MaterialTheme.colorScheme.error)
                },
                onClick = { onDismiss(); onRemove() }
            )
        }
        if (onHide != null) {
            DropdownMenuItem(
                text = { Text("从列表隐藏（不删文件）") },
                leadingIcon = { Icon(Icons.Default.VisibilityOff, null) },
                onClick = { onDismiss(); onHide() }
            )
        }
        if (onDeleteFolder != null) {
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("从手机删除文件夹", color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                },
                onClick = { onDismiss(); onDeleteFolder() }
            )
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format("%.1f GB", bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024)
    bytes >= 1024L -> String.format("%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/** mod 在游戏里的当前状态。未装入时不显示，避免上百个条目上全是徽章。 */
@Composable
private fun InstallStateBadge(state: ModInstallState) {
    val (label, color) = when (state) {
        ModInstallState.INSTALLED ->
            "生效中" to MaterialTheme.colorScheme.primary
        ModInstallState.STALE ->
            "需重新应用" to MaterialTheme.colorScheme.error
        ModInstallState.MODIFIED_BY_OTHER ->
            "被其他工具修改" to MaterialTheme.colorScheme.tertiary
        ModInstallState.UNVERIFIED ->
            "未校验" to MaterialTheme.colorScheme.onSurfaceVariant
        ModInstallState.NOT_INSTALLED -> return
    }

    Spacer(Modifier.width(6.dp))
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 1.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1
        )
    }
}

@Composable
fun ShimmerLoadingScreen() {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(10) {
            ShimmerModCard()
        }
    }
}

@Composable
fun ShimmerModCard() {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .shimmer()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .height(20.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .height(16.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                )
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .width(100.dp)
                        .height(32.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                )
            }
        }
    }
}

// ==================================================================== 两级分组

/**
 * 二级：某个角色在某个目标 bundle 下的 mod。
 *
 * 分组键是「角色 + bundle」而不是只有 bundle。只按 bundle 分会出事：实测心契之约的立绘
 * 全部打包进同一个 bundle，19 个不同角色共用一个 hash —— 那样整组只能挂在头一个角色下，
 * 用户在自己关心的角色段里根本找不到它的心契之约，按角色分组也就白做了。
 *
 * 仍保留 bundle 这一层（而不是让「类别」取代它）是因为它有功能含义：同一个 bundle 只能装
 * 一个 mod，组内互斥，卸载与全选都挂在这里。而且同一角色同一类别下往往就有多个 bundle
 * （Justia 的过场动画有 6 个，对应 6 套皮肤）。
 */
private data class BundleGroup(
    val hash: String,
    val category: ModCategory,
    /** 二级标题显示的皮肤名。角色名已在一级标题里，这里不重复。 */
    val costume: String,
    val mods: List<ModInfo>,
    /**
     * 这个 bundle 在整份列表里一共有多少个 mod 指向它（含其他角色的）。
     * 大于 [mods] 的个数时说明是共用 bundle，要在界面上说清，
     * 否则用户会以为「卸载」只影响眼前这一个角色。
     */
    val totalInBundle: Int
)

/** 一级：一个角色名下的所有 bundle 组。 */
private data class CharacterGroup(
    val character: String,
    val isUnknown: Boolean,
    val groups: List<BundleGroup>
) {
    val total: Int get() = groups.sumOf { it.mods.size }
}

/**
 * 把平铺的 mod 列表整理成「角色 → 目标 bundle」两级。
 *
 * 起因是 PC 页在真实收藏下会有近千条平铺（实测 951 个 mod / 9.7 GB），
 * 除了搜索没有任何找东西的办法。
 *
 * 排序：角色按名字，未识别的一律垫底；角色内先按类别（过场动画→立绘→心契之约→其他）
 * 再按皮肤名，这样同类别的自然聚成一段，配合分隔条就有了类别层次。
 */
private fun buildCharacterGroups(mods: List<ModInfo>): List<CharacterGroup> {
    if (mods.isEmpty()) return emptyList()

    fun hashOf(m: ModInfo): String = when (m.resolutionState) {
        ResolutionState.KNOWN, ResolutionState.MISC -> m.targetHash ?: "未知"
        ResolutionState.UNKNOWN -> "未知"
        ResolutionState.INVALID -> "无效"
    }
    fun charOf(m: ModInfo): String =
        if (isUnknownCharacter(m.character)) "未识别" else m.character.trim()

    // 每个 bundle 总共被多少个 mod 指向 —— 用来判断是不是跨角色共用
    val bundleTotals = mods.groupingBy { hashOf(it) }.eachCount()

    val perCharacter = LinkedHashMap<String, MutableList<BundleGroup>>()
    for ((key, list) in mods.groupBy { charOf(it) to hashOf(it) }) {
        val (charName, hash) = key
        val rep = list.first()
        perCharacter.getOrPut(charName) { mutableListOf() }.add(
            BundleGroup(
                hash = hash,
                category = categoryOf(rep.type, rep.costume),
                costume = rep.costume?.trim().orEmpty().ifBlank { hash },
                mods = list,
                totalInBundle = bundleTotals[hash] ?: list.size
            )
        )
    }

    return perCharacter.map { (name, groups) ->
        CharacterGroup(
            character = name,
            isUnknown = name == "未识别",
            groups = groups.sortedWith(
                compareBy({ it.category.ordinal }, { it.costume.lowercase() })
            )
        )
    }.sortedWith(
        // 未识别垫底；其余按名字。用 lowercase 而不是默认排序，
        // 否则大小写混排会让同一角色的不同写法离得很远
        compareBy({ if (it.isUnknown) 1 else 0 }, { it.character.lowercase() })
    )
}

/** 一级标题：角色名 + 条目数。会吸顶，所以要不透明背景。 */
@Composable
private fun CharacterHeader(group: CharacterGroup) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = group.character,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (group.isUnknown) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${group.total} 项",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 类别分隔：同一角色内换类别时插一条。刻意做得比角色标题轻。 */
@Composable
private fun CategoryLabel(category: ModCategory) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 16.dp, top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = category.label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.secondary
        )
        HorizontalDivider(
            modifier = Modifier
                .padding(start = 10.dp)
                .weight(1f)
        )
    }
}

/** 二级标题：皮肤名 + 类别徽章 + bundle hash，附卸载与三态全选。 */
@Composable
private fun BundleGroupHeader(
    group: BundleGroup,
    selectedMods: Set<Uri>,
    onUninstall: () -> Unit,
    onToggleAll: () -> Unit
) {
    val special = group.hash == "未知" || group.hash == "无效"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (special) group.hash else group.costume,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!special) {
                    Text(
                        text = group.hash,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // 共用 bundle 时必须说清：卸载还原的是整个资源包，不只是眼前这个角色的。
                    // 心契之约就是这种情况 —— 十几个角色的立绘打包在一起。
                    if (group.totalInBundle > group.mods.size) {
                        Text(
                            text = "与其他角色共用此资源包（共 ${group.totalInBundle} 个候选）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (!special) {
                IconButton(onClick = onUninstall) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "卸载",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            val uris = group.mods.map { it.uri }.toSet()
            val selectedInGroup = selectedMods.intersect(uris)
            TriStateCheckbox(
                state = when {
                    selectedInGroup.isEmpty() -> ToggleableState.Off
                    selectedInGroup.size == uris.size -> ToggleableState.On
                    else -> ToggleableState.Indeterminate
                },
                onClick = onToggleAll
            )
        }
    }
}
