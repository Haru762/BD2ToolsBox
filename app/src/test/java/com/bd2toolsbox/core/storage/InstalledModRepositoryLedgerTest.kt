package com.bd2toolsbox.core.storage

import android.content.Context
import com.bd2toolsbox.core.model.OperationErrorKind
import com.bd2toolsbox.data.model.InstalledModRecord
import com.bd2toolsbox.data.repository.InstalledModRepository
import com.bd2toolsbox.data.repository.LedgerTake
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * `InstalledModRepository` 这一层的行为（T08）。
 *
 * ## 为什么这一组必须存在，而不是只测 Store
 *
 * 计划验收第 1 条（「并发写入不丢记录；损坏账本不会被下一次安装当空表覆盖」）
 * 说的**不是 Store**，是这个仓储：锁在它身上、损坏处置的**决策**也在它身上
 * （Store 只提供 `read()` 的分态与 `preserveCorruptFile()`，谁在什么时候调是仓储的事）。
 * 只测 Store 会让「仓储忘了调 `readForWrite`」这类破坏全绿。
 *
 * ## 桩的边界（只此一处）
 *
 * 用 mockk 桩**两件**东西，都只为了在 JVM 上把真实文件系统跑起来：
 *
 * 1. `Context.filesDir` → `TemporaryFolder`（`InstalledModRepository` 唯一的外部依赖，
 *    与 `PrepackCacheIntegrationTest.kt` 的既有范本同口径）；
 * 2. `android.util.Log` 的静态方法 —— **实测**它在 JVM 单测里会抛
 *    `RuntimeException: Method w in android.util.Log not mocked`（探针输出见 `T08.md` §2），
 *    而本仓储的失败路径会记日志。
 *
 * 除这两处之外**没有任何 fake**：落盘走真实文件、并发走真实线程。
 */
class InstalledModRepositoryLedgerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var context: Context
    private lateinit var repo: InstalledModRepository

    @Before
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0

        filesDir = temp.newFolder("files")
        context = mockk<Context>()
        every { context.filesDir } returns filesDir
        repo = InstalledModRepository(context)
    }

    @Test
    fun removingFromCorruptLedgerReportsPreservationForBothDeleteEntrypoints() {
        for (byTarget in listOf(false, true)) {
            repo.file().writeText("{broken")
            val write = if (byTarget) repo.removeByTargetHash("absent") else repo.remove("absent")
            assertTrue("Preservation must not be reported as a plain no-op", write is LedgerWrite.Repaired)
            val preserved = File(filesDir, (write as LedgerWrite.Repaired).preservedAs)
            assertEquals("{broken", preserved.readText())
            assertNotNull(repo.consumeLastError())
        }
    }

    /** 同进程重建实例并重读磁盘；不模拟进程死亡，调用方须显式释放旧租约。 */
    private fun reloadedRepo(): InstalledModRepository = InstalledModRepository(context)

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ----------------------------------------------------- 四态在仓储层的表现

    /**
     * **全新安装**：没有账本文件 → 第一次 `put` 必须成功写进去。
     *
     * 这是本任务最容易被写错的一格（把 NotFound 当损坏 → 拒绝写入 → 全新安装永远失败）。
     * 这一条与 `InstalledLedgerStoreTest` 的那条**守的不是同一层**：
     * 那条守 Store 自己的 `save()`，这条守**仓储的写入路径真的把 NotFound 放行了**
     * （`readForWrite()` 里把 NotFound 映射成空表那一支）。
     */
    @Test
    fun aFreshInstallCanWriteItsFirstRecord() {
        assertFalse("前提：账本文件真的不存在", repo.file().exists())

        assertEquals("全新安装必须写成功", LedgerWrite.Written, repo.put(installed(URI_A)))

        val stored = repo.load()
        assertEquals("写进去的必须真的读得回来", 1, stored.size)
        // 期望值取自夹具构造函数自己的字面量规则（`A-<uri>`），不是拿被测代码算的
        assertEquals("A-uri-a", stored.getValue(URI_A).modName)
        assertEquals("hash-uri-a", stored.getValue(URI_A).snapshotTargetHash)
        assertFalse("usedAstc 必须逐字段还原", stored.getValue(URI_A).usedAstc)
    }

    /** **合法空表**同样允许写（与「不存在」是两个态，但都放行）。 */
    @Test
    fun anEmptyLedgerStillAcceptsWrites() {
        repo.file().writeText("""{"schemaVersion":1,"records":{}}""")
        assertEquals(LedgerWrite.Written, repo.put(installed(URI_A)))
        assertEquals(1, repo.load().size)
    }

    /**
     * **损坏账本不会被下一次安装当空表覆盖**（计划验收第 1 条，改动前正是这个形态）。
     *
     * 改动前的路径：`load()` 吞掉解析异常返回空表 → `put()` 拿着空表 + 新记录 `writeText`
     * → 旧内容被整体覆写、永久丢失、无日志。
     *
     * 这里证明**两件事同时成立**：
     * 1. 旧内容**没有**消失 —— 它以 `.corrupt-<时间戳>` 的名字留在同目录；
     * 2. 失败/处置**传回了上层** —— `consumeLastError()` 拿得到一条 MALFORMED_DATA。
     */
    @Test
    fun aCorruptLedgerIsNeverOverwrittenAsIfItWereEmpty() {
        val corruptText = """{"uri-old":{"modUri":"uri-old","modName":"OLD","familyKey":"f","""
        repo.file().writeText(corruptText)

        // 写入路径走到损坏分支：保留原件 → 从空表重建 → 新记录落盘
        val result = repo.put(installed(URI_A))

        // (1) 旧内容被保留成独立文件，逐字节相同
        val preserved = filesDir.listFiles()?.filter {
            it.name.startsWith("installed_mods.json" + InstalledLedgerStore.CORRUPT_SUFFIX_PREFIX)
        } ?: emptyList()
        assertEquals("损坏原件必须留下恰好一份", 1, preserved.size)
        assertEquals("保留件必须与损坏前逐字节相同", corruptText, preserved[0].readText())

        // (2) 处置被**回传上层**（不是静默）：结局必须是 Repaired，
        //     且它同时说明了「重建了」与「原件在哪」。
        assertTrue(
            "损坏处置必须以 Repaired 回传上层，不许静默，实际 $result",
            result is LedgerWrite.Repaired
        )
        val repaired = result as LedgerWrite.Repaired
        assertEquals(OperationErrorKind.MALFORMED_DATA, repaired.notice.kind)
        assertEquals("保留件的名字必须能从上层的返回值里读到", preserved[0].name, repaired.preservedAs)
        assertTrue(
            "给用户的消息要能看懂发生了什么（实际：${repaired.notice.message}）",
            repaired.notice.message.contains(preserved[0].name)
        )
        // (3) 这条消息也必须能被只读调用方取到（不只是写路径的返回值）
        assertNotNull("修复消息要留给 instance，供提示用", repo.consumeLastError())
    }

    /**
     * **读取失败（IoError）→ 拒绝写入、不动磁盘。**
     *
     * 与「损坏」不同：读不出来不代表内容坏了，所以**不改名**。
     * 做法是把账本路径做成一个**目录**（`readText` 必失败，且 `isDirectory` 判据会先命中）。
     */
    @Test
    fun aLedgerThatCannotBeReadBlocksTheWriteInsteadOfOverwritingIt() {
        val ledgerPath = repo.file()
        assertTrue("前提：用同名目录占住账本路径", ledgerPath.mkdirs())

        val result = repo.put(installed(URI_A))
        assertTrue(
            "读不出来的账本必须**拒绝写入**（不是 Repaired、更不是 Written），实际 $result",
            result is LedgerWrite.Failed
        )
        assertEquals(
            "目录占位属于「读不出来」，不是「内容损坏」",
            OperationErrorKind.IO, (result as LedgerWrite.Failed).error.kind
        )
        assertTrue("同名目录必须还在（没有被写坏、没有被改名走）", ledgerPath.isDirectory)
    }

    /**
     * **保留原件失败时拒绝覆盖**：这是「不许静默丢弃」的硬底线。
     *
     * 造法：损坏文件 + 它所在的父目录被设成只读 → `renameTo` 失败 →
     * 仓储必须**拒绝写入**（返回非 null），且原文件**原地不动**。
     */
    @Test
    fun whenTheCorruptOriginalCannotBePreservedTheWriteIsRefused() {
        repo.file().writeText("""{"uri-old":半截""")
        val before = repo.file().readText()
        // 让改名**一定失败**：宿主上持有一个打开的写句柄时 `renameTo` 返回 false
        // （**实测**，见 T08.md 的探针输出）。父目录设只读在 Windows 上不起作用
        // —— 探针实测 `setWritable(false)` 对目录直接返回 false。
        val hold = java.io.FileOutputStream(repo.file(), true)
        try {
            val result = repo.put(installed(URI_A))
            assertTrue(
                "原件保不住时必须拒绝写入（Failed），实际 $result",
                result is LedgerWrite.Failed
            )
            assertEquals(
                "原文件必须原地逐字节不动（唯一一份可能还能人工抢救的东西）",
                before, repo.file().readText()
            )
        } finally {
            hold.close()
        }
    }

    // ------------------------------------------------------------- 并发不丢记录

    /**
     * **并发写入不丢记录**（计划验收第 1 条）。
     *
     * 8 个线程各写 25 条互不相同的记录，全部 join 之后账本必须**恰好**含 200 条。
     * 判据是「条数 == 200」这个**写死的字面量**，不是「和某个集合比」——
     * 后者在夹具恒为空时会退化成 0 == 0 的空转断言（T07 二轮复核抓到的形状）。
     */
    @Test
    fun concurrentWritesLoseNoRecords() {
        val threads = 8
        val perThread = 25
        // 先确保文件存在（走一遍 NotFound → 已存在的转变），让并发发生在稳态上。
        assertEquals(LedgerWrite.Written, repo.put(installed("uri-seed")))
        assertEquals("前提：种子里真的只有 1 条", 1, repo.load().size)

        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val failures = AtomicInteger(0)
        val workers = (0 until threads).map { t ->
            Thread {
                start.await()
                try {
                    for (i in 0 until perThread) {
                        val result = repo.put(installed("uri-$t-$i"))
                        if (result !is LedgerWrite.Written) failures.incrementAndGet()
                    }
                } catch (e: Exception) {
                    failures.incrementAndGet()
                } finally {
                    done.countDown()
                }
            }.also { it.isDaemon = true; it.start() }
        }
        start.countDown()
        assertTrue("并发写入必须在 30 秒内跑完（超时说明死锁）", done.await(30, TimeUnit.SECONDS))
        workers.forEach { it.join(5_000) }

        assertEquals("写入过程不该有失败", 0, failures.get())
        val stored = repo.load()
        assertEquals(
            "并发写入不许丢记录：1 条种子 + 8 线程 × 25 条 = 201 条",
            1 + threads * perThread, stored.size
        )
        // 抽查首尾各一条，确认不是「条数对但内容错位」
        assertTrue("uri-0-0 必须在", stored.containsKey("uri-0-0"))
        assertTrue("uri-7-24 必须在", stored.containsKey("uri-7-24"))
        assertEquals("A-uri-7-24", stored.getValue("uri-7-24").modName)
    }

    /** 并发 `putAll` 同样不丢：两个批次各 40 条，最终 80 条。 */
    @Test
    fun concurrentPutAllBatchesLoseNoRecords() {
        val expected = 80
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        for (batch in 0 until 2) {
            Thread {
                start.await()
                try {
                    repo.putAll((0 until 40).map { installed("b$batch-i$it") })
                } finally {
                    done.countDown()
                }
            }.also { it.isDaemon = true; it.start() }
        }
        start.countDown()
        assertTrue("并发 putAll 必须在 30 秒内跑完", done.await(30, TimeUnit.SECONDS))

        assertEquals("两个批次共 $expected 条，一条都不能少", expected, repo.load().size)
    }

    // ------------------------------------------------- 销账必须回传结局（真缺陷 1）

    /**
     * **账本损坏时销账：原件被改名保留、结局被回传、不是静默返回 `null`**
     * （T08 二轮整改，复核真缺陷 1）。
     *
     * ## 这一条守的是什么
     *
     * `removeAndReturn()` 曾经返回裸的 `InstalledModRecord?`，于是这条路径上
     * **三件事共用一个 `null`**：本来就没这条 / 写失败记录还在 / 账本损坏原件被搬走。
     * 第三种是 T08 亲手引入的副作用（`readForWrite()` 的「损坏即改名」那一支），
     * 在裸 `null` 下**完全静默**：原件被搬成 `.corrupt-*`、账本路径消失、
     * `lastError` 为空、用户零提示。
     *
     * 断言三点（复核要求「至少两点」，这里三点全钉）：
     *   1. 原件**真的**被改名保留成 `.corrupt-<时间戳>`，且逐字节不变；
     *   2. 结局是 [LedgerTake.Repaired]（不是 `null`、也不是「本来就没事」的 `Absent`）；
     *   3. 修复消息**能被上层读走**（`consumeLastError()` 非空）。
     *
     * 第 2 点把「把回传改回丢弃」这种破坏直接打红 —— 那就是本用例的对照证伪。
     */
    @Test
    fun takingARecordFromACorruptLedgerKeepsTheOriginalAndReportsItInsteadOfReturningNull() {
        val corruptText = """{"uri-old":{"modUri":"uri-old","modName":"OLD","familyKey":"f","""
        repo.file().writeText(corruptText)

        val take = repo.removeAndReturn(URI_A)

        // (1) 原件被改名保留，逐字节不变
        val preserved = filesDir.listFiles()?.filter {
            it.name.startsWith("installed_mods.json" + InstalledLedgerStore.CORRUPT_SUFFIX_PREFIX)
        } ?: emptyList()
        assertEquals("损坏原件必须留下恰好一份", 1, preserved.size)
        assertEquals("保留件必须与损坏前逐字节相同", corruptText, preserved[0].readText())
        assertFalse("账本路径必须已经被腾空（原件搬走了）", repo.file().exists())

        // (2) 结局不是「静默的 null」：必须自报为「损坏重建过」，
        //     并且把保留件的名字带上来（上层据此告诉用户原件在哪）。
        assertTrue(
            "损坏账本上的销账必须以 Repaired 回传（不许静默返回 null），实际 $take",
            take is LedgerTake.Repaired
        )
        val repaired = take as LedgerTake.Repaired
        assertEquals(
            "保留件的名字必须能从上层的返回值里读到",
            preserved[0].name, repaired.preservedAs
        )
        // 损坏前读不出内容 ⇒ 没有可放回的记录。这一点必须能表达出来，
        // 否则调用方会拿着一份「以为销掉了」的记录去复原。
        assertNull("损坏前读不出内容，没有可放回的记录", take.record)

        // (3) 修复消息也必须能被只读调用方取到（不只是写路径的返回值）
        val notice = repo.consumeLastError()
        assertNotNull("修复消息要留在 instance，供只读调用方提示用", notice)
        assertEquals(
            "没进 save() 这条路也必须把消息记成 MALFORMED_DATA（而不是别的类别）",
            OperationErrorKind.MALFORMED_DATA, notice!!.kind
        )
        assertTrue(
            "给用户的说明里要能看出原件被搬去了哪（实际：${notice.message}）",
            notice.message.contains(preserved[0].name)
        )
    }

    /**
     * **账本写失败时销账必须报失败，且账本里那条记录还在**
     * （T08 二轮整改，复核真缺陷 1 的第二半）。
     *
     * 复核者复现的形态是「`removeAndReturn` 仍返回记录对象，调用方以为销账成功，
     * 账本里那条其实还在」。裸返回值下成功与失败长得一模一样，
     * 调用方（`MainViewModel`）随后会把 `null` 或记录当成「已销账」继续走。
     */
    @Test
    fun takingARecordFromALedgerThatCannotBeWrittenReportsTheFailureAndKeepsTheRecord() {
        assertEquals("前提：先写进去一条", LedgerWrite.Written, repo.put(installed(URI_A)))
        assertTrue("前提：账本目标真的变成只读", repo.file().setWritable(false))
        try {
            val take = repo.removeAndReturn(URI_A)

            assertTrue(
                "写不进去时销账必须报失败（不是「销掉了」也不是「本来就没有」），实际 $take",
                take is LedgerTake.Blocked
            )
            assertNull("被拒绝时没有记录被销掉，不该交出可复原的记录", take.record)

            repo.file().setWritable(true)
            assertEquals(
                "销账失败后账本里那条记录必须还在（调用方以为销了才是真问题）",
                1, repo.load().size
            )
            assertTrue("那条记录必须还是原来那条", repo.load().containsKey(URI_A))
        } finally {
            repo.file().setWritable(true)
        }
    }

    // ------------------------------------------------------------- journal 接线

    /**
     * 仓储把 journal 的阶段**真的写到了磁盘上**（不是只留在内存里）。
     *
     * 这一条守的是「仓储 → Store」那根线；「谁在什么业务时机调这些方法」
     * 是另一层，登记在 `T08.md` 的未覆盖项里。
     */
    @Test
    fun journalStagesReachTheDiskThroughTheRepository() {
        repo.beginSubmit("op-1", listOf(JournalTarget("bundle-a", "hash-a", 10L, listOf(URI_A))))
        repo.markStaged()
        repo.markTargetReplaced()

        val raw = repo.journalFile().readText()
        assertTrue("PLANNED 必须落盘（实际 $raw）", raw.contains("\"planned\""))
        assertTrue("STAGED 必须落盘", raw.contains("\"staged\""))
        assertTrue("TARGET_REPLACED 必须落盘", raw.contains("\"target_replaced\""))
    }

    /**
     * **「文件已换、账本未存」的接线**：`markTargetReplaced()` 之后、账本里没有这条记录
     * → `checkRecovery()` 报待核对。
     *
     * 这是计划验收第 2 条在仓储层的落点。
     */
    @Test
    fun replacingTheTargetWithoutCommittingTheLedgerLeavesAPendingReconciliation() {
        repo.beginSubmit("op-1", listOf(JournalTarget("bundle-a", "hash-a", 10L, listOf(URI_A))))
        // 不传参：走 [beginSubmit] 记下的那一批目标（生产路径正是这么调的）。
        repo.markTargetReplaced()
        // 注意：**没有**落账本、**没有**调 finishSubmit —— 这正是崩溃留下的形态。

        val verdict = repo.checkRecovery()
        assertTrue(
            "文件已换、账本未存 → 必须报待核对，实际 $verdict",
            verdict is OperationJournalStore.RecoveryVerdict.PendingReconciliation
        )
        assertEquals(
            listOf("bundle-a"),
            (verdict as OperationJournalStore.RecoveryVerdict.PendingReconciliation).bundleNames
        )
    }

    /**
     * **正常收尾之后没有待核对**：账本落盘成功 → `finishSubmit(null)` → journal 被清掉。
     *
     * 与上一条配对，证明「待核对」不是恒真的（否则上一条就是空转断言）。
     */
    @Test
    fun committingTheLedgerClosesTheWindow() {
        repo.beginSubmit("op-1", listOf(JournalTarget("bundle-a", "hash-a", 10L, listOf(URI_A))))
        repo.markTargetReplaced()
        val ledgerResult = repo.putAll(listOf(installed(URI_A)))
        assertEquals("账本必须写成功", LedgerWrite.Written, ledgerResult)

        assertNull("正常收尾不该报错", repo.finishSubmit(null))
        assertEquals(
            "账本已落盘 → 窗口关闭",
            OperationJournalStore.RecoveryVerdict.Consistent,
            repo.checkRecovery()
        )
        assertEquals(
            "收尾之后 journal 应当被清掉",
            JournalRead.Absent,
            OperationJournalStore(repo.journalFile()).read()
        )
    }

    /**
     * **账本落盘失败时 journal 必须留着**：否则那个窗口在下次启动就查不出来了。
     *
     * 造法：只读账本文件让写入失败 → `finishSubmit(error)` 必须返回该错误、
     * 且 **journal 里那条 TARGET_REPLACED 还在**。
     */
    @Test
    fun aFailedLedgerWriteKeepsTheJournalForTheNextStartup() {
        repo.beginSubmit("op-1", listOf(JournalTarget("bundle-a", "hash-a", 10L, listOf(URI_A))))
        repo.markTargetReplaced()

        // 让账本文件无法被写入（只读目标 + ATOMIC_MOVE，宿主实测抛 AccessDeniedException）。
        repo.file().writeText("""{"schemaVersion":1,"records":{}}""")
        assertTrue("前提：账本目标真的变成只读", repo.file().setWritable(false))
        try {
            val ledgerResult = repo.putAll(listOf(installed(URI_A)))
            assertTrue("只读账本的写入必须失败", ledgerResult is LedgerWrite.Failed)
            val ledgerError = (ledgerResult as LedgerWrite.Failed).error

            val finishError = repo.finishSubmit(ledgerError)
            assertNotNull("finishSubmit 必须把账本失败原样交回", finishError)

            val journal = OperationJournalStore(repo.journalFile()).read()
            assertTrue("journal 必须还在，实际 $journal", journal is JournalRead.Loaded)
            assertTrue(
                "那条 TARGET_REPLACED 必须留着，否则下次启动查不出这个窗口",
                (journal as JournalRead.Loaded).entries.any {
                    it.stageOrNull == JournalStage.TARGET_REPLACED
                }
            )
            assertTrue(
                "并且下次启动真的报待核对",
                repo.checkRecovery() is OperationJournalStore.RecoveryVerdict.PendingReconciliation
            )
        } finally {
            repo.file().setWritable(true)
        }
    }

    // ------------------------------- 恢复门禁：pending/unknown/in-flight 不许再次覆写
    //
    // 本组是 T08 提交门禁的仓储层回归测试（修复前已验证 RED）。守的契约：
    //   1. 重启后处于**待核对**（PendingReconciliation）时，再次 beginSubmit 必须被拒绝，
    //      且 journal 逐字节不变（不许把「文件已换、账本未存」那条 TARGET_REPLACED 覆写掉）。
    //   2. 账本读坏时 checkRecovery **fail closed**：不许降级成空账本再报 Consistent 放行。
    //   3. journal 读不出来（Unknown）时同样拒绝再次 beginSubmit，journal 不变。
    //   4. **一致**（Consistent）时可以正常重新提交（不能过度封锁）。
    //   5. 同实例**提交进行中**（beginSubmit 未 finish）时，另一次 beginSubmit 不许覆盖
    //      当前上下文（journal 不变）。
    //
    // 拟定生产改动（本轮不写）：beginSubmit 返回 OperationError?（拒绝时非 null，
    // 用现有 kind：进行中 -> INVALID_INPUT；待核对/未知 -> INVALID_INPUT），
    // 且在**任何 append 之前**先核对；checkRecovery 改走 readLedger() 分态，
    // Corrupt/IoError -> RecoveryVerdict.Unknown（不再经 load() 吞成空表）。
    // 本组用**副作用**（journal 字节不变 + verdict 类型）表达契约，不断言尚不存在的返回值，
    // 修复前以文件副作用证明失败；实现后同时断言返回错误/放行结果。

    /**
     * **重启后待核对 → 再次 beginSubmit 被拒、journal 逐字节不变**（防重复提交的核心）。
     *
     * 造一个真实磁盘窗口（TARGET_REPLACED 已落、账本里没有对应记录），释放租约后重建实例，
     * 确认 checkRecovery 报 PendingReconciliation；随后再 beginSubmit 一批新目标 ——
     * journal 必须原样不动。修复前实现**无条件 append PLANNED**，因此这里会 RED。
     */
    @Test
    fun aPendingReconciliationRejectsANewSubmitAndLeavesTheJournalByteIdentical() {
        repo.beginSubmit("op-old", listOf(JournalTarget("bundle-a", "hash-a", 10L, listOf(URI_A))))
        repo.markTargetReplaced()
        // 不落账本、不 finish；只释放内存租约，保留真实磁盘窗口。
        repo.releaseSubmit("op-old")

        val restarted = reloadedRepo()
        assertTrue(
            "前提：重启后必须先处于待核对",
            restarted.checkRecovery() is OperationJournalStore.RecoveryVerdict.PendingReconciliation
        )

        val before = restarted.journalFile().readBytes()
        val gateError = restarted.beginSubmit("op-new", listOf(JournalTarget("bundle-b", "hash-b", 20L, listOf("uri-b"))))
        val after = restarted.journalFile().readBytes()

        assertNotNull("待核对时 beginSubmit 必须返回错误", gateError)
        assertEquals(OperationErrorKind.INVALID_INPUT, gateError!!.kind)
        assertArrayEquals(
            "待核对时的 beginSubmit 必须在写盘前被拒绝，journal 逐字节不变（不许覆写窗口证据）",
            before, after
        )
        // 窗口证据必须还在：TARGET_REPLACED 没被冲掉，重启仍报待核对。
        assertTrue(
            "被拒绝后窗口证据必须仍在，下一次启动还能核对出来",
            restarted.checkRecovery() is OperationJournalStore.RecoveryVerdict.PendingReconciliation
        )
    }

    /**
     * **账本读坏时 checkRecovery fail closed**：不许把损坏账本降级成空表再报 Consistent。
     *
     * 造一份损坏账本、**没有** journal。修复前实现 checkRecovery 经 `load()` 把损坏吞成空表，
     * journal 缺失 → 报 Consistent（放行）。这是 fail-open 的漏洞。这里断言它**不是** Consistent
     * 且是 Unknown —— 修复前实现会 RED。
     */
    @Test
    fun aCorruptLedgerMakesRecoveryFailClosedInsteadOfLookingConsistent() {
        repo.file().writeText("""{"uri-old":{"modUri":"uri-old","modName":"OLD","familyKey":"f",""")
        assertFalse("前提：没有 journal 文件", repo.journalFile().exists())

        val verdict = repo.checkRecovery()
        assertFalse(
            "账本读坏绝不能被当空表进而报「一致」放行，实际 $verdict",
            verdict is OperationJournalStore.RecoveryVerdict.Consistent
        )
        assertTrue(
            "账本读坏应 fail closed 到 Unknown（我们确实不知道，不许伪装成正常），实际 $verdict",
            verdict is OperationJournalStore.RecoveryVerdict.Unknown
        )
    }

    /**
     * **Unknown（journal 读不出来）时再次 beginSubmit 被拒、journal 不变**。
     *
     * 写一份读不出来的 journal（顶层非对象）。修复前实现 append 会「损坏即从头重写」
     * （见 OperationRecoveryTest 的 appendingOverACorruptJournalStartsFresh），
     * 从而改动字节 → RED。门禁应在 append 前拒绝。
     */
    @Test
    fun anUnknownVerdictRejectsANewSubmitAndLeavesTheJournalByteIdentical() {
        repo.journalFile().writeText("not-an-object")
        assertTrue(
            "前提：journal 读不出来时核对应为 Unknown",
            repo.checkRecovery() is OperationJournalStore.RecoveryVerdict.Unknown
        )

        val before = repo.journalFile().readBytes()
        val gateError = repo.beginSubmit("op-new", listOf(JournalTarget("bundle-a", "hash-a", 10L, listOf(URI_A))))
        val after = repo.journalFile().readBytes()

        assertNotNull("Unknown 时 beginSubmit 必须返回错误", gateError)
        assertArrayEquals(
            "Unknown 时的 beginSubmit 必须在写盘前被拒绝，journal 逐字节不变",
            before, after
        )
    }

    /**
     * **一致时可以正常重新提交**（防止门禁过度封锁）。
     *
     * 正常状态（有效空账本、无窗口 journal）下 beginSubmit 必须放行并真的落一条 PLANNED。
     * 这一条在修复前实现上就是 GREEN —— 它锁的是「解除条件」：核对通过后不许还拦着。
     * 与上面几条配对，证明拒绝不是恒真的。
     */
    @Test
    fun aConsistentStateStillAllowsAFreshSubmit() {
        repo.file().writeText("""{"schemaVersion":1,"records":{}}""")
        assertNull(
            "一致状态下 beginSubmit 必须放行（返回 null）",
            repo.beginSubmit("op-1", listOf(JournalTarget("bundle-a", "hash-a", 10L, listOf(URI_A))))
        )

        val journal = OperationJournalStore(repo.journalFile()).read()
        assertTrue("一致状态下 beginSubmit 必须放行并落盘", journal is JournalRead.Loaded)
        assertTrue(
            "必须真的写下这次提交的 PLANNED",
            (journal as JournalRead.Loaded).entries.any {
                it.stageOrNull == JournalStage.PLANNED && it.operationId == "op-1"
            }
        )
    }

    /**
     * **同实例提交进行中时，另一次 beginSubmit 不许覆盖当前上下文**。
     *
     * beginSubmit(op-1) 之后**没有** finish（提交在途）。此刻再 beginSubmit(op-2)
     * 必须被拒绝：否则 currentOperationId/currentTargets 被 op-2 覆盖，op-1 的收尾
     * （finishSubmit / markTargetReplaced 沿用的目标）就错乱了。
     * 用 journal 字节不变来观察——修复前实现会为 op-2 追加一条 PLANNED，故 RED。
     */
    @Test
    fun anInFlightSubmitIsNotOverwrittenByAnotherBegin() {
        repo.file().writeText("""{"schemaVersion":1,"records":{}}""")
        assertNull("op-1 开始应放行", repo.beginSubmit("op-1", listOf(JournalTarget("bundle-a", "hash-a", 10L, listOf(URI_A)))))
        // 不 finish：op-1 仍在途。

        val before = repo.journalFile().readBytes()
        val gateError = repo.beginSubmit("op-2", listOf(JournalTarget("bundle-b", "hash-b", 20L, listOf("uri-b"))))
        val after = repo.journalFile().readBytes()

        assertNotNull("在途时第二次 beginSubmit 必须返回错误", gateError)
        assertEquals(OperationErrorKind.INVALID_INPUT, gateError!!.kind)
        assertArrayEquals(
            "在途提交期间的第二次 beginSubmit 必须被拒绝，不许把 op-1 的上下文覆盖掉",
            before, after
        )
        // op-1 的目标必须还在（用它收尾）：markTargetReplaced 沿用的应仍是 bundle-a。
        repo.markTargetReplaced()
        val verdict = repo.checkRecovery()
        assertTrue(
            "收尾核对应当仍围绕 op-1 的 bundle-a，实际 $verdict",
            verdict is OperationJournalStore.RecoveryVerdict.PendingReconciliation &&
                verdict.bundleNames == listOf("bundle-a")
        )
    }

    /**
     * **releaseSubmit 只能释放自己的在途态**（并发缺陷回归）。
     *
     * op-1 收尾 → op-2 begin 已占位 → op-1 的 finally 调 releaseSubmit(op-1)
     * 不能把 op-2 的上下文清掉；因此第三次 begin 仍被拒，journal 不变。
     */
    @Test
    fun releasingOnlyClearsItsOwnInFlightNotAnothersContext() {
        repo.file().writeText("""{"schemaVersion":1,"records":{}}""")
        assertNull(repo.beginSubmit("op-1", listOf(JournalTarget("bundle-a", "hash-a", 10L, listOf(URI_A)))))
        assertNull("op-1 收尾不报错", repo.finishSubmit(null))
        // op-1 收尾后、其 finally 调到前，op-2 已经开始（模拟调度交错）。
        assertNull(repo.beginSubmit("op-2", listOf(JournalTarget("bundle-b", "hash-b", 20L, listOf("uri-b")))))

        // op-1 迟到的 finally：传自己的 id，不能清掉 op-2。
        repo.releaseSubmit("op-1")

        val before = repo.journalFile().readBytes()
        val gateError = repo.beginSubmit("op-3", listOf(JournalTarget("bundle-c", "hash-c", 30L, listOf("uri-c"))))
        val after = repo.journalFile().readBytes()

        assertNotNull("op-2 仍在途 → 第三次 begin 必须被拒", gateError)
        assertEquals(OperationErrorKind.INVALID_INPUT, gateError!!.kind)
        assertArrayEquals("op-1 的释放不得放行 op-3，journal 不变", before, after)
    }

    /** 无内容证据的 TARGET_REPLACED：人工 putAll 也不能把窗口判成一致。 */
    @Test
    fun legacyReplacedRecordStaysPendingEvenAfterManualLedgerPut() {
        assertNull(repo.beginSubmit("op-old", listOf(JournalTarget("hash-$URI_A", "hash-a", 10L, listOf(URI_A)))))
        repo.markTargetReplaced()
        repo.releaseSubmit("op-old")
        val restarted = reloadedRepo()
        assertNotNull(
            "前提：未补账时应拒绝",
            restarted.beginSubmit("op-blocked", listOf(JournalTarget("hash-x", "hash-x", 1L, listOf("uri-x"))))
        )

        assertEquals(LedgerWrite.Written, restarted.putAll(listOf(installed(URI_A))))
        assertTrue(
            "缺本次内容证明时不能仅靠账本放行，实际 ${restarted.checkRecovery()}",
            restarted.checkRecovery() is OperationJournalStore.RecoveryVerdict.PendingReconciliation
        )
        assertNotNull(
            "未闭合窗口仍须挡住新提交",
            restarted.beginSubmit("op-after", listOf(JournalTarget("hash-b", "hash-b", 2L, listOf("uri-b"))))
        )
    }

    // ------------------------------------------------------------------ 辅助

    private fun installed(uri: String) = InstalledModRecord(
        modUri = uri,
        modName = "A-$uri",
        familyKey = "family-$uri",
        snapshotTargetHash = "hash-$uri",
        quality = "HD",
        usedAstc = false,
        installedAt = 1_700_000_000_000L
    )

    private companion object {
        const val URI_A = "uri-a"
    }
}
