package com.bd2toolsbox.core.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 「文件已换、账本未存」这个窗口的核对（T08）。
 *
 * ## 这一组守的是哪一层
 *
 * 守的是**判据本身**：给定一份 journal 与一份账本，[OperationJournalStore.recoveryVerdict]
 * 必须给出**明确的三态之一**（一致 / 待核对 / 不知道），
 * **不许把「文件换了但账本没记」伪装成「全成功」或「全失败」**（计划验收第 2 条）。
 *
 * 它**不**守「谁在什么时机往 journal 里落阶段」—— 那是 `ShizukuManager` /
 * `MainViewModel` 调用点的事，JVM 层测不了，登记在 `T08.md` 的未覆盖项里。
 *
 * ## 夹具
 *
 * 全部在 `TemporaryFolder` 里现造（仓里没有 `installed_mods.json` 真实样本，
 * 见 `fixture-manifest.json` 的 `missing.installed-mods-ledger`）。
 * journal 的 JSON **手写成字面量**而不是调被测代码生成 ——
 * 那样「破坏 stage 的落点」才会真的红（写代码算期望会让输入与期望一起变，断言恒真）。
 */
class OperationRecoveryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var journal: File

    /**
     * 每次都开一个**新的**子目录：同一用例里需要多个 Store 时
     * `temp.newFolder("files")` 会撞名抛 IOException。
     */
    private fun newStore(): OperationJournalStore {
        journal = File(temp.newFolder("files-" + (dirSeq++)), "install_journal.json")
        return OperationJournalStore(journal)
    }

    private var dirSeq = 0

    // ------------------------------------------------------------- 基本读写

    /** 没有 journal 文件 → [JournalRead.Absent]（**正常态**，不是错误）。 */
    @Test
    fun noJournalFileMeansNoOperationInFlight() {
        val store = newStore()
        assertEquals(JournalRead.Absent, store.read())
    }

    /** 追加一条后能读回来，字段逐个对得上。 */
    @Test
    fun appendedEntryRoundTripsWithEveryField() {
        val store = newStore()
        assertNull(
            store.append(
                JournalEntry(
                    operationId = "op-1",
                    stage = JournalStage.PLANNED.id,
                    atMillis = 1234L,
                    targets = listOf(JournalTarget("bundle-a", "hash-a", 4096L, listOf("uri-a"))),
                    note = "开始"
                )
            )
        )

        val read = store.read()
        assertTrue("应当读到一条，实际 $read", read is JournalRead.Loaded)
        val entries = (read as JournalRead.Loaded).entries
        assertEquals("条数必须是字面量 1", 1, entries.size)
        assertEquals("op-1", entries[0].operationId)
        assertEquals(JournalStage.PLANNED, entries[0].stageOrNull)
        assertEquals(1234L, entries[0].atMillis)
        assertEquals(1, entries[0].targets.size)
        assertEquals("bundle-a", entries[0].targets[0].bundleName)
        assertEquals(4096L, entries[0].targets[0].dataBytes)
        assertEquals(listOf("uri-a"), entries[0].targets[0].modUris)
        assertEquals("开始", entries[0].note)
    }

    /** 多次 append 累积（阶段推进就是连续 append）。 */
    @Test
    fun appendingKeepsEveryStageInOrder() {
        val store = newStore()
        for (stage in JournalStage.values()) {
            assertNull(store.append(entry(stage, at = 1000L + stage.ordinal)))
        }
        val entries = (store.read() as JournalRead.Loaded).entries
        // 用 JournalStage.entries.size 而不硬编码：五阶段
        // （PLANNED / STAGED / TARGET_REPLACING / TARGET_REPLACED / LEDGER_COMMITTED）各一条。
        assertEquals("每个阶段各一条", JournalStage.values().size, entries.size)
        assertEquals(
            JournalStage.values().map { it.id },
            entries.map { it.stage }
        )
    }

    /** 磁盘形态：顶层 `schemaVersion` + `entries` 数组（用**独立文本检查**，不调解析器）。 */
    @Test
    fun theFileCarriesAVersionedEnvelopeWithAnEntriesArray() {
        val store = newStore()
        assertNull(store.append(entry(JournalStage.PLANNED)))
        val text = journal.readText()
        assertTrue("顶层必须有 schemaVersion:1（实际 $text）", text.contains("\"schemaVersion\":1"))
        assertTrue("载荷键必须是 entries（实际 $text）", text.contains("\"entries\""))
    }

    /** 未知 schema 版本 → [JournalRead.Unreadable]（不猜、不当成空）。 */
    @Test
    fun unknownSchemaVersionIsUnreadable() {
        val store = newStore()
        journal.writeText("""{"schemaVersion":9,"entries":[]}""")
        assertTrue("未知版本必须判不可读", store.read() is JournalRead.Unreadable)
    }

    /** 半截 JSON → [JournalRead.Unreadable]。 */
    @Test
    fun truncatedJournalIsUnreadable() {
        val store = newStore()
        journal.writeText("""{"schemaVersion":1,"entries":[{"operationId":""")
        assertTrue("半截 JSON 必须判不可读", store.read() is JournalRead.Unreadable)
    }

    /**
     * **未知 stage 字符串 → Unreadable**，不是悄悄跳过那一条。
     *
     * 判据建立在「最后一条 TARGET_REPLACED 说了什么」上：静默跳过一条就可能正好跳过它，
     * 于是窗口被误判成「没有」。
     */
    @Test
    fun unknownStageStringMakesTheWholeJournalUnreadable() {
        val store = newStore()
        journal.writeText(
            """{"schemaVersion":1,"entries":[
                 {"operationId":"op-1","stage":"teleported","atMillis":1,"targets":[]}
               ]}"""
        )
        assertTrue("不认识的阶段必须判不可读", store.read() is JournalRead.Unreadable)
    }

    /** 损坏日志是恢复证据：追加必须拒绝，不能丢弃旧原件重开。 */
    @Test
    fun appendingOverACorruptJournalPreservesTheOriginalEvidence() {
        val store = newStore()
        journal.writeText("not json at all")
        val before = journal.readBytes()
        assertTrue(store.read() is JournalRead.Unreadable)
        org.junit.Assert.assertNotNull(store.append(entry(JournalStage.PLANNED)))
        assertArrayEquals("Bad journal must not be discarded", before, journal.readBytes())
        assertTrue(store.read() is JournalRead.Unreadable)
    }

    /** `clear()` 之后回到 [JournalRead.Absent]。 */
    @Test
    fun clearReturnsToAbsent() {
        val store = newStore()
        store.append(entry(JournalStage.TARGET_REPLACED))
        assertNull(store.clear())
        assertEquals(JournalRead.Absent, store.read())
    }

    // ------------------------------------------------------- 核心：核对判据

    /**
     * **窗口裂开的场景**：journal 说「目标已替换」，账本里却**没有**这条记录。
     *
     * 这正是 `copyDirectory` 成功之后、`putAll` 落盘之前崩溃留下的形态。
     * 必须报 [OperationJournalStore.RecoveryVerdict.PendingReconciliation]，
     * **不许**报「一致」（伪装成全成功），也**不许**报成「全失败」。
     */
    @Test
    fun replacedTargetMissingFromLedgerIsPendingReconciliation() {
        val store = newStore()
        store.append(
            JournalEntry(
                operationId = "op-1",
                stage = JournalStage.TARGET_REPLACED.id,
                atMillis = 5000L,
                targets = listOf(JournalTarget("bundle-a", "hash-a", 4096L, listOf("uri-a")))
            )
        )

        val verdict = store.recoveryVerdict(emptyMap())
        assertTrue(
            "文件已换、账本未存 → 必须报待核对，实际 $verdict",
            verdict is OperationJournalStore.RecoveryVerdict.PendingReconciliation
        )
        val pending = verdict as OperationJournalStore.RecoveryVerdict.PendingReconciliation
        assertEquals(listOf("bundle-a"), pending.bundleNames)
        assertEquals(listOf("uri-a"), pending.modUris)
        assertEquals("核对结论要带上原始时间戳（那是那次提交的时刻）", 5000L, pending.detectedAtMillis)
    }

    /** 无内容证据的 TARGET_REPLACED 即使账本 URI/bundle 对得上也不能放行。 */
    @Test
    fun replacedTargetWithoutFactsStaysPendingEvenWhenLedgerMatches() {
        val store = newStore()
        store.append(
            JournalEntry(
                operationId = "op-1",
                stage = JournalStage.TARGET_REPLACED.id,
                atMillis = 5000L,
                targets = listOf(JournalTarget("bundle-a", "hash-a", 4096L, listOf("uri-a")))
            )
        )

        val verdict = store.recoveryVerdict(mapOf("uri-a" to record("uri-a", "bundle-a")))
        assertTrue(
            "缺本次内容证明时不能仅靠账本放行，实际 $verdict",
            verdict is OperationJournalStore.RecoveryVerdict.PendingReconciliation
        )
    }

    /**
     * **多个目标里只差一个**也要报待核对 —— 判据是「全部都在才算一致」。
     *
     * 这条守的是「部分成功」这个真实状态（T07 §4.2 已确认批次部分成功是真实的）：
     * 只核对第一个目标、或者要求「至少一个在」都会漏掉这一格。
     */
    @Test
    fun partiallyRecordedReplacementIsStillPendingReconciliation() {
        val store = newStore()
        store.append(
            JournalEntry(
                operationId = "op-1",
                stage = JournalStage.TARGET_REPLACED.id,
                atMillis = 5000L,
                targets = listOf(
                    JournalTarget("bundle-a", "hash-a", 1L, listOf("uri-a")),
                    JournalTarget("bundle-b", "hash-b", 2L, listOf("uri-b"))
                )
            )
        )

        val verdict = store.recoveryVerdict(mapOf("uri-a" to record("uri-a", "bundle-a")))
        assertTrue(
            "只记了一半也要报待核对（部分成功是真实状态），实际 $verdict",
            verdict is OperationJournalStore.RecoveryVerdict.PendingReconciliation
        )
        assertEquals(
            "未闭合窗口的全部目标都要列出来，不能靠账本里已有的一半假装闭合",
            listOf("bundle-a", "bundle-b"),
            (verdict as OperationJournalStore.RecoveryVerdict.PendingReconciliation).bundleNames
        )
    }

    /**
     * **只有 PLANNED / STAGED（还没动游戏文件）→ 一致。**
     *
     * 这两种阶段下崩溃，游戏文件一个字节都没变，账本也不需要记任何东西 ——
     * 报「待核对」会是假警报，用户会被无谓地吓一跳。
     */
    @Test
    fun stagesThatNeverTouchedGameFilesAreConsistent() {
        for (stage in listOf(JournalStage.PLANNED, JournalStage.STAGED)) {
            val store = newStore()
            store.append(entry(stage))
            assertEquals(
                "$stage 还没动游戏文件，不该报待核对",
                OperationJournalStore.RecoveryVerdict.Consistent,
                store.recoveryVerdict(emptyMap())
            )
        }
    }

    /**
     * 旧窗口必须由**本 op 自己的 LEDGER_COMMITTED** 闭合，不能靠账本里还有旧 URI。
     * 已闭合的旧 op 不再报 pending；未闭合的新 op 必须报出来。
     */
    @Test
    fun onlyUnclosedReplacedEntriesDriveTheVerdict() {
        val store = newStore()
        store.append(
            JournalEntry(
                operationId = "op-old",
                stage = JournalStage.TARGET_REPLACED.id,
                atMillis = 1000L,
                targets = listOf(JournalTarget("bundle-old", "h", 1L, listOf("uri-old")))
            )
        )
        store.append(
            JournalEntry(
                operationId = "op-old",
                stage = JournalStage.LEDGER_COMMITTED.id,
                atMillis = 1001L,
                targets = listOf(JournalTarget("bundle-old", "h", 1L, listOf("uri-old")))
            )
        )
        store.append(
            JournalEntry(
                operationId = "op-new",
                stage = JournalStage.TARGET_REPLACED.id,
                atMillis = 9000L,
                targets = listOf(JournalTarget("bundle-new", "h", 1L, listOf("uri-new")))
            )
        )

        val verdict = store.recoveryVerdict(emptyMap())
        val pending = verdict as? OperationJournalStore.RecoveryVerdict.PendingReconciliation
        assertTrue("必须落在未闭合的新 op 上，实际 $verdict", pending != null)
        assertEquals(listOf("bundle-new"), pending!!.bundleNames)
        assertEquals(9000L, pending.detectedAtMillis)
    }

    /**
     * **journal 读不出来 → Unknown，不是 Consistent。**
     *
     * 「不知道」必须能与「没问题」区分开：把读不出来的 journal 当「一切正常」，
     * 正是计划验收禁止的「自动伪装成全成功」的另一种写法。
     */
    @Test
    fun anUnreadableJournalYieldsUnknownNotConsistent() {
        val store = newStore()
        journal.writeText("not json")

        val verdict = store.recoveryVerdict(emptyMap())
        assertTrue("读不出来必须报 Unknown，实际 $verdict", verdict is OperationJournalStore.RecoveryVerdict.Unknown)
        assertTrue(
            "Unknown 与 Consistent 必须是两个不同的值",
            verdict != OperationJournalStore.RecoveryVerdict.Consistent
        )
    }

    /** 没有 journal → 一致（没有进行中的提交）。 */
    @Test
    fun absentJournalIsConsistent() {
        val store = newStore()
        assertEquals(
            OperationJournalStore.RecoveryVerdict.Consistent,
            store.recoveryVerdict(emptyMap())
        )
    }

    /**
     * 目标 `modUris` 为空的 TARGET_REPLACED（比如只搬了目录、没有具体 mod 记录）
     * → 无法核对，按**待核对**处理（保守：宁可多提示一次，也不要说「没事」）。
     */
    @Test
    fun aReplacedEntryWithNoModUrisIsTreatedAsPending() {
        val store = newStore()
        store.append(
            JournalEntry(
                operationId = "op-1",
                stage = JournalStage.TARGET_REPLACED.id,
                atMillis = 1L,
                targets = listOf(JournalTarget("bundle-a", "hash-a", 1L, emptyList()))
            )
        )
        assertTrue(
            "没有可核对的 mod uri 时不能报「一致」——那是猜测",
            store.recoveryVerdict(emptyMap()) is OperationJournalStore.RecoveryVerdict.PendingReconciliation
        )
    }

    /**
     * **同一个 URI 撞了、但账本记的是别的 bundle → 必须报待核对**（本轮真缺陷）。
     * 判据要落在 [LedgerRecord.snapshotTargetHash] 与 bundleName 对不对得上，而不是 `uri` 键在不在；
     * 否则旧的同 URI 记录会把「本次没落账」伪装成一致（计划验收第 2 条禁止）。
     */
    @Test
    fun aReplacedTargetWhoseLedgerRecordPointsAtADifferentBundleIsPending() {
        val store = newStore()
        store.append(
            JournalEntry(
                operationId = "op-1",
                stage = JournalStage.TARGET_REPLACED.id,
                atMillis = 5000L,
                targets = listOf(JournalTarget("bundle-a", "hash-a", 4096L, listOf("uri-a")))
            )
        )

        // 账本里**有** uri-a 这个键，但它的 snapshotTargetHash 记的是**另一个 bundle**
        // （上一次别的安装留下的）。只看「键在不在」会把它当成一致——那正是缺陷。
        val ledger = mapOf("uri-a" to record("uri-a", "bundle-OTHER"))
        val verdict = store.recoveryVerdict(ledger)
        assertTrue(
            "同 URI 但账本记的是别的 bundle → 必须报待核对，实际 $verdict",
            verdict is OperationJournalStore.RecoveryVerdict.PendingReconciliation
        )
        val pending = verdict as OperationJournalStore.RecoveryVerdict.PendingReconciliation
        assertEquals(listOf("bundle-a"), pending.bundleNames)
        assertEquals(listOf("uri-a"), pending.modUris)
    }

    /** 账本部分匹配也不能缩小未闭合窗口：两个目标都还没被本 op 的 commit 闭合。 */
    @Test
    fun ledgerPartialMatchDoesNotShrinkAnUnclosedReplacementWindow() {
        val store = newStore()
        store.append(
            JournalEntry(
                operationId = "op-1",
                stage = JournalStage.TARGET_REPLACED.id,
                atMillis = 5000L,
                targets = listOf(
                    JournalTarget("bundle-a", "hash-a", 1L, listOf("uri-a")),
                    JournalTarget("bundle-b", "hash-b", 2L, listOf("uri-b"))
                )
            )
        )

        val ledger = mapOf(
            "uri-a" to record("uri-a", "bundle-a"),
            "uri-b" to record("uri-b", "bundle-WRONG")
        )
        val verdict = store.recoveryVerdict(ledger)
        assertTrue(
            "缺内容证明时整批保持待核对，实际 $verdict",
            verdict is OperationJournalStore.RecoveryVerdict.PendingReconciliation
        )
        val pending = verdict as OperationJournalStore.RecoveryVerdict.PendingReconciliation
        assertEquals(listOf("bundle-a", "bundle-b"), pending.bundleNames)
        assertEquals(listOf("uri-a", "uri-b"), pending.modUris)
    }

    // ------------------------------------ 写前意图（target_replacing）未闭合

    /**
     * **写前意图未闭合 → 必须报待核对，不是「不知道」。**（T08 本轮引入）
     *
     * ## 这一格补的是什么窗口
     *
     * [JournalStage.TARGET_REPLACED] 是 `copyDirectory` **返回成功之后**才落的 ——
     * 它回答的是「文件确实换了」。但在**动手换文件之前**、意图刚落盘到真正调用
     * `copyDirectory` 之间，还有一小段：进程若在那里崩，磁盘上可能是「文件换了一半」
     * 或「压根没换」，而账本、TARGET_REPLACED 都还没写。既有判据只认 TARGET_REPLACED，
     * 于是这一段崩溃会被判成 [OperationJournalStore.RecoveryVerdict.Consistent]（伪装成没事）。
     *
     * 写前意图阶段 `target_replacing`（**用字面串，不依赖尚不存在的枚举值**）就是
     * 用来钉住这一段的：只要 journal 里存着一条 `target_replacing` 而**没有它自己后续的**
     * [JournalStage.LEDGER_COMMITTED]，就必须报
     * [OperationJournalStore.RecoveryVerdict.PendingReconciliation] —— 「不知道文件换没换」
     * 也是**必须核对**的状态，不能当成一致，更不能因为 stage 不认识就退化成
     * [OperationJournalStore.RecoveryVerdict.Unknown]（那会把一条合法的进行中意图当成损坏日志）。
     *
     * 今天这条**必红**：现有 `parse()` 不认识 `target_replacing`，整份判 `Unreadable`，
     * `recoveryVerdict` 因此返回 `Unknown`。绿灯要求引入该 stage 且让未闭合意图 → 待核对。
     */
    @Test
    fun anUnclosedWriteAheadIntentIsPendingNotUnknown() {
        val store = newStore()
        store.append(
            JournalEntry(
                operationId = "op-1",
                stage = "target_replacing",
                atMillis = 5000L,
                targets = listOf(JournalTarget("bundle-a", "hash-a", 4096L, listOf("uri-a")))
            )
        )

        val verdict = store.recoveryVerdict(emptyMap())
        assertTrue(
            "未闭合的写前意图必须报待核对，不能是 Unknown（stage 不认识）或 Consistent（伪装没事），实际 $verdict",
            verdict is OperationJournalStore.RecoveryVerdict.PendingReconciliation
        )
        val pending = verdict as OperationJournalStore.RecoveryVerdict.PendingReconciliation
        assertEquals(listOf("bundle-a"), pending.bundleNames)
        assertEquals(listOf("uri-a"), pending.modUris)
        assertEquals("待核对结论要带上意图落盘的时刻", 5000L, pending.detectedAtMillis)
    }

    /**
     * **账本里已有相同 URI/hash 的记录，也挡不住未闭合意图报待核对。**
     *
     * 这是与 [JournalStage.TARGET_REPLACED] 判据的关键分野：TARGET_REPLACED 只要账本对得上
     * 就算账本 URI/bundle 对得上（见
     * [replacedTargetWithoutFactsStaysPendingEvenWhenLedgerMatches]），也不能把未闭合意图当没事。
     * 但 `target_replacing` 只宣告「打算换」——账本里那条相同 URI/bundle 的记录可能是
     * **上一次**成功安装留下的，跟这次「打算换」的意图闭没闭合毫无关系。若因为账本对得上就放过，
     * 正是计划验收第 2 条禁止的「把没完成的当成全成功」。
     *
     * 因此：只要这条 `target_replacing` 没有它**自己**后续的 LEDGER_COMMITTED，
     * 哪怕账本 `uri-a → bundle-a` 完全吻合，也必须报待核对。
     */
    @Test
    fun anUnclosedIntentStaysPendingEvenWhenTheLedgerAlreadyMatches() {
        val store = newStore()
        store.append(
            JournalEntry(
                operationId = "op-1",
                stage = "target_replacing",
                atMillis = 5000L,
                targets = listOf(JournalTarget("bundle-a", "hash-a", 4096L, listOf("uri-a")))
            )
        )

        // 账本里 uri-a → bundle-a 完全吻合（上一次安装留下的），但这次意图并未闭合。
        val ledger = mapOf("uri-a" to record("uri-a", "bundle-a"))
        val verdict = store.recoveryVerdict(ledger)
        assertTrue(
            "相同 URI/bundle 已在账本 ≠ 本次意图已闭合，仍必须报待核对，实际 $verdict",
            verdict is OperationJournalStore.RecoveryVerdict.PendingReconciliation
        )
        assertEquals(
            listOf("bundle-a"),
            (verdict as OperationJournalStore.RecoveryVerdict.PendingReconciliation).bundleNames
        )
    }

    /**
     * **别的操作提交了账本，遮不住本操作的未闭合意图。**
     *
     * 闭合意图的只能是它**自己**（同 operationId）后续的 LEDGER_COMMITTED。别的 op
     * 写了 LEDGER_COMMITTED 只说明**那一次**收尾了，与 `op-1` 那条悬着的 `target_replacing`
     * 无关。若判据被别人的收尾带偏，就能被无关操作「洗白」——那正是伪装成全成功的一条暗路。
     */
    @Test
    fun anotherOperationsCommitDoesNotHideThisIntentsPendingState() {
        val store = newStore()
        // op-1 悬着一条写前意图；op-2 已走完自己的收尾（时间更晚）。
        // 两条必须共存，用字面量避免第二次 append 丢掉读不出来的意图条。
        journal.writeText(
            """{"schemaVersion":1,"entries":[
                 {"operationId":"op-1","stage":"target_replacing","atMillis":5000,
                  "targets":[{"bundleName":"bundle-a","hashDir":"hash-a","dataBytes":4096,"modUris":["uri-a"]}]},
                 {"operationId":"op-2","stage":"ledger_committed","atMillis":9000,
                  "targets":[{"bundleName":"bundle-z","hashDir":"hash-z","dataBytes":1,"modUris":["uri-z"]}]}
               ]}"""
        )

        val verdict = store.recoveryVerdict(mapOf("uri-z" to record("uri-z", "bundle-z")))
        assertTrue(
            "别的 op 收尾遮不住 op-1 未闭合的意图，仍必须报待核对，实际 $verdict",
            verdict is OperationJournalStore.RecoveryVerdict.PendingReconciliation
        )
        assertEquals(
            "待核对指向的必须是 op-1 那条意图涉及的 bundle",
            listOf("bundle-a"),
            (verdict as OperationJournalStore.RecoveryVerdict.PendingReconciliation).bundleNames
        )
    }

    /**
     * **只有它自己后续的 LEDGER_COMMITTED 才能闭合意图 → 一致。**
     *
     * 这是绿灯的正面：`op-1` 落了 `target_replacing`，随后**同一个 op-1** 又落了
     * LEDGER_COMMITTED（账本也确实对得上），窗口彻底关闭，必须报
     * [OperationJournalStore.RecoveryVerdict.Consistent]。
     *
     * 与 [anotherOperationsCommitDoesNotHideThisIntentsPendingState] 成对：闭合看的是
     * **同 operationId** 的后续收尾，不是「journal 里存在任意一条 LEDGER_COMMITTED」。
     */
    @Test
    fun anIntentClosedByItsOwnLaterLedgerCommitIsConsistent() {
        val store = newStore()
        // 同一个 op-1 先落写前意图、后落收尾，两条必须共存——同样用字面量避免
        // 第二次 append 把读不出来的写前意图丢掉。
        journal.writeText(
            """{"schemaVersion":1,"entries":[
                 {"operationId":"op-1","stage":"target_replacing","atMillis":5000,
                  "targets":[{"bundleName":"bundle-a","hashDir":"hash-a","dataBytes":4096,"modUris":["uri-a"]}]},
                 {"operationId":"op-1","stage":"ledger_committed","atMillis":6000,
                  "targets":[{"bundleName":"bundle-a","hashDir":"hash-a","dataBytes":4096,"modUris":["uri-a"]}]}
               ]}"""
        )

        val verdict = store.recoveryVerdict(mapOf("uri-a" to record("uri-a", "bundle-a")))
        assertEquals(
            "意图被它自己后续的 LEDGER_COMMITTED 闭合，窗口关闭 → 一致",
            OperationJournalStore.RecoveryVerdict.Consistent,
            verdict
        )
    }

    /**
     * **重启后连续两次核对：判定层自己不写任何东西、结论幂等。**
     * 只声称 [OperationJournalStore.recoveryVerdict] 这一层是纯读——不重放、不写任何文件（包括
     * 旁边预置的模拟账本与目标哨兵文件）；**不**声称整条安装链已防叠加。
     * 用空账本制造稳定的待核对态（不依赖 bundle 匹配修复），对同一文件重新 new store 模拟重启。
     */
    @Test
    fun theVerdictIsPureAcrossRestartsWritingNothing() {
        val store = newStore()
        store.append(
            JournalEntry(
                operationId = "op-1",
                stage = JournalStage.TARGET_REPLACED.id,
                atMillis = 5000L,
                targets = listOf(JournalTarget("bundle-a", "hash-a", 4096L, listOf("uri-a")))
            )
        )
        val dir = journal.parentFile!!
        // 旁边放两个哨兵（纯临时目录内，不碰真实游戏路径）：模拟账本与一份目标文件。
        val ledgerSentinel = File(dir, "installed_mods.json").apply { writeText("""{"sentinel":"ledger"}""") }
        val targetSentinel = File(dir, "__data").apply { writeText("sentinel-target-bytes") }
        val journalBytesBefore = journal.readBytes()
        val ledgerBytesBefore = ledgerSentinel.readBytes()
        val targetBytesBefore = targetSentinel.readBytes()
        val filesBefore = dir.list()!!.sorted()

        // 模拟重启：对同一个 journal 文件重新 new 一个 store。空账本 = 稳定的待核对。
        val restarted = OperationJournalStore(journal)
        val first = restarted.recoveryVerdict(emptyMap())
        val second = restarted.recoveryVerdict(emptyMap())

        assertTrue(
            "待核对态：第一次核对必须报 PendingReconciliation，实际 $first",
            first is OperationJournalStore.RecoveryVerdict.PendingReconciliation
        )
        assertEquals("连续两次核对结论必须一致（幂等）", first, second)
        assertArrayEquals("核对不许改动 journal 字节", journalBytesBefore, journal.readBytes())
        assertArrayEquals("核对不许改动模拟账本字节", ledgerBytesBefore, ledgerSentinel.readBytes())
        assertArrayEquals("核对不许改动目标哨兵字节", targetBytesBefore, targetSentinel.readBytes())
        assertEquals(
            "核对不许新建任何文件（判定层不写 ledger/journal/目标）",
            filesBefore, dir.list()!!.sorted()
        )
    }

    // ------------------------------------------- 畸形 journal 不许让核对抛异常

    /**
     * **缺 `targets` / 缺 `target.modUris` 的 journal 必须被判废，不许在核对时抛 NPE**
     * （T08 二轮整改，复核真缺陷 3）。
     *
     * ## 为什么这不是「不可能」
     *
     * Gson 用 Unsafe 造实例、**完全不理会 Kotlin 的非空约束** —— 本仓库自己就在
     * `InstalledLedgerStore` 的解析里写明了这件事。所以「同 schema 但字段缺失」的
     * JSON 反序列化出来字段真的是 `null`，而 `recoveryVerdict()` 直接对
     * `entry.targets` / `target.modUris` 迭代。复核者用手写字面量复现了两次 NPE：
     *
     * ```
     * PROBE missing targets field   -> THREW java.lang.NullPointerException: "$this$filterTo$iv$iv" is null
     * PROBE target without modUris  -> THREW java.lang.NullPointerException: JournalTarget.getModUris() is null
     * ```
     *
     * 今天没有生产调用者所以是潜伏的；一旦接进启动路径（T10 的快照失效链），
     * 一份被外部改过、或未来版本写出的同 schema journal 就能让**启动崩**。
     *
     * ## 判据的两半（缺一不可）
     *
     * 1. [JournalRead.read] 判 `Unreadable` —— 与「不认识 stage」「未知版本」同一取向：
     *    缺字段的条目不可信，宁可说「不知道」；
     * 2. [OperationJournalStore.recoveryVerdict] **不抛**，走可预期的分支
     *    （`Unreadable` → [OperationJournalStore.RecoveryVerdict.Unknown]）。
     *
     * 第 2 半是对照证伪的靶子：把 `parse()` 里那两处新增的判废删掉，
     * 这一条就会以 NPE 红。
     */
    @Test
    fun aJournalMissingTargetFieldsIsRejectedInsteadOfCrashingTheVerdict() {
        // 三种畸形各来一份：整条缺 targets、target 缺 modUris、targets 是 null 字面量。
        val malformed = listOf(
            "缺 targets 字段" to
                """{"schemaVersion":1,"entries":[
                     {"operationId":"op-1","stage":"target_replaced","atMillis":1}
                   ]}""",
            "target 缺 modUris" to
                """{"schemaVersion":1,"entries":[
                     {"operationId":"op-1","stage":"target_replaced","atMillis":1,
                      "targets":[{"bundleName":"bundle-a"}]}
                   ]}""",
            "targets 是 null 字面量" to
                """{"schemaVersion":1,"entries":[
                     {"operationId":"op-1","stage":"target_replaced","atMillis":1,"targets":null}
                   ]}"""
        )

        for ((what, text) in malformed) {
            val store = newStore()
            journal.writeText(text)

            assertTrue(
                "$what：必须判不可读，而不是留给核对去炸",
                store.read() is JournalRead.Unreadable
            )
            // 第二半：核对路径**不抛**，且给出「不知道」而不是「没问题」。
            val verdict = store.recoveryVerdict(emptyMap())
            assertTrue(
                "$what：核对必须给出 Unknown（不知道），实际 $verdict",
                verdict is OperationJournalStore.RecoveryVerdict.Unknown
            )
        }
    }

    @Test
    fun legacyTargetWithoutBundleNameIsUnreadable() {
        assertMalformedLegacyTargetRejected("""{"modUris":["uri-a"]}""")
    }

    @Test
    fun legacyTargetWithNullBundleNameIsUnreadable() {
        assertMalformedLegacyTargetRejected("""{"bundleName":null,"modUris":["uri-a"]}""")
    }

    private fun assertMalformedLegacyTargetRejected(rawTarget: String) {
        val store = newStore()
        journal.writeText(
            """{"schemaVersion":1,"entries":[
                {"operationId":"op-a","stage":"target_replaced","atMillis":1,
                 "targets":[$rawTarget]}]}"""
        )
        val before = journal.readBytes()

        assertTrue("missing target identity must be unreadable", store.read() is JournalRead.Unreadable)
        assertTrue(
            "malformed legacy target must not reach observation",
            store.recoveryVerdict(emptyMap()) is OperationJournalStore.RecoveryVerdict.Unknown
        )
        assertTrue("append must not replace malformed evidence", store.append(entry(JournalStage.PLANNED)) != null)
        assertArrayEquals(before, journal.readBytes())
    }

    // ---------------------------------------------- 破坏判据会红在哪（自我说明）

    /**
     * 这一条**不是**新断言，而是把「判据依赖什么」写死在用例里，让破坏实验可预期：
     *
     * - 把 TARGET_REPLACED 落到错误的分支（比如只在 PLANNED 里落），
     *   [replacedTargetMissingFromLedgerIsPendingReconciliation] 会红（不再有待核对）；
     * - 把「全部都在才算一致」改成「任意一个在就算一致」，
     *   [partiallyRecordedReplacementIsStillPendingReconciliation] 会红；
     * - 把 Unreadable 并入 Consistent，[anUnreadableJournalYieldsUnknownNotConsistent] 会红。
     */
    @Test
    fun theVerdictDependsOnExactlyTheStageAndTheLedgerContents() {
        val store = newStore()
        val target = JournalTarget("bundle-a", "hash-a", 1L, listOf("uri-a"))

        // 同一批目标、同一份空账本，唯一变量是阶段。
        store.append(entry(JournalStage.STAGED, targets = listOf(target)))
        assertEquals(
            "STAGED 阶段（没动游戏文件）必须是 Consistent",
            OperationJournalStore.RecoveryVerdict.Consistent,
            store.recoveryVerdict(emptyMap())
        )

        store.append(entry(JournalStage.TARGET_REPLACED, at = 2L, targets = listOf(target)))
        assertTrue(
            "同一份账本、同一批目标，只是阶段推进到 TARGET_REPLACED，结论必须翻面",
            store.recoveryVerdict(emptyMap()) is OperationJournalStore.RecoveryVerdict.PendingReconciliation
        )
    }

    // ------------------------------------------------------------------ 辅助

    private fun entry(
        stage: JournalStage,
        at: Long = 1000L,
        targets: List<JournalTarget> = listOf(JournalTarget("bundle-a", "hash-a", 1L, listOf("uri-a")))
    ) = JournalEntry(
        operationId = "op-${stage.id}",
        stage = stage.id,
        atMillis = at,
        targets = targets
    )

    private fun record(uri: String, bundle: String) = LedgerRecord(
        modUri = uri,
        modName = uri,
        familyKey = "family-$uri",
        snapshotTargetHash = bundle,
        quality = "HD",
        usedAstc = false,
        installedAt = 1L
    )
}
