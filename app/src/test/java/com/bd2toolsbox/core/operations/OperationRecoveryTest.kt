package com.bd2toolsbox.core.operations

import com.bd2toolsbox.core.storage.JournalEntry
import com.bd2toolsbox.core.storage.JournalStage
import com.bd2toolsbox.core.storage.JournalTarget
import com.bd2toolsbox.core.storage.OperationJournalStore
import com.bd2toolsbox.data.repository.InstalledModRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 纯 JVM：真 journal 文件，验证阶段分类与清理路径归属校验。 */
class OperationRecoveryTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var journalFile: File
    private lateinit var store: OperationJournalStore
    private lateinit var recovery: OperationRecovery

    @Before fun setup() {
        journalFile = File(temp.newFolder("files"), "install_journal.json")
        store = OperationJournalStore(journalFile)
        val repository = mockk<InstalledModRepository>()
        every { repository.journalFile() } returns journalFile
        recovery = OperationRecovery(repository)
    }

    @After fun teardown() = unmockkAll()

    private fun entry(
        op: String,
        stage: JournalStage,
        at: Long = 1L,
        bundle: String = "bundle",
        uri: String = "content://mods/a"
    ) = JournalEntry(
        operationId = op,
        stage = stage.id,
        atMillis = at,
        targets = listOf(JournalTarget(bundle, "hash", 10L, listOf(uri)))
    )

    private fun append(vararg entries: JournalEntry) {
        entries.forEach { assertEquals(null, store.append(it)) }
    }

    @Test fun emptyJournalIsClear() {
        assertTrue(recovery.classify().isEmpty())
    }

    @Test fun intentWithoutConfirmedReplaceIsInterrupted() {
        append(entry("op1", JournalStage.TARGET_REPLACING))
        val d = recovery.classify().single()
        assertTrue(d is RecoveryDisposition.Interrupted)
        d as RecoveryDisposition.Interrupted
        assertEquals("op1", d.operationId)
        assertEquals(listOf("bundle"), d.bundleNames)
        assertEquals(listOf("content://mods/a"), d.modUris)
    }

    @Test fun confirmedReplaceWithoutLedgerNeedsReconciliation() {
        append(entry("op1", JournalStage.TARGET_REPLACED))
        val d = recovery.classify().single()
        assertTrue(d is RecoveryDisposition.NeedsReconciliation)
    }

    @Test fun ledgerCommitClosesTheWindowAndReportsCommitted() {
        append(
            entry("op1", JournalStage.TARGET_REPLACED, at = 1L),
            entry("op1", JournalStage.LEDGER_COMMITTED, at = 2L)
        )
        val d = recovery.classify().single()
        assertTrue(d is RecoveryDisposition.Committed)
        assertEquals("op1", (d as RecoveryDisposition.Committed).operationId)
    }

    @Test fun replaceAfterCommitReopensTheWindow() {
        append(
            entry("op1", JournalStage.LEDGER_COMMITTED, at = 1L),
            entry("op1", JournalStage.TARGET_REPLACED, at = 2L)
        )
        val d = recovery.classify().single()
        assertTrue(d is RecoveryDisposition.NeedsReconciliation)
    }

    @Test fun multipleOperationsAreClassifiedIndependently() {
        append(
            entry("op1", JournalStage.TARGET_REPLACING, at = 1L, bundle = "b1", uri = "u1"),
            entry("op2", JournalStage.TARGET_REPLACED, at = 2L, bundle = "b2", uri = "u2")
        )
        val all = recovery.classify()
        assertEquals(2, all.size)
        assertTrue(all.any { it is RecoveryDisposition.Interrupted && it.operationId == "op1" })
        assertTrue(all.any { it is RecoveryDisposition.NeedsReconciliation && it.operationId == "op2" })
    }

    @Test fun unreadableJournalIsUnknownNotClear() {
        journalFile.writeText("{ this is not valid json")
        val d = recovery.classify().single()
        assertTrue(d is RecoveryDisposition.Unknown)
    }

    @Test fun cleanupPathMustBeInsideAnAllowedRoot() {
        val root = temp.newFolder("cache")
        val allowed = listOf(root)
        assertTrue(OperationRecovery.isCleanupPathAllowed(File(root, "task/tmp"), allowed))
        assertTrue(OperationRecovery.isCleanupPathAllowed(root, allowed))
        assertFalse(OperationRecovery.isCleanupPathAllowed(temp.newFolder("outside"), allowed))
    }

    @Test fun cleanupPathCannotEscapeWithDotDot() {
        val root = temp.newFolder("cache")
        val escaped = File(root, "../outside/file")
        assertFalse(OperationRecovery.isCleanupPathAllowed(escaped, listOf(root)))
    }

    @Test fun siblingWithCommonPrefixIsNotInside() {
        val root = temp.newFolder("a")
        val sibling = temp.newFolder("a-sibling")
        assertFalse("字符串前缀不等于路径包含", OperationRecovery.isCleanupPathAllowed(sibling, listOf(root)))
    }

    @Test fun noAllowedRootsMeansNothingIsAllowed() {
        assertFalse(OperationRecovery.isCleanupPathAllowed(temp.newFolder("x"), emptyList()))
    }
}
