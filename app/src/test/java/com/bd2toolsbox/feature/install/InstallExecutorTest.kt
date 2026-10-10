package com.bd2toolsbox.feature.install

import android.content.Context
import com.bd2toolsbox.core.fileaccess.GameFileAccess
import com.bd2toolsbox.core.model.OperationId
import com.bd2toolsbox.core.storage.*
import com.bd2toolsbox.data.model.InstalledModRecord
import com.bd2toolsbox.data.repository.InstalledModRepository
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 真文件、真暂存、journal 和账本；只替换 Android Context、平台文件身份与 Binder。 */
class InstallExecutorTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var shared: File
    private lateinit var staging: File
    private lateinit var game: File
    private lateinit var repository: InstalledModRepository
    private lateinit var access: GameFileAccess
    private lateinit var executor: InstallExecutor
    private var connected = false
    private var beforeCopy: () -> Unit = {}

    @Before fun setup() {
        // T22：InstallExecutor 在暂存清理路径上写 Log.w；JVM 下先桩住 Android Log。
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        shared = temp.newFolder("shared")
        staging = temp.newFolder("staging")
        game = temp.newFolder("game")
        val context = mockk<Context>()
        every { context.filesDir } returns temp.newFolder("files")
        repository = InstalledModRepository(context)
        mockkObject(StagingDirectoryLease.Companion)
        every { StagingDirectoryLease.NIO_FILE_KEY } returns JvmTestStagingIdentity
        access = mockk()
        val token = Any()
        every { access.acquire() } answers { check(!connected); connected = true; token }
        every { access.release(token) } answers { connected = false }
        coEvery { access.installConvertedBundle(any(), any()) } coAnswers {
            check(connected) { "The executor must hold the connection while writing" }
            beforeCopy()
            File(firstArg<String>()).copyRecursively(File(game, secondArg<String>()), overwrite = true)
            true to "copied"
        }
        executor = InstallExecutor(repository, access)
    }

    @After fun teardown() = unmockkAll()

    private fun target(name: String, restore: Boolean = false): JournalTarget {
        File(shared, "$name/hash/__data").apply { parentFile!!.mkdirs(); writeText("new $name") }
        val records = if (restore) emptyList() else listOf(
            LedgerRecord("mod:$name", name, "family", name, "HD", true, 17L)
        )
        return JournalMoveFacts.target(shared, name, "$name/hash/__data", records.map { it.modUri }, records)
    }

    @Test fun commitsOnlyFrozenTargetsAndPreservesUnrelatedDownloads() = runTest {
        val selected = target("selected")
        target("unrelated")
        val result = executor.move(OperationId("task-a"), shared, staging, listOf(selected), emptySet())
        assertTrue(result.submission.message, result.submission.completedSuccessfully)
        assertEquals("new selected", File(game, "selected/hash/__data").readText())
        assertFalse(File(game, "unrelated").exists())
        assertTrue(File(shared, "unrelated/hash/__data").isFile)
        assertFalse(File(shared, "selected/hash/__data").exists())
        assertEquals("HD", repository.load().getValue("mod:selected").quality)
        assertTrue(repository.load().getValue("mod:selected").usedAstc)
        assertFalse(repository.journalFile().exists())
        assertFalse(connected)
    }

    @Test fun fileReplacementFailureKeepsSourceAndPendingJournal() = runTest {
        val selected = target("selected")
        coEvery { access.installConvertedBundle(any(), any()) } returns (false to "disconnected")
        val result = executor.move(OperationId("task-a"), shared, staging, listOf(selected), emptySet())
        assertFalse(result.submission.completedSuccessfully)
        assertTrue(result.submission.requiresReconciliation)
        assertTrue(repository.load().isEmpty())
        assertTrue(File(shared, "selected/hash/__data").isFile)
        val journal = OperationJournalStore(repository.journalFile()).read() as JournalRead.Loaded
        assertTrue(journal.entries.all { it.operationId == "task-a" })
        assertEquals(JournalStage.TARGET_REPLACING, journal.entries.last().stageOrNull)
        assertFalse(connected)
    }

    @Test fun ledgerFailureAfterCopyIsNotReportedAsSuccessfulInstall() = runTest {
        val selected = target("selected")
        // 故障在文件边界产生：复制完成前占用账本的文件名，不替换仓储/提交算法。
        beforeCopy = { assertTrue(repository.file().mkdir()) }
        val result = executor.move(OperationId("task-a"), shared, staging, listOf(selected), emptySet())
        assertTrue(result.submission.filesReplaced)
        assertFalse(result.submission.completedSuccessfully)
        assertTrue(result.submission.requiresReconciliation)
        assertTrue(result.ledgerNotices.any { it.result is LedgerWrite.Failed })
        assertTrue(File(game, "selected/hash/__data").isFile)
        assertTrue(File(shared, "selected/hash/__data").isFile)
        assertTrue(repository.journalFile().isFile)
        assertFalse(connected)
    }

    @Test fun restoringOneBundleCannotClearOtherInstalledRecords() = runTest {
        val old = listOf("selected", "other").map {
            InstalledModRecord("mod:$it", it, "family", it, "HD", false, 1L)
        }
        assertTrue(repository.putAll(old) is LedgerWrite.Written)
        val selected = target("selected", restore = true)
        val result = executor.move(OperationId("task-a"), shared, staging, listOf(selected), setOf("selected", "other"))
        assertTrue(result.submission.completedSuccessfully)
        assertEquals(setOf("mod:other"), repository.load().keys)
        assertFalse(connected)
    }

    @Test fun aSourceChangedAfterPlanningNeverReachesGame() = runTest {
        val selected = target("selected")
        File(shared, "selected/hash/__data").writeText("changed")
        try {
            executor.move(OperationId("task-a"), shared, staging, listOf(selected), emptySet())
            fail("Source drift must reject the operation")
        } catch (_: java.io.IOException) {
            assertTrue(game.listFiles()!!.isEmpty())
            assertTrue(repository.load().isEmpty())
            assertFalse(repository.journalFile().exists())
            assertFalse(connected)
        }
    }
}
