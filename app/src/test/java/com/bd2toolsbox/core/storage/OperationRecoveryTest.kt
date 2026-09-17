package com.bd2toolsbox.core.storage

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
        assertEquals("四个阶段各一条，共 4 条", 4, entries.size)
        assertEquals(
            listOf(
                JournalStage.PLANNED.id,
                JournalStage.STAGED.id,
                JournalStage.TARGET_REPLACED.id,
                JournalStage.LEDGER_COMMITTED.id
            ),
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

    /**
     * 损坏的 journal **不阻断重新开始**：append 会把读不出来的旧内容丢掉、重开一份。
     *
     * 这不是静默丢弃 —— `read()` 已经把 Unreadable 上报过了；
     * journal 是诊断证据，保住「以后还能核对」比留住一份读不出来的历史更有价值。
     */
    @Test
    fun appendingOverACorruptJournalStartsFreshAndIsReportedByRead() {
        val store = newStore()
        journal.writeText("not json at all")
        assertTrue("前提：旧内容确实读不出来", store.read() is JournalRead.Unreadable)

        assertNull("重开一份必须成功", store.append(entry(JournalStage.PLANNED)))
        val read = store.read()
        assertTrue("重开之后应当可读，实际 $read", read is JournalRead.Loaded)
        assertEquals("旧内容已被丢弃，只剩新的一条", 1, (read as JournalRead.Loaded).entries.size)
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

    /** **窗口已闭合**：journal 里有 TARGET_REPLACED，但账本里那几条都在 → 一致。 */
    @Test
    fun replacedTargetPresentInLedgerIsConsistent() {
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
        assertEquals(
            "账本里已经有这条记录了，说明那次提交后来落了账（journal 只是没清）",
            OperationJournalStore.RecoveryVerdict.Consistent, verdict
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
            "只该列出缺的那一个 bundle",
            listOf("bundle-b"),
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
     * **只看最新的那条 TARGET_REPLACED。**
     *
     * 历史上有过窗口、但后来成功闭合（账本里有），同时**最近一次**还没闭合 ——
     * 判据必须落在最近的那次上，否则会把「现在正裂着」误判成「早就没事了」。
     */
    @Test
    fun onlyTheMostRecentReplacedEntryDrivesTheVerdict() {
        val store = newStore()
        // 旧的：账本已经补上了
        store.append(
            JournalEntry(
                operationId = "op-old",
                stage = JournalStage.TARGET_REPLACED.id,
                atMillis = 1000L,
                targets = listOf(JournalTarget("bundle-old", "h", 1L, listOf("uri-old")))
            )
        )
        // 新的：账本里没有
        store.append(
            JournalEntry(
                operationId = "op-new",
                stage = JournalStage.TARGET_REPLACED.id,
                atMillis = 9000L,
                targets = listOf(JournalTarget("bundle-new", "h", 1L, listOf("uri-new")))
            )
        )

        val verdict = store.recoveryVerdict(mapOf("uri-old" to record("uri-old", "bundle-old")))
        val pending = verdict as? OperationJournalStore.RecoveryVerdict.PendingReconciliation
        assertTrue("必须落在最近那条（9000ms）上，实际 $verdict", pending != null)
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
