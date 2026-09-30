package com.bd2toolsbox.core.storage

import com.bd2toolsbox.core.model.OperationErrorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `InstalledLedgerStore` 的四态读取与原子落盘（T08）。
 *
 * ## 这一组守的是哪一层
 *
 * **Store 本体**：四态判定、schema 闸门、损坏原件的保留与代价、写入原子性。
 * 它**不**守 `InstalledModRepository` 的并发（那是 `InstalledLedgerStoreTest` 里
 * 通过 mockk 桩 `Context` 的那几条 + `OperationRecoveryTest`），
 * 也**不**守「谁在什么时机调它」（那是调用点的事，登记在 `T08.md` 的未覆盖项里）。
 *
 * ## 夹具
 *
 * 仓里 `local/refactor-inputs/legacy-device-0.2.16/files/` **没有** `installed_mods.json`
 * （与 `docs/refactor/fixture-manifest.json` 的 `missing.installed-mods-ledger` 一致），
 * 所以**全部输入都在 `TemporaryFolder` 里现造**：正常表 / 空表 / 半截 JSON /
 * 未知字段 / schema 不兼容 / v0 裸表。**不写任何真实设备路径。**
 *
 * ## 断言纪律
 *
 * 每条期望值都是**与实现无关的字面量**（写死的 JSON 文本、写死的记录条数），
 * 没有任何一条拿被测函数自己的输出去算期望 —— 那种写法在「实现被改坏」时
 * 期望会跟着一起变、断言恒真（T07 复核抓到的形状，见 `T07.md` §5.3 与 §11.2）。
 */
class InstalledLedgerStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var ledger: File

    /**
     * 每次都开一个**新的**子目录：同一用例里需要多个 Store 时
     * `temp.newFolder("files")` 会撞名抛 IOException（第一版就是这么红的）。
     */
    private fun newStore(): InstalledLedgerStore {
        ledger = File(temp.newFolder("files-" + (dirSeq++)), "installed_mods.json")
        return InstalledLedgerStore(ledger)
    }

    private var dirSeq = 0

    // ------------------------------------------------------------------ 四态

    /**
     * **全新安装**：文件不存在 → [LedgerRead.NotFound]，且**允许继续写**。
     *
     * 这条守的是本任务最容易写错的一格：把「文件不存在」也当损坏而拒绝写入，
     * 会让用户第一次装 mod 就永远失败。
     */
    @Test
    fun missingFileReadsAsNotFoundAndStillAcceptsAWrite() {
        val store = newStore()
        assertFalse("前提：文件真的不存在", ledger.exists())

        val read = store.read()
        assertEquals(
            "文件不存在必须是 NotFound —— 它不是损坏，没有已有数据需要保护",
            LedgerRead.NotFound, read
        )

        val error = store.save(mapOf(URI_A to record(URI_A)))
        assertNull("全新安装必须能写进去，实际失败：" + error?.message, error)
        assertTrue("写完之后文件要真的在磁盘上", ledger.isFile)
    }

    /**
     * **合法空表** → [LedgerRead.Empty]，且与 [LedgerRead.NotFound] **不是同一个态**。
     *
     * 「空」与「不存在」分开，是因为它们的业务含义不同（一个是「装过又卸干净了」，
     * 一个是「从没装过」），而更重要的判别是：两者都允许写，
     * 而损坏**不一定**允许 —— 见下一条。
     */
    @Test
    fun validEmptyLedgerReadsAsEmptyDistinctFromNotFound() {
        val store = newStore()
        ledger.writeText("""{"schemaVersion":1,"records":{}}""")

        assertEquals(LedgerRead.Empty, store.read())
        assertFalse(
            "Empty 与 NotFound 必须是两个不同的态（否则调用方无法区分全新安装与已清空的账本）",
            store.read() == LedgerRead.NotFound
        )
    }

    /** 正常表 → [LedgerRead.Loaded]，条数与内容由**写死的字面量**核对。 */
    @Test
    fun populatedLedgerReadsAsLoadedWithEveryRecord() {
        val store = newStore()
        ledger.writeText(
            """
            {"schemaVersion":1,"records":{
              "uri-a":{"modUri":"uri-a","modName":"A","familyKey":"fa","snapshotTargetHash":"h1",
                       "quality":"HD","usedAstc":false,"installedAt":1000},
              "uri-b":{"modUri":"uri-b","modName":"B","familyKey":"fb","snapshotTargetHash":"h2",
                       "quality":"SD","usedAstc":true,"installedAt":2000}
            }}
            """.trimIndent()
        )

        val read = store.read()
        assertTrue("应当是 Loaded，实际 $read", read is LedgerRead.Loaded)
        val records = (read as LedgerRead.Loaded).records
        assertEquals("条数必须等于夹具里写死的 2", 2, records.size)
        assertEquals("uri-a", records.getValue("uri-a").modUri)
        assertEquals("A", records.getValue("uri-a").modName)
        assertEquals("SD", records.getValue("uri-b").quality)
        assertTrue("usedAstc 必须逐字段还原", records.getValue("uri-b").usedAstc)
        assertEquals(2000L, records.getValue("uri-b").installedAt)
    }

    /**
     * **半截 JSON → Corrupt，且拒绝覆写。**
     *
     * 这条直接对应计划验收第 2 条「损坏账本不会被下一次安装当空表覆盖」：
     * 改动前 `load()` 返回空表 → 下一次 `put()` 把「只有新记录」的表整体覆写上去，
     * 旧记录永久丢失。这里证明 Store **拒绝**在没有处置原件的情况下覆盖。
     */
    @Test
    fun truncatedJsonIsCorruptAndTheOriginalSurvivesUntilPreserved() {
        val store = newStore()
        val truncated = """{"schemaVersion":1,"records":{"uri-a":"""
        ledger.writeText(truncated)

        val read = store.read()
        assertTrue("半截 JSON 必须是 Corrupt，实际 $read", read is LedgerRead.Corrupt)

        // 读路径**零副作用**：原件一个字都没动（改名是写入方的显式决定）。
        assertEquals("读一遍不能改动磁盘上的原件", truncated, ledger.readText())
    }

    /**
     * **损坏 + 显式保留原件 → 原件以确定性名字留在同目录。**
     *
     * 名字必须能被**独立算出来**（这里用前缀 + 长度 + 时间戳形状校验，
     * 而不是调 Store 的格式化器去生成期望值 —— 那会是自我参照）。
     */
    @Test
    fun preservingACorruptLedgerKeepsTheOriginalUnderADeterministicName() {
        val store = newStore()
        val original = """{"schemaVersion":1,"records":{半截"""
        ledger.writeText(original)

        val preserved = store.preserveCorruptFile()
        assertNotNull("损坏原件必须能保留下来", preserved)
        val name = preserved!!.name
        assertTrue(
            "保留件的名字必须以 installed_mods.json${InstalledLedgerStore.CORRUPT_SUFFIX_PREFIX} 开头，实际 $name",
            name.startsWith("installed_mods.json" + InstalledLedgerStore.CORRUPT_SUFFIX_PREFIX)
        )
        // 时间戳形状写死成 yyyyMMdd-HHmmss 的**字符形状**（8 位数字 + '-' + 6 位数字）。
        val stamp = name.removePrefix(
            "installed_mods.json" + InstalledLedgerStore.CORRUPT_SUFFIX_PREFIX
        )
        assertTrue(
            "时间戳必须是确定性的 yyyyMMdd-HHmmss（实际 '$stamp'），否则复核者无法按名字排序",
            Regex("""^\d{8}-\d{6}$""").matches(stamp)
        )
        assertEquals("保留件的内容必须与原件逐字节相同", original, preserved.readText())
        assertFalse("原件已被改名，原路径上不该再有文件", ledger.exists())
    }

    /** 保留之后**新表写在原路径**上，于是「保留原件」与「用户还能继续用」同时成立。 */
    @Test
    fun afterPreservingTheCorruptFileANewLedgerCanBeWritten() {
        val store = newStore()
        ledger.writeText("""{"schemaVersion":1,"records":{半截""")
        store.preserveCorruptFile()

        assertNull("保留原件之后必须能写新表", store.save(mapOf(URI_A to record(URI_A))))
        val read = store.read()
        assertTrue("新表应当可读，实际 $read", read is LedgerRead.Loaded)
        assertEquals(1, (read as LedgerRead.Loaded).records.size)
    }

    /** **合法 JSON 但结构不对**（顶层是数组 / 标量 / `null`）→ 一律 Corrupt，不是空表。 */
    @Test
    fun structurallyWrongJsonIsCorruptNotAnEmptyLedger() {
        for (text in listOf("[]", "123", "\"hello\"", "null", "true")) {
            val store = newStore()
            ledger.writeText(text)
            val read = store.read()
            assertTrue(
                "顶层是 '$text' 的账本必须判损坏（它是合法 JSON 但不是一张表），实际 $read",
                read is LedgerRead.Corrupt
            )
        }
    }

    /** **schema 不兼容** → Corrupt。不认识的版本读成空表会静默丢记录。 */
    @Test
    fun unknownSchemaVersionIsCorruptNotAnEmptyLedger() {
        val store = newStore()
        ledger.writeText("""{"schemaVersion":99,"records":{"uri-a":{}}}""")

        val read = store.read()
        assertTrue("未知 schemaVersion 必须判损坏，实际 $read", read is LedgerRead.Corrupt)
        assertTrue(
            "损坏原因里要能看见实际版本号，便于排查",
            (read as LedgerRead.Corrupt).reason.contains("99")
        )
    }

    /** 缺 `schemaVersion` 的 v1 形状（有 `records` 但没版本）→ Corrupt。 */
    @Test
    fun recordsWithoutVersionKeyIsCorrupt() {
        val store = newStore()
        ledger.writeText("""{"records":{"uri-a":{"modUri":"uri-a"}}}""")
        assertTrue("有 records 却没有 schemaVersion，必须判损坏", store.read() is LedgerRead.Corrupt)
    }

    /** 版本对、`records` 不是对象 → Corrupt。 */
    @Test
    fun recordsThatIsNotAnObjectIsCorrupt() {
        val store = newStore()
        ledger.writeText("""{"schemaVersion":1,"records":[]}""")
        assertTrue("records 是数组时必须判损坏", store.read() is LedgerRead.Corrupt)
    }

    /**
     * **未知的额外顶层键一律忽略**（向前兼容）。
     *
     * 反过来说：将来给账本加字段，不该让今天写出的表在明天判废。
     */
    @Test
    fun unknownExtraTopLevelKeysAreIgnored() {
        val store = newStore()
        ledger.writeText(
            """{"schemaVersion":1,"records":{"uri-a":{"modUri":"uri-a","modName":"A",
               "familyKey":"fa","snapshotTargetHash":"h1","quality":"HD","usedAstc":false,
               "installedAt":7}},"futureField":{"whatever":true}}"""
        )
        val read = store.read()
        assertTrue("额外顶层键不该让账本判废，实际 $read", read is LedgerRead.Loaded)
        assertEquals(1, (read as LedgerRead.Loaded).records.size)
    }

    /**
     * **记录内缺必需字段 → 整份 Corrupt**，而不是静默丢掉那一条。
     *
     * 静默丢单条 = 静默丢数据：用户会看到「某个 mod 明明装着却显示未安装」，
     * 且没有任何线索。整份判损坏至少会留下可见痕迹（原件被保留 + 错误上报）。
     */
    @Test
    fun recordMissingARequiredFieldIsCorruptInsteadOfBeingSilentlyDropped() {
        val store = newStore()
        // 第二条缺 modName（Gson 会把它填成 null，而 Kotlin 认为它不可空）
        ledger.writeText(
            """{"schemaVersion":1,"records":{
              "uri-a":{"modUri":"uri-a","modName":"A","familyKey":"fa",
                       "snapshotTargetHash":"h1","quality":"HD","usedAstc":false,"installedAt":1},
              "uri-b":{"modUri":"uri-b","familyKey":"fb",
                       "snapshotTargetHash":"h2","quality":"HD","usedAstc":false,"installedAt":2}
            }}"""
        )
        val read = store.read()
        assertTrue("缺字段的记录必须让整份判损坏，实际 $read", read is LedgerRead.Corrupt)
        assertTrue(
            "损坏原因要点出是哪一条记录（实际：${(read as LedgerRead.Corrupt).reason}）",
            read.reason.contains("uri-b")
        )
    }

    /** 记录键与 `modUri` 不一致 → Corrupt（否则删除 / 回滚会对不上账）。 */
    @Test
    fun keyNotMatchingItsOwnModUriIsCorrupt() {
        val store = newStore()
        ledger.writeText(
            """{"schemaVersion":1,"records":{
              "uri-a":{"modUri":"uri-DIFFERENT","modName":"A","familyKey":"fa",
                       "snapshotTargetHash":"h1","quality":"HD","usedAstc":false,"installedAt":1}
            }}"""
        )
        assertTrue("键与 modUri 不一致必须判损坏", store.read() is LedgerRead.Corrupt)
    }

    /** An existing blank file may be a truncated ledger, not a valid empty map. */
    @Test
    fun blankFileIsCorruptAndRemainsUntouched() {
        val store = newStore()
        for (text in listOf("", " \n\t")) {
            ledger.writeText(text)
            assertTrue(store.read() is LedgerRead.Corrupt)
            assertEquals(text, ledger.readText())
        }
    }

    /**
     * 路径被**目录**占着 → [LedgerRead.IoError]（**不是** `Corrupt`）。
     *
     * 这一格刻意不是「损坏」：把目录当损坏会让写入路径走去**改名那个目录**，
     * 而目录里可能有用户的东西。归到「读不出来」= 拒绝写入、不动磁盘，才是安全的那一支。
     */
    @Test
    fun aDirectoryOccupyingTheLedgerPathIsAnIoErrorNotCorruption() {
        val store = newStore()
        assertTrue("前提：真的建出了同名目录", ledger.mkdirs())

        val read = store.read()
        assertTrue(
            "目录占位必须归到 IoError（拒绝写入、不动磁盘），实际 $read",
            read is LedgerRead.IoError
        )
        assertTrue("并且目录本身还在原地", ledger.isDirectory)
    }

    // -------------------------------------------------------------- v0 兼容

    /**
     * **0.2.16 写出的裸表（无版本号）必须能读出来。**
     *
     * 改动前 `save()` 写的是 `gson.toJson(records)` —— 一个裸 Map，没有 `schemaVersion`。
     * 真实用户升级上来时磁盘上就是这个形态。若不认它，第一次运行会把用户全部装入记录
     * 当损坏改名掉、重建空表 —— 所有 mod 变成「未安装」。
     *
     * 期望值写死：2 条、字段值与夹具里的字面量逐个相等。
     */
    @Test
    fun legacyBareTableWithoutSchemaVersionIsStillRead() {
        val store = newStore()
        ledger.writeText(
            """{
              "uri-a":{"modUri":"uri-a","modName":"A","familyKey":"fa","snapshotTargetHash":"h1",
                       "quality":"HD","usedAstc":false,"installedAt":1000},
              "uri-b":{"modUri":"uri-b","modName":"B","familyKey":"fb","snapshotTargetHash":"h2",
                       "quality":"SD","usedAstc":true,"installedAt":2000}
            }"""
        )

        val read = store.read()
        assertTrue("0.2.16 的裸表必须能读出来，实际 $read", read is LedgerRead.Loaded)
        val records = (read as LedgerRead.Loaded).records
        assertEquals("条数必须等于夹具里写死的 2", 2, records.size)
        assertEquals("A", records.getValue("uri-a").modName)
        assertEquals("h2", records.getValue("uri-b").snapshotTargetHash)
        assertEquals(2000L, records.getValue("uri-b").installedAt)
    }

    /** v0 裸表里出现一条不合法的记录时同样整份判损坏（不因为它是旧格式就放宽）。 */
    @Test
    fun legacyBareTableWithABadRecordIsStillCorrupt() {
        val store = newStore()
        ledger.writeText(
            """{"uri-a":{"modUri":"uri-a","modName":"A","familyKey":"fa",
                        "snapshotTargetHash":"h1","quality":"HD","usedAstc":false,"installedAt":1},
               "uri-b":{"modName":"B"}}"""
        )
        assertTrue("旧格式里的坏记录同样要判损坏", store.read() is LedgerRead.Corrupt)
    }

    // ---------------------------------------------------------------- 落盘

    /** 写出的是什么：顶层 `schemaVersion` + `records`，且**能被自己读回来**。 */
    @Test
    fun saveWritesAVersionedEnvelopeThatReadsBackIdentically() {
        val store = newStore()
        val records = mapOf(URI_A to record(URI_A), URI_B to record(URI_B).copy(quality = "SD"))
        assertNull(store.save(records))

        // 磁盘形态用**独立解析**核对（不调 Store 的解析器），避免自我参照。
        val text = ledger.readText()
        assertTrue("顶层必须有 schemaVersion:1（实际 $text）", text.contains("\"schemaVersion\":1"))
        assertTrue("载荷键必须是 records（实际 $text）", text.contains("\"records\""))

        val back = store.read()
        assertTrue(back is LedgerRead.Loaded)
        assertEquals(records, (back as LedgerRead.Loaded).records)
    }

    /**
     * **保存失败时旧账本仍可用**（计划验收第 1 条）。
     *
     * 造一次真实的失败：把账本文件设成只读，宿主 NTFS 上 `ATOMIC_MOVE` 替换只读目标会
     * 抛 `AccessDeniedException`（**实测**，见 `T08.md` §2 与探针输出）——
     * 于是 `save()` 必须返回非 null，而**旧内容一个字节不变**。
     */
    @Test
    fun aFailedSaveLeavesThePreviousLedgerIntactAndReportsTheFailure() {
        val store = newStore()
        assertNull("先写一份好账本", store.save(mapOf(URI_A to record(URI_A))))
        val before = ledger.readText()

        assertTrue("前提：目标真的变成只读", ledger.setWritable(false))
        try {
            val error = store.save(mapOf(URI_B to record(URI_B)))
            assertNotNull("只读目标上的写入必须失败并被上报（不是静默返回）", error)
            assertEquals(
                "替换只读目标的失败应当归到 IO / 权限类，而不是 MALFORMED_DATA",
                true,
                error!!.kind == OperationErrorKind.IO ||
                    error.kind == OperationErrorKind.PERMISSION_DENIED
            )
            assertEquals("失败后旧账本必须逐字节不变", before, ledger.readText())
        } finally {
            ledger.setWritable(true)
        }
    }

    /**
     * **写入过程中目标只会是「旧内容」或「完整新内容」**，不会是半截。
     *
     * 判据刻意不依赖任何平台属性（不用创建时间、不用 inode —— T07 §5.1 记过那条
     * 被宿主探针推翻的写法）：这里用「失败注入后目标必须仍是**完整**旧表」来钉住
     * 「没有直接覆写目标」这件事。
     *
     * ## 这条守的是「失败注入下目标不被改坏」，**不是**「`writeText` 会留半截」
     *
     * T08 二轮复核用 R1 证伪推翻了初版 KDoc 的说法：把 `save()` 打回 `File.writeText`
     * 之后，**这条用例仍然绿**（那次唯一红的是 `saveCreatesTheParentDirectoryWhenItIsMissing`）。
     * 原因是「只读目标」这种失败注入对 `writeText` 与 `AtomicFileWriter` 是**同一结果**
     * —— `writeText` 在打开阶段就抛 `AccessDeniedException`，一个字节都没写，
     * 所以目标同样是完整旧内容。
     *
     * **「`save()` 真的走了原子写」由哪条守**：`saveCreatesTheParentDirectoryWhenItIsMissing`
     * （`AtomicFileWriter` 会 `mkdirs`、`writeText` 不会 —— 那条才真的能区分两者）。
     * **「替换本身是原子的」由 T07 的 `AtomicFileWriterTest`（17 条）在写入器自己的
     * 实现上守**，不在 T08。见 `T08.md` §6.2 与 §5.1 末尾的口径说明。
     */
    @Test
    fun aWriteThatFailsHalfwayNeverLeavesAPartialLedger() {
        val store = newStore()
        assertNull(store.save(mapOf(URI_A to record(URI_A))))
        val oldText = ledger.readText()

        val target = ReadOnlyTarget.install(ledger)
        try {
            assertNotNull("注入的写失败必须被上报", store.save(mapOf(URI_B to record(URI_B))))
            assertEquals("失败后目标必须仍是完整旧内容（不是半截、不是空）", oldText, ledger.readText())
            assertEquals(
                "而且仍然要能读出完整的 1 条记录",
                1,
                ((store.read() as? LedgerRead.Loaded)?.records ?: emptyMap<String, LedgerRecord>()).size
            )
        } finally {
            target.restore()
        }
    }

    /** 成功落盘后**不留暂存件残留**（证明这一路真的走的是同目录暂存 + 替换）。 */
    @Test
    fun aSuccessfulSaveLeavesNoStagingResidue() {
        val store = newStore()
        assertNull(store.save(mapOf(URI_A to record(URI_A))))

        val dir = ledger.parentFile
        assertTrue("前提：账本有父目录", dir != null)
        val leftovers = com.bd2toolsbox.core.fileaccess.AtomicFileWriter.listStagingFiles(dir!!)
        assertEquals("成功提交之后不该留下暂存件，实际 $leftovers", 0, leftovers.size)
    }

    /** 账本父目录不存在时会被建出来（`AtomicFileWriter` 的 mkdirs 那一支真的被走到）。 */
    @Test
    fun saveCreatesTheParentDirectoryWhenItIsMissing() {
        val missing = File(temp.root, "deep/nested")
        assertFalse("前提：父目录真的不存在", missing.exists())
        val store = InstalledLedgerStore(File(missing, "installed_mods.json"))

        assertNull("父目录不存在也要能写成功", store.save(mapOf(URI_A to record(URI_A))))
        assertTrue("父目录必须被建出来", missing.isDirectory)
        assertTrue("并且账本真的落在那里", store.read() is LedgerRead.Loaded)
    }

    // ---------------------------------------------------------- 并发 / 原子性

    /**
     * **保存中断仍可读取旧账本**（计划验收第 1 条）。
     *
     * 「中断」用一次**写入中途抛异常**来模拟（真杀进程没法在单测里做）：
     * 字节已经写进同目录暂存件、但替换没发生 —— 目标必须保持旧内容。
     */
    @Test
    fun anInterruptedSaveKeepsTheOldLedgerReadable() {
        val store = newStore()
        assertNull(store.save(mapOf(URI_A to record(URI_A))))
        val old = store.read() as LedgerRead.Loaded

        val failure = ReadOnlyTarget.install(ledger)
        try {
            assertNotNull(store.save(mapOf(URI_B to record(URI_B))))
            val after = store.read()
            assertTrue("中断后仍必须能读出旧账本，实际 $after", after is LedgerRead.Loaded)
            assertEquals(
                "读出来的必须还是中断前那一份",
                old.records, (after as LedgerRead.Loaded).records
            )
        } finally {
            failure.restore()
        }
    }

    /**
     * **[已删除的判据，保留记录]** 曾经写过一条「写入期间目标不会被截断成 0 字节」的
     * 概率性探针（后台线程反复采样目标长度、跑 20 轮）。
     *
     * **为什么删掉**：它在宿主 Windows 上**不稳定**。第一版让读线程读内容，实测
     * `ATOMIC_MOVE` 直接抛 `AccessDeniedException`（文件被打开时不能被替换）；
     * 改成只读 `File.length()` 后仍偶发同一个异常（在「三条验收命令」那次跑里红了，
     * 见 `T08.md` §6 的证伪 2 保留记录）。
     *
     * **判据本身的问题，不是实现的问题**：一条会因宿主文件句柄语义而随机变红的断言，
     * 无法用来主张任何事 —— 它红了不能说明实现坏了，绿了也不能说明实现对了。
     * 与其留着它制造噪声，不如删掉并**换用确定性判据**：
     *
     * - 「`save()` 真的委托给了 `AtomicFileWriter`」由
     *   [saveCreatesTheParentDirectoryWhenItIsMissing] 确定性守住 ——
     *   `AtomicFileWriter.write` 会 `mkdirs` 建父目录，`File.writeText` 不会；
     *   证伪 2（把 `save()` 打回 `writeText`）**确实让那条红了**。
     * - 「替换本身的原子性」由 T07 的 `AtomicFileWriterTest`（17 条）覆盖，
     *   那是在**它自己的实现**上直接测的，判据不受 T08 这层委托影响。
     */
    /** 同一份表写两次（幂等）：内容与条数都不变。 */
    @Test
    fun savingTheSameTableTwiceIsIdempotent() {
        val store = newStore()
        val records = mapOf(URI_A to record(URI_A))
        assertNull(store.save(records))
        val first = ledger.readText()
        assertNull(store.save(records))
        assertEquals("写同样的内容两次，文件应当逐字节相同（时间戳不进载荷）", first, ledger.readText())
    }

    // ------------------------------------------------------------------ 辅助

    /**
     * 让**下一次**写入失败的一次性夹具。
     *
     * 做法是把目标设成只读：宿主 NTFS 上 `ATOMIC_MOVE` 替换只读目标**实测**抛
     * `AccessDeniedException`（探针输出见 `T08.md` §2）。**只作用在这一个文件上**，
     * 且 [restore] 一定把权限恢复 —— 否则后续用例会被连带弄红。
     */
    private class ReadOnlyTarget private constructor(private val target: File) {
        fun restore() {
            target.setWritable(true)
        }

        companion object {
            fun install(target: File): ReadOnlyTarget {
                target.setWritable(false)
                return ReadOnlyTarget(target)
            }
        }
    }

    private fun record(uri: String) = LedgerRecord(
        modUri = uri,
        modName = uri,
        familyKey = "family-$uri",
        snapshotTargetHash = "hash-$uri",
        quality = "HD",
        usedAstc = false,
        installedAt = 1_700_000_000_000L
    )

    private companion object {
        const val URI_A = "uri-a"
        const val URI_B = "uri-b"
    }
}
