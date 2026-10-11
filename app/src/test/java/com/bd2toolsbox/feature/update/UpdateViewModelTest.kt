package com.bd2toolsbox.feature.update

import android.content.Context
import android.content.SharedPreferences
import com.bd2toolsbox.data.repository.UpdateRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * T32：更新检查与下载调度的语义（JVM）。
 *
 * 守的是「什么时候打扰用户」这条线：
 * - 自动检查静默：连不上、已最新都不弹；**忽略过的版本**也不弹；
 * - 手动检查把「连不上 / 已最新」都说出来，且**不受忽略记录限制**；
 * - 已下过同一个包时不再重复下载，直接弹安装。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private var stored: String? = null

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        mockkStatic(android.util.Log::class)
        // Toast 在 JVM 单测里是未实现的桩：本类只在失败/提示路径上用它，
        // 断言的是状态而不是弹了几次 toast，所以整体 stub 掉。
        mockkStatic(android.widget.Toast::class)
        every { android.widget.Toast.makeText(any(), any<String>(), any<Int>()) } returns mockk(relaxed = true)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0

        stored = null
        editor = mockk(relaxed = true)
        every { editor.putString(any(), any()) } answers {
            stored = secondArg()
            editor
        }
        prefs = mockk(relaxed = true)
        every { prefs.getString(any(), any()) } answers { stored ?: secondArg() }
        every { prefs.edit() } returns editor
        context = mockk(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
    }

    @After
    fun teardown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    private fun release(version: String) = UpdateRepository.Release(
        version = version, notes = "", htmlUrl = "https://example.invalid/$version"
    )

    private fun repo(installed: String = "0.2.16"): UpdateRepository {
        val repo = mockk<UpdateRepository>(relaxed = true)
        every { repo.installedVersion() } returns installed
        every { repo.isNewerThanInstalled(any()) } answers { firstArg<String>() != installed }
        return repo
    }

    @Test
    fun autoCheckStaysSilentWhenUpToDate() = runTest(dispatcher) {
        val repo = repo()
        coEvery { repo.fetchLatest() } returns release("0.2.16")
        val vm = UpdateViewModel(context, repo, dispatcher)
        vm.checkForUpdates(auto = true)
        dispatcher.scheduler.advanceUntilIdle()
        assertNull("已最新时自动检查不得弹更新框", vm.latestRelease.value)
    }

    @Test
    fun autoCheckStaysSilentWhenOffline() = runTest(dispatcher) {
        val repo = repo()
        coEvery { repo.fetchLatest() } returns null
        val vm = UpdateViewModel(context, repo, dispatcher)
        vm.checkForUpdates(auto = true)
        dispatcher.scheduler.advanceUntilIdle()
        assertNull(vm.latestRelease.value)
    }

    @Test
    fun manualCheckSurfacesANewerRelease() = runTest(dispatcher) {
        val repo = repo()
        coEvery { repo.fetchLatest() } returns release("0.2.17")
        val vm = UpdateViewModel(context, repo, dispatcher)
        vm.checkForUpdates(auto = false)
        dispatcher.scheduler.advanceUntilIdle()
        assertNotNull("手动检查必须把新版拿出来", vm.latestRelease.value)
        assertEquals("0.2.17", vm.latestRelease.value!!.version)
    }

    @Test
    fun ignoredVersionDoesNotPopupOnAutoButManualStillShowsIt() = runTest(dispatcher) {
        val repo = repo()
        coEvery { repo.fetchLatest() } returns release("0.2.17")
        val vm = UpdateViewModel(context, repo, dispatcher)

        vm.ignoreUpdateVersion("0.2.17")
        assertEquals("忽略记录必须落盘", "0.2.17", stored)

        vm.checkForUpdates(auto = true)
        dispatcher.scheduler.advanceUntilIdle()
        assertNull("忽略过的版本自动检查不得再弹", vm.latestRelease.value)

        vm.checkForUpdates(auto = false)
        dispatcher.scheduler.advanceUntilIdle()
        assertNotNull("手动检查不受忽略记录限制", vm.latestRelease.value)
    }

    @Test
    fun dismissingClearsThePendingRelease() = runTest(dispatcher) {
        val repo = repo()
        coEvery { repo.fetchLatest() } returns release("0.2.17")
        val vm = UpdateViewModel(context, repo, dispatcher)
        vm.checkForUpdates(auto = false)
        dispatcher.scheduler.advanceUntilIdle()
        assertNotNull(vm.latestRelease.value)
        vm.dismissUpdate()
        assertNull(vm.latestRelease.value)
    }

    @Test
    fun alreadyDownloadedApkSkipsTheDownloadAndOffersInstall() = runTest(dispatcher) {
        val repo = repo()
        coEvery { repo.isApkDownloaded(any()) } returns true
        coEvery { repo.downloadedApkFile() } returns java.io.File("/tmp/app-arm64-v8a-release.apk")
        val vm = UpdateViewModel(context, repo, dispatcher)
        vm.downloadUpdate(release("0.2.17"))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            "已下过的包必须直接弹安装，不再重复下载几十 MB",
            "app-arm64-v8a-release.apk",
            vm.updateApkReady.value
        )
        io.mockk.coVerify(exactly = 0) { repo.startApkDownload(any()) }
    }

    @Test
    fun downloadCompleteReportsSuccessOnlyForTheCurrentDownload() = runTest(dispatcher) {
        val repo = repo()
        coEvery { repo.currentDownloadId() } returns 42L
        coEvery { repo.isDownloadSuccessful(42L) } returns true
        coEvery { repo.downloadedApkFile() } returns java.io.File("/tmp/update.apk")
        val vm = UpdateViewModel(context, repo, dispatcher)

        // 不是当前下载的 id：忽略，不谎报完成
        vm.onUpdateDownloadComplete(7L)
        dispatcher.scheduler.advanceUntilIdle()
        assertNull(vm.updateApkReady.value)

        vm.onUpdateDownloadComplete(42L)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("update.apk", vm.updateApkReady.value)
    }
}
