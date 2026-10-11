package com.bd2toolsbox.ui.viewmodel

import android.content.Context
import com.bd2toolsbox.feature.install.DirectMoveCommitHost
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.bd2toolsbox.core.model.OperationError
import com.bd2toolsbox.core.model.OperationErrorKind
import com.bd2toolsbox.core.storage.JvmTestStagingIdentity
import com.bd2toolsbox.core.storage.LedgerWrite
import com.bd2toolsbox.core.storage.OperationJournalStore
import com.bd2toolsbox.core.storage.StagingDirectoryLease
import com.bd2toolsbox.data.model.*
import com.bd2toolsbox.data.repository.BundleBackupRepository
import com.bd2toolsbox.data.repository.CharacterRepository
import com.bd2toolsbox.data.repository.InstalledModRepository
import com.bd2toolsbox.data.repository.ModRepository
import com.bd2toolsbox.data.repository.PreviewCacheRepository
import com.bd2toolsbox.service.InstallService
import com.bd2toolsbox.service.ShizukuManager
import com.chaquo.python.Python
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Audit item 2 at the real entry point: direct converted install must not report success
 * when the game file was replaced but bookkeeping did not complete. Real VM, staging,
 * submitter, journal and ledger; only Android/Binder/Python/service edges are replaced.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DirectInstallWiringTest {
    @get:Rule val temp = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private lateinit var scope: CoroutineScope
    private lateinit var vm: MainViewModel
    private lateinit var repo: InstalledModRepository
    private lateinit var game: File
    private lateinit var context: Context
    private val products = mutableMapOf<String, File>()

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        game = temp.newFolder("game")
        val files = temp.newFolder("files")
        context = mockk(relaxed = true)
        every { context.filesDir } returns files
        every { context.externalCacheDir } returns temp.newFolder("external-cache")
        every { context.cacheDir } returns temp.newFolder("cache")
        every { context.applicationContext } returns context
        repo = InstalledModRepository(context)
        scope = CoroutineScope(SupervisorJob() + dispatcher)
        val character = CharacterRepository(context)
        val engine = mockk<com.bd2toolsbox.core.engine.ModEngine>(relaxed = true)
        every { engine.getBundleMeta(any(), any(), any()) } returns null
        vm = spyk(MainViewModel(
            SavedStateHandle(),
            context,
            character,
            ModRepository(context, character),
            repo,
            BundleBackupRepository(context),
            PreviewCacheRepository(context),
            engine,
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            DirectMoveCommitHost(mockk(relaxed = true)),
            mockk(relaxed = true), // uninstall executor is not used by direct-install tests
            com.bd2toolsbox.feature.mods.DirectScanRunHost(mockk(relaxed = true)),
            mockk(relaxed = true), // scan executor is not used by direct-install tests
            mockk(relaxed = true), // prepack executor is not used by direct-install tests
        ), recordPrivateCalls = true)
        // SAF/primary-storage source read is an Android boundary: copy the host product dir.
        every { vm["copyConvertedSourceInto"](any<Context>(), any<ModInfo>(), any<File>()) } answers {
            val mod = secondArg<ModInfo>()
            products.getValue(mod.targetHash!!).copyRecursively(thirdArg<File>(), overwrite = true)
            true
        }
        setField("appContext", context)
        setField("installedModRepository", repo)
        setField("installScope", scope)
        @Suppress("UNCHECKED_CAST")
        (MainViewModel::class.java.getDeclaredField("_backupOriginals").apply { isAccessible = true }
            .get(vm) as kotlinx.coroutines.flow.MutableStateFlow<Boolean>).value = false

        mockkObject(StagingDirectoryLease.Companion)
        every { StagingDirectoryLease.NIO_FILE_KEY } returns JvmTestStagingIdentity
        mockkObject(ShizukuManager, InstallService.Companion)
        // T19: acquire/releaseConnection touch real Shizuku (Binder init) unless stubbed;
        // JVM tests never bind for real, so the token itself is an opaque marker here.
        every { ShizukuManager.acquireConnection() } returns Any()
        every { ShizukuManager.releaseConnection(any()) } just Runs
        every { InstallService.start(any(), any()) } returns true
        every { InstallService.update(any(), any(), any(), any()) } just Runs
        every { InstallService.stop(any()) } just Runs
        every { ShizukuManager.isRunning() } returns true
        coEvery { ShizukuManager.listGameBundles() } returns null
        mockkStatic(Python::class)
        every { Python.isStarted() } returns false
        coEvery { ShizukuManager.installConvertedBundle(any(), any()) } coAnswers {
            File(firstArg<String>()).copyRecursively(File(game, secondArg<String>()), overwrite = true)
            true to "copied"
        }
    }

    @After fun cleanup() {
        scope.cancel()
        vm.viewModelScope.cancel()
        unmockkAll()
        Dispatchers.resetMain()
    }

    private fun setField(name: String, value: Any) {
        MainViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(vm, value)
    }

    /** A converted product directory on the host (what the user's SAF folder would contain). */
    private fun product(bundle: String): ModInfo {
        val dir = temp.newFolder("product-$bundle")
        File(dir, "hash/__data").apply { requireNotNull(parentFile).mkdirs(); writeText("converted $bundle") }
        products[bundle] = dir
        val uri = mockk<Uri>()
        every { uri.toString() } returns "content://mods/$bundle"
        return ModInfo("Mod $bundle", "Character", "Costume", "type", true, uri,
            bundle, true, ResolutionState.KNOWN, resolvedFamilyKey = "family",
            kind = ModKind.CONVERTED_BUNDLE, targetHash = bundle, convertedHashDir = "hash")
    }

    private suspend fun TestScope.install(targets: List<ModInfo>) {
        val m = MainViewModel::class.java.getDeclaredMethod(
            "doInstallConvertedBundles", Context::class.java, List::class.java)
        m.isAccessible = true
        m.invoke(vm, context, targets)
        advanceUntilIdle()
        scope.coroutineContext[Job]!!.children.toList().joinAll()
        advanceUntilIdle()
    }

    @Test fun replacedButUnrecordedIsNotReportedAsInstalled() = runTest(dispatcher) {
        val failingRepo = spyk(repo)
        every { failingRepo.putAll(any()) } returns LedgerWrite.Failed(OperationError(OperationErrorKind.IO, "disk full"))
        setField("installedModRepository", failingRepo)
        install(listOf(product("a")))
        val state = vm.moveState.value
        assertTrue(state.toString(), state is MoveState.Failed)
        assertTrue(state.toString(), (state as MoveState.Failed).error.contains("请先核对"))
        assertFalse(state.error, state.error.startsWith("已装入"))
        val result = vm.finalInstallResult.value!!
        assertEquals(0, result.successfulJobs)
        assertEquals(1, result.failedJobs)
        assertTrue(File(game, "a/hash/__data").isFile)   // the file did change...
        assertTrue(repo.load().isEmpty())                 // ...but was not recorded
        assertTrue(repo.checkRecovery() is OperationJournalStore.RecoveryVerdict.PendingReconciliation)
    }

    @Test fun partialDirectInstallReportsWhatIsLive() = runTest(dispatcher) {
        coEvery { ShizukuManager.installConvertedBundle(any(), "b") } returns (false to "Binder disconnected")
        install(listOf(product("a"), product("b")))
        val state = vm.moveState.value as MoveState.Failed
        assertTrue(state.error, state.error.startsWith("已装入 1 个"))
        assertTrue(state.error, state.error.contains("1 个未装入"))
        assertEquals(1, vm.finalInstallResult.value!!.successfulJobs)
        assertEquals(listOf("content://mods/a"), repo.load().keys.toList())
    }
}
