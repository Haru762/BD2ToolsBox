package com.bd2toolsbox.data.repository

import android.content.Context
import com.bd2toolsbox.core.model.OperationError
import com.bd2toolsbox.core.model.OperationErrorKind
import com.bd2toolsbox.core.storage.LedgerWrite
import com.bd2toolsbox.core.storage.JournalEntry
import com.bd2toolsbox.core.storage.JournalRead
import com.bd2toolsbox.core.storage.JournalStage
import com.bd2toolsbox.core.storage.JournalTarget
import com.bd2toolsbox.core.storage.OperationJournalStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `ConvertedInstallSubmitter` 的**真实磁盘行为**（T08）。
 *
 * ## 这一组守什么
 *
 * submitter 把一次「转换产物 → 游戏目录」的提交按
 * **写前意图 → 换文件 → 记账**的顺序编排，让崩溃/取消留下的磁盘状态始终可核对。
 * 这一组用真实的 `InstalledModRepository`（真实文件系统、真实 journal）驱动它，
 * 断言的是**磁盘上留下了什么**与**回调按什么顺序被调**，而不是 mock 的调用次数。
 *
 * ## 桩的边界（只此两处，与 `InstalledModRepositoryLedgerTest` 同口径）
 *
 * 1. `Context.filesDir` → `TemporaryFolder`（仓储唯一的外部依赖）；
 * 2. `android.util.Log` 静态方法（JVM 单测里未 mock 会抛）。
 *
 * `replaceTarget` / `commitLedger` 是被测编排的**输入**，由用例现造 lambda ——
 * 它们不是 fake 掉被测对象，而是 submitter 契约里本就由调用方提供的两个动作。
 */
class ConvertedInstallSubmitterTest {

    @Test
    fun aReplacedFileIsNotACompletedSubmissionWhenLedgerOrReconciliationFailed() {
        val error = OperationError(OperationErrorKind.IO, "ledger failed")
        assertFalse(ConvertedSubmitResult(true, error, "pending", true).completedSuccessfully)
        assertFalse(ConvertedSubmitResult(true, null, "pending", true).completedSuccessfully)
        assertFalse(ConvertedSubmitResult(false, null, "not written", false).completedSuccessfully)
        assertTrue(ConvertedSubmitResult(true, null, "done", false).completedSuccessfully)
    }

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
    fun tearDown() {
        unmockkAll()
    }

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

    private fun readJournal() = OperationJournalStore(repo.journalFile()).read()

    private fun journalStages(): List<String> = when (val read = readJournal()) {
        is JournalRead.Loaded -> read.entries.map { it.stage }
        else -> emptyList()
    }

    // ------------------------------------------------------------- happy path

    /**
     * **顺序正确的成功路径**：begin → staged → 写前意图 → 换文件 → replaced → 记账 → 收尾。
     *
     * 断言两件事：结果是「文件已换 + 无错」；回调**先意图持久化、后换文件**
     * （写前意图必须在真正动文件之前落盘），且账本记账在换文件成功之后。
     */
    @Test
    fun aSuccessfulSubmitReplacesFilesThenCommitsAndClearsTheJournal() = runBlocking {
        val order = mutableListOf<String>()
        val result = submitter.submit(
            operationId = "op-1",
            target = target(),
            replaceTarget = {
                // 写前意图必须已在磁盘上（真正换文件之前）。
                order.add("replace")
                assertTrue(
                    "换文件之前，写前意图必须已持久化",
                    journalStages().contains(JournalStage.TARGET_REPLACING.id)
                )
                true to "已替换"
            },
            commitLedger = {
                order.add("commit")
                null
            }
        )

        assertTrue("文件已换", result.filesReplaced)
        assertNull("一路成功不该有错", result.error)
        assertFalse("成功收尾不需要核对", result.requiresReconciliation)
        assertEquals("回调顺序必须是先换文件、后记账", listOf("replace", "commit"), order)
        assertEquals("成功收尾后 journal 必须被清掉", JournalRead.Absent, readJournal())
    }

