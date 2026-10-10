package com.bd2toolsbox.feature.install

import android.content.Context
import com.bd2toolsbox.core.storage.LedgerWrite
import com.bd2toolsbox.data.model.InstalledModRecord
import com.bd2toolsbox.data.repository.InstalledModRepository
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 真文件、真账本；只替换 Android Context 与 Log。 */
class UninstallExecutorTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var repository: InstalledModRepository
    private lateinit var executor: UninstallExecutor

    @Before fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0
        val context = mockk<Context>()
        every { context.filesDir } returns temp.newFolder("files")
        repository = InstalledModRepository(context)
        executor = UninstallExecutor(repository)
    }

    @After fun teardown() = unmockkAll()

    private fun record(uri: String, target: String) =
        InstalledModRecord(uri, uri, "family", target, "HD", false, 1L)

    private fun seed(vararg records: InstalledModRecord) {
        assertTrue(repository.putAll(records.toList()) is LedgerWrite.Written)
    }

    @Test fun removingOneOfTwoKeepsTheOtherInTheSameBundle() {
        seed(record("content://mods/a", "bundle"), record("content://mods/b", "bundle"))
        val removal = executor.removeSingleModRecord("content://mods/a", "bundle")
        assertEquals(listOf("content://mods/b"), removal.remainingModUris)
        assertEquals(1, removal.notices.size)
        assertEquals("账本移除", removal.notices.single().action)
        assertTrue(removal.notices.single().result is LedgerWrite.Written)
        assertEquals(setOf("content://mods/b"), repository.load().keys)
    }

    @Test fun removingTheLastModLeavesTheBundleEmpty() {
        seed(record("content://mods/a", "bundle"))
        val removal = executor.removeSingleModRecord("content://mods/a", "bundle")
        assertTrue(removal.remainingModUris.isEmpty())
        assertTrue(repository.load().isEmpty())
    }

    @Test fun removingAnAbsentUriDoesNotTouchOtherRecords() {
        seed(record("content://mods/a", "bundle"))
        val removal = executor.removeSingleModRecord("content://mods/missing", "bundle")
        // 账本里本来就没有这条：不写盘、不删别人的记录。
        assertEquals(listOf("content://mods/a"), removal.remainingModUris)
        assertEquals(setOf("content://mods/a"), repository.load().keys)
    }

    @Test fun clearTargetLedgerRemovesOnlyThatBundle() {
        seed(
            record("content://mods/a", "bundle"),
            record("content://mods/b", "bundle"),
            record("content://mods/c", "other")
        )
        val notices = executor.clearTargetLedger("bundle")
        assertEquals(1, notices.size)
        assertEquals("账本清理", notices.single().action)
        assertEquals(setOf("content://mods/c"), repository.load().keys)
    }

    @Test fun clearTargetLedgerOnMissingBundleIsANoop() {
        seed(record("content://mods/c", "other"))
        val notices = executor.clearTargetLedger("bundle")
        assertEquals(1, notices.size)
        assertEquals(setOf("content://mods/c"), repository.load().keys)
    }
}
