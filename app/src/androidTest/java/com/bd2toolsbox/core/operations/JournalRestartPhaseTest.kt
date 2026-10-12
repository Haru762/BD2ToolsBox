package com.bd2toolsbox.core.operations

import android.app.Instrumentation
import android.os.Bundle
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bd2toolsbox.core.storage.JournalEntry
import com.bd2toolsbox.core.storage.JournalStage
import com.bd2toolsbox.core.storage.JournalTarget
import com.bd2toolsbox.core.storage.OperationJournalStore
import com.bd2toolsbox.data.repository.InstalledModRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * T25：**真实「杀进程再启动」**的恢复验收（设备，分阶段）。
 *
 * 为什么单独一个类：这三条 phase **必须由外部编排**驱动 —— 阶段之间要真的
 * `am force-stop`（`Test-Bd2JournalRecovery` 干这件事，与 `Invoke-Bd2SnapshotPhases`
 * 同一手法）。它们要求 `-e bd2RunId`，整类单独跑会因为没有 runId 而失败，
 * 所以**不能**和进程内分类用例混在一个类里 —— 那样 `ProcessDeathRecoveryTest`
 * 整类就再也跑不了了（实测踩过）。
 *
 * 判据是 **PID 必须不同**：写日志的进程与读日志的进程是两个进程，
 * 后者看到的是从磁盘读回来的、被另一个进程写下的 journal。
 * 单靠同一个进程里「自己写自己读」证明不了重启恢复。
 *
 * 只动应用私有目录里的 journal，不碰账本、游戏目录或备份。
 */
@RunWith(AndroidJUnit4::class)
class JournalRestartPhaseTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository get() = InstalledModRepository(context)
    private val journalFile: File get() = repository.journalFile()

    private fun append(op: String, stage: JournalStage, bundle: String = "t25_restart") {
        val entry = JournalEntry(
            operationId = op,
            stage = stage.id,
            atMillis = System.currentTimeMillis(),
            targets = listOf(JournalTarget(bundle, "hash", 16L, listOf("content://t25/$bundle")))
        )
        assertEquals(null, OperationJournalStore(journalFile).append(entry))
    }

    private fun recovery() = OperationRecovery(repository)

    /** 阶段一：写下「已确认替换但没有账本提交」的日志，然后进程被外部杀掉。 */
    @Test
    fun writeInterruptedJournalPhase() {
        val runId = requiredRunId()
        // 从干净状态开始，免得上一轮残留混进来
        OperationJournalStore(journalFile).clear()
        append("$runId-op", JournalStage.TARGET_REPLACING)
        append("$runId-op", JournalStage.TARGET_REPLACED)
        val pending = recovery().classify().count {
            it is RecoveryDisposition.NeedsReconciliation || it is RecoveryDisposition.Interrupted
        }
        marker("write", runId, pending)
        assertEquals("写完就必须能读到一条待处理", 1, pending)
    }

    /**
     * 阶段二：**新进程**读阶段一留下的日志。
     *
     * 这是「真实杀进程再启动」的判据 —— 日志不是本进程写的，是从磁盘读回来的。
     */
    @Test
    fun verifyAfterRealRestartPhase() {
        val runId = requiredRunId()
        val mine = recovery().classify().filter { d ->
            when (d) {
                is RecoveryDisposition.Interrupted -> d.operationId == "$runId-op"
                is RecoveryDisposition.NeedsReconciliation -> d.operationId == "$runId-op"
                is RecoveryDisposition.Committed -> d.operationId == "$runId-op"
                is RecoveryDisposition.Unknown -> false
            }
        }
        marker("verify", runId, mine.size)
        assertEquals("重启后必须读到上一条日志", 1, mine.size)
        // 只落到 TARGET_REPLACED → 待核对；**不能**被当成已提交
        assertTrue("重启后必须判待核对", mine.single() is RecoveryDisposition.NeedsReconciliation)
        assertFalse("不得被当成已提交", mine.single() is RecoveryDisposition.Committed)
    }

    /** 阶段三：清掉本次留下的日志（编排侧的 finally 一定会调）。 */
    @Test
    fun cleanupJournalPhase() {
        val runId = requiredRunId()
        OperationJournalStore(journalFile).clear()
        marker("cleanup", runId, 0)
    }

    /**
     * 把标记送回 `am instrument` 的原始输出。
     *
     * **不能**用 `Log.i` 或 `println`：AndroidJUnitRunner 不把 stdout 带回来，
     * 标记只会进 logcat，编排侧就找不到（`ReleaseSnapshotProbeTest` 的注释里
     * 记着同一条坑）。走 `sendStatus` 的 `REPORT_KEY_STREAMRESULT`，status 码用
     * **2**（非终态 progress）而不是 0 —— 0 是「单个测试通过」的码，
     * 拿它发标记会把一次 phase 冒充成额外的 pass。
     */
    private fun marker(phase: String, runId: String, pending: Int) {
        val bundle = Bundle()
        bundle.putString(
            Instrumentation.REPORT_KEY_STREAMRESULT,
            "$TAG phase=$phase runId=$runId pid=${Process.myPid()} pending=$pending\n"
        )
        InstrumentationRegistry.getInstrumentation().sendStatus(2, bundle)
    }

    /** runId 由编排侧通过 `-e bd2RunId` 传入；缺失即失败，不给默认值。 */
    private fun requiredRunId(): String {
        val runId = InstrumentationRegistry.getArguments().getString("bd2RunId")
        require(!runId.isNullOrBlank()) { "缺少 -e bd2RunId（本类只由 Test-Bd2JournalRecovery 驱动）" }
        require(runId.matches(Regex("^[A-Za-z0-9-]{1,80}$"))) { "runId 含不安全字符" }
        return runId
    }

    private companion object {
        const val TAG = "BD2_JOURNAL_PROBE"
    }
}
