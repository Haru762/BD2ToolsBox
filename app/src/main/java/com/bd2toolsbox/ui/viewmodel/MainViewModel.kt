package com.bd2toolsbox.ui.viewmodel

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import com.bd2toolsbox.core.python.PythonRuntime
import com.chaquo.python.Python
import com.bd2toolsbox.SpinePreviewActivity
// 领域结果/计划模型已迁出本类（T05）。它们不依赖 android.* / UI 包，也不持有
// ViewModel、Context、Composable 或 PyObject，因此可以在 JVM 单测里直接构造。
import com.bd2toolsbox.core.fileaccess.DirectoryCopyEntry
import com.bd2toolsbox.core.fileaccess.DirectoryCopySource
import com.bd2toolsbox.core.fileaccess.FileDirectoryCopySource
import com.bd2toolsbox.core.fileaccess.SafeArchivePaths
import com.bd2toolsbox.core.fileaccess.StrictDirectoryCopy
import com.bd2toolsbox.core.fileaccess.decideBackupGate
import com.bd2toolsbox.core.model.ConvertedOverwritePlan
import com.bd2toolsbox.core.model.HashRenameResult
import com.bd2toolsbox.core.model.InstalledModRef
import com.bd2toolsbox.core.model.ModScanProgress
import com.bd2toolsbox.core.model.OperationError
import com.bd2toolsbox.core.model.OperationErrorKind
import com.bd2toolsbox.core.model.OperationId
import com.bd2toolsbox.core.model.PlannedMod
import com.bd2toolsbox.core.model.PrepackPlan
import com.bd2toolsbox.core.model.PreviewAssets
import com.bd2toolsbox.core.model.RepackPlanBuilder
import com.bd2toolsbox.core.model.UninstallPlan
import com.bd2toolsbox.core.model.BackupPurge
import com.bd2toolsbox.core.model.PurgeOutcome
import com.bd2toolsbox.core.storage.BackupProvenance
import com.bd2toolsbox.core.storage.BaselineSource
import com.bd2toolsbox.core.storage.CatalogAnchor
import com.bd2toolsbox.core.storage.ConvertedInstallPreparation
import com.bd2toolsbox.core.storage.ConvertedInstallStaging
import com.bd2toolsbox.core.storage.JournalMoveFacts
import com.bd2toolsbox.core.operations.OperationRecovery
import com.bd2toolsbox.core.operations.RecoveryDisposition
import com.bd2toolsbox.core.model.ThemeMode
import com.bd2toolsbox.core.storage.JournalTarget
import com.bd2toolsbox.core.storage.LedgerRecord
import com.bd2toolsbox.core.storage.LedgerWrite
import com.bd2toolsbox.core.storage.ModSnapshotStore
import com.bd2toolsbox.core.storage.OperationJournalStore
import com.bd2toolsbox.core.storage.SafMediaExclusion
import com.bd2toolsbox.core.storage.SettingsRepository
import com.bd2toolsbox.core.storage.SharedPreferencesSettingsStore
import com.bd2toolsbox.data.repository.RecoveryApplyResult
import com.bd2toolsbox.core.storage.TargetObservation
import com.bd2toolsbox.core.storage.TargetObservations
import com.bd2toolsbox.core.storage.RecoveryObservationLayout

import com.bd2toolsbox.core.storage.PendingWrite
import com.bd2toolsbox.core.storage.PristineVerifier
import com.bd2toolsbox.core.storage.SnapshotCommit
import com.bd2toolsbox.core.storage.SnapshotEntry
import com.bd2toolsbox.core.storage.SnapshotLoad
import com.bd2toolsbox.core.storage.Verdict
import com.bd2toolsbox.data.model.*
import com.bd2toolsbox.data.repository.AvatarRepository
import com.bd2toolsbox.data.repository.BundleBackupRepository
import com.bd2toolsbox.data.repository.BundleNameResolver
import com.bd2toolsbox.data.repository.CharacterMetaRepository
import com.bd2toolsbox.data.repository.CharacterRepository
import com.bd2toolsbox.data.repository.ConvertedInstallSubmitter
import com.bd2toolsbox.data.repository.ConvertedSubmitResult
import com.bd2toolsbox.data.repository.InstalledModRepository
import com.bd2toolsbox.feature.install.MoveCommitHost
import com.bd2toolsbox.feature.install.MoveCommitRequest
import com.bd2toolsbox.feature.install.UninstallExecutor
import com.bd2toolsbox.feature.mods.ScanCheck
import com.bd2toolsbox.feature.mods.ScanExecutor
import com.bd2toolsbox.feature.mods.ScanRunHost
import com.bd2toolsbox.feature.mods.ScanRunProgress
import com.bd2toolsbox.feature.mods.ScanRunRequest
import com.bd2toolsbox.feature.mods.ScanRun
import com.bd2toolsbox.feature.preview.PrepackExecutor
import com.bd2toolsbox.data.repository.LedgerTake
import com.bd2toolsbox.data.repository.ModRepository
import com.bd2toolsbox.data.repository.PreviewCacheRepository
import com.bd2toolsbox.data.repository.SpineRuntimeRepository
import com.bd2toolsbox.data.repository.UpdateRepository
import com.bd2toolsbox.service.InstallService
import com.bd2toolsbox.core.engine.ModEngine
import com.bd2toolsbox.service.PrepackService
import com.bd2toolsbox.service.ScanService
import com.bd2toolsbox.service.ShizukuManager
import com.bd2toolsbox.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import java.util.zip.ZipInputStream

