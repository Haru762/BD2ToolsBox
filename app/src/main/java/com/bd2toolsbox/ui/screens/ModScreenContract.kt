package com.bd2toolsbox.ui.screens

import android.net.Uri
import com.bd2toolsbox.core.model.ModScanProgress
import com.bd2toolsbox.data.model.ModInfo
import com.bd2toolsbox.data.model.ModInstallState

/**
 * ModScreen 需要的**一切只读状态**（T28）。
 *
 * 抽出来的理由：此前 `ModScreen` 直接收整个 `MainViewModel`（5300 行）并自己
 * `collectAsState()` 十几个流 —— 页面与 ViewModel 的耦合面是「它能做什么」而不是
 * 「它显示什么」，于是任何一处 VM 改动都可能牵到页面，页面也没法在不构造 VM 的情况下
 * 渲染出确定的样子。
 *
 * 现在：**Route 负责采集与装配**（[ModRoute]），Screen 只读这份状态 + 发事件。
 *
 * 注意这里只放「显示所需」的值：不放 ViewModel、不放 StateFlow、不放动作。
 */
data class ModScreenState(
    /** 当前唯一的 MOD 源目录；null = 还没选。用于「去选目录」这类空态引导。 */
    val modSourceDirectoryUri: Uri?,
    val modSourceDirs: List<Uri>,
    /** 已按 kindFilter 收窄、且已过搜索/状态筛选的列表。 */
    val modsList: List<ModInfo>,
    /** 已按 kindFilter 收窄的**全量**列表（用于四区划分与计数基准）。 */
    val allMods: List<ModInfo>,
    val stateFilter: ModInstallState?,
    val searchQuery: String,
    val selectedMods: Set<Uri>,
    val isLoading: Boolean,
    val showShimmer: Boolean,
    val isDeletingAbnormal: Boolean,
    val isUpdatingOutdated: Boolean,
    val modScanProgress: ModScanProgress?,
    val selectedQuality: String,
    /** 某个 URI 是否属于已记住的源目录树（决定「删除文件夹」能不能点）。 */
    val isSourceRoot: (Uri) -> Boolean
)

/**
 * ModScreen 能发出的**一切事件**（T28）。
 *
 * 用类而不是十几个参数：页面签名从「9 个回调 + 一个 ViewModel」变成
 * 「state + events」，加一个动作时不会再把签名撑长一行。
 *
 * 所有需要 `Context` 的动作都在 Route 侧捕获（那里也是 Composable），
 * 页面本身不再把 Context 转交给 ViewModel。
 */
class ModScreenEvents(
    val onSelectModSource: () -> Unit,
    val onUninstallRequest: (String) -> Unit,
    val onRemoveModRequest: (ModInfo) -> Unit,
    val onBackupManageRequest: () -> Unit,
    val onRenameRequest: (ModInfo) -> Unit,
    val onDeleteFolderRequest: (ModInfo) -> Unit,
    val onInstallConverted: (List<ModInfo>) -> Unit,
    val onRequestNotification: () -> Unit,
    // ---- 列表本身的交互 ----
    val onSearchQueryChanged: (String) -> Unit,
    val onSetStateFilter: (ModInstallState?) -> Unit,
    val onToggleSelection: (Uri) -> Unit,
    val onToggleSelectAll: () -> Unit,
    val onToggleSelectAllForUris: (Set<Uri>) -> Unit,
    val onRescan: () -> Unit,
    // ---- 条目上的操作 ----
    val onPreview: (ModInfo) -> Unit,
    val onHideMod: (ModInfo) -> Unit,
    val onUpdateOutdated: (ModInfo) -> Unit,
    val onUpdateAllOutdated: () -> Unit,
    val onDeleteModFolder: (ModInfo, onDone: (Boolean, String) -> Unit) -> Unit,
    val onDeleteAbnormalMods: (List<ModInfo>) -> Unit,
    val onInitiateBatchRepack: () -> Unit
)
