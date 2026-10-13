@file:OptIn(ExperimentalFoundationApi::class)

package com.bd2toolsbox

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.provider.Settings
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import dagger.hilt.android.AndroidEntryPoint
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bd2toolsbox.data.model.ModInfo
import com.bd2toolsbox.data.model.PreviewState
import com.bd2toolsbox.ui.components.RecoveryBanner
import com.bd2toolsbox.ui.components.TaskProgressPanel
import com.bd2toolsbox.ui.AppRoot
import com.bd2toolsbox.ui.AppSystemHandoffs
import com.bd2toolsbox.ui.TopLevelTab
import com.bd2toolsbox.ui.components.WallpaperBackdrop
import com.bd2toolsbox.ui.rememberTopLevelNavState
import com.bd2toolsbox.ui.dialogs.*
import com.bd2toolsbox.ui.screens.CharacterFilter
import com.bd2toolsbox.ui.screens.CharacterScreen
import com.bd2toolsbox.ui.screens.CharacterSheet
import com.bd2toolsbox.ui.screens.CdkScreen
import com.bd2toolsbox.ui.screens.GuideScreen
import com.bd2toolsbox.ui.screens.ModRoute
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

// T16：接入 Hilt 对象图。当前不注入任何字段（构造注入留给 T17），但登记为受支持
// 入口后，Compose 的 hiltViewModel() 与后续构造注入才能在本 Activity 生效。
@AndroidEntryPoint
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
                id == updateRepository.currentDownloadId()
            ) {
                updateViewModel.onUpdateDownloadComplete(id)
            }
        }
    }

    private val viewModel: MainViewModel by viewModels()
    private val guideViewModel: GuideViewModel by viewModels()
    private val cdkViewModel: com.bd2toolsbox.feature.cdk.CdkViewModel by viewModels()
    private val updateViewModel: com.bd2toolsbox.feature.update.UpdateViewModel by viewModels()

    // T20：字段注入，不再经静态 UpdateRepository.get(context)
    @javax.inject.Inject lateinit var updateRepository: UpdateRepository

    private val SHIZUKU_PERMISSION_REQUEST_CODE = 1001

    /**
     * T19：Activity 自己持有的一份占用。旧行为是 `onDestroy` 时无条件 unbind，会切断
     * 仍在 installScope/ScanService 里跑着的任务的连接。现在只释放自己这一份，
     * 底层连接是否真正断开由 [ShizukuManager] 内部的占用计数决定。
     */
    private var shizukuConnectionToken: Any? = null

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
        // 更新包下载完成的系统广播。EXPORTED 而非 NOT_EXPORTED：部分国产
        // ROM 对 NOT_EXPORTED 接收器的系统广播投递不可靠（实测过弹框不出现
        // 的场景），DownloadManager 广播本身无敏感数据，EXPORTED 更稳。
        ContextCompat.registerReceiver(
            this,
            updateDownloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
        // 手势条沉浸：状态栏/导航栏透明，内容画到系统栏后面（各内容区的
        // 避让交给各自的 statusBarsPadding / NavigationBar / Scaffold）
        enableEdgeToEdge()

        viewModel.initialize()
        // 攻略板块的仓库现已构造注入（T17），这里顺手把分类树拉起来（进 tab 前就绪）
        guideViewModel.initialize()
        // T19：Activity 存活期间自己占用一份连接；即使这一份释放，连接也不会断，
        // 只要还有其他占用者（后台安装/扫描任务）。
        shizukuConnectionToken = ShizukuManager.acquireConnection()

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
            AppRoot(
                viewModel = viewModel,
                guideViewModel = guideViewModel,
                cdkViewModel = cdkViewModel,
                updateViewModel = updateViewModel,
                handoffs = AppSystemHandoffs(
                    installUpdateApk = { installUpdateApk() },
                    recheckShizuku = { recheckShizukuWithFeedback(viewModel) },
                )
            )
        }
    }

    override fun onRestart() {
        super.onRestart()
        viewModel.refreshModSourcesOnReturn()
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
        // T19：旧行为是 isFinishing 时无条件 unbind（会切断仍在 installScope/ScanService 里
        // 跑着的任务的连接），现在改成无条件释放 Activity 自己那一份占用——它只减去
        // 本实例的占用，后台任务自己持有的 token 不受影响，真正解绑只在全部 token 都
        // 释放时发生。
        //
        // 故意不沿用旧的 isFinishing 分支：那个优化建立在“unbind 是唯一直接操作”上，
        // 换成计数模型后会引发 token 泄漏：旋转屏幕时旧 Activity 实例销毁，如果这里不释放，
        // 它持有的 token 引用就永久丢失了，没有任何入口能把它 remove 掉，占用计数
        // 会每次旋转都多出一份永久不可回收的占用，最终连接永远不会真正解绑。
        // 没有后台任务时旋转会多一次解绑/重绑，但正确性优先于这点开销。
        shizukuConnectionToken?.let { ShizukuManager.releaseConnection(it) }
        shizukuConnectionToken = null
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
        val file = updateRepository.downloadedApkFile() ?: run {
            Toast.makeText(this, "安装包不存在，请重新检查更新", Toast.LENGTH_SHORT).show()
            return
        }
        if (!packageManager.canRequestPackageInstalls()) {
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