@HiltViewModel
class MainViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    // T17：应用上下文与有状态仓储改构造注入（Hilt 单例），不再用
    // initialize(context)+lateinit 装配。appContext 现为非空注入值；旧代码里的
    // appContext?./appContext!! 仍能编译（只是冗余），不强行改写 51 处无关调用点。
    @ApplicationContext private val appContext: Context,
    private val characterRepository: CharacterRepository,
    private val modRepository: ModRepository,
    private val installedModRepository: InstalledModRepository,
    private val backupRepository: BundleBackupRepository,
    private val previewCacheRepository: PreviewCacheRepository,
    private val modEngine: ModEngine,
    // T20：清理残留的手工 get(context) 单例，改成与其他仓储同样的构造注入。
    private val characterMetaRepository: CharacterMetaRepository,
    private val avatarRepository: AvatarRepository,
    private val spineRuntimeRepository: SpineRuntimeRepository,
    private val updateRepository: UpdateRepository,
    private val commitHost: MoveCommitHost,
    private val uninstallExecutor: UninstallExecutor,
    private val scanRunHost: ScanRunHost,
    private val scanExecutor: ScanExecutor,
    private val prepackExecutor: PrepackExecutor,
) : ViewModel() {

    companion object {
        /** Shizuku 官方 app 的包名，用于「打开 Shizuku」按钮。 */
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

        // T28：只落这两个小型恢复参数（搜索词 + 状态筛选名）。
        private const val KEY_SEARCH_QUERY = "mod_search_query"
        private const val KEY_STATE_FILTER = "mod_state_filter"


        /**
         * mod 源目录列表在 SharedPreferences 里的分隔符。
         *
         * 用换行而不是 JSON：URI 规范要求换行必须转义，所以它绝不会出现在值里，
         * 这么写就不必为一个字符串列表专门养一个 Gson 实例。
         */
        private const val DIR_SEPARATOR = "\n"

        /**
         * 无 Shizuku 时的手动装入命令（需要 root shell）。
         *
         * 必须是「合并」语义而不是 `mv`：游戏跑过一次就会在 UnityCache 下生成
         * 非空的 Shared 目录，此时 `mv src/Shared dst/` 会因目标已存在而直接失败
         * （`Directory not empty`），一个文件都不会被装入。`cp -rf src/. dst/`
         * 才是把内容逐个合并进去。
         */
        /** 系统拒绝启动前台服务时给用户的话（T15）。任务没开跑，所以只需让用户回到前台重试。 */
        const val FGS_REFUSED_MESSAGE = "系统不允许在后台启动任务，请回到应用界面后重试"

        const val MANUAL_INSTALL_COMMAND =
            "cp -rf /storage/emulated/0/Download/Shared/. " +
            "/storage/emulated/0/Android/data/com.neowizgames.game.browndust2/files/UnityCache/Shared/ " +
            "&& rm -rf /storage/emulated/0/Download/Shared"

        /** UnityFS bundle 文件头的魔数（"UnityFS" + 结尾 NUL，共 8 字节），产物 __data 的损坏判定用。
         *  用字节字面量而不是字符串：源码字符串里的裸 NUL 既看不见也容易被编辑工具吞掉。 */
        private val UNITYFS_MAGIC = byteArrayOf(0x55, 0x6E, 0x69, 0x74, 0x79, 0x46, 0x53, 0x00)
    }

    private fun shouldIgnoreModEntry(entryName: String?): Boolean {
        val name = entryName?.substringAfterLast('/')?.trim()?.lowercase() ?: return true
        // T62：`.nomedia` 媒体扫描隔离标记同样不参与 MOD 枚举/签名/重打包输入。
        return name.isEmpty() || name == ".modfile" || name.endsWith(".modfile") ||
            com.bd2toolsbox.core.storage.MediaExclusionManager.shouldSkipInModScan(name)
    }


    /**
     * 干净判定的纯函数实现（T09）。无状态、无 `Context`，构造一次即可复用。
     *
     * 摘要用默认的流式 SHA-256 —— 最大 bundle 实测 414 MB，读进内存不是选项
     * （`StreamingDigest` 的 64 KiB 分块）。
     */
    private val verifier = PristineVerifier()

    /**
     * 全部 mod 源目录。
     *
     * 原先只记一个：选哪个就只显示哪个，换目录等于把上一个的 mod 全从列表里抹掉 ——
     * 而 mod 本来就常散在好几处（按作者分的、下载堆着的、自己整理过的）。
     *
     * 只存 URI、不把文件拷到一处：拷贝动辄几个 GB，而且会废掉用户原有的目录结构。
     * 扫描时遍历全部目录再合并，按 mod uri 去重（用户可能同时加了 A 和 A/sub）。
     */
    private val _modSourceDirs = MutableStateFlow<List<Uri>>(emptyList())
    val modSourceDirs: StateFlow<List<Uri>> = _modSourceDirs.asStateFlow()

    /**
     * 「有没有目录」以及「其中某一个」。
     *
     * 保留这个是为了让判空的地方（欢迎页、下拉刷新等）不必都改成查列表长度；
     * 值取列表第一个，null 表示一个目录都没有。
     */
    val modSourceDirectoryUri: StateFlow<Uri?> = _modSourceDirs
        .map { it.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _modsList = MutableStateFlow<List<ModInfo>>(emptyList())
    val modsList: StateFlow<List<ModInfo>> = _modsList.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _modScanProgress = MutableStateFlow<ModScanProgress?>(null)
    val modScanProgress: StateFlow<ModScanProgress?> = _modScanProgress.asStateFlow()

    private val _isUpdatingCharacters = MutableStateFlow(false)
    val isUpdatingCharacters: StateFlow<Boolean> = _isUpdatingCharacters.asStateFlow()

    val showShimmer: StateFlow<Boolean> =
        combine(_modsList, _isLoading, _isUpdatingCharacters) { mods, isScanning, isUpdating ->
            (isScanning || isUpdating) && mods.isEmpty()
        }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), false)

    private val _selectedMods = MutableStateFlow<Set<Uri>>(emptySet())
    val selectedMods: StateFlow<Set<Uri>> = _selectedMods.asStateFlow()

    /** T27：用户设置的唯一读写入口（保留 SharedPreferences 键值）。 */
    private val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(
            SharedPreferencesSettingsStore(
                appContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            ),
            AppTheme.entries.map { it.name }.toSet()
        )
    }

    /** T27：明/暗/跟随系统。缺失默认 LIGHT；接入主题由 T39/T47。 */
    private val _themeMode = MutableStateFlow(ThemeMode.LIGHT)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        settingsRepository.saveAppearance(settingsRepository.appearance().copy(themeMode = mode))
    }

    private val _useAstc = MutableStateFlow(false)

    /** 装入前是否自动备份原版 bundle，让卸载能本地秒还原而不必重新下载。默认开。 */
    private val _backupOriginals = MutableStateFlow(true)
    val backupOriginals: StateFlow<Boolean> = _backupOriginals.asStateFlow()

    /**
     * 跟随壁纸取色（Material You）。默认**关** —— 壁纸配色未必和界面里那套状态色协调，
     * 交给用户主动开。Android 12 以下即便开了也无效（主题里会忽略）。
     */
    private val _dynamicColor = MutableStateFlow(false)
    val dynamicColor: StateFlow<Boolean> = _dynamicColor.asStateFlow()

    /** 手选的配色预设。开了「跟随壁纸取色」时它让位（系统那套没法再叠预设）。 */
    private val _appTheme = MutableStateFlow(AppTheme.PURPLE)
    val appTheme: StateFlow<AppTheme> = _appTheme.asStateFlow()

    fun setAppTheme(theme: AppTheme) {
        _appTheme.value = theme
        settingsRepository.saveAppearance(settingsRepository.appearance().copy(colorPreset = theme.name))
    }

    /**
     * 自定义壁纸的 SAF URI；null = 不用壁纸。
     *
     * 存 URI 而不是把图拷进 app 目录：省一份体积，用户换图也不必再来一趟。
     * 代价是要拿持久化读权限（见 [setWallpaper]），否则重启后读不到。
     */
    private val _wallpaperUri = MutableStateFlow<Uri?>(null)
    val wallpaperUri: StateFlow<Uri?> = _wallpaperUri.asStateFlow()

    /**
     * 内容层盖在壁纸上的遮罩不透明度，0f = 壁纸全见（字最难读）、1f = 完全挡住壁纸。
     *
     * 有这个滑块是因为「壁纸好看」和「字看得清」天生冲突，而哪张图配多少合适
     * 只有用户自己知道 —— 与其我定死一个值，不如给条滑块。默认 0.82：
     * 实测这个值下浅色图能看清正文，深色图也还能辨认壁纸内容。
     */
    private val _wallpaperScrim = MutableStateFlow(0.82f)
    val wallpaperScrim: StateFlow<Float> = _wallpaperScrim.asStateFlow()

    fun setWallpaperScrim(v: Float) {
        val clamped = v.coerceIn(0f, 1f)
        _wallpaperScrim.value = clamped
        settingsRepository.saveAppearance(settingsRepository.appearance().copy(scrim = clamped))
    }

    /** 选好壁纸后调用。会尝试拿持久化读权限，拿不到也照存 —— 本次运行仍能显示。 */
    fun setWallpaper(uri: Uri?) {
        val ctx = appContext
        if (uri != null && ctx != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                // 有些来源（相册的临时项等）给不了持久权限。不拦：本次能看，
                // 重启后读不到时 loadWallpaper 会返回 null，界面自动退回纯色。
                Log.w("MainViewModel", "壁纸未能取得持久权限，重启后可能失效", e)
            }
        }
        _wallpaperUri.value = uri
        settingsRepository.saveAppearance(
            settingsRepository.appearance().copy(wallpaperUri = uri?.toString())
        )
    }

    /** 备份占用（字节数, 个数），供设置区显示。 */
    private val _backupUsage = MutableStateFlow(0L to 0)
    val backupUsage: StateFlow<Pair<Long, Int>> = _backupUsage.asStateFlow()
    val useAstc: StateFlow<Boolean> = _useAstc.asStateFlow()

    private val _selectedQuality = MutableStateFlow("HD")
    val selectedQuality: StateFlow<String> = _selectedQuality.asStateFlow()

    private val _isSearchActive = MutableStateFlow(false)
    val isSearchActive: StateFlow<Boolean> = _isSearchActive.asStateFlow()

    /**
     * 搜索词与状态筛选（T28：**落 SavedStateHandle**）。
     *
     * 以前它们是裸 `MutableStateFlow`，旋转屏幕或进程被回收重建后筛选条件就丢了 ——
     * 用户回到「全部」的几十上百条里重新找一遍。`savedStateHandle` 早就在构造函数里
     * 注入着，但**一次都没被用过**（死参数）。
     *
     * 只存这两个**小型恢复参数**（一个字符串 + 一个枚举名）：不塞 MOD 列表、
     * 不塞图片、不塞大批文件对象 —— 那些由仓库与任务状态自己恢复。
     */
    private val _searchQuery = MutableStateFlow(savedStateHandle.get<String>(KEY_SEARCH_QUERY) ?: "")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /** 状态筛选。null = 全部。上百个 mod 时靠它快速找出「当前生效的是哪些」。 */
    private val _stateFilter = MutableStateFlow(
        savedStateHandle.get<String>(KEY_STATE_FILTER)
            ?.let { name -> ModInstallState.entries.firstOrNull { it.name == name } }
    )
    val stateFilter: StateFlow<ModInstallState?> = _stateFilter.asStateFlow()

    fun setStateFilter(state: ModInstallState?) {
        _stateFilter.value = if (_stateFilter.value == state) null else state
        persistFilterState()
    }

    private fun persistFilterState() {
        savedStateHandle[KEY_SEARCH_QUERY] = _searchQuery.value
        savedStateHandle[KEY_STATE_FILTER] = _stateFilter.value?.name
    }

    val filteredModsList: StateFlow<List<ModInfo>> =
        combine(_modsList, _searchQuery, _stateFilter) { mods, query, filter ->
            val byState = if (filter == null) mods else mods.filter { it.installState == filter }
            if (query.isBlank()) {
                byState
            } else {
                val keywords = query.split(" ").filter { it.isNotBlank() }
                byState.filter { modInfo ->
                    keywords.all { keyword ->
                        modInfo.name.contains(keyword, ignoreCase = true) ||
                        modInfo.character.contains(keyword, ignoreCase = true) ||
                        modInfo.costume.contains(keyword, ignoreCase = true) ||
                        modInfo.type.contains(keyword, ignoreCase = true)
                    }
                }
            }
        }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyList())

    private val _installJobs = MutableStateFlow<List<InstallJob>>(emptyList())
    val installJobs: StateFlow<List<InstallJob>> = _installJobs.asStateFlow()

    /**
     * 转换 / 装入专用的作用域。**故意不用 viewModelScope，也故意不在 onCleared 里取消。**
     *
     * 用户反馈「装 mod 时放着不管容易被杀」。两个原因叠在一起：
     *   1. 没有前台服务，切后台后系统把整个进程当空闲后台回收
     *   2. 跑在 viewModelScope 上，用户退出界面时 ViewModel onCleared、协程被取消
     * 只补第 1 条（[InstallService]）解决不了第 2 条 —— 这是 v6.5 做预解包时踩过的坑。
     *
     * 重打包断在中途会在游戏目录留下半个 __data，比慢一点严重得多，所以这条流程
     * 一旦开始就该跑完。生命周期挂在进程上（前台服务负责让进程活着），
     * 而不是挂在界面上。
     */
    private val installScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _showInstallDialog = MutableStateFlow(false)
    val showInstallDialog: StateFlow<Boolean> = _showInstallDialog.asStateFlow()

    private val _finalInstallResult = MutableStateFlow<FinalInstallResult?>(null)
    val finalInstallResult: StateFlow<FinalInstallResult?> = _finalInstallResult.asStateFlow()
    
    // 批次處理開始時間（用於計算總耗時）
    private var batchStartTimeMs: Long = 0L

    private val _uninstallState = MutableStateFlow<UninstallState>(UninstallState.Idle)
    val uninstallState: StateFlow<UninstallState> = _uninstallState.asStateFlow()

    private val _unpackState = MutableStateFlow<UnpackState>(UnpackState.Idle)
    val unpackState: StateFlow<UnpackState> = _unpackState.asStateFlow()

    private val _unpackInputFile = MutableStateFlow<Uri?>(null)
    val unpackInputFile: StateFlow<Uri?> = _unpackInputFile.asStateFlow()

    private val _mergeState = MutableStateFlow<MergeState>(MergeState.Idle)
    val mergeState: StateFlow<MergeState> = _mergeState.asStateFlow()

    private val _showMergeDialog = MutableStateFlow(false)
    val showMergeDialog: StateFlow<Boolean> = _showMergeDialog.asStateFlow()

    private val _moveState = MutableStateFlow<MoveState>(MoveState.Idle)
    val moveState: StateFlow<MoveState> = _moveState.asStateFlow()

    private val _bundleScanState = MutableStateFlow<BundleScanState>(BundleScanState.Idle)
    val bundleScanState: StateFlow<BundleScanState> = _bundleScanState.asStateFlow()

    private val _showVersionMismatchWarning = MutableStateFlow(false)
    val showVersionMismatchWarning: StateFlow<Boolean> = _showVersionMismatchWarning.asStateFlow()

    // Stored for deferred scan execution after user confirmation
    private var pendingCheckResult: BundleCheckResult? = null
    // appContext 已改为构造注入（见主构造器）。

    private var initialized = false
    private var scanJob: Job? = null
    private var pendingScan: Boolean = false

    /**
     * 清掉上次遗留的预览临时目录。
     *
     * SpinePreviewActivity.onDestroy 会删自己那份，但预览界面被强杀、或 app 整体被系统
     * 回收时不会走到，残留就永久留在 cache 里。一次预览解出的素材有好几 MB（实测约 5 MB），
     * 攒多了不小。启动时统一收一遍，比在各处补 try/finally 可靠。
     */
    private fun cleanStalePreviewDirs(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var freed = 0L
                var n = 0
                context.cacheDir.listFiles()?.forEach { f ->
                    if (f.isDirectory && f.name.startsWith("spine_preview_")) {
                        freed += f.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
                        if (f.deleteRecursively()) n++
                    }
                }
                if (n > 0) {
                    Log.d("MainViewModel", "清理遗留预览目录 $n 个，释放 ${freed / 1024 / 1024} MB")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * T17：仓储与 appContext 都已构造注入，本方法不再需要传 context，只负责一次性启动
     * 装配（恢复设置 / 壁纸 / 目录、启动核对与迁移、异步初始化）。仍用 initialized 幂等守卫。
     */
    fun initialize() {
        if (initialized) return
        initialized = true
        val context = appContext

        // 界面重建恢复：扫描在 installScope（进程级）上跑，MIUI 等激进 ROM 会把
        // 后台 Activity 连同 ViewModel 回收掉 —— 用户从通知栏点回来时拿到的是新
        // VM，_bundleScanState 归零成 Idle，进度框消失（真机场景 B）。扫描还在跑
        // （scanRunning 是进程级标志），这里把 Scanning 状态接回来，后续进度由
        // 扫描循环的 viewModelScope 更新继续驱动。总进度未知就显示 -1（不确定态）。
        if (ShizukuManager.scanRunning && _bundleScanState.value is BundleScanState.Idle) {
            _bundleScanState.value = BundleScanState.Scanning(
                currentIndex = -1, totalCount = -1, currentBundle = "", progressMessage = "正在建立文件索引"
            )
        }

        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        restoreSettings(prefs)
        restoreWallpaper(prefs)
        restoreModSourceDirs(prefs)
        watchPrepackCompletion()
        cleanStalePreviewDirs(context)  // context == appContext
        // 核对必须**早于**旧格式迁移：迁移会 clear() 掉账本，之后再核对就是拿着一张
        // 被清空的表去判「账本里有没有这条记录」，会凭空多出一次待核对。
        // 两者都触发时对话框归核对那条（它可操作：别把这些 mod 重复装入），
        // 迁移那条仍有 Log.i（`检测到旧格式账本（n 条），已清空`）留痕，不是静默丢弃。
        checkRecoveryOnStartup()
        migrateLegacyLedgerIfNeeded()
        // 冷启动先把上次扫描的结果填上（几毫秒的事），真正的扫描随后在后台跑完再整表替换 ——
        // 「全部」那一页因此不用再对着空列表等整个 SAF 扫描
        restoreModListSnapshot()

        startAsyncInitialization(context)
    }

    /** 读回全部用户设置项。 */
    private fun restoreSettings(prefs: android.content.SharedPreferences) {
        val appearance = settingsRepository.appearance()
        _useAstc.value = settingsRepository.useAstc()
        _backupOriginals.value = settingsRepository.backupOriginals()
        _dynamicColor.value = appearance.dynamicColor
        // 画质只认 SD/HD；历史版本存过 FHD（与 HD 同源的假档位），读回归一成 HD
        _selectedQuality.value = settingsRepository.quality()
        _appTheme.value = AppTheme.fromName(appearance.colorPreset)
        _themeMode.value = appearance.themeMode
        _onboardingDone.value = settingsRepository.onboardingDone()
        _wallpaperScrim.value = appearance.scrim
    }

    /** 恢复壁纸，但只在持久读权限还在时 —— 权限被系统回收后那个 URI 读不出图，
     *  界面会是一片空白背景，不如退回纯色（顺手把死键清掉）。 */
    private fun restoreWallpaper(prefs: android.content.SharedPreferences) {
        settingsRepository.appearance().wallpaperUri?.let { saved ->
            try {
                val uri = Uri.parse(saved)
                val readable = appContext!!.contentResolver.persistedUriPermissions.any {
                    it.uri == uri && it.isReadPermission
                }
                if (readable) _wallpaperUri.value = uri
                else settingsRepository.clearWallpaperUri()
            } catch (e: Exception) {
                settingsRepository.clearWallpaperUri()
            }
        }
    }

    /** 恢复上次的 mod 文件夹们，避免每次启动都回到欢迎页。
     *  只保留持久权限还在的 —— 权限被回收的目录读不出任何东西，留在列表里
     *  只会让人以为 mod 丢了。v9.3 及以前存的是单目录键，这里一并迁移。
     *
     *  T15：去掉的目录要告诉用户（[lostSourceDirsNotice]）。之前是静默去掉，
     *  全部目录都被撤销时界面直接回到欢迎页 / 空列表，看起来像「mod 全没了」，
     *  用户无从知道其实是授权没了、重新选一次目录就好。 */
    private fun restoreModSourceDirs(prefs: android.content.SharedPreferences) {
        val raw: List<String> = settingsRepository.sourceDirs()
        val granted = appContext!!.contentResolver.persistedUriPermissions
        val alive = raw.mapNotNull { s ->
            try {
                val uri = Uri.parse(s)
                if (granted.any { it.uri == uri && it.isReadPermission }) uri else null
            } catch (e: Exception) {
                null
            }
        }
        _modSourceDirs.value = alive
        val lost = raw.filter { s -> alive.none { it.toString() == s } }
        if (lost.isNotEmpty()) {
            Log.w("MainViewModel", "${lost.size} 个 mod 文件夹的访问授权已失效，已移出列表")
            _lostSourceDirsNotice.value = lost
        }
        if (alive.size != raw.size) {
            // 有目录被移除（或完成了旧单键迁移）：落一次新格式
            settingsRepository.setSourceDirs(alive.map { it.toString() })
        }
    }

    /** 预解包结束（进度由非空变回 null）后刷新缓存用量。
     *  解包循环在前台服务里，服务够不到 ViewModel，只能由这边观察它的进度收尾。 */
    private fun watchPrepackCompletion() {
        viewModelScope.launch {
            var wasRunning = false
            PrepackService.progress.collect { p ->
                val running = p != null
                if (wasRunning && !running) refreshPreviewCacheUsage()
                wasRunning = running
            }
        }
    }

    /** 旧版账本一条记录含多个 modUris 却只有一个 familyKey，还原不出 uri 与 familyKey
     *  的对应关系，没法安全迁移，只能丢弃重建。账本仅是记账：清空不动游戏里的实际
     *  文件；装过的 mod 会暂时显示成「被其他工具改过」，重装一次即恢复。 */
    private fun migrateLegacyLedgerIfNeeded() {
        if (!installedModRepository.needsReset()) return
        val n = try {
            installedModRepository.load().size
        } catch (e: Exception) {
            0
        }
        installedModRepository.clear()?.let { reportLedgerWrite(LedgerWrite.Failed(it), "旧格式账本清空") }
        // 走 notifyLedgerNotice 而不是直接赋值：T08 二轮整改在它之前加了启动核对
        // （见 checkRecoveryOnStartup），两者都有话说时不能互相覆盖 ——
        // 先发生的（核对）更接近根因，且它给的是可操作的提示。
        notifyLedgerNotice(
            "装入记录的格式已升级（旧版把同一资源包里的多个 mod 记成一条，会导致卸载时" +
                "连带卸掉别的 mod）。旧记录已清空，游戏里的文件没有动。" +
                "重新装一次即可恢复状态显示。"
        )
        Log.i("MainViewModel", "检测到旧格式账本（$n 条），已清空")
    }

    /**
     * 启动期的异步两步：
     *   1. 刷新角色表（顺带拉起 Python 运行时）
     *   2. Shizuku 在线时对照缓存检查游戏 bundle，要重扫就弹确认框
     *
     * 第 2 步排在第 1 步后，沿用角色表刷新后再检查游戏资源的业务顺序；PythonRuntime 统一同步初始化。
     * 角色表刚换版本、游戏 bundle 却没变化时，多半是游戏还没更新 —— 提醒用户去更新。
     * 需要用户确认扫描的话，初始化收尾推迟到确认之后（finishInitialization）。
     */
    private fun startAsyncInitialization(context: Context) {
        viewModelScope.launch {
            var requiresDeferredInitialization = false
            try {
                _isUpdatingCharacters.value = true
                val hadLocalCharacters = characterRepository.hasLocalCharactersJson()
                // 本地已有角色表就先读出来把界面填上：版本检查只是一次网络确认
                //（常态几秒钟、结果多为「没新版」），没必要让用户对着「首次启动
                // 需要联网下载」的空屏干等 —— 缓存明明是好的，观感像缓存坏了。
                if (hadLocalCharacters) refreshCharacterNames()

                val updateStatus = characterRepository.updateCharacterData(_selectedQuality.value)
                val charactersWereRefreshed = (updateStatus == "SUCCESS" && hadLocalCharacters)
                if (!hadLocalCharacters || updateStatus == "SUCCESS") {
                    // 首次启动（空模板，必须等下载完才有得读）或刚换新版本（重读）
                    refreshCharacterNames()
                }

                if (ShizukuManager.isAvailable() && !ShizukuManager.scanRunning) {
                    // 扫描已在跑（installScope 上，可能跨了界面重建）时不能再做
                    // 启动期检查 —— check_scan_needed 会重置 python 侧的全局
                    // 扫描状态，把在途扫描搅掉。等它跑完，下次启动再对账。
                    val checkResult = withContext(Dispatchers.IO) {
                        ShizukuManager.checkLocalBundles(
                            outputDir = context.filesDir.absolutePath
                        ) { progress ->
                            Log.d("MainViewModel", "Bundle check: $progress")
                        }
                    }
                    if (checkResult != null) {
                        if (checkResult.needsScanCount > 0) {
                            pendingCheckResult = checkResult
                            _bundleScanState.value = BundleScanState.Confirmation(checkResult.needsScanCount)
                            requiresDeferredInitialization = true
                        } else if (charactersWereRefreshed) {
                            // 全部 bundle 都没变 + 角色表刚换版本 → 游戏本体还没更新。
                            // 不需要重扫时也别跑 finalizeScan —— 那会白白重解析一遍 catalog。
                            _showVersionMismatchWarning.value = true
                        }
                    }
                } else {
                    Log.d("MainViewModel", "Shizuku not available, skipping local bundle scan. Using cached index if available.")
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error during initialization", e)
            }

            if (!requiresDeferredInitialization) {
                finishInitialization()
            }
        }

        // 所有 job 收尾后汇总结果。用 installScope 而不是 viewModelScope：
        // 汇总带着「自动移入游戏」这步，界面销毁就停止收集的话，
        // 转换跑完了却没人做最后那一下，产物白留在 Download/Shared。
        installScope.launch(Dispatchers.Main.immediate) {
            installJobs.collect { jobs ->
                if (jobs.isNotEmpty() && jobs.all { it.producesDownload } &&
                    jobs.all { it.status is JobStatus.Finished || it.status is JobStatus.Failed }) {
                    summarizeResults()
                }
            }
        }
    }

    /**
     * 添加一个 mod 源目录。返回 false 表示这个目录已经被覆盖、列表没有变化。
     *
     * 嵌套目录要处理，但**方向只能是一个**：新目录是某个已记住目录的下级时，什么都不做
     * （它的 mod 本来就已经在列表里了）；反过来新目录是上级时，把它下面那几条收掉。
     * 早先两个方向一起剔，于是「加了 BD2mods/pc_test」会把「BD2mods」顶掉 ——
     * 用户本想再添一个目录，眼前 50 个 mod 反而缩成几个。往小的方向改列表永远是错的。
     */
    fun addModSourceDir(context: Context, uri: Uri?): Boolean {
        if (uri == null) return false
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            Log.w("MainViewModel", "目录未能取得持久权限: $uri", e)
        }

        val incoming = uri.toString()
        val existing = _modSourceDirs.value

        // 已经在列表里，或被某个已记住的上级目录覆盖 —— 加进去只会让同一批 mod
        // 被扫两遍，而列表里还多一条看不出区别的路径。
        val coveredBy = existing.firstOrNull { old ->
            val o = old.toString()
            incoming == o || incoming.startsWith("$o/")
        }
        if (coveredBy != null) {
            Log.d("MainViewModel", "目录已被覆盖，不重复添加: $incoming（已有 $coveredBy）")
            rescanAllModSources()
            return false
        }

        val kept = existing.filterNot { it.toString().startsWith("$incoming/") }
        _modSourceDirs.value = kept + uri
        persistModSourceDirs()
        rescanAllModSources()

        // T62：给这个 MOD 专用目录放一个 `.nomedia`，否则解出来的贴图会进系统相册。
        // 这是 SAF（content URI）路径 —— 以前只实现了普通目录（java.io.File）那一半，
        // 用户通过目录选择器加的 MOD 目录**从来没有**被保护过。
        // 混合用途根默认拒绝（往 BD2mods 这种根上放标记会连带影响别的东西）。
        val exclusion = SafMediaExclusion.ensureMarker(context, uri)
        SafMediaExclusion.message(exclusion)?.let { message ->
            Log.w("MainViewModel", "媒体隔离：$message")
            _mediaExclusionNotice.value = message
        }
        return true
    }

    /** 媒体隔离标记未能建立时的告知；UI 消费后调 [clearMediaExclusionNotice]。 */
    private val _mediaExclusionNotice = MutableStateFlow<String?>(null)
    val mediaExclusionNotice: StateFlow<String?> = _mediaExclusionNotice.asStateFlow()

    fun clearMediaExclusionNotice() {
        _mediaExclusionNotice.value = null
    }

    /** 移除一个目录，并把它的持久权限一并放掉（不然会一直占着配额）。 */
    fun removeModSourceDir(uri: Uri) {
        _modSourceDirs.value = _modSourceDirs.value.filterNot { it == uri }
        persistModSourceDirs()
        try {
            appContext?.contentResolver?.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            Log.w("MainViewModel", "释放目录权限失败: $uri", e)
        }
        if (_modSourceDirs.value.isEmpty()) {
            _modsList.value = emptyList()
            _selectedMods.value = emptySet()
            _stateFilter.value = null
            // 一个目录都不剩：快照里的条目再也扫不到了，留着只会在下次冷启动显出一批
            // 点不动的幽灵行
            clearModListSnapshot()
        } else {
            rescanAllModSources()
        }
    }

    private fun persistModSourceDirs() {
        // 换行分隔而不是 JSON：URI 规范要求换行必须转义，所以它绝不会出现在值里，
        // 这么写就不必为一个字符串列表专门养一个 Gson 实例。
        settingsRepository.setSourceDirs(_modSourceDirs.value.map { it.toString() })
    }

    fun setUseAstc(useAstc: Boolean) {
        _useAstc.value = useAstc
        settingsRepository.setUseAstc(useAstc)
    }

    fun setBackupOriginals(enabled: Boolean) {
        _backupOriginals.value = enabled
        settingsRepository.setBackupOriginals(enabled)
    }

    fun setDynamicColor(enabled: Boolean) {
        _dynamicColor.value = enabled
        settingsRepository.saveAppearance(settingsRepository.appearance().copy(dynamicColor = enabled))
    }

    /** 重算备份占用（遍历备份目录，放 IO 线程）。 */
    fun refreshBackupUsage() {
        if (!true) return
        viewModelScope.launch(Dispatchers.IO) {
            val usage = backupRepository.totalBytes() to backupRepository.count()
            _backupUsage.value = usage
        }
    }

    /**
     * 清空原版备份（T44）。
     *
     * 与旧实现的区别（都是真实缺陷，不是风格）：
     * - 旧版直接 `clearAll()`（裸 `deleteRecursively()`）+ `_backupUsage.value = 0L to 0`，
     *   **删除失败时也把用量报成 0**；
     * - 新版逐 bundle 删除、**重新统计**剩余量，并把真实结果写进 [backupPurgeResult]，
     *   由界面如实展示（成功/部分成功/全失败三种）。
     *
     * 确认步骤（数量/空间/后果 + 复核目标集合）在界面层，见 `BackupPurge`。
     */
    fun clearBackups() {
        if (!true) return
        viewModelScope.launch(Dispatchers.IO) {
            val outcome = backupRepository.clearAllTracked()
            _backupUsage.value = backupRepository.totalBytes() to backupRepository.count()
            _backupPurgeResult.value = outcome
        }
    }

    /** 上一次清空备份的真实结果；null 表示本次会话还没清过。 */
    private val _backupPurgeResult = MutableStateFlow<PurgeOutcome?>(null)
    val backupPurgeResult: StateFlow<PurgeOutcome?> = _backupPurgeResult.asStateFlow()

    fun clearBackupPurgeResult() {
        _backupPurgeResult.value = null
    }

    /** 当前备份集合的指纹（确认后执行前复核用，T44）。 */
    fun backupFingerprint(): String = backupRepository.fingerprint()

    /** 是否仍有尚未收尾的转换/装入任务在跑（已完成待关闭弹窗的列表不算活跃任务）。 */
    private fun hasActiveInstallTask(): Boolean =
        moveInProgress || _installJobs.value.any {
            it.status !is JobStatus.Finished && it.status !is JobStatus.Failed
        }

    /**
     * 活跃任务与清空互斥（T44）：有任务在用时不允许清空。
     *
     * 返回阻塞原因，null 表示可以清。
     */
    fun backupPurgeBlocker(): String? {
        val busy = hasActiveInstallTask() ||
            prepackProgress.value != null ||
            ShizukuManager.scanRunning
        return if (busy) BackupPurge.blockedByActiveTask("安装/扫描/预解包") else null
    }

    // ------------------------------------------------ 批量预解包

    private val _prepackPlan = MutableStateFlow<PrepackPlan?>(null)
    val prepackPlan: StateFlow<PrepackPlan?> = _prepackPlan.asStateFlow()

    /**
     * 角色表里的全部角色名，供「按角色」主界面用。
     *
     * 必须是全量而非「有 mod 的那些」—— 用户也想看出自己还缺谁。
     * 角色表是启动时下载的，所以在这里按需读一次并缓存。
     */
    private val _allCharacterNames = MutableStateFlow<List<String>>(emptyList())
    val allCharacterNames: StateFlow<List<String>> = _allCharacterNames.asStateFlow()

    /** NPC（商店/路人模型）角色名，列表里排在可玩角色之后的单独分区。 */
    private val _npcCharacterNames = MutableStateFlow<List<String>>(emptyList())
    val npcCharacterNames: StateFlow<List<String>> = _npcCharacterNames.asStateFlow()

    private fun refreshCharacterNames() {
        val ctx = appContext ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val resolver = BundleNameResolver(ctx)
            val names = resolver.listAllCharacters()
            _allCharacterNames.value = names
            _npcCharacterNames.value = resolver.listNpcCharacters()
            Log.d("MainViewModel", "角色表收录 ${names.size} 个角色")

            // 顺手把随包的角色附加信息（性别/联动/中文名/头像文件名）建好索引。
            // 它读并解析 52 KB 的 assets json —— 放在这里做掉，界面就不会在
            // 第一次点筛选或画第一个头像时卡一下。
            val meta = characterMetaRepository
            val (male, female, collab) = meta.counts()
            Log.d(
                "MainViewModel",
                "角色附加信息 v${meta.version()}：男 $male / 女 $female / 联动 $collab"
            )
        }
    }

    /**
     * 头像预取失败（网络类异常、没补齐）—— 角色列表据此在正中提示「请检查网络」。
     *
     * 缓存在本地的那些照常显示：图源在 GitHub，国内直连常常整趟都拿不到，
     * 一片占位图标总得有个说法，否则用户只会以为功能坏了。
     */
    private val _avatarSyncFailed = MutableStateFlow(false)
    val avatarSyncFailed: StateFlow<Boolean> = _avatarSyncFailed.asStateFlow()

    private var avatarSyncWatcher: Job? = null

    /**
     * 启动后把全部角色头像补进本地缓存。**不阻塞扫描**：自己在后台协程里跑，
     * 初始化那两步该怎么走还怎么走。
     *
     * 只认「走过一趟没」：重复调用（初始化收尾、用户点重试）不会叠起来跑，
     * [AvatarRepository.syncAll] 自己也上了锁。
     */
    fun prefetchAvatars() {
        val repo = avatarRepository
        if (avatarSyncWatcher == null) {
            // 状态跟着仓库走：重试成功后提示要能自己消失
            avatarSyncWatcher = viewModelScope.launch {
                repo.syncState.collect {
                    _avatarSyncFailed.value = it == AvatarRepository.SyncState.FAILED
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) { repo.syncAll() }
    }

    /** 用户点「请检查网络」那条提示时的手动重试。 */
    fun retryAvatarSync() = prefetchAvatars()

    /** 旧格式账本被清空时的一次性告知；null 表示无需提示。 */
    private val _ledgerResetNotice = MutableStateFlow<String?>(null)
    val ledgerResetNotice: StateFlow<String?> = _ledgerResetNotice.asStateFlow()

    fun dismissLedgerResetNotice() {
        _ledgerResetNotice.value = null
    }

    /** 启动时因授权失效被移出列表的 mod 文件夹（tree URI 字符串）；null = 没有要说的。 */
    private val _lostSourceDirsNotice = MutableStateFlow<List<String>?>(null)
    val lostSourceDirsNotice: StateFlow<List<String>?> = _lostSourceDirsNotice.asStateFlow()

    fun dismissLostSourceDirsNotice() {
        _lostSourceDirsNotice.value = null
    }

    /**
     * 启动核对结论：上次那次装入提交有没有留下「文件已换、账本未存」的窗口
     * （T08 二轮整改，复核真缺陷 2）。
     *
     * ## 为什么必须有这条通道
     *
     * T08 一轮把 `InstalledModRepository.checkRecovery()` 的**判据**做扎实了（21 条用例
     * 覆盖三态），但生产代码里**没有一个调用者**，也没有任何 UI 渲染
     * `RecoveryVerdict.PendingReconciliation` —— 于是验收第 2 条「重启后进入明确的
     * 待核对状态」在交付代码里**不可能发生**。初始化路径上调用一次并把结论放进这个
     * StateFlow，就是让那句话真的成立。
     *
     * ## 为什么用三态的 `RecoveryVerdict` 而不是 `Boolean`
     *
     * `Unknown`（journal 读不出来）必须能与 `Consistent`（没问题）区分开 ——
     * 把「不知道」渲染成「一切正常」正是计划验收禁止的「自动伪装成全成功」。
     * UI 侧因此只对 `PendingReconciliation` 弹提示，`Unknown` 只记日志。
     *
     * 初值 `Consistent` 而不是 null：核对是启动路径上的一次同步读，
     * 「还没核对」这个中间态对用户没有意义（它转瞬即逝），
     * 而 null 会让 UI 侧多一个必须处理的分支。
     */
    private val _recoveryVerdict =
        MutableStateFlow<OperationJournalStore.RecoveryVerdict>(
            OperationJournalStore.RecoveryVerdict.Consistent
        )
    val recoveryVerdict: StateFlow<OperationJournalStore.RecoveryVerdict> =
        _recoveryVerdict.asStateFlow()

    /**
     * T25：启动核对的细分结论（中断 / 已替换待核对 / 已提交 / 未知）。
     * 与 [recoveryVerdict] 同一次启动检查产出；UI 尚未消费，保留为恢复入口。
     */
    /**
     * 初始化完成的一次性信号（T32）。
     *
     * 自动检查更新以前直接在这里调 checkForUpdates(auto = true)；更新逻辑搬进
     * UpdateViewModel 之后，**ViewModel 不互相注入**（T55 的 R6），改由界面观察这个
     * 信号后转交 UpdateViewModel。
     */
    private val _initializationCompleted = MutableStateFlow(false)
    val initializationCompleted: StateFlow<Boolean> = _initializationCompleted.asStateFlow()

    private val _recoveryDispositions = MutableStateFlow<List<RecoveryDisposition>>(emptyList())
    val recoveryDispositions: StateFlow<List<RecoveryDisposition>> =
        _recoveryDispositions.asStateFlow()

    /**
     * 启动时核对一次「文件已换、账本未存」的窗口，并把结论交给 UI。
     *
     * **只读**：不改账本、不改 journal、不改游戏目录（见
     * [InstalledModRepository.checkRecovery] 的说明），它只交出一份判断。
     */
    private fun checkRecoveryOnStartup() {
        if (!true) return
        val verdict = try {
            installedModRepository.checkRecovery()
        } catch (e: Exception) {
            // 核对本身抛了也不能挡住启动：降级成 Unknown，如实告诉上层「不知道」，
            // 而不是悄悄当成「没问题」。
            Log.w("MainViewModel", "启动核对失败", e)
            OperationJournalStore.RecoveryVerdict.Unknown(
                OperationError(
                    kind = OperationErrorKind.UNKNOWN,
                    message = "装入核对异常：" + e.message.orEmpty(),
                    detail = e.toString()
                )
            )
        }
        _recoveryVerdict.value = verdict
        // T25：同一次启动检查里再给一份按操作细分的处置（只读 journal，不重放）。
        _recoveryDispositions.value = try {
            OperationRecovery(installedModRepository).classify()
        } catch (e: Exception) {
            Log.w("MainViewModel", "启动恢复分类失败", e)
            emptyList()
        }
        when (verdict) {
            is OperationJournalStore.RecoveryVerdict.PendingReconciliation -> {
                Log.w(
                    "MainViewModel",
                    "启动核对：上次装入可能有「文件已换、账本未存」的窗口 —— " +
                        "bundle=${verdict.bundleNames} mod=${verdict.modUris}"
                )
                // 与账本提示共用一条通道：都是「装入记录出了一件你必须知道的事」，
                // 分成两个对话框只会让用户要连着关两次。
                notifyLedgerNotice(
                    "上次装入可能没有完整收尾：游戏里的文件已经换成新的，但装入记录没来得及更新" +
                        "（涉及 ${verdict.bundleNames.size} 个资源包：" +
                        verdict.bundleNames.take(3).joinToString("、") +
                        (if (verdict.bundleNames.size > 3) " 等" else "") +
                        "）。请用「核对并修复记录」按目标内容补账，不要重新安装。"
                )
            }
            is OperationJournalStore.RecoveryVerdict.Unknown -> {
                // 不打扰用户：journal 读不出来不损失用户数据（真实事实在游戏目录与账本里），
                // 但也不能当成「没问题」—— 记日志留痕。
                Log.w("MainViewModel", "启动核对：操作日志读不出来，本次无法确认：${verdict.error.message}")
            }
            OperationJournalStore.RecoveryVerdict.Consistent -> Unit
        }
    }

    /**
     * 批量预解包进度。null 表示没在跑。
     *
     * 真正的进度源在 [PrepackService]（它与本进程同生共死，直接共享 StateFlow）。
     * 用 Eagerly 而非 WhileSubscribed：设置弹层关掉后仍要能感知任务结束。
     *
     * 直接把 [PrepackService.Progress] 透出去，不压成 Triple —— 加了 detail
     * （「读取 62%」/「解包 12/48」）之后四个字段，Triple 装不下，而且靠位置取值
     * 本来就容易搞错。
     * （「读取 62%」/「解包 12/48」）之后四个字段，Triple 装不下，而且靠位置取值
     * 本来就容易搞错。
     */
    val prepackProgress: StateFlow<PrepackService.Progress?> =
        PrepackService.progress
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)

    /**
     * 估算「把所有已转换产物都预解包」需要多少空间，并与剩余可用空间比对。
     *
     * 膨胀比优先用已有缓存实测出来的（解包后素材 ÷ 源 `__data`），没有样本时退回 1.6 ——
     * 这个数来自实测：4.0 MB 的产物解出约 6.4 MB 素材。拍系数不如量，但总比不提示好。
     */
    fun preparePrepack() {
        val context = appContext ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val free = try {
                (context.getExternalFilesDir(null) ?: context.filesDir).freeSpace
            } catch (e: Exception) {
                0L
            }
            // T24：计划与实际目标同源（PrepackExecutor），负缓存不再被算成待办。
            _prepackPlan.value = prepackExecutor.plan(_modsList.value, free)
        }
    }

    fun dismissPrepackPlan() {
        _prepackPlan.value = null
    }

    /**
     * 批量预解包：把所有还没缓存的产物依次解包填进缓存，之后预览都是秒开。
     *
     * 实际的解包循环在 [PrepackService] 里跑，本函数只负责挑出待办目标并把它交出去。
     * 之所以不留在 viewModelScope：那样用户一退出界面 ViewModel 就 onCleared、
     * 协程随之取消，十几分钟的任务几乎不可能跑完。前台服务才撑得住。
     *
     * 串行执行 —— 解包是 CPU 密集（要解 ASTC 再压 PNG），并行只会互相抢核，还更容易 OOM。
     * 缓存逐个提交，所以即便中途被杀，下次再点也会跳过已完成的接着做，不会白干。
     */
    fun startPrepack() {
        val context = appContext ?: return
        val plan = _prepackPlan.value
        _prepackPlan.value = null
        if (plan == null || plan.todo == 0) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // T24：目标筛选收进 PrepackExecutor，与计划同源；执行器产出的
                // PrepackTarget 直接就是服务要的类型（不再是两套同形类型互转）。
                val targets = prepackExecutor.selectTargets(_modsList.value)
                if (targets.isEmpty()) return@launch
                Log.d("MainViewModel", "交给前台服务预解包 ${targets.size} 个")
                if (!PrepackService.start(context, targets)) {
                    withContext(Dispatchers.Main) { toast(context, FGS_REFUSED_MESSAGE) }
                }
            } catch (e: Exception) {
                // 这里抛过的最真实事故：大批量目标走 Intent 顶爆 Binder 上限
                // （TransactionTooLargeException）。目标已改进程内直传，但兜底要留 ——
                // 启动服务这条路再出任何意外，用户至少能看到一句话而不是无声无息。
                Log.e("MainViewModel", "预解包启动失败", e)
                withContext(Dispatchers.Main) {
                    toast(context, "预解包没能启动：${e.message ?: "未知错误"}")
                }
            }
        }
    }

    fun cancelPrepack() {
        val context = appContext ?: return
        PrepackService.cancel(context)
        refreshPreviewCacheUsage()
    }

    /** 预览缓存占用（字节数, 条目数）。 */
    private val _previewCacheUsage = MutableStateFlow(0L to 0)
    val previewCacheUsage: StateFlow<Pair<Long, Int>> = _previewCacheUsage.asStateFlow()

    fun refreshPreviewCacheUsage() {
        if (!true) return
        viewModelScope.launch(Dispatchers.IO) {
            _previewCacheUsage.value =
                previewCacheRepository.totalBytes() to previewCacheRepository.count()
        }
    }

    /**
     * 清空预览缓存（T29）。
     *
     * 与旧实现的区别（真实缺陷，不是风格）：
     * - 旧版 `clearAll()` 返回 `Unit`，删完**无条件**把用量置 `0L to 0` ——
     *   删除失败时界面上显示「已清空」，实际一个字节没删；
     * - 新版拿 `PurgeOutcome`，并**重新测量**剩余量（来自实际磁盘，
     *   不是从「应该删了多少」推出来的），由界面如实展示。
     *
     * 与活跃任务互斥：预解包正在往这个目录里写，边写边删会把在途产物弄坏。
     */
    fun clearPreviewCache() {
        previewCacheBlocker()?.let { reason ->
            notifyLedgerNotice(reason)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val outcome = previewCacheRepository.clearAll()
            _previewCacheUsage.value = outcome.remainingBytes to outcome.remainingCount
            _previewPurgeResult.value = outcome
        }
    }

    /** 上一次清空预览缓存的真实结果；null 表示本次会话还没清过。 */
    private val _previewPurgeResult = MutableStateFlow<PurgeOutcome?>(null)
    val previewPurgeResult: StateFlow<PurgeOutcome?> = _previewPurgeResult.asStateFlow()

    fun clearPreviewPurgeResult() {
        _previewPurgeResult.value = null
    }

    /**
     * 预览缓存清理与活跃任务互斥（T29）。
     *
     * 返回阻塞原因，null 表示可以清。预解包/预览正在跑时不能清 ——
     * 它们正在往这个目录里写。
     */
    fun previewCacheBlocker(): String? {
        val busy = hasActiveInstallTask() ||
            prepackProgress.value != null ||
            previewState.value is PreviewState.Preparing
        return if (busy) "有任务正在进行（装入/预解包/预览），先等它收尾再清缓存。" else null
    }

    /**
     * Spine 运行时（pixi-spine）的占用字节数，0 表示还没下过。
     *
     * 它不随安装包分发（专有许可，与本项目 GPLv3 不兼容），首次预览时才下一份，
     * 所以要有个地方让用户看到「它在不在、占多少」，以及下坏了能清掉重下 ——
     * 预览页的失败提示里就是让用户来这儿清的。
     */
    private val _spineRuntimeUsage = MutableStateFlow(0L)
    val spineRuntimeUsage: StateFlow<Long> = _spineRuntimeUsage.asStateFlow()

    fun refreshSpineRuntimeUsage() {
        viewModelScope.launch(Dispatchers.IO) {
            _spineRuntimeUsage.value = spineRuntimeRepository.usage()
        }
    }

    /**
     * 清掉 Spine 运行时组件（T29）。
     *
     * 与旧实现一样要**如实反馈**：`clear()` 现在返回 `PurgeOutcome`，
     * 用量来自重新测量而不是「删过了就当 0」。
     */
    fun clearSpineRuntime() {
        viewModelScope.launch(Dispatchers.IO) {
            val outcome = spineRuntimeRepository.clear()
            _spineRuntimeUsage.value = outcome.remainingBytes
            _spinePurgeResult.value = outcome
        }
    }

    /** 上一次清理 Spine 运行时的真实结果；null 表示本次会话还没清过。 */
    private val _spinePurgeResult = MutableStateFlow<PurgeOutcome?>(null)
    val spinePurgeResult: StateFlow<PurgeOutcome?> = _spinePurgeResult.asStateFlow()

    fun clearSpinePurgeResult() {
        _spinePurgeResult.value = null
    }

    /**
     * 使用引导看过了没有。false 时盖在主界面之上。
     *
     * 初值给 true（不显示）而不是 false：[initialize] 是在 setContent 之前跑的、
     * 读 prefs 又是同步的，所以真实值第一帧就位；万一哪天调用顺序变了，
     * 宁可老用户少看一次引导，也别让每个人启动时都闪一下引导页。
     */
    private val _onboardingDone = MutableStateFlow(true)
    val onboardingDone: StateFlow<Boolean> = _onboardingDone.asStateFlow()

    fun completeOnboarding() {
        _onboardingDone.value = true
        settingsRepository.setOnboardingDone(true)
    }

    /** 设置里「重看使用引导」。只改内存状态，不动 prefs —— 看完照样算看过。 */
    fun replayOnboarding() {
        _onboardingDone.value = false
    }

    fun setSelectedQuality(quality: String) {
        _selectedQuality.value = quality
        settingsRepository.setQuality(quality)
    }

    // ------------------------------------------------ 一键卸载

    private val _uninstallPlan = MutableStateFlow<UninstallPlan?>(null)
    val uninstallPlan: StateFlow<UninstallPlan?> = _uninstallPlan.asStateFlow()

    /**
     * 算一遍"要卸载什么、代价多大"，不动任何文件。
     *
     * 关键：判断依据是干净检测（游戏目录里 __data 的实际大小 ≠ catalog 记录的原版大小），
     * **不依赖装入记账**。所以用户手动拷进游戏目录的 mod 同样能被发现并卸掉 —— 这正是
     * "一键恢复原状"该有的语义。
     */
    fun prepareUninstallAll() {
        appContext ?: return
        viewModelScope.launch {
            _isUninstallScanning.value = true
            try {
                withContext(Dispatchers.IO) { refreshBundleCleanStates() }
                // 检测本身没跑成功就别给计划：0 个 bundle 的计划看起来跟「已经很干净」
                // 一模一样，而这两件事的后果完全相反。
                cleanScanError?.let { err ->
                    // 分类与文案分开：文案给人看，类别给用例/UI 判「要不要让人重试」。
                    // 这条路上的两种成因（Shizuku 拿不到游戏目录 / 取不到官方清单）
                    // 都是「连接层面拿不到判定依据」，可重试；细分成具体信号要等
                    // adapter 返回分类结果（见 T19/T23）。
                    _uninstallPlan.value = UninstallPlan(
                        emptyList(), 0, 0, 0L,
                        failure = OperationError(
                            kind = OperationErrorKind.NOT_CONNECTED,
                            message = err,
                            detail = cleanScanDetail
                        )
                    )
                    return@launch
                }
                val modified = bundleCleanStates.filterValues { it == BundleCleanState.MODIFIED }.keys.toList()
                var fromBackup = 0
                var needDownload = 0
                var bytes = 0L
                // refreshBundleCleanStates 刚取过表并存在 catalogBundleMeta（能走到
                // 这里说明 cleanScanError 为空、表已到手），直接复用，不再取一遍
                val meta = catalogBundleMeta
                for (name in modified) {
                    val hashDir = gameBundleHashes[name]
                    if (hashDir != null && backupRepository.hasBackup(name, hashDir)) {
                        fromBackup++
                    } else {
                        needDownload++
                        bytes += meta?.get(name)?.first ?: 0L
                    }
                }
                _uninstallPlan.value = UninstallPlan(modified, fromBackup, needDownload, bytes)
            } finally {
                _isUninstallScanning.value = false
            }
        }
    }

    private val _isUninstallScanning = MutableStateFlow(false)
    val isUninstallScanning: StateFlow<Boolean> = _isUninstallScanning.asStateFlow()

    fun dismissUninstallPlan() {
        _uninstallPlan.value = null
    }

    /**
     * 执行一键卸载：把所有被改过的 bundle 还原成官方原版。
     *
     * 复用装入那套流程 —— 每个 bundle 一个"空 mod 列表"的 RepackJob，[processSingleJob]
     * 见到空列表就跳过重打包、直接把原版落盘。于是并发调度、进度对话框、「装入游戏」按钮
     * 全部照用，不必另写一套。有备份的走本地拷贝（瞬间），没备份的才下载。
     *
     * 装入完成后清空装入记账 —— 卸载后 app 不该再声称任何 mod 生效中。
     */
    fun uninstallAll(context: Context) {
        val plan = _uninstallPlan.value ?: return
        _uninstallPlan.value = null
        if (plan.bundles.isEmpty()) return

        pendingRecordClear = true
        _moveState.value = MoveState.Idle
        batchStartTimeMs = System.currentTimeMillis()
        _installJobs.value = plan.bundles.map { InstallJob(RepackJob(it, emptyList())) }
        _finalInstallResult.value = null
        _showInstallDialog.value = true
        processInstallJobs(context)
    }

    /** 一键卸载走完并装入后要清空记账；由 [moveFilesToGame] 成功时触发。 */
    private var pendingRecordClear = false

    // --- Bundle Scan Dialog Actions ---

    /** 用户确认后执行启动期发现的 bundle 扫描（Phase 2），结束后收尾初始化。
     *
     * 跑在 [installScope] 而不是 viewModelScope：全量扫描 20–40 分钟，用户必然会
     * 切出去 —— viewModelScope 在界面销毁时取消协程，扫描断一半（与转换/装入
     * 同一条教训，见 [com.bd2toolsbox.service.InstallService] 的类注释）。期间
     * [ScanService] 钉住进程 + 通知栏进度；界面销毁后 in-app 进度条没了就没了，
     * 通知栏是主进度通道。
     */
    /** 扫描进度（由服务驱动的宿主推入；界面状态是它的映射）。 */
    private val scanProgress = MutableStateFlow(ScanRunProgress(0, 0, "", ""))

    fun confirmBundleScan() {
        val context = appContext ?: return
        val checkResult = pendingCheckResult ?: return
        // 立刻取走并进入扫描态：双击确认 / 界面重建后的重复确认都不该启动第二次
        // 扫描 —— python 侧 _scan_state 是全局单例，两个扫描会互相搅状态。
        pendingCheckResult = null
        if (ShizukuManager.scanRunning) return
        ShizukuManager.markScanRunning(true)
        _bundleScanState.value = BundleScanState.Scanning(
            currentIndex = 0,
            totalCount = checkResult.needsScanCount,
            currentBundle = "",
            progressMessage = ""
        )

        installScope.launch {
            // 用 appContext：这条流程刻意要活过界面销毁（同 processInstallJobs）
            val svcCtx = appContext ?: context
            val total = checkResult.needsScanCount
            if (!ScanService.start(svcCtx, total)) {
                // 系统拒绝前台服务（T15）：不开跑。没有前台服务的扫描一切后台就可能被杀，
                // 索引写到一半；留在确认态让用户回到前台后再点一次。
                pendingCheckResult = checkResult
                ShizukuManager.markScanRunning(false)
                _bundleScanState.value = BundleScanState.Confirmation(checkResult.needsScanCount)
                withContext(Dispatchers.Main) { toast(context, FGS_REFUSED_MESSAGE) }
                return@launch
            }
            // T19：扫描全程在 installScope——界面销毁也不能让连接断。
            // T23：**扫描循环搬进 ScanService**。以前这里直接调 executor，协程挂在
            // installScope 上；现在服务持有自己的 scope 与前台通知，界面死活无关。
            // ViewModel 只提交冻结输入、观察进度与结果、做 UI 收尾。
            try {
                // Shizuku 服务跑在 shell UID，写不了 app 的内部 cacheDir（/data/data/…），
                // 扫描临时文件得放 externalCacheDir
                val shizukuCacheDir = (context.externalCacheDir ?: context.cacheDir).absolutePath
                val request = ScanRunRequest(
                    outputDir = context.filesDir.absolutePath,
                    cacheDir = shizukuCacheDir,
                    checkResult = checkResult
                )
                // 进度：服务通过通知栏自己推，这里只把同一份进度映射到界面状态。
                // VM 已销毁时 launch 静默不生效，可接受（通知栏才是主通道）。
                scanProgress.value = ScanRunProgress(0, checkResult.needsScanCount, "", "")
                val progressJob = viewModelScope.launch(Dispatchers.Main) {
                    scanProgress.collect { p ->
                        _bundleScanState.value = BundleScanState.Scanning(
                            currentIndex = p.currentIndex,
                            totalCount = if (p.total > 0) p.total else checkResult.needsScanCount,
                            currentBundle = p.bundleName,
                            progressMessage = p.message
                        )
                    }
                }
                val outcome = try {
                    withContext(Dispatchers.IO) { scanRunHost.run(request) { scanProgress.value = it } }
                } finally {
                    progressJob.cancel()
                }

                when (outcome) {
                    null -> {
                        // 宿主没能开始跑（前台服务被拒）：回到确认态，让用户回前台再点
                        pendingCheckResult = checkResult
                        _bundleScanState.value = BundleScanState.Confirmation(checkResult.needsScanCount)
                        withContext(Dispatchers.Main) { toast(context, FGS_REFUSED_MESSAGE) }
                    }
                    is ScanRun.Finished -> {
                        _bundleScanState.value = BundleScanState.Finished(
                            scannedCount = outcome.scanned,
                            failedCount = outcome.failed,
                            message = "扫描完成：成功 ${outcome.scanned} 个，失败 ${outcome.failed} 个。"
                        )
                    }
                    is ScanRun.Failed -> {
                        _bundleScanState.value = BundleScanState.Failed(outcome.message)
                    }
                }
            } catch (e: Exception) {
                _bundleScanState.value = BundleScanState.Failed(e.message ?: "Unknown error")
                ScanService.stop(svcCtx)
            } finally {
                ShizukuManager.markScanRunning(false)
                // 扫描产出新索引，收尾初始化并触发一轮 mod 重扫。回 viewModelScope：
                // 里面的刷新本来就挂在界面上，界面没了就不必跑（下次冷启动会重来）
                viewModelScope.launch { finishInitialization() }
            }
        }
    }

    /** 用户跳过/关闭扫描：清掉待扫结果。跳过确认框时初始化收尾要在这里补上。 */
    fun dismissBundleScan() {
        val wasPending = pendingCheckResult != null
        pendingCheckResult = null
        _bundleScanState.value = BundleScanState.Idle
        if (wasPending) finishInitialization()
    }

    private fun finishInitialization() {
        _isUpdatingCharacters.value = false
        if (modSourceDirs.value.isNotEmpty()) rescanAllModSources()
        // 头像预取排在扫描后面触发，但不等它 —— 扫描（Python + 文件遍历）与下几十张
        // 小图谁也不该等谁。此时角色表已经读完，URL 拼得出来。
        prefetchAvatars()
        // 启动自动检查更新：静默、24h 一次、有新版弹框
        _initializationCompleted.value = true
        // 清上次更新残留的安装包（装完即弃）
        cleanupUpdateApk()
    }

    fun dismissVersionMismatchWarning() {
        _showVersionMismatchWarning.value = false
    }

    /**
     * 手动触发建立文件索引（左上角目录菜单 → 建立文件索引）。
     *
     * 自动检查只在冷启动时跑一次，且要求 Shizuku 当时已就绪 —— 用户先开 app 后
     * 启动 Shizuku、或 Shizuku 中途挂掉，就永远等不到扫描提示，表现为「装了
     * 新版 mod 还是大量未识别」。这里走与启动检查完全相同的链路（check →
     * 确认框 → confirmBundleScan），入口常驻。
     */
    /**
     * 导出诊断日志（设置 → 诊断 → 导出日志）。
     *
     * 真机排查的主通道：收集本进程 logcat（Kotlin 的 Log + Python 的
     * stdout/stderr 都在里面）存到 Download 并拉分享面板。比如「预解包
     * 哪些目标没缓存」——PrepackService 每个目标一行日志，导出即定位。
     */
    fun exportLogs() {
        val context = appContext ?: return
        viewModelScope.launch(Dispatchers.IO) {
            var temp: java.io.File? = null
            try {
                temp = com.bd2toolsbox.data.repository.LogExporter.collect(context)
                val uri = com.bd2toolsbox.data.repository.LogExporter
                    .exportToDownloads(context, temp)
                viewModelScope.launch(Dispatchers.Main) {
                    if (uri != null) {
                        toast(context, "日志已保存到 Download，可直接分享")
                        com.bd2toolsbox.data.repository.LogExporter.share(context, uri)
                    } else {
                        toast(context, "日志保存失败，请重试")
                    }
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "导出日志失败", e)
                viewModelScope.launch(Dispatchers.Main) {
                    toast(context, "导出日志失败：${e.message ?: "未知错误"}")
                }
            } finally {
                temp?.delete()
            }
        }
    }

    fun requestGameScan() {
        val context = appContext ?: return
        if (ShizukuManager.scanRunning) return   // 已在扫，进度框自会显示
        viewModelScope.launch {
            try {
                if (PrepackService.progress.value != null) {
                    // 预解包占着 Python（Chaquopy 单解释器，长解包期间其它 python
                    // 调用都在排队）—— 现在启动扫描只会两头互相拖，且 check 阶段
                    // 就可能超时误报「读取游戏目录失败」。等它跑完再点。
                    toast(context, "预解包尚未完成，请等待其结束后再建立文件索引")
                    return@launch
                }
                // T23：检查编排收进 ScanExecutor；这里只把四种结果映射成用户可见反馈。
                // 失败原因不再笼统地归为「游戏没装」：未连接/读取失败/已最新在用户眼里
                // 完全不同，笼统提示会让人白跑一趟重装游戏（真机实测：预解包期间误报）。
                val check = withContext(Dispatchers.IO) {
                    scanExecutor.check(context.filesDir.absolutePath)
                }
                when (check) {
                    is ScanCheck.NotConnected ->
                        toast(context, "Shizuku 未运行，请先在 Shizuku 中启动服务")
                    is ScanCheck.Failed ->
                        toast(context, "读取游戏资源目录失败：请确认游戏已安装、Shizuku 正在运行且已授权")
                    is ScanCheck.UpToDate ->
                        toast(context, "游戏资源索引已是最新，无需重扫")
                    is ScanCheck.NeedsScan -> {
                        pendingCheckResult = check.checkResult
                        _bundleScanState.value =
                            BundleScanState.Confirmation(check.checkResult.needsScanCount)
                    }
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "手动扫描检查失败", e)
                toast(context, "扫描检查失败：${e.message ?: "未知错误"}")
            }
        }
    }

    fun setSearchActive(isActive: Boolean) {
        _isSearchActive.value = isActive
        if (!isActive) {
            _searchQuery.value = ""
            persistFilterState()
        }
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
        persistFilterState()
    }

    fun toggleModSelection(modUri: Uri) {
        _selectedMods.value = if (modUri in _selectedMods.value) _selectedMods.value - modUri else _selectedMods.value + modUri
    }

    /** 列表内（筛选后）可装条目的全选/取消全选。以「筛选结果是否已全选」为切换依据，
     *  不看整体选中数 —— 否则筛出 2 项时永远判不全选，点一下变成「再加一遍」。 */
    fun toggleSelectAll() {
        toggleSelection(filteredModsList.value
            .filter {
                it.resolutionState == ResolutionState.KNOWN && it.defect == null &&
                        // 未识别产物（查不到角色名但 bundle 有效）也是 KNOWN，但 UI 里
                        // 渲染在只读区选不到，不排除会让全选数目和可见的勾选框对不上
                        !isUnknownCharacter(it.character) &&
                        // 「待更新」条目同理：它们渲染在只读的待更新区（更新即治愈），
                        // 不参与勾选，排除掉全选态才准
                        it.outdatedCurrentHash == null
            }
            .map { it.uri }
            .toSet())
    }

    /**
     * 对一组指定的条目做全选/取消全选。
     *
     * 比按 targetHash 全选更准：同一个 bundle 可能被多个角色的 mod 竞争（实测心契之约的
     * 立绘全都打包进同一个 bundle，19 个不同角色共用一个 hash），列表按角色拆开显示后，
     * 若仍按 hash 全选，用户在某个角色下看到 1 项、一点却选中了 19 项。
     */
    fun toggleSelectAllForUris(uris: Set<Uri>) {
        if (uris.isEmpty()) return
        val current = _selectedMods.value
        val inGroup = current.intersect(uris)
        _selectedMods.value = if (inGroup.size == uris.size) current - uris else current + uris
    }

    /** 同一 bundle 分组的全选/取消全选（只认该组内可装条目）。 */
    fun toggleSelectAllForGroup(groupHash: String) {
        toggleSelection(filteredModsList.value
            .filter {
                it.targetHash == groupHash && it.resolutionState == ResolutionState.KNOWN &&
                        it.defect == null && !isUnknownCharacter(it.character) &&
                        it.outdatedCurrentHash == null
            }
            .map { it.uri }
            .toSet())
    }

    /** 全选切换的公共实现：集合已全选则移除，否则并入。空集合不动。 */
    private fun toggleSelection(uris: Set<Uri>) {
        if (uris.isEmpty()) return
        val current = _selectedMods.value
        _selectedMods.value =
            if (current.intersect(uris).size == uris.size) current - uris
            else current + uris
    }

    /**
     * 把一批要装的 mod 变成装入 job：按目标 bundle 分组，并**并入该包上已装的 mod**。
     *
     * 「同 bundle 的多个 MOD 要并成一个 job，并把已装入的一起带上」这条判定已经迁到
     * [RepackPlanBuilder]（纯函数，可 JVM 直测）。这里只负责喂输入、记日志，并用
     * [toRepackJob] 做一次显式 adapter —— 计划模型刻意不带 `android.net.Uri`。
     */
    private fun buildRepackJobs(selected: List<ModInfo>): List<RepackJob> {
        if (selected.isEmpty()) return emptyList()

        val byUri = _modsList.value.associateBy { it.uri.toString() }
        val installed = if (true) {
            // 按**本次涉及的** bundle 取记账：计划不会去动本次没装的包。
            selected.mapNotNull { it.targetHash }.distinct().flatMap { hash ->
                installedModRepository.listByTargetHash(hash)
                    .map { InstalledModRef(it.modUri, hash, it.modName) }
            }
        } else {
            emptyList()
        }

        val availableByUri = byUri.values
            .filter { !it.targetHash.isNullOrBlank() }
            .associate { it.uri.toString() to it.toPlannedMod() }

        val plan = RepackPlanBuilder.build(
            selected = selected.map { it.toPlannedMod() },
            installed = installed,
            availableByUri = availableByUri
        )

        for (dropped in plan.droppedInstalled) {
            // 被丢弃有两种原因，**必须分开说**：`availableByUri` 的口径是
            // 「源文件仍在且解析得出 targetHash」，所以查不到不等于源文件没了。
            // 旧写法一律归因「源文件不在」，重扫后 targetHash 变空的条目会被冤枉 ——
            // 用户照着这条日志去外面找一个好端端躺着的文件。
            val reason = if (byUri.containsKey(dropped.modUri)) {
                "当前解析不出目标 hash（重扫后未解析或有缺陷）"
            } else {
                "源文件不在"
            }
            Log.w(
                "MainViewModel",
                "包 ${dropped.targetHash} 上已装的 ${dropped.modUri} $reason，本次重打包将不含它"
            )
        }

        val pickedPerHash = selected.groupingBy { it.targetHash }.eachCount()
        for (job in plan.jobs) {
            val picked = pickedPerHash[job.targetHash] ?: 0
            if (job.mods.size > picked) {
                Log.d(
                    "MainViewModel",
                    "包 ${job.targetHash} 并入已装 ${job.mods.size - picked} 个，本次选中 $picked 个"
                )
            }
        }

        // 计划只带领域标识：按 uri 反查回 ModInfo（计划模型不带 android.net.Uri）。
        return plan.jobs.map { it.toRepackJob(byUri) }
    }

    /** `ModInfo` → 计划里的领域引用。计划模型刻意不依赖 `android.net.Uri`。 */
    private fun ModInfo.toPlannedMod(): PlannedMod = PlannedMod(
        modUri = uri.toString(),
        name = name,
        targetHash = targetHash.orEmpty(),
        familyKey = resolvedFamilyKey
    )

    fun initiateBatchRepack(context: Context) {
        val allMods = _modsList.value
        startRepackFor(
            context,
            _selectedMods.value.mapNotNull { uri -> allMods.find { it.uri == uri } }
        )
    }

    /**
     * 按给定的一批 mod 启动「转换 + 装入」。
     *
     * 两个入口汇到这里：列表页用它自己的选中集，角色卡片用卡片内的多选。
     * 分组与合并（同一 bundle 的多个 mod 要并成一个 job，并把已装入的一起带上）
     * 只写一遍 —— 这段逻辑错一次就会出「装第二个把第一个顶掉」那类问题。
     */
    fun startRepackFor(context: Context, mods: List<ModInfo>) {
        val jobs = buildRepackJobs(
            mods.filter {
                !it.targetHash.isNullOrBlank() &&
                    it.resolutionState == ResolutionState.KNOWN &&
                    // 异常条目（下架/损坏/画质错配等）拦在装入入口：界面上本就选不到，
                    // 这里再兜一层，防选中集里混入校验前的旧 uri
                    it.defect == null &&
                    // 「待更新」同样拦：hash 目录名落后于当前 catalog，重打包出来
                    // 路径对不上，等于白跑一趟。它得先在待更新区改名治愈。
                    it.outdatedCurrentHash == null
            }
        )

        if (jobs.isNotEmpty()) {
            _moveState.value = MoveState.Idle
            batchStartTimeMs = System.currentTimeMillis()  // 記錄開始時間
            _installJobs.value = jobs.map { InstallJob(it) }
            _finalInstallResult.value = null
            _showInstallDialog.value = true
            processInstallJobs(context)
        }
    }

    private fun processInstallJobs(context: Context) {
        // 新批次开始：清掉上一批的「未完成备份」记录（T07 整改裁定 3 附加）。
        // 放在这个漏斗里而不是每个调用点：五个入口共用这一条，新增入口不会漏。
        resetUnfinishedBackups()
        installScope.launch {
            val total = _installJobs.value.size
            // 用 appContext 而不是传进来的 Activity context：这条流程刻意要活过界面
            // 销毁，而用已销毁的 Activity context 发 Intent 会失败 —— 通知就更新不了、
            // 也停不掉，恰好毁在这个功能最该起作用的场景上。
            val svcCtx = appContext ?: context
            if (!InstallService.start(svcCtx, total)) {
                // 系统拒绝前台服务（T15）：一个 job 都不开跑，全部如实记失败。
                // 没动游戏文件、没写账本；结果框照常由 summarizeResults 弹出。
                failAllJobsForRefusedForegroundService()
                return@launch
            }
            // T19：转换/装入全程可能需要 ShizukuManager（本地基底复用/装入游戏目录），
            // 自己持有一份占用，不依赖 MainActivity 的 token 是否还在。
            val installConnectionToken = ShizukuManager.acquireConnection()
            try {
                withContext(Dispatchers.IO) {
                    PythonRuntime.ensureStarted(context)
                }

                // 每次转换前重新做一遍干净检测。
                //
                // 这一步是「本地资源复用」的安全阀：上一批装入游戏后，那些 bundle 已经不再干净，
                // 若沿用旧的检测结果就会把已改过的文件当原版基底，导致贴图叠加。检测本身只是
                // 一次目录遍历 + 读一份 150KB 缓存，代价极小。
                withContext(Dispatchers.IO) {
                    refreshBundleCleanStates()
                }

                val batchCacheKey = System.currentTimeMillis().toString()
                val semaphore = Semaphore(5)

                // coroutineScope 是必须的：外层 launch 的 block 一返回就会走 finally，
                // 那时子协程还在跑，通知会在装到一半时被撤掉。包一层等它们全部结束。
                coroutineScope {
                    _installJobs.value.forEach { installJob ->
                        launch(Dispatchers.IO) {
                            semaphore.acquire()
                            try {
                                processSingleJob(context, installJob, batchCacheKey)
                            } finally {
                                semaphore.release()
                            }
                        }
                    }
                }
                // updateJobStatus 通过 installScope.launch(Dispatchers.Main) 异步排队；
                // 先在 Main 上排一帧确保最后一个 Finished 已送达 installJobs.collect 并完成
                // summarizeResults -> moveFilesToGame 的连接交接，再进 finally 释放本阶段占用。
                withContext(Dispatchers.Main) {}
            } finally {
                InstallService.stop(svcCtx)
                ShizukuManager.releaseConnection(installConnectionToken)
            }
        }
    }

    /**
     * 一个转换任务的完整流水：取基底（备份/游戏目录/CDN）→ 解包 mod 文件 →
     * 重打包（或纯还原）→ 落到 Download/Shared。任何一步失败即整任务失败，
     * 临时文件在 finally 里全部清掉。
     *
     * 空 modsToInstall = 纯还原任务（一键卸载走这条），不替换任何资源。
     */
    private suspend fun processSingleJob(context: Context, installJob: InstallJob, cacheKey: String) {
        val hashedName = installJob.job.hashedName
        var originalDataCache: File? = null
        var repackedDataCache: File? = null
        val modAssetsDir = File(context.cacheDir, "temp_mod_assets_$hashedName")

        try {
            // ---- 取原版基底 ----
            // 基底优先级：本地备份 > 游戏目录的干净原版 > CDN 下载。前两种是
            // UnityCache 格式（游戏直接能读），CDN 那份是压缩包（同一 bundle 实测
            // 6.2 MB vs 26 MB）—— 重打包两种都吃（UnityPy 都读得懂，输出统一 lz4），
            // 但纯还原必须区分：前者直接拷回，后者要转格式。
            // 只有干净检测判 PRISTINE（目录名 = catalog 内容哈希且大小 = 官方字节数）
            // 才敢用游戏目录那份 —— 本地装着 mod 时拿它当基底会把贴图叠上去。
            var baseIsGameFormat = false
            val backupBase = tryUseBackup(context, hashedName)
            if (backupBase != null) baseIsGameFormat = true
            val localBase = backupBase ?: tryUseLocalBundle(context, hashedName)?.also {
                baseIsGameFormat = true
            }
            val baseMessage: String = if (localBase != null) {
                updateJobStatus(hashedName, JobStatus.Downloading("已复用本地原版资源，跳过下载"))
                localBase
            } else {
                updateJobStatus(hashedName, JobStatus.Downloading("开始下载..."))
                val (ok, result) = modEngine.downloadBundle(
                    hashedName, selectedQuality.value, context.cacheDir.absolutePath, cacheKey
                ) { progress -> updateJobStatus(hashedName, JobStatus.Downloading(progress)) }
                if (!ok) throw Exception("Download failed: $result")
                result
            }

            originalDataCache = File(baseMessage)
            val relativePath = originalDataCache.relativeTo(context.cacheDir)

            // 顺手把这份原版存成备份 —— 卸载时就能本地拷回，不用再下一次。
            // 只备份「游戏目录格式」那份：能直接盖回游戏，还原最简单。CDN 那份是
            // 压缩包，存下来还得转一次格式，留给 restore_bundle 现场处理。
            // 「装哪个备份哪个」，已备份的不重复占空间。
            if (backupOriginals.value && baseIsGameFormat && backupBase == null) {
                hashDirOf(relativePath)?.let { hashDir ->
                    // T09：来源元数据。
                    //
                    // 这份基底的来源有且只有两种，由 `baseIsGameFormat` 那条链唯一决定：
                    // - `localBase` 走的是 tryUseLocalBundle → 游戏目录里那份，
                    //   它在采集时被干净检测判过 PRISTINE（= catalog 锚点一致）。
                    // - `localBase` 为 null 时走 CDN 下载 → 官方字节。
                    // 判据用「是在哪条分支拿到的」，不是事后靠尺寸猜。
                    val source = if (localBase != null) {
                        BaselineSource.GAME_DIR
                    } else {
                        BaselineSource.CATALOG_DOWNLOAD
                    }
                    val provenance = provenanceFor(source, hashedName)
                    val saved = withContext(Dispatchers.IO) {
                        backupRepository.saveBackup(
                            hashedName, hashDir, originalDataCache!!, provenance
                        )
                    }
                    // T07：这条**不在**提交路径上（产物照样落到 Download/Shared），
                    // 所以失败不中止转换，但必须让用户看见。判定走纯函数
                    // BackupGate.decideBackupGate，三个调用点同一套规则。
                    val gate = decideBackupGate(
                        backupEnabled = true,
                        backupSucceeded = saved,
                        onCommitPath = false
                    )
                    if (gate.mustReport) {
                        Log.w("MainViewModel", "备份原版失败，本次仍继续转换：$hashedName($hashDir)")
                        reportBackupFailure(hashedName, "原版备份失败：转换产物不受影响，但卸载后无法本地还原。")
                    }
                }
            }

            // ---- 解包 mod 文件到临时目录 ----
            updateJobStatus(hashedName, JobStatus.Installing("Extracting mod files..."))
            if (modAssetsDir.exists()) modAssetsDir.deleteRecursively()
            modAssetsDir.mkdirs()
            for (mod in installJob.job.modsToInstall) {
                extractModInto(mod, context, modAssetsDir)
            }

            // ---- 重打包（或纯还原）----
            updateJobStatus(hashedName, JobStatus.Installing("Repacking bundle..."))
            repackedDataCache = File(context.cacheDir, "repacked/${relativePath.path}")
            repackedDataCache.parentFile?.mkdirs()

            if (installJob.job.modsToInstall.isEmpty()) {
                restoreBaseBundle(hashedName, originalDataCache, repackedDataCache, baseIsGameFormat, relativePath)
            } else {
                val (ok, message) = modEngine.repackBundle(
                    originalDataCache.absolutePath, modAssetsDir.absolutePath,
                    repackedDataCache.absolutePath, useAstc.value
                ) { progress -> updateJobStatus(hashedName, JobStatus.Installing(progress)) }
                if (!ok) throw Exception("Repack failed: $message")
            }

            // ---- 落到公共目录 ----
            val publicUri = saveFileToDownloads(context, repackedDataCache, relativePath.path, "Shared")
            if (publicUri != null) {
                updateJobStatus(hashedName, JobStatus.Finished(relativePath.path))
            } else {
                throw Exception("Failed to save file to Downloads folder.")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            val fullError = e.message ?: "An unknown error occurred."
            val displayError = if (fullError.startsWith("Repack failed:")) {
                "Repack failed: Repack process failed without an exception."
            } else {
                fullError
            }
            updateJobStatus(hashedName, JobStatus.Failed(displayMessage = displayError, detailedLog = fullError))
        } finally {
            // 三处临时产物逐一清理，单项失败不掩盖其余清理
            originalDataCache?.takeIf { it.exists() }?.let { runCatching { it.delete() } }
            repackedDataCache?.takeIf { it.exists() }?.let { runCatching { it.delete() } }
            runCatching { if (modAssetsDir.exists()) modAssetsDir.deleteRecursively() }
        }
    }

    /** 缓存相对路径里的 hash 目录名（`<bundle>/<hash>/__data` 的中间段）。 */
    private fun hashDirOf(relativePath: File): String? =
        relativePath.path.replace('\\', '/').split('/').let {
            if (it.size >= 2) it[it.size - 2] else null
        }

    // ---------------------------------------------------------------- T09：备份来源元数据

    /**
     * 采集备份时用的 catalog 登记尺寸（`m_BundleSize`），拿不到返回 null。
     *
     * 它是 sidecar 里 `catalogVerified` 的锚点之一：**采集那一刻**这个值与
     * 待存字节一致，才说明这份备份曾被外部锚点钉住。拿不到表（离线且无缓存）
     * 就返回 null → `catalogVerified` 推导为 false → 该备份**不会**被当成可信基准。
     * 这是刻意的：宁可少一份可信备份，不可凭空断言。
     */
    private fun catalogSizeFor(bundleName: String): Long? =
        catalogBundleMeta?.get(bundleName)?.first

    /**
     * 当前档位的 catalog 版本号，仅用于 sidecar 的 `catalogVersion` 追溯字段。
     *
     * **不参与任何判定**，所以拿不到就返回 null。取法是从磁盘缓存文件名反推
     * （`catalog_{画质}_{版本}.json`，见 `catalog_indexer.cache_file_name`）——
     * 这样不必给 `modEngine.getBundleMeta` 加返回值（那会把 T09 的范围
     * 扩到 `service/` 去，超出计划列的文件清单）。
     */
    private fun currentCatalogVersion(quality: String): String? {
        val dir = appContext?.filesDir ?: return null
        val prefix = "catalog_${if (quality == "FHD") "HD" else quality}_"
        return dir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.name.startsWith(prefix) && it.name.endsWith(".json") }
            // 同一档只会留一份（download_catalog 落盘后清旧的），取最新以防万一
            ?.maxByOrNull { it.lastModified() }
            ?.name
            ?.removePrefix(prefix)
            ?.removeSuffix(".json")
    }

    /** 采集一份备份的来源声明。三个调用点共用，保证 sidecar 字段形状一致。 */
    private fun provenanceFor(
        source: BaselineSource,
        bundleName: String,
        anchoredInputSize: Long? = null,
    ): BackupProvenance = BackupProvenance(
        source = source,
        catalogSize = catalogSizeFor(bundleName),
        anchoredInputSize = anchoredInputSize,
        catalogVersion = currentCatalogVersion(selectedQuality.value),
        quality = selectedQuality.value,
    )

    /** 一个 mod 的源文件解到收集目录：目录 mod 整树拷入，zip mod 逐条解压。 */
    private fun extractModInto(mod: ModInfo, context: Context, dest: File) {
        if (mod.isDirectory) {
            DocumentFile.fromTreeUri(context, mod.uri)?.let {
                copyDirectoryToCache(context, it, dest)
            }
            return
        }
        context.contentResolver.openInputStream(mod.uri)?.use { fis ->
            ZipInputStream(fis).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && !shouldIgnoreModEntry(entry.name)) {
                        // T06：条目名来自归档、是外部输入。旧写法 File(dest, entry.name)
                        // 实测能被 `../outside.txt` / `..\outside.txt` 写出 dest 之外。
                        // 不安全的条目在这里抛 IllegalArgumentException，由
                        // processSingleJob 的 catch 转成 JobStatus.Failed，finally 清理临时目录。
                        val outFile = SafeArchivePaths.resolveInside(dest, entry.name)
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { fos -> zis.copyTo(fos) }
                    }
                    entry = zis.nextEntry
                }
            }
        }
    }

    /**
     * 纯还原任务（空 mod 列表）的产出：官方原版转成游戏缓存格式。
     * CDN 下载的基底是压缩包，与 UnityCache 的 __data 编码不同，不能直接拷；
     * 备份/游戏目录来源的已经是游戏格式，拷贝即完成。
     */
    private suspend fun restoreBaseBundle(
        hashedName: String,
        base: File,
        output: File,
        baseIsGameFormat: Boolean,
        relativePath: File
    ) {
        if (baseIsGameFormat) {
            updateJobStatus(hashedName, JobStatus.Installing("正在从本地备份还原..."))
            base.copyTo(output, overwrite = true)
            return
        }
        val (ok, msg) = modEngine.restoreBundle(
            base.absolutePath, output.absolutePath
        ) { progress -> updateJobStatus(hashedName, JobStatus.Installing(progress)) }
        if (!ok) throw Exception("Restore failed: $msg")

        // 还原产物就是原版的游戏格式，顺手存成备份：下次卸载零下载。
        //
        // T09：**旧注释的后半句已经删掉**。它说「restore_bundle 用 lz4 重打包，
        // 字节数与官方 catalog 有千分之几差异（实测 6,243,701 vs 6,238,441），
        // 干净检测把备份大小也当原版基准，卸载后状态才不会一直停在「被修改」」——
        // 那就是被 T09 消灭的那条循环自证判据。替代路径见下面传给
        // backupRestoredBundle 的 anchoredInputSize 与 PristineVerifier 的注释。
        hashDirOf(relativePath)?.let { hashDir ->
            // 走到这里说明 baseIsGameFormat == false，也就是基底**来自 CDN 下载**
            // （另两种来源都是游戏格式、在上面就 return 了）。所以：
            // - 输入是被 TLS 通道 + catalog 大小钉住的官方字节；
            // - 转换是确定性的（T09 实测：同一输入两次运行字节完全相同）；
            // - 于是产物可信，且这份输入的大小就是锚点。
            // 记 anchoredInputSize 而不是给输入再算一次摘要，是为了避免为了审计
            // 把几百 MB 的输入全量读一遍 —— 输入大小已经足够复现这次锚定。
            val provenance = provenanceFor(
                source = BaselineSource.RESTORE_PRODUCT,
                bundleName = hashedName,
                anchoredInputSize = base.length(),
            )
            withContext(Dispatchers.IO) {
                backupRestoredBundle(hashedName, hashDir, output, provenance)
            }
        }
    }

    /**
     * 还原产物顺手备份：**开关关着就什么都不做**（T07 三轮整改，先看开关）。
     *
     * ## 为什么这一处以前是错的
     *
     * 二轮把这条链的失败收进了「未完成的备份」汇总，但这里**不检查
     * [backupOriginals] 开关**就调 `saveBackup` —— 于是用户**明确关掉**自动备份时，
     * 一次**没被要求过**的备份失败也会被收进汇总，结果对话框弹出一条
     * 「未完成的备份：……卸载时需重新下载原版」。用户没要求备份，却被告知备份没做成。
     *
     * 现在与另外两处（`processSingleJob` 的 `:1505`、提交路径的 `:2008`）形状一致：
     * **外层先判开关**，关着就既不建备份、也不产生任何条目。这同时守住
     * 「用户明确关掉的设置不被静默改回开启」这条既有约束。
     *
     * ## 为什么它不是 `suspend`
     *
     * `saveBackup` 是阻塞文件拷贝，切线程这件事留在调用点（`withContext(Dispatchers.IO)`），
     * 生产行为与改动前**逐字一致**。拆成普通私有函数是为了让它能在设备用例里被反射
     * 驱动 —— 私有 `suspend` 方法要 `kotlin-reflect` 才调得动，而 androidTest 没有它。
     * 见 `BackupFailureSummaryTest.restoreBackupHonoursTheAutoBackupSwitch`。
     *
     * ## T09：多一个 [provenance] 参数
     *
     * 因为它现在必须能区分「这份还原产物是从什么锚定的输入派生出来的」——
     * 没有它，`saveBackup` 只能写出一份 `catalogVerified=false` 的备份
     * （于是判定落 UNKNOWN），卸载后状态又会停在「被修改」。
     * 见 `restoreBaseBundle` 的调用点注释。
     */
    private fun backupRestoredBundle(
        hashedName: String,
        hashDir: String,
        output: File,
        provenance: BackupProvenance,
    ) {
        if (!backupOriginals.value) {
            Log.d("MainViewModel", "自动备份已关闭，跳过还原产物的备份：$hashedName")
            return
        }
        val saved = backupRepository.saveBackup(hashedName, hashDir, output, provenance)
        // T07：与 processSingleJob 里那处同一条规则 —— 不在提交路径上，
        // 失败不中止还原（产物照样落到 Download/Shared），但要让用户看见。
        val gate = decideBackupGate(
            backupEnabled = true,
            backupSucceeded = saved,
            onCommitPath = false
        )
        if (gate.mustReport) {
            Log.w("MainViewModel", "备份还原产物失败，本次仍继续：$hashedName($hashDir)")
            reportBackupFailure(hashedName, "还原产物的备份失败：还原本身不受影响，但卸载后无法本地秒还原。")
        }
    }

    /**
     * 尝试用游戏本地的原版 bundle 当重打包基底，成功则返回缓存文件路径，否则返回 null。
     *
     * 安全前提：只有干净检测判定为 [BundleCleanState.PRISTINE] 才复用。这个判定来自
     * catalog 的官方原版字节数与内容哈希，与 app 自己的记账无关，所以连旧版工具或手动
     * 装入造成的改动也能挡住 —— 那些情况会落到 MODIFIED，从而走 CDN 取权威原版。
     *
     * 落盘路径刻意与 downloadBundle 保持一致（`<cacheDir>/<bundleName>/<hash>/__data`），
     * 因为调用方随后用 relativeTo(cacheDir) 推导产物路径，结构必须相同。
     */
    /**
     * 优先拿本地备份当原版基底。
     *
     * 比 [tryUseLocalBundle] 更靠前：备份是装入前存下的权威原版，而游戏目录里那份一旦装过
     * mod 就不再干净、只能弃用。所以对"已经装过 mod、现在要卸载或换一批"的 bundle，
     * 备份是唯一能免下载的来源。
     *
     * 注意不检查 [backupOriginals] 开关 —— 开关只管"要不要新建备份"，已经存下来的备份
     * 无论开关状态都该拿来用。
     */
    private fun tryUseBackup(context: Context, bundleName: String): String? {
        val hashDir = gameBundleHashes[bundleName] ?: return null
        if (!backupRepository.hasBackup(bundleName, hashDir)) return null
        val dest = File(context.cacheDir, "$bundleName/$hashDir/__data")
        dest.parentFile?.mkdirs()
        return try {
            backupRepository.backupFile(bundleName, hashDir).copyTo(dest, overwrite = true)

            // T09：**用之前先验**。旧写法只判 `hasBackup`，任何存在的备份都被当成权威
            // 原版拿去重打包 —— 一份来源不明、甚至其实是被改过的备份会把旧 mod 内容
            // 原样带进新产物（计划验收：「被修改备份不会作为干净基底再次复用」）。
            //
            // ## 为什么是 byteAnchoredBaseline 而不是 trustedBaseline（二轮整改）
            //
            // 首版这里用的是 `trustedBaseline` —— 而它**不筛来源强度**，`GAME_DIR` 照样
            // 放行。复核者的攻击当场证伪（runId 20260918-031658，`expected:<UNKNOWN>
            // but was:<PRISTINE>`）：一个**同尺寸的 mod** 躺在游戏目录里，扫描层因为看
            // 不到字节、只能按 `size == catalog.size` 判它 PRISTINE，于是
            // `backupPristineOriginal` 以 `GAME_DIR` 来源把它存成「可信」备份 ——
            // 之后 `tryUseBackup` 拿它当干净基底复用。**那正是 T09 要消灭的形态**
            // （「被修改备份不会作为干净基底再次复用」），在这条路径上没被消灭，
            // 只是把判据从「无 sidecar 的任意备份」收窄到「GAME_DIR + 尺寸对得上」。
            //
            // `GAME_DIR` 的 `catalogVerified` 规则本身在扫描层判 PRISTINE 之后就是**循环**的：
            // 判 PRISTINE 用的就是 size == catalog，存备份时又拿 size == catalog 当
            // 「被锚定」的证据。所以这里必须只认 [BaselineSource.BYTE_ANCHORED]
            // （CDN 下载 / 由它确定性派生）—— 与扫描层 `byteAnchoredBaseline` 同一口径。
            //
            // 现在备份要能当基底，必须带 sidecar 且其来源属于 BYTE_ANCHORED。
            // **旧备份没有 sidecar → 返回 null → 落 UNKNOWN → 这里拒绝**，
            // 改走游戏目录或 CDN 下载那条路。
            //
            // 这是**有意的行为变化**：旧备份从此不再被信任。代价是那些 bundle 卸载
            // 时要重新下载一次；换来的是「来源不明的字节不会被当原版用」。
            // 取舍写进 T09.md §「删掉大小判据」。
            val baseline = backupRepository.byteAnchoredBaseline(bundleName, hashDir)
            val verdict = verifier.classifyBackup(dest, baseline)
            if (verdict != Verdict.PRISTINE) {
                Log.w(
                    "MainViewModel",
                    "备份未通过可信校验，弃用并改走其他基底：$bundleName($hashDir) " +
                            "verdict=$verdict baseline=${baseline?.source ?: "none"}"
                )
                runCatching { dest.delete() }
                return null
            }
            Log.d("MainViewModel", "使用本地备份原版: $bundleName (${dest.length()} 字节)")
            dest.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private suspend fun tryUseLocalBundle(context: Context, bundleName: String): String? {
        if (bundleCleanStates[bundleName] != BundleCleanState.PRISTINE) return null

        val hashDir = gameBundleHashes[bundleName] ?: return null
        val dest = File(context.cacheDir, "$bundleName/$hashDir/__data")
        dest.parentFile?.mkdirs()

        val ok = ShizukuManager.copyLocalBundleToCache(bundleName, hashDir, dest.absolutePath)
        if (!ok || !dest.isFile || dest.length() <= 0L) {
            try {
                if (dest.exists()) dest.delete()
            } catch (_: Exception) {}
            return null
        }

        // T09：拿到字节之后再做一次**字节级**复验。
        //
        // 这一步只在**有摘要基准**时才有约束力 —— 而游戏目录的原版恰恰通常没有：
        // 实测证明 catalog 的 `m_Hash` 不是 `__data` 字节的内容摘要（T09.md
        // §「m_Hash 语义」），所以「官方原版长什么样」这件事，官方清单本身答不了。
        // 唯一能给出摘要的是带 sidecar 的可信备份（CDN 下载 / 由它派生）。
        //
        // 因此这里的行为是：
        // - 有可信备份基准（同名同 hashDir）→ **要求逐字节一致**，不一致就弃用；
        // - 没有摘要基准（纯 catalog 锚点）→ 保持原行为（name+size 一致即接受）。
        //   这不是退让，是当前能拿到的最强证据；扫描层那条注释解释了为什么
        //   `m_Hash` 只能撑到「锚点一致」级。
        val baseline = if (true) {
            backupRepository.byteAnchoredBaseline(bundleName, hashDir)
        } else {
            null
        }
        val verdict = verifier.classify(dest, baseline?.copy(expectedHashDir = hashDir))
        if (verdict != Verdict.PRISTINE) {
            Log.w("MainViewModel", "游戏基底缺少可信内容证据或摘要不符，改用可信来源：$bundleName($hashDir) verdict=$verdict")
            runCatching { dest.delete() }
            return null
        }
        Log.d("MainViewModel", "复用本地原版 bundle: $bundleName (${dest.length()} 字节)")
        return dest.absolutePath
    }

    // ------------------------------------------------ 已转换产物：直接装入（不跑转换）

    /**
     * 把 SAF 的 documentId 还原成 Shizuku（shell UID）能读的真实路径。
     *
     * SAF 权限是系统授予 app 的，没法传递给独立进程的 shell 服务，所以 Shizuku 只认真实路径。
     * 好在用户的产物本来就躺在 /sdcard 下，shell 直接可读 —— 推导成功就能少拷一整趟。
     *
     * 只对 ExternalStorageProvider 的 `primary:` 前缀有把握；SD 卡、Downloads provider 或
     * 第三方文件管理器的 provider 一律返回 null，交给中转路径兜底。
     */
    private fun resolveRealPath(uri: Uri): String? {
        if (uri.authority != "com.android.externalstorage.documents") return null
        val docId = try {
            DocumentsContract.getDocumentId(uri)
        } catch (e: Exception) {
            return null
        }
        val parts = docId.split(':', limit = 2)
        if (parts.size != 2 || parts[0] != "primary") return null
        val rel = parts[1].trim('/')
        return if (rel.isEmpty()) "/storage/emulated/0" else "/storage/emulated/0/$rel"
    }

    /**
     * 装入一批已转换好的产物。
     *
     * 这类条目本身就是打包完成的 bundle，只需放到游戏 Shared/ 下的正确位置，完全不经过
     * 下载与重打包 —— 所以比 PC mod 快一个数量级。
     */
    /**
     * 把游戏目录里那份原版 bundle 备份下来，供日后卸载时本地还原。
     *
     * 只在干净检测判定 PRISTINE 时才做 —— 已经装着 mod 的那份不是原版，存下来会让
     * 「还原」把 mod 又盖回去。已有备份则跳过，不重复占空间。
     *
     * 用于「直接装入产物」这条路径：它覆盖式写游戏目录，写完原版就找不回来了，
     * 而它既不下载也不重打包，没有别的地方能顺手拿到原版。
     *
     * **T07 的改动：返回值从 `Unit` 变成 [BackupAttempt]。** 旧实现在备份失败时
     * 照样打印「已备份原版」（那是一条虚假记录），异常还被 `printStackTrace()` 吞掉。
     * 现在把结果带回调用方，由 `decideBackupGate` 判定 —— 这条**在提交路径上**，
     * 备份失败即中止本目标的写入（写完原版就没了，没有第二次机会）。
     *
     * 「不需要备份」与「备份成功」都返回 [BackupAttempt.NotNeeded]：
     * 两者对调用方的含义相同（可以继续写入），区分它们只会制造一个没人用的分支。
     */
    private suspend fun backupPristineOriginal(bundleName: String): BackupAttempt {
        if (!true) return BackupAttempt.NotNeeded
        if (bundleCleanStates[bundleName] != BundleCleanState.PRISTINE) return BackupAttempt.NotNeeded
        val hashDir = gameBundleHashes[bundleName] ?: return BackupAttempt.NotNeeded
        if (backupRepository.hasBackup(bundleName, hashDir)) return BackupAttempt.NotNeeded

        val cacheDir = appContext?.cacheDir ?: return BackupAttempt.NotNeeded
        val tmp = File(cacheDir, "backup_stage/$bundleName/$hashDir/__data")
        tmp.parentFile?.mkdirs()
        return try {
            val ok = ShizukuManager.copyLocalBundleToCache(bundleName, hashDir, tmp.absolutePath)
            if (!ok || !tmp.isFile || tmp.length() <= 0L) {
                Log.w("MainViewModel", "取原版失败，未备份：$bundleName($hashDir) copied=$ok")
                BackupAttempt.Failed("读取游戏目录里的原版失败")
            } else if (backupRepository.saveBackup(
                    bundleName, hashDir, tmp,
                    // T09：这份字节直接来自游戏目录，且上面刚判过 bundleCleanStates
                    // 是 PRISTINE（catalog 锚点一致）。来源照实记 GAME_DIR ——
                    // 它的强度上限就是「采集当时 name+size 对得上」，sidecar 里
                    // 也如实体现（deriveCatalogVerified 要求尺寸等于 catalog 登记值）。
                    provenanceFor(BaselineSource.GAME_DIR, bundleName),
                )
            ) {
                // 这条日志只许在备份**真的**成功时打印（旧实现在失败时也打）
                Log.d("MainViewModel", "已备份原版 $bundleName (${tmp.length()} 字节)")
                BackupAttempt.Saved
            } else {
                Log.w("MainViewModel", "写备份失败：$bundleName($hashDir)")
                BackupAttempt.Failed("写入备份文件失败")
            }
        } catch (e: Exception) {
            // 旧写法在这里 printStackTrace() 吞掉 —— 失败必须传播，不许吞
            Log.e("MainViewModel", "备份原版出错：$bundleName($hashDir)", e)
            BackupAttempt.Failed(e.message ?: "备份原版出错")
        } finally {
            try { tmp.delete() } catch (_: Exception) {}
        }
    }

    /**
     * 原版备份的尝试结果。
     *
     * 三态而不是布尔：[NotNeeded]（备份关着 / 不是原版 / 已经备份过）与 [Saved]
     * 对调用方是同一个含义，但与 [Failed] **不同** —— 用布尔会把「跳过」和
     * 「做了且成功」压成同一个 `true`，那样 `decideBackupGate` 的输入就失真了。
     */
    private sealed class BackupAttempt {
        /** 这次不需要备份（或已经备份过）。 */
        object NotNeeded : BackupAttempt()

        /** 备份已落盘。 */
        object Saved : BackupAttempt()

        /** 备份失败，[reason] 是给用户看的原因。 */
        data class Failed(val reason: String) : BackupAttempt()
    }

    /**
     * 把一次备份失败挂到该任务的状态上，让用户**看得见**（T07）。
     *
     * 用 `JobStatus.Installing` 而不是 `Failed`：失败的是「顺手备份」这一步，
     * 不是这次转换/还原本身 —— 用 `Failed` 会把一个仍在正常进行的任务显示成失败，
     * 而那正是这一整项要消灭的「报虚假状态」的另一面。
     * [AtomicFileWriter] 那一侧同理：宁可多一条可见的警告，也不要一条不可见的丢失。
     *
     * **同时收进批次汇总**（T07 二轮整改，裁定 3 附加）：toast 一闪而过，任务随后被置成
     * [JobStatus.Finished]，用户在结果对话框里看不到这件事 —— 而「卸载时能不能本地还原」
     * 恰恰是他事后才会发现的事。所以除了即时提示，还要在收尾时单列一节。
     *
     * ## toast 必须自己切回主线程（二轮整改时由设备用例抓出来的真缺陷）
     *
     * 这个方法被 `processSingleJob`（跑在 `Dispatchers.IO`）与 `restoreBaseBundle` 调用，
     * 而 `Toast.makeText(...).show()` 在**没调过 `Looper.prepare()` 的线程**上直接抛
     * `NullPointerException: Can't toast on a thread that has not called Looper.prepare()`。
     * 那个异常会被 `processSingleJob` 的 `catch (e: Exception)` 接住 → 该 job 被标成
     * [JobStatus.Failed]，**整个转换连同已下载的基底一起中止**，用户看到的还是一条
     * 与真实原因无关的报错。
     *
     * 这与裁定 1（「非提交路径备份失败只警告、不中止」）**方向相反** —— 也就是说
     * 不修这一处，那条裁定在真机上根本不成立。所以这里显式切主线程：
     * `toast(...)` 自己只换 application context、不切线程（见它的 KDoc）。
     */
    private fun reportBackupFailure(hashedName: String, message: String) {
        updateJobStatus(hashedName, JobStatus.Installing(message))
        // 累加是纯内存操作，留在当前线程 —— 汇总不能因为切线程而被漏掉。
        recordUnfinishedBackup(hashedName, message)
        val ctx = appContext ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            toast(ctx, message)
        } else {
            Handler(Looper.getMainLooper()).post { toast(ctx, message) }
        }
    }

    /**
     * 本次任务里没做成的备份（T07 二轮整改，裁定 3 附加）。
     *
     * 三处备份失败都记到这里：转换流程顺手备份（`processSingleJob`）、还原产物顺手备份
     * （`restoreBaseBundle`）、以及提交路径上因备份失败被跳过的目标。前两处不中止任务，
     * 只有一闪而过的 toast；都收进来之后 [summarizeResults] 才能在结果对话框里
     * 如实回报「哪些备份没做成」—— 那正是用户原话要的「回报是否全部完成，
     * 如果没有则显示哪些没有」。
     *
     * 用同步列表：`processSingleJob` 的 job 是并发跑的（`Semaphore(5)`），
     * 累加必须线程安全。按 [FailedJobInfo.hashedName] 去重，同一个 bundle 只留一条。
     *
     * **不改变** [FinalInstallResult.successfulJobs] / [FinalInstallResult.failedJobs]：
     * 备份失败不是装入失败（CDN 能重下），计数只反映装载结果。
     */
    private val unfinishedBackups: MutableList<FailedJobInfo> =
        Collections.synchronizedList(mutableListOf())

    /** 记下一条「没做成的备份」。同一个 [hashedName] 只留第一条（原因以首次为准）。 */
    private fun recordUnfinishedBackup(hashedName: String, message: String) {
        synchronized(unfinishedBackups) {
            if (unfinishedBackups.none { it.hashedName == hashedName }) {
                unfinishedBackups.add(FailedJobInfo(hashedName, message))
            }
        }
    }

    /** 开新批次前清空上一批的备份失败记录。 */
    private fun resetUnfinishedBackups() {
        synchronized(unfinishedBackups) { unfinishedBackups.clear() }
    }

    /** 收尾时取一份快照，交给 [FinalInstallResult]。 */
    private fun unfinishedBackupsSnapshot(): List<FailedJobInfo> =
        synchronized(unfinishedBackups) { unfinishedBackups.toList() }

    /**
     * 「装产物会覆盖同包已装 mod」的预警。
     *
     * 类型本身在 `core.model`（[ConvertedOverwritePlan]）：它只描述业务参数，
     * 存的是产物 uri 字符串而不是 `ModInfo`，所以不依赖 Android 对象。
     */
    private val _convertedOverwritePlan = MutableStateFlow<ConvertedOverwritePlan?>(null)
    val convertedOverwritePlan: StateFlow<ConvertedOverwritePlan?> = _convertedOverwritePlan.asStateFlow()

    fun dismissConvertedOverwritePlan() {
        _convertedOverwritePlan.value = null
    }

    /**
     * 计划里的领域标识（uri 字符串）→ 当前列表里的 [ModInfo]。
     *
     * 这是「迁移完成前仅允许的显式 adapter」：计划模型不带 Android 对象，装入流程要
     * `ModInfo`，就在这一步按 uri 反查。查不到（用户在这期间重扫过、条目已不在）直接丢弃 ——
     * 它本来也装不了了。
     *
     * **这与迁出前不是同一件事**：旧实现把 `List<ModInfo>` 实例捕获在计划里，
     * 确认时直接搬运，**不可能丢**；改成按 uri 反查之后才出现这条丢弃路径。
     * 因此丢了多少要**说出来** —— 本函数只负责反查，报告由
     * [confirmConvertedOverwrite] 统一做（日志 + 可见提示），避免两处各说一套。
     */
    private fun resolveModInfos(modUris: List<String>): List<ModInfo> {
        val byUri = _modsList.value.associateBy { it.uri.toString() }
        return modUris.mapNotNull { byUri[it] }
    }

    /** 用户在预警框里点了「仍要装入」。 */
    fun confirmConvertedOverwrite(context: Context) {
        val plan = _convertedOverwritePlan.value ?: return
        _convertedOverwritePlan.value = null
        val targets = resolveModInfos(plan.modUris)
        val lost = plan.modUris.size - targets.size
        if (targets.isEmpty()) {
            // 旧实现不可能走到这里（计划里存的是捕获好的 ModInfo 实例）。现在
            // 「弹窗到确认之间全部条目消失」会让这里空手而归 —— 不点出来就是
            // 「用户点了『仍要装入』，什么都没发生，也没有任何提示」。
            Log.w(
                "MainViewModel",
                "转换预警确认：计划里的 ${plan.modUris.size} 个条目都已不在当前列表，本次装入被跳过"
            )
            toast(context, "这些 mod 已不在当前列表（可能刚重扫过），本次装入已取消")
            return
        }
        if (lost > 0) {
            // 部分丢：还是装，但用户确认的是 N 个、实际装进去的是 N-lost 个，
            // 差额必须当面说，否则他会以为全装上了。
            Log.w(
                "MainViewModel",
                "转换预警确认：${plan.modUris.size} 个条目里有 $lost 个已不在当前列表，本次装入不含它们"
            )
            toast(context, "有 $lost 个 mod 已不在当前列表，本次装入不含它们")
        }
        doInstallConvertedBundles(context, targets)
    }

    fun installConvertedBundles(context: Context, mods: List<ModInfo>) {
        // defect 拦截与 startRepackFor 同款语义：UI 分区后本就选不到异常条目，
        // 这里兜一层，防选中集里混入校验前的旧 uri 直拷进游戏
        // 「待更新」同样拦：它的 hash 目录名落后于当前 catalog，直拷进游戏也是白拷，
        // 先更新（改名）再装。UI 也选不到它们（只读区）。
        val targets = mods.filter {
            it.kind == ModKind.CONVERTED_BUNDLE && it.targetHash != null && it.defect == null &&
                    it.outdatedCurrentHash == null
        }
        if (targets.isEmpty()) return

        // 先看会不会覆盖掉同包里已装的别的 mod
        if (true) {
            val selectedUris = targets.map { it.uri.toString() }.toSet()
            val byUri = _modsList.value.associateBy { it.uri.toString() }
            val casualties = LinkedHashMap<String, List<String>>()
            val recoverable = HashSet<String>()
            for (hash in targets.mapNotNull { it.targetHash }.distinct()) {
                val victims = installedModRepository.listByTargetHash(hash)
                    .filterNot { it.modUri in selectedUris }
                if (victims.isEmpty()) continue
                casualties[hash] = victims.map { it.modName }
                // 源文件都还在的话，改走「转换所选」就能靠重打包保住它们
                if (victims.all { byUri.containsKey(it.modUri) }) recoverable.add(hash)
            }
            if (casualties.isNotEmpty()) {
                _convertedOverwritePlan.value =
                    ConvertedOverwritePlan(targets.map { it.uri.toString() }, casualties, recoverable)
                return
            }
        }
        doInstallConvertedBundles(context, targets)
    }

    private fun doInstallConvertedBundles(context: Context, targets: List<ModInfo>) {
        if (targets.isEmpty()) return
        // 这条路不经 processInstallJobs（它自己就是漏斗），备份失败记录要在这里清。
        resetUnfinishedBackups()
        _moveState.value = MoveState.Idle
        batchStartTimeMs = System.currentTimeMillis()
        _installJobs.value = targets.map {
            InstallJob(RepackJob(it.targetHash!!, listOf(it)), producesDownload = false)
        }
        _finalInstallResult.value = null
        _showInstallDialog.value = true

        installScope.launch {
            // 同上：服务的生命周期挂在进程上，不能用 Activity context 去驱动
            val svcCtx = appContext ?: context
            if (!InstallService.start(svcCtx, targets.size)) {
                failAllJobsForRefusedForegroundService()
                return@launch
            }
            // T19：直接装入全程需要 ShizukuManager.installConvertedBundle（真正的 Binder 调用），
            // 自己持有一份占用，不依赖 MainActivity 的 token 是否还在。
            val directInstallConnectionToken = ShizukuManager.acquireConnection()
            try {
            var ok = 0
            var reconcile = 0
            val failed = mutableListOf<FailedJobInfo>()

            // 先刷一次干净检测：备份原版的前提是「游戏目录里那份现在还是原版」，
            // 拿启动时的旧结果判断会误备份已经装过 mod 的文件。一次目录遍历，代价很小。
            withContext(Dispatchers.IO) { refreshBundleCleanStates() }

            for (mod in targets) {
                val bundleName = mod.targetHash!!
                updateJobStatus(bundleName, JobStatus.Installing("准备装入 ${mod.name}"))

                // 完整校验改到独占暂存 __data 上（与提交同一份字节），见 installOneConverted。

                // 直接装入是覆盖式写游戏目录，写完原版就没了。所以先把原版留一份 ——
                // 这条路径不下载、不重打包，备份只能在这里做（转换流程那边是在拿到
                // 下载/复用的基底时顺手存的，两条路互不经过）。
                //
                // T07：这是**提交路径**——写完这个目标，游戏目录里那份原版就永远
                // 找不回来了。所以备份失败就跳过这个目标（不是只打日志继续往下写）。
                // 旧实现在这里无条件继续，备份失败时照样覆盖原版、日志里还写着「已备份」。
                if (backupOriginals.value) {
                    val attempt = withContext(Dispatchers.IO) { backupPristineOriginal(bundleName) }
                    val gate = decideBackupGate(
                        backupEnabled = true,
                        backupSucceeded = attempt is BackupAttempt.Saved || attempt is BackupAttempt.NotNeeded,
                        onCommitPath = true
                    )
                    if (!gate.allowsWrite) {
                        val reason = (attempt as? BackupAttempt.Failed)?.reason ?: "备份原版失败"
                        val msg = "已跳过装入（未能备份原版，覆盖后将无法还原）：$reason"
                        Log.e("MainViewModel", "$msg [$bundleName]")
                        failed.add(FailedJobInfo(bundleName, msg))
                        // 这一处与另外两处不同：备份失败**确实**让这次装入没做成，
                        // 所以它同时出现在「失败的分组」与「未完成的备份」两节里 ——
                        // 前者说「这次没生效」，后者说「卸载时没法本地还原」。两句话都对。
                        recordUnfinishedBackup(bundleName, msg)
                        updateJobStatus(bundleName, JobStatus.Failed(msg, "$reason；目标未改动"))
                        continue
                    }
                } else {
                    // 开关关着：不建备份、照常写入。这条分支显式写出来（而不是靠
                    // decideBackupGate 内部处理）是为了让「用户的明确选择」在调用点
                    // 也看得见 —— 见 BackupGate.decideBackupGate 的文档注释。
                    Log.d("MainViewModel", "自动备份已关闭，跳过原版备份：$bundleName")
                }

                val result = withContext(Dispatchers.IO) {
                    installOneConverted(context, mod, bundleName)
                }
                if (result.requiresReconciliation) notifyLedgerNotice(result.message)
                if (result.completedSuccessfully) {
                    ok++
                    updateJobStatus(bundleName, JobStatus.Finished(bundleName))
                } else {
                    if (result.requiresReconciliation) reconcile++
                    failed.add(FailedJobInfo(bundleName, result.message))
                    updateJobStatus(bundleName, JobStatus.Failed(result.message, result.message))
                }
            }

            if (ok > 0) {
                // 记账已在 installOneConverted 的 ConvertedInstallSubmitter.commitLedger 完成。
                withContext(Dispatchers.IO) { refreshBundleCleanStates() }
                refreshInstallStates()
            }

            // 必须先设 moveState、后设 finalInstallResult —— 这两个是各自独立的
            // StateFlow，中间会有一次重组。反过来的话那一帧是「finalResult 已非 null
            // 而 moveState 还是 Idle」，对话框据此走进 Idle 分支、弹出「一键装入游戏」；
            // 而这条路是直拷进游戏目录的，Download/Shared 里根本没东西，点了就报
            // 「未找到 Download/Shared 目录」，紧接着 Success 到达又跳成「已装入」。
            // 实测就是用户看到的那个现象。
            _moveState.value = directInstallOutcome(ok, failed.size, reconcile)

            _finalInstallResult.value = FinalInstallResult(
                successfulJobs = ok,
                failedJobs = failed.size,
                // 已经直接进游戏目录了，没有留在 Download/ 的东西需要再装一次
                command = null,
                elapsedTimeMs = System.currentTimeMillis() - batchStartTimeMs,
                failedJobDetails = failed,
                // 备份失败单列一节（T07 整改裁定 3 附加）：转换/还原流程里那两处顺手备份
                // 失败只闪一条 toast，不汇总的话用户在结果里看不到。不计入 failedJobs。
                unfinishedBackups = unfinishedBackupsSnapshot(),
                shizukuAvailable = ShizukuManager.isRunning(),
                alreadyInGame = true
            )
            } finally {
                InstallService.stop(svcCtx)
                ShizukuManager.releaseConnection(directInstallConnectionToken)
            }
        }
    }

    /** 单个产物：独占暂存绑定 SHA/账本事实，再经 ConvertedInstallSubmitter 写前意图后覆盖。 */
    private suspend fun installOneConverted(
        context: Context,
        mod: ModInfo,
        bundleName: String
    ): ConvertedSubmitResult {
        fun rejected(message: String) = ConvertedSubmitResult(
            false, OperationError(OperationErrorKind.INVALID_INPUT, message), message, false
        )
        if (!true) {
            return rejected("装入记录仓储未初始化，拒绝覆盖游戏文件")
        }
        val hashDir = mod.convertedHashDir
        if (hashDir.isNullOrBlank()) {
            return rejected("缺少 convertedHashDir，拒绝覆盖：$bundleName")
        }
        val cache = context.externalCacheDir
            ?: return rejected("没有 externalCacheDir，Shizuku 读不到暂存")
        val stagingRoot = File(cache, "converted_staging_owned")
        val now = System.currentTimeMillis()
        val frozen = listOf(
            LedgerRecord(
                modUri = mod.uri.toString(),
                modName = mod.name,
                familyKey = mod.resolvedFamilyKey ?: bundleName,
                snapshotTargetHash = bundleName,
                quality = _selectedQuality.value,
                usedAstc = _useAstc.value,
                installedAt = now
            )
        )
        val preparation = ConvertedInstallStaging(stagingRoot).prepare(bundleName, hashDir, frozen) { dest ->
            copyConvertedSourceInto(context, mod, dest)
        }
        val prepared = when (preparation) {
            is ConvertedInstallPreparation.Failed -> return rejected(preparation.error.message)
            is ConvertedInstallPreparation.Ready -> preparation.prepared
        }
        return try {
            val verdict = try {
                if (!Python.isStarted()) null
                else modEngine.validateBundle(prepared.dataFile.absolutePath)
            } catch (e: Exception) {
                Log.w("MainViewModel", "暂存校验异常，不据此拦下装入：${mod.name}", e)
                null
            }
            if (verdict != null && !verdict.first) {
                return rejected("校验未通过，已跳过：${verdict.second}")
            }
            val result = ConvertedInstallSubmitter(installedModRepository).submit(
                operationId = "converted-$now-$bundleName",
                target = prepared.target,
                replaceTarget = {
                    val drift = prepared.verifyUnchanged()
                    if (drift != null) false to drift.message
                    else ShizukuManager.installConvertedBundle(prepared.sourceDir.absolutePath, bundleName)
                },
                commitLedger = {
                    when (val write = installedModRepository.putAll(
                        frozen.map {
                            InstalledModRecord(
                                it.modUri, it.modName, it.familyKey, it.snapshotTargetHash,
                                it.quality, it.usedAstc, it.installedAt
                            )
                        }
                    )) {
                        is LedgerWrite.Failed -> write.error
                        else -> null
                    }
                }
            )
            result
        } finally {
            try {
                prepared.close()
            } catch (e: Exception) {
                Log.w("MainViewModel", "关闭 converted 暂存失败：$bundleName", e)
            }
        }
    }

    private fun copyConvertedSourceInto(context: Context, mod: ModInfo, dest: File): Boolean {
        return try {
            val real = resolveRealPath(mod.uri)
            if (real != null) {
                val src = File(real)
                if (!src.isDirectory) return false
                val source = FileDirectoryCopySource(src)
                StrictDirectoryCopy.copy(source, source.rootId, dest)
                return true
            }
            val doc = DocumentFile.fromTreeUri(context, mod.uri) ?: return false
            if (!doc.isDirectory) return false
            val source = DocumentFileCopySource(context, doc)
            StrictDirectoryCopy.copy(source, source.rootId, dest)
            true
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("MainViewModel", "converted 源复制失败：${mod.name}", e)
            false
        }
    }

    /**
     * SAF 树适配 [DirectoryCopySource]。
     * 子目录必须沿用 [DocumentFile.listFiles] 拿到的对象：`fromSingleUri` 对 tree 子文档
     * 常常列不出孩子，嵌套 `hash/__data` 会整树失败。无名条目仍让整层失败。
     */
    private class DocumentFileCopySource(
        private val context: Context,
        private val root: DocumentFile
    ) : DirectoryCopySource {
        val rootId: String get() = root.uri.toString()
        private val nodes = hashMapOf(rootId to root.uri)

        override fun listChildren(directoryId: String): List<DirectoryCopyEntry> {
            val directory = nodes[directoryId] ?: throw java.io.IOException("未知源目录")
            val docId = DocumentsContract.getDocumentId(directory)
            val queryUri = DocumentsContract.buildChildDocumentsUriUsingTree(root.uri, docId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )
            try {
                val cursor = context.contentResolver.query(queryUri, projection, null, null, null)
                    ?: throw java.io.IOException("源目录查询失败")
                return cursor.use {
                    val extras = it.extras
                    if (extras.getBoolean(DocumentsContract.EXTRA_LOADING, false) ||
                        !extras.getString(DocumentsContract.EXTRA_ERROR).isNullOrBlank()) {
                        throw java.io.IOException("源目录尚未完整读取")
                    }
                    val idColumn = it.getColumnIndexOrThrow(projection[0])
                    val nameColumn = it.getColumnIndexOrThrow(projection[1])
                    val mimeColumn = it.getColumnIndexOrThrow(projection[2])
                    val out = ArrayList<DirectoryCopyEntry>()
                    while (it.moveToNext()) {
                        val childId = it.getString(idColumn) ?: throw java.io.IOException("源条目缺少标识")
                        val name = it.getString(nameColumn) ?: throw java.io.IOException("源条目缺少名称")
                        val mime = it.getString(mimeColumn) ?: throw java.io.IOException("源条目缺少类型")
                        val uri = DocumentsContract.buildDocumentUriUsingTree(root.uri, childId)
                        val id = uri.toString()
                        nodes[id] = uri
                        out.add(DirectoryCopyEntry(id, name, mime == DocumentsContract.Document.MIME_TYPE_DIR))
                    }
                    out
                }
            } catch (cancelled: java.util.concurrent.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                throw java.io.IOException("源目录读取未完成", failure)
            }
        }

        override fun openFile(fileId: String): java.io.InputStream? {
            val uri = nodes[fileId] ?: throw java.io.IOException("未知源文件")
            return context.contentResolver.openInputStream(uri)
        }
    }

    // ------------------------------------------------ mod 目录管理 / 隐藏 / 删除

    /**
     * 换一个 mod 文件夹。
     *
     * 之前只有欢迎页有选目录的入口，而目录一旦持久化就再也回不到欢迎页 —— 等于没法换目录，
     * 也没法从选错的目录里退出来。这里把入口独立出来，随时可用。
     */
    fun clearModSourceDirectory() {
        val context = appContext
        // 逐个放掉持久权限，别把配额一直占着（系统给每个 app 的额度有限）
        _modSourceDirs.value.forEach { uri ->
            try {
                context?.contentResolver?.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                Log.w("MainViewModel", "释放目录权限失败: $uri", e)
            }
        }
        _modSourceDirs.value = emptyList()
        context?.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            ?.edit()?.remove("mod_source_dirs")?.remove("mod_source_dir_uri")?.apply()
        _modsList.value = emptyList()
        _selectedMods.value = emptySet()
        _stateFilter.value = null
        // 源目录没了，快照也该没了（否则下次冷启动会显出一批再也扫不到的条目）
        clearModListSnapshot()
    }

    private val _hiddenMods = MutableStateFlow<Set<String>>(emptySet())

    /** 已隐藏的条目数，供工作台显示「恢复已隐藏的项」是否有内容可恢复。 */
    val hiddenCount: StateFlow<Int> = _hiddenMods
        .map { it.size }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), 0)

    private fun hiddenFile(): File? =
        appContext?.let { File(it.filesDir, "hidden_mods.json") }

    private fun loadHidden(): Set<String> {
        val f = hiddenFile() ?: return emptySet()
        if (!f.exists()) return emptySet()
        return try {
            val arr = org.json.JSONArray(f.readText())
            buildSet { for (i in 0 until arr.length()) add(arr.optString(i)) }
        } catch (e: Exception) {
            emptySet()
        }
    }

    private fun saveHidden(set: Set<String>) {
        val f = hiddenFile() ?: return
        try {
            f.writeText(org.json.JSONArray(set.toList()).toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** 把一个条目从列表里藏起来。只记一个 uri，不动手机上的文件。 */
    fun hideMod(mod: ModInfo) {
        val next = loadHidden() + mod.uri.toString()
        saveHidden(next)
        _hiddenMods.value = next
        _modsList.value = _modsList.value.filterNot { it.uri == mod.uri }
    }

    fun clearHidden() {
        saveHidden(emptySet())
        _hiddenMods.value = emptySet()
        if (modSourceDirs.value.isNotEmpty()) rescanAllModSources()
    }

    /**
     * 正在删除 / 刚删掉的条目 uri（**墓碑**）。
     *
     * 两个用途：
     *  1. 防重入：同一条正在删时，重复的删除请求直接挡掉；
     *  2. 防「复活」：乐观删除把条目摘掉的那一刻，文件其实还在磁盘上 —— 任何**已经开跑**
     *     的扫描（或 retryCatalogValidation 的整表回写）都还能把它扫回来，行就又跳出来了。
     *     删除成功的 uri **留在集合里**（墓碑），扫描收尾过滤完之后由 [rescanAllModSources]
     *     统一清空；删除失败/中断的则立刻摘掉，否则这个还在的 mod 会被永久挡在扫描结果外。
     *
     * 并发集合：批量删除的循环跑在 IO 线程上。
     */
    private val deletingModUris: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * 这个 uri 是不是某个 mod 源文件夹的**根本身**。
     *
     * 源文件夹根级直接摆着 skel/png 时，扫出来的条目就是树根（见 ModRepository.discoverMods），
     * 删它等于把整个源文件夹连同里面别的 mod 一起删掉，所以这种条目到哪都不给删。
     */
    fun isModSourceRoot(uri: Uri): Boolean = _modSourceDirs.value.any { tree ->
        try {
            DocumentsContract.buildDocumentUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree)
            ) == uri
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 从手机上真正删除这个 mod 的源文件。
     *
     * **乐观更新**：SAF 删一个目录要几秒（provider 在递归删子项），原先等删完才动列表，
     * 这几秒里界面毫无反应、看着像没点上。现在确认后立刻把条目摘掉、账本同步销账，
     * 真删在后台跑；失败再把条目放回原位并报错（[restoreDeletedMods]）。
     *
     * 删除目标：条目 uri 是 buildDocumentUriUsingTree 出来的 **document uri**，
     * [DocumentFile.fromSingleUri] 删的就是这一层。刻意不用 fromTreeUri —— 那条路要先按
     * `isDocumentUri(context, uri)` 反查 provider 才决定用 documentId 还是树根 id
     * （见 documentfile-1.0.1 字节码），查不出 provider 时会退回**树根**；fromSingleUri
     * 永远只认传进来的那个 document，不留这种隐患。
     *
     * 这是整个 app 里唯一会动用户文件的操作，不可恢复，调用方必须先做二次确认。
     * 源文件夹根本身（`_modSourceDirs` 里某个树的根 document）一律不删，见 [isModSourceRoot]。
     */
    fun deleteModFolder(context: Context, mod: ModInfo, onDone: (Boolean, String) -> Unit) {
        // 源文件夹根条目不能删：删它等于把整个源目录（可能还有别的 mod）一起删掉。
        // 界面上有一道同样的守卫（异常区的确认框），但卡片菜单那条入口没有 —— 收在这里，
        // 任何调用方都绕不过去。
        if (isModSourceRoot(mod.uri)) {
            onDone(false, "「${mod.name}」就是 mod 源文件夹本身，删不得；要处理请到文件管理器里操作")
            return
        }
        val key = mod.uri.toString()
        // 同一条正在删（删除期间重扫又把它扫了回来，用户再点一次）→ 不并发删第二遍
        if (!deletingModUris.add(key)) {
            onDone(false, "「${mod.name}」正在删除，请稍候")
            return
        }
        val index = _modsList.value.indexOfFirst { it.uri == mod.uri }
        // —— 乐观：条目立刻消失，不等 SAF ——
        _modsList.value = _modsList.value.filterNot { it.uri == mod.uri }
        _selectedMods.value = _selectedMods.value - mod.uri

        viewModelScope.launch {
            var ok = false
            var take: LedgerTake = LedgerTake.Absent
            try {
                // 账本先销账。源文件都没了，这条记录留着只会让状态推导继续把它算成「生效中」，
                // 而重打包时又找不到文件。注意这里只销账，不动游戏里的实际内容 ——
                // 那需要重打包，属于「移除 mod」的动作，不是「删源文件」该做的事。
                // 失败要把它放回去，所以让 removeAndReturn 把记录交回来。
                take = withContext(Dispatchers.IO) { takeLedgerRecord(key) }
                // 销账本身出的事（写失败 / 账本损坏被重建）必须让用户看见 ——
                // 这条路径跑在后台，不处置就等于静默（复核真缺陷 1）。
                withContext(Dispatchers.IO) { reportLedgerWrite(take.write, "删除时销账") }
                ok = withContext(Dispatchers.IO) { deleteModSourceFile(context, mod) }
            } catch (t: Throwable) {
                // 兜底（含协程被取消）：任何意外都要把列表、账本、防重入状态收拾干净，
                // 不能留下一个「条目不在了、文件还在、也没人管」的中间态
                Log.w("MainViewModel", "删除中断: $key", t)
            } finally {
                if (ok) {
                    // 删除期间若用户重扫过，条目会被扫回来 —— 结果落定时再摘一次。
                    // 墓碑留在 deletingModUris 里（见那里的说明），由下一次扫描收尾统一清。
                    _modsList.value = _modsList.value.filterNot { it.uri == mod.uri }
                    val snapshot = captureModListSnapshot(_modsList.value)
                    withContext(NonCancellable + Dispatchers.IO) { saveModListSnapshot(snapshot) }
                    onDone(true, "已从手机删除「${mod.name}」")
                } else {
                    // 失败/中断：墓碑必须摘掉，否则以后每次扫描都会把它挡在外面，
                    // 这个其实还在的 mod 就再也扫不回来了
                    deletingModUris.remove(key)
                    restoreDeletedMods(listOf(mod to index))
                    withContext(NonCancellable + Dispatchers.IO) { putLedgerRecord(take.record) }
                    onDone(false, "删除失败，可能没有该目录的写入权限")
                }
            }
        }
    }

    private val _isDeletingAbnormal = MutableStateFlow(false)

    /** 一键删除异常 mod 是否在进行中：区头按钮据此置灰防重入。 */
    val isDeletingAbnormal: StateFlow<Boolean> = _isDeletingAbnormal.asStateFlow()

    /** 与 [isDeletingAbnormal] 配套的布尔（与 outdatedUpdateBusy 同款）：按钮点击都在主线程上。 */
    private var abnormalDeleteBusy = false

    /**
     * 一键删除异常 mod 的源文件（「异常mod」区头的按钮）。
     *
     * 目标是**当前区里看得见的那几条**（[targets]）：搜索/状态筛选会把区头计数收窄，
     * 按「全部异常」删会和用户看到的数字对不上。
     *
     * 整批一次性从列表消失，而不是逐条消失：逐条的话剩下的会不断往上跳，用户还得盯着
     * 它一条条走完；整批清空后只有一句结果 toast。真删在后台**串行**跑（每个目录都要
     * 几秒，并发反而容易被 provider 限流），失败的条目在结束时放回列表。
     */
    fun deleteAbnormalMods(context: Context, targets: List<ModInfo>) {
        if (abnormalDeleteBusy) return          // 防重入
        abnormalDeleteBusy = true
        _isDeletingAbnormal.value = true

        // 源文件夹根条目不能删（删它等于删整个源目录），指给用户去文件管理器处理
        val (roots, deletable) = targets.partition { isModSourceRoot(it.uri) }
        val pending = deletable.filter { deletingModUris.add(it.uri.toString()) }
        if (pending.isEmpty()) {
            abnormalDeleteBusy = false
            _isDeletingAbnormal.value = false
            toast(
                context,
                if (roots.isNotEmpty()) "这些条目就是 mod 源文件夹本身，请到文件管理器里处理"
                else "当前没有可删除的条目"
            )
            return
        }

        val before = _modsList.value
        val positions = pending.associate { it.uri.toString() to before.indexOfFirst { m -> m.uri == it.uri } }
        val removedUris = pending.map { it.uri }.toSet()
        // —— 乐观：整批立刻消失 ——
        _modsList.value = before.filterNot { it.uri in removedUris }
        _selectedMods.value = _selectedMods.value - removedUris

        viewModelScope.launch(Dispatchers.IO) {
            var removed = 0
            val failed = ArrayList<ModInfo>()
            val deleted = ArrayList<String>()
            var i = 0
            try {
                while (i < pending.size) {
                    val mod = pending[i]
                    val key = mod.uri.toString()
                    val take = takeLedgerRecord(key)
                    // 销账出的事同样要上报（写失败 / 账本损坏被重建）——
                    // 批量删除跑在 IO 上，不处置就是静默（复核真缺陷 1）。
                    reportLedgerWrite(take.write, "批量删除销账")
                    val ok = deleteModSourceFile(context, mod)
                    if (ok) {
                        removed++
                        // 墓碑留着（见 deletingModUris 的说明），下一次扫描收尾统一清
                        deleted += key
                    } else {
                        failed += mod
                        deletingModUris.remove(key)     // 没删成 → 别再挡着以后的重扫
                        putLedgerRecord(take.record)    // 账本同理，原样放回
                    }
                    i++
                }
            } catch (t: Throwable) {
                // 兜底（含协程被取消）：一条出意外不该让整批无声中断、busy 卡死
                Log.w("MainViewModel", "批量删除中断", t)
            } finally {
                // 中断时还没轮到的条目：它们只是被乐观地摘掉了，文件还在 —— 一并算失败放回，
                // 不能让它们就这么从列表里消失
                while (i < pending.size) {
                    deletingModUris.remove(pending[i].uri.toString())
                    failed += pending[i]
                    i++
                }
                // 收尾一律跑在 NonCancellable 上：取消（清后台、VM 销毁）也不能把这些
                // 「防重入」状态留在原地，否则这个界面就再也删不动了
                val snapshot = withContext(NonCancellable + Dispatchers.Main) {
                    abnormalDeleteBusy = false
                    _isDeletingAbnormal.value = false
                    // 删成功的再摘一次：这期间若正好有一次扫描把条目扫了回来（墓碑被清），
                    // 结果落定时仍要保证它们不在列表里
                    if (deleted.isNotEmpty()) {
                        _modsList.value = _modsList.value.filterNot { it.uri.toString() in deleted }
                    }
                    if (failed.isNotEmpty()) {
                        restoreDeletedMods(failed.map { it to (positions[it.uri.toString()] ?: -1) })
                    }
                    val parts = mutableListOf("已删除 $removed 个")
                    if (failed.isNotEmpty()) parts += "失败 ${failed.size} 个"
                    if (roots.isNotEmpty()) parts += "${roots.size} 个是源文件夹，未删"
                    toast(context, parts.joinToString("，"))
                    captureModListSnapshot(_modsList.value)
                }
                // 列表变了，快照跟着刷新，免得下次冷启动又把这些条目显出来
                withContext(NonCancellable + Dispatchers.IO) { saveModListSnapshot(snapshot) }
            }
        }
    }

    /** 真删一个条目的源文件。跑在 IO 上；任何异常都算失败（delete 本身返回 false）。 */
    private fun deleteModSourceFile(context: Context, mod: ModInfo): Boolean = try {
        DocumentFile.fromSingleUri(context, mod.uri)?.delete() == true
    } catch (e: Exception) {
        Log.w("MainViewModel", "删除源文件失败: ${mod.uri}", e)
        false
    }

    /**
     * 从账本里销掉一条并把它交回来（删除失败要原样放回）。跑在 IO 上。
     * 读改写是仓库里一次上锁的 [InstalledModRepository.removeAndReturn] —— 分开调
     * load()+remove() 会让并发的另一次写入从中间插进来。
     *
     * **返回结局而不是裸记录**（T08 二轮整改，复核真缺陷 1）：裸 `null` 会把
     * 「本来就没这条」「账本写失败、记录还在」「账本损坏、原件被搬走」三件事混成一个值，
     * 后两种在这条路径上完全静默。现在把 [LedgerTake] 交回调用方，
     * 由它经 [reportLedgerWrite] 处置 —— **与其它写入口同一个处置点**。
     */
    private fun takeLedgerRecord(modUri: String): LedgerTake {
        if (!true) return LedgerTake.Absent
        return try {
            installedModRepository.removeAndReturn(modUri)
        } catch (e: Exception) {
            Log.w("MainViewModel", "销账失败: $modUri", e)
            LedgerTake.Blocked(
                OperationError(
                    kind = OperationErrorKind.UNKNOWN,
                    message = "装入记录销账异常：" + e.message.orEmpty(),
                    detail = e.toString(),
                    target = modUri
                )
            )
        }
    }

    /** 把销掉的账放回去（删除失败时）。跑在 IO 上。 */
    private fun putLedgerRecord(record: InstalledModRecord?) {
        if (record == null || !true) return
        try {
            reportLedgerWrite(installedModRepository.put(record), "账本复原")
        } catch (e: Exception) {
            Log.w("MainViewModel", "账本复原失败: ${record.modUri}", e)
        }
    }

    /**
     * 账本写入结果的**唯一**处置点（T08）。
     *
     * 改动前 `InstalledModRepository` 的写方法返回 `Unit`、失败只 `printStackTrace()` ——
     * 调用方无从知道写失败了，账本可以静默地与游戏目录脱节。现在写方法回传
     * `LedgerWrite`（Written / Repaired / Failed），本函数把它落成**用户能看见的东西**：
     *
     * | 结局 | 处置 |
     * |---|---|
     * | `Written` | 什么都不做（正常路径零噪音） |
     * | `Repaired` | 记日志 + 走 [notifyLedgerNotice] 提示（**不是错误**：账本已可用，只是重建过） |
     * | `Failed` | 记日志 + 提示（账本没写成功，旧账本仍在） |
     *
     * **切主线程**这件事由 [notifyLedgerNotice] 负责 —— 这些调用点大多跑在
     * `Dispatchers.IO` 上，直接 `Toast` 会抛「Can't toast on a thread that has not
     * called Looper.prepare()」，那正是 T07 真缺陷 #3 的形态（见 `T07.md` §8.11）。
     *
     * @param what 这次写的是什么（「装入记账」「账本复原」…），拼进提示文案里让用户知道
     *   是哪一步出的问题。
     */
    private fun reportLedgerWrite(result: LedgerWrite, what: String) {
        when (result) {
            is LedgerWrite.Written -> Unit
            is LedgerWrite.Repaired -> {
                Log.w("MainViewModel", "${what}：账本损坏已重建（原件保留为 ${result.preservedAs}）")
                notifyLedgerNotice("${what}时发现装入记录无法读取，已将原文件保留为 " +
                    "${result.preservedAs}，并重建了一份新记录。游戏里的文件没有动。")
            }
            is LedgerWrite.Failed -> {
                Log.w("MainViewModel", "${what}失败：${result.error.message}")
                notifyLedgerNotice("${what}失败：${result.error.message}")
            }
        }
    }

    /**
     * 把一条需要用户知道的账本事实送到 UI。
     *
     * 复用既有的 [ledgerResetNotice] 通道（`MainActivity` 已有 `LedgerResetDialog` 渲染它），
     * 不新开一条 StateFlow —— 两者都是「账本出了一件你必须知道的事」，
     * 分成两个通道只会让 UI 需要同时看两处。
     *
     * 后台线程调用是**现实情况**：本函数的调用点跑在 `Dispatchers.IO` 上，
     * 而 `MutableStateFlow` 本身线程安全、UI 侧 `collectAsState` 会切回主线程，
     * 所以这里不需要额外的调度 —— 真正需要切线程的是 `Toast`，
     * 而本函数不 `Toast`。
     */
    private fun notifyLedgerNotice(message: String) {
        // 旧的提示还没被用户消掉时不覆盖：先发生的那条更重要（它更接近根因）。
        if (_ledgerResetNotice.value == null) _ledgerResetNotice.value = message
    }

    /**
     * 把乐观删掉的条目放回列表原来的位置（[index] 为 -1 表示放在末尾）。
     *
     * 按 uri 去重：删除失败前若正好有一次扫描结束，那条会被扫回来，别再插一份。
     */
    private fun restoreDeletedMods(entries: List<Pair<ModInfo, Int>>) {
        if (entries.isEmpty()) return
        val current = _modsList.value.toMutableList()
        entries.sortedBy { it.second }.forEach { (mod, index) ->
            if (current.any { it.uri == mod.uri }) return@forEach
            val at = if (index < 0) current.size else index.coerceIn(0, current.size)
            current.add(at, mod)
        }
        _modsList.value = current
    }

    /** 读某个 bundle 当前的自定义名（没设过则为空串）。 */
    fun aliasOf(bundleName: String): String =
        appContext?.let { BundleNameResolver(it).aliasOf(bundleName) } ?: ""

    /** 设置/清除自定义名，并就地刷新列表里该条目的显示。 */
    fun setModAlias(mod: ModInfo, alias: String) {
        val context = appContext ?: return
        val bundleName = mod.targetHash ?: return
        // 全程走 IO：这里每次都是**新建** BundleNameResolver 实例，实例级的
        // 「拉过就不重试」保护跨实例无效 —— 别名没设成时 displayName 会落到
        // catalog 兜底命名路，冷缓存就是一次 60MB 下载 + 数秒解析，放主线程
        // 必定 ANR。
        viewModelScope.launch(Dispatchers.IO) {
            val resolver = BundleNameResolver(context)
            resolver.setAlias(bundleName, alias)
            val shown = resolver.displayName(bundleName)
            withContext(Dispatchers.Main) {
                _modsList.value = _modsList.value.map {
                    if (it.uri == mod.uri) it.copy(name = shown) else it
                }
            }
        }
    }

    /**
     * 全部 job 收尾后的汇总：成功/失败计数、失败明细、总耗时，组装成
     * [FinalInstallResult] 给结果对话框。
     *
     * 转换成功且 Shizuku 在线就直接移进游戏，不等用户点「一键装入游戏」——
     * 那一步没有任何决策，用户点它只是因为程序要求点；转换完走开的人
     * 回来常忘了还有一下要点，mod 明明转好了却没生效。Shizuku 不可用时
     * 保持原样：那种情况确实需要用户处理（启动 Shizuku 或 root 手动拷），
     * 对话框里的引导和命令照给。
     */
    private fun summarizeResults() {
        val jobs = _installJobs.value
        val successful = jobs.filter { it.status is JobStatus.Finished }
        val failed = jobs.filter { it.status is JobStatus.Failed }

        _finalInstallResult.value = FinalInstallResult(
            successfulJobs = successful.size,
            failedJobs = failed.size,
            command = if (successful.isNotEmpty()) MANUAL_INSTALL_COMMAND else null,
            elapsedTimeMs = System.currentTimeMillis() - batchStartTimeMs,
            failedJobDetails = failed.map {
                FailedJobInfo(
                    hashedName = it.job.hashedName,
                    error = (it.status as JobStatus.Failed).detailedLog
                )
            },
            // 备份失败的单独一节（T07 整改裁定 3 附加）：不改变 success/failed 计数，
            // 只告诉用户「这些 bundle 没做成备份，卸载时要重新下载」。
            unfinishedBackups = unfinishedBackupsSnapshot(),
            shizukuAvailable = ShizukuManager.isRunning()
        )

        if (successful.isNotEmpty() && ShizukuManager.isRunning()) {
            moveFilesToGame(silent = true)
        }
    }

    @Synchronized
    /** 前台服务被拒时把本批全部 job 标成失败（尚未开跑，目标未改动）。 */
    private fun failAllJobsForRefusedForegroundService() {
        _installJobs.value.forEach {
            updateJobStatus(it.job.hashedName, JobStatus.Failed(FGS_REFUSED_MESSAGE, "$FGS_REFUSED_MESSAGE；目标未改动"))
        }
    }

    private fun updateJobStatus(hashedName: String, newStatus: JobStatus) {
        installScope.launch(Dispatchers.Main) {
            val currentJobs = _installJobs.value.toMutableList()
            val jobIndex = currentJobs.indexOfFirst { it.job.hashedName == hashedName }
            if (jobIndex != -1) {
                currentJobs[jobIndex] = currentJobs[jobIndex].copy(status = newStatus)
                _installJobs.value = currentJobs
                pushInstallNotification(currentJobs, newStatus)
            }
        }
    }

    /** 通知节流用。下载进度回调一秒来几十次，每次都发 Intent 既没必要也会被系统丢弃。 */
    private var lastInstallNotifyMs = 0L

    /**
     * 把当前进度推给前台服务的通知栏。
     *
     * 这里是所有 job 状态的唯一汇聚点，所以通知也从这里更新 —— 不必在每个环节
     * （下载 / 重打包 / 装入）各插一次。
     */
    private fun pushInstallNotification(jobs: List<InstallJob>, latest: JobStatus) {
        val ctx = appContext ?: return
        val done = jobs.count { it.status is JobStatus.Finished || it.status is JobStatus.Failed }
        val terminal = latest is JobStatus.Finished || latest is JobStatus.Failed
        val now = System.currentTimeMillis()
        // 有 job 收尾时不节流：进度条往前跳一格是用户最想看到的那一帧
        if (!terminal && now - lastInstallNotifyMs < 700L) return
        lastInstallNotifyMs = now

        val text = when (latest) {
            is JobStatus.Downloading -> latest.progressMessage
            is JobStatus.Installing -> latest.progressMessage
            is JobStatus.Finished -> "已完成 $done/${jobs.size}"
            is JobStatus.Failed -> latest.displayMessage
            JobStatus.Pending -> "等待中…"
        }
        InstallService.update(ctx, done, jobs.size, text)
    }

    fun closeInstallDialog() {
        _showInstallDialog.value = false
        _installJobs.value = emptyList()
        _finalInstallResult.value = null
        _selectedMods.value = emptySet()
        _moveState.value = MoveState.Idle
    }

    // Main-thread ownership is independent of dialog visibility and MoveState resets.
    private var moveInProgress = false

    fun moveFilesToGame(silent: Boolean = false) {
        installScope.launch(Dispatchers.Main.immediate) {
            if (moveInProgress) return@launch
            if (!true || appContext == null) {
                _moveState.value = MoveState.Failed("装入记录仓储未初始化，无法安全提交")
                return@launch
            }
            // Capture on Main, before the first suspension. Closing the dialog or
            // changing settings must not change either the write set or its ledger.
            val displayedJobs = _installJobs.value
            if (displayedJobs.any { !it.producesDownload }) {
                _moveState.value = MoveState.Failed("这批产物已走直接装入流程，不能再次搬运；如有失败请先核对。")
                return@launch
            }
            val jobs = displayedJobs.filter { it.status is JobStatus.Finished }.map {
                it.copy(job = it.job.copy(modsToInstall = it.job.modsToInstall.toList()))
            }
            val quality = _selectedQuality.value
            val astc = _useAstc.value
            val installedAt = System.currentTimeMillis()
            val clearRestored = pendingRecordClear
            val removeTarget = pendingUninstallTargetHash
            val backupEnabled = _backupOriginals.value
            val selectedBundles = jobs.map { it.job.hashedName }.toSet()
            val failedBackups = unfinishedBackupsSnapshot().filter { it.hashedName in selectedBundles }
            val cache = appContext!!.externalCacheDir
            if (jobs.isEmpty() || cache == null) {
                _moveState.value = MoveState.Failed("没有可提交的转换产物或无法访问外部暂存目录")
                return@launch
            }
            if (!decideBackupGate(backupEnabled, failedBackups.isEmpty(), onCommitPath = true).allowsWrite) {
                _moveState.value = MoveState.Failed("原版备份未完成，未覆盖游戏文件：" +
                    failedBackups.joinToString("、") { it.hashedName })
                return@launch
            }

            moveInProgress = true
            if (!silent) _moveState.value = MoveState.Moving
            val moveConnectionToken = ShizukuManager.acquireConnection()
            try {
                // T22：**提交执行搬进 InstallService**。以前这里直接调 executor，
                // 协程挂在 viewModelScope 上 —— 用户退出界面时 onCleared 会取消它，
                // 在游戏目录留下半个 __data。现在服务持有自己的 scope，
                // 界面销毁不影响提交；ViewModel 只等结果、做 UI 收尾。
                val request = withContext(Dispatchers.IO) {
                    val shared = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Shared")
                    val targets = journalTargetsForJobs(shared, jobs, quality, astc, installedAt)
                    val removals = targets.filter {
                        it.modUris.isEmpty() && (clearRestored || it.bundleName == removeTarget)
                    }.map { it.bundleName }.toSet()
                    MoveCommitRequest(
                        id = "move-" + java.util.UUID.randomUUID(),
                        sharedPath = shared.absolutePath,
                        stagingPath = File(cache, "move_staging_owned").absolutePath,
                        targets = targets,
                        removals = removals
                    )
                }
                val runResult = withContext(Dispatchers.IO) {
                    commitHost.execute(request)
                }
                if (runResult == null) {
                    // 宿主没能开始跑（例如系统拒绝启动前台服务）：不能照常当成功
                    _moveState.value = MoveState.Failed("系统拒绝启动前台服务，装入未开始；请保持应用在前台后重试")
                    return@launch
                }
                runResult.ledgerNotices.forEach { reportLedgerWrite(it.result, it.action) }
                if (runResult.failure != null || runResult.submission == null) {
                    val message = "装入未完成，请先核对游戏文件和安装记录：${runResult.failure.orEmpty()}"
                    _moveState.value = MoveState.Failed(message)
                    notifyLedgerNotice(message)
                    return@launch
                }
                val result = runResult.submission
                if (result.requiresReconciliation) notifyLedgerNotice(result.message)
                if (result.filesReplaced) {
                    _finalInstallResult.value = _finalInstallResult.value?.copy(command = null)
                    (_uninstallState.value as? UninstallState.Finished)?.let {
                        _uninstallState.value = it.copy(command = null)
                    }
                }
                if (result.completedSuccessfully) {
                    if (_installJobs.value === displayedJobs || _installJobs.value.isEmpty()) {
                        pendingRecordClear = false
                        pendingUninstallTargetHash = null
                    }
                    _moveState.value = MoveState.Success(result.message)
                } else {
                    _moveState.value = MoveState.Failed(result.message)
                }
                if (result.filesReplaced) {
                    try {
                        withContext(Dispatchers.IO) { refreshBundleCleanStates() }
                        refreshInstallStates()
                        refreshBackupUsage()
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (refreshFailure: Exception) {
                        // The durable submission result is already known. A metadata
                        // refresh failure is not a failed install or a reason to retry.
                        Log.w("MainViewModel", "装入后状态刷新失败，保留实际提交结果", refreshFailure)
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                _moveState.value = MoveState.Failed("装入已取消，请先核对游戏文件和安装记录")
                throw cancelled
            } catch (failure: Exception) {
                val message = "装入未完成，请先核对游戏文件和安装记录：${failure.message.orEmpty()}"
                _moveState.value = MoveState.Failed(message)
                notifyLedgerNotice(message)
            } finally {
                moveInProgress = false
                ShizukuManager.releaseConnection(moveConnectionToken)
            }
        }
    }

    /** Runs on IO with a frozen Main-thread snapshot, never reads live UI state. */
    private fun journalTargetsForJobs(
        downloadShared: File,
        jobs: List<InstallJob>,
        quality: String,
        astc: Boolean,
        installedAt: Long
    ): List<JournalTarget> = jobs.map { installJob ->
        val hashedName = installJob.job.hashedName
        val relative = (installJob.status as JobStatus.Finished).relativePath
        val upserts = installJob.job.modsToInstall.map { mod ->
            LedgerRecord(
                modUri = mod.uri.toString(),
                modName = mod.name,
                familyKey = mod.resolvedFamilyKey ?: hashedName,
                snapshotTargetHash = hashedName,
                quality = quality,
                usedAstc = astc,
                installedAt = installedAt
            )
        }
        JournalMoveFacts.target(downloadShared, hashedName, relative, upserts.map { it.modUri }, upserts)
    }

    /** 不重新扫描 mod 目录，只按最新的检测结果刷新状态徽章。 */
    private fun refreshInstallStates() {
        _modsList.value = applyInstallState(_modsList.value)
    }

    /**
     * 重新检测 Shizuku 是否已启动，并就地更新完成/还原对话框的状态。
     *
     * 用户常见处境：手机重启后 Shizuku 失效（真机每次重启都要重新激活），
     * 但 bundle 索引仍在缓存里，所以转换照样能跑完 —— 只是装不进游戏。
     * 有了这个方法，用户去启动 Shizuku 后回来点一下即可继续装入，
     * 不必重跑一遍耗时的转换。
     *
     * @return 检测后 Shizuku 是否可用，供 UI 层给出即时反馈
     */
    fun recheckShizuku(): Boolean {
        val available = ShizukuManager.isRunning()
        _moveState.value = MoveState.Idle
        _finalInstallResult.value = _finalInstallResult.value?.copy(shizukuAvailable = available)
        (_uninstallState.value as? UninstallState.Finished)?.let { finished ->
            _uninstallState.value = finished.copy(shizukuAvailable = available)
        }
        return available
    }

    fun resetMoveState() {
        _moveState.value = MoveState.Idle
    }

    /**
     * 移除单个 mod（而非整组还原）。
     *
     * 同一个 bundle 可能由多个 mod 合并打包而成，所以移除的语义分两种：
     *  - 该 bundle 还有别的 mod 留着 → 重新打包一次，只放保留下来的那些
     *  - 一个都不剩 → 走原有的还原流程，把官方原版盖回去
     *
     * 两种都需要用户再点一次「装入游戏」才真正生效（安卓改的是游戏缓存，绕不开这一步）。
     */
    fun removeSingleMod(context: Context, mod: ModInfo) {
        if (!true) return
        val targetHash = mod.targetHash ?: return

        // 先摘掉这一个，再看这个包上还剩谁。
        // 早先是 detachMod(familyKey, uri)，而账本按 familyKey「一包一条」记 ——
        // 卸同包第二个 mod 时按它自己的 familyKey 查不到记录，detachMod 返回空，
        // 就被当成「该包已无 mod」而整包还原，把同包其他 mod 一起卸了。
        val removal = uninstallExecutor.removeSingleModRecord(mod.uri.toString(), targetHash)
        removal.notices.forEach { reportLedgerWrite(it.result, it.action) }
        val remainingUris = removal.remainingModUris

        if (remainingUris.isEmpty()) {
            // 该 bundle 已无 mod —— 还原成官方原版
            initiateUninstall(context, targetHash)
            refreshInstallStates()
            return
        }

        // 还有保留项 —— 只带上它们重新打包一次
        val keep = _modsList.value.filter { it.uri.toString() in remainingUris }
        if (keep.isEmpty()) {
            // 记录还在但源文件都没了，重打包无从下手，只能还原原版
            Log.w("MainViewModel", "包 $targetHash 尚有 ${remainingUris.size} 条记录但源文件均缺失，改为还原原版")
            uninstallExecutor.clearTargetLedger(targetHash).forEach { reportLedgerWrite(it.result, it.action) }
            initiateUninstall(context, targetHash)
            refreshInstallStates()
            return
        }
        _moveState.value = MoveState.Idle
        batchStartTimeMs = System.currentTimeMillis()
        _installJobs.value = listOf(InstallJob(RepackJob(targetHash, keep)))
        _finalInstallResult.value = null
        _showInstallDialog.value = true
        processInstallJobs(context)
    }

    /**
     * 还原单个 bundle 的官方原版。
     *
     * 直接委托给批量还原那条路（一个空 mod 列表的 RepackJob）。原先这里是另一套实现：
     * 下载完就把 CDN 那份原样存进 Download/ —— 但 CDN 是压缩包，与游戏缓存里的 __data
     * 编码不同（实测同一 bundle 6.2 MB vs 26 MB），装进去游戏读不了。批量那条路已经处理
     * 好格式转换、备份优先和状态刷新，没有理由再维护第二份还原逻辑。
     */
    fun initiateUninstall(context: Context, hashedName: String) {
        if (_installJobs.value.any { it.status !is JobStatus.Finished && it.status !is JobStatus.Failed }) return
        pendingUninstallTargetHash = hashedName
        _moveState.value = MoveState.Idle
        batchStartTimeMs = System.currentTimeMillis()
        _installJobs.value = listOf(InstallJob(RepackJob(hashedName, emptyList())))
        _finalInstallResult.value = null
        _showInstallDialog.value = true
        processInstallJobs(context)
    }

    fun resetUninstallState() {
        _uninstallState.value = UninstallState.Idle
    }

    fun setUnpackInputFile(uri: Uri?) {
        _unpackInputFile.value = uri
    }

    /** 解包工具入口：SAF 选的文件先拷到缓存，python 解包后产物逐个落到 Download/outputs。 */
    /**
     * 解包入口：源文件拷进 cache → python 解包 → 产物逐个存进 Download/outputs。
     * 临时文件用完即清；失败也清，不留半个解包结果。
     */
    fun initiateUnpack(context: Context) {
        val inputFile = _unpackInputFile.value ?: return

        viewModelScope.launch {
            _unpackState.value = UnpackState.Unpacking("Starting unpack...")
            val (success, message) = withContext(Dispatchers.IO) {
                PythonRuntime.ensureStarted(context)
                val tempInput = File(context.cacheDir, "temp_unpack_input.bundle")
                context.contentResolver.openInputStream(inputFile)?.use { input ->
                    tempInput.outputStream().use { input.copyTo(it) }
                }

                val tempOutput = File(context.cacheDir, "temp_unpack_output").apply {
                    if (exists()) deleteRecursively()
                    mkdirs()
                }

                val result = modEngine.unpackBundle(
                    tempInput.absolutePath, tempOutput.absolutePath
                ) { progress ->
                    viewModelScope.launch(Dispatchers.Main) {
                        _unpackState.value = UnpackState.Unpacking(progress)
                    }
                    false   // 解包工具页没有取消入口
                }

                if (result.first) {
                    tempOutput.listFiles()?.forEach { file ->
                        saveFileToDownloads(context, file, file.name, "outputs")
                    }
                }

                tempInput.delete()
                tempOutput.deleteRecursively()
                result
            }

            _unpackState.value = if (success) {
                UnpackState.Finished("解包完成，文件已保存到 Download/outputs。")
            } else {
                UnpackState.Failed(message)
            }
        }
    }

    fun resetUnpackState() {
        _unpackState.value = UnpackState.Idle
        _unpackInputFile.value = null
    }

    /**
     * 图集合并入口：把选中的单个 mod 的文件拷到临时目录、跑 python 合并，
     * 然后把合并产物（png/atlas 与 .old 备份）写回 mod 源目录。
     * UI 入口当前未挂（转换时自动合并），保留供高级菜单复用。
     */
    fun initiateMerge(context: Context) {
        if (selectedMods.value.size != 1) return
        val modUri = selectedMods.value.first()
        val modInfo = _modsList.value.find { it.uri == modUri } ?: return

        _showMergeDialog.value = true
        _mergeState.value = MergeState.Merging("Preparing files...")

        viewModelScope.launch {
            val tempDir = File(context.cacheDir, "temp_merge_${System.currentTimeMillis()}")
            try {
                withContext(Dispatchers.IO) {
                    tempDir.apply { if (exists()) deleteRecursively(); mkdirs() }
                    if (modInfo.isDirectory) {
                        DocumentFile.fromTreeUri(context, modInfo.uri)?.let {
                            copyDirectoryToCacheNonRecursive(context, it, tempDir)
                        }
                    } else {
                        extractZipToFlatDir(context, modInfo.uri, tempDir)
                    }
                }

                val (success, message) = withContext(Dispatchers.IO) {
                    modEngine.mergeSpineAssets(tempDir.absolutePath) { progress ->
                        viewModelScope.launch(Dispatchers.Main) {
                            _mergeState.value = MergeState.Merging(progress)
                        }
                    }
                }

                _mergeState.value = if (success) {
                    writeMergeResultBack(context, modInfo, tempDir)
                } else {
                    MergeState.Failed(message)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _mergeState.value = MergeState.Failed(e.message ?: "An unknown error occurred.")
            } finally {
                withContext(Dispatchers.IO) {
                    if (tempDir.exists()) tempDir.deleteRecursively()
                }
            }
        }
    }

    /** zip mod 解到目录（拍平：只取文件名段，忽略目录层级）。 */
    private fun extractZipToFlatDir(context: Context, zipUri: Uri, destDir: File) {
        context.contentResolver.openInputStream(zipUri)?.use { fis ->
            ZipInputStream(fis).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val fileName = entry.name.substringAfterLast('/')
                    if (!entry.isDirectory && !shouldIgnoreModEntry(fileName)) {
                        File(destDir, fileName).outputStream().use { zis.copyTo(it) }
                    }
                    entry = zis.nextEntry
                }
            }
        }
    }

    /** 合并产物写回 mod 源目录：先删旧的 png/atlas，再写入合并后的（含 .old 备份目录）。 */
    private fun writeMergeResultBack(
        context: Context,
        modInfo: ModInfo,
        tempDir: File
    ): MergeState {
        val modDoc = DocumentFile.fromTreeUri(context, modInfo.uri)
        if (modDoc == null || !modDoc.isDirectory) {
            return MergeState.Failed("Failed to access original mod directory.")
        }
        modDoc.listFiles()
            .filter { it.isFile && (it.name?.endsWith(".png") == true || it.name?.endsWith(".atlas") == true) }
            .forEach { it.delete() }

        tempDir.listFiles()?.forEach { file ->
            when {
                file.isFile && (file.name.endsWith(".png") || file.name.endsWith(".atlas")) -> {
                    modDoc.createFile("application/octet-stream", file.name)?.let { doc ->
                        context.contentResolver.openOutputStream(doc.uri)?.use { output ->
                            file.inputStream().use { it.copyTo(output) }
                        }
                    }
                }
                file.isDirectory && file.name == ".old" -> {
                    modDoc.createDirectory(".old")?.let { oldDoc ->
                        copyDirectoryToSaf(context, file, oldDoc)
                    }
                }
            }
        }
        return MergeState.Finished("Successfully merged mod in-place!")
    }

    fun resetMergeState() {
        _mergeState.value = MergeState.Idle
        _showMergeDialog.value = false
    }

    /** SAF 目录的直接子文件拷进 cache 目录（不递归、跳过 .modfile 等噪音）。 */
    private fun copyDirectoryToCacheNonRecursive(context: Context, sourceDir: DocumentFile, destinationDir: File) {
        if (!destinationDir.exists()) destinationDir.mkdirs()
        sourceDir.listFiles().forEach { file ->
            val fileName = file.name ?: return@forEach
            if (!file.isFile || shouldIgnoreModEntry(fileName)) return@forEach
            try {
                context.contentResolver.openInputStream(file.uri)?.use { input ->
                    File(destinationDir, fileName).outputStream().use { input.copyTo(it) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /** 真实目录树整个拷进 SAF 目录（递归；合并产物回 mod 源目录时用）。 */
    private fun copyDirectoryToSaf(context: Context, sourceDir: File, destinationDoc: DocumentFile) {
        sourceDir.listFiles()?.forEach { file ->
            when {
                file.isFile -> {
                    if (shouldIgnoreModEntry(file.name)) return@forEach
                    destinationDoc.createFile("application/octet-stream", file.name)?.let { doc ->
                        context.contentResolver.openOutputStream(doc.uri)?.use { output ->
                            file.inputStream().use { it.copyTo(output) }
                        }
                    }
                }
                file.isDirectory ->
                    destinationDoc.createDirectory(file.name)?.let { dirDoc ->
                        copyDirectoryToSaf(context, file, dirDoc)
                    }
            }
        }
    }



    /**
     * 重扫**全部** mod 源目录并合并成一个列表。
     *
     * 原先是「扫哪个目录就只显示哪个」，签名也带着一个 dirUri。现在目录是一组，
     * 所以这里不再收参数 —— 想扫某个目录就先 [addModSourceDir] 把它加进来。
     *
     * `pendingScan` 那套防抖保留：扫描期间又被触发（加了新目录、下拉刷新）时
     * 不并发跑第二遍，而是记一个标记、当前这轮结束后再扫一次。
     */
    /** 从文件管理器返回时重扫；正在装入、更新或预解包时不插入额外任务。 */
    fun refreshModSourcesOnReturn() {
        if (!initialized || _modSourceDirs.value.isEmpty() || _isUpdatingCharacters.value ||
            _showInstallDialog.value || _isUpdatingOutdated.value ||
            _isDeletingAbnormal.value || prepackProgress.value != null
        ) return
        rescanAllModSources(preserveSelection = true)
    }

    private val clearSelectionForNextScan = java.util.concurrent.atomic.AtomicBoolean(false)

    fun rescanAllModSources(preserveSelection: Boolean = false) {
        if (!preserveSelection) clearSelectionForNextScan.set(true)
        pendingScan = true
        if (scanJob?.isActive == true) return

        scanJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                if (!pendingScan) break
                pendingScan = false

                val clearSelection = clearSelectionForNextScan.getAndSet(false)
                val dirs = _modSourceDirs.value
                if (dirs.isEmpty()) break

                withContext(Dispatchers.Main) {
                    _isLoading.value = true
                    _modScanProgress.value = ModScanProgress(0, dirs.size, "准备扫描")
                }

                isUpdatingCharacters.first { !it } // Wait for character data to be ready
                try {
                    val hidden = loadHidden()
                    // 按 uri 去重：目录嵌套（用户加了 A 又加了 A/sub）或同一 mod
                    // 出现在两处时，只留第一次扫到的那份。
                    val seen = HashSet<String>()
                    val mods = ArrayList<ModInfo>()
                    for ((dirIndex, dir) in dirs.withIndex()) {
                        withContext(Dispatchers.Main) {
                            _modScanProgress.value = ModScanProgress(
                                doneDirs = dirIndex,
                                totalDirs = dirs.size,
                                stage = "扫描目录 ${dirIndex + 1}/${dirs.size}"
                            )
                        }
                        val found = try {
                            modRepository.scanMods(dir)
                        } catch (e: ModRepository.IncompleteScanException) {
                            throw e
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // 单个目录出问题（权限被撤、被删）不该让整次扫描失败，
                            // 否则用户会以为所有 mod 都不见了。
                            Log.w("MainViewModel", "扫描目录失败，跳过: $dir", e)
                            emptyList()
                        }
                        for (m in found) {
                            val key = m.uri.toString()
                            if (key in hidden) continue
                            if (seen.add(key)) mods.add(m)
                        }
                        withContext(Dispatchers.Main) {
                            _modScanProgress.value = ModScanProgress(
                                doneDirs = dirIndex + 1,
                                totalDirs = dirs.size,
                                stage = "已发现 ${mods.size} 个 mod"
                            )
                        }
                    }
                    // 用户在扫描期间移除/重选目录，旧任务不再覆盖当前列表。
                    if (dirs != _modSourceDirs.value) {
                        pendingScan = true
                        continue
                    }
                    Log.d("MainViewModel", "扫描 ${dirs.size} 个目录，合计 ${mods.size} 个 mod")
                    withContext(Dispatchers.Main) {
                        _modScanProgress.value = ModScanProgress(
                            doneDirs = dirs.size, totalDirs = dirs.size, stage = "校验 mod 状态"
                        )
                    }
                    refreshBundleCleanStates()
                    // 四道检查在扫描链尾全量跑一遍（缓存命中的条目也重判）—— 每次扫描
                    // 即复查：游戏更新/画质切换后 catalog 变了，这遍重新分四类 ——
                    // 判出缺陷的进异常区、hash 目录名落后的进待更新区、看清画质后
                    // 依然有效的摘回正常分组（判定表见 validateMods）。
                    val validated = appContext?.let { validateMods(it, mods) } ?: mods
                    val withState = applyInstallState(validated)
                    // 正在删的条目不要被这轮扫描「复活」：一个目录 SAF 删要几秒，
                    // 这期间的重扫读到的还是删除前的状态，扫回来会让刚消失的行又跳出来
                    // 校验会联网；在主线程提交时再次核对来源，不能把已移除目录扫回来。
                    val published = withContext(Dispatchers.Main) {
                        if (dirs != _modSourceDirs.value) {
                            pendingScan = true
                            return@withContext null
                        }
                        val visible = withState.filterNot { deletingModUris.contains(it.uri.toString()) }
                        _modsList.value = visible
                        if (clearSelection) {
                            _selectedMods.value = emptySet()
                        } else {
                            val selectableUris = visible.filter {
                                it.resolutionState == ResolutionState.KNOWN && it.defect == null &&
                                        it.outdatedCurrentHash == null && !isUnknownCharacter(it.character)
                            }.map { it.uri }.toSet()
                            _selectedMods.value = _selectedMods.value intersect selectableUris
                        }
                        scanSettled = true
                        // 墓碑只挡「本轮扫描」这一个时间窗：结果已经落定，之后还能出现的
                        // 条目就是真的还在（比如删除失败又扫了回来）。不清的话，那些删不掉的
                        // 条目会被永久挡在扫描结果之外，用户再也看不到它们。
                        deletingModUris.clear()
                        captureModListSnapshot(visible)
                    }
                    // 只给实际提交的列表写快照，失效扫描不覆盖快照。
                    if (published != null) saveModListSnapshot(published)
                } catch (e: ModRepository.IncompleteScanException) {
                    Log.w("MainViewModel", "扫描未完成，保留上次列表", e)
                    withContext(Dispatchers.Main) {
                        appContext?.let { toast(it, "扫描未完成：${e.message}") }
                    }
                } finally {
                    withContext(Dispatchers.Main) {
                        _isLoading.value = false
                        _modScanProgress.value = null
                    }
                }

                if (!pendingScan) break
            }
        }
    }

    // ---------------------------------------------------------------- 冷启动列表快照

    /**
     * 上次扫描落定的整张列表，序列化在 filesDir 里，冷启动先拿它把「全部」填上。
     *
     * 为什么需要：角色表是本地文件、按角色视图秒开，而「全部」必须等整个 SAF 扫描
     * （上千个 mod 要好几秒），于是冷启动时那一页永远是空的 —— 用户以为 mod 没了。
     * 有快照后先显示上次的结果，扫描在后台跑完再整表替换（[rescanAllModSources]）。
     *
     * 快照只是显示用的缓存，**不是**事实来源：它可能过期（用户删了目录、撤销了授权、
     * 在文件管理器里动了文件），这些偏差都由随后的那次扫描收敛；扫描还会把新结果写回去。
     * 因此读写失败、文件损坏都只是「这次没有快照」，不影响任何功能。
     *
     * ## 磁盘格式与读写语义都在 [ModSnapshotStore]（T10 迁出）
     *
     * 本类从这一版起**只做转发**：`ModInfo` ⇄ [SnapshotEntry] 的转换（`Uri` 是 android
     * 类型，Store 刻意不碰）、以及「读出来之后怎么灌进列表」。块里旧实现的三个真缺陷被
     * 一并修掉（失败不上报、改名回退先删目标再改名 → 新旧皆失、schema 校验散在四处），
     * 详见 `ModSnapshotStore` 的类注释与 `docs/refactor/evidence/T10.md` §2。
     *
     * **旧快照仍能读回**：0.2.16 release 写出的 `{"a":…,"b":…,"c":…}`（字段被 R8 改名、
     * `version` 被整个删掉）由 Store 的 legacy 分支按 `mapping.txt` 的无歧义映射迁移读取。
     * 新写出的文件用规范键名，**绝不**再写 `a`/`b`/`c`。
     */
    private var snapshotStoreDir: File? = null
    private var snapshotStoreInstance: ModSnapshotStore? = null

    /**
     * 快照的磁盘访问点。
     *
     * **必须是「每个 filesDir 一个实例」的缓存，不能是每次现算**：`capture` 分配的
     * `revision` 是 [ModSnapshotStore] 的**实例状态**，`commit` 拿它做闸门。若 `capture`
     * 与 `commit` 落在两个不同的实例上，后者的计数器还是 0，**每一次提交都会被判成过期**、
     * 快照永远写不出去 —— 而且 debug 下完全看不出来（没有异常、只有一条 `Log.i`）。
     *
     * 缓存的是「上一次给的 filesDir → 那个 Store」，不是 `Context`：测试会换 `filesDir`
     * （`ModSnapshotTest` 用 `ContextWrapper`），换了就得换实例，按目录判等正好覆盖这个
     * 场景，同时不会把一个已经作废的 Context 留在这里。
     */
    private fun snapshotStore(): ModSnapshotStore? {
        val filesDir = appContext?.filesDir ?: return null
        if (snapshotStoreDir != filesDir) {
            snapshotStoreInstance = ModSnapshotStore(File(filesDir, ModSnapshotStore.SNAPSHOT_FILENAME))
            snapshotStoreDir = filesDir
        }
        return snapshotStoreInstance
    }

    /** 在列表提交时编号，旧 IO 任务即使较晚完成也不能盖掉较新的结果。 */
    private fun captureModListSnapshot(mods: List<ModInfo>): PendingWrite? =
        snapshotStore()?.capture(
            entries = mods.map(::toSnapshotEntry),
            sourceDirs = _modSourceDirs.value.map { it.toString() }
        )

    /**
     * `ModInfo` → 磁盘条目。
     *
     * 落盘时刻意**不写** `resolvedTargets` / `unresolvedFiles` / `convertedDataUri`
     * —— 它们是转换路径的数据，整个 Kotlin 侧没有消费者，却是体积大头。
     *
     * 改动前靠一个只作用于序列化的 `ExclusionStrategy` 按字段名排除它们。T10 首版把这段
     * 注释写成「那条策略在 release 包里**是坏的**（`FieldAttributes.getName()` 拿到 R8
     * 改名后的名字）」—— **二轮复核用本任务自己的夹具反证了它，这句是错的**：`ModInfo` 有
     * `data.model.**` 的包级 keep（自 0.1 就在），历史 release 的字段名原样未改，那条策略
     * **当年确实在生效**；夹具里这三个键本来就不存在，就是它生效的产物（详见
     * `ModSnapshotStore` 的类注释与 `T10.md` §11.1）。
     *
     * 现在改成 DTO 里根本没有这三个字段，**收益不是「修一个失效的策略」，而是判据不再依赖
     * 反射字段名** —— 与 0.2.16 里 `ModListSnapshot` 字段被改成 `a`/`b`/`c` 是同一类契约
     * 脆弱性（"持久化格式不许依赖会被改的东西"，T09 二轮那条教训的同型）。
     *
     * 代价（读侧）与旧行为**完全相同**：三个字段读回来分别是空表 / 空表 / null，
     * 见 [fromSnapshotEntry]。
     */
    private fun toSnapshotEntry(mod: ModInfo) = SnapshotEntry(
        name = mod.name,
        character = mod.character,
        costume = mod.costume,
        type = mod.type,
        isEnabled = mod.isEnabled,
        uri = mod.uri.toString(),
        targetHashedName = mod.targetHashedName,
        isDirectory = mod.isDirectory,
        resolutionState = mod.resolutionState.name,
        targetHash = mod.targetHash,
        resolvedFamilyKey = mod.resolvedFamilyKey,
        errorReason = mod.errorReason,
        installState = mod.installState.name,
        kind = mod.kind.name,
        convertedHashDir = mod.convertedHashDir,
        convertedDataSize = mod.convertedDataSize,
        defect = mod.defect?.name,
        outdatedCurrentHash = mod.outdatedCurrentHash
    )

    /**
     * 磁盘条目 → `ModInfo`。结构完整性已由 [ModSnapshotStore] 在读侧判过
     * （8 个非空字段逐个相同），这里剩下的只有「字符串 → 枚举」的解析：
     * 枚举名对不上说明文件来自别的 schema，**整条丢掉**（[mapNotNull]），
     * 不猜一个默认值 —— 猜出来的状态会在 UI 上显示成一条像真的假信息。
     */
    private fun fromSnapshotEntry(entry: SnapshotEntry): ModInfo? {
        val uri = entry.uri ?: return null
        val name = entry.name ?: return null
        val character = entry.character ?: return null
        val costume = entry.costume ?: return null
        val type = entry.type ?: return null
        val resolutionState = enumOrNull<ResolutionState>(entry.resolutionState) ?: return null
        val installState = enumOrNull<ModInstallState>(entry.installState) ?: return null
        val kind = enumOrNull<ModKind>(entry.kind) ?: return null
        return ModInfo(
            name = name,
            character = character,
            costume = costume,
            type = type,
            isEnabled = entry.isEnabled,
            uri = Uri.parse(uri),
            targetHashedName = entry.targetHashedName,
            isDirectory = entry.isDirectory,
            resolutionState = resolutionState,
            targetHash = entry.targetHash,
            resolvedFamilyKey = entry.resolvedFamilyKey,
            // 落盘时被丢掉的三个字段，读回来与旧实现给的值一致：空表 / 空表 / null。
            // 这不是「丢了数据」，而是「本来就没存」（旧实现靠 Gson 的 Unsafe 造实例，
            // 读回来同样是这三个值）。
            resolvedTargets = emptyList(),
            unresolvedFiles = emptyList(),
            errorReason = entry.errorReason,
            installState = installState,
            kind = kind,
            convertedHashDir = entry.convertedHashDir,
            convertedDataSize = entry.convertedDataSize,
            convertedDataUri = null,
            defect = enumOrNull<ModDefect>(entry.defect),
            outdatedCurrentHash = entry.outdatedCurrentHash
        )
    }

    /** 按名字取枚举；不认识的名字（或 null）返回 null。 */
    private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? =
        name?.let { n -> enumValues<T>().firstOrNull { it.name == n } }

    /**
     * 本轮进程里扫描是否已经落定过一次。
     *
     * 快照只许在这次扫描**之前**进场：扫描一旦出过结果，列表就是事实，
     * 迟到的快照不许再把它盖回去 —— 尤其扫出空表（用户把目录清空了）那种情况，
     * 被旧快照覆盖就是「删掉的 mod 又回来了」，而且不会再有下一次扫描来纠正。
     */
    @Volatile
    private var scanSettled = false

    /** 源目录被清空时把快照一并抹掉（**只删快照文件本身**，绝不碰账本/配置）。 */
    private fun clearModListSnapshot() {
        val store = snapshotStore() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            if (!store.discard()) {
                Log.w("MainViewModel", "快照失效失败（不影响功能）：${store.file.path}")
            }
        }
    }

    /**
     * 落快照。写在 IO 上、失败只记日志：宁可下次启动回到「没有快照」的老路径，
     * 也不能因为一个缓存文件写不进去影响扫描收尾。
     *
     * 原子提交与失败回退都在 [ModSnapshotStore.commit]（走 T07 的 `AtomicFileWriter`）。
     * 调用方**不需要**再判 revision/源目录 —— 那两道闸在 Store 里，且能如实回报
     * 「为什么这次没落盘」（[SnapshotCommit.Skipped]）；改动前这里是静默 `return`，
     * 「这份快照为什么没写进去」在测试里只能靠间接推断。
     */
    private fun saveModListSnapshot(write: PendingWrite?) {
        // null = 这次没有可写的快照（`appContext` 还没挂上，或扫描在提交前就被源目录变化
        // 判废）。与「写了但被闸门拒绝」不是一回事，所以在这里就返回，不进 Store。
        val store = snapshotStore() ?: return
        if (write == null) return
        when (val result = store.commit(write, _modSourceDirs.value.map { it.toString() })) {
            is SnapshotCommit.Written -> Unit
            is SnapshotCommit.Skipped -> Log.i("MainViewModel", "本次不落快照：${result.reason}")
            is SnapshotCommit.Failed ->
                Log.w("MainViewModel", "写入冷启动快照失败（不影响功能）", null)
        }
    }

    /**
     * 冷启动把快照灌进列表。跑在 IO 上，读完才回主线程。
     *
     * installState 用账本重算一遍（[applyInstallState]）：快照里那份是上次扫描时的判断，
     * 账本才是「装过什么」的事实。此刻干净检测还没跑过（bundleCleanStates 还是空的），
     * 算出来的是「账本里有 → 未校验 / 没有 → 未装」——这是诚实的：游戏目录还没看过，
     * 扫描跑完自然会落回生效中/需重新应用那些真实状态。
     *
     * defect/outdated 按快照原样显示（它们依赖 catalog，冷启动时还没有更新的依据）。
     *
     * ## 分态处置（T10）
     *
     * - [SnapshotLoad.Loaded]：按旧口径灌列表（下面那套闸不变）。
     * - [SnapshotLoad.StaleVersion] / [SnapshotLoad.Corrupt]：**显式失效** —— 只删快照
     *   文件本身，重建即可。这是计划第 2 条要的「明确重新加载」，而不是让一份读不回来的
     *   文件永远躺在那里、每次冷启动都空转一次。
     * - [SnapshotLoad.SourceDirsChanged]：**不删**。表是好的，只是属于另一组源目录；
     *   用户可能只是临时切走再切回来，下一次扫描收尾会正常覆盖它。
     * - [SnapshotLoad.Absent] / [SnapshotLoad.IoError]：什么都不做。
     */
    private fun restoreModListSnapshot() {
        viewModelScope.launch {
            val store = snapshotStore()
            val sources = _modSourceDirs.value
            val restored = withContext(Dispatchers.IO) {
                if (store == null) return@withContext emptyList()
                when (val loaded = store.loadAndMigrate(sources.map { it.toString() })) {
                    is SnapshotLoad.Loaded -> {
                        if (loaded.droppedEntries > 0) {
                            // 「表读出来了」与「表读出来了但少了几条」是两件事，必须分开可见。
                            // 旧实现的 mapNotNull 把后者静默吞了。
                            Log.w(
                                "MainViewModel",
                                "快照有 ${loaded.droppedEntries} 条结构不完整，已跳过"
                            )
                        }
                        val hidden = loadHidden()
                        val mods = loaded.entries.mapNotNull(::fromSnapshotEntry)
                        if (mods.isEmpty()) emptyList()
                        else applyInstallState(mods.filterNot { it.uri.toString() in hidden })
                    }
                    is SnapshotLoad.StaleVersion -> {
                        Log.i(
                            "MainViewModel",
                            "快照版本 ${loaded.version} 不是 ${ModSnapshotStore.SCHEMA_VERSION}，丢弃重建"
                        )
                        store.discard()
                        emptyList()
                    }
                    is SnapshotLoad.Corrupt -> {
                        Log.w("MainViewModel", "冷启动快照损坏（${loaded.reason}），丢弃重建", null)
                        store.discard()
                        emptyList()
                    }
                    is SnapshotLoad.SourceDirsChanged -> {
                        Log.i("MainViewModel", "快照属于另一组源目录，本次不用（不删）")
                        emptyList()
                    }
                    is SnapshotLoad.Absent -> emptyList()
                    is SnapshotLoad.IoError -> {
                        Log.w("MainViewModel", "冷启动快照读取失败，按没有快照处理", null)
                        emptyList()
                    }
                }
            }
            // 扫描已经出过结果时不许覆盖：那份才是真的（见 scanSettled）。
            // 没有源目录时也不灌：那种情况下列表本就该是空的（欢迎页 / 授权被撤销），
            // 快照只会显出一批再也扫不到、也点不动的幽灵条目
            if (restored.isNotEmpty() && !scanSettled && _modsList.value.isEmpty() &&
                sources.isNotEmpty() && sources == _modSourceDirs.value
            ) {
                _modsList.value = restored
            }
        }
    }

    // ---------------------------------------------------------------- 干净检测

    /** 上次干净检测的结果：bundle 名 -> 干净程度。装 mod 时用它决定基底从哪来。 */
    private var bundleCleanStates: Map<String, BundleCleanState> = emptyMap()

    /** 同一次检测里记下的 bundle 名 -> hash 目录名，避免每个 job 都重新遍历游戏目录。 */
    private var gameBundleHashes: Map<String, String> = emptyMap()

    /**
     * 上次干净检测失败的原因，null 表示上次检测跑完了（结果可能是空表，那是「确实都干净」）。
     *
     * 有这个字段才能把「都是原版」和「压根没测出来」分开。缺了它，Shizuku 断开或者
     * 连不上 CDN 时，一键卸载会显示「游戏目录里所有资源都是官方原版」—— 用户据此
     * 以为 mod 已经卸干净，实际一个都没动。
     */
    private var cleanScanError: String? = null

    /**
     * 上面那句的技术细节（哪个域名、底层抛的原文），收进对话框的折叠区。
     *
     * 分开是因为这两段的读者不同：主文案要让玩家一眼知道该干什么，
     * 技术原文只在排查时有用 —— 摊在正文里只会把那句「检查网络后重试」淹掉。
     */
    private var cleanScanDetail: String? = null

    /** 当前画质 catalog 的 bundle 元数据（bundle 名 -> (原版大小, 内容哈希)）。
     *  null = 这次没拿到（离线且无缓存），查表类的检查只能跳过。 */
    private var catalogBundleMeta: Map<String, Pair<Long, String>>? = null

    /**
     * **另一档**画质（HD ↔ SD）的 bundle 元数据，只用于分辨「版本轮换」与「画质错配」。
     *
     * 为什么非要有它：实测同代 HD/SD 两档 catalog 有 1412 个 hex 同名不同哈希
     * （尺寸中位缩放 0.616）。只看当前档的「hash 不符」，分不出这是游戏更新轮换了哈希
     * 还是产物本来就按另一档转的 —— 判错的代价是把另一档画质的产物改到当前档哈希位，
     * 游戏照读不误，静默装错画质（见 [ModDefect.QUALITY_MISMATCH]）。
     *
     * 只与 [catalogBundleMeta] 成对更新，且要求两者都是新鲜表：null（另一档没取到、
     * 或当前档本身是降级表）时，hash 不符一律保守按 STALE 拦住，不错改。
     */
    private var otherTierBundleMeta: Map<String, Pair<Long, String>>? = null

    /** 元数据是否来自离线降级（磁盘缓存的旧表）：true 时查表判定仍做，但结果
     *  可能基于旧版 catalog，[unverifiedModCount] 照亮「未校验」标识提醒用户。 */
    private var catalogMetaDegraded = false

    /** 因拿不到 catalog 而没做过期检查的 mod 数，>0 时 Mods 顶栏亮「无信号」标识。
     *  离线降级表查出的异常照常显示 —— 但基于降级表的判定可能误报（游戏刚更新、
     *  缓存表还是旧版时会把新 mod 也判过期），所以降级时全部查表条目都计为未验证。 */
    private val _unverifiedModCount = MutableStateFlow(0)
    val unverifiedModCount: StateFlow<Int> = _unverifiedModCount.asStateFlow()

    /** 正在还原的 bundle 名。装入游戏成功后据它清掉对应记账。 */
    private var pendingUninstallTargetHash: String? = null

    /**
     * 刷新干净检测。
     *
     * 把 catalog 的权威值（每个 bundle 的原版字节数 + 内容哈希）与游戏目录的实际值
     * （hash 目录名 + __data 大小）比对。这是客观判定，不依赖 app 自己的记账，因此
     * 也能发现旧版工具或手动装入造成的改动。
     *
     * 重打包产物沿用原版的路径结构，所以目录名对 mod 版和原版是一样的 —— 只有大小
     * 能区分两者，这也是当初给 listBundleDirectory 加 size 字段的原因。
     */
    private suspend fun refreshBundleCleanStates(): Map<String, BundleCleanState> {
        val context = appContext ?: return emptyMap()

        val local = ShizukuManager.listGameBundles()

        // 画质自检 + 取元数据：先按当前档位取，对不上时换另一档比对比对。
        // Shizuku 没开也要取 —— 扫描期校验（异常 mod 判定）要查这张表，查表本身
        // 不依赖游戏目录；只是没了目录实际值，画质自检做不了，按设置档位取。
        var metaHint: String? = null
        val metaResult = withContext(Dispatchers.IO) {
            val result = if (local != null) {
                detectQualityAndFetchMeta(context, local) { msg ->
                    Log.d("MainViewModel", "bundleMeta: $msg")
                    if (msg.contains("失败")) metaHint = msg
                }
            } else {
                modEngine.getBundleMeta(
                    context.filesDir.absolutePath, _selectedQuality.value
                ) { msg ->
                    Log.d("MainViewModel", "bundleMeta: $msg")
                    if (msg.contains("失败")) metaHint = msg
                }
            }
            // 另一档画质的表：判断「版本轮换 vs 画质错配」要用（见 otherTierBundleMeta）。
            // 画质自检（detectQualityAndFetchMeta）可能刚切过档，所以这里读的是切换后的
            // _selectedQuality —— 拿到的就是「不是当前档」的那一档。
            otherTierBundleMeta = fetchOtherTierMeta(context, result)
            result
        }
        val meta = metaResult?.first
        catalogBundleMeta = meta
        catalogMetaDegraded = metaResult?.second == true
        if (catalogMetaDegraded) {
            Log.i("MainViewModel", "bundle 元数据来自磁盘缓存（离线降级），判定结果可能基于旧版 catalog")
        }

        if (local == null) {
            bundleCleanStates = emptyMap()      // 拿不到实际值就不做判断，而非假定干净
            gameBundleHashes = emptyMap()
            // 空表和「检测失败」必须分开：前者是「确实都干净」，后者是「不知道」。
            // 一键卸载据此决定是报错还是说「没有需要卸载的」—— 混在一起会让
            // 网络/Shizuku 出问题时显示「所有资源都是官方原版」，用户以为已经卸干净了。
            cleanScanError = "无法读取游戏目录。请确认 Shizuku 正在运行，然后在设置里点「重新检测 Shizuku」。"
            cleanScanDetail = null
            return bundleCleanStates
        }
        gameBundleHashes = local.mapValues { it.value.first }

        if (meta == null) {
            bundleCleanStates = emptyMap()
            cleanScanError = "无法获取官方资源清单，请检查网络后重试。"
            cleanScanDetail = "判断哪些资源被改过，要先从官方 CDN（cdn.bd2.pmang.cloud）" +
                    "取到一份资源清单（catalog），这一步没成功。" +
                    (metaHint?.let { "\n\n$it" } ?: "")
            return bundleCleanStates
        }

        val states = HashMap<String, BundleCleanState>(meta.size)
        val hasBackupRepo = true
        for ((name, expected) in meta) {
            val (expectedSize, expectedHash) = expected
            val actual = local[name]
            val hashDir = actual?.first
            if (actual == null) {
                states[name] = BundleCleanState.ABSENT
                continue
            }
            // T09：判定收进 PristineVerifier.classifyListing —— 纯函数、可单测。
            //
            // 它**不再**拿「游戏目录里那份的大小 == 我们自己备份的大小」当原版判据
            // （旧实现在这里有一分支正是如此）。那条判据是循环自证：备份是我们自己
            // 存下来的，来源无从查证。现在备份要能当基准，必须带 sidecar 且其来源
            // 属于 BYTE_ANCHORED（从官方 CDN 下载、或由它确定性派生）——
            // 旧备份没有 sidecar，一律落 UNKNOWN，不会被当成原版。
            //
            // 注意这一层**只看得到 (目录名, 尺寸)** —— 游戏目录的字节这个 app 读不到
            // （在别的应用的 data 下，要 Shizuku 才读得了），所以扫描层能做到的上限
            // 就是「锚点一致」级。真正的字节级判定在使用点（tryUseLocalBundle /
            // tryUseBackup），那里读得到字节，走的是 classify() 的摘要比对。
            val catalogAnchor = CatalogAnchor(expectedHashDir = expectedHash, size = expectedSize)
            val backupBaseline = if (hasBackupRepo && hashDir != null) {
                backupRepository.byteAnchoredBaseline(name, hashDir)
            } else {
                null
            }
            states[name] = verifier.classifyListing(
                hashDir = hashDir,
                size = actual.second,
                catalog = catalogAnchor,
                backup = backupBaseline,
            ).toBundleCleanState()
        }
        bundleCleanStates = states
        cleanScanError = null
        cleanScanDetail = null
        Log.d("MainViewModel", "干净检测: ${states.size} 个 bundle，" +
                "其中被修改 ${states.count { it.value == BundleCleanState.MODIFIED }} 个")
        return states
    }

    /**
     * `PristineVerifier` 的 [Verdict] → UI 用的 [BundleCleanState]。
     *
     * 两个枚举**逐字对齐**（见 `PristineVerifier.kt` 的 Verdict 注释），所以这里是
     * 一次没做任何转换的直接映射 —— 保留成显式函数而不是 `valueOf`，是为了让
     * 「将来某一边加了取值」在编译期就红，而不是运行期抛。
     */
    private fun Verdict.toBundleCleanState(): BundleCleanState = when (this) {
        Verdict.PRISTINE -> BundleCleanState.PRISTINE
        Verdict.MODIFIED -> BundleCleanState.MODIFIED
        Verdict.VERSION_MISMATCH -> BundleCleanState.VERSION_MISMATCH
        Verdict.ABSENT -> BundleCleanState.ABSENT
        Verdict.UNKNOWN -> BundleCleanState.UNKNOWN
    }

    /**
     * 按游戏目录里实际存在的内容哈希推断资源档位，返回该用的 bundle 元数据。
     *
     * 游戏 Shared/<bundle>/<hash>/ 的 hash 来自游戏自己下载时用的那份 catalog
     * （HD / SD 各一份），所以跟两档 catalog 的权威哈希比对即可：哪档对得多
     * 就是哪档。推断出与设置不同的档位就顺手切换 —— 选错档会让干净检测把
     * 全部资源判成「版本不符」，下载的原版也和游戏目录对不上。
     *
     * 游戏比 catalog 旧（更新后没进过游戏）时两档都对不上，保持现状 ——
     * 那种情况有「游戏资源需要更新」的提醒兜底。另一档的 catalog 只在真的
     * 需要判别时才下载（约 60MB，之后磁盘缓存复用）。
     */
    private suspend fun detectQualityAndFetchMeta(
        context: Context,
        local: Map<String, Pair<String, Long>>,
        onProgress: (String) -> Unit
    ): Pair<Map<String, Pair<Long, String>>, Boolean>? {
        val selected = _selectedQuality.value
        val selectedResult = modEngine.getBundleMeta(context.filesDir.absolutePath, selected, onProgress)
            ?: return null
        val selectedMeta = selectedResult.first
        // 游戏目录还没东西（刚装游戏）或当前档位对得上 —— 不需要判别
        if (local.isEmpty() || hashMatchRatio(local, selectedMeta) > 0.5) {
            return selectedResult
        }

        // 当前档位对不上：试另一档。对得明显更多才切，避免半更新状态误判。
        // 画质判别在线下没意义（离线降级表对哪一档都只有一份缓存），直接返回本档。
        if (selectedResult.second) return selectedResult
        val other = if (selected == "HD") "SD" else "HD"
        val otherResult = modEngine.getBundleMeta(context.filesDir.absolutePath, other) { }
            ?: return selectedResult
        if (hashMatchRatio(local, otherResult.first) > hashMatchRatio(local, selectedMeta)) {
            Log.i("MainViewModel", "画质自检：游戏资源是 $other（原设置 $selected），已自动切换")
            setSelectedQuality(other)
            return otherResult
        }
        return selectedResult
    }

    /** 游戏目录与 catalog 都有的 bundle 里，内容哈希一致的比例。 */
    private fun hashMatchRatio(
        local: Map<String, Pair<String, Long>>,
        meta: Map<String, Pair<Long, String>>
    ): Double {
        val common = local.keys intersect meta.keys
        if (common.isEmpty()) return 0.0
        val matched = common.count { name -> local[name]?.first == meta[name]?.second }
        return matched.toDouble() / common.size
    }

    /**
     * 取另一档画质（HD ↔ SD）的 bundle 元数据，只用于分辨「版本轮换」与「画质错配」。
     *
     * 只在当前档拿到**新鲜**表时才取：当前档是降级表（离线磁盘缓存）或压根没取到时，
     * 判断依据本身就不可信，没必要再花一次请求；另一档同样要求非降级 —— 两张旧表
     * 互相印证出的还是旧结论，反而可能把版本轮换的产物误判成画质错配。
     *
     * 取不到（离线 / 请求失败 / 只有降级缓存）就返回 null，由 validateMods 走保守分支：
     * hash 不符的一律按 STALE 拦住，不让「改到当前档哈希」发生在看不清画质的时候。
     * 不重试、不抛 —— 扫描链不能因为它挂掉。
     *
     * **阻塞调用（会走 python 取表），调用方必须已经在 IO 线程上。**
     */
    private fun fetchOtherTierMeta(
        context: Context,
        currentResult: Pair<Map<String, Pair<Long, String>>, Boolean>?
    ): Map<String, Pair<Long, String>>? {
        if (currentResult == null || currentResult.second) return null
        val other = if (_selectedQuality.value == "HD") "SD" else "HD"
        return try {
            val result = modEngine.getBundleMeta(context.filesDir.absolutePath, other) { }
            if (result == null || result.second) {
                Log.i("MainViewModel",
                    "另一档（$other）catalog 本次不可用：hash 不符的产物分不出画质错配，一律保守拦住")
                null
            } else {
                result.first
            }
        } catch (e: Exception) {
            Log.w("MainViewModel", "取另一档（$other）catalog 失败", e)
            null
        }
    }

    /**
     * 对整份 mod 列表跑四道检查（截断名 / 文件头 / catalog 查表 / 结构非法），
     * 给确定无效的条目标上 [ModDefect]。
     *
     * 每次扫描都全量重算，缓存命中的条目也不例外 —— 游戏更新、画质切换后 catalog
     * 变了，这一遍就是「复查」：判出缺陷的进异常区，切回有效画质/版本的自动回正常区。
     * 顺带把「角色表刷新失败、游戏却已更新」时缓存里存活的过期 targetHash 也
     * 查了出来 —— 以前这种要拖到转换下载那一步才报错。
     *
     * **「hash 对不上」分三种，别混在一起**（实测：游戏更新后所有 bundle 的内容哈希
     * 都轮换，但 hex 目录名与内容逐字节不变；而同代 HD/SD 两档有 1412 个 hex 同名
     * 不同哈希）：
     *  · hex 名还在当前档、内层 hash 只是落后于版本 → 填
     *    [ModInfo.outdatedCurrentHash]，**不算缺陷**，进「待更新」区，改名即治愈；
     *  · hash 目录名对得上**另一档**画质 → [ModDefect.QUALITY_MISMATCH]，进异常区
     *    （改了等于把另一档画质的内容装进本档，游戏照读不误）；
     *  · hex 名压根不在 catalog（非标打包 / 已下架）→ STALE，真死，留在异常区。
     *
     * 判定优先级：当前档命中（正常）→ 降级表（只报不改）→ 另一档命中（画质错配）
     * → 另一档表缺失（分不清，保守拦住）→ hex 不在本档表（真死）→ 待更新。
     * 画质判定**必须排在「hex 不在表」之前**：另一档独有的 bundle 在本档表里查不到，
     * 顺序反了会被误判成「已从当前游戏移除」。
     *
     * 离线（[catalogBundleMeta] 为 null）只跳过查表那一道，其余三道是纯本地检查
     * 照跑；本该查表却没查成的条目数进 [unverifiedModCount]（顶栏无信号标识）。
     * 降级表（离线磁盘缓存）与「另一档没取到」这两种看不清的情形都不填「待更新」，
     * 且都计入未校验 —— 顶栏「无信号」点按的重试会把两张表一起重取，正是这两条
     * 的正解（见 [otherTierBundleMeta]）。
     */
    private fun validateMods(context: Context, mods: List<ModInfo>): List<ModInfo> {
        val meta = catalogBundleMeta
        // 降级表（离线磁盘缓存）：查表判定照做 —— 截断/损坏/需拆分与网络无关，
        // STALE 判定也比不判强。但降级表的「过期」可能是假阳性（缓存表是旧版、
        // 游戏刚更新），所以查过降级表的条目全部计入未验证，亮标识提醒。
        val degraded = catalogMetaDegraded
        val nameResolver = BundleNameResolver(context)
        var unverified = 0
        val out = mods.map { mod ->
            // 非空 = 「待更新」：hex 目标还在 catalog 里，只是内层 hash 目录名落后了。
            // 不是缺陷，进 Mods 列表的「待更新」区，改名即可治愈（见 updateOutdatedMod）。
            var outdated: String? = null
            val defect = when (mod.kind) {
                ModKind.CONVERTED_BUNDLE -> {
                    val bundleName = mod.targetHash.orEmpty()
                    when {
                        // ① 目录名残缺：路径对不上，装了也不生效
                        nameResolver.looksTruncated(bundleName) -> ModDefect.TRUNCATED
                        // ④ 文件头：半截/损坏的 __data 游戏照常加载 → 特定界面崩
                        checkUnityFsHeader(context, mod) == false -> ModDefect.CORRUPT
                        // ②③ 查表：先看 hex 名还在不在当前画质 catalog，再看内层 hash
                        meta != null -> {
                            val expected = meta[bundleName]
                            // 另一档画质下同一个 hex 的哈希：用来分辨版本轮换与画质错配
                            val otherHash = otherTierBundleMeta?.get(bundleName)?.second
                            when {
                                // 当前档命中 = 正常。这一条必须排在降级判断之前：降级表
                                // 也可能「通过」，那种情况按老规矩算正常但计未校验，
                                // 不能因为表旧就把好 mod 判成缺陷。
                                expected != null && expected.second == mod.convertedHashDir -> {
                                    if (degraded) unverified++
                                    null
                                }
                                // 降级表（离线磁盘缓存）：哈希可能整个是旧版的，拿它当
                                // 改名目标可能把好 mod 改坏 —— 只报不改，计未校验提醒
                                degraded -> {
                                    unverified++
                                    ModDefect.STALE
                                }
                                // 画质错配：本档哈希对不上，但对得上**另一档** —— 这个产物
                                // 本来就是按另一档转的。绝不能按待更新去改名：那是把另一档
                                // 画质（贴图尺寸都不对）的内容塞进本档哈希位，游戏照读不误，
                                // 静默装错画质比装不上更糟。**排在死目标判定之前**：
                                // 另一档独有的 bundle 在本档表里查不到，不先看另一档就会被
                                // 误判成「已从当前游戏移除」。
                                mod.convertedHashDir != null && otherHash == mod.convertedHashDir ->
                                    ModDefect.QUALITY_MISMATCH
                                // 另一档的表没取到 → 分不清是版本轮换还是画质错配，
                                // 宁可不改。计未校验：顶栏「无信号」亮起来，点它重试正好
                                // 会把另一档也重取一遍，这条就能落到确定答案上。
                                otherTierBundleMeta == null -> {
                                    unverified++
                                    ModDefect.STALE
                                }
                                // hex 名不在本档表里、也不在另一档 = 这一档清单里确实
                                // 没有它（下架/移除）—— 无从更新，是死目标，留在异常区
                                expected == null -> ModDefect.STALE
                                // hash 目录名落后：内容与 hex 名都没变，改名即治愈。
                                // 不算缺陷 —— 它没有装不进去的问题，只是名字旧了。
                                else -> {
                                    outdated = expected.second
                                    null
                                }
                            }
                        }
                        // 本地两道过了，但离线没表可查
                        else -> { unverified++; null }
                    }
                }
                ModKind.PC_SOURCE -> when {
                    // ⑤ 结构非法：一个文件夹混入了多个 bundle 的文件
                    mod.resolutionState == ResolutionState.INVALID -> ModDefect.INVALID
                    // ②③ 查表：已解析出目标 bundle 的，确认它还在当前 catalog 里
                    mod.targetHash != null && (mod.resolutionState == ResolutionState.KNOWN ||
                            mod.resolutionState == ResolutionState.MISC) -> {
                        if (meta == null) { unverified++; null }
                        else {
                            if (degraded) unverified++         // 降级表的判定同样不算真验证
                            if (!meta.containsKey(mod.targetHash)) ModDefect.STALE
                            else null
                        }
                    }
                    // 没解析出目标（UNKNOWN）→ 「未识别」区，不判异常
                    else -> null
                }
            }
            // 「待更新」只属于已转换产物：PC 源没有 hash 目录可改
            if (mod.kind != ModKind.CONVERTED_BUNDLE) outdated = null
            if (defect == mod.defect && outdated == mod.outdatedCurrentHash) mod
            else mod.copy(defect = defect, outdatedCurrentHash = outdated)
        }
        _unverifiedModCount.value = unverified
        val defective = out.count { it.defect != null }
        if (defective > 0 || unverified > 0) {
            Log.i("MainViewModel", "扫描期校验：异常 $defective 个，未校验 $unverified 个")
        }
        return out
    }

    /**
     * 读产物 __data 的文件头判损坏：魔数必须是 UnityFS，且头部声明的 bundle 总长度
     * 不得超过实际字节数（写了一半的文件：声明长度 > 实际长度）。
     *
     * 返回 true=完好 / false=损坏 / **null=读不出来不判**（SAF 开流失败、大小缺失、
     * 头部声明区没读全）。只把「确定坏」的判成 false —— 把好 mod 误标成损坏，
     * 用户会照着提示去删好文件。
     */
    private fun checkUnityFsHeader(context: Context, mod: ModInfo): Boolean? {
        val dataUri = mod.convertedDataUri ?: return null
        val actualSize = mod.convertedDataSize
        return try {
            context.contentResolver.openInputStream(Uri.parse(dataUri))?.use { ins ->
                val head = ByteArray(128)
                var len = 0
                while (len < head.size) {
                    val r = ins.read(head, len, head.size - len)
                    if (r < 0) break
                    len += r
                }
                if (len < 16 || !head.copyOfRange(0, 8).contentEquals(UNITYFS_MAGIC)) {
                    return@use false
                }
                // 头部布局：魔数(8) + 格式版本 int32 大端 + 两条 null 结尾字符串
                // + bundle 总长度 int64 大端
                var p = 12
                repeat(2) {
                    while (p < len && head[p].toInt() != 0) p++
                    p++
                }
                if (p + 8 > len) return@use null
                var declared = 0L
                for (i in 0 until 8) declared = (declared shl 8) or (head[p + i].toLong() and 0xFF)
                when {
                    actualSize <= 0L -> null            // SAF 没报大小，比不了，不判
                    // 只拒「声明长度超过实际字节数」= 确定的截断。反方向（声明 < 实际）
                    // 实测存在于外部 repacker 的产物里、且游戏照常加载 —— 那种不判坏，
                    // 否则用户会照着提示删掉能用的好 mod
                    declared > actualSize -> false
                    else -> true
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 顶栏「无信号」标识的点按动作：重新联网取一次 catalog 元数据，对当前列表重跑校验。
     *
     * 不重扫源目录 —— 文件没变，变的只是「这次有没有表可查」：把离线时跳过的
     * 过期检查补上。取到表后未验证数归零，标识自然消失；仍然取不到就维持现状。
     */
    fun retryCatalogValidation() {
        val context = appContext ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = modEngine.getBundleMeta(
                    context.filesDir.absolutePath, _selectedQuality.value
                ) { }
                if (result == null) return@launch
                catalogBundleMeta = result.first
                catalogMetaDegraded = result.second
                // 与这张表成对更新：判画质错配要看另一档（见 otherTierBundleMeta）。
                // 不跟着刷的话，离线时没取到的那一档会一直缺着，hash 不符的产物
                // 联网后仍然全按保守的 STALE 处理 —— 用户点了「重试」却没变化。
                otherTierBundleMeta = fetchOtherTierMeta(context, result)
                val revalidated = applyInstallState(validateMods(context, _modsList.value))
                // 与扫描同一条回写路径，墓碑也要一起套用：不然「重试」这几秒里被乐观删掉的
                // 条目会被这一遍整表回写复活（它的文件同样还在磁盘上）
                val visible = revalidated.filterNot { deletingModUris.contains(it.uri.toString()) }
                // 这一遍可能把原本正常的条目判成异常/待更新（离线时表是旧的、看不出来），
                // 而选中集是上一轮的 —— 不清掉的话，批量转换会把已经失效的产物照单收下。
                val invalid = visible
                    .filter { it.defect != null || it.outdatedCurrentHash != null }
                    .map { it.uri }
                    .toSet()
                withContext(Dispatchers.Main) {
                    _modsList.value = visible
                    if (invalid.isNotEmpty()) {
                        _selectedMods.value = _selectedMods.value - invalid
                    }
                }
            } catch (e: Exception) {
                Log.w("MainViewModel", "重新校验失败", e)
            }
        }
    }

    // ---------------------------------------------------------------- 待更新（hash 目录改名）

    // 改名结果类型在 core.model（[HashRenameResult]）：它只描述结果，
    // 「跳过（目标名已存在）不是失败」这条语义由类型本身表达，不依赖 ViewModel。

    /** 「待更新」区在更新期间置 true：按钮置灰防重复点。 */
    private val _isUpdatingOutdated = MutableStateFlow(false)
    val isUpdatingOutdated: StateFlow<Boolean> = _isUpdatingOutdated.asStateFlow()

    /** 改名任务的防并发标志：单点与一键共用，同一时刻只允许一个跑。只在主线程读写。 */
    private var outdatedUpdateBusy = false

    /**
     * 单点更新：把一条「待更新」产物的内层 hash 目录改名成当前 catalog 的哈希。
     *
     * 成功后就地更新这一条（改成新 hash 名、清掉待更新标记），它会自动从「待更新」区
     * 回到正常分组 —— 不必为一条重扫整个源目录（重扫会让列表闪一下、还白跑一遍 python）。
     */
    fun updateOutdatedMod(context: Context, mod: ModInfo) {
        val newHash = mod.outdatedCurrentHash?.takeIf { it.isNotBlank() } ?: return
        if (outdatedUpdateBusy) return
        outdatedUpdateBusy = true
        _isUpdatingOutdated.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val result = try {
                renameOutdatedHashDir(context, mod, newHash)
            } catch (e: Exception) {
                // 兜底：改名本身已逐段 try，但别让任何意外把 busy 卡住
                Log.w("MainViewModel", "更新意外失败: ${mod.uri}", e)
                HashRenameResult.Failed(e.message ?: e.javaClass.simpleName)
            }
            withContext(Dispatchers.Main) {
                outdatedUpdateBusy = false
                _isUpdatingOutdated.value = false
                when (result) {
                    HashRenameResult.Renamed -> {
                        _modsList.value = _modsList.value.map { m ->
                            if (m.uri == mod.uri) {
                                m.copy(
                                    convertedHashDir = newHash,
                                    outdatedCurrentHash = null,
                                    // 旧的 __data document uri 指向改名前的路径，
                                    // 已经作废；置空等下次扫描重新发现（文件头校验
                                    // 拿到 null 只会跳过，不会误判成损坏）
                                    convertedDataUri = null
                                )
                            } else m
                        }
                        toast(context, "已更新「${mod.name}」，现在可以正常装入了")
                    }
                    HashRenameResult.TargetExists ->
                        toast(context, "「${mod.name}」已有同版本目录，未改动")
                    HashRenameResult.GameBehindCatalog ->
                        toast(context, "「${mod.name}」暂不能更新：游戏资源还是旧版本，请先打开游戏更新资源")
                    is HashRenameResult.Failed ->
                        toast(context, "更新「${mod.name}」失败：${result.reason}")
                }
            }
        }
    }

    /**
     * 一键更新：把所有「待更新」条目逐个改名。
     *
     * 跑完统一重扫一次 —— 批量改名动的是磁盘上的目录名，列表得从源目录重新读一遍
     * （复用已有的扫描刷新机制，成功的条目自动回归正常分组）。
     */
    fun updateAllOutdatedMods(context: Context) {
        if (outdatedUpdateBusy) return
        val targets = _modsList.value.filter {
            it.kind == ModKind.CONVERTED_BUNDLE && it.defect == null &&
                    it.outdatedCurrentHash != null
        }
        if (targets.isEmpty()) return
        outdatedUpdateBusy = true
        _isUpdatingOutdated.value = true
        viewModelScope.launch(Dispatchers.IO) {
            var updated = 0
            var skipped = 0
            var gameBehind = 0
            val failures = ArrayList<String>()
            try {
                for (mod in targets) {
                    val newHash = mod.outdatedCurrentHash?.takeIf { it.isNotBlank() } ?: continue
                    when (val r = renameOutdatedHashDir(context, mod, newHash)) {
                        HashRenameResult.Renamed -> updated++
                        HashRenameResult.TargetExists -> skipped++
                        HashRenameResult.GameBehindCatalog -> gameBehind++
                        is HashRenameResult.Failed -> {
                            failures += "${mod.name}：${r.reason}"
                            Log.w("MainViewModel", "更新失败：${mod.name} —— ${r.reason}")
                        }
                    }
                }
            } catch (e: Exception) {
                // 兜底同上：一条出意外不该让整批无声中断、busy 卡死
                Log.w("MainViewModel", "一键更新中断", e)
                failures += "意外中断：${e.message ?: e.javaClass.simpleName}"
            }
            withContext(Dispatchers.Main) {
                outdatedUpdateBusy = false
                _isUpdatingOutdated.value = false
                toast(context, buildUpdateSummary(updated, skipped, gameBehind, failures))
                if (updated > 0) rescanAllModSources()
            }
        }
    }

    /** 一键更新的结果文案。失败逐条进日志，Toast 里只带一条时的具体原因（多了放不下）。 */
    private fun buildUpdateSummary(
        updated: Int,
        skipped: Int,
        gameBehind: Int,
        failures: List<String>
    ): String {
        val parts = ArrayList<String>(4)
        if (updated > 0) parts += "已更新 $updated 个"
        if (skipped > 0) parts += "跳过 $skipped 个（已有同版本目录）"
        if (gameBehind > 0) {
            // 游戏目录还停在旧哈希上：现在改名游戏不会读，必须先把游戏资源更新到
            // catalog 这一版（打开游戏让它下载）再来更新，所以这里要说清先后顺序
            parts += "$gameBehind 个暂不能更新：先打开游戏更新资源后再更新 mod"
        }
        if (failures.isNotEmpty()) {
            parts += if (failures.size == 1) "1 个失败：${failures[0]}" else "${failures.size} 个失败"
        }
        return if (parts.isEmpty()) "没有需要更新的 mod" else parts.joinToString("，")
    }

    /**
     * 把一个产物的内层 hash 目录改名成 [newHash]。返回结果而不是抛异常，调用方按结果
     * 分别计数/提示。
     *
     * 为什么改名就够：游戏更新时 bundle 的内容与 hex 目录名都没变，变的只是 catalog 里
     * 的内容哈希（Unity 按哈希命名缓存目录），实测内容逐字节相同 —— 所以把旧哈希目录
     * 原地改名即可，零下载、零拷贝。
     *
     * SAF 层级（见 ModRepository.discoverMods）：条目 uri 指向 **bundle 那一层**
     * （hex 名目录），hash 目录是它的直接子目录，结构是 `<bundle>/<hash>/__data`。
     * 导航刻意不用 DocumentFile：条目 uri 是 document uri（buildDocumentUriUsingTree），
     * `fromSingleUri` 拿到的 SingleDocumentFile 的 listFiles/renameTo 都会抛
     * UnsupportedOperationException（拿不到子项、改不了名）。所以直接走 DocumentsContract ——
     * 与 ModRepository 遍历目录用的是同一套 API，深浅层级都正确。
     *（注：`fromTreeUri` 传 document uri 时其实解析到的是该条目本身，不是树根 ——
     * 1.0.1 的字节码里有一条 isDocumentUri 分支会改用 getDocumentId；但那条路要先反查
     * provider，不如直接用 DocumentsContract 明确。）
     *
     * 动手前还有一道**游戏目录校验**（[gameBundleHashes]）：游戏资源还停在旧哈希上时
     * 直接返回 [HashRenameResult.GameBehindCatalog] 不碰文件 —— 那种状态改名等于让 mod
     * 静默失效。详见函数内注释。
     */
    private fun renameOutdatedHashDir(
        context: Context,
        mod: ModInfo,
        newHash: String
    ): HashRenameResult {
        val oldHash = mod.convertedHashDir?.takeIf { it.isNotBlank() }
            ?: return HashRenameResult.Failed("读不到产物的 hash 目录名")
        if (oldHash == newHash) return HashRenameResult.Renamed   // 已经是对的了

        val bundleName = mod.targetHash?.takeIf { it.isNotBlank() }
            ?: return HashRenameResult.Failed("读不到产物的目标 bundle 名")

        // —— 游戏目录校验：游戏资源还落后于 catalog 时不许改 ——
        // gameBundleHashes 是干净检测（refreshBundleCleanStates）从游戏目录读到的
        // 「bundle 名 -> 实际 hash 目录名」，只在 Shizuku 可用时有内容。
        // 表里没有这个 bundle → 放行。两种情形都安全：游戏还没下载过它（预放在当前
        // 哈希位，游戏下次下载正好命中），或整表为空（Shizuku 没开、无从校验）。
        // 表里有、但不是 catalog 这一版 → 用户还没进游戏更新资源，此刻改名游戏根本
        // 不会读，mod 静默失效 —— 宁可不改，让用户先把游戏资源更新到这一版。
        val gameHash = gameBundleHashes[bundleName]
        if (gameHash != null && gameHash != newHash) return HashRenameResult.GameBehindCatalog

        val resolver = context.contentResolver
        val bundleDocId = try {
            DocumentsContract.getDocumentId(mod.uri)
        } catch (e: Exception) {
            Log.w("MainViewModel", "读不到产物目录的 document id: ${mod.uri}", e)
            null
        } ?: return HashRenameResult.Failed("读不到产物目录的标识")

        // bundle 目录的直接子项：找旧 hash 目录，并顺带看新名字是否已被占用
        var oldDirDocId: String? = null
        var targetNameTaken = false
        try {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(mod.uri, bundleDocId)
            resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null, null, null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(idCol) ?: continue
                    val name = cursor.getString(nameCol) ?: continue
                    val isDir = mimeCol >= 0 &&
                            cursor.getString(mimeCol) == DocumentsContract.Document.MIME_TYPE_DIR
                    if (!isDir) continue
                    if (name == oldHash) oldDirDocId = docId
                    if (name == newHash) targetNameTaken = true
                }
            }
        } catch (e: Exception) {
            Log.w("MainViewModel", "列出产物目录失败: ${mod.uri}", e)
            return HashRenameResult.Failed("读不到产物目录的内容")
        }
        val oldDirUri = oldDirDocId
            ?: return HashRenameResult.Failed("找不到 hash 目录 $oldHash")
        // 目标名已存在：同一个 bundle 目录里已经躺着一份对齐到新哈希的产物（更新前后
        // 两份堆在一起，或上次改名只成功了一半）。撞名硬改会失败，也可能留下半截状态 ——
        // 跳过，让用户去文件管理器自己收拾。
        if (targetNameTaken) return HashRenameResult.TargetExists

        val renamedUri = try {
            DocumentsContract.renameDocument(
                resolver,
                DocumentsContract.buildDocumentUriUsingTree(mod.uri, oldDirUri),
                newHash
            )
        } catch (e: Exception) {
            Log.w("MainViewModel", "hash 目录改名失败: ${mod.uri}", e)
            null
        }
        if (renamedUri == null) return HashRenameResult.Failed("改名失败，可能没有该目录的写入权限")

        // 预览缓存的键就是 hash 目录名，旧名下那份再也对不上（内容没变，但键变了），
        // 留着只会白占空间，直到用户手动清缓存。size 段拿产物的（改名不动内容，
        // size 不变）；拿不到就退无后缀槽（size<=0 的兼容路径）。
        if (true) {
            try {
                previewCacheRepository.delete(bundleName, oldHash, mod.convertedDataSize)
            } catch (e: Exception) {
                Log.w("MainViewModel", "清理旧哈希的预览缓存失败", e)
            }
        }
        Log.d("MainViewModel", "待更新治愈：$oldHash -> $newHash（${mod.name}）")
        return HashRenameResult.Renamed
    }

    /** Toast 统一走 application context：更新可能跑过界面销毁那一刻。 */
    private fun toast(context: Context, text: String) {
        Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG).show()
    }

    /**
     * 给每个 mod 标上装入状态：干净检测的客观结果 × 装入记账。
     *
     * 用当前已有的 [bundleCleanStates]，不自己触发检测 —— 刷新时机由调用方掌握
     * （扫描后、装入后各刷一次，避免同一次操作里重复遍历游戏目录）。
     *
     * 记账里有 + bundle 已被修改 → 生效中
     * 记账里有 + bundle 是干净原版/版本不符 → 需重新应用（被还原过，或游戏更新了）
     * 记账里没有 + bundle 已被修改 → 被其他工具改过（装入前会先取回官方原版做基底）
     */
    private fun applyInstallState(mods: List<ModInfo>): List<ModInfo> {
        val states = bundleCleanStates
        val records = if (true) {
            installedModRepository.load()
        } else {
            emptyMap()
        }

        if (states.isEmpty()) {
            // 无法校验：记账里有的标 UNVERIFIED，其余保持未装
            return mods.map { mod ->
                val recorded = records.containsKey(mod.uri.toString())
                mod.copy(installState = if (recorded) ModInstallState.UNVERIFIED
                                        else ModInstallState.NOT_INSTALLED)
            }
        }

        return mods.map { mod ->
            val targetHash = mod.targetHash
            // 账本按 mod uri 索引，直接命中即可。以前是拿 mod 自己的 familyKey 去查
            // 「一包一条」的记录，同包第二个 mod 必然查不到，于是被误判成
            // MODIFIED_BY_OTHER（界面上显示「被其他工具改过」）。
            val recorded = records.containsKey(mod.uri.toString())
            val clean = targetHash?.let { states[it] } ?: BundleCleanState.UNKNOWN

            val state = when {
                recorded && clean == BundleCleanState.MODIFIED -> ModInstallState.INSTALLED
                recorded -> ModInstallState.STALE
                clean == BundleCleanState.MODIFIED -> ModInstallState.MODIFIED_BY_OTHER
                clean == BundleCleanState.UNKNOWN -> ModInstallState.UNVERIFIED
                else -> ModInstallState.NOT_INSTALLED
            }
            mod.copy(installState = state)
        }
    }

    /**
     * 把一个产物文件写进公共 Download 目录（MediaStore）。
     *
     * 同名覆盖的完整策略：先查已有条目直接覆写 → 写失败则删掉重建 →
     * 插入新条目 → 并发插入撞 UNIQUE 约束时退回复查（另一个写入者已建好）。
     * 全失败返回 null，由调用方按「保存失败」处理。
     */
    private fun saveFileToDownloads(context: Context, file: File, relativeDestPath: String, rootDir: String): Uri? {
        val resolver = context.contentResolver
        val finalRelativePath = File(rootDir, relativeDestPath)
        val relativeDir = File(Environment.DIRECTORY_DOWNLOADS, finalRelativePath.parent).path + File.separator

        val queryUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(relativeDir, finalRelativePath.name)

        fun findExistingUri(): Uri? {
            resolver.query(queryUri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idColumn = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                    if (idColumn != -1) {
                        return ContentUris.withAppendedId(queryUri, cursor.getLong(idColumn))
                    }
                }
            }
            return null
        }

        fun writeToUri(targetUri: Uri): Boolean = try {
            resolver.openOutputStream(targetUri, "wt")?.use { output ->
                file.inputStream().use { it.copyTo(output) }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }

        // 覆写已有条目；写坏了就删掉走重建
        findExistingUri()?.let { existing ->
            if (writeToUri(existing)) return existing
            runCatching { resolver.delete(existing, null, null) }
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, finalRelativePath.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
        }
        try {
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                if (writeToUri(uri)) return uri
                runCatching { resolver.delete(uri, null, null) }
            }
        } catch (e: Exception) {
            // 并发插入的 UNIQUE 冲突 —— 对方多半已建好条目，退回复查覆写
            Log.w("MainViewModel", "MediaStore insert conflict, falling back to overwrite: ${e.message}")
            findExistingUri()?.let { existing ->
                if (writeToUri(existing)) return existing
            }
        }
        return null
    }

    private fun copyDirectoryToCache(context: Context, sourceDir: DocumentFile, destinationDir: File) {
        if (!destinationDir.exists()) destinationDir.mkdirs()
        sourceDir.listFiles().forEach { file ->
            val fileName = file.name ?: return@forEach
            if (file.isFile && !shouldIgnoreModEntry(fileName)) {
                val destFile = File(destinationDir, fileName)
                try {
                    context.contentResolver.openInputStream(file.uri)?.use { input -> destFile.outputStream().use { output -> input.copyTo(output) } }
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }

    private val _previewState = MutableStateFlow<PreviewState>(PreviewState.Idle)
    val previewState: StateFlow<PreviewState> = _previewState.asStateFlow()

    fun dismissPreviewState() {
        _previewState.value = PreviewState.Idle
    }

    /**
     * 把已转换产物解包成可预览的 Spine 素材。
     *
     * 产物里只有二进制 `__data`，得先解包才能拿到 skel/atlas/png。流程：
     * 1. 从 SAF 把 `<bundle>/<hash>/__data` 拷进 cache（Shizuku 不参与，产物在用户目录下）
     * 2. 交给 Python 的 unpack_bundle 解包（贴图走 libastcenc 解码，x86_64 也有）
     * 3. 在解包结果里找 skel + atlas
     *
     * 解包器只在**同名冲突**时才会给文件加 ` #<path_id>` 后缀，而单个 bundle 内不会有同名
     * 资源，所以导出的贴图名与 atlas 里的引用天然对得上，不需要额外改名。
     *
     * @return skel 与 atlas 的路径；任一缺失则返回 null
     */
    private suspend fun unpackConvertedForPreview(
        context: Context,
        modInfo: ModInfo,
        tempDir: File
    ): PreviewAssets? {
        val hashDir = modInfo.convertedHashDir ?: return null
        val srcSize = modInfo.convertedDataSize ?: -1L
        val bundleName = modInfo.targetHash ?: return null

        // 命中缓存就整段跳过解包 —— 这正是「批量预解包」的收益所在：
        // 预热过的 mod 再看只是读文件，不用再等那几秒。
        if (srcSize > 0 && previewCacheRepository.isValid(bundleName, hashDir, srcSize)) {
            previewCacheRepository.resolvePair(bundleName, hashDir, srcSize)?.let { cached ->
                Log.d("MainViewModel", "预览命中缓存: $bundleName")
                // 不能把缓存目录直接交给 SpinePreviewActivity —— 它 onDestroy 里会
                // deleteRecursively 传入的目录，那样看一次就把缓存删了。拷副本给它删。
                previewCacheRepository.entryDir(bundleName, hashDir, srcSize).listFiles()
                    ?.filter { it.isFile && !it.name.startsWith(".") }
                    ?.forEach { it.copyTo(File(tempDir, it.name), overwrite = true) }
                return PreviewAssets(
                    File(tempDir, File(cached.first).name).absolutePath,
                    File(tempDir, File(cached.second).name).absolutePath,
                    tempDir.listFiles()?.filter { it.name.endsWith(".png") }
                        ?.map { it.absolutePath }.orEmpty()
                )
            }
        }

        val dataDoc = DocumentFile.fromTreeUri(context, modInfo.uri)
            ?.findFile(hashDir)?.findFile("__data")
            ?: return null

        _previewState.value = PreviewState.Preparing("正在读取产物…")
        val bundleFile = File(tempDir, "__data")
        context.contentResolver.openInputStream(dataDoc.uri)?.use { input ->
            bundleFile.outputStream().use { input.copyTo(it) }
        } ?: return null

        PythonRuntime.ensureStarted(context)
        // 直接解到缓存位置，省掉「解到临时目录再整体拷进缓存」那一趟
        val outDir = previewCacheRepository.prepareDir(bundleName, hashDir, srcSize)
        val (ok, msg) = modEngine.unpackBundle(
            bundleFile.absolutePath, outDir.absolutePath, fast = true
        ) { p ->
            _previewState.value = PreviewState.Preparing(p)
            false   // 预览路径不提供中途取消
        }
        bundleFile.delete()
        if (!ok) {
            previewCacheRepository.delete(bundleName, hashDir, srcSize)   // 不留半成品当缓存
            _previewState.value = PreviewState.Failed("解包失败：$msg")
            return null
        }

        val files = outDir.listFiles()?.toList().orEmpty()
        val skel = files.firstOrNull { it.name.endsWith(".skel", true) }
            ?: files.firstOrNull { it.name.endsWith(".json", true) }
        val atlas = files.firstOrNull { it.name.endsWith(".atlas", true) }
        if (skel == null || atlas == null) {
            // 有图没骨架：不算失败——静态查看兜底（预解包侧同样只认骨架，
            // 这里至少让用户当场看到贴图）
            val pngs = files.filter { it.name.endsWith(".png") }
                .map { it.absolutePath }
            if (pngs.isNotEmpty()) {
                return PreviewAssets(null, null, pngs)
            }
            previewCacheRepository.delete(bundleName, hashDir, srcSize)
            _previewState.value = PreviewState.Failed(
                "这个 bundle 里没有可预览的素材（解出 ${files.size} 个文件）"
            )
            return null
        }
        if (srcSize > 0) previewCacheRepository.commit(bundleName, hashDir, srcSize)

        files.filter { it.isFile && !it.name.startsWith(".") }
            .forEach { it.copyTo(File(tempDir, it.name), overwrite = true) }
        return PreviewAssets(
            File(tempDir, skel.name).absolutePath,
            File(tempDir, atlas.name).absolutePath,
            files.filter { it.name.endsWith(".png") }.map { it.absolutePath }
        )
    }

    /**
     * 装入单个 mod —— 按角色浏览的界面用这个入口。
     *
     * 内部自动判断走哪条路：已有产物就直接放进游戏目录（不必重打包），
     * 否则走转换流程。用户不该为了装一个 mod 先自己跨过「转换」这道门。
     *
     * 两条路都会把同包已装的其他 mod 一并处理：转换那条靠 [buildRepackJobs]，
     * 产物那条没法合并，所以 [installConvertedBundles] 会先弹预警。
     */
    fun installSingle(context: Context, converted: ModInfo?, source: ModInfo?) {
        if (converted != null) {
            if (converted.defect != null || converted.outdatedCurrentHash != null) {
                Log.w("MainViewModel", "装入被拦截：产物 ${converted.name} 异常（${converted.defect}）" +
                        "或待更新（hash=${converted.outdatedCurrentHash}）")
                return
            }
            installConvertedBundles(context, listOf(converted))
            return
        }
        val mod = source ?: return
        val hash = mod.targetHash
        if (hash.isNullOrBlank() || mod.resolutionState != ResolutionState.KNOWN || mod.defect != null) {
            Log.w("MainViewModel", "装入被跳过：${mod.name} 还没解析出目标 bundle")
            return
        }
        _moveState.value = MoveState.Idle
        batchStartTimeMs = System.currentTimeMillis()
        val jobs = buildRepackJobs(listOf(mod))
        if (jobs.isEmpty()) {
            Log.w("MainViewModel", "装入被跳过：${mod.name} 没能构成装入计划")
            return
        }
        _installJobs.value = jobs.map { InstallJob(it) }
        _finalInstallResult.value = null
        _showInstallDialog.value = true
        processInstallJobs(context)
    }

    /**
     * 长按预览入口：把 mod 的素材收集到临时目录，找到 skel+atlas 后拉起
     * SpinePreviewActivity。三类来源（产物 / 目录 mod / zip mod）统一收进
     * [collectPreviewFiles]，完成后走同一套启动逻辑。
     */
    fun prepareAndShowPreview(context: Context, modInfo: ModInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            val tempDir = File(context.cacheDir, "spine_preview_${System.currentTimeMillis()}")
            if (!tempDir.mkdirs()) return@launch

            try {
                val preview = collectPreviewFiles(context, modInfo, tempDir)
                if (preview.skelPath != null && preview.atlasPath != null) {
                    withContext(Dispatchers.Main) {
                        context.startActivity(Intent(context, SpinePreviewActivity::class.java).apply {
                            putExtra("skelPath", preview.skelPath)
                            putExtra("atlasPath", preview.atlasPath)
                            putExtra("tempDirPath", tempDir.absolutePath)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        })
                    }
                    _previewState.value = PreviewState.Idle
                } else if (preview.pngPaths.isNotEmpty()) {
                    // 没骨架的贴图型 mod（立绘/壁纸等）：spine 放不了动画，
                    // 至少把图亮出来看看 —— 用户「哪怕不是动画」的要求。
                    // 路径所有权交给查看器：静态查看不经过 SpinePreviewActivity
                    // 的 onDestroy 清理，这里由查看器关闭时删 tempDir。
                    withContext(Dispatchers.Main) {
                        _staticPreviewImages.value = preview.pngPaths
                        _staticPreviewTempDir.value = tempDir.absolutePath
                    }
                    _previewState.value = PreviewState.Idle
                } else {
                    // 一点素材都没有要明说 —— 旧版这里是空分支，长按毫无反应，
                    // 用户分不清是不支持还是坏了
                    _previewState.value = PreviewState.Failed(
                        "没找到可预览的素材（动画需要 .skel/.json + .atlas；静态至少要有一张 .png）"
                    )
                    tempDir.deleteRecursively()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _previewState.value = PreviewState.Failed(
                    "预览准备失败：${e.message ?: e::class.java.simpleName}"
                )
                tempDir.deleteRecursively()
            }
        }
    }

    /**
     * 把 mod 素材收进临时目录，返回 (skel 路径, atlas 路径)。三类来源：
     * 产物（先解包）、目录 mod（逐文件拷）、zip mod（逐条解压）。
     */
    private suspend fun collectPreviewFiles(
        context: Context,
        modInfo: ModInfo,
        tempDir: File
    ): PreviewAssets {
        if (modInfo.kind == ModKind.CONVERTED_BUNDLE) {
            return unpackConvertedForPreview(context, modInfo, tempDir)
                ?: run {
                    if (_previewState.value is PreviewState.Preparing) {
                        _previewState.value = PreviewState.Failed("无法从产物中取出可预览的素材")
                    }
                    tempDir.deleteRecursively()
                    PreviewAssets(null, null, emptyList())
                }
        }

        var skel: String? = null
        var atlas: String? = null
        val pngs = mutableListOf<String>()
        fun onExtracted(fileName: String, dest: File) {
            when {
                fileName.endsWith(".skel", ignoreCase = true) -> skel = dest.absolutePath
                fileName.endsWith(".json", ignoreCase = true) -> {
                    // .skel 优先于 .json：避免包内的非骨骼元数据 json 把已找到的 .skel 覆盖掉
                    if (skel == null || !skel!!.endsWith(".skel", ignoreCase = true)) {
                        skel = dest.absolutePath
                    }
                }
                fileName.endsWith(".atlas", ignoreCase = true) -> atlas = dest.absolutePath
                fileName.endsWith(".png", ignoreCase = true) -> pngs.add(dest.absolutePath)
            }
        }

        val previewExts = setOf(".skel", ".json", ".atlas", ".png")
        if (modInfo.isDirectory) {
            DocumentFile.fromTreeUri(context, modInfo.uri)?.listFiles()?.forEach { file ->
                val fileName = file.name ?: ""
                if (!shouldIgnoreModEntry(fileName) &&
                    previewExts.any { fileName.endsWith(it, ignoreCase = true) }) {
                    val dest = File(tempDir, fileName)
                    context.contentResolver.openInputStream(file.uri)?.use { input ->
                        dest.outputStream().use { input.copyTo(it) }
                    }
                    onExtracted(fileName, dest)
                }
            }
        } else {
            context.contentResolver.openInputStream(modInfo.uri)?.use { fis ->
                ZipInputStream(fis).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val fileName = entry.name.replace('\\', '/').substringAfterLast('/')
                        if (!entry.isDirectory &&
                            fileName.isNotBlank() &&
                            fileName != "." &&
                            fileName != ".." &&
                            !shouldIgnoreModEntry(fileName) &&
                            previewExts.any { fileName.endsWith(it, ignoreCase = true) }) {
                            val dest = File(tempDir, fileName)
                            dest.outputStream().use { zis.copyTo(it) }
                            onExtracted(fileName, dest)
                        }
                        entry = zis.nextEntry
                    }
                }
            }
        }
        return PreviewAssets(skel, atlas, pngs)
    }

    // ------------------------------------------------ 检查更新

    /**
     * 发现的新版本。非 null = 有新版本可用，界面上挂更新弹窗。
     *
     * 只在手动点「检查更新」且确实有新版本时才有值 —— 没有启动时自动检查，
     * App 不会自己在后台打 GitHub。
     */
    private val _staticPreviewImages = MutableStateFlow<List<String>>(emptyList())
    val staticPreviewImages: StateFlow<List<String>> = _staticPreviewImages.asStateFlow()
    private val _staticPreviewTempDir = MutableStateFlow<String?>(null)


    /** 启动时清掉上次更新留下的安装包（装完就没用了，白占几十 MB）。 */
    fun cleanupUpdateApk() {
        viewModelScope.launch(Dispatchers.IO) {
            updateRepository.updateDir().listFiles()?.forEach { it.delete() }
        }
    }

    /** 关掉静态预览查看器，顺带清掉它的临时目录。 */
    fun dismissStaticPreview() {
        _staticPreviewImages.value = emptyList()
        _staticPreviewTempDir.value?.let { path ->
            viewModelScope.launch(Dispatchers.IO) {
                File(path).deleteRecursively()
            }
        }
        _staticPreviewTempDir.value = null
    }

    /**
     * **刻意不取消 [installScope]。**
     *
     * onCleared 意味着界面没了（用户退出、或系统回收 Activity），但正在跑的转换/装入
     * 不该跟着断 —— 重打包断在中途会在游戏目录留下半个 __data。那条流程的生命周期
     * 挂在进程上（[InstallService] 让进程活着），不挂在界面上。
     *
     * 代价是 ViewModel 对象会被跑着的协程多留一会儿，跑完自然释放。
     * 用户反馈的「装 mod 时放着不管容易被杀」正是这两件事一起解决的。
     */
    override fun onCleared() {
        val running = _installJobs.value.count {
            it.status !is JobStatus.Finished && it.status !is JobStatus.Failed
        }
        if (running > 0) {
            Log.i("MainViewModel", "界面已销毁，但还有 $running 个装入任务在跑，不取消")
        }
        super.onCleared()
    }

    /**
     * 核对并修复安装记录：只读拷出游戏 __data、按冻结 SHA 补账，不重新安装。
     */
    fun reconcilePendingInstalls() {
        // T19：核对全程需要 ShizukuManager.copyGameDataForRecovery（真正的 Binder 调用），
        // 自己持有一份占用，不依赖 MainActivity 的 token 是否还在。
        val recoveryConnectionToken = ShizukuManager.acquireConnection()
        installScope.launch {
            try {
            val result = withContext(Dispatchers.IO) {
                try {
                    val ctx = appContext ?: return@withContext RecoveryApplyResult.NotApplied(
                        OperationError(OperationErrorKind.INVALID_INPUT, "没有可用的 Context，无法核对。")
                    )
                    val destRoot = File(ctx.externalCacheDir ?: ctx.cacheDir, "recovery-obs")
                    try {
                        installedModRepository.recoverContent { targets ->
                            destRoot.mkdirs()
                            targets.map { ref ->
                                val target = ref.target
                                val destFile = try {
                                    RecoveryObservationLayout.destFile(
                                        destRoot = destRoot,
                                        operationId = ref.operationId,
                                        bundleName = target.bundleName,
                                        hashDir = target.hashDir
                                    )
                                } catch (e: IllegalArgumentException) {
                                    return@map TargetObservation.ReadFailed(
                                        operationId = ref.operationId,
                                        bundleName = target.bundleName,
                                        hashDir = target.hashDir,
                                        reason = "Path validation failed: " + e.message
                                    )
                                }
                                destFile.parentFile?.mkdirs()
                                val copied = try {
                                    ShizukuManager.copyGameDataForRecovery(
                                        target.bundleName, target.hashDir, destFile.absolutePath
                                    )
                                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                    throw cancelled
                                } catch (e: Exception) {
                                    false
                                }
                                if (!copied) {
                                    TargetObservation.ReadFailed(
                                        operationId = ref.operationId,
                                        bundleName = target.bundleName,
                                        hashDir = target.hashDir,
                                        reason = "copyGameDataForRecovery failed"
                                    )
                                } else {
                                    TargetObservations.observeFile(ref.operationId, target.bundleName, target.hashDir, destFile)
                                }
                            }
                        }
                    } finally {
                        runCatching { destRoot.deleteRecursively() }
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    RecoveryApplyResult.NotApplied(OperationError(
                        OperationErrorKind.IO, "核对失败：${failure.message.orEmpty()}"
                    ))
                }
            }
            when (result) {
                is RecoveryApplyResult.Applied -> {
                    _recoveryVerdict.value = runCatching { installedModRepository.checkRecovery() }
                        .getOrDefault(OperationJournalStore.RecoveryVerdict.Consistent)
                    _recoveryDispositions.value = runCatching { OperationRecovery(installedModRepository).classify() }
                        .getOrDefault(emptyList())
                    runCatching { refreshInstallStates() }
                    notifyLedgerNotice("已根据目标内容补齐安装记录。")
                }
                is RecoveryApplyResult.NotApplied -> notifyLedgerNotice(result.error.message)
            }
            } finally {
                ShizukuManager.releaseConnection(recoveryConnectionToken)
            }
        }
    }
}