    /**
     * **提交目标没有完整的 recoveryFacts 时直接拒绝，不调 replaceTarget，标记需核对。**
     */
    @Test
    fun anIncompleteRecoveryFactsBlocksSubmitWithoutInvokingReplaceTarget() = runBlocking {
        val incompleteTarget = JournalTarget("bundle-x", "hash-x", 4096L, listOf(URI_A), null)
        var replaceCalled = false
        var commitCalled = false

        val result = submitter.submit(
            operationId = "op-3",
            target = incompleteTarget,
            replaceTarget = { replaceCalled = true; true to "不该被调" },
            commitLedger = { commitCalled = true; null }
        )

        assertFalse("不完整的 facts 不许换文件", replaceCalled)
        assertFalse("不完整的 facts 不许记账", commitCalled)
        assertFalse("文件没换", result.filesReplaced)
        assertNotNull("被拒绝必须带错", result.error)
        assertTrue("不完整 facts 导致中断，需标记核对", result.requiresReconciliation)
        assertEquals(OperationErrorKind.INVALID_INPUT, result.error!!.kind)
    }

    // --------------------------------------------------- 门禁：pending / in-flight

    /**
     * **重启后处于待核对 → submit 直接被拒，一个回调都不许触发。**
     *
     * 预置一条未闭合的 TARGET_REPLACED（文件已换、账本未存），模拟上次崩溃。
     * 此时再 submit：`replaceTarget` / `commitLedger` 都不能被调（不许在没核对前叠加覆写），
     * 结果必须标记需要核对。
     */
    @Test
    fun aPendingReconciliationBlocksSubmitWithoutInvokingAnyCallback() = runBlocking {
        // 预置崩溃现场：TARGET_REPLACED 落盘、账本为空。
        repo.journalFile().writeText(
            """{"schemaVersion":1,"entries":[
                 {"operationId":"old","stage":"target_replaced","atMillis":1,
                  "targets":[{"bundleName":"bundle-a","hashDir":"hash-a","dataBytes":1,"modUris":["$URI_A"]}]}
               ]}"""
        )
        assertTrue(
            "前提：确实处于待核对",
            repo.checkRecovery() is OperationJournalStore.RecoveryVerdict.PendingReconciliation
        )
        var replaceCalled = false
        var commitCalled = false

        val result = submitter.submit(
            operationId = "op-new",
            target = target(),
            replaceTarget = { replaceCalled = true; true to "不该被调" },
            commitLedger = { commitCalled = true; null }
        )

        assertFalse("待核对时不许换文件", replaceCalled)
        assertFalse("待核对时不许记账", commitCalled)
        assertFalse("被门禁拒绝时文件没换", result.filesReplaced)
        assertNotNull("被拒绝必须带错误", result.error)
        assertTrue("被门禁拒绝要提示核对", result.requiresReconciliation)
    }

    /**
     * **已有在途提交 → submit 直接被拒，一个回调都不许触发。**
     *
     * 先手动占位一次 beginSubmit（不收尾），再 submit：门禁必须挡住，不动文件、不记账。
     */
    @Test
    fun anInFlightSubmitBlocksAnotherSubmitWithoutInvokingAnyCallback() = runBlocking {
        assertNull("前提：占位一次在途提交", repo.beginSubmit("op-inflight", listOf(target())))
        var replaceCalled = false
        var commitCalled = false

        val result = submitter.submit(
            operationId = "op-2",
            target = target(),
            replaceTarget = { replaceCalled = true; true to "不该被调" },
            commitLedger = { commitCalled = true; null }
        )

        assertFalse("在途时不许换文件", replaceCalled)
        assertFalse("在途时不许记账", commitCalled)
        assertFalse("被门禁拒绝时文件没换", result.filesReplaced)
        assertNotNull("被拒绝必须带错误", result.error)
    }

