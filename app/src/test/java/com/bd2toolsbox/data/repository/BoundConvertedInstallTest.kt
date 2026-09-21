package com.bd2toolsbox.data.repository

import android.content.Context
import com.bd2toolsbox.core.model.OperationError
import com.bd2toolsbox.core.model.OperationErrorKind
import com.bd2toolsbox.core.storage.*
import com.bd2toolsbox.data.model.InstalledModRecord
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * 准备器 + 提交器 + 真实文件 journal/ledger 的集成测试。
 * 仅 Android Context.filesDir/Log 是桩；目标是宿主临时文件，不是 Android/Shizuku 验收。
 * new Repository 明确只是同进程磁盘重读，不声称杀进程/重启。
 */
class BoundConvertedInstallTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var repo: InstalledModRepository
    private lateinit var gameData: File
    private val record = LedgerRecord("uri-a", "Alice", "family-a", "bundle-a", "HD", false, 123456L)

    @Before fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0
        val filesDir = temp.newFolder("files")
        context = mockk()
        every { context.filesDir } returns filesDir
        repo = InstalledModRepository(context)
        gameData = File(temp.newFolder("game"), "__data").apply { writeText("original") }
    }
    @After fun tearDown() = unmockkAll()

    private fun prepare(records: List<LedgerRecord> = listOf(record)): PreparedConvertedInstall {
        val preparation = stagingForJvm(temp.newFolder()).prepare("bundle-a", "hash-a", records) {
            File(it, "hash-a").mkdirs()
            File(it, "hash-a/__data").writeText("prepared-mod")
            true
        }
        assertTrue("Preparation must succeed: $preparation", preparation is ConvertedInstallPreparation.Ready)
        return (preparation as ConvertedInstallPreparation.Ready).prepared
    }
    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun journal() = (OperationJournalStore(repo.journalFile()).read() as JournalRead.Loaded).entries
    private fun toInstalled(r: LedgerRecord) = InstalledModRecord(
        r.modUri, r.modName, r.familyKey, r.snapshotTargetHash, r.quality, r.usedAstc, r.installedAt
    )

    @Test fun persistedIntentAndLedgerDescribeOnlyTheBoundBytesAndFrozenRecords() = runBlocking {
        val input = mutableListOf(record)
        val prepared = prepare(input)
        input[0] = record.copy(quality = "CHANGED", usedAstc = true, installedAt = 999L)
        var copies = 0
        prepared.use {
            val result = ConvertedInstallSubmitter(repo).submit("bound-success", it.target, {
                val entries = journal()
                assertEquals(listOf("planned", "staged", "target_replacing"), entries.map { e -> e.stage })
                entries.forEach { e -> assertEquals(it.target, e.targets.single()) }
                assertEquals(sha(it.dataFile), entries.last().targets.single().recoveryFacts!!.expectedContentSha256)
                assertNull(it.verifyUnchanged())
                copies++
                it.dataFile.copyTo(gameData, overwrite = true)
                true to "copied"
            }, {
                assertEquals("target_replaced", journal().last().stage)
                assertEquals("prepared-mod", gameData.readText())
                val result = repo.putAll(it.target.recoveryFacts!!.ledgerUpserts.map(::toInstalled))
                (result as? LedgerWrite.Failed)?.error
            })
            assertTrue(result.filesReplaced)
            assertNull(result.error)
            assertFalse(result.requiresReconciliation)
            assertEquals(1, copies)
            assertEquals(toInstalled(record), repo.load()[record.modUri])
            assertEquals(it.target.recoveryFacts!!.expectedContentSha256, sha(gameData))
            assertEquals(JournalRead.Absent, OperationJournalStore(repo.journalFile()).read())
        }
        assertFalse(prepared.sourceDir.exists())
    }

    @Test fun fileReplacedThenLedgerFailureKeepsCompleteFactsAcrossDiskReloadAndBlocksReplay() = runBlocking {
        val prepared = prepare()
        val target = prepared.target
        var copies = 0
        prepared.use {
            val result = ConvertedInstallSubmitter(repo).submit("bound-ledger-failure", it.target, {
                copies++
                it.dataFile.copyTo(gameData, overwrite = true)
                true to "copied"
            }, { OperationError(OperationErrorKind.IO, "injected ledger failure") })
            assertTrue(result.filesReplaced)
            assertNotNull(result.error)
            assertTrue(result.requiresReconciliation)
        }
        assertFalse(prepared.sourceDir.exists())
        assertEquals("prepared-mod", gameData.readText())
        val journalBefore = repo.journalFile().readBytes()
        val reloaded = InstalledModRepository(context) // submitter has explicitly released the lease.
        assertTrue(reloaded.checkRecovery() is OperationJournalStore.RecoveryVerdict.PendingReconciliation)
        assertEquals(target, journal().last().targets.single())
        assertEquals(target.recoveryFacts!!.expectedContentSha256, sha(gameData))
        assertEquals(listOf(record), journal().last().targets.single().recoveryFacts!!.ledgerUpserts)
        assertTrue(reloaded.load().isEmpty())
        val retry = ConvertedInstallSubmitter(reloaded).submit("must-not-replay", target, {
            copies++
            gameData.writeText("REPLAY")
            true to "unexpected"
        }, { fail("ledger callback must not run"); null })
        assertNotNull(retry.error)
        assertTrue(retry.requiresReconciliation)
        assertEquals(1, copies)
        assertArrayEquals(journalBefore, repo.journalFile().readBytes())
        assertEquals("prepared-mod", gameData.readText())
    }

    @Test fun cancellationAfterActualWriteKeepsIntentAndCleansOnlyOwnedStaging() = runBlocking {
        val prepared = prepare()
        val target = prepared.target
        val cancellation = CancellationException("after-copy")
        try {
            prepared.use {
                ConvertedInstallSubmitter(repo).submit("bound-cancel", it.target, {
                    it.dataFile.copyTo(gameData, overwrite = true)
                    throw cancellation
                }, { fail("No ledger on cancelled replacement"); null })
            }
            fail("Cancellation must escape")
        } catch (caught: CancellationException) {
            assertSame(cancellation, caught)
        }
        assertFalse(prepared.sourceDir.exists())
        assertEquals("prepared-mod", gameData.readText())
        assertEquals("target_replacing", journal().last().stage)
        assertEquals(target, journal().last().targets.single())
        val reloaded = InstalledModRepository(context)
        assertTrue(reloaded.checkRecovery() is OperationJournalStore.RecoveryVerdict.PendingReconciliation)
        assertNotNull(reloaded.checkSubmitAllowed())
    }

    @Test fun incompleteSourceCannotCreateIntentOrTouchTheTarget() {
        val staging = temp.newFolder()
        val preparation = stagingForJvm(staging).prepare("bundle-a", "hash-a", listOf(record)) {
            File(it, "hash-a").mkdirs()
            false // strict SAF adapter cannot report a partial copy as successful.
        }
        assertTrue(preparation is ConvertedInstallPreparation.Failed)
        assertEquals("original", gameData.readText())
        assertEquals(JournalRead.Absent, OperationJournalStore(repo.journalFile()).read())
        assertTrue(staging.listFiles()!!.isEmpty())
    }

    @Test fun partialStrictCopyIsCleanedBeforeAnyJournalOrTargetWrite() {
        val staging = temp.newFolder()
        var dataWasOpened = false
        val source = object : com.bd2toolsbox.core.fileaccess.DirectoryCopySource {
            override fun listChildren(directoryId: String): List<com.bd2toolsbox.core.fileaccess.DirectoryCopyEntry>? =
                when (directoryId) {
                    "root" -> listOf(com.bd2toolsbox.core.fileaccess.DirectoryCopyEntry("hash", "hash-a", true))
                    "hash" -> listOf(com.bd2toolsbox.core.fileaccess.DirectoryCopyEntry("data", "__data", false),
                        com.bd2toolsbox.core.fileaccess.DirectoryCopyEntry("bad", "unreadable", true))
                    else -> null
                }
            override fun openFile(fileId: String): java.io.InputStream {
                dataWasOpened = true
                return java.io.ByteArrayInputStream("partial-mod".toByteArray())
            }
        }
        val result = stagingForJvm(staging).prepare("bundle-a", "hash-a", listOf(record)) {
            com.bd2toolsbox.core.fileaccess.StrictDirectoryCopy.copy(source, "root", it)
            true
        }
        assertTrue(dataWasOpened)
        assertTrue(result is ConvertedInstallPreparation.Failed)
        assertTrue(staging.listFiles()!!.isEmpty())
        assertEquals(JournalRead.Absent, OperationJournalStore(repo.journalFile()).read())
        assertEquals("original", gameData.readText())
    }
}
