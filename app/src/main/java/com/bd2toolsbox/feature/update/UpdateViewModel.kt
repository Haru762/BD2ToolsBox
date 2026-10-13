package com.bd2toolsbox.feature.update

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bd2toolsbox.data.repository.UpdateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 更新检查与下载调度的宿主（T32）。
 *
 * 从 `MainViewModel`（当时 5300 行）里独立出来：更新是一条**与 mod 列表无关**的
 * 支线 —— 它有自己的状态（检查中 / 有新版 / 安装包就绪）、自己的持久化
 * （忽略过的版本号）和自己的失败面（连不上 GitHub、DownloadManager 被 ROM 掐）。
 * 混在 mod 状态机里只会让两边的失败互相污染。
 *
 * 架构选择（本机 ABI → 该下哪个包）仍在 [UpdateRepository] + `ApkSelector` 里，
 * 本类只负责调度与状态。**实际安装**由 Activity 做（要 FileProvider + 系统安装意图，
 * 见 `MainActivity.installUpdateApk`）。
 */
@HiltViewModel
class UpdateViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: UpdateRepository,
    /** T17 的注入调度器：纯逻辑测试可换成 StandardTestDispatcher，不必真起线程池。 */
    @com.bd2toolsbox.di.IoDispatcher private val io: kotlinx.coroutines.CoroutineDispatcher
) : ViewModel() {

    private val _latestRelease = MutableStateFlow<UpdateRepository.Release?>(null)

    /** 有新版本时的发布信息；null = 没有待处理的更新。 */
    val latestRelease: StateFlow<UpdateRepository.Release?> = _latestRelease.asStateFlow()

    private val _updateApkReady = MutableStateFlow<String?>(null)

    /** 下载完成的安装包文件名；非 null 时界面弹「立即安装」。 */
    val updateApkReady: StateFlow<String?> = _updateApkReady.asStateFlow()

    private val _updateChecking = MutableStateFlow(false)
    val updateChecking: StateFlow<Boolean> = _updateChecking.asStateFlow()

    /**
     * 检查更新。
     *
     * @param auto 自动检查（冷启动后一次）时**静默**：连不上、已最新都不打扰用户，
     *   且忽略过的版本不再弹。手动检查会把这两种情况都说出来。
     */
    fun checkForUpdates(auto: Boolean = false) {
        if (_updateChecking.value) return
        _updateChecking.value = true
        viewModelScope.launch {
            try {
                val release = repository.fetchLatest()
                when {
                    release == null ->
                        if (!auto) toast("检查更新失败：连不上 GitHub，请检查网络或代理")
                    !repository.isNewerThanInstalled(release.version) ->
                        if (!auto) toast("已是最新版本（当前 ${repository.installedVersion()}）")
                    // 自动检查：被忽略的版本不再弹（手动点「检查更新」不受此限）
                    auto && release.version == ignoredUpdateVersion() -> Unit
                    else -> _latestRelease.value = release
                }
            } catch (e: Exception) {
                Log.w(TAG, "检查更新失败", e)
                if (!auto) toast("检查更新失败：${e.message ?: "未知原因"}")
            } finally {
                _updateChecking.value = false
            }
        }
    }

    /** 用户点「忽略此版本」后记录：该版本号不再自动弹更新框。 */
    fun ignoreUpdateVersion(version: String) {
        _latestRelease.value = null
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putString(KEY_IGNORED, version)?.apply()
    }

    private fun ignoredUpdateVersion(): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.getString(KEY_IGNORED, "").orEmpty()

    /** 关掉更新弹窗。下次再点「检查更新」重新拉。 */
    fun dismissUpdate() {
        _latestRelease.value = null
    }

    fun dismissUpdateReady() {
        _updateApkReady.value = null
    }

    /**
     * 应用内下载更新包。架构选择在仓库层按本机 ABI 自动做；排队成功 toast 提醒
     * 进度看通知栏，结果由 DownloadManager 的完成广播接回（见 MainActivity 的
     * 接收器 → [onUpdateDownloadComplete]）。
     */
    fun downloadUpdate(release: UpdateRepository.Release) {
        viewModelScope.launch {
            // 已经下过这个包就直接弹安装，不再重复下载几十 MB
            if (withContext(io) { repository.isApkDownloaded(release) }) {
                _updateApkReady.value =
                    withContext(io) { repository.downloadedApkFile()?.name }
                return@launch
            }
            val started = try {
                withContext(io) { repository.startApkDownload(release) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "下载更新失败", e)
                toast("下载更新失败：${e.message ?: "未知原因"}")
                return@launch
            }
            if (started != null) {
                toast("已开始下载 ${started.name}，进度见通知栏")
                // 轮询兜底：广播接收器在部分 ROM 上不可靠（NOT_EXPORTED 改
                // EXPORTED 已修一层），这里再直接查状态——双保险，谁先到谁
                // 触发弹框；已有 _updateApkReady 非空守卫不会弹两次。
                viewModelScope.launch(io) {
                    while (_updateApkReady.value == null) {
                        delay(POLL_INTERVAL_MS)
                        val id = repository.currentDownloadId()
                        if (id != -1L && repository.isDownloadSuccessful(id)) {
                            repository.downloadedApkFile()?.let { f ->
                                _updateApkReady.value = f.name
                                break
                            }
                        }
                        // 下载不在了（被取消或超时很久）就停
                        if (!repository.hasActiveDownload() && !repository.isDownloadSuccessful(id)) {
                            break
                        }
                    }
                }
            } else if (withContext(io) { repository.hasActiveDownload() }) {
                toast("已有下载在进行中")
            } else {
                toast("这个版本没有可下载的安装包，请到发布页手动下载")
            }
        }
    }

    /** DownloadManager 完成广播的入口（MainActivity 的接收器调用）。 */
    fun onUpdateDownloadComplete(id: Long) {
        viewModelScope.launch {
            if (id != withContext(io) { repository.currentDownloadId() }) return@launch
            val ok = withContext(io) { repository.isDownloadSuccessful(id) }
            if (ok) {
                _updateApkReady.value =
                    withContext(io) { repository.downloadedApkFile()?.name }
                toast("更新包下载完成")
            } else {
                toast("更新包下载失败，请检查网络或代理后重试")
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val TAG = "UpdateViewModel"
        const val PREFS = "update_check"
        const val KEY_IGNORED = "ignored_version"
        const val POLL_INTERVAL_MS = 3000L
    }
}
