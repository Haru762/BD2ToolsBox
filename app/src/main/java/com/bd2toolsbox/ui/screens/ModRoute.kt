package com.bd2toolsbox.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bd2toolsbox.data.model.ModInfo
import com.bd2toolsbox.data.model.ModKind
import com.bd2toolsbox.ui.viewmodel.MainViewModel

/**
 * ModScreen 的 Route（T28）。
 *
 * 职责只有两件：**采集**（生命周期感知地读 ViewModel 的流）与**装配**
 * （拼出 [ModScreenState] 与 [ModScreenEvents]）。Screen 因此不再接收整个
 * `MainViewModel`，也不再自己 `collectAsState()`。
 *
 * ## 为什么 Route 在 `ui/screens` 而不是计划写的 `feature/mods`
 *
 * 它必须引用 `MainViewModel`（在 `ui/viewmodel`），而分层规则 R3 禁止
 * `feature → ui`。计划那条路径写于规则建立之前，这里按规则落在 ui 层。
 *
 * ## 为什么用 collectAsStateWithLifecycle
 *
 * T28 要求「生命周期感知采集」：页面退到后台时停止采集，避免后台仍因状态变化重组。
 * 需要 `androidx.lifecycle:lifecycle-runtime-compose`（已随 lifecycle 引入）。
 */
@Composable
fun ModRoute(
    viewModel: MainViewModel,
    /**
     * 这一页只显示哪一类 mod；null 表示不过滤、两类混排。
     *
     * 收窄在这里做（不是在 Screen 里）：后续所有计数（全选三态、筛选 chip）都基于
     * 收窄后的列表，否则会出现「本页看不到的条目也被算进全选」这类错位。
     */
    kindFilter: ModKind?,
    onSelectModSource: () -> Unit,
    onUninstallRequest: (String) -> Unit,
    onRemoveModRequest: (ModInfo) -> Unit,
    onBackupManageRequest: () -> Unit,
    onRenameRequest: (ModInfo) -> Unit,
    onDeleteFolderRequest: (ModInfo) -> Unit,
    onInstallConverted: (List<ModInfo>) -> Unit,
    onRequestNotification: () -> Unit
) {
    val context = LocalContext.current

    val modSourceDirectoryUri by viewModel.modSourceDirectoryUri.collectAsStateWithLifecycle()
    val modSourceDirs by viewModel.modSourceDirs.collectAsStateWithLifecycle()
    val filtered by viewModel.filteredModsList.collectAsStateWithLifecycle()
    val all by viewModel.modsList.collectAsStateWithLifecycle()
    val stateFilter by viewModel.stateFilter.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedMods by viewModel.selectedMods.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val showShimmer by viewModel.showShimmer.collectAsStateWithLifecycle()
    val isDeletingAbnormal by viewModel.isDeletingAbnormal.collectAsStateWithLifecycle()
    val isUpdatingOutdated by viewModel.isUpdatingOutdated.collectAsStateWithLifecycle()
    val modScanProgress by viewModel.modScanProgress.collectAsStateWithLifecycle()
    val selectedQuality by viewModel.selectedQuality.collectAsStateWithLifecycle()

    val state = ModScreenState(
        modSourceDirectoryUri = modSourceDirectoryUri,
        modSourceDirs = modSourceDirs,
        modsList = remember(filtered, kindFilter) {
            filtered.filter { kindFilter == null || it.kind == kindFilter }
        },
        allMods = remember(all, kindFilter) {
            all.filter { kindFilter == null || it.kind == kindFilter }
        },
        stateFilter = stateFilter,
        searchQuery = searchQuery,
        selectedMods = selectedMods,
        isLoading = isLoading,
        showShimmer = showShimmer,
        isDeletingAbnormal = isDeletingAbnormal,
        isUpdatingOutdated = isUpdatingOutdated,
        modScanProgress = modScanProgress,
        selectedQuality = selectedQuality,
        isSourceRoot = { uri -> viewModel.isModSourceRoot(uri) }
    )

    // 需要 Context 的动作在这里捕获（Route 也是 Composable），
    // 页面本身不再把 Context 转交给 ViewModel。
    val events = ModScreenEvents(
        onSelectModSource = onSelectModSource,
        onUninstallRequest = onUninstallRequest,
        onRemoveModRequest = onRemoveModRequest,
        onBackupManageRequest = onBackupManageRequest,
        onRenameRequest = onRenameRequest,
        onDeleteFolderRequest = onDeleteFolderRequest,
        onInstallConverted = onInstallConverted,
        onRequestNotification = onRequestNotification,
        onSearchQueryChanged = { viewModel.onSearchQueryChanged(it) },
        onSetStateFilter = { viewModel.setStateFilter(it) },
        onToggleSelection = { viewModel.toggleModSelection(it) },
        onToggleSelectAll = { viewModel.toggleSelectAll() },
        onToggleSelectAllForUris = { viewModel.toggleSelectAllForUris(it) },
        onRescan = { viewModel.rescanAllModSources() },
        onPreview = { viewModel.prepareAndShowPreview(context, it) },
        onHideMod = { viewModel.hideMod(it) },
        onUpdateOutdated = { viewModel.updateOutdatedMod(context, it) },
        onUpdateAllOutdated = { viewModel.updateAllOutdatedMods(context) },
        onDeleteModFolder = { mod, onDone -> viewModel.deleteModFolder(context, mod, onDone) },
        onDeleteAbnormalMods = { viewModel.deleteAbnormalMods(context, it) },
        onInitiateBatchRepack = { viewModel.initiateBatchRepack(context) }
    )

    ModScreen(state = state, events = events)
}
