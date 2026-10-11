package com.bd2toolsbox.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.bd2toolsbox.core.fileaccess.GameFileAccess
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
import com.bd2toolsbox.feature.install.DirectMoveCommitHost
import com.bd2toolsbox.feature.install.InstallExecutor
import com.bd2toolsbox.feature.install.UninstallExecutor
import com.bd2toolsbox.service.ShizukuManager
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Real VM, staging, journal and ledger; only Android/network/Binder boundaries are replaced. */
@OptIn(ExperimentalCoroutinesApi::class)
class MoveInstallWiringTest {
    @get:Rule val temp = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private lateinit var scope: CoroutineScope
    private lateinit var vm: MainViewModel
    private lateinit var repo: InstalledModRepository
    private lateinit var access: GameFileAccess
    private lateinit var executor: InstallExecutor
    private lateinit var uninstallExecutor: UninstallExecutor
    private lateinit var engine: com.bd2toolsbox.core.engine.ModEngine
    private lateinit var shared: File
    private lateinit var game: File
    private lateinit var cache: File
    private var beforeCopy: suspend () -> Unit = {}
    private var copyCalls = 0

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        mockkStatic(android.util.Log::class, Environment::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        val downloads = temp.newFolder("Download")
        shared = File(downloads, "Shared").apply { mkdir() }
        game = temp.newFolder("game")
        cache = temp.newFolder("external-cache")
        val files = temp.newFolder("files")
        every { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) } returns downloads
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns files
        every { context.externalCacheDir } returns cache
        every { context.getExternalFilesDir(null) } returns temp.newFolder("external-files")
        every { context.applicationContext } returns context
        repo = InstalledModRepository(context)
        scope = CoroutineScope(SupervisorJob() + dispatcher)
        val character = CharacterRepository(context)
        engine = mockk<com.bd2toolsbox.core.engine.ModEngine>(relaxed = true)
        every { engine.getBundleMeta(any(), any(), any()) } returns null
        // T22：真正的提交链在 InstallExecutor 里，它通过可注入的 GameFileAccess 写入，
        // 不再直接调 ShizukuManager 静态桥。这里只替换 Binder/文件边界。
        access = mockk()
        val token = Any()
        every { access.acquire() } returns token
        every { access.release(any()) } just Runs
        coEvery { access.installConvertedBundle(any(), any()) } coAnswers {
            copyCalls++
            beforeCopy()
            File(firstArg<String>()).copyRecursively(File(game, secondArg<String>()), overwrite = true)
            true to "copied"
        }
        executor = InstallExecutor(repo, access)
        uninstallExecutor = UninstallExecutor(repo)
        vm = MainViewModel(
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
            DirectMoveCommitHost(executor),
            uninstallExecutor,
            com.bd2toolsbox.feature.mods.DirectScanRunHost(mockk(relaxed = true)),
            mockk(relaxed = true), // scan executor is not used by move-install tests
            mockk(relaxed = true), // prepack executor is not used by move-install tests
        )
        setField("installScope", scope)

