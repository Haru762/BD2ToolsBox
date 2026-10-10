package com.bd2toolsbox.core.operations

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bd2toolsbox.core.storage.JournalEntry
import com.bd2toolsbox.core.storage.JournalStage
import com.bd2toolsbox.core.storage.JournalTarget
import com.bd2toolsbox.core.storage.OperationJournalStore
import com.bd2toolsbox.data.repository.InstalledModRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 进程死亡后的安全收尾（T25）—— **设备侧只验证 journal 的落盘/读回与分类**。
 *
 * 真实「杀进程再启动」的循环不在本类里：instrumentation 无法杀掉自己的进程后继续执行。
 * 这里用应用真实 `filesDir` 上的 journal 文件构造一次「重启后的分类」，
 * 覆盖 JVM 单测无法覆盖的部分（真实序列化/R8/文件系统）。
 *
 * 只写应用私有目录，不碰游戏目录、MOD 源目录或备份；`finally` 里清干净。
 */
@RunWith(AndroidJUnit4::class)
class ProcessDeathRecoveryTest {
    private lateinit var context: android.content.Context
    private lateinit var repository: InstalledModRepository
    private lateinit var journalFile: File

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        repository = InstalledModRepository(context)
        journalFile = repository.journalFile()
        // 每个用例从干净状态开始：只清自己的 journal，不动账本与其它文件。
        OperationJournalStore(journalFile).clear()
    }

    @After
    fun tearDown() {
        OperationJournalStore(journalFile).clear()
    }

    private fun append(op: String, stage: JournalStage, bundle: String = "t25_bundle") {
        val entry = JournalEntry(
            operationId = op,
            stage = stage.id,
            atMillis = System.currentTimeMillis(),
            targets = listOf(JournalTarget(bundle, "hash", 16L, listOf("content://t25/$bundle")))
        )
        assertEquals(null, OperationJournalStore(journalFile).append(entry))
    }

    private fun recovery() = OperationRecovery(repository)

    @Test
    fun intentOnlyIsClassifiedAsInterrupted() {
        append("t25-op-1", JournalStage.TARGET_REPLACING)
        val d = recovery().classify().single()
        assertTrue("只落写前意图应判中断", d is RecoveryDisposition.Interrupted)
    }

    @Test
    fun confirmedReplaceIsClassifiedAsNeedsReconciliation() {
        append("t25-op-2", JournalStage.TARGET_REPLACED)
        val d = recovery().classify().single()
        assertTrue("已确认替换应判待核对", d is RecoveryDisposition.NeedsReconciliation)
    }

    @Test
    fun ledgerCommitAfterReplaceIsClassifiedAsCommitted() {
        append("t25-op-3", JournalStage.TARGET_REPLACED)
        append("t25-op-3", JournalStage.LEDGER_COMMITTED)
        val d = recovery().classify().single()
        assertTrue("替换+提交应判已提交", d is RecoveryDisposition.Committed)
    }

    @Test
    fun cleanupPathOutsideAppOwnedRootsIsRejected() {
        val cache = context.cacheDir
        val external = context.getExternalFilesDir(null) ?: cache
        assertTrue(OperationRecovery.isCleanupPathAllowed(File(cache, "t25/tmp"), listOf(cache, external)))
        // 游戏目录 / 用户 MOD 根都不是本 app 的工作目录，必须拒绝。
        assertFalse(OperationRecovery.isCleanupPathAllowed(File("/sdcard/Download/Shared"), listOf(cache, external)))
        assertFalse(OperationRecovery.isCleanupPathAllowed(File(cache, "../escape"), listOf(cache, external)))
    }
}
