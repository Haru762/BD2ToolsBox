package com.bd2toolsbox.data.repository

import android.content.Context
import com.bd2toolsbox.core.model.OperationError
import com.bd2toolsbox.core.model.OperationErrorKind
import com.bd2toolsbox.core.storage.JournalRead
import com.bd2toolsbox.core.storage.JournalStage
import com.bd2toolsbox.core.storage.JournalTarget
import com.bd2toolsbox.core.storage.OperationJournalStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `ConvertedInstallSubmitter` 的**边界错误传递**（T08 补测）。
 *
 * 这一组只证明**编排在每个 journal 落盘失败点上把错误正确传出、且不越界推进**，
 * 用 `spyk` 只注入**单个** journal 方法返回错误 —— 其余 journal 与文件行为全走真实
 * `InstalledModRepository`（真实文件系统、真实 journal）。这**不是**在伪称真实磁盘
 * I/O 故障：注入的是「某一步记账返回了错误」这个被测编排本就要处理的输入分支。
 *
 * 桩边界与 `ConvertedInstallSubmitterTest` 同口径：`Context.filesDir` → `TemporaryFolder`、
 * `android.util.Log` 静态方法。`replaceTarget` / `commitLedger` 是编排的输入 lambda。
 */
class ConvertedSubmitDurabilityTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var context: Context
    private lateinit var repo: InstalledModRepository
    private lateinit var submitter: ConvertedInstallSubmitter

    @Before
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0

        filesDir = temp.newFolder("files")
        context = mockk<Context>()
        every { context.filesDir } returns filesDir
        repo = InstalledModRepository(context)
        submitter = ConvertedInstallSubmitter(repo)
    }

    @After
    fun tearDown() = unmockkAll()

    // ------------------------------------------------------------- 小辅助

    private fun target(bundle: String = "bundle-a", uri: String = URI_A) =
        JournalTarget(
            bundleName = bundle,
            hashDir = "hash-$bundle",
            dataBytes = 4096L,
            modUris = listOf(uri),
            recoveryFacts = com.bd2toolsbox.core.storage.JournalRecoveryFacts(
                formatVersion = 1,
                expectedContentSha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                ledgerUpserts = listOf(
                    com.bd2toolsbox.core.storage.LedgerRecord(
                        modUri = uri,
                        modName = "mod",
                        familyKey = "family",
                        snapshotTargetHash = bundle,
                        quality = "HD",
                        usedAstc = false,
                        installedAt = 1000L
                    )
                )
            )
        )

    private fun ioError(msg: String) =
        OperationError(kind = OperationErrorKind.IO, message = msg, target = "journal")

    /** 直接从磁盘读回 journal 阶段（不经内存态）。 */
    private fun stagesOnDisk(): List<String> =
        when (val r = OperationJournalStore(repo.journalFile()).read()) {
            is JournalRead.Loaded -> r.entries.map { it.stage }
            else -> emptyList()
        }

    // ------------------------------------------------- markStaged 落盘失败

    /** markStaged 失败 → 尚未动文件，replaceTarget / commitLedger 均 0，错误传出。 */
    @Test
    fun aStagedJournalErrorAbortsBeforeReplaceAndCommit() = runBlocking {
        val spied = spyk(repo)
        every { spied.markStaged(any(), any()) } returns ioError("STAGED 落盘失败")
        val submitter = ConvertedInstallSubmitter(spied)

        var replaceCalled = false
        var commitCalled = false
        val result = submitter.submit(
            operationId = "op-staged",
            target = target(),
            replaceTarget = { replaceCalled = true; true to "不该被调" },
            commitLedger = { commitCalled = true; null }
        )

        assertFalse("markStaged 失败后不许换文件", replaceCalled)
        assertFalse("markStaged 失败后不许记账", commitCalled)
        assertFalse("没换文件", result.filesReplaced)
        assertNotNull("边界错误必须传出", result.error)
    }

    // -------------------------------------------- markTargetReplacing 落盘失败

    /** 写前意图落盘失败 → 意图未持久化就不许动文件，replace / commit 均 0。 */
    @Test
    fun aReplacingIntentJournalErrorAbortsBeforeReplaceAndCommit() = runBlocking {
        val spied = spyk(repo)
        every { spied.markTargetReplacing(any(), any()) } returns ioError("写前意图落盘失败")
        val submitter = ConvertedInstallSubmitter(spied)

        var replaceCalled = false
        var commitCalled = false
        val result = submitter.submit(
            operationId = "op-replacing",
            target = target(),
            replaceTarget = { replaceCalled = true; true to "不该被调" },
            commitLedger = { commitCalled = true; null }
        )

        assertFalse("写前意图未持久化就不许换文件", replaceCalled)
        assertFalse("写前意图未持久化就不许记账", commitCalled)
        assertFalse("没换文件", result.filesReplaced)
        assertNotNull("边界错误必须传出", result.error)
    }

    // -------------------------------------------- markTargetReplaced 落盘失败

    /**
     * 物理换文件已成功、但 TARGET_REPLACED 记账失败 → filesReplaced 仍为真、
     * commitLedger 回调 0；真实写前意图仍在盘上未闭合 → 启动核对报待核对。
     */
    @Test
    fun aReplacedJournalErrorStillReportsFilesReplacedAndSkipsLedger() = runBlocking {
        val spied = spyk(repo)
        every { spied.markTargetReplaced(any(), any()) } returns ioError("TARGET_REPLACED 落盘失败")
        val submitter = ConvertedInstallSubmitter(spied)

        var commitCalled = false
        val result = submitter.submit(
            operationId = "op-replaced",
            target = target(),
            replaceTarget = { true to "已替换" }, // 物理换文件成功
            commitLedger = { commitCalled = true; null }
        )

        assertTrue("物理换文件成功 → filesReplaced 必须为真", result.filesReplaced)
        assertFalse("TARGET_REPLACED 记账失败不许再记账本", commitCalled)
        assertNotNull("边界错误必须传出", result.error)
        assertTrue(
            "真实写前意图仍在磁盘上（只注入了 markTargetReplaced 失败）",
            stagesOnDisk().contains(JournalStage.TARGET_REPLACING.id)
        )
        assertTrue(
            "未闭合意图 → 启动核对必须报待核对",
            repo.checkRecovery() is OperationJournalStore.RecoveryVerdict.PendingReconciliation
        )
    }

    // -------------------------------------------- markLedgerCommitted 落盘失败

    /**
     * 账本回调成功、finishSubmit 写 LEDGER_COMMITTED marker 失败 → 真实收尾保留窗口
     * （TARGET_REPLACED 留盘、无 LEDGER_COMMITTED），返回错误且释放 token：
     * 下一次被拒必须因**待核对**而不是**在途**。
     */
    @Test
    fun aLedgerCommittedMarkerErrorReleasesTokenAndRejectsNextAsPending() = runBlocking {
        val spied = spyk(repo)
        every { spied.markLedgerCommitted(any()) } returns ioError("LEDGER_COMMITTED 落盘失败")
        val submitter = ConvertedInstallSubmitter(spied)

        val result = submitter.submit(
            operationId = "op-marker",
            target = target(),
            replaceTarget = { true to "已替换" },
            commitLedger = { null } // 账本本身成功
        )

        assertTrue("文件已换", result.filesReplaced)
        assertNotNull("marker 边界错误必须传出", result.error)
        assertTrue("窗口保留：TARGET_REPLACED 必须留盘",
            stagesOnDisk().contains(JournalStage.TARGET_REPLACED.id))
        assertFalse("marker 失败不许出现 LEDGER_COMMITTED",
            stagesOnDisk().contains(JournalStage.LEDGER_COMMITTED.id))

        val gate = spied.checkSubmitAllowed()
        assertNotNull("仍应被门禁拒绝", gate)
        assertTrue(
            "拒绝原因必须是待核对而非在途（token 已释放）：${gate?.message}",
            gate!!.message.contains("未核对")
        )
    }

    // -------------------------------------------- 被拒的第二次不得窃取 owner

    /**
     * 已有在途提交时，第二次 submit（**故意用相同 operationId**）被门禁拒绝：
     * 它不得在 finally 里 releaseSubmit(相同 id) 把先前占位清掉。若实现现状用
     * 无条件 `finally { releaseSubmit(operationId) }`，本测试会红 —— 正是要暴露它。
     */
    @Test
    fun aRejectedSubmitMustNotReleaseAnotherInflightOwnerEvenWithSameId() = runBlocking {
        assertNull("前提：先占位一次在途提交", repo.beginSubmit("op-dup", listOf(target())))

        var replaceCalled = false
        var commitCalled = false
        val result = submitter.submit(
            operationId = "op-dup", // 故意用相同 id
            target = target(),
            replaceTarget = { replaceCalled = true; true to "不该被调" },
            commitLedger = { commitCalled = true; null }
        )

        assertFalse("在途时不许换文件", replaceCalled)
        assertFalse("在途时不许记账", commitCalled)
        assertNotNull("第二次必须被拒", result.error)

        val gate = repo.checkSubmitAllowed()
        assertNotNull("先前在途占位必须仍保留（未被同 id 的被拒提交窃取释放）", gate)
        assertTrue(
            "占位仍应报在途（含 op-dup），而非被释放后判一致：${gate?.message}",
            gate!!.message.contains("op-dup")
        )
    }

    companion object {
        private const val URI_A = "content://sample/uri-a"
    }
}
