package com.bd2toolsbox.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bd2toolsbox.data.model.ModInfo
import com.bd2toolsbox.data.model.PreviewState
import com.bd2toolsbox.feature.cdk.CdkViewModel
import com.bd2toolsbox.feature.update.UpdateViewModel
import com.bd2toolsbox.ui.TopLevelTab
import com.bd2toolsbox.ui.components.RecoveryBanner
import com.bd2toolsbox.ui.components.TaskProgressPanel
import com.bd2toolsbox.ui.components.WallpaperBackdrop
import com.bd2toolsbox.ui.dialogs.*
import com.bd2toolsbox.ui.rememberTopLevelNavState
import com.bd2toolsbox.ui.screens.CdkScreen
import com.bd2toolsbox.ui.screens.CharacterFilter
import com.bd2toolsbox.ui.screens.CharacterScreen
import com.bd2toolsbox.ui.screens.CharacterSheet
import com.bd2toolsbox.ui.screens.GuideScreen
import com.bd2toolsbox.ui.screens.ModRoute
import com.bd2toolsbox.ui.screens.OnboardingScreen
import com.bd2toolsbox.ui.screens.WorkbenchSheet
import com.bd2toolsbox.ui.screens.buildCharacterEntries
import com.bd2toolsbox.ui.screens.buildSkinEntries
import com.bd2toolsbox.ui.theme.BD2ModManagerTheme
import com.bd2toolsbox.ui.viewmodel.GuideViewModel
import com.bd2toolsbox.ui.viewmodel.MainViewModel
import com.bd2toolsbox.utils.SafManager

/**
 * 根 UI 装配（T33）。
 *
 * 从 `MainActivity` 搬出来的那一大块：主题、壁纸背景层、Scaffold、三个顶层页面
 * 与全部弹层。**它只做装配** —— 业务状态都在 ViewModel，路由状态在
 * [TopLevelNavState]，需要 Android 系统的动作通过 [AppSystemHandoffs] 回调出去。
 *
 * 这样 `MainActivity` 回到「Android 入口 + 系统交接」：注册广播、边到边、
 * 启动 ViewModel、Shizuku 授权，然后把这一棵 UI 挂上去。
 */
@Stable
class AppSystemHandoffs(
    /** 拉起系统安装器装已下载的更新包（要 Activity 的 startActivity 与授权页）。 */
    val installUpdateApk: () -> Unit,
    /** 重查 Shizuku 并给即时反馈（要 Toast 与 Shizuku 静态门面）。 */
    val recheckShizuku: () -> Unit,
)