    // --------------------------------------------------- replaceTarget 返回 false

    /**
     * **`replaceTarget` 返回 false（可能部分写入无法清干净）→ 保留意图、不记账、需核对。**
     *
     * 换文件没成功地完成，但写前意图已落盘 —— submitter 不许清 journal（那条意图要留到
     * 下次启动核对），也不许调 `commitLedger`。
     */
    @Test
    fun aFalseReplaceKeepsTheIntentAndDoesNotCommit() = runBlocking {
        var commitCalled = false
        val result = submitter.submit(
            operationId = "op-1",
            target = target(),
            replaceTarget = { false to "换文件失败" },
            commitLedger = { commitCalled = true; null }
        )

        assertFalse("换文件没成功", result.filesReplaced)
        assertFalse("换文件失败不许记账", commitCalled)
        assertTrue("false 可能部分写入 → 需核对", result.requiresReconciliation)
        assertTrue(
            "写前意图必须留在 journal 里",
            journalStages().contains(JournalStage.TARGET_REPLACING.id)
        )
        assertFalse(
            "没换成功不许落 TARGET_REPLACED",
            journalStages().contains(JournalStage.TARGET_REPLACED.id)
        )
    }

    // --------------------------------------------------- 异常 / 取消：释放锁但留证据

    /**
     * **`replaceTarget` 抛异常 → 释放在途所有权（能重试）但保留意图证据，并重抛。**
     */
    @Test
    fun anExceptionDuringReplaceReleasesTheLockButKeepsTheIntentAndRethrows() {
        val boom = RuntimeException("换文件炸了")
        try {
            runBlocking {
                submitter.submit(
                    operationId = "op-1",
                    target = target(),
                    replaceTarget = { throw boom },
                    commitLedger = { null }
                )
            }
            fail("异常必须重抛，不能被吞掉")
        } catch (e: RuntimeException) {
            assertEquals("必须是原始异常", boom, e)
        }

        // 所有权已释放：新的 beginSubmit 不会因为「在途」被拒（journal 里意图证据仍在，
        // 因此门禁可能因待核对而拒 —— 但那属于「有证据」，不是「在途占用」）。
        assertTrue(
            "写前意图证据必须保留",
            journalStages().contains(JournalStage.TARGET_REPLACING.id)
        )
        assertTrue(
            "留下的证据要让下次核对报待核对",
            repo.checkRecovery() is OperationJournalStore.RecoveryVerdict.PendingReconciliation
        )
    }

    /**
     * **`replaceTarget` 被取消 → 释放所有权但保留意图证据，并重抛 CancellationException。**
     */
    @Test
    fun aCancellationDuringReplaceReleasesTheLockButKeepsTheIntentAndRethrows() {
        try {
            runBlocking {
                submitter.submit(
                    operationId = "op-1",
                    target = target(),
                    replaceTarget = { throw CancellationException("取消了") },
                    commitLedger = { null }
                )
            }
            fail("取消必须重抛")
        } catch (e: CancellationException) {
            // 期望
        }

        assertTrue(
            "取消也要保留写前意图证据",
            journalStages().contains(JournalStage.TARGET_REPLACING.id)
        )
    }

    // --------------------------------------------------- 记账失败：文件已换、账本未存

    /**
     * **换文件成功、`commitLedger` 失败 → filesReplaced=true、带错、需核对、保留窗口。**
     *
     * 这是「文件已换、账本未存」窗口的正面：物理事实（换了）与记账事实（没记上）分开表达，
     * journal 里那条 TARGET_REPLACED 必须留着，下次启动仍报待核对。
     */
    @Test
    fun aLedgerCommitFailureReportsFilesReplacedAndKeepsTheWindow() = runBlocking {
        val ledgerError = OperationError(
            kind = OperationErrorKind.IO,
            message = "账本写失败",
            target = "bundle-a"
        )
        val result = submitter.submit(
            operationId = "op-1",
            target = target(),
            replaceTarget = { true to "已替换" },
            commitLedger = { ledgerError }
        )

        assertTrue("文件确实换了", result.filesReplaced)
        assertNotNull("记账失败必须带错", result.error)
        assertTrue("文件已换、账本未存 → 需核对", result.requiresReconciliation)
        assertTrue(
            "TARGET_REPLACED 必须留着，下次启动查得出窗口",
            journalStages().contains(JournalStage.TARGET_REPLACED.id)
        )
        assertTrue(
            "重启核对必须报待核对",
            repo.checkRecovery() is OperationJournalStore.RecoveryVerdict.PendingReconciliation
        )
    }

