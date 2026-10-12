package com.bd2toolsbox.core.operations

import android.app.Instrumentation
import android.os.Bundle
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bd2toolsbox.core.storage.ContentAuditResult
import com.bd2toolsbox.core.storage.ExpectedTarget
import com.bd2toolsbox.core.storage.JournalEntry
import com.bd2toolsbox.core.storage.JournalRecoveryFacts
import com.bd2toolsbox.core.storage.JournalStage
import com.bd2toolsbox.core.storage.JournalTarget
import com.bd2toolsbox.core.storage.LedgerRecord
import com.bd2toolsbox.core.storage.OperationJournalStore
import com.bd2toolsbox.core.storage.RecoveryObservationLayout
import com.bd2toolsbox.core.storage.TargetContentAudit
import com.bd2toolsbox.core.storage.TargetObservation
import com.bd2toolsbox.data.repository.InstalledModRepository
import com.bd2toolsbox.service.ShizukuFileService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * T25：**在隔离数据上真的跑一次「装到一半被杀 → 重启 → 核对」**。
 *
 * 与 [JournalRestartPhaseTest] 的区别：那个只动应用私有目录里的 journal，
 * 证明的是「重启后能读回并分类」。这里**真的替换一个文件**（走生产
 * `ShizukuFileService.copyFile` → `AtomicFileWriter` 的原子替换），
 * 然后让进程被杀，新进程再核对「磁盘上到底是什么」。
 *
 * ## 隔离
 *
 * 全程只用 `context.cacheDir` 下的临时目录（[rootFor]）：
 *   · `staging/` 扮演「下载/转换产物目录」；
 *   · `dest/` 扮演「游戏目录」，真实路径由 [RecoveryObservationLayout] 按
 *     `operationId/bundle/hash/__data` 拼出来。
 * **不碰真实游戏目录、MOD 源目录或备份**；`cleanup` 阶段整个删掉。
 *
 * ## 三个阶段证明什么
 *
 * 1. `stage`：真实替换文件 + 写 `TARGET_REPLACED` 日志，**不写账本**（那个窗口），
 *    然后进程被外部 `am force-stop` 杀掉；
 * 2. `verify`（新进程）：分类必须是 `NeedsReconciliation`；
 *    [TargetContentAudit] 必须判 `AllMatched`（文件**确实**换成了新内容）；
 *    账本里**没有**记录（窗口是真的）；staging 文件**还在**（没有被自动清理）；
 * 3. `cleanup`：清日志 + 删隔离目录。
 */
