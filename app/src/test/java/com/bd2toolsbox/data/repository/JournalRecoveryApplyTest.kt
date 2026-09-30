package com.bd2toolsbox.data.repository

import android.content.Context
import com.bd2toolsbox.core.storage.LedgerWrite
import com.bd2toolsbox.core.storage.OperationJournalStore
import com.bd2toolsbox.core.storage.StreamingDigest
import com.bd2toolsbox.core.storage.TargetObservation
import com.bd2toolsbox.core.storage.TargetObservations
import com.bd2toolsbox.data.model.InstalledModRecord
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * T08 批次 B：恢复租约 + 只按 journal 冻结事实补账。
 * 同进程磁盘重读，不冒充 Android 杀进程。
 */
class JournalRecoveryApplyTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var filesDir: File

    @Before
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0
        filesDir = temp.newFolder("files")
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun aSecondRecoveryOnTheSameRepositoryCannotReplaceAnActiveLease() {
        plantReplacedWithFacts()
        val owner = repo()
        assertNull(owner.beginRecovery())
        try {
            assertNotNull("Same-instance recovery must not be reentrant", owner.beginRecovery())
            assertNotNull("The rejected request must leave the first lease held", repo().beginRecovery())
        } finally { owner.releaseRecovery() }
        assertAnotherRepositoryCanRecover()
    }

    @Test
    fun aLateOneShotFinallyCannotReleaseANewerRecoveryOnTheSameRepository() {
        plantReplacedWithFacts()
        val owner = io.mockk.spyk(repo())
        var acquiredReplacement = false
        every { owner.applyContentRecovery(any()) } answers {
            val result = callOriginal()
            assertNull(owner.beginRecovery())
            acquiredReplacement = true
            result
        }
        try {
            val result = owner.recoverFromSharedTree(temp.newFolder("empty-shared"), temp.newFolder("observed"))
            assertTrue(result is RecoveryApplyResult.NotApplied)
            assertTrue(acquiredReplacement)
            val other = repo()
            try {
                assertNotNull("Old finally must not release a newly acquired recovery lease", other.beginRecovery())
            } finally { other.releaseRecovery() }
        } finally { owner.releaseRecovery() }
    }

    @Test
    fun failedObservationReleasesLeaseForANewRepositoryWithoutClearingEvidence() {
        plantReplacedWithFacts()
        val first = repo()
        val before = first.journalFile().readBytes()
        assertNull(first.beginRecovery())
        assertTrue(first.applyContentRecovery(listOf(observed(SHA_B))) is RecoveryApplyResult.NotApplied)
        val second = repo()
        try {
            assertNull("Failure must not strand recovery ownership in an old UI", second.beginRecovery())
            assertArrayEquals(before, second.journalFile().readBytes())
        } finally { second.releaseRecovery(); first.releaseRecovery() }
    }

    @Test
    fun observedLocalFileMatchingPlantedFactsCommitsLedger() {
        val data = temp.newFile("__data")
        data.writeBytes(byteArrayOf(9, 8, 7, 6))
        val sha = StreamingDigest.sha256().digest(data)
        plantReplacedWithFacts(sha)
        val repo = repo()
        assertNull(repo.beginRecovery())
        val obs = TargetObservations.observeFile("op-a", "bundle-a", "hash-a", data)
        val applied = repo.applyContentRecovery(listOf(obs))
        assertTrue("file observation must apply, actual $applied", applied is RecoveryApplyResult.Applied)
        assertEquals("Alice", repo.load()["uri-a"]?.modName)
        assertTrue(!repo.journalFile().exists())
    }

    @Test
    fun recoverFromSharedTreeCopiesThenCommits() {
        val data = temp.newFile("payload")
        data.writeBytes(byteArrayOf(1, 2, 3))
        val sha = StreamingDigest.sha256().digest(data)
        plantReplacedWithFacts(sha)
        val shared = temp.newFolder("Shared")
        val src = File(File(File(shared, "bundle-a"), "hash-a"), "__data")
        src.parentFile.mkdirs()
        data.copyTo(src, overwrite = true)
        val repo = repo()
        val applied = repo.recoverFromSharedTree(shared, temp.newFolder("obs"))
        assertTrue("shared-tree recovery must apply, actual $applied", applied is RecoveryApplyResult.Applied)
        assertEquals("Alice", repo.load()["uri-a"]?.modName)
        assertTrue(!repo.journalFile().exists())
    }

    @Test
    fun pendingRecoveryTargetsCarryOperationId() {
        plantReplacedWithFacts()
        val refs = repo().pendingRecoveryTargets()
        assertEquals(listOf("op-a"), refs.map { it.operationId })
        assertEquals(listOf("bundle-a"), refs.map { it.target.bundleName })
    }

    @Test
    fun matchingObservationCommitsFrozenUpsertsAndClearsJournal() {
        plantReplacedWithFacts()
        val repo = repo()
        assertNull(repo.beginRecovery())
        val applied = repo.applyContentRecovery(listOf(observed(SHA_A)))
        assertTrue("matching content must apply, actual $applied", applied is RecoveryApplyResult.Applied)
        assertEquals("Alice", repo.load()["uri-a"]?.modName)
        assertEquals("bundle-a", repo.load()["uri-a"]?.snapshotTargetHash)
        assertTrue("journal must be gone after successful apply", !repo.journalFile().exists())
        assertNull("new submit allowed after recovery", repo.beginSubmit("op-next", emptyList()))
    }

    @Test
    fun mismatchDoesNotWriteLedgerOrJournal() {
        plantReplacedWithFacts()
        val repo = repo()
        val beforeJournal = repo.journalFile().readBytes()
        assertNull(repo.beginRecovery())
        val result = repo.applyContentRecovery(listOf(observed(SHA_B)))
        assertTrue("mismatch must not apply, actual $result", result is RecoveryApplyResult.NotApplied)
        assertTrue(repo.load().isEmpty())
        assertArrayEquals(beforeJournal, repo.journalFile().readBytes())
    }

    @Test
    fun journalFingerprintChangeBlocksApply() {
        plantReplacedWithFacts()
        val repo = repo()
        assertNull(repo.beginRecovery())
        repo.journalFile().appendText(" ")
        val result = repo.applyContentRecovery(listOf(observed(SHA_A)))
        assertTrue("tampered journal must block apply, actual $result", result is RecoveryApplyResult.NotApplied)
        assertTrue(repo.load().isEmpty())
    }

    @Test
    fun recoveryLeaseBlocksOrdinaryPutsAndOtherInstanceSubmit() {
        plantReplacedWithFacts()
        val a = repo()
        val b = repo()
        assertNull(a.beginRecovery())
        val put = a.put(
            com.bd2toolsbox.data.model.InstalledModRecord(
                "uri-sneak", "sneak", "family", "bundle-x", "HD", false, 1L
            )
        )
        assertTrue("ordinary put during recovery must fail, actual $put", put is com.bd2toolsbox.core.storage.LedgerWrite.Failed)
        assertNotNull(b.beginSubmit("op-b", emptyList()))
        val take = a.removeAndReturn("uri-a")
        assertTrue("removeAndReturn during recovery must fail, actual $take", take is LedgerTake.Blocked)
        a.releaseRecovery()
    }

    @Test
    fun legacyWithoutFactsCannotInventLedgerRecords() {
        File(filesDir, "install_journal.json").writeText(
            """{"schemaVersion":1,"entries":[{"operationId":"op-a","stage":"target_replaced","atMillis":1,
            "targets":[{"bundleName":"bundle-a","hashDir":"hash-a","dataBytes":4,"modUris":["uri-a"]}]}]}"""
        )
        val repo = repo()
        assertNull(repo.beginRecovery())
        val result = repo.applyContentRecovery(listOf(observed(SHA_A)))
        assertTrue("legacy cannot auto-fill ledger, actual $result", result is RecoveryApplyResult.NotApplied)
        assertTrue(repo.load().isEmpty())
        assertTrue(repo.journalFile().exists())
    }

    @Test
    fun conflictingBundlesAcrossOperationsPreserveLedgerAndJournal() {
        assertCrossOperationConflictRejected(secondBundle = "bundle-b", secondName = "Alice")
    }

    @Test
    fun conflictingRecordsAcrossOperationsForSameBundlePreserveLedgerAndJournal() {
        assertCrossOperationConflictRejected(secondBundle = "bundle-a", secondName = "Changed name")
    }

    private fun assertCrossOperationConflictRejected(secondBundle: String, secondName: String) {
        val repo = repo()
        val unrelated = seedUnrelatedRecord(repo)
        plantOperations(
            recoveryEntry("op-a", "bundle-a", "uri-a"),
            recoveryEntry("op-b", secondBundle, "uri-a", modName = secondName)
        )
        val beforeLedger = repo.file().readBytes()
        val beforeJournal = repo.journalFile().readBytes()

        try {
            assertNull(repo.beginRecovery())
            val applied = repo.applyContentRecovery(listOf(
                observed(SHA_A),
                observed(SHA_A, operationId = "op-b", bundleName = secondBundle)
            ))
            assertTrue("conflicting frozen records must not apply: $applied", applied is RecoveryApplyResult.NotApplied)
            assertArrayEquals(beforeLedger, repo.file().readBytes())
            assertArrayEquals(beforeJournal, repo.journalFile().readBytes())
            assertEquals(mapOf(unrelated.modUri to unrelated), repo.load())
            assertTrue(repo.checkRecovery() is OperationJournalStore.RecoveryVerdict.PendingReconciliation)
        } finally {
            repo.releaseRecovery()
        }
        assertNotNull("unresolved conflict must still block submissions", repo.checkSubmitAllowed())
    }

    @Test
    fun disjointOperationsRecoverTogetherAndPreserveUnrelatedRecords() {
        val repo = repo()
        val unrelated = seedUnrelatedRecord(repo)
        plantOperations(
            recoveryEntry("op-a", "bundle-a", "uri-a"),
            recoveryEntry("op-b", "bundle-b", "uri-b", modName = "Bob", sha = SHA_B)
        )

        try {
            assertNull(repo.beginRecovery())
            val applied = repo.applyContentRecovery(listOf(
                observed(SHA_A),
                observed(SHA_B, operationId = "op-b", bundleName = "bundle-b")
            ))
            assertEquals(RecoveryApplyResult.Applied(listOf("op-a", "op-b")), applied)
            val records = repo.load()
            assertEquals(setOf("uri-a", "uri-b", unrelated.modUri), records.keys)
            assertEquals("bundle-a", records["uri-a"]?.snapshotTargetHash)
            assertEquals("bundle-b", records["uri-b"]?.snapshotTargetHash)
            assertEquals(unrelated, records[unrelated.modUri])
            assertTrue("all committed operations may be cleared", !repo.journalFile().exists())
            assertNull(repo.checkSubmitAllowed())
        } finally {
            repo.releaseRecovery()
        }
    }

    @Test
    fun identicalUpsertsAcrossOperationsRecoverIdempotently() {
        plantOperations(
            recoveryEntry("op-a", "bundle-a", "uri-a"),
            recoveryEntry("op-b", "bundle-a", "uri-a")
        )
        val repo = repo()
        try {
            assertNull(repo.beginRecovery())
            val applied = repo.applyContentRecovery(listOf(
                observed(SHA_A),
                observed(SHA_A, operationId = "op-b")
            ))
            assertEquals(RecoveryApplyResult.Applied(listOf("op-a", "op-b")), applied)
            assertEquals(setOf("uri-a"), repo.load().keys)
            assertEquals("Alice", repo.load()["uri-a"]?.modName)
            assertNull(repo.checkSubmitAllowed())
        } finally {
            repo.releaseRecovery()
        }
    }

    private fun seedUnrelatedRecord(repo: InstalledModRepository): InstalledModRecord {
        val record = InstalledModRecord("uri-existing", "Existing", "family-existing", "bundle-existing", "HD", false, 7L)
        assertEquals(LedgerWrite.Written, repo.put(record))
        return record
    }

    private fun repo(): InstalledModRepository {
        val context = mockk<Context>()
        every { context.filesDir } returns filesDir
        return InstalledModRepository(context)
    }

    private fun plantReplacedWithFacts(sha: String = SHA_A) {
        plantOperations(recoveryEntry("op-a", "bundle-a", "uri-a", sha = sha))
    }

    private fun plantOperations(vararg entries: String) {
        File(filesDir, "install_journal.json").writeText(
            """{"schemaVersion":1,"entries":[${entries.joinToString(",")}]}"""
        )
    }

    private fun recoveryEntry(
        operationId: String,
        bundleName: String,
        modUri: String,
        modName: String = "Alice",
        sha: String = SHA_A
    ): String = """{"operationId":"$operationId","stage":"target_replaced","atMillis":1,
        "targets":[{"bundleName":"$bundleName","hashDir":"hash-a","dataBytes":4,"modUris":["$modUri"],
        "recoveryFacts":{"formatVersion":1,"expectedContentSha256":"$sha",
        "ledgerUpserts":[{"modUri":"$modUri","modName":"$modName","familyKey":"family-a",
        "snapshotTargetHash":"$bundleName","quality":"HD","usedAstc":true,"installedAt":123}]}}]}"""

    private fun observed(
        sha: String,
        operationId: String = "op-a",
        bundleName: String = "bundle-a"
    ) = TargetObservation.Observed(
        operationId = operationId,
        bundleName = bundleName,
        hashDir = "hash-a",
        actualSha256 = sha
    )

    @Test
    fun recoverFromSharedTreeWhenSharedTreeHasNoDataYieldsNotApplied() {
        plantReplacedWithFacts()
        val shared = temp.newFolder("Shared")
        val repo = repo()
        val beforeJournal = repo.journalFile().readBytes()
        val applied = repo.recoverFromSharedTree(shared, temp.newFolder("obs"))
        assertTrue("missing __data must not apply, actual $applied", applied is RecoveryApplyResult.NotApplied)
        assertTrue(repo.load().isEmpty())
        assertArrayEquals(beforeJournal, repo.journalFile().readBytes())
        assertAnotherRepositoryCanRecover()
    }

    @Test
    fun recoverFromSharedTreeWhenDataExistsButShaDiffersYieldsNotApplied() {
        plantReplacedWithFacts(SHA_A)
        val data = temp.newFile("payload")
        data.writeBytes(byteArrayOf(1, 2, 3))
        val shared = temp.newFolder("Shared")
        val src = File(File(File(shared, "bundle-a"), "hash-a"), "__data")
        src.parentFile.mkdirs()
        data.copyTo(src, overwrite = true)
        
        val repo = repo()
        val beforeJournal = repo.journalFile().readBytes()
        val applied = repo.recoverFromSharedTree(shared, temp.newFolder("obs"))
        assertTrue("mismatched __data must not apply, actual $applied", applied is RecoveryApplyResult.NotApplied)
        assertTrue(repo.load().isEmpty())
        assertArrayEquals(beforeJournal, repo.journalFile().readBytes())
        assertAnotherRepositoryCanRecover()
    }

    private fun assertAnotherRepositoryCanRecover() {
        val retry = repo()
        assertNotNull("pending evidence must still block ordinary submission", retry.checkSubmitAllowed())
        try {
            assertNull("one-shot failure must release the original instance's recovery lease", retry.beginRecovery())
        } finally {
            retry.releaseRecovery()
        }
    }

    companion object {
        private const val SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
