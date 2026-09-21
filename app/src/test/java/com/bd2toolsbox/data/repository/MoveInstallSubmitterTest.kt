package com.bd2toolsbox.data.repository

import android.content.Context
import com.bd2toolsbox.core.model.OperationError
import com.bd2toolsbox.core.model.OperationErrorKind
import com.bd2toolsbox.core.storage.LedgerWrite
import com.bd2toolsbox.core.storage.JournalEntry
import com.bd2toolsbox.core.storage.JournalRead
import com.bd2toolsbox.core.storage.JournalStage
import com.bd2toolsbox.core.storage.JournalTarget
import com.bd2toolsbox.core.storage.OperationJournalStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MoveInstallSubmitterTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var context: Context
    private lateinit var repo: InstalledModRepository
    private lateinit var submitter: MoveInstallSubmitter

    @Before
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0

        filesDir = temp.newFolder("files")
        context = mockk<Context>()
        every { context.filesDir } returns filesDir
        repo = InstalledModRepository(context)
        submitter = MoveInstallSubmitter(repo)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun target(bundle: String = "bundle-a", uri: String = "content://sample/uri-a") =
        JournalTarget(
            bundleName = bundle,
            hashDir = "hash-$bundle",
            dataBytes = 4096L,
            modUris = listOf(uri),
            recoveryFacts = com.bd2toolsbox.core.storage.JournalRecoveryFacts(
                formatVersion = 1,
                expectedContentSha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                ledgerUpserts = listOf(
                    com.bd2toolsbox.core.storage.LedgerRecord(
                        modUri = uri,
                        modName = "mod",
                        familyKey = "family",
                        snapshotTargetHash = bundle,
                        quality = "HD",
                        usedAstc = false,
                        installedAt = 1000L
                    )
                )
            )
        )

    private fun readJournal() = OperationJournalStore(repo.journalFile()).read()

    private fun journalStages(): List<String> = when (val read = readJournal()) {
        is JournalRead.Loaded -> read.entries.map { it.stage }
        else -> emptyList()
    }

    @Test
    fun aPendingReconciliationBlocksSubmitWithoutInvokingAnyCallback() = runBlocking {
        repo.journalFile().writeText(
            """{"schemaVersion":1,"entries":[
                 {"operationId":"old","stage":"target_replaced","atMillis":1,
                  "targets":[{"bundleName":"bundle-a","hashDir":"hash-a","dataBytes":1,"modUris":["content://sample/uri-a"]}]}
               ]}"""
        )
        assertTrue(repo.checkRecovery() is OperationJournalStore.RecoveryVerdict.PendingReconciliation)
        var moveCalled = false
        var commitCalled = false

        val result = submitter.submit(
            operationId = "op-new",
            targets = listOf(target()),
            moveFiles = { moveCalled = true; true to "won't happen" },
            commitLedger = { commitCalled = true; null }
        )

        assertFalse(moveCalled)
        assertFalse(commitCalled)
        assertFalse(result.filesReplaced)
        assertNotNull(result.error)
        assertTrue(result.requiresReconciliation)
    }

    @Test
    fun anInFlightSubmitBlocksAnotherSubmitWithoutInvokingAnyCallback() = runBlocking {
        assertNull(repo.beginSubmit("op-inflight", listOf(target())))
        var moveCalled = false
        var commitCalled = false

        val result = submitter.submit(
            operationId = "op-2",
            targets = listOf(target()),
            moveFiles = { moveCalled = true; true to "won't happen" },
            commitLedger = { commitCalled = true; null }
        )

        assertFalse(moveCalled)
        assertFalse(commitCalled)
        assertFalse(result.filesReplaced)
        assertNotNull(result.error)
    }

    @Test
    fun aFalseMoveKeepsTheIntentAndDoesNotCommit() = runBlocking {
        var commitCalled = false
        val result = submitter.submit(
            operationId = "op-1",
            targets = listOf(target()),
            moveFiles = { false to "failed" },
            commitLedger = { commitCalled = true; null }
        )

        assertFalse(result.filesReplaced)
        assertFalse(commitCalled)
        assertTrue(result.requiresReconciliation)
        assertTrue(journalStages().contains(JournalStage.TARGET_REPLACING.id))
        assertFalse(journalStages().contains(JournalStage.TARGET_REPLACED.id))
        
        val newRepo = InstalledModRepository(context)
        assertNotNull(newRepo.beginSubmit("op-new", listOf(target())))
        try {
            assertNull(newRepo.beginRecovery())
        } finally {
            newRepo.releaseRecovery()
        }
    }

    @Test
    fun anExceptionDuringMoveReleasesTheLockButKeepsTheIntentAndRethrows() {
        val boom = RuntimeException("boom")
        try {
            runBlocking {
                submitter.submit(
                    operationId = "op-1",
                    targets = listOf(target()),
                    moveFiles = { throw boom },
                    commitLedger = { null }
                )
            }
            fail()
        } catch (e: RuntimeException) {
            assertEquals(boom, e)
        }

        assertTrue(journalStages().contains(JournalStage.TARGET_REPLACING.id))
        assertTrue(repo.checkRecovery() is OperationJournalStore.RecoveryVerdict.PendingReconciliation)
        
        val newRepo = InstalledModRepository(context)
        assertNotNull(newRepo.beginSubmit("op-new", listOf(target())))
        try {
            assertNull(newRepo.beginRecovery())
        } finally {
            newRepo.releaseRecovery()
        }
    }
    
    @Test
    fun aCancellationDuringMoveReleasesTheLockButKeepsTheIntentAndRethrows() {
        try {
            runBlocking {
                submitter.submit(
                    operationId = "op-1",
                    targets = listOf(target()),
                    moveFiles = { throw CancellationException("cancel") },
                    commitLedger = { null }
                )
            }
            fail()
        } catch (e: CancellationException) {
        }
        assertTrue(journalStages().contains(JournalStage.TARGET_REPLACING.id))
        
        val newRepo = InstalledModRepository(context)
        assertNotNull(newRepo.beginSubmit("op-new", listOf(target())))
        try {
            assertNull(newRepo.beginRecovery())
        } finally {
            newRepo.releaseRecovery()
        }
    }

    @Test
    fun aLedgerCommitFailureReportsFilesReplacedAndKeepsTheWindow() = runBlocking {
        val ledgerError = OperationError(
            kind = OperationErrorKind.IO,
            message = "error",
            target = "bundle-a"
        )
        val result = submitter.submit(
            operationId = "op-1",
            targets = listOf(target()),
            moveFiles = { true to "done" },
            commitLedger = { ledgerError }
        )

        assertTrue(result.filesReplaced)
        assertNotNull(result.error)
        assertTrue(result.requiresReconciliation)
        assertTrue(journalStages().contains(JournalStage.TARGET_REPLACED.id))
        assertTrue(repo.checkRecovery() is OperationJournalStore.RecoveryVerdict.PendingReconciliation)
    }

    @Test
    fun aSuccessfulSubmitReplacesFilesThenCommitsAndClearsTheJournal() = runBlocking {
        val order = mutableListOf<String>()
        val result = submitter.submit(
            operationId = "op-1",
            targets = listOf(target()),
            moveFiles = {
                order.add("move")
                assertTrue(journalStages().contains(JournalStage.TARGET_REPLACING.id))
                true to "done"
            },
            commitLedger = {
                order.add("commit")
                null
            }
        )

        assertTrue(result.filesReplaced)
        assertNull(result.error)
        assertFalse(result.requiresReconciliation)
        assertEquals(listOf("move", "commit"), order)
        assertEquals(JournalRead.Absent, readJournal())
    }
    
    @Test
    fun emptyTargetsAreAllowedForBatchUninstallSemantics() = runBlocking {
        val order = mutableListOf<String>()
        val result = submitter.submit(
            operationId = "op-empty",
            targets = emptyList(),
            moveFiles = {
                order.add("move")
                true to "done"
            },
            commitLedger = {
                order.add("commit")
                null
            }
        )

        assertTrue(result.filesReplaced)
        assertNull(result.error)
        assertFalse(result.requiresReconciliation)
        assertEquals(listOf("move", "commit"), order)
        assertEquals(JournalRead.Absent, readJournal())
    }
    @Test
    fun aStageFailureProvesZeroMoveAndZeroLedger() = runBlocking {
        val spyRepo = io.mockk.spyk(repo)
        val spySubmitter = MoveInstallSubmitter(spyRepo)
        
        io.mockk.coEvery { spyRepo.markStaged(any()) } returns OperationError(OperationErrorKind.IO, "stage error")
        
        var moveCalled = false
        var commitCalled = false
        val result = spySubmitter.submit(
            operationId = "op-1",
            targets = listOf(target()),
            moveFiles = { moveCalled = true; true to "done" },
            commitLedger = { commitCalled = true; null }
        )

        assertFalse(result.filesReplaced)
        assertFalse(moveCalled)
        assertFalse(commitCalled)
        assertNotNull(result.error)
        assertFalse(result.requiresReconciliation)
    }

    @Test
    fun aMarkTargetReplacingFailureProvesZeroMoveAndZeroLedger() = runBlocking {
        val spyRepo = io.mockk.spyk(repo)
        val spySubmitter = MoveInstallSubmitter(spyRepo)
        
        val intentError = OperationError(OperationErrorKind.IO, "intent error")
        io.mockk.coEvery { spyRepo.markTargetReplacing(any()) } returns intentError
        
        var moveCalled = false
        var commitCalled = false
        val result = spySubmitter.submit(
            operationId = "op-1",
            targets = listOf(target()),
            moveFiles = { moveCalled = true; true to "done" },
            commitLedger = { commitCalled = true; null }
        )

        assertFalse(moveCalled)
        assertFalse(commitCalled)
        assertFalse(result.filesReplaced)
        assertEquals(intentError, result.error)
        assertFalse(result.requiresReconciliation)
    }

    @Test
    fun aFailureOfMarkTargetReplacedProvesFilesReplacedTrueAndNoLedger() = runBlocking {
        val spyRepo = io.mockk.spyk(repo)
        val spySubmitter = MoveInstallSubmitter(spyRepo)
        
        io.mockk.coEvery { spyRepo.markTargetReplaced(any()) } returns OperationError(OperationErrorKind.IO, "replace error")
        
        var moveCalled = false
        var commitCalled = false
        val result = spySubmitter.submit(
            operationId = "op-1",
            targets = listOf(target()),
            moveFiles = { moveCalled = true; true to "done" },
            commitLedger = { commitCalled = true; null }
        )

        assertTrue(result.filesReplaced)
        assertTrue(moveCalled)
        assertFalse(commitCalled)
        assertNotNull(result.error)
        assertTrue(result.requiresReconciliation)
    }

    @Test
    fun aHappyPathActualRepositoryPutOfARecordPersistsAndClearsJournal() = runBlocking {
        val result = submitter.submit(
            operationId = "op-happy",
            targets = listOf(target()),
            moveFiles = { true to "done" },
            commitLedger = {
                val record = com.bd2toolsbox.data.model.InstalledModRecord(
                    modUri = "content://sample/uri-a",
                    modName = "mod",
                    familyKey = "family",
                    snapshotTargetHash = "bundle-a",
                    quality = "HD",
                    usedAstc = false,
                    installedAt = 1000L
                )
                val writeResult = repo.putAll(listOf(record))
                if (writeResult is com.bd2toolsbox.core.storage.LedgerWrite.Failed) {
                    writeResult.error
                } else {
                    null
                }
            }
        )

        assertTrue(result.filesReplaced)
        assertNull(result.error)
        assertFalse(result.requiresReconciliation)
        assertEquals(JournalRead.Absent, readJournal())
        assertEquals(1, repo.load().size)
    }
}