    // --------------------------------------------------- 旧同 URI/hash 不掩盖

    /**
     * **账本里已有相同 URI/hash 的旧记录，也不能掩盖本次未闭合的写前意图。**
     *
     * 预置：账本里 `URI_A → bundle-a` 已存在（上一次安装留下）。本次 submit 的 `replaceTarget`
     * 返回 false（没换成）。因为写前意图已落盘、且本次没有它自己的 commit，
     * 核对必须报待核对 —— 不许因为账本里恰好有同 URI/hash 的旧记录就判「一致」。
     */
    @Test
    fun anExistingSameUriLedgerRecordDoesNotMaskThisUnclosedIntent() = runBlocking {
        // 预置旧账本记录（同 URI、同 bundle）。
        assertEquals(
            LedgerWrite.Written,
            repo.put(
                InstalledModRecordFactory.of(URI_A, "bundle-a")
            )
        )

        val result = submitter.submit(
            operationId = "op-1",
            target = target(),
            replaceTarget = { false to "没换成" },
            commitLedger = { null }
        )

        assertFalse("没换成", result.filesReplaced)
        assertTrue("未闭合意图必须需核对", result.requiresReconciliation)
        assertTrue(
            "旧同 URI/hash 记录不能把未闭合意图伪装成一致",
            repo.checkRecovery() is OperationJournalStore.RecoveryVerdict.PendingReconciliation
        )
    }

    // --------------------------------------------------- 成功清理后可再次提交

    /**
     * **一次成功提交彻底清干净，下一次提交能正常开始。**
     *
     * 成功收尾会清 journal、释放所有权。紧接着的第二次 submit 不该被「在途」或「待核对」挡住。
     */
    @Test
    fun aSuccessfulSubmitLeavesRoomForTheNextOne() = runBlocking {
        val first = submitter.submit(
            operationId = "op-1",
            target = target("bundle-a", URI_A),
            replaceTarget = { true to "已替换" },
            commitLedger = { null }
        )
        assertTrue("第一次必须成功", first.filesReplaced && first.error == null)
        assertEquals("第一次收尾后 journal 清空", JournalRead.Absent, readJournal())

        var secondReplaceCalled = false
        val second = submitter.submit(
            operationId = "op-2",
            target = target("bundle-b", URI_B),
            replaceTarget = { secondReplaceCalled = true; true to "已替换" },
            commitLedger = { null }
        )

        assertTrue("第二次的换文件回调必须真的被调（没被门禁挡）", secondReplaceCalled)
        assertTrue("第二次也成功", second.filesReplaced && second.error == null)
        assertEquals("第二次收尾后 journal 也清空", JournalRead.Absent, readJournal())
    }

    companion object {
        private const val URI_A = "content://sample/uri-a"
        private const val URI_B = "content://sample/uri-b"
    }
}

/** 测试用 [InstalledModRecord] 构造器（只测试用，不进生产）。 */
private object InstalledModRecordFactory {
    fun of(uri: String, bundle: String) = com.bd2toolsbox.data.model.InstalledModRecord(
        modUri = uri,
        modName = uri,
        familyKey = "family-$uri",
        snapshotTargetHash = bundle,
        quality = "HD",
        usedAstc = false,
        installedAt = 1L
    )
}
