package com.bd2toolsbox

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.bd2toolsbox.data.repository.UpdateRepository
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bd2toolsbox.data.model.ModInfo
import com.bd2toolsbox.ui.components.WallpaperBackdrop
import com.bd2toolsbox.ui.dialogs.*
import com.bd2toolsbox.ui.screens.CharacterFilter
import com.bd2toolsbox.ui.screens.CharacterScreen
import com.bd2toolsbox.ui.screens.CharacterSheet
import com.bd2toolsbox.ui.screens.CdkScreen
import com.bd2toolsbox.ui.screens.GuideScreen
import com.bd2toolsbox.ui.screens.ModScreen
import com.bd2toolsbox.ui.screens.OnboardingScreen
import com.bd2toolsbox.ui.screens.WorkbenchSheet
import com.bd2toolsbox.ui.screens.buildCharacterEntries
import com.bd2toolsbox.ui.screens.buildSkinEntries
import com.bd2toolsbox.ui.theme.BD2ModManagerTheme
import android.widget.Toast
import com.bd2toolsbox.ui.viewmodel.GuideViewModel
import com.bd2toolsbox.ui.viewmodel.MainViewModel
import com.bd2toolsbox.utils.SafManager
import com.bd2toolsbox.service.ShizukuManager
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    /**
     * 系统下载完成广播：只认我们自己排队的那个下载 id，交给 ViewModel 走
     * 「下载完成 → 弹安装框」的流程。
     */
    private val updateDownloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id != -1L &&
                id == UpdateRepository.get(applicationContext).currentDownloadId()
            ) {
                viewModel.onUpdateDownloadComplete(id)
            }
        }
    }

    private val viewModel: MainViewModel by viewModels()
    private val guideViewModel: GuideViewModel by viewModels()

    private val SHIZUKU_PERMISSION_REQUEST_CODE = 1001

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    // Permission granted, UI will update automatically via state
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 更新包下载完成的系统广播（targetSdk 34 起 context 注册必须带 flag；
        // 广播由系统发出，NOT_EXPORTED 即可）
        ContextCompat.registerReceiver(
            this,
            updateDownloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // 手势条沉浸：状态栏/导航栏透明，内容画到系统栏后面（各内容区的
        // 避让交给各自的 statusBarsPadding / NavigationBar / Scaffold）
        enableEdgeToEdge()

        viewModel.initialize(applicationContext)
        // 攻略板块的仓库是懒加载单例，这里顺手把分类树拉起来（进 tab 前就绪）
        guideViewModel.initialize(applicationContext)

        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
            // 自動請求 Shizuku 權限
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
            }
        } catch (_: Exception) {
            // Shizuku not available
        }

        setContent {
            // 取色开关、配色预设、壁纸都会让整个主题重组，所以在这一层收集
            val dynamicColor by viewModel.dynamicColor.collectAsState()
            val appTheme by viewModel.appTheme.collectAsState()
            val wallpaperUri by viewModel.wallpaperUri.collectAsState()
            val wallpaperScrim by viewModel.wallpaperScrim.collectAsState()

            BD2ModManagerTheme(
                dynamicColor = dynamicColor,
                theme = appTheme,
                // 有壁纸时把 background/surface 抽成透明，壁纸才透得出来
                transparentBackground = wallpaperUri != null
            ) {
                // 壁纸铺在所有内容之下。放在 Theme 之内是为了拿到 colorScheme
                // 算遮罩色 —— 遮罩必须跟着配色走，否则换了主题色壁纸上会压着一层
                // 不搭的灰。
                WallpaperBackdrop(uri = wallpaperUri, scrim = wallpaperScrim)

                var uninstallConfirmationTarget by remember { mutableStateOf<String?>(null) }
                var removeModTarget by remember { mutableStateOf<ModInfo?>(null) }
                var renameTarget by remember { mutableStateOf<ModInfo?>(null) }
                var deleteFolderTarget by remember { mutableStateOf<ModInfo?>(null) }
                var showUnpackDialog by remember { mutableStateOf(false) }
                var showBackupManage by remember { mutableStateOf(false) }
                var showDirsManage by remember { mutableStateOf(false) }

                val modSourceDirLauncher = rememberLauncherForActivityResult(
                    contract = SafManager.PickDirectoryWithSpecialAccess(),
                    onResult = { uri ->
                        if (uri != null) {
                            contentResolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            )
                            viewModel.addModSourceDir(this, uri).let { added ->
                                // 选了个已记住目录的下级时列表不会变。不说一句的话，
                                // 用户只看到「点了没反应」，会以为选目录失败了。
                                if (!added) Toast.makeText(
                                    this,
                                    "这个目录已经在列表里（或已被上层目录覆盖）",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                )

                // 壁纸选图。用 OpenDocument 而不是 GetContent —— 只有前者拿得到
                // 可持久化的 URI，后者给的是一次性权限，重启后就读不到图了。
                val wallpaperLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument(),
                    onResult = { uri -> if (uri != null) viewModel.setWallpaper(uri) }
                )

                // 通知权限（Android 13+）。批量预解包靠前台服务保活，而前台服务要有通知
                // 才看得见进度。刻意不在启动时就要 —— 放在点「批量预解包」时请求，
                // 用户才能把弹窗和它的用途对上。拒绝也不拦：任务照跑，只是没有进度条。
                val notificationPermLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission(),
                    onResult = { /* 允许与否都继续 */ }
                )
                fun ensureNotificationPermission() {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
                    val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                            PackageManager.PERMISSION_GRANTED
                    if (!granted) notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }

                // 底栏三项：「Mods」「攻略」「兑换码」。攻略与兑换码都是纯在线
                // 阅读，与 mod 处理互不牵扯，所以顶栏那排目录/筛选只在 Mods 出现；
                // 「设置」照旧不占 tab，挂在顶栏右上角齿轮 —— 齿轮在另两页也会
                // 出现（壁纸/配色是全局的）。
                var currentTab by remember { mutableStateOf(0) }
                var settingsOpen by remember { mutableStateOf(false) }

                // 三个 tab 共用的「打开设置」动作：先刷一遍各缓存的占用数，
                // 弹层里的数值才是当下的
                val openSettings = {
                    viewModel.refreshBackupUsage()
                    viewModel.refreshPreviewCacheUsage()
                    viewModel.refreshSpineRuntimeUsage()
                    settingsOpen = true
                    Unit
                }

                // 「按角色」是新的默认视图：用户关心的是某个角色有没有 mod，
                // 而不是它处在哪个加工阶段。「全部」保留原来的平铺列表 ——
                // 批量多选转换要用它，认不出角色的 mod 也只能在那里看到。
                var byCharacter by remember { mutableStateOf(true) }
                var openedCharacter by remember { mutableStateOf<String?>(null) }
                var characterFilter by remember { mutableStateOf(CharacterFilter.ALL) }

                val modSourceUri by viewModel.modSourceDirectoryUri.collectAsState()
                val modSourceDirs by viewModel.modSourceDirs.collectAsState()

                Scaffold(
                    topBar = {
                        if (currentTab == 1) {
                            // 攻略页头部：只有标题与设置齿轮。齿轮与 Mods 页是同一个
                            // 入口 —— 壁纸、配色这些是全局观感，攻略页里也得能调。
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .statusBarsPadding()
                                    .padding(start = 16.dp, end = 8.dp, top = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        "攻略",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        "来自 GameKee 棕色尘埃2分区",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(Modifier.weight(1f))
                                SettingsGear(openSettings)
                            }
                        } else if (currentTab == 2) {
                            // 兑换码页自带标题行（含刷新），顶栏只补一个设置齿轮
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .statusBarsPadding()
                                    .padding(end = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Spacer(Modifier.weight(1f))
                                SettingsGear(openSettings)
                            }
                        } else
                        // 一行放齐：目录入口 │ 筛选 │ 视图切换 │ 设置。
                        // 不再写「mods」标题 —— 底栏那一项已经在说当前在 Mods 了。
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(start = 8.dp, end = 8.dp, top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            var folderMenu by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { folderMenu = true }) {
                                    Icon(Icons.Default.FolderOpen, contentDescription = "mod 文件夹")
                                }
                                DropdownMenu(
                                    expanded = folderMenu,
                                    onDismissRequest = { folderMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        // 不再叫「更换」：现在是往已记住的目录里再添一个，
                                        // 旧的不会被顶掉。
                                        text = { Text(if (modSourceUri == null) "打开 mod 文件夹" else "添加 mod 文件夹") },
                                        leadingIcon = { Icon(Icons.Default.FolderOpen, null) },
                                        onClick = { folderMenu = false; modSourceDirLauncher.launch(Unit) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("管理文件夹（${modSourceDirs.size}）") },
                                        leadingIcon = { Icon(Icons.Default.Tune, null) },
                                        enabled = modSourceDirs.isNotEmpty(),
                                        onClick = { folderMenu = false; showDirsManage = true }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("重新扫描") },
                                        leadingIcon = { Icon(Icons.Default.Refresh, null) },
                                        enabled = modSourceUri != null,
                                        onClick = {
                                            folderMenu = false
                                            viewModel.rescanAllModSources()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("回到首页重选") },
                                        leadingIcon = { Icon(Icons.Default.Close, null) },
                                        enabled = modSourceUri != null,
                                        onClick = { folderMenu = false; viewModel.clearModSourceDirectory() }
                                    )
                                }
                            }

                            // 筛选收进图标 + 二级菜单。原先是一整行 chip，六个筛选就占掉
                            // 一行高度，而它们并非每次都要改；收起来能多露出一个角色。
                            // 只在「按角色」视图出现 ——「全部」视图有自己的状态筛选（在列表内）。
                            if (byCharacter) {
                                var filterMenu by remember { mutableStateOf(false) }
                                Box {
                                    IconButton(onClick = { filterMenu = true }) {
                                        Icon(
                                            Icons.Default.FilterList,
                                            contentDescription = "筛选",
                                            // 收起来之后「当前有没有在筛」就看不见了，
                                            // 所以非「全部」时把图标染成主色当作提示。
                                            tint = if (characterFilter == CharacterFilter.ALL)
                                                LocalContentColor.current
                                            else MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = filterMenu,
                                        onDismissRequest = { filterMenu = false }
                                    ) {
                                        CharacterFilter.entries.forEach { f ->
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        f.label,
                                                        color = if (f.ready) Color.Unspecified
                                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                },
                                                leadingIcon = {
                                                    // 用勾而不是 RadioButton：菜单里一行只需要
                                                    // 「是不是当前这个」，勾更轻
                                                    if (f == characterFilter && f.ready) {
                                                        Icon(Icons.Default.Check, null)
                                                    } else {
                                                        Spacer(Modifier.width(24.dp))
                                                    }
                                                },
                                                // 不做成 enabled=false：那样点了毫无反应，
                                                // 用户分不清是坏了还是没做。点了给一句说明，菜单不关。
                                                onClick = {
                                                    if (f.ready) {
                                                        characterFilter = f
                                                        filterMenu = false
                                                    } else {
                                                        Toast.makeText(
                                                            this@MainActivity,
                                                            "这个筛选还需要一份角色名单（性别 / 联动），暂未启用",
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            // 无信号标识：离线导致 N 个 mod 没做过期检查（截断名/文件头/
                            // 结构三道本地检查照跑，跳过的只有查表那道）。只在 N>0 时出现，
                            // 点按重新联网校验，取到表后计数归零、标识自然消失。
                            val unverifiedCount by viewModel.unverifiedModCount.collectAsState()
                            if (unverifiedCount > 0) {
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.14f),
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.clickable { viewModel.retryCatalogValidation() }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(
                                            start = 8.dp, end = 10.dp, top = 5.dp, bottom = 5.dp
                                        ),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.WifiOff,
                                            contentDescription = "$unverifiedCount 个 mod 未联网校验，点按重试",
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            "$unverifiedCount",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                                Spacer(Modifier.width(6.dp))
                            }

                            Spacer(Modifier.weight(1f))

                            FilterChip(
                                selected = byCharacter,
                                onClick = { byCharacter = true },
                                label = { Text("按角色", style = MaterialTheme.typography.labelMedium) }
                            )
                            Spacer(Modifier.width(6.dp))
                            FilterChip(
                                selected = !byCharacter,
                                onClick = { byCharacter = false },
                                label = { Text("全部", style = MaterialTheme.typography.labelMedium) }
                            )
                            Spacer(Modifier.width(2.dp))
                            SettingsGear(
                                onClick = {
                                    viewModel.refreshBackupUsage()
                                    viewModel.refreshPreviewCacheUsage()
                                    viewModel.refreshSpineRuntimeUsage()
                                    settingsOpen = true
                                }
                            )
                        }
                    },
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = currentTab == 0,
                                onClick = { currentTab = 0 },
                                icon = { Icon(Icons.Default.Extension, contentDescription = null) },
                                label = { Text("Mods") }
                            )
                            NavigationBarItem(
                                selected = currentTab == 1,
                                onClick = { currentTab = 1 },
                                icon = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null) },
                                label = { Text("攻略") }
                            )
                            NavigationBarItem(
                                selected = currentTab == 2,
                                onClick = { currentTab = 2 },
                                icon = { Icon(Icons.Default.CardGiftcard, contentDescription = null) },
                                label = { Text("兑换码") }
                            )
                        }
                    }
                    ) { padding ->
                        Surface(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding),
                            // 有壁纸时 colorScheme.background 已经是透明的（见 Theme），
                            // 所以这里照常引用它即可 —— 不必在这儿再判一次。
                            color = MaterialTheme.colorScheme.background
                        ) {
                            when (currentTab) {
                                1 -> GuideScreen(guideViewModel)
                                2 -> CdkScreen()
                                else -> Column(modifier = Modifier.fillMaxSize()) {
                                    val allMods by viewModel.modsList.collectAsState()

                                    if (byCharacter) {
                                        val characters by viewModel.allCharacterNames.collectAsState()
                                        val npcCharacters by viewModel.npcCharacterNames.collectAsState()
                                        val avatarSyncFailed by viewModel.avatarSyncFailed.collectAsState()
                                        val entries = remember(characters, npcCharacters, allMods) {
                                            buildCharacterEntries(characters, npcCharacters, allMods)
                                        }
                                        CharacterScreen(
                                            entries = entries,
                                            filter = characterFilter,
                                            onPickCharacter = { openedCharacter = it },
                                            // 预取整趟没拿到图时，列表正中给一句「请检查网络」
                                            avatarSyncFailed = avatarSyncFailed,
                                            onRetryAvatarSync = { viewModel.retryAvatarSync() }
                                        )
                                    } else {
                                        ModScreen(
                                            viewModel = viewModel,
                                            // 不按加工阶段过滤：两类混排，与「按角色」视图口径一致
                                            kindFilter = null,
                                            onSelectModSource = { modSourceDirLauncher.launch(Unit) },
                                            onUninstallRequest = { hash -> uninstallConfirmationTarget = hash },
                                            onRemoveModRequest = { mod -> removeModTarget = mod },
                                            onRenameRequest = { mod -> renameTarget = mod },
                                            onDeleteFolderRequest = { mod -> deleteFolderTarget = mod },
                                            onInstallConverted = { mods ->
                                                // 转换/装入现在挂在前台服务上（防止放着不管被系统回收），
                                                // 前台服务要有通知才看得见进度，所以这里顺手请求一次。
                                                // 拒绝也不拦：任务照跑，只是通知栏里看不到。
                                                ensureNotificationPermission()
                                                viewModel.installConvertedBundles(this@MainActivity, mods)
                                            },
                                            onRequestNotification = { ensureNotificationPermission() },
                                            onBackupManageRequest = {
                                                viewModel.refreshBackupUsage()
                                                showBackupManage = true
                                            },
                                            onUnpackRequest = { showUnpackDialog = true }
                                        )
                                    }
                                }
                            }
                        }
                    }

                // 角色卡片
                openedCharacter?.let { name ->
                    val allMods by viewModel.modsList.collectAsState()
                    val skins = remember(name, allMods) { buildSkinEntries(name, allMods) }
                    CharacterSheet(
                        characterName = name,
                        entries = skins,
                        onPreview = { mod -> viewModel.prepareAndShowPreview(this, mod) },
                        onInstall = { e ->
                            openedCharacter = null
                            ensureNotificationPermission()
                            viewModel.installSingle(this, e.converted, e.source)
                        },
                        onInstallBatch = { picked ->
                            openedCharacter = null
                            ensureNotificationPermission()
                            // 和列表页的 FAB 同一套判断：全是已转换产物就直拷进游戏目录，
                            // 只要掺了 PC 源就整批走转换 —— 那条路本来就会复用已有产物。
                            val allConverted = picked.all { it.onlyConverted || it.hasConverted }
                            if (allConverted) {
                                viewModel.installConvertedBundles(this, picked.map { it.converted!! })
                            } else {
                                viewModel.startRepackFor(this, picked.map { it.primary })
                            }
                        },
                        onRemove = { mod ->
                            openedCharacter = null
                            removeModTarget = mod
                        },
                        onDismiss = { openedCharacter = null }
                    )
                }

                if (settingsOpen) {
                    WorkbenchSheet(
                        viewModel = viewModel,
                        onUninstallAll = { settingsOpen = false; viewModel.prepareUninstallAll() },
                        onUnpackTool = { settingsOpen = false; showUnpackDialog = true },
                        onRecheckShizuku = { recheckShizukuWithFeedback(viewModel) },
                        onPrepack = {
                            settingsOpen = false
                            ensureNotificationPermission()
                            viewModel.preparePrepack()
                        },
                        onPickWallpaper = {
                            // 弹层不关：选完图回来能立刻看到效果、还能接着拖滑块
                            wallpaperLauncher.launch(arrayOf("image/*"))
                        },
                        onReplayOnboarding = {
                            settingsOpen = false
                            viewModel.replayOnboarding()
                        },
                        onDismiss = { settingsOpen = false }
                    )
                }

                val ledgerNotice by viewModel.ledgerResetNotice.collectAsState()
                LedgerResetDialog(
                    notice = ledgerNotice,
                    onDismiss = { viewModel.dismissLedgerResetNotice() }
                )

                val overwritePlan by viewModel.convertedOverwritePlan.collectAsState()
                ConvertedOverwriteDialog(
                    plan = overwritePlan,
                    onConfirm = { viewModel.confirmConvertedOverwrite(this) },
                    onDismiss = { viewModel.dismissConvertedOverwritePlan() }
                )

                val prepackPlan by viewModel.prepackPlan.collectAsState()
                PrepackPlanDialog(
                    plan = prepackPlan,
                    onConfirm = { viewModel.startPrepack() },
                    onDismiss = { viewModel.dismissPrepackPlan() }
                )

                // 检查更新：只在「设置 → 高级」里手动点，结果落在这一个弹窗上。
                // 「去下载」跳系统浏览器到 release 页，App 自己不下载不安装。
                val latestRelease by viewModel.latestRelease.collectAsState()
                UpdateDialog(
                    release = latestRelease,
                    onDownload = { release ->
                        // 应用内下载：架构按本机自动选，进度看通知栏，
                        // 完成后由下载广播接回并弹「安装」框
                        viewModel.downloadUpdate(release)
                        viewModel.dismissUpdate()
                    },
                    onDismiss = { viewModel.dismissUpdate() }
                )

                // 下载完成后的安装确认。安装要拉系统安装器与未知来源授权，
                // 都需要 Activity 上下文，动作在 Activity 层做。
                val updateApkReady by viewModel.updateApkReady.collectAsState()
                UpdateReadyDialog(
                    apkName = updateApkReady,
                    onInstall = { installUpdateApk() },
                    onDismiss = { viewModel.dismissUpdateReady() }
                )

                val prepackProgress by viewModel.prepackProgress.collectAsState()
                // 「后台运行」只是把框收起来，任务在服务里照跑，所以这个开关得留在
                // 界面侧（ViewModel 里的 progress 仍然非 null）。
                var prepackHidden by remember { mutableStateOf(false) }
                // 任务结束（progress 变 null）时复位，否则收起来一次之后，
                // 下次点「批量预解包」就再也看不到进度框了。
                LaunchedEffect(prepackProgress == null) {
                    if (prepackProgress == null) prepackHidden = false
                }
                PrepackProgressDialog(
                    progress = if (prepackHidden) null else prepackProgress,
                    onCancel = { viewModel.cancelPrepack() },
                    onHide = { prepackHidden = true }
                )

                val uninstallPlan by viewModel.uninstallPlan.collectAsState()
                UninstallAllDialog(
                    plan = uninstallPlan,
                    onConfirm = { viewModel.uninstallAll(this) },
                    onDismiss = { viewModel.dismissUninstallPlan() }
                )

                if (showBackupManage) {
                    val backupOn by viewModel.backupOriginals.collectAsState()
                    val backupUsage by viewModel.backupUsage.collectAsState()
                    BackupManageDialog(
                        enabled = backupOn,
                        usage = backupUsage,
                        onToggle = { viewModel.setBackupOriginals(it) },
                        onClear = { viewModel.clearBackups() },
                        onDismiss = { showBackupManage = false }
                    )
                }

                if (showDirsManage) {
                    val allModsForDirs by viewModel.modsList.collectAsState()
                    ModSourceDirsDialog(
                        dirs = modSourceDirs,
                        mods = allModsForDirs,
                        // 框不关：加完一个还能接着加下一个，也能立刻看到它带进来几个 mod
                        onAdd = { modSourceDirLauncher.launch(Unit) },
                        onRemove = { viewModel.removeModSourceDir(it) },
                        onDismiss = { showDirsManage = false }
                    )
                }

                val previewState by viewModel.previewState.collectAsState()
                PreviewProgressDialog(
                    state = previewState,
                    onDismiss = { viewModel.dismissPreviewState() }
                )

                val showInstallDialog by viewModel.showInstallDialog.collectAsState()
                val moveState by viewModel.moveState.collectAsState()

                if (showInstallDialog) {
                    val installJobs by viewModel.installJobs.collectAsState()
                    val finalResult by viewModel.finalInstallResult.collectAsState()
                    ParallelInstallDialog(
                        installJobs = installJobs,
                        finalResult = finalResult,
                        moveState = moveState,
                        onMoveToGame = { viewModel.moveFilesToGame() },
                        onRecheckShizuku = { recheckShizukuWithFeedback(viewModel) },
                        onDismiss = { viewModel.closeInstallDialog() }
                    )
                }

                val uninstallState by viewModel.uninstallState.collectAsState()
                UninstallDialog(
                    state = uninstallState,
                    moveState = moveState,
                    onMoveToGame = { viewModel.moveFilesToGame() },
                    onRecheckShizuku = { recheckShizukuWithFeedback(viewModel) },
                    onDismiss = { viewModel.resetUninstallState() }
                )

                // 首次使用的引导。放在最后 = 盖在所有内容（含各种对话框）之上：
                // 新用户第一眼该看到的是「怎么用」，而不是背后那些空列表和提示框。
                val onboardingDone by viewModel.onboardingDone.collectAsState()
                if (!onboardingDone) {
                    OnboardingScreen(
                        // 引导里那个「现在就选一个文件夹」直接复用主界面的选择器，
                        // 选完就真的记住了，不是走个过场
                        onPickFolder = { modSourceDirLauncher.launch(Unit) },
                        onFinish = { viewModel.completeOnboarding() }
                    )
                }

                UninstallConfirmationDialog(
                    targetHash = uninstallConfirmationTarget,
                    onConfirm = { hash ->
                        viewModel.initiateUninstall(this, hash)
                        uninstallConfirmationTarget = null
                    },
                    onDismiss = {
                        uninstallConfirmationTarget = null
                    }
                )

                RemoveModConfirmationDialog(
                    mod = removeModTarget,
                    onConfirm = { mod ->
                        viewModel.removeSingleMod(this, mod)
                        removeModTarget = null
                    },
                    onDismiss = { removeModTarget = null }
                )

                RenameModDialog(
                    mod = renameTarget,
                    currentAlias = renameTarget?.targetHash?.let { viewModel.aliasOf(it) } ?: "",
                    onConfirm = { mod, alias ->
                        viewModel.setModAlias(mod, alias)
                        renameTarget = null
                    },
                    onDismiss = { renameTarget = null }
                )

                DeleteFolderConfirmationDialog(
                    mod = deleteFolderTarget,
                    onConfirm = { mod ->
                        viewModel.deleteModFolder(this, mod) { _, msg ->
                            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                        }
                        deleteFolderTarget = null
                    },
                    onDismiss = { deleteFolderTarget = null }
                )

                if (showUnpackDialog) {
                    val unpackState by viewModel.unpackState.collectAsState()
                    val unpackInputFile by viewModel.unpackInputFile.collectAsState()
                    UnpackDialog(
                        unpackState = unpackState,
                        inputFile = unpackInputFile,
                        onSetInputFile = { viewModel.setUnpackInputFile(it) },
                        onInitiateUnpack = { viewModel.initiateUnpack(this) },
                        onResetState = { viewModel.resetUnpackState() },
                        onDismiss = { showUnpackDialog = false }
                    )
                }

                // 「合并 Spine」的 UI 入口已移除（转换时 repacker 本就会自动合并超出的贴图页），
                // 所以这里不再挂 MergeSpineDialog。ViewModel 侧的 initiateMerge/mergeState 保留着，
                // 将来若要放进高级菜单可直接复用。

                val showVersionMismatch by viewModel.showVersionMismatchWarning.collectAsState()
                if (showVersionMismatch) {
                    VersionMismatchWarningDialog(
                        onDismiss = { viewModel.dismissVersionMismatchWarning() }
                    )
                }

                val bundleScanState by viewModel.bundleScanState.collectAsState()
                BundleScanDialog(
                    state = bundleScanState,
                    onConfirm = { viewModel.confirmBundleScan() },
                    onDismiss = { viewModel.dismissBundleScan() }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 更新下载广播注册于 onCreate，这里配对注销
        runCatching { unregisterReceiver(updateDownloadReceiver) }
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        } catch (_: Exception) {
            // Shizuku not available
        }
        // 正常退出时顺手把 Shizuku 服务进程收掉，省下约 130 MB 常驻。
        //
        // 防止进程堆积靠的是 .daemon(true)（见 ShizukuManager）——「守护」保证无论怎么退出
        // 都只有一个进程可复用；这里只是让主动退出的情况更干净些，收不掉也不会累积。
        //
        // isFinishing 判断是必要的：旋转屏幕等配置变更同样走 onDestroy，那时 Activity
        // 马上重建，解绑只会让下一次操作重新走一遍绑定握手。
        if (isFinishing) {
            ShizukuManager.unbindService()
        }
    }

    /**
     * 重新检测 Shizuku 并给出即时反馈。
     *
     * 没有 Toast 的话，Shizuku 仍未启动时界面不会有任何变化，
     * 用户无法区分「点了没生效」和「检测过但仍不可用」。
     */
    /**
     * 拉起系统安装器装下载好的更新包。
     *
     * Android 8+ 要求「允许来自此来源的应用安装」授权：没给过就先把设置页
     * 打开（系统没有授权完成的回调，用户回来后需要再点一次安装）。
     */
    private fun installUpdateApk() {
        val file = UpdateRepository.get(applicationContext).downloadedApkFile() ?: run {
            Toast.makeText(this, "安装包不存在，请重新检查更新", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            Toast.makeText(
                this,
                "请先允许 BD2 ToolsBox 安装应用，回来后再点一次安装",
                Toast.LENGTH_LONG
            ).show()
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")
                    )
                )
            }
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        }.onFailure {
            Toast.makeText(this, "无法启动安装器：${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun recheckShizukuWithFeedback(viewModel: MainViewModel) {
        val available = viewModel.recheckShizuku()
        val msg = if (available) {
            "已检测到 Shizuku，可以装入游戏了"
        } else {
            "Shizuku 仍未运行，请先在 Shizuku 中启动服务"
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}

/** 顶栏右上角的设置齿轮。三个 tab 共用同一个入口，点击行为由所在 tab 注入。 */
@Composable
private fun SettingsGear(onClick: () -> Unit = {}) {
    IconButton(onClick = onClick) {
        Icon(Icons.Default.Settings, contentDescription = "设置")
    }
}

