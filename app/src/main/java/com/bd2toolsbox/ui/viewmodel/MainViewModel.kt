package com.bd2toolsbox.ui.viewmodel

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chaquo.python.Python
import com.bd2toolsbox.SpinePreviewActivity
import com.bd2toolsbox.data.model.*
import com.bd2toolsbox.data.repository.AvatarRepository
import com.bd2toolsbox.data.repository.BundleBackupRepository
import com.bd2toolsbox.data.repository.BundleNameResolver
import com.bd2toolsbox.data.repository.CharacterMetaRepository
import com.bd2toolsbox.data.repository.CharacterRepository
import com.bd2toolsbox.data.repository.InstalledModRepository
import com.bd2toolsbox.data.repository.ModRepository
import com.bd2toolsbox.data.repository.PreviewCacheRepository
import com.bd2toolsbox.data.repository.SpineRuntimeRepository
import com.bd2toolsbox.data.repository.UpdateRepository
import com.bd2toolsbox.service.InstallService
import com.bd2toolsbox.service.ModdingService
import com.bd2toolsbox.service.PrepackService
import com.bd2toolsbox.service.ShizukuManager
import com.bd2toolsbox.ui.theme.AppTheme
import com.google.gson.ExclusionStrategy
import com.google.gson.FieldAttributes
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

class MainViewModel(private val savedStateHandle: SavedStateHandle) : ViewModel() {

    companion object {
        /** Shizuku 官方 app 的包名，用于「打开 Shizuku」按钮。 */
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

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
        const val MANUAL_INSTALL_COMMAND =
            "cp -rf /storage/emulated/0/Download/Shared/. " +
            "/storage/emulated/0/Android/data/com.neowizgames.game.browndust2/files/UnityCache/Shared/ " +
            "&& rm -rf /storage/emulated/0/Download/Shared"

        /** UnityFS bundle 文件头的魔数（"UnityFS" + 结尾 NUL，共 8 字节），产物 __data 的损坏判定用。
         *  用字节字面量而不是字符串：源码字符串里的裸 NUL 既看不见也容易被编辑工具吞掉。 */
        private val UNITYFS_MAGIC = byteArrayOf(0x55, 0x6E, 0x69, 0x74, 0x79, 0x46, 0x53, 0x00)

        /** 冷启动列表快照（见 [saveModListSnapshot]）：上次扫描落定的整张列表。 */
        private const val SNAPSHOT_FILENAME = "mod_list_snapshot.json"

        /**
         * 快照结构版本。改了 [ModInfo] 的字段含义就 +1 —— 老快照直接丢弃按「没有快照」走，
         * 比按缺字段的半截数据渲染强（那种数据会以 NPE 的形式在很远的地方炸）。
         */
        private const val SNAPSHOT_VERSION = 1
    }

    private fun shouldIgnoreModEntry(entryName: String?): Boolean {
        val name = entryName?.substringAfterLast('/')?.trim()?.lowercase() ?: return true
        return name.isEmpty() || name == ".modfile" || name.endsWith(".modfile")
    }

    private lateinit var characterRepository: CharacterRepository
    private lateinit var modRepository: ModRepository
    private lateinit var installedModRepository: InstalledModRepository
    private lateinit var backupRepository: BundleBackupRepository
    private lateinit var previewCacheRepository: PreviewCacheRepository

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

    private val _isUpdatingCharacters = MutableStateFlow(false)
    val isUpdatingCharacters: StateFlow<Boolean> = _isUpdatingCharacters.asStateFlow()