@Composable
fun AppRoot(
    viewModel: MainViewModel,
    guideViewModel: GuideViewModel,
    cdkViewModel: CdkViewModel,
    updateViewModel: UpdateViewModel,
    handoffs: AppSystemHandoffs,
) {
    val context = LocalContext.current
            // 取色开关、配色预设、壁纸都会让整个主题重组，所以在这一层收集
            val dynamicColor by viewModel.dynamicColor.collectAsStateWithLifecycle()
            val appTheme by viewModel.appTheme.collectAsStateWithLifecycle()
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val wallpaperUri by viewModel.wallpaperUri.collectAsStateWithLifecycle()
            val wallpaperScrim by viewModel.wallpaperScrim.collectAsStateWithLifecycle()

            BD2ModManagerTheme(
                themeMode = themeMode,
                dynamicColor = dynamicColor,
                theme = appTheme
                // T40：不再把 background/surface 抽透明。全局 ColorScheme 恒不透明，
                // 壁纸由下面的独立背景层展示，内容区用不透明底色。
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
                            context.contentResolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            )
                            viewModel.addModSourceDir(context, uri).let { added ->
                                // 选了个已记住目录的下级时列表不会变。不说一句的话，
                                // 用户只看到「点了没反应」，会以为选目录失败了。
                                if (!added) Toast.makeText(
                                    context,
                                    "这个目录已经在列表里（或已被上层目录覆盖）",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            // T62：媒体隔离标记没建成时如实告知（混合用途根被拒、
                            // 只读授权、provider 改写文件名等）。成功与「已存在」不出声。
                            viewModel.mediaExclusionNotice.value?.let { notice ->
                                Toast.makeText(context, notice, Toast.LENGTH_LONG).show()
                                viewModel.clearMediaExclusionNotice()
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
                    val granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                            PackageManager.PERMISSION_GRANTED
                    if (!granted) notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }

                // 底栏三项：「Mods」「攻略」「兑换码」。攻略与兑换码都是纯在线
                // 阅读，与 mod 处理互不牵扯，所以顶栏那排目录/筛选只在 Mods 出现；
                // 「设置」照旧不占 tab，挂在顶栏右上角齿轮 —— 齿轮在另两页也会
                // 出现（壁纸/配色是全局的）。
                //
                // T33：改用**可保存**的路由状态（见 ui/TopLevelNavigation.kt）——
                // 旋转/字体缩放/深浅切换后不再掉回 Mods 第一页、设置面板也不再被关掉。
                val nav = rememberTopLevelNavState()
                val currentTab = nav.tab.id
                val settingsOpen = nav.settingsOpen

                // 返回键只关「盖在最上面那一层」（设置面板）。三个 tab 是平级的，
                // 在别的 tab 上按返回直接退出 App 才符合直觉。
                BackHandler(enabled = nav.settingsOpen) { nav.closeSettings() }

                // 三个 tab 共用的「打开设置」动作：先刷一遍各缓存的占用数，
                // 弹层里的数值才是当下的
                val openSettings = {
                    viewModel.refreshBackupUsage()
                    viewModel.refreshPreviewCacheUsage()
                    viewModel.refreshSpineRuntimeUsage()
                    nav.openSettings()
                    Unit
                }

                // 「按角色」是新的默认视图：用户关心的是某个角色有没有 mod，
                // 而不是它处在哪个加工阶段。「全部」保留原来的平铺列表 ——
                // 批量多选转换要用它，认不出角色的 mod 也只能在那里看到。
                var byCharacter by rememberSaveable { mutableStateOf(true) }
                // 左右滑切换视图的累积位移
                var viewSwipeAccum by remember { mutableFloatStateOf(0f) }
                var openedCharacter by rememberSaveable { mutableStateOf<String?>(null) }
                var characterFilter by rememberSaveable { mutableStateOf(CharacterFilter.ALL) }

                val modSourceUri by viewModel.modSourceDirectoryUri.collectAsStateWithLifecycle()
                val modSourceDirs by viewModel.modSourceDirs.collectAsStateWithLifecycle()

                // T43：进行中任务的「收起」开关与进度源。声明在 Scaffold **之前** ——
                // 内容区顶部的重开横幅（Scaffold 的 content lambda 里）要看得见它们。
                val prepackProgress by viewModel.prepackProgress.collectAsStateWithLifecycle()
                val previewState by viewModel.previewState.collectAsStateWithLifecycle()
                var prepackHidden by rememberSaveable { mutableStateOf(false) }
                var previewHidden by rememberSaveable { mutableStateOf(false) }

                Scaffold(
                    // T40：有壁纸时根容器透明，让底下的 WallpaperBackdrop 在页头露出来；
                    // 无壁纸时直接用主题 background，确保顶栏与状态栏底色始终与当前明暗主题一致。
                    containerColor = if (wallpaperUri != null) {
                        androidx.compose.ui.graphics.Color.Transparent
                    } else {
                        MaterialTheme.colorScheme.background
                    },
                    contentColor = MaterialTheme.colorScheme.onBackground,
                    // T46：内容区自己避让系统栏与刘海。以前只有 topBar 里的
                    // statusBarsPadding()，内容区靠 Scaffold 的默认 padding 兜 ——
                    // 刘海（displayCutout）与横屏时的侧边系统栏没被算进去。
                    contentWindowInsets = WindowInsets.safeDrawing,
                    topBar = {
                        if (currentTab == 1) {
                            // 攻略页头部：只有标题与设置齿轮。齿轮与 Mods 页是同一个
                            // 入口 —— 壁纸、配色这些是全局观感，攻略页里也得能调。
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .statusBarsPadding()
                                    .padding(start = 16.dp, end = 8.dp),
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
                                .padding(start = 8.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            var folderMenu by remember { mutableStateOf(false) }
                            // 搜索状态全在 ViewModel 里（展开与否 / 查询词），顶栏只负责
                            // 「点图标展开、再点收起」。原先它是列表里的第二行，
                            // 现在挪到这儿 —— 顶栏本来就只放与当前列表强相关的东西。
                            val isSearchActive by viewModel.isSearchActive.collectAsStateWithLifecycle()
                            val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
                            // 搜索常驻顶栏：按角色视图点搜索时先切到全部再展开
                            val searchExpanded = isSearchActive
                            Box {
                                IconButton(
                                    onClick = { folderMenu = true },
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(Icons.Default.FolderOpen, contentDescription = "mod 文件夹", modifier = Modifier.size(22.dp))
                                }
                                DropdownMenu(
                                    modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.extraSmall),
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
                                        text = { Text("建立文件索引") },
                                        leadingIcon = { Icon(Icons.Default.FindInPage, null) },
                                        onClick = {
                                            folderMenu = false
                                            ensureNotificationPermission()
                                            viewModel.requestGameScan()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("导出日志") },
                                        leadingIcon = { Icon(Icons.Default.BugReport, null) },
                                        onClick = {
                                            folderMenu = false
                                            viewModel.exportLogs()
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
                                    modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.extraSmall),
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
                                                            context,
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
                            val unverifiedCount by viewModel.unverifiedModCount.collectAsStateWithLifecycle()
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

                            // 搜索：从列表的第二行上移到顶栏，收起时只占一个图标。
                            // 展开时输入框吃掉剩余宽度，图标变叉号，再点一次收起并清空
                            // 查询词（清空由 ViewModel 的 setSearchActive(false) 负责）。
                            if (true) {
                                if (searchExpanded) {
                                    Surface(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(40.dp),
                                        shape = RoundedCornerShape(16.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(horizontal = 16.dp),
                                            contentAlignment = Alignment.CenterStart
                                        ) {
                                            BasicTextField(
                                                value = searchQuery,
                                                onValueChange = viewModel::onSearchQueryChanged,
                                                modifier = Modifier.fillMaxWidth(),
                                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                                    color = MaterialTheme.colorScheme.onSurface
                                                ),
                                                singleLine = true,
                                                decorationBox = { innerTextField ->
                                                    if (searchQuery.isEmpty()) {
                                                        Text(
                                                            "按名称搜索...",
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            // 顶栏挤，窄屏上留给输入框的宽度可能不足一行
                                                            // 中文的高度固定 40.dp，让它断行会顶出圆角框，
                                                            // 宁可截断
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                    innerTextField()
                                                }
                                            )
                                        }
                                    }
                                    Spacer(Modifier.width(4.dp))
                                }
                                IconButton(
                                    onClick = {
                                    if (byCharacter) byCharacter = false
                                    viewModel.setSearchActive(!isSearchActive)
                                },
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isSearchActive) Icons.Default.Close
                                                      else Icons.Default.Search,
                                        contentDescription = if (isSearchActive) "收起搜索" else "搜索",
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }


                            // 搜索展开时输入框要吃掉整条剩余宽度；这颗 Spacer 若同时占着
                            // weight，两者会各分一半，输入框只剩几十 dp。所以收起时才放它。
                            if (!searchExpanded) Spacer(Modifier.weight(1f))

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
                                    nav.openSettings()
                                }
                            )
                        }
                    },
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = nav.tab == TopLevelTab.MODS,
                                onClick = { nav.select(TopLevelTab.MODS) },
                                icon = { Icon(Icons.Default.Extension, contentDescription = null) },
                                label = { Text("Mods") }
                            )
                            NavigationBarItem(
                                selected = nav.tab == TopLevelTab.GUIDES,
                                onClick = { nav.select(TopLevelTab.GUIDES) },
                                icon = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null) },
                                label = { Text("攻略") }
                            )
                            NavigationBarItem(
                                selected = nav.tab == TopLevelTab.CDK,
                                onClick = { nav.select(TopLevelTab.CDK) },
                                icon = { Icon(Icons.Default.CardGiftcard, contentDescription = null) },
                                label = { Text("兑换码") }
                            )
                        }
                    }
                    ) { padding ->
                        com.bd2toolsbox.ui.components.AppScreenSurface(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding)
                                // T46：软键盘弹出时把内容顶上去，否则底部的
                                // 「添加账号」「提交」这类输入与按钮会被键盘盖住。
                                // 用 imePadding 而不是 adjustResize：本 Activity 是
                                // edge-to-edge，窗口不会自己缩，得由 insets 驱动。
                                .imePadding(),
                            color = MaterialTheme.colorScheme.background
                        ) {
                            // T43：进行中的任务被收起后，这里给出**统一的重开入口**。
                            // 它只改展示，绝不取消任务或释放仍使用的 Binder。
                            val prepackRunning by viewModel.prepackProgress.collectAsStateWithLifecycle()
                            val previewRunning by viewModel.previewState.collectAsStateWithLifecycle()
                            val activeTitle = when {
                                prepackRunning != null -> "预解包进行中"
                                previewRunning is PreviewState.Preparing -> "预览准备中"
                                else -> null
                            }
                            val collapsed = when {
                                prepackRunning != null -> prepackHidden
                                previewRunning is PreviewState.Preparing -> previewHidden
                                else -> false
                            }
                            // T25：启动恢复处置的可见结论。此前 recoveryDispositions
                            // 只被算出来、没有任何界面消费 —— 用户永远看不到
                            // 「上次操作被打断 / 待核对 / 记录读不出来」。
                            // T32：初始化完成后把「自动检查更新」转交给 UpdateViewModel
                // （ViewModel 之间不互相注入）。
                val initDone by viewModel.initializationCompleted.collectAsStateWithLifecycle()
                LaunchedEffect(initDone) {
                    if (initDone) updateViewModel.checkForUpdates(auto = true)
                }

                val recovery by viewModel.recoveryDispositions.collectAsStateWithLifecycle()
                            var recoveryDismissed by rememberSaveable { mutableStateOf(false) }
                            if (!recoveryDismissed) {
                                RecoveryBanner(
                                    dispositions = recovery,
                                    onDismiss = { recoveryDismissed = true },
                                    // T25：正文一直让用户去用「核对并修复记录」，此前却没有入口。
                                    // 不在点击瞬间把 recoveryDismissed 置 true：修复成功后
                                    // recoveryDispositions 清空横幅会自然消失；若因未启动 Shizuku
                                    // 等原因失败，保留横幅以便用户启动 Shizuku 后再次点击。
                                    onReconcile = {
                                        viewModel.reconcilePendingInstalls()
                                    }
                                )
                            }
                            if (activeTitle != null && collapsed) {
                                TaskProgressPanel(
                                    title = activeTitle,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                    collapsed = true,
                                    onToggleCollapse = {
                                        prepackHidden = false
                                        previewHidden = false
                                    }
                                )
                            }
                            when (currentTab) {
                                1 -> GuideScreen(guideViewModel)
                                2 -> CdkScreen(cdkViewModel)
                                else -> Column(modifier = Modifier.fillMaxSize()) {
                                    val allMods by viewModel.modsList.collectAsStateWithLifecycle()

                                    // 左右滑切换 按角色 ↔ 全部：HorizontalPager 自带平滑翻页
                                    // 动画 + 手势，跟引导页同一套组件。0=按角色 1=全部。
                                    val pagerState = rememberPagerState(
                                        initialPage = if (byCharacter) 0 else 1,
                                        pageCount = { 2 }
                                    )
                                    // 页面滑完同步 byCharacter（FilterChip 的高亮跟着走）
                                    LaunchedEffect(pagerState.currentPage) {
                                        byCharacter = pagerState.currentPage == 0
                                    }
                                    // FilterChip 点击时驱动 pager 滑过去（动画）
                                    LaunchedEffect(byCharacter) {
                                        val target = if (byCharacter) 0 else 1
                                        if (pagerState.currentPage != target) {
                                            pagerState.animateScrollToPage(target)
                                        }
                                    }
                                    HorizontalPager(
                                        state = pagerState,
                                        modifier = Modifier.fillMaxSize()
                                    ) { page ->
                                    if (page == 0) {
                                        val characters by viewModel.allCharacterNames.collectAsStateWithLifecycle()
                                        val npcCharacters by viewModel.npcCharacterNames.collectAsStateWithLifecycle()
                                        val avatarSyncFailed by viewModel.avatarSyncFailed.collectAsStateWithLifecycle()
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
                                    } else {  // page == 1，全部视图
                                        // T28：Route 负责采集与装配，Screen 只读 state + 发 events。
                                        ModRoute(
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
                                                viewModel.installConvertedBundles(context, mods)
                                            },
                                            onRequestNotification = { ensureNotificationPermission() },
                                            onBackupManageRequest = {
                                                viewModel.refreshBackupUsage()
                                                showBackupManage = true
                                            }
                                        )
                                    }
                                    }
                                }
                            }
                        }
                    }

                // 角色卡片
                openedCharacter?.let { name ->
                    val allMods by viewModel.modsList.collectAsStateWithLifecycle()
                    val skins = remember(name, allMods) { buildSkinEntries(name, allMods) }
                    CharacterSheet(
                        characterName = name,
                        entries = skins,
                        onPreview = { mod -> viewModel.prepareAndShowPreview(context, mod) },
                        onInstall = { e ->
                            openedCharacter = null
                            ensureNotificationPermission()
                            viewModel.installSingle(context, e.converted, e.source)
                        },
                        onInstallBatch = { picked ->
                            openedCharacter = null
                            ensureNotificationPermission()
                            // 和列表页的 FAB 同一套判断：全是已转换产物就直拷进游戏目录，
                            // 只要掺了 PC 源就整批走转换 —— 那条路本来就会复用已有产物。
                            val allConverted = picked.all { it.onlyConverted || it.hasConverted }
                            if (allConverted) {
                                viewModel.installConvertedBundles(context, picked.map { it.converted!! })
                            } else {
                                viewModel.startRepackFor(context, picked.map { it.primary })
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
                        updateViewModel = updateViewModel,
                        onUninstallAll = { nav.closeSettings(); viewModel.prepareUninstallAll() },
                        onUnpackTool = { nav.closeSettings(); showUnpackDialog = true },
                        onRecheckShizuku = { handoffs.recheckShizuku() },
                        onPrepack = {
                            nav.closeSettings()
                            ensureNotificationPermission()
                            viewModel.preparePrepack()
                        },
                        onPickWallpaper = {
                            // 弹层不关：选完图回来能立刻看到效果、还能接着拖滑块
                            wallpaperLauncher.launch(arrayOf("image/*"))
                        },
                        onReplayOnboarding = {
                            nav.closeSettings()
                            viewModel.replayOnboarding()
                        },
                        onDismiss = { nav.closeSettings() },
                        onManageBackups = {
                            nav.closeSettings()
                            viewModel.refreshBackupUsage()
                            showBackupManage = true
                        },
                    )
                }

                val lostDirs by viewModel.lostSourceDirsNotice.collectAsStateWithLifecycle()
                LostSourceDirsDialog(
                    dirs = lostDirs,
                    onDismiss = { viewModel.dismissLostSourceDirsNotice() },
                    onReselect = { modSourceDirLauncher.launch(Unit) }
                )

                val ledgerNotice by viewModel.ledgerResetNotice.collectAsStateWithLifecycle()
                LedgerResetDialog(
                    notice = ledgerNotice,
                    onDismiss = { viewModel.dismissLedgerResetNotice() },
                    onReconcile = { viewModel.reconcilePendingInstalls() }
                )

                val overwritePlan by viewModel.convertedOverwritePlan.collectAsStateWithLifecycle()
                ConvertedOverwriteDialog(
                    plan = overwritePlan,
                    onConfirm = { viewModel.confirmConvertedOverwrite(context) },
                    onDismiss = { viewModel.dismissConvertedOverwritePlan() }
                )

                val prepackPlan by viewModel.prepackPlan.collectAsStateWithLifecycle()
                PrepackPlanDialog(
                    plan = prepackPlan,
                    onConfirm = { viewModel.startPrepack() },
                    onDismiss = { viewModel.dismissPrepackPlan() }
                )

                // 检查更新：只在「设置 → 高级」里手动点，结果落在这一个弹窗上。
                // 「去下载」跳系统浏览器到 release 页，App 自己不下载不安装。
                val latestRelease by updateViewModel.latestRelease.collectAsStateWithLifecycle()
                UpdateDialog(
                    release = latestRelease,
                    onDownload = { release ->
                        // 应用内下载：架构按本机自动选，进度看通知栏，
                        // 完成后由下载广播接回并弹「安装」框
                        updateViewModel.downloadUpdate(release)
                        updateViewModel.dismissUpdate()
                    },
                    onIgnore = { version -> updateViewModel.ignoreUpdateVersion(version) },
                    onDismiss = { updateViewModel.dismissUpdate() }
                )

                // 无骨架 mod 的静态贴图查看（预览的兜底路径）
                val staticImages by viewModel.staticPreviewImages.collectAsStateWithLifecycle()
                ImageViewerDialog(
                    imagePaths = staticImages,
                    onDismiss = { viewModel.dismissStaticPreview() }
                )

                // 下载完成后的安装确认。安装要拉系统安装器与未知来源授权，
                // 都需要 Activity 上下文，动作在 Activity 层做。
                val updateApkReady by updateViewModel.updateApkReady.collectAsStateWithLifecycle()
                UpdateReadyDialog(
                    apkName = updateApkReady,
                    onInstall = { handoffs.installUpdateApk() },
                    onDismiss = { updateViewModel.dismissUpdateReady() }
                )

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

                val uninstallPlan by viewModel.uninstallPlan.collectAsStateWithLifecycle()
                UninstallAllDialog(
                    plan = uninstallPlan,
                    onConfirm = { viewModel.uninstallAll(context) },
                    onDismiss = { viewModel.dismissUninstallPlan() }
                )

                if (showBackupManage) {
                    val backupOn by viewModel.backupOriginals.collectAsStateWithLifecycle()
                    val backupUsage by viewModel.backupUsage.collectAsStateWithLifecycle()
                    val purgeResult by viewModel.backupPurgeResult.collectAsStateWithLifecycle()
                    BackupManageDialog(
                        enabled = backupOn,
                        usage = backupUsage,
                        onToggle = { viewModel.setBackupOriginals(it) },
                        onConfirmClear = { confirmedFingerprint ->
                            // T44：确认后**复核目标集合** —— 确认期间新产生的备份不删，
                            // 指纹变了就把这次确认作废（界面会退回确认步骤重新问一次）。
                            val current = viewModel.backupFingerprint()
                            if (confirmedFingerprint.isNotEmpty() &&
                                confirmedFingerprint != current
                            ) {
                                android.util.Log.i(
                                    "BackupPurge",
                                    "目标集合在确认后发生变化，已作废本次确认"
                                )
                            } else {
                                viewModel.clearBackups()
                            }
                        },
                        blocker = viewModel.backupPurgeBlocker(),
                        result = purgeResult,
                        onAcknowledgeResult = { viewModel.clearBackupPurgeResult() },
                        onDismiss = { showBackupManage = false }
                    )
                }

                if (showDirsManage) {
                    val allModsForDirs by viewModel.modsList.collectAsStateWithLifecycle()
                    ModSourceDirsDialog(
                        dirs = modSourceDirs,
                        mods = allModsForDirs,
                        // 框不关：加完一个还能接着加下一个，也能立刻看到它带进来几个 mod
                        onAdd = { modSourceDirLauncher.launch(Unit) },
                        onRemove = { viewModel.removeModSourceDir(it) },
                        onDismiss = { showDirsManage = false }
                    )
                }

                // T43：收起只改展示 —— 准备任务照跑，横幅里有重开入口。
                LaunchedEffect(previewState) {
                    if (previewState !is PreviewState.Preparing) previewHidden = false
                }
                PreviewProgressDialog(
                    state = if (previewHidden) PreviewState.Idle else previewState,
                    onDismiss = { viewModel.dismissPreviewState() },
                    onHide = { previewHidden = true }
                )

                val showInstallDialog by viewModel.showInstallDialog.collectAsStateWithLifecycle()
                val moveState by viewModel.moveState.collectAsStateWithLifecycle()

                if (showInstallDialog) {
                    val installJobs by viewModel.installJobs.collectAsStateWithLifecycle()
                    val finalResult by viewModel.finalInstallResult.collectAsStateWithLifecycle()
                    ParallelInstallDialog(
                        installJobs = installJobs,
                        finalResult = finalResult,
                        moveState = moveState,
                        onMoveToGame = { viewModel.moveFilesToGame() },
                        onRecheckShizuku = { handoffs.recheckShizuku() },
                        onDismiss = { viewModel.closeInstallDialog() }
                    )
                }

                val uninstallState by viewModel.uninstallState.collectAsStateWithLifecycle()
                UninstallDialog(
                    state = uninstallState,
                    moveState = moveState,
                    onMoveToGame = { viewModel.moveFilesToGame() },
                    onRecheckShizuku = { handoffs.recheckShizuku() },
                    onDismiss = { viewModel.resetUninstallState() }
                )

                // 首次使用的引导。放在最后 = 盖在所有内容（含各种对话框）之上：
                // 新用户第一眼该看到的是「怎么用」，而不是背后那些空列表和提示框。
                val onboardingDone by viewModel.onboardingDone.collectAsStateWithLifecycle()
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
                        viewModel.initiateUninstall(context, hash)
                        uninstallConfirmationTarget = null
                    },
                    onDismiss = {
                        uninstallConfirmationTarget = null
                    }
                )

                RemoveModConfirmationDialog(
                    mod = removeModTarget,
                    onConfirm = { mod ->
                        viewModel.removeSingleMod(context, mod)
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
                        viewModel.deleteModFolder(context, mod) { _, msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                        deleteFolderTarget = null
                    },
                    onDismiss = { deleteFolderTarget = null }
                )

                if (showUnpackDialog) {
                    val unpackState by viewModel.unpackState.collectAsStateWithLifecycle()
                    val unpackInputFile by viewModel.unpackInputFile.collectAsStateWithLifecycle()
                    UnpackDialog(
                        unpackState = unpackState,
                        inputFile = unpackInputFile,
                        onSetInputFile = { viewModel.setUnpackInputFile(it) },
                        onInitiateUnpack = { viewModel.initiateUnpack(context) },
                        onResetState = { viewModel.resetUnpackState() },
                        onDismiss = { showUnpackDialog = false }
                    )
                }

                // 「合并 Spine」的 UI 入口已移除（转换时 repacker 本就会自动合并超出的贴图页），
                // 所以这里不再挂 MergeSpineDialog。ViewModel 侧的 initiateMerge/mergeState 保留着，
                // 将来若要放进高级菜单可直接复用。

                val showVersionMismatch by viewModel.showVersionMismatchWarning.collectAsStateWithLifecycle()
                if (showVersionMismatch) {
                    VersionMismatchWarningDialog(
                        onDismiss = { viewModel.dismissVersionMismatchWarning() }
                    )
                }

                val bundleScanState by viewModel.bundleScanState.collectAsStateWithLifecycle()
                BundleScanDialog(
                    state = bundleScanState,
                    onConfirm = { viewModel.confirmBundleScan() },
                    onDismiss = { viewModel.dismissBundleScan() }
                )
            }
}

/** 顶栏右上角的设置齿轮。三个 tab 共用同一个入口，点击行为由所在 tab 注入。 */
@Composable
internal fun SettingsGear(onClick: () -> Unit = {}) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(Icons.Default.Settings, contentDescription = "设置", modifier = Modifier.size(22.dp))
    }
}
