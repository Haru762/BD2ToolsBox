package com.bd2toolsbox.service

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bd2toolsbox.core.storage.JournalRecoveryFacts
import com.bd2toolsbox.core.storage.JournalEntry
import com.bd2toolsbox.core.storage.JournalStage
import com.bd2toolsbox.core.storage.JournalTarget
import com.bd2toolsbox.core.storage.LedgerRecord
import com.bd2toolsbox.core.storage.OperationJournalStore
import com.bd2toolsbox.core.storage.StreamingDigest
import com.bd2toolsbox.data.repository.InstalledModRepository
import com.bd2toolsbox.data.repository.RecoveryApplyResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * 设备上跑 JVM 已有的「Shared 树观察 → 补账」闭环。
 * 夹具只写本用例 cache 子目录，不碰真实游戏 UnityCache，不清 app 数据。
 */
@RunWith(AndroidJUnit4::class)
class ContentRecoveryDeviceTest {

    private var fixtureDir: File? = null

    @After
    fun cleanupOwnFixture() {
        fixtureDir?.let { dir ->
            val base = InstrumentationRegistry.getInstrumentation().targetContext
            check(dir.name.startsWith(PREFIX) && dir.parentFile == base.cacheDir) {
                "拒绝删除非本用例夹具：${dir.absolutePath}"
            }
            dir.deleteRecursively()
        }
        fixtureDir = null
    }

    @Test
    fun recoverFromIsolatedSharedTreeCommitsFrozenLedgerOnDevice() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(base.cacheDir, PREFIX + UUID.randomUUID()).apply { mkdirs() }
        fixtureDir = dir
        val filesDir = File(dir, "files").apply { mkdirs() }
        val shared = File(dir, "Shared").apply { mkdirs() }
        val obs = File(dir, "obs").apply { mkdirs() }
        val data = File(File(File(shared, "bundle-a"), "hash-a"), "__data")
        data.parentFile.mkdirs()
        data.writeBytes(byteArrayOf(9, 8, 7, 6, 5))
        val sha = StreamingDigest.sha256().digest(data)
        val facts = JournalRecoveryFacts(
            1, sha,
            listOf(
                LedgerRecord("uri-a", "Alice", "family-a", "bundle-a", "HD", true, 123L)
            )
        )
        val journal = OperationJournalStore(File(filesDir, "install_journal.json"))
        val appendError = journal.append(
            JournalEntry(
                "op-a",
                JournalStage.TARGET_REPLACED.id,
                1L,
                listOf(JournalTarget("bundle-a", "hash-a", data.length(), listOf("uri-a"), facts))
            )
        )
        assertTrue("fixture journal must write, actual $appendError", appendError == null)

        val context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = filesDir
        }
        val repo = InstalledModRepository(context)
        val applied = repo.recoverFromSharedTree(shared, obs)
        assertTrue("device recoverFromSharedTree must apply, actual $applied", applied is RecoveryApplyResult.Applied)
        assertEquals("Alice", repo.load()["uri-a"]?.modName)
        assertTrue("journal must clear after apply", !repo.journalFile().exists())
        val verdict = repo.checkRecovery()
        assertTrue("after apply must be consistent, actual $verdict", verdict is OperationJournalStore.RecoveryVerdict.Consistent)
    }

    companion object {
        private const val PREFIX = "t08-content-recovery-"
    }
}