    val showShimmer: StateFlow<Boolean> =
        combine(_modsList, _isLoading, _isUpdatingCharacters) { mods, isScanning, isUpdating ->
            (isScanning || isUpdating) && mods.isEmpty()
        }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), false)

    private val _selectedMods = MutableStateFlow<Set<Uri>>(emptySet())
    val selectedMods: StateFlow<Set<Uri>> = _selectedMods.asStateFlow()

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
        appContext?.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            ?.edit()?.putString("app_theme", theme.name)?.apply()
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
        appContext?.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            ?.edit()?.putFloat("wallpaper_scrim", clamped)?.apply()
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
        ctx?.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            ?.edit()?.putString("wallpaper_uri", uri?.toString())?.apply()
    }

    /** 备份占用（字节数, 个数），供设置区显示。 */
    private val _backupUsage = MutableStateFlow(0L to 0)
    val backupUsage: StateFlow<Pair<Long, Int>> = _backupUsage.asStateFlow()
    val useAstc: StateFlow<Boolean> = _useAstc.asStateFlow()

    private val _selectedQuality = MutableStateFlow("HD")
    val selectedQuality: StateFlow<String> = _selectedQuality.asStateFlow()

    private val _isSearchActive = MutableStateFlow(false)
    val isSearchActive: StateFlow<Boolean> = _isSearchActive.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /** 状态筛选。null = 全部。上百个 mod 时靠它快速找出「当前生效的是哪些」。 */
    private val _stateFilter = MutableStateFlow<ModInstallState?>(null)
    val stateFilter: StateFlow<ModInstallState?> = _stateFilter.asStateFlow()

    fun setStateFilter(state: ModInstallState?) {
        _stateFilter.value = if (_stateFilter.value == state) null else state
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
    private var appContext: Context? = null

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

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext

        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        restoreSettings(prefs)
        restoreWallpaper(prefs)
        restoreModSourceDirs(prefs)
        watchPrepackCompletion()
        cleanStalePreviewDirs(context)
        initRepositories(context)
        migrateLegacyLedgerIfNeeded()
        // 冷启动先把上次扫描的结果填上（几毫秒的事），真正的扫描随后在后台跑完再整表替换 ——
        // 「全部」那一页因此不用再对着空列表等整个 SAF 扫描
        restoreModListSnapshot()

        startAsyncInitialization(context)
    }

    /** 读回全部用户设置项。 */
    private fun restoreSettings(prefs: android.content.SharedPreferences) {
        _useAstc.value = prefs.getBoolean("use_astc", false)
        _backupOriginals.value = prefs.getBoolean("backup_originals", true)
        _dynamicColor.value = prefs.getBoolean("dynamic_color", false)
        // 画质只认 SD/HD；历史版本存过 FHD（与 HD 同源的假档位），读回归一成 HD
        _selectedQuality.value = prefs.getString("selected_quality", "HD")
            ?.takeIf { it == "SD" || it == "HD" } ?: "HD"
        _appTheme.value = AppTheme.fromName(prefs.getString("app_theme", null))
        _onboardingDone.value = prefs.getBoolean("onboarding_done", false)
        _wallpaperScrim.value = prefs.getFloat("wallpaper_scrim", 0.82f)
    }

    /** 恢复壁纸，但只在持久读权限还在时 —— 权限被系统回收后那个 URI 读不出图，
     *  界面会是一片空白背景，不如退回纯色（顺手把死键清掉）。 */
    private fun restoreWallpaper(prefs: android.content.SharedPreferences) {
        prefs.getString("wallpaper_uri", null)?.let { saved ->
            try {
                val uri = Uri.parse(saved)
                val readable = appContext!!.contentResolver.persistedUriPermissions.any {
                    it.uri == uri && it.isReadPermission
                }
                if (readable) _wallpaperUri.value = uri
                else prefs.edit().remove("wallpaper_uri").apply()
            } catch (e: Exception) {
                prefs.edit().remove("wallpaper_uri").apply()
            }
        }
    }

    /** 恢复上次的 mod 文件夹们，避免每次启动都回到欢迎页。
     *  只保留持久权限还在的 —— 权限被回收的目录读不出任何东西，留在列表里
     *  只会让人以为 mod 丢了。v9.3 及以前存的是单目录键，这里一并迁移。 */
    private fun restoreModSourceDirs(prefs: android.content.SharedPreferences) {
        val saved = prefs.getString("mod_source_dirs", null)
        val legacy = prefs.getString("mod_source_dir_uri", null)
        val raw: List<String> = when {
            saved != null -> saved.split(DIR_SEPARATOR).filter { it.isNotBlank() }
            legacy != null -> listOf(legacy)
            else -> emptyList()
        }
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
        if (alive.size != raw.size || saved == null) {
            // 顺手清掉旧的单目录键，并落一次新格式
            prefs.edit().remove("mod_source_dir_uri").apply()
            persistModSourceDirs()
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

    private fun initRepositories(context: Context) {
        characterRepository = CharacterRepository(context)
        modRepository = ModRepository(context, characterRepository)
        installedModRepository = InstalledModRepository(context)
        backupRepository = BundleBackupRepository(context)
        previewCacheRepository = PreviewCacheRepository(context)
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
        installedModRepository.clear()
        _ledgerResetNotice.value =
            "装入记录的格式已升级（旧版把同一资源包里的多个 mod 记成一条，会导致卸载时" +
                "连带卸掉别的 mod）。旧记录已清空，游戏里的文件没有动。" +
                "重新装一次即可恢复状态显示。"
        Log.i("MainViewModel", "检测到旧格式账本（$n 条），已清空")
    }

    /**
     * 启动期的异步两步：
     *   1. 刷新角色表（顺带拉起 Python 运行时）
     *   2. Shizuku 在线时对照缓存检查游戏 bundle，要重扫就弹确认框
     *
     * 第 2 步必须排在第 1 步后：两边都要用 Python，而 Python.start() 不是线程安全的。
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

                if (ShizukuManager.isAvailable()) {
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
        installScope.launch {
            installJobs.collect { jobs ->
                if (jobs.isNotEmpty() &&
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
        return true
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
        val blob = _modSourceDirs.value.joinToString(DIR_SEPARATOR) { it.toString() }
        appContext?.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            ?.edit()?.putString("mod_source_dirs", blob)?.apply()
    }

    fun setUseAstc(useAstc: Boolean) {
        _useAstc.value = useAstc
        appContext?.getSharedPreferences("app_settings", Context.MODE_PRIVATE)?.edit()?.putBoolean("use_astc", useAstc)?.apply()
    }

    fun setBackupOriginals(enabled: Boolean) {
        _backupOriginals.value = enabled
        appContext?.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            ?.edit()?.putBoolean("backup_originals", enabled)?.apply()
    }

    fun setDynamicColor(enabled: Boolean) {
        _dynamicColor.value = enabled
        appContext?.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            ?.edit()?.putBoolean("dynamic_color", enabled)?.apply()
    }

    /** 重算备份占用（遍历备份目录，放 IO 线程）。 */
    fun refreshBackupUsage() {
        if (!::backupRepository.isInitialized) return
        viewModelScope.launch(Dispatchers.IO) {
            val usage = backupRepository.totalBytes() to backupRepository.count()
            _backupUsage.value = usage
        }
    }

    fun clearBackups() {
        if (!::backupRepository.isInitialized) return
        viewModelScope.launch(Dispatchers.IO) {
            backupRepository.clearAll()
            _backupUsage.value = 0L to 0
        }
    }

    // ------------------------------------------------ 批量预解包

    /** 批量预解包的评估结果，给确认框用。 */
    data class PrepackPlan(
        val total: Int,
        val alreadyCached: Int,
        val todo: Int,
        val estimatedBytes: Long,
        val freeBytes: Long,
        val ratioFromSamples: Boolean
    ) {
        /** 预估占用超过可用空间的九成就拦下 —— 塞满存储比多等一会儿糟得多。 */
        val enoughSpace: Boolean get() = estimatedBytes < freeBytes * 0.9
    }

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
            val meta = CharacterMetaRepository.get(ctx)
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
        val ctx = appContext ?: return
        val repo = AvatarRepository.get(ctx)
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
            val converted = _modsList.value.filter {
                it.kind == ModKind.CONVERTED_BUNDLE && it.convertedHashDir != null
            }
            var cached = 0
            var todoBytes = 0L
            for (m in converted) {
                val name = m.targetHash ?: continue
                val hash = m.convertedHashDir ?: continue
                val size = m.convertedDataSize ?: 0L
                if (size > 0 && previewCacheRepository.isValid(name, hash, size)) cached++
                else todoBytes += size
            }
            val measured = previewCacheRepository.measuredExpansionRatio()
            val ratio = measured ?: 1.6
            val free = try {
                (context.getExternalFilesDir(null) ?: context.filesDir).usableSpace
            } catch (e: Exception) {
                0L
            }
            _prepackPlan.value = PrepackPlan(
                total = converted.size,
                alreadyCached = cached,
                todo = converted.size - cached,
                estimatedBytes = (todoBytes * ratio).toLong(),
                freeBytes = free,
                ratioFromSamples = measured != null
            )
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
                val targets = _modsList.value.filter {
                    it.kind == ModKind.CONVERTED_BUNDLE && it.convertedHashDir != null
                }.mapNotNull { m ->
                    val n = m.targetHash ?: return@mapNotNull null
                    val h = m.convertedHashDir ?: return@mapNotNull null
                    val s = m.convertedDataSize
                    if (s > 0 && previewCacheRepository.isValid(n, h, s)) return@mapNotNull null
                    PrepackService.Target(
                        treeUri = m.uri.toString(),
                        bundleName = n,
                        hashDir = h,
                        size = s,
                        displayName = m.name
                    )
                }
                if (targets.isEmpty()) return@launch
                Log.d("MainViewModel", "交给前台服务预解包 ${targets.size} 个")
                PrepackService.start(context, targets)
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
        if (!::previewCacheRepository.isInitialized) return
        viewModelScope.launch(Dispatchers.IO) {
            _previewCacheUsage.value =
                previewCacheRepository.totalBytes() to previewCacheRepository.count()
        }
    }

    fun clearPreviewCache() {
        if (!::previewCacheRepository.isInitialized) return
        viewModelScope.launch(Dispatchers.IO) {
            previewCacheRepository.clearAll()
            _previewCacheUsage.value = 0L to 0
        }
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
        val ctx = appContext ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _spineRuntimeUsage.value = SpineRuntimeRepository.get(ctx).usage()
        }
    }

    fun clearSpineRuntime() {
        val ctx = appContext ?: return
        viewModelScope.launch(Dispatchers.IO) {
            SpineRuntimeRepository.get(ctx).clear()
            _spineRuntimeUsage.value = 0L
        }
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
        appContext?.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            ?.edit()?.putBoolean("onboarding_done", true)?.apply()
    }

    /** 设置里「重看使用引导」。只改内存状态，不动 prefs —— 看完照样算看过。 */
    fun replayOnboarding() {
        _onboardingDone.value = false
    }

    fun setSelectedQuality(quality: String) {
        _selectedQuality.value = quality
        appContext?.getSharedPreferences("app_settings", Context.MODE_PRIVATE)?.edit()?.putString("selected_quality", quality)?.apply()
    }

    // ------------------------------------------------ 一键卸载

    /** 一键卸载的预估结果，用于确认对话框。 */
    data class UninstallPlan(
        val bundles: List<String>,
        val fromBackup: Int,
        val needDownload: Int,
        val downloadBytes: Long,
        /**
         * 非空表示这次「压根没测出来」，而不是「没东西要卸」。
         * 对话框据此改成报错，不能再说「所有资源都是官方原版」。
         */
        val error: String? = null,
        /** [error] 的技术细节，对话框里收进「详细原因」折叠区。 */
        val detail: String? = null
    )

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
                    _uninstallPlan.value = UninstallPlan(
                        emptyList(), 0, 0, 0L, error = err, detail = cleanScanDetail
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

    /** 用户确认后执行启动期发现的 bundle 扫描（Phase 2），结束后收尾初始化。 */
    fun confirmBundleScan() {
        val context = appContext ?: return
        val checkResult = pendingCheckResult ?: return

        viewModelScope.launch {
            try {
                // Shizuku 服务跑在 shell UID，写不了 app 的内部 cacheDir（/data/data/…），
                // 扫描临时文件得放 externalCacheDir
                val shizukuCacheDir = (context.externalCacheDir ?: context.cacheDir).absolutePath

                val (success, scanned, failed) = withContext(Dispatchers.IO) {
                    ShizukuManager.executeBundleScan(
                        outputDir = context.filesDir.absolutePath,
                        cacheDir = shizukuCacheDir,
                        checkResult = checkResult
                    ) { currentIndex, total, bundleName, message ->
                        // 扫描跑在 IO 线程，状态更新回主线程
                        viewModelScope.launch(Dispatchers.Main) {
                            _bundleScanState.value = BundleScanState.Scanning(
                                currentIndex = currentIndex,
                                totalCount = total,
                                currentBundle = bundleName,
                                progressMessage = message
                            )
                        }
                    }
                }

                _bundleScanState.value = if (success) {
                    BundleScanState.Finished(
                        scannedCount = scanned,
                        failedCount = failed,
                        message = "扫描完成：成功 $scanned 个，失败 $failed 个。"
                    )
                } else {
                    BundleScanState.Failed("资源扫描失败。")
                }
            } catch (e: Exception) {
                _bundleScanState.value = BundleScanState.Failed(e.message ?: "Unknown error")
            } finally {
                pendingCheckResult = null
                // 扫描产出新索引，收尾初始化并触发一轮 mod 重扫
                finishInitialization()
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
    }

    fun dismissVersionMismatchWarning() {
        _showVersionMismatchWarning.value = false
    }

    fun setSearchActive(isActive: Boolean) {
        _isSearchActive.value = isActive
        if (!isActive) {
            _searchQuery.value = ""
        }
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
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
     * 把「本次要装的 mod」与「该 bundle 上已装的 mod」合并成一份重打包清单。
     *
     * 为什么必须合并：游戏把多个角色的资源打进同一个包（实测某个立绘包被 45 个角色共用，
     * 心契之约的 19 个角色更是全在一个包里）。而 [processSingleJob] 取基底时，只要发现
     * 包已被改过就会退回官方原版重打包 —— 那是有意的保守选择（避免在改过的包上反复叠加
     * 导致 Spine 贴图页累积、atlas 错乱）。两者相加的后果是：分两次装同一个包里的两个
     * mod，第二次会把第一次的改动抹掉。而按角色浏览的界面天然就是一次装一个。
     *
     * 所以基底仍然用干净原版，但把这个包上所有要保留的 mod 一起重新打包 ——
     * 不叠加、不丢失，且同一组 mod 无论装几次都得到同样的包。这也与卸载侧一致
     * （[removeSingleMod] 就是「摘掉一个，剩下的重新打包」）。
     *
     * 顺序：已装的排前、本次选中的排后。[processSingleJob] 是顺序把各 mod 的文件拷进同一
     * 个目录，后者覆盖同名文件 —— 于是「换掉同一资源的另一个版本」和「新增另一个角色」
     * 两种意图都自然正确。
     *
     * 只按 uri 去重，**不按 familyKey 去重**：familyKey 会剥掉 `_1` 后缀，拆成
     * Part A / Part B 的 mod（`cutscene_char004091` 与 `cutscene_char004091_1`，实为两个
     * 资源、需同时装）算出来是同一个 key，按它去重会弄坏这类 mod。
     */
    private fun mergeWithInstalled(targetHash: String, selected: List<ModInfo>): List<ModInfo> {
        if (!::installedModRepository.isInitialized) return selected
        val selectedUris = selected.map { it.uri.toString() }.toSet()
        val alreadyInstalled = installedModRepository.listByTargetHash(targetHash)
            .map { it.modUri }
            .filterNot { it in selectedUris }
        if (alreadyInstalled.isEmpty()) return selected

        val byUri = _modsList.value.associateBy { it.uri.toString() }
        val kept = alreadyInstalled.mapNotNull { uri ->
            byUri[uri] ?: run {
                // 源文件已不在（用户删了文件夹、或换了 mod 目录）。没法重打包，只能放弃它，
                // 但要说出来 —— 静默丢弃的结果是用户发现某个 mod 莫名失效却无从追查。
                Log.w("MainViewModel", "包 $targetHash 上已装的 $uri 源文件不在，本次重打包将不含它")
                null
            }
        }
        if (kept.isNotEmpty()) {
            Log.d("MainViewModel", "包 $targetHash 并入已装 ${kept.size} 个，本次选中 ${selected.size} 个")
        }
        return kept + selected
    }

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
        val jobs = mods
            .filter {
                !it.targetHash.isNullOrBlank() &&
                    it.resolutionState == ResolutionState.KNOWN &&
                    // 异常条目（下架/损坏/画质错配等）拦在装入入口：界面上本就选不到，
                    // 这里再兜一层，防选中集里混入校验前的旧 uri
                    it.defect == null &&
                    // 「待更新」同样拦：hash 目录名落后于当前 catalog，重打包出来
                    // 路径对不上，等于白跑一趟。它得先在待更新区改名治愈。
                    it.outdatedCurrentHash == null
            }
            .groupBy { it.targetHash!! }
            .map { (hash, group) -> RepackJob(hash, mergeWithInstalled(hash, group)) }

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
        installScope.launch {
            val total = _installJobs.value.size
            // 用 appContext 而不是传进来的 Activity context：这条流程刻意要活过界面
            // 销毁，而用已销毁的 Activity context 发 Intent 会失败 —— 通知就更新不了、
            // 也停不掉，恰好毁在这个功能最该起作用的场景上。
            val svcCtx = appContext ?: context
            InstallService.start(svcCtx, total)
            try {
                if (!Python.isStarted()) {
                    withContext(Dispatchers.IO) {
                        Python.start(com.chaquo.python.android.AndroidPlatform(context))
                    }
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
            } finally {
                InstallService.stop(svcCtx)
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
                val (ok, result) = ModdingService.downloadBundle(
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
                    withContext(Dispatchers.IO) {
                        backupRepository.saveBackup(hashedName, hashDir, originalDataCache!!)
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
                val (ok, message) = ModdingService.repackBundle(
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
                        val outFile = File(dest, entry.name)
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
        val (ok, msg) = ModdingService.restoreBundle(
            base.absolutePath, output.absolutePath
        ) { progress -> updateJobStatus(hashedName, JobStatus.Installing(progress)) }
        if (!ok) throw Exception("Restore failed: $msg")

        // 还原产物就是原版的游戏格式，顺手存成备份。两个好处：
        // 下次卸载零下载；而且 restore_bundle 用 lz4 重打包，字节数与官方 catalog
        // 有千分之几差异（实测 6,243,701 vs 6,238,441），干净检测把备份大小也当
        // 原版基准，卸载后状态才不会一直停在「被修改」。
        hashDirOf(relativePath)?.let { hashDir ->
            withContext(Dispatchers.IO) {
                backupRepository.saveBackup(hashedName, hashDir, output)
            }
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
     */
    private suspend fun backupPristineOriginal(bundleName: String) {
        if (!::backupRepository.isInitialized) return
        if (bundleCleanStates[bundleName] != BundleCleanState.PRISTINE) return
        val hashDir = gameBundleHashes[bundleName] ?: return
        if (backupRepository.hasBackup(bundleName, hashDir)) return

        val tmp = File(appContext?.cacheDir ?: return, "backup_stage/$bundleName/$hashDir/__data")
        tmp.parentFile?.mkdirs()
        try {
            val ok = ShizukuManager.copyLocalBundleToCache(bundleName, hashDir, tmp.absolutePath)
            if (ok && tmp.isFile && tmp.length() > 0) {
                backupRepository.saveBackup(bundleName, hashDir, tmp)
                Log.d("MainViewModel", "已备份原版 $bundleName (${tmp.length()} 字节)")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { tmp.delete() } catch (_: Exception) {}
        }
    }

    /**
     * 「装产物会覆盖同包已装 mod」的预警。
     *
     * 产物是已经打包好的完整 bundle，直接覆盖写进游戏目录，没有重打包环节，
     * 所以**无法**像 [mergeWithInstalled] 那样把同包已装的 mod 并进去 ——
     * 只能在动手前说清会损失什么。
     */
    data class ConvertedOverwritePlan(
        val mods: List<ModInfo>,
        /** bundle 名 -> 会被覆盖掉的已装 mod 名字 */
        val casualties: Map<String, List<String>>,
        /** 其中源文件仍在、可改走转换流程保住的 bundle 名 */
        val recoverable: Set<String>
    ) {
        val total: Int get() = casualties.values.sumOf { it.size }
    }

    private val _convertedOverwritePlan = MutableStateFlow<ConvertedOverwritePlan?>(null)
    val convertedOverwritePlan: StateFlow<ConvertedOverwritePlan?> = _convertedOverwritePlan.asStateFlow()

    fun dismissConvertedOverwritePlan() {
        _convertedOverwritePlan.value = null
    }

    /** 用户在预警框里点了「仍要装入」。 */
    fun confirmConvertedOverwrite(context: Context) {
        val plan = _convertedOverwritePlan.value ?: return
        _convertedOverwritePlan.value = null
        doInstallConvertedBundles(context, plan.mods)
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
        if (::installedModRepository.isInitialized) {
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
                    ConvertedOverwritePlan(targets, casualties, recoverable)
                return
            }
        }
        doInstallConvertedBundles(context, targets)
    }

    private fun doInstallConvertedBundles(context: Context, targets: List<ModInfo>) {
        if (targets.isEmpty()) return
        _moveState.value = MoveState.Idle
        batchStartTimeMs = System.currentTimeMillis()
        _installJobs.value = targets.map { InstallJob(RepackJob(it.targetHash!!, listOf(it))) }
        _finalInstallResult.value = null
        _showInstallDialog.value = true

        installScope.launch {
            // 同上：服务的生命周期挂在进程上，不能用 Activity context 去驱动
            val svcCtx = appContext ?: context
            InstallService.start(svcCtx, targets.size)
            try {
            var ok = 0
            val failed = mutableListOf<FailedJobInfo>()

            // 先刷一次干净检测：备份原版的前提是「游戏目录里那份现在还是原版」，
            // 拿启动时的旧结果判断会误备份已经装过 mod 的文件。一次目录遍历，代价很小。
            withContext(Dispatchers.IO) { refreshBundleCleanStates() }

            for (mod in targets) {
                val bundleName = mod.targetHash!!
                updateJobStatus(bundleName, JobStatus.Installing("准备装入 ${mod.name}"))

                // 装入前完整校验（外来产物）：扫描期只有头部快检，这里补完整加载
                // （块解压 + 对象表解析）——「头完好但内部坏」、装进游戏才在特定
                // 界面崩的文件在这一步拦下，游戏目录一个字节都不动。
                val verdict = withContext(Dispatchers.IO) { validateConvertedForInstall(context, mod) }
                if (verdict != null && !verdict.first) {
                    val msg = "校验未通过，已跳过：${verdict.second}"
                    failed.add(FailedJobInfo(bundleName, msg))
                    updateJobStatus(bundleName, JobStatus.Failed(msg, verdict.second))
                    continue
                }

                // 直接装入是覆盖式写游戏目录，写完原版就没了。所以先把原版留一份 ——
                // 这条路径不下载、不重打包，备份只能在这里做（转换流程那边是在拿到
                // 下载/复用的基底时顺手存的，两条路互不经过）。
                if (backupOriginals.value) {
                    withContext(Dispatchers.IO) { backupPristineOriginal(bundleName) }
                }

                val (success, message) = withContext(Dispatchers.IO) {
                    installOneConverted(context, mod, bundleName)
                }
                if (success) {
                    ok++
                    updateJobStatus(bundleName, JobStatus.Finished(bundleName))
                } else {
                    failed.add(FailedJobInfo(bundleName, message))
                    updateJobStatus(bundleName, JobStatus.Failed(message, message))
                }
            }

            if (ok > 0) {
                recordConvertedInstalls(targets.filter { m ->
                    failed.none { it.hashedName == m.targetHash }
                })
                withContext(Dispatchers.IO) { refreshBundleCleanStates() }
                refreshInstallStates()
            }

            // 必须先设 moveState、后设 finalInstallResult —— 这两个是各自独立的
            // StateFlow，中间会有一次重组。反过来的话那一帧是「finalResult 已非 null
            // 而 moveState 还是 Idle」，对话框据此走进 Idle 分支、弹出「一键装入游戏」；
            // 而这条路是直拷进游戏目录的，Download/Shared 里根本没东西，点了就报
            // 「未找到 Download/Shared 目录」，紧接着 Success 到达又跳成「已装入」。
            // 实测就是用户看到的那个现象。
            if (ok > 0) {
                _moveState.value = MoveState.Success("已装入 $ok 个，重启游戏后生效。")
            }

            _finalInstallResult.value = FinalInstallResult(
                successfulJobs = ok,
                failedJobs = failed.size,
                // 已经直接进游戏目录了，没有留在 Download/ 的东西需要再装一次
                command = null,
                elapsedTimeMs = System.currentTimeMillis() - batchStartTimeMs,
                failedJobDetails = failed,
                shizukuAvailable = ShizukuManager.isRunning(),
                alreadyInGame = true
            )
            } finally {
                InstallService.stop(svcCtx)
            }
        }
    }

    /**
     * 外来产物装入前的完整校验：SAF 里的 __data 先中转到 cacheDir（python 只认
     * 文件路径；cacheDir 是 app 私有目录、python 进程可读，不需要 shell 可见的
     * externalCacheDir），再调 validate_bundle 做头快检 + UnityPy 完整加载。
     *
     * 返回 null = 校验的先决条件没就绪（中转读不出来 / python 未启动）——校验是
     * 防崩溃的纵深防御，缺了它不该反过来拦住装入；放行，让后续真正装文件的
     * 步骤去报更准确的错。返回非 null 且 first=false 才是真校验失败（截断 /
     * 结构损坏），调用方据此中止这一个条目。
     */
    private fun validateConvertedForInstall(context: Context, mod: ModInfo): Pair<Boolean, String>? {
        val dataUri = mod.convertedDataUri ?: return null
        if (!Python.isStarted()) return null
        val dir = File(context.cacheDir, "install_validate/${mod.targetHash}")
        val data = File(dir, "__data")
        return try {
            dir.deleteRecursively()
            dir.mkdirs()
            context.contentResolver.openInputStream(Uri.parse(dataUri))?.use { ins ->
                data.outputStream().use { ins.copyTo(it) }
            } ?: return null
            val (ok, reason) = ModdingService.validateBundle(data.absolutePath)
            if (ok) Log.d("MainViewModel", "装入前校验通过: ${mod.name}")
            else Log.w("MainViewModel", "装入前校验未通过: ${mod.name} ($reason)")
            ok to reason
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            try { dir.deleteRecursively() } catch (_: Exception) {}
        }
    }

    /** 单个产物的装入：优先直接拷，推导不出真实路径或拷贝失败则走 externalCacheDir 中转。 */
    private suspend fun installOneConverted(
        context: Context,
        mod: ModInfo,
        bundleName: String
    ): Pair<Boolean, String> {
        val real = resolveRealPath(mod.uri)
        if (real != null) {
            val (ok, msg) = ShizukuManager.installConvertedBundle(real, bundleName)
            if (ok) {
                Log.d("MainViewModel", "直接装入（1 趟拷贝）: $bundleName <- $real")
                return true to msg
            }
            Log.w("MainViewModel", "直接装入失败，回退中转: $bundleName ($msg)")
        }

        // 中转：SAF 读 -> externalCacheDir -> Shizuku 拷。
        // 必须用 externalCacheDir 而非 cacheDir —— 后者在 app 私有目录里，shell 读不到。
        val staging = File(context.externalCacheDir ?: context.cacheDir, "converted_staging/$bundleName")
        return try {
            if (staging.exists()) staging.deleteRecursively()
            staging.mkdirs()
            val copied = copyTreeFromSaf(context, mod.uri, staging)
            if (!copied) return false to "从所选目录读取失败：$bundleName"
            val (ok, msg) = ShizukuManager.installConvertedBundle(staging.absolutePath, bundleName)
            if (ok) Log.d("MainViewModel", "中转装入（2 趟拷贝）: $bundleName")
            ok to msg
        } catch (e: Exception) {
            false to (e.message ?: "装入出错：$bundleName")
        } finally {
            try {
                if (staging.exists()) staging.deleteRecursively()
            } catch (_: Exception) {}
        }
    }

    /** 把 SAF 目录树整体复制到本地目录（产物只有 `<hash>/__data` 两层，深度很浅）。 */
    private fun copyTreeFromSaf(context: Context, treeUri: Uri, dest: File): Boolean {
        val doc = DocumentFile.fromTreeUri(context, treeUri) ?: return false
        if (!doc.isDirectory) return false
        return try {
            fun walk(src: DocumentFile, out: File) {
                out.mkdirs()
                src.listFiles().forEach { child ->
                    val name = child.name ?: return@forEach
                    if (child.isDirectory) {
                        walk(child, File(out, name))
                    } else {
                        context.contentResolver.openInputStream(child.uri)?.use { input ->
                            File(out, name).outputStream().use { input.copyTo(it) }
                        }
                    }
                }
            }
            walk(doc, dest)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /** 已转换产物的记账。familyKey 用反查到的资源名，与 PC mod 完全一致，从而复用同一套状态管理。 */
    private fun recordConvertedInstalls(mods: List<ModInfo>) {
        if (!::installedModRepository.isInitialized || mods.isEmpty()) return
        val now = System.currentTimeMillis()
        installedModRepository.putAll(
            mods.mapNotNull { mod ->
                val hash = mod.targetHash ?: return@mapNotNull null
                InstalledModRecord(
                    modUri = mod.uri.toString(),
                    modName = mod.name,
                    familyKey = mod.resolvedFamilyKey ?: hash,
                    snapshotTargetHash = hash,
                    quality = _selectedQuality.value,
                    usedAstc = _useAstc.value,
                    installedAt = now
                )
            }
        )
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
            var record: InstalledModRecord? = null
            try {
                // 账本先销账。源文件都没了，这条记录留着只会让状态推导继续把它算成「生效中」，
                // 而重打包时又找不到文件。注意这里只销账，不动游戏里的实际内容 ——
                // 那需要重打包，属于「移除 mod」的动作，不是「删源文件」该做的事。
                // 失败要把它放回去，所以让 removeAndReturn 把记录交回来。
                record = withContext(Dispatchers.IO) { takeLedgerRecord(key) }
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
                    withContext(NonCancellable + Dispatchers.IO) { saveModListSnapshot(_modsList.value) }
                    onDone(true, "已从手机删除「${mod.name}」")
                } else {
                    // 失败/中断：墓碑必须摘掉，否则以后每次扫描都会把它挡在外面，
                    // 这个其实还在的 mod 就再也扫不回来了
                    deletingModUris.remove(key)
                    restoreDeletedMods(listOf(mod to index))
                    withContext(NonCancellable + Dispatchers.IO) { putLedgerRecord(record) }
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
                    val record = takeLedgerRecord(key)
                    val ok = deleteModSourceFile(context, mod)
                    if (ok) {
                        removed++
                        // 墓碑留着（见 deletingModUris 的说明），下一次扫描收尾统一清
                        deleted += key
                    } else {
                        failed += mod
                        deletingModUris.remove(key)     // 没删成 → 别再挡着以后的重扫
                        putLedgerRecord(record)         // 账本同理，原样放回
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
                withContext(NonCancellable + Dispatchers.Main) {
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
                }
                // 列表变了，快照跟着刷新，免得下次冷启动又把这些条目显出来
                withContext(NonCancellable + Dispatchers.IO) { saveModListSnapshot(_modsList.value) }
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

    /** 从账本里销掉一条并把它交回来（删除失败要原样放回）。跑在 IO 上。
     *  读改写是仓库里一次上锁的 [InstalledModRepository.removeAndReturn] —— 分开调
     *  load()+remove() 会让并发的另一次写入从中间插进来。 */
    private fun takeLedgerRecord(modUri: String): InstalledModRecord? {
        if (!::installedModRepository.isInitialized) return null
        return try {
            installedModRepository.removeAndReturn(modUri)
        } catch (e: Exception) {
            Log.w("MainViewModel", "销账失败: $modUri", e)
            null
        }
    }

    /** 把销掉的账放回去（删除失败时）。跑在 IO 上。 */
    private fun putLedgerRecord(record: InstalledModRecord?) {
        if (record == null || !::installedModRepository.isInitialized) return
        try {
            installedModRepository.put(record)
        } catch (e: Exception) {
            Log.w("MainViewModel", "账本复原失败: ${record.modUri}", e)
        }
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
            shizukuAvailable = ShizukuManager.isRunning()
        )

        if (successful.isNotEmpty() && ShizukuManager.isRunning()) {
            moveFilesToGame()
        }
    }

    @Synchronized
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

    fun moveFilesToGame() {
        // 跟着装入流程走 installScope：它现在是「转换完自动移入」的收尾一步，
        // 界面销毁时若被取消，产物就停在 Download/Shared 里没进游戏 —— 用户以为装好了。
        installScope.launch {
            _moveState.value = MoveState.Moving
            val (success, message) = ShizukuManager.moveDownloadToGame()
            _moveState.value = if (success) {
                MoveState.Success(message)
            } else {
                MoveState.Failed(message)
            }
            if (success) {
                // 装完就把 command 清掉，「一键装入游戏」按钮从此不再出现。
                //
                // 这是必需的，不只是收拾门面：moveDownloadToGame 是「移动」语义 ——
                // 拷进游戏目录后会 deleteRecursively() 删掉 Download/Shared。而 command
                // 只是个手动命令的文本常量（转换成功就非 null），它被当成了「要不要显示
                // 装入按钮」的开关，却完全不反映「Download/Shared 里还有没有东西」。
                // 于是只要 _moveState 被别的操作重置回 Idle（installSingle 等就会这么做），
                // 按钮又冒出来，一点必然报「未找到 Download/Shared 目录」——
                // 用户看到的就是「已转换的再点导入到游戏说没在 dl 目录」。
                _finalInstallResult.value = _finalInstallResult.value?.copy(command = null)
                (_uninstallState.value as? UninstallState.Finished)?.let {
                    _uninstallState.value = it.copy(command = null)
                }

                // 一键卸载的装入完成 —— 记账里不该再留任何「生效中」，否则状态与游戏实际相反。
                // 必须早于 recordSuccessfulInstall()：卸载任务的 mod 列表是空的，本来不会写账，
                // 但残留的旧记录得在这里一并清掉。
                if (pendingRecordClear) {
                    if (::installedModRepository.isInitialized) {
                        withContext(Dispatchers.IO) { installedModRepository.clear() }
                    }
                    pendingRecordClear = false
                } else {
                    // 装入游戏成功才算「生效」，此时才记账 —— 转换只是把产物放到 Download/，
                    // 没进游戏目录的话 mod 并未生效，记了反而会谎报状态。
                    recordSuccessfulInstall()
                    pendingUninstallTargetHash?.let { installedModRepository.removeByTargetHash(it) }
                    pendingUninstallTargetHash = null
                }
                // 游戏目录已变，重新检测并刷新列表状态（顺带让状态徽章立刻更新）
                withContext(Dispatchers.IO) { refreshBundleCleanStates() }
                refreshInstallStates()
                refreshBackupUsage()
            }
        }
    }

    /** 把本批成功装入的 job 写进账本 —— 一个 mod 一条。 */
    private fun recordSuccessfulInstall() {
        if (!::installedModRepository.isInitialized) return
        val now = System.currentTimeMillis()
        val quality = _selectedQuality.value
        val astc = _useAstc.value

        val records = _installJobs.value
            .filter { it.status is JobStatus.Finished }
            .flatMap { installJob ->
                val hash = installJob.job.hashedName
                installJob.job.modsToInstall.map { mod ->
                    InstalledModRecord(
                        modUri = mod.uri.toString(),
                        modName = mod.name,
                        // familyKey 降为普通字段，只用于游戏更新后识别「需重新应用」。
                        // 早先它是主键、一个 bundle 只记一条，导致同包第二个 mod
                        // 既卸不掉也显示错状态（见 InstalledModRecord 的说明）。
                        familyKey = mod.resolvedFamilyKey ?: hash,
                        snapshotTargetHash = hash,
                        quality = quality,
                        usedAstc = astc,
                        installedAt = now
                    )
                }
            }
        installedModRepository.putAll(records)
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
        if (!::installedModRepository.isInitialized) return
        val targetHash = mod.targetHash ?: return

        // 先摘掉这一个，再看这个包上还剩谁。
        // 早先是 detachMod(familyKey, uri)，而账本按 familyKey「一包一条」记 ——
        // 卸同包第二个 mod 时按它自己的 familyKey 查不到记录，detachMod 返回空，
        // 就被当成「该包已无 mod」而整包还原，把同包其他 mod 一起卸了。
        installedModRepository.remove(mod.uri.toString())
        val remainingUris = installedModRepository.listByTargetHash(targetHash).map { it.modUri }

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
            installedModRepository.removeByTargetHash(targetHash)
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
                if (!Python.isStarted()) {
                    Python.start(com.chaquo.python.android.AndroidPlatform(context))
                }
                val tempInput = File(context.cacheDir, "temp_unpack_input.bundle")
                context.contentResolver.openInputStream(inputFile)?.use { input ->
                    tempInput.outputStream().use { input.copyTo(it) }
                }

                val tempOutput = File(context.cacheDir, "temp_unpack_output").apply {
                    if (exists()) deleteRecursively()
                    mkdirs()
                }

                val result = ModdingService.unpackBundle(
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
                    ModdingService.mergeSpineAssets(tempDir.absolutePath) { progress ->
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
    fun rescanAllModSources() {
        pendingScan = true
        if (scanJob?.isActive == true) return

        scanJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                if (!pendingScan) break
                pendingScan = false

                val dirs = _modSourceDirs.value
                if (dirs.isEmpty()) break

                withContext(Dispatchers.Main) {
                    _isLoading.value = true
                }

                isUpdatingCharacters.first { !it } // Wait for character data to be ready
                try {
                    val hidden = loadHidden()
                    // 按 uri 去重：目录嵌套（用户加了 A 又加了 A/sub）或同一 mod
                    // 出现在两处时，只留第一次扫到的那份。
                    val seen = HashSet<String>()
                    val mods = ArrayList<ModInfo>()
                    for (dir in dirs) {
                        val found = try {
                            modRepository.scanMods(dir)
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
                    }
                    Log.d("MainViewModel", "扫描 ${dirs.size} 个目录，合计 ${mods.size} 个 mod")
                    refreshBundleCleanStates()
                    // 四道检查在扫描链尾全量跑一遍（缓存命中的条目也重判）—— 每次扫描
                    // 即复查：游戏更新/画质切换后 catalog 变了，这遍重新分四类 ——
                    // 判出缺陷的进异常区、hash 目录名落后的进待更新区、看清画质后
                    // 依然有效的摘回正常分组（判定表见 validateMods）。
                    val validated = appContext?.let { validateMods(it, mods) } ?: mods
                    val withState = applyInstallState(validated)
                    // 正在删的条目不要被这轮扫描「复活」：一个目录 SAF 删要几秒，
                    // 这期间的重扫读到的还是删除前的状态，扫回来会让刚消失的行又跳出来
                    val visible = withState.filterNot { deletingModUris.contains(it.uri.toString()) }
                    withContext(Dispatchers.Main) {
                        _modsList.value = visible
                        _selectedMods.value = emptySet()
                        scanSettled = true
                        // 墓碑只挡「本轮扫描」这一个时间窗：结果已经落定，之后还能出现的
                        // 条目就是真的还在（比如删除失败又扫了回来）。不清的话，那些删不掉的
                        // 条目会被永久挡在扫描结果之外，用户再也看不到它们。
                        deletingModUris.clear()
                    }
                    // 扫描链尾 = 列表落定的那一处：落一份冷启动快照（IO 已在，写失败不影响本轮扫描）
                    saveModListSnapshot(visible)
                } finally {
                    withContext(Dispatchers.Main) {
                        _isLoading.value = false
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
     */
    private data class ModListSnapshot(
        val version: Int = SNAPSHOT_VERSION,
        /** 落盘时刻，仅供排查（读侧不据此判新旧：新旧由扫描结果决定）。 */
        val savedAt: Long = 0L,
        val mods: List<ModInfo> = emptyList()
    )

    /**
     * Uri ⇄ 字符串。
     *
     * Gson 默认拿 Uri 没辙：它是抽象类、字段私有，反射序列化出来是一堆内部字段
     * （甚至直接抛异常），读回来也构造不出对象。统一按 [Uri.toString] 存、
     * [Uri.parse] 读 —— 与整份代码里「uri 当字符串用」的口径一致。
     */
    private object UriStringAdapter : TypeAdapter<Uri>() {
        override fun write(out: JsonWriter, value: Uri?) {
            out.value(value?.toString())
        }

        override fun read(input: JsonReader): Uri? {
            if (input.peek() == JsonToken.NULL) {
                input.nextNull()
                return null
            }
            return Uri.parse(input.nextString())
        }
    }

    /**
     * 落盘时丢掉的字段。
     *
     * `resolvedTargets`/`unresolvedFiles` 是转换路径的数据，整个 Kotlin 侧没有消费者，
     * 却是体积大头（一条一个几百字节的数组）；`convertedDataUri` 是一长串 document uri，
     * 扫描时会重新发现，快照里那份只在「恢复了条目但还没扫完」这几秒内有用，丢掉它换来的
     * 是校验路径自动跳过（checkUnityFsHeader 拿不到 uri 就返回 null，不判损坏，安全）。
     * 只作用于序列化：读侧结构不变，历史快照里带着这些字段也照读不误。
     */
    private val snapshotDroppedFields =
        setOf("resolvedTargets", "unresolvedFiles", "convertedDataUri")

    private val snapshotGson: Gson by lazy {
        GsonBuilder()
            .registerTypeAdapter(Uri::class.java, UriStringAdapter)
            .addSerializationExclusionStrategy(object : ExclusionStrategy {
                override fun shouldSkipField(f: FieldAttributes) = f.name in snapshotDroppedFields
                override fun shouldSkipClass(clazz: Class<*>?) = false
            })
            .create()
    }

    /**
     * 本轮进程里扫描是否已经落定过一次。
     *
     * 快照只许在这次扫描**之前**进场：扫描一旦出过结果，列表就是事实，
     * 迟到的快照不许再把它盖回去 —— 尤其扫出空表（用户把目录清空了）那种情况，
     * 被旧快照覆盖就是「删掉的 mod 又回来了」，而且不会再有下一次扫描来纠正。
     */
    @Volatile
    private var scanSettled = false

    private fun modListSnapshotFile(): File? =
        appContext?.let { File(it.filesDir, SNAPSHOT_FILENAME) }

    /** 源目录被清空时把快照一并抹掉（写空表即可，读侧按「没有快照」走）。 */
    private fun clearModListSnapshot() {
        viewModelScope.launch(Dispatchers.IO) { saveModListSnapshot(emptyList()) }
    }

    /**
     * 落快照。写在 IO 上、失败只记日志：宁可下次启动回到「没有快照」的老路径，
     * 也不能因为一个缓存文件写不进去影响扫描收尾。
     *
     * 先写 .tmp 再改名：写到一半被杀（用户清后台）时旧的完整快照还在，不会留下
     * 半截 JSON —— 虽然读侧也按损坏处理，但能救回来一份就用一份。
     * [Synchronized]：删除成功、扫描收尾、清目录三处都会写，交错写会把 .tmp 写坏，
     * 相互串起来（同一进程内只有这一处在写这个文件）。
     */
    @Synchronized
    private fun saveModListSnapshot(mods: List<ModInfo>) {
        val file = modListSnapshotFile() ?: return
        try {
            val json = snapshotGson.toJson(
                ModListSnapshot(SNAPSHOT_VERSION, System.currentTimeMillis(), mods)
            )
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.delete()                    // 上次进程被杀留下的半截 .tmp
            tmp.writeText(json)
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) {
                    Log.w("MainViewModel", "快照改名失败，本次不落盘")
                    tmp.delete()
                }
            }
        } catch (e: Exception) {
            Log.w("MainViewModel", "写入冷启动快照失败（不影响功能）", e)
        }
    }

    /** 读快照。任何异常（文件不存在、JSON 半截、字段缺失）都按「没有快照」返回空表。 */
    private fun loadModListSnapshot(): List<ModInfo> {
        val file = modListSnapshotFile() ?: return emptyList()
        if (!file.exists()) return emptyList()
        return try {
            val text = file.readText()
            if (text.isBlank()) return emptyList()
            val snapshot = snapshotGson.fromJson(text, ModListSnapshot::class.java)
                ?: return emptyList()
            if (snapshot.version != SNAPSHOT_VERSION) {
                Log.i("MainViewModel", "快照版本 ${snapshot.version} 不是 $SNAPSHOT_VERSION，丢弃")
                return emptyList()
            }
            // Gson 不管 Kotlin 的非空约束：字段在 JSON 里缺失/被改坏时会给 null，
            // 放过去就会在很远的地方炸 NPE（与 InstalledModRepository.load 同一道防线）
            snapshot.mods.mapNotNull { it?.let(::normalizeSnapshotEntry) }
        } catch (e: Exception) {
            Log.w("MainViewModel", "冷启动快照读取失败，按没有快照处理", e)
            emptyList()
        }
    }

    /**
     * 快照条目的校验与补全。
     *
     * 落盘时被排除的 resolvedTargets/unresolvedFiles 读回来**必然是 null** —— Gson 用
     * Unsafe 造实例、不走 Kotlin 的默认值。留着 null 会在转换/校验路径上炸 NPE，补成空表；
     * 其余关键字段缺失（JSON 被改坏、枚举名对不上）没法修，整条丢掉。
     */
    private fun normalizeSnapshotEntry(mod: ModInfo): ModInfo? {
        @Suppress("SENSELESS_COMPARISON")
        if (mod.uri == null || mod.name == null || mod.character == null || mod.costume == null ||
            mod.type == null || mod.resolutionState == null || mod.installState == null || mod.kind == null
        ) return null
        return mod.copy(
            resolvedTargets = nullToEmptyList(mod.resolvedTargets),
            unresolvedFiles = nullToEmptyList(mod.unresolvedFiles)
        )
    }

    /**
     * null → 空表。
     *
     * 必须跨一层函数边界：直接写 `mod.resolvedTargets ?: emptyList()` 时，编译器按字段的
     * 非空静态类型判定左侧恒非空，判空会被优化掉，null 照样漏过去。
     */
    private fun <T> nullToEmptyList(list: List<T>?): List<T> = list ?: emptyList()

    /**
     * 冷启动把快照灌进列表。跑在 IO 上，读完才回主线程。
     *
     * installState 用账本重算一遍（[applyInstallState]）：快照里那份是上次扫描时的判断，
     * 账本才是「装过什么」的事实。此刻干净检测还没跑过（bundleCleanStates 还是空的），
     * 算出来的是「账本里有 → 未校验 / 没有 → 未装」——这是诚实的：游戏目录还没看过，
     * 扫描跑完自然会落回生效中/需重新应用那些真实状态。
     *
     * defect/outdated 按快照原样显示（它们依赖 catalog，冷启动时还没有更新的依据）。
     */
    private fun restoreModListSnapshot() {
        viewModelScope.launch {
            val restored = withContext(Dispatchers.IO) {
                val snapshot = loadModListSnapshot()
                if (snapshot.isEmpty()) {
                    emptyList()
                } else {
                    val hidden = loadHidden()
                    applyInstallState(snapshot.filterNot { it.uri.toString() in hidden })
                }
            }
            // 扫描已经出过结果时不许覆盖：那份才是真的（见 scanSettled）。
            // 没有源目录时也不灌：那种情况下列表本就该是空的（欢迎页 / 授权被撤销），
            // 快照只会显出一批再也扫不到、也点不动的幽灵条目
            if (restored.isNotEmpty() && !scanSettled && _modsList.value.isEmpty() &&
                _modSourceDirs.value.isNotEmpty()
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
                ModdingService.getBundleMeta(
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
        val hasBackupRepo = ::backupRepository.isInitialized
        for ((name, expected) in meta) {
            val (expectedSize, expectedHash) = expected
            val actual = local[name]
            states[name] = when {
                actual == null -> BundleCleanState.ABSENT
                actual.first != expectedHash -> BundleCleanState.VERSION_MISMATCH
                actual.second < 0 -> BundleCleanState.UNKNOWN
                actual.second == expectedSize -> BundleCleanState.PRISTINE
                // 与本地备份一致也算原版：备份要么是装 mod 前从游戏目录留下的原版，
                // 要么是卸载时 restore_bundle 产出的原版。后者用 lz4 重打包，字节数与
                // 官方 catalog 会差千分之几，不认这一条的话卸载完会一直显示「被修改」。
                hasBackupRepo && actual.second ==
                        backupRepository.backupSize(name, actual.first) -> BundleCleanState.PRISTINE
                else -> BundleCleanState.MODIFIED
            }
        }
        bundleCleanStates = states
        cleanScanError = null
        cleanScanDetail = null
        Log.d("MainViewModel", "干净检测: ${states.size} 个 bundle，" +
                "其中被修改 ${states.count { it.value == BundleCleanState.MODIFIED }} 个")
        return states
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
        val selectedResult = ModdingService.getBundleMeta(context.filesDir.absolutePath, selected, onProgress)
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
        val otherResult = ModdingService.getBundleMeta(context.filesDir.absolutePath, other) { }
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
            val result = ModdingService.getBundleMeta(context.filesDir.absolutePath, other) { }
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
                val result = ModdingService.getBundleMeta(
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

    /**
     * 一批 hash 目录改名的结果。**跳过与失败必须分开**：跳过（目标名已存在）不是错误，
     * 是在那种情形下唯一安全的选择，报成失败只会让用户以为坏了、反复重试。
     */
    private sealed class HashRenameResult {
        /** 改好了（或本来就已经是新哈希，无需改）。 */
        object Renamed : HashRenameResult()

        /** 同目录里已经有一个同名（新哈希）目录，没动任何东西。 */
        object TargetExists : HashRenameResult()

        /**
         * 游戏目录里这个 bundle 还停在别的哈希上（[gameBundleHashes] 有据可查）——
         * 用户还没进游戏把资源更新到 catalog 这一版。这时改名是改早了：改完游戏
         * 根本不读，mod 静默失效，正是最坏的失败模式。宁可不改，让用户先更新资源。
         */
        object GameBehindCatalog : HashRenameResult()

        data class Failed(val reason: String) : HashRenameResult()
    }

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
        // 留着只会白占空间，直到用户手动清缓存
        if (::previewCacheRepository.isInitialized) {
            try {
                previewCacheRepository.delete(bundleName, oldHash)
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
        val records = if (::installedModRepository.isInitialized) {
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
            previewCacheRepository.resolvePair(bundleName, hashDir)?.let { cached ->
                Log.d("MainViewModel", "预览命中缓存: $bundleName")
                // 不能把缓存目录直接交给 SpinePreviewActivity —— 它 onDestroy 里会
                // deleteRecursively 传入的目录，那样看一次就把缓存删了。拷副本给它删。
                previewCacheRepository.entryDir(bundleName, hashDir).listFiles()
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

        if (!Python.isStarted()) {
            Python.start(com.chaquo.python.android.AndroidPlatform(context))
        }
        // 直接解到缓存位置，省掉「解到临时目录再整体拷进缓存」那一趟
        val outDir = previewCacheRepository.prepareDir(bundleName, hashDir)
        val (ok, msg) = ModdingService.unpackBundle(
            bundleFile.absolutePath, outDir.absolutePath, fast = true
        ) { p ->
            _previewState.value = PreviewState.Preparing(p)
            false   // 预览路径不提供中途取消
        }
        bundleFile.delete()
        if (!ok) {
            previewCacheRepository.delete(bundleName, hashDir)   // 不留半成品当缓存
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
            previewCacheRepository.delete(bundleName, hashDir)
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
     * 两条路都会把同包已装的其他 mod 一并处理：转换那条靠 [mergeWithInstalled]，
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
        _installJobs.value = listOf(InstallJob(RepackJob(hash, mergeWithInstalled(hash, listOf(mod)))))
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
    /** collectPreviewFiles 的产物：spine 三件套 + 全部贴图（静态查看兜底用）。 */
    data class PreviewAssets(
        val skelPath: String?,
        val atlasPath: String?,
        val pngPaths: List<String>
    )

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
            if (fileName.endsWith(".skel") || fileName.endsWith(".json")) skel = dest.absolutePath
            else if (fileName.endsWith(".atlas")) atlas = dest.absolutePath
            else if (fileName.endsWith(".png")) pngs.add(dest.absolutePath)
        }

        val previewExts = setOf(".skel", ".json", ".atlas", ".png")
        if (modInfo.isDirectory) {
            DocumentFile.fromTreeUri(context, modInfo.uri)?.listFiles()?.forEach { file ->
                val fileName = file.name ?: ""
                if (!shouldIgnoreModEntry(fileName) &&
                    previewExts.any { fileName.endsWith(it) }) {
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
                        val fileName = entry.name.substringAfterLast('/')
                        if (!entry.isDirectory && !shouldIgnoreModEntry(fileName) &&
                            previewExts.any { fileName.endsWith(it) }) {
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
    private val _latestRelease = MutableStateFlow<UpdateRepository.Release?>(null)
    val latestRelease: StateFlow<UpdateRepository.Release?> = _latestRelease.asStateFlow()


    /** 静态预览（无骨架 mod 的贴图查看）：图片路径列表；非空时界面弹查看器。 */
    private val _staticPreviewImages = MutableStateFlow<List<String>>(emptyList())
    val staticPreviewImages: StateFlow<List<String>> = _staticPreviewImages.asStateFlow()

    /** 静态预览的临时目录，查看器关闭时清理。 */
    private val _staticPreviewTempDir = MutableStateFlow<String?>(null)

    /** 下载完成待安装的 APK 文件名；非空时界面弹「安装」确认框。 */
    private val _updateApkReady = MutableStateFlow<String?>(null)
    val updateApkReady: StateFlow<String?> = _updateApkReady.asStateFlow()
    /** 正在检查。同时当防连点用：进行中再点直接忽略。 */
    private val _updateChecking = MutableStateFlow(false)
    val updateChecking: StateFlow<Boolean> = _updateChecking.asStateFlow()

    /**
     * 手动检查更新一次。
     *
     * 网络请求在仓库里走 IO 线程，这里回到 viewModelScope 的主线程再弹 toast ——
     * Toast 在没调过 Looper.prepare 的后台线程上会直接抛异常，结果回调必须落在
     * 主线程（[toast] 只负责换个 application context，不切线程）。
     */
    fun checkForUpdates() {
        if (_updateChecking.value) return
        val ctx = appContext ?: return
        _updateChecking.value = true
        viewModelScope.launch {
            try {
                val repo = UpdateRepository.get(ctx)
                val release = repo.fetchLatest()
                when {
                    release == null ->
                        toast(ctx, "检查更新失败：连不上 GitHub，请检查网络或代理")
                    !repo.isNewerThanInstalled(release.version) ->
                        toast(ctx, "已是最新版本（当前 ${repo.installedVersion()}）")
                    else -> _latestRelease.value = release
                }
            } finally {
                _updateChecking.value = false
            }
        }
    }

    /** 关掉更新弹窗。下次再点「检查更新」重新拉。 */
    fun dismissUpdate() {
        _latestRelease.value = null
    }

    /**
     * 应用内下载更新包。架构选择在仓库层按本机 ABI 自动做；排队成功 toast 提醒
     * 进度看通知栏，结果由 DownloadManager 的完成广播接回（见 MainActivity 的
     * 接收器 → [onUpdateDownloadComplete]）。
     */
    fun downloadUpdate(release: UpdateRepository.Release) {
        val ctx = appContext ?: return
        viewModelScope.launch {
            val repo = UpdateRepository.get(ctx)
            // 已经下过这个包就直接弹安装，不再重复下载几十 MB
            if (withContext(Dispatchers.IO) { repo.isApkDownloaded(release) }) {
                _updateApkReady.value =
                    withContext(Dispatchers.IO) { repo.downloadedApkFile()?.name }
                return@launch
            }
            val started = withContext(Dispatchers.IO) {
                repo.startApkDownload(release)
            }
            if (started != null) {
                toast(ctx, "已开始下载 ${started.name}，进度见通知栏")
            } else if (withContext(Dispatchers.IO) { UpdateRepository.get(ctx).hasActiveDownload() }) {
                toast(ctx, "已有下载在进行中")
            } else {
                toast(ctx, "这个版本没有可下载的安装包，请到发布页手动下载")
            }
        }
    }

    /** 下载完成的回执入口（MainActivity 的广播接收器调）。 */
    fun onUpdateDownloadComplete(id: Long) {
        val ctx = appContext ?: return
        viewModelScope.launch {
            val repo = UpdateRepository.get(ctx)
            if (id != withContext(Dispatchers.IO) { repo.currentDownloadId() }) return@launch
            val ok = withContext(Dispatchers.IO) { repo.isDownloadSuccessful(id) }
            if (ok) {
                _updateApkReady.value = withContext(Dispatchers.IO) {
                    repo.downloadedApkFile()?.name
                }
                toast(ctx, "更新包下载完成")
            } else {
                toast(ctx, "更新包下载失败，请检查网络或代理后重试")
            }
        }
    }

    /** 关掉「下载完成」弹窗（不安装，包文件保留，下次检查更新可重下）。 */
    fun dismissUpdateReady() {
        _updateApkReady.value = null
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
}