        // Host fileKey is null on Windows. Substitute the actual platform File ID,
        // not the staging algorithm, its cleanup or the receipt verification.
        mockkObject(StagingDirectoryLease.Companion)
        every { StagingDirectoryLease.NIO_FILE_KEY } returns JvmTestStagingIdentity
        mockkObject(ShizukuManager)
        // T19: acquire/releaseConnection touch real Shizuku (Binder init) unless stubbed;
        // JVM tests never bind for real, so the token itself is an opaque marker here.
        every { ShizukuManager.acquireConnection() } returns Any()
        every { ShizukuManager.releaseConnection(any()) } just Runs
        every { ShizukuManager.isRunning() } returns true
        coEvery { ShizukuManager.listGameBundles() } returns null
        // T19: production `moveFilesToGame` no longer calls the whole-tree
        // `moveDownloadToGame` endpoint (removed from ShizukuManager) — every real
        // commit path goes through per-target `installConvertedBundle` in InstallExecutor.
        jobs().value = listOf(job("selected"))
        data(shared, "selected", "approved")
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
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String): T = MainViewModel::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(vm) as T
    private fun jobs() = field<MutableStateFlow<List<InstallJob>>>("_installJobs")
    private fun data(root: File, bundle: String, content: String) = File(root, "$bundle/hash/__data")
        .apply { requireNotNull(parentFile).mkdirs(); writeText(content) }
    private fun job(bundle: String, restore: Boolean = false): InstallJob {
        val uri = mockk<Uri>()
        every { uri.toString() } returns "content://mods/$bundle"
        val mod = ModInfo("Original name", "Character", "Costume", "type", true, uri,
            bundle, true, ResolutionState.KNOWN, resolvedFamilyKey = "family")
        return InstallJob(RepackJob(bundle, if (restore) emptyList() else listOf(mod)),
            JobStatus.Finished("$bundle/hash/__data"))
    }
    private fun record(bundle: String) = InstalledModRecord("content://mods/$bundle", "Old name",
        "family", bundle, "HD", false, 1L)
    private suspend fun joinMove() { scope.coroutineContext[Job]!!.children.toList().joinAll() }
    private fun markBackupFailure() {
        field<MutableList<FailedJobInfo>>("unfinishedBackups").add(FailedJobInfo("selected", "disk full"))
    }

    private fun plantPendingRecovery() {
        val target = com.bd2toolsbox.core.storage.JournalMoveFacts.target(shared, "selected", "selected/hash/__data",
            listOf("content://mods/selected"), listOf(com.bd2toolsbox.core.storage.LedgerRecord(
                "content://mods/selected", "Recovered name", "family", "selected", "HD", false, 123L)))
        assertNull(repo.beginSubmit("pending", listOf(target)))
        assertNull(repo.markTargetReplacing())
        repo.releaseSubmit("pending")
    }

    @Test fun twoRecoveryClicksCannotStartOverlappingObservationsOnTheSameRepository() = runTest(dispatcher) {
        plantPendingRecovery()
        val entered = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val reads = java.util.concurrent.atomic.AtomicInteger()
        coEvery { ShizukuManager.copyGameDataForRecovery(any(), any(), any()) } coAnswers {
            if (reads.incrementAndGet() == 1) entered.complete(Unit) else secondEntered.complete(Unit)
            resume.await()
            val destination = File(thirdArg<String>())
            requireNotNull(destination.parentFile).mkdirs()
            destination.writeText("approved")
            true
        }
        vm.reconcilePendingInstalls()
        entered.await()
        vm.reconcilePendingInstalls()
        val second = scope.coroutineContext[Job]!!.children.toList().last()
        val overlapped = try {
            kotlinx.coroutines.selects.select<Boolean> {
                second.onJoin { false }
                secondEntered.onAwait { true }
            }
        } finally {
            resume.complete(Unit)
            joinMove()
        }
        assertFalse("Duplicate recovery must not enter the observation callback", overlapped)
        assertEquals(1, reads.get())
        assertEquals("Recovered name", repo.load()["content://mods/selected"]?.modName)
        assertFalse(repo.journalFile().exists())
    }

    @Test fun cancellationDuringRecoveryIsPropagatedAndDoesNotStrandTheLease() = runTest(dispatcher) {
        plantPendingRecovery()
        val before = repo.journalFile().readBytes()
        coEvery { ShizukuManager.copyGameDataForRecovery(any(), any(), any()) } throws CancellationException("cancel observation")
        vm.reconcilePendingInstalls()
        val task = scope.coroutineContext[Job]!!.children.toList().single()
        task.join()
        assertTrue("Cancellation must not become an ordinary ReadFailed result", task.isCancelled)
        assertArrayEquals(before, repo.journalFile().readBytes())
        assertTrue(repo.load().isEmpty())
        assertNull(repo.beginRecovery())
        repo.releaseRecovery()
    }

    @Test fun onlySelectedOutputReachesGameAndUnrelatedDownloadsSurvive() = runTest(dispatcher) {
        data(shared, "unrelated", "KEEP")
        vm.moveFilesToGame()
        joinMove()
        assertTrue(vm.moveState.value.toString(), vm.moveState.value is MoveState.Success)
        assertEquals("approved", File(game, "selected/hash/__data").readText())
        assertFalse(File(game, "unrelated").exists())
        assertEquals("KEEP", File(shared, "unrelated/hash/__data").readText())
        assertFalse(File(shared, "selected/hash/__data").exists())
        assertEquals(1, repo.load().size)
    }

    @Test fun closingTheDialogDuringCopyCannotChangeTheLedgerOrSelectedOptions() = runTest(dispatcher) {
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        beforeCopy = { entered.complete(Unit); resume.await() }
        vm.moveFilesToGame()
        entered.await()
        vm.closeInstallDialog()
        field<MutableStateFlow<String>>("_selectedQuality").value = "SD"
        field<MutableStateFlow<Boolean>>("_useAstc").value = true
        resume.complete(Unit)
        joinMove()
        assertTrue(vm.moveState.value.toString(), vm.moveState.value is MoveState.Success)
        val actual = repo.load()["content://mods/selected"]
        assertNotNull("Closing UI must not erase the in-flight install facts", actual)
        assertEquals("Original name", actual!!.modName)
        assertEquals("HD", actual.quality)
        assertFalse(actual.usedAstc)
        assertTrue(actual.installedAt > 0)
        assertEquals("approved", File(game, "selected/hash/__data").readText())
    }

    @Test fun backupFailureStopsTheRealCommitWithoutDeletingConversionOutput() = runTest(dispatcher) {
        data(game, "selected", "ORIGINAL")
        markBackupFailure()
        vm.moveFilesToGame()
        joinMove()
        assertTrue(vm.moveState.value.toString(), vm.moveState.value is MoveState.Failed)
        assertEquals("ORIGINAL", File(game, "selected/hash/__data").readText())
        assertEquals("approved", File(shared, "selected/hash/__data").readText())
        assertTrue(repo.load().isEmpty())
        assertFalse(repo.journalFile().exists())
        assertEquals(0, copyCalls)
    }

    @Test fun disablingAutomaticBackupDoesNotReenableItOrBlockTheCommit() = runTest(dispatcher) {
        markBackupFailure()
        field<MutableStateFlow<Boolean>>("_backupOriginals").value = false
        vm.moveFilesToGame()
        joinMove()
        assertTrue(vm.moveState.value.toString(), vm.moveState.value is MoveState.Success)
        assertEquals("approved", File(game, "selected/hash/__data").readText())
        assertFalse(vm.backupOriginals.value)
    }

    @Test fun bookkeepingFailureKeepsSourcesAndBlocksRetry() = runTest(dispatcher) {
        val failingRepo = spyk(repo)
        every { failingRepo.putAll(any()) } returns LedgerWrite.Failed(OperationError(OperationErrorKind.IO, "disk full"))
        setField("installedModRepository", failingRepo)
        // T22：真正的提交走 installExecutor，它持有自己的一份仓储引用；
        // 只换 VM 的字段够不着它，必须把 executor 手里的也换成同一个故障仓储。
        InstallExecutor::class.java.getDeclaredField("repository").apply { isAccessible = true }.set(executor, failingRepo)
        vm.moveFilesToGame()
        joinMove()
        assertTrue(vm.moveState.value.toString(), vm.moveState.value is MoveState.Failed)
        assertEquals("approved", File(game, "selected/hash/__data").readText())
        assertTrue("Pending bookkeeping must retain its source output", File(shared, "selected/hash/__data").isFile)
        assertTrue(repo.checkRecovery() is OperationJournalStore.RecoveryVerdict.PendingReconciliation)
        vm.moveFilesToGame()
        joinMove()
        assertEquals(1, copyCalls)
        assertTrue(repo.load().isEmpty())
    }

    @Test fun partialBatchRestoreNeverClearsUnrestoredLedgerRecords() = runTest(dispatcher) {
        assertTrue(repo.putAll(listOf(record("selected"), record("unrestored"))) is LedgerWrite.Written)
        jobs().value = listOf(job("selected", restore = true),
            job("unrestored", restore = true).copy(status = JobStatus.Failed("download failed", "failed")))
        setField("pendingRecordClear", true)
        vm.moveFilesToGame()
        joinMove()
        assertTrue(vm.moveState.value.toString(), vm.moveState.value is MoveState.Success)
        assertEquals(setOf("content://mods/unrestored"), repo.load().keys)
    }

    @Test fun sourceChangedDuringCommitIsPreservedWhileFrozenBytesReachGame() = runTest(dispatcher) {
        beforeCopy = { File(shared, "selected/hash/__data").writeText("new user output") }
        vm.moveFilesToGame()
        joinMove()
        assertTrue(vm.moveState.value.toString(), vm.moveState.value is MoveState.Success)
        assertEquals("approved", File(game, "selected/hash/__data").readText())
        assertEquals("new user output", File(shared, "selected/hash/__data").readText())
        assertTrue((vm.moveState.value as MoveState.Success).message.contains("保留"))
    }

    @Test fun aLaterBundleCopyFailureRetainsTheEntirePendingBatch() = runTest(dispatcher) {
        jobs().value = listOf(job("selected"), job("second"))
        data(shared, "second", "second output")
        coEvery { access.installConvertedBundle(any(), "second") } returns (false to "Binder disconnected")
        vm.moveFilesToGame()
        joinMove()
        assertTrue(vm.moveState.value.toString(), vm.moveState.value is MoveState.Failed)
        assertEquals("approved", File(game, "selected/hash/__data").readText())
        assertFalse(File(game, "second").exists())
        assertTrue(File(shared, "selected/hash/__data").exists())
        assertTrue(File(shared, "second/hash/__data").exists())
        assertTrue(repo.load().isEmpty())
        assertTrue(repo.checkRecovery() is OperationJournalStore.RecoveryVerdict.PendingReconciliation)
    }

    /** A same-size tamper of a later target, after the first copy, must never reach the game. */
    @Test fun tamperingALaterStagedTargetStopsBeforeItsCopy() = runTest(dispatcher) {
        jobs().value = listOf(job("selected"), job("second"))
        data(shared, "second", "second output")
        coEvery { access.installConvertedBundle(any(), "selected") } coAnswers {
            copyCalls++
            File(firstArg<String>()).copyRecursively(File(game, "selected"), overwrite = true)
            val staged = File(File(firstArg<String>()).parentFile, "second/hash/__data")
            staged.writeText("SECOND OUTPUT")
            true to "copied"
        }
        vm.moveFilesToGame()
        joinMove()
        assertTrue(vm.moveState.value.toString(), vm.moveState.value is MoveState.Failed)
        assertEquals("approved", File(game, "selected/hash/__data").readText())
        assertFalse(File(game, "second").exists())
        assertEquals(1, copyCalls)
        assertTrue(repo.load().isEmpty())
        assertTrue(repo.checkRecovery() is OperationJournalStore.RecoveryVerdict.PendingReconciliation)
    }

    /** Hash work grows linearly with the batch: each target hashed a bounded number of times. */
    @Test fun commitHashesEachStagedTargetABoundedNumberOfTimes() = runTest(dispatcher) {
        val names = (1..6).map { "b$it" }
        jobs().value = names.map { job(it) }
        names.forEach { data(shared, it, "content-$it") }
        val digests = mutableMapOf<String, Int>()
        mockkObject(com.bd2toolsbox.core.storage.BoundStagingTree)
        every { com.bd2toolsbox.core.storage.BoundStagingTree.snapshot(any(), any()) } answers {
            val select = secondArg<(String) -> Boolean>()
            callOriginal().also { snap ->
                snap.files.keys.filter(select).forEach { path ->
                    val bundle = path.substringBefore('/')
                    digests[bundle] = (digests[bundle] ?: 0) + 1
                }
            }
        }
        vm.moveFilesToGame()
        joinMove()
        assertTrue(vm.moveState.value.toString(), vm.moveState.value is MoveState.Success)
        assertEquals(names.toSet(), digests.keys)
        // manifest (1) + pre-submit whole check (1) + before/after its own copy (2) = 4 per target,
        // independent of batch size; the old per-target whole-tree check was n + 3 (= 9 here).
        assertTrue(digests.toString(), digests.values.all { it <= 4 })
    }

    @Test fun aConvertedBatchCannotMoveUnrelatedDownloadLeftovers() = runTest(dispatcher) {
        jobs().value = listOf(job("selected").copy(producesDownload = false))
        vm.moveFilesToGame()
        joinMove()
        assertTrue(vm.moveState.value.toString(), vm.moveState.value is MoveState.Failed)
        assertTrue(game.listFiles()!!.isEmpty())
        assertEquals("approved", File(shared, "selected/hash/__data").readText())
        assertEquals(0, copyCalls)
    }

    /** The direct-install marker belongs to that batch only; it must not block a later repack batch. */
    @Test fun anEarlierConvertedBatchDoesNotBlockALaterRepackBatch() = runTest(dispatcher) {
        jobs().value = listOf(job("selected").copy(producesDownload = false))
        vm.moveFilesToGame()          // rejected for the direct batch
        joinMove()
        assertTrue(vm.moveState.value.toString(), vm.moveState.value is MoveState.Failed)
        vm.closeInstallDialog()
        jobs().value = listOf(job("selected"))
        vm.moveFilesToGame()
        joinMove()
        assertTrue(vm.moveState.value.toString(), vm.moveState.value is MoveState.Success)
        assertEquals("approved", File(game, "selected/hash/__data").readText())
        assertEquals(1, copyCalls)
    }

    @Test fun statusRefreshFailureCannotUndoACommittedSuccess() = runTest(dispatcher) {
        every { engine.getBundleMeta(any(), any(), any()) } throws java.io.IOException("metadata unavailable")
        vm.moveFilesToGame()
        joinMove()
        assertEquals("approved", File(game, "selected/hash/__data").readText())
        assertEquals(1, repo.load().size)
        assertFalse(repo.journalFile().exists())
        assertTrue(vm.moveState.value.toString(), vm.moveState.value is MoveState.Success)
    }

    @Test fun cancellationKeepsIntentAndSourcesAndReleasesOwnedStaging() = runTest(dispatcher) {
        beforeCopy = { throw CancellationException("cancel at Binder boundary") }
        vm.moveFilesToGame()
        joinMove()
        assertTrue(vm.moveState.value.toString(), vm.moveState.value is MoveState.Failed)
        assertEquals("approved", File(shared, "selected/hash/__data").readText())
        assertTrue(repo.checkRecovery() is OperationJournalStore.RecoveryVerdict.PendingReconciliation)
        assertTrue(File(cache, "move_staging_owned").listFiles().orEmpty().isEmpty())
        assertNull(repo.beginRecovery())
        repo.releaseRecovery()
    }
}
