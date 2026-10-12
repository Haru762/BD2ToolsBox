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
 * 进程死亡后的安全收尾（T25）。
 *
 * **进程内**的分类与归属校验：用应用真实 `filesDir` 上的 journal 构造三种落盘形态，
 * 断言分类结果与清理路径归属校验。覆盖 JVM 单测碰不到的部分（真实序列化/R8/文件系统）。
 *
 * **真实「杀进程再启动」在 [JournalRestartPhaseTest]**：那三条 phase 必须由外部
 * `am force-stop` 编排（`Test-Bd2JournalRecovery`），且要求 `-e bd2RunId`，
 * 所以**不能**混进本类 —— 混进来会让本类整类跑不了（实测踩过）。
 *
 * 只写应用私有目录，不碰游戏目录、MOD 源目录或备份；`@After` 里清干净。
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
    }

    /**
     * 真实杀进程的 phase 用例必须让日志**活到下一个进程**。
     *
     * `@After` 默认清日志（进程内用例之间要互不干扰），但写阶段写完就清的话，
     * 下一个进程读到的永远是 0 条 —— 那是**假的验证**。实测踩过两次：
     * 先是 `@Before` 清、后是 `@After` 清，两次都表现为「重启后读到 0 条」。
     * 所以 phase 用例显式把它置真，只有 `cleanupJournalPhase` 不置。
     */
    private var keepJournalAfterTest = false

    @After
    fun tearDown() {
        if (!keepJournalAfterTest) {
            OperationJournalStore(journalFile).clear()
        }
    }

    /**
     * 进程内用例的「从干净状态开始」。
     *
     * **刻意不放在 `@Before`**：真实杀进程的三个 phase 是**跨进程**的 ——
     * 写阶段留下的日志必须活到读阶段。如果 `@Before` 无条件 clear，读阶段会把
     * 上一阶段写的日志先擦掉，然后断言「重启后读到 0 条」——那是一个**假的通过**，
     * 或者像本轮一样直接失败。实测踩过。
     */
    private fun resetJournal() {
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

    private companion object {
        const val TAG = "BD2_JOURNAL_PROBE"
    }

    @Test
    fun intentOnlyIsClassifiedAsInterrupted() {
        resetJournal()
        append("t25-op-1", JournalStage.TARGET_REPLACING)
        val d = recovery().classify().single()
        assertTrue("只落写前意图应判中断", d is RecoveryDisposition.Interrupted)
    }

    @Test
    fun confirmedReplaceIsClassifiedAsNeedsReconciliation() {
        resetJournal()
        append("t25-op-2", JournalStage.TARGET_REPLACED)
        val d = recovery().classify().single()
        assertTrue("已确认替换应判待核对", d is RecoveryDisposition.NeedsReconciliation)
    }

    @Test
    fun ledgerCommitAfterReplaceIsClassifiedAsCommitted() {
        resetJournal()
        append("t25-op-3", JournalStage.TARGET_REPLACED)
        append("t25-op-3", JournalStage.LEDGER_COMMITTED)
        val d = recovery().classify().single()
        assertTrue("替换+提交应判已提交", d is RecoveryDisposition.Committed)
    }

    @Test
    fun cleanupPathOutsideAppOwnedRootsIsRejected() {
        resetJournal()
        val cache = context.cacheDir
        val external = context.getExternalFilesDir(null) ?: cache
        assertTrue(OperationRecovery.isCleanupPathAllowed(File(cache, "t25/tmp"), listOf(cache, external)))
        // 游戏目录 / 用户 MOD 根都不是本 app 的工作目录，必须拒绝。
        assertFalse(OperationRecovery.isCleanupPathAllowed(File("/sdcard/Download/Shared"), listOf(cache, external)))
        assertFalse(OperationRecovery.isCleanupPathAllowed(File(cache, "../escape"), listOf(cache, external)))
    }
}