@RunWith(AndroidJUnit4::class)
class IsolatedCommitRecoveryPhaseTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository get() = InstalledModRepository(context)

    private fun rootFor(runId: String): File {
        val root = File(context.cacheDir, "t25-isolated/$runId")
        check(root.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator)) {
            "隔离目录必须在 cacheDir 下"
        }
        return root
    }

    private fun stagingFile(runId: String, bundle: String, hash: String): File =
        File(rootFor(runId), "staging/$bundle/$hash/__data")

    private fun destFile(runId: String, opId: String, bundle: String, hash: String): File =
        RecoveryObservationLayout.destFile(File(rootFor(runId), "dest"), opId, bundle, hash)

    private fun sha256(file: File): String? {
        if (!file.isFile) return null
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                md.update(buf, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 阶段一：真实替换一个文件，写下「已替换、账本未提交」的日志，然后进程被杀。 */
    @Test
    fun stageInterruptedCommitPhase() {
        val runId = requiredRunId()
        val opId = "$runId-op"
        val bundle = "t25_isolated"
        val hash = "hash1"
        val root = rootFor(runId)
        root.deleteRecursively()
        check(root.mkdirs())

        // 「下载/转换产物」：内容确定，便于下一进程复算哈希
        val payload = ByteArray(4096) { (it % 251).toByte() }
        val staging = stagingFile(runId, bundle, hash)
        staging.parentFile?.mkdirs()
        staging.writeBytes(payload)

        // 目标先放旧内容，再走生产提交路径替换 —— 这是真实的「文件已换」状态
        val dest = destFile(runId, opId, bundle, hash)
        dest.parentFile?.mkdirs()
        dest.writeBytes(ByteArray(16) { 0x11 })

        val service = ShizukuFileService()
        assertTrue("生产提交必须成功", service.copyFile(staging.absolutePath, dest.absolutePath))

        val expectedSha = sha256(staging)
        assertNotNull(expectedSha)
        assertEquals("替换后目标内容必须等于产物", expectedSha, sha256(dest))

        val journal = OperationJournalStore(repository.journalFile())
        journal.clear()
        val target = JournalTarget(
            bundleName = bundle,
            hashDir = hash,
            dataBytes = payload.size.toLong(),
            modUris = listOf("content://t25/isolated"),
            recoveryFacts = JournalRecoveryFacts(
                formatVersion = 1,
                expectedContentSha256 = expectedSha!!,
                ledgerUpserts = listOf(
                    LedgerRecord(
                        modUri = "content://t25/isolated",
                        modName = "isolated",
                        familyKey = bundle,
                        snapshotTargetHash = bundle,
                        quality = "SD",
                        usedAstc = false,
                        installedAt = System.currentTimeMillis()
                    )
                )
            )
        )
        assertEquals(null, journal.append(JournalEntry(opId, JournalStage.TARGET_REPLACING.id, System.currentTimeMillis(), listOf(target))))
        assertEquals(null, journal.append(JournalEntry(opId, JournalStage.TARGET_REPLACED.id, System.currentTimeMillis(), listOf(target))))
        // **刻意不写账本** —— 这就是「文件已换、账本未记」的窗口

        marker("stage", runId, 1)
    }

    /** 阶段二（新进程）：核对分类、磁盘内容与「什么都没被自动动过」。 */
    @Test
    fun verifyIsolatedRecoveryAfterRestartPhase() {
        val runId = requiredRunId()
        val opId = "$runId-op"
        val bundle = "t25_isolated"
        val hash = "hash1"
        val root = rootFor(runId)
        val dest = destFile(runId, opId, bundle, hash)
        val staging = stagingFile(runId, bundle, hash)

        // 1) 分类：必须是待核对，不能是已提交
        val mine = OperationRecovery(repository).classify().filter { d ->
            when (d) {
                is RecoveryDisposition.Interrupted -> d.operationId == opId
                is RecoveryDisposition.NeedsReconciliation -> d.operationId == opId
                is RecoveryDisposition.Committed -> d.operationId == opId
                is RecoveryDisposition.Unknown -> false
            }
        }
        assertEquals("重启后必须读到那条日志", 1, mine.size)
        assertTrue("必须判待核对", mine.single() is RecoveryDisposition.NeedsReconciliation)
        assertFalse("不得判已提交", mine.single() is RecoveryDisposition.Committed)

        // 2) 磁盘内容核对：文件确实换成了产物内容
        val actual = sha256(dest)
        assertNotNull("目标文件必须还在", actual)
        val expectedSha = sha256(staging)
        assertNotNull("staging 文件必须还在（没有被自动清理）", expectedSha)
        val audit = TargetContentAudit().audit(
            expected = listOf(ExpectedTarget(opId, bundle, hash, expectedSha)),
            observations = listOf(TargetObservation.Observed(opId, bundle, hash, actual!!))
        )
        assertTrue(
            "内容核对必须是 AllMatched，实际 $audit",
            audit is ContentAuditResult.AllMatched
        )

        // 3) 账本里没有记录 —— 窗口是真的
        val ledger = repository.load()
        assertFalse(
            "本阶段刻意没写账本，重启后也不许凭空多出来",
            ledger.containsKey("content://t25/isolated")
        )

        // 4) 隔离目录里没有 `.part` 残留（原子替换必须清干净）
        val leftovers = File(root, "dest").walkTopDown()
            .filter { it.isFile && it.name.endsWith(".part") }.toList()
        assertEquals("原子替换不许留下 .part", emptyList<File>(), leftovers)

        marker("verify", runId, mine.size)
    }

    /** 阶段三：清日志 + 删隔离目录。 */
    @Test
    fun cleanupIsolatedRecoveryPhase() {
        val runId = requiredRunId()
        OperationJournalStore(repository.journalFile()).clear()
        rootFor(runId).deleteRecursively()
        marker("cleanup", runId, 0)
    }

    private fun marker(phase: String, runId: String, pending: Int) {
        val bundle = Bundle()
        bundle.putString(
            Instrumentation.REPORT_KEY_STREAMRESULT,
            "$TAG phase=$phase runId=$runId pid=${Process.myPid()} pending=$pending\n"
        )
        InstrumentationRegistry.getInstrumentation().sendStatus(2, bundle)
    }

    private fun requiredRunId(): String {
        val runId = InstrumentationRegistry.getArguments().getString("bd2RunId")
        require(!runId.isNullOrBlank()) { "缺少 -e bd2RunId（本类只由 Test-Bd2IsolatedRecovery 驱动）" }
        require(runId.matches(Regex("^[A-Za-z0-9-]{1,80}$"))) { "runId 含不安全字符" }
        return runId
    }

    private companion object {
        const val TAG = "BD2_ISOLATED_PROBE"
    }
}
