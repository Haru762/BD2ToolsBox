package com.bd2toolsbox.data.repository

import android.content.Context
import com.bd2toolsbox.core.model.OperationError
import com.bd2toolsbox.core.storage.LedgerWrite
import com.bd2toolsbox.core.storage.JournalTarget
import com.bd2toolsbox.data.model.InstalledModRecord
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 同进程多实例的租约身份边界；真实 journal 文件，不桩持久化。 */
class InstalledModRepositoryLeaseBoundaryTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var dir: File
    private val targets = listOf(JournalTarget("bundle-a", "hash-a", 4L, listOf("uri-a")))

    @Before fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0
        dir = temp.newFolder("files")
    }
    @After fun tearDown() = unmockkAll()

    private fun repo(): InstalledModRepository {
        val context = mockk<Context>()
        every { context.filesDir } returns dir
        return InstalledModRepository(context)
    }

    private fun mutations(r: InstalledModRepository): List<Pair<String, () -> OperationError?>> = listOf(
        "staged" to { r.markStaged() },
        "replacing" to { r.markTargetReplacing() },
        "replaced" to { r.markTargetReplaced() },
        "committed" to { r.markLedgerCommitted() },
        "finish" to { r.finishSubmit(null) },
        "clear" to { r.clearJournal() }
    )

    @Test fun foreignInstanceCannotReleaseEvenWithCurrentOperationId() {
        val owner = repo()
        val stranger = repo()
        assertNull(owner.beginSubmit("current", targets))
        val before = owner.journalFile().readBytes()
        stranger.releaseSubmit("current")
        assertNotNull(repo().beginSubmit("next", targets))
        assertArrayEquals(before, owner.journalFile().readBytes())
        assertNull(owner.markStaged())
    }

    @Test fun foreignInstanceCannotAdvanceFinishOrClearOwnersJournal() {
        val owner = repo()
        val stranger = repo()
        assertNull(owner.beginSubmit("current", targets))
        assertNull(owner.markStaged())
        assertNull(owner.markTargetReplacing())
        val before = owner.journalFile().readBytes()
        for ((name, mutate) in mutations(stranger)) {
            assertNotNull("Non-owner must reject $name", mutate())
            assertArrayEquals("Rejected mutation must preserve journal: $name", before, owner.journalFile().readBytes())
            assertNotNull("Lease must remain owned after $name", repo().checkSubmitAllowed())
        }
    }

    @Test fun instanceWithoutBeginCannotCreateUnknownJournal() {
        val r = repo()
        for ((name, mutate) in mutations(r)) {
            assertNotNull("No lease must reject $name", mutate())
            assertFalse("No unknown entry allowed: $name", r.journalFile().exists())
        }
    }

    @Test fun releasedPendingOperationCannotBeClearedWithoutRecovery() {
        val owner = repo()
        assertNull(owner.beginSubmit("pending", targets))
        assertNull(owner.markTargetReplacing())
        owner.releaseSubmit("pending")
        val before = owner.journalFile().readBytes()
        assertNotNull(repo().clearJournal())
        assertArrayEquals(before, owner.journalFile().readBytes())
        assertNotNull(repo().checkSubmitAllowed())
    }

    @Test fun successfulOwnerFinishReleasesLeaseForAnotherInstance() {
        val owner = repo()
        assertNull(owner.beginSubmit("done", targets))
        assertNull(owner.markStaged())
        assertNull(owner.markTargetReplacing())
        assertNull(owner.markTargetReplaced())
        val write = owner.put(InstalledModRecord(
            modUri = "uri-a", modName = "a", familyKey = "family-a",
            snapshotTargetHash = "bundle-a", quality = "HD", usedAstc = false, installedAt = 1L
        ))
        assertTrue(write is LedgerWrite.Written)
        assertNull(owner.finishSubmit(null))
        assertFalse(owner.journalFile().exists())
        assertNull(repo().beginSubmit("successor", targets))
    }

    @Test fun failedBeginDoesNotLeaveSharedLeaseOccupied() {
        val first = repo()
        val badJournal = first.journalFile()
        assertTrue(badJournal.mkdir())
        assertNotNull(first.beginSubmit("fails", targets))
        // Only remove the empty directory this test itself created.
        assertTrue(badJournal.delete())
        assertNull(repo().beginSubmit("retry", targets))
    }

    @Test fun evenOwnerCannotClearUnresolvedReplacementIntent() {
        val owner = repo()
        assertNull(owner.beginSubmit("pending", targets))
        assertNull(owner.markTargetReplacing())
        val before = owner.journalFile().readBytes()
        assertNotNull(owner.clearJournal())
        assertArrayEquals(before, owner.journalFile().readBytes())
        owner.releaseSubmit("pending")
        assertNotNull(repo().checkSubmitAllowed())
    }

    @Test fun ownerMayClearBeforeAnyReplacementStarts() {
        val owner = repo()
        assertNull(owner.beginSubmit("planned-only", targets))
        assertNull(owner.clearJournal())
        assertFalse(owner.journalFile().exists())
        owner.releaseSubmit("planned-only")
        assertNull(repo().beginSubmit("next", targets))
    }

    @Test fun ownerCannotClearUnreadableJournal() {
        val owner = repo()
        assertNull(owner.beginSubmit("corrupt-journal", targets))
        owner.journalFile().writeText("not-json")
        val before = owner.journalFile().readBytes()
        assertNotNull(owner.clearJournal())
        assertArrayEquals(before, owner.journalFile().readBytes())
    }

    @Test fun ownerCannotClearWhenLedgerCannotBeRead() {
        val owner = repo()
        assertNull(owner.beginSubmit("corrupt-ledger", targets))
        owner.file().writeText("not-json")
        val before = owner.journalFile().readBytes()
        assertNotNull(owner.clearJournal())
        assertArrayEquals(before, owner.journalFile().readBytes())
        assertEquals("not-json", owner.file().readText())
    }
}
