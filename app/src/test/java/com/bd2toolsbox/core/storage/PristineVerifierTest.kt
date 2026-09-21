package com.bd2toolsbox.core.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

/**
 * [PristineVerifier] 的 JVM 单元测试（T09）。
 *
 * ## 这些用例补的是哪一块空白
 *
 * 改动前，「这个 bundle 还是不是官方原版」的唯一判定在
 * `MainViewModel.refreshBundleCleanStates()` 里，与 `BundleCleanState` /
 * `ShizukuManager` / `Context` 缠在一起 —— **一条 JVM 用例都写不了**（实测：
 * `app/src/test` 与 `app/src/androidTest` 里 PRISTINE 相关测试**零命中**）。
 *
 * 抽成纯函数之后，判定的**每一格**都能用 `TemporaryFolder` 造出来的真文件驱动。
 * 本类**不用 mockk、不用 Robolectric**：`PristineVerifier` 只收 `File`
 * 与可注入的 `Digester`。
 *
 * ## 本类**覆盖不到**什么（说清在明处，不写笼统的「不可测」）
 *
 * `MainViewModel` 里**调用**这个判定器的三处（`refreshBundleCleanStates` 的
 * `classifyListing`、`tryUseBackup` / `tryUseLocalBundle` 的 `classify`）都在
 * ViewModel 里，构造它要 `Context`，本仓没有 Robolectric。那三处是**分支替换**，
 * 本轮只经过编译与人工核对。设备侧有现成范本（`ModSnapshotTest` 的
 * `ContextWrapper` + 反射注入 `appContext`，或 MuMu `127.0.0.1:16384`），
 * 但计划给 T09 的验收命令只有 JVM 单测，未做 —— 详见 `T09.md` §「未覆盖」。
 *
 * ## 断言纪律
 *
 * 每条断言的期望值都是**测试自己写出来的常量**（或夹具自身重算的值），
 * 不调用被测函数去算期望值。`makeDigester` 记下每次被问过的文件，用来证明
 * 「判定真的读了字节」与「读的是哪一个文件」—— 后者是抓循环自证的关键。
 */
class PristineVerifierTest {

    @get:Rule
    val temporary = TemporaryFolder()

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /**
     * 受控的假摘要器：按文件**内容**返回一个可预测的串，并记录每次调用。
     *
     * 为什么不用真 SHA-256 做全部用例：受控摘要器让「内容不同但摘要相同」
     * 这种真 SHA-256 下要撞库才能构造的反例变成一行 —— 那是验证判定链
     * 真的按摘要走（而不是偷偷比大小）的唯一办法。
     *
     * **[calls] 是抓循环自证的探针**：若哪天有人把 `trusted.sha256` 换成
     * 「待验证文件自己的摘要」，那么被测代码会去读那个文件、[calls] 里就会出现
     * 它，而断言能当场指出「基准摘要不该由被验证对象产生」。
     */
    private class RecordingDigester(private val salt: String = "") : Digester {
        val calls = mutableListOf<String>()
        override fun digest(file: File): String {
            calls += file.absolutePath
            // 内容 → 稳定假摘要（长度 64 与 sha256 同形，便于断言）
            val h = "$salt:" + file.readBytes().joinToString(",")
            return h.padEnd(64, '0').take(64)
        }
    }

    /** 造一个 `<根>/<bundle>/<hashDir>/__data` 形态的文件，返回该文件。 */
    private fun makeBundleFile(
        root: File,
        bundleName: String,
        hashDir: String,
        bytes: ByteArray,
        fileName: String = "__data",
    ): File {
        val dir = File(root, "$bundleName/$hashDir")
        dir.mkdirs()
        return File(dir, fileName).apply { writeBytes(bytes) }
    }

    // ==================================================================
    // 1. 计划验收第 1 条：同名同尺寸、内容不同 → 只有匹配可信基准者 PRISTINE
    // ==================================================================

    /**
     * **本任务的头号反例**，用的是**真实 catalog 里的数据**。
     *
     * 下面这两个 bundle 名与它们的官方尺寸取自 `local/refactor-inputs/legacy-device-0.2.16/`
     * 的两份真实 `bundle_meta`（HD 20260904214954 / SD 20260909203034）：
     * 它们是**同名、同尺寸（2966 B）、不同内容哈希**的真实一对 —— 全表实测有 357 对。
     *
     * 这里把「内容不同」实体化成两份等长但内容不同的文件，验证：
     * **尺寸一样不构成原版证明**，只有摘要对得上 trusted 的那份才 PRISTINE。
     *
     * 这正是旧判据 `actual.second == backupRepository.backupSize(...)` 的破绽：
     * 它只看尺寸，会把下面那份冒牌货判成原版。
     */
    @Test
    fun sameNameSameSizeDifferentContent_onlyTheOneMatchingBaselineIsPristine() {
        // 真实数据（HD/SD 各一份 bundle_meta 实测得出）
        val bundleName = "061b13dbdd0a0d707bd13766f4d85043"
        val hashDir = "a39904f0d2a0025aa2cd58ea7d3bbcb9"     // HD 侧官方 m_Hash
        val officialSize = 2966L

        val root = temporary.newFolder("game")
        // 冒牌货：尺寸分毫不差，内容不同 —— 按旧判据会被判 PRISTINE
        val impostor = makeBundleFile(
            root, bundleName, hashDir,
            ByteArray(officialSize.toInt()) { 0x41 },
        )
        // 真原版：同尺寸、内容不同
        val genuineBytes = ByteArray(officialSize.toInt()) { 0x42 }
        val genuine = makeBundleFile(root, "genuine/$bundleName", hashDir, genuineBytes)

        val digester = RecordingDigester()
        val verifier = PristineVerifier(digester)
        val baseline = TrustedBaseline(
            sha256 = digester.digest(genuine),      // 基准摘要来自**别处**的一份字节
            size = officialSize,
            source = BaselineSource.CATALOG_DOWNLOAD,
            expectedHashDir = hashDir,
        )

        assertEquals("尺寸与目录名都对得上的一份应当 PRISTINE",
            Verdict.PRISTINE, verifier.classify(genuine, baseline))
        assertEquals("同尺寸但内容不同的一份必须 MODIFIED —— 尺寸不是原版证明",
            Verdict.MODIFIED, verifier.classify(impostor, baseline))

        // 这一条守住「不得用尺寸冒充内容校验」
        assertEquals(
            "两份文件尺寸必须真的相等，否则这条用例证明不了任何事",
            impostor.length(), genuine.length(),
        )
    }

    /**
     * 反向再钉一次：**只有 trusted 那份**过。若把基准换成冒牌货的摘要，
     * 判定必须**翻转** —— 证明结果由摘要决定，不是由某个恒定的旁路决定。
     */
    @Test
    fun flippingTheBaselineFlipsTheVerdict() {
        val root = temporary.newFolder("game")
        val a = makeBundleFile(root, "b1", "h1", ByteArray(2966) { 0x41 })
        val b = makeBundleFile(root, "b2", "h1", ByteArray(2966) { 0x42 })

        val digester = RecordingDigester()
        val verifier = PristineVerifier(digester)
        val baselineA = TrustedBaseline(
            sha256 = digester.digest(a), size = 2966, source = BaselineSource.CATALOG_DOWNLOAD,
            expectedHashDir = "h1",
        )
        val baselineB = baselineA.copy(sha256 = digester.digest(b))

        assertEquals(Verdict.PRISTINE, verifier.classify(a, baselineA))
        assertEquals(Verdict.MODIFIED, verifier.classify(b, baselineA))
        assertEquals(Verdict.MODIFIED, verifier.classify(a, baselineB))
        assertEquals(Verdict.PRISTINE, verifier.classify(b, baselineB))
    }

    /**
     * 「内容不同但摘要相同」：只有匹配**摘要**者 PRISTINE。
     *
     * 受控摘要器把两个不同文件的摘要设成同一个值，验证判定走的是摘要、
     * 而不是任何长度/名字旁路。同时断言**判定确实读了两个文件的字节**
     * （[RecordingDigester.calls]），排除「根本没算摘要、碰巧结果对」。
     */
    @Test
    fun sameSizeSameDigestSoBothAreAccepted_provingVerdictIsDrivenByTheDigest() {
        val root = temporary.newFolder("game")
        val a = makeBundleFile(root, "b1", "h1", ByteArray(2966) { 0x41 })
        val b = makeBundleFile(root, "b2", "h1", ByteArray(2966) { 0x42 })

        // 一个恒定摘要器：任何文件都返回同一串 —— 模拟「摘要碰撞」
        val constant = "c".repeat(64)
        val digester = RecordingDigester()
        val verifier = PristineVerifier { constant }

        val baseline = TrustedBaseline(
            sha256 = constant, size = 2966, source = BaselineSource.CATALOG_DOWNLOAD,
            expectedHashDir = "h1",
        )
        assertEquals(Verdict.PRISTINE, verifier.classify(a, baseline))
        assertEquals(Verdict.PRISTINE, verifier.classify(b, baseline))
        // 摘要**不**相同的那一份必须落 MODIFIED（同一份文件，摘要器返回别的值）
        val mismatch = PristineVerifier { "d".repeat(64) }
        assertEquals(Verdict.MODIFIED, mismatch.classify(a, baseline))
        // 用 RecordingDigester 只是为了让夹具造得真实；这里显式断言它没被这条用例用到
        assertTrue(digester.calls.isEmpty())
    }

    // ==================================================================
    // 2. 计划验收：备份存在但没有来源元数据 → UNKNOWN（不是 PRISTINE）
    // ==================================================================

    /**
     * **必测反例 2**：文件与一份备份**逐字节相同**，但拿不到可信基准 → **UNKNOWN**。
     *
     * 这是旧备份的真实处境：T09 之前存下来的 `__data.game` 没有 sidecar，
     * 来源无从查证。旧代码会因为它「大小相等」判 PRISTINE —— 那正是循环自证。
     *
     * **二轮整改后这条的形态**：首版 `classify` 还收一个 `backup` 参数，这里传的就是它。
     * 那个参数已被删除（它返回值被丢弃、生产零调用者、删掉零红，详见 `PristineVerifier.classify`
     * 的 KDoc），所以本用例改成**显式断言「备份就在旁边、与待判文件逐字节相同，结论仍是
     * UNKNOWN」** —— 用文件系统的真实状态表达同一个反例，而不是靠一个不参与判定的参数。
     * 备份本身**不**参与判定这件事，现在由「它压根不在签名里」保证，比首版更硬。
     */
    @Test
    fun backupWithoutProvenanceIsUnknownNotPristine() {
        val root = temporary.newFolder("root")
        val payload = ByteArray(4096) { 0x7A }
        val onDisk = makeBundleFile(root, "game/b", "h1", payload)
        // 备份与它逐字节相同 —— 诱惑最大的那种情形
        val backup = makeBundleFile(root, "backup/b", "h1", payload, fileName = "__data.game")

        val verifier = PristineVerifier(RecordingDigester())
        val verdict = verifier.classify(onDisk, trusted = null)

        assertEquals(
            "没有外部锚定的基准时，哪怕与备份逐字节相同也必须 UNKNOWN",
            Verdict.UNKNOWN, verdict,
        )
        assertNotEquals(Verdict.PRISTINE, verdict)
        // 前提：那份「诱惑」真的存在且真的同字节，否则这条用例证明不了任何事
        assertTrue("前提：备份确实在", backup.isFile)
        assertEquals("前提：备份与待判文件真的同尺寸", onDisk.length(), backup.length())
        assertArrayEquals(
            "前提：备份与待判文件真的逐字节相同",
            onDisk.readBytes(), backup.readBytes(),
        )
    }

    /** 对照：**什么候选都没有**时同样是 UNKNOWN —— 两条路径结论一致但都被覆盖。 */
    @Test
    fun noBaselineIsUnknown() {
        val root = temporary.newFolder("root")
        val onDisk = makeBundleFile(root, "game/b", "h1", ByteArray(128) { 1 })
        assertEquals(
            Verdict.UNKNOWN,
            PristineVerifier(RecordingDigester()).classify(onDisk, trusted = null),
        )
    }

    /**
     * 备份**存在且不可信**时，也绝不能把判定抬成 PRISTINE —— 即使用一个
     * 「随便什么文件都判相同」的摘要器也一样，因为 trusted 为 null 时
     * **根本不该走到内容比对那一步**（`classify` 现在连备份参数都不收）。
     */
    @Test
    fun aPermissiveDigesterStillCannotPromoteABackupToPristine() {
        val root = temporary.newFolder("root")
        val onDisk = makeBundleFile(root, "game/b", "h1", ByteArray(64) { 1 })
        makeBundleFile(root, "backup/b", "h1", ByteArray(64) { 2 }, "__data.game")
        // 一个「永远说相同」的摘要器：若判定被写成「摘要相等即 PRISTINE」，
        // 又没有 trusted，这条就会漏成 PRISTINE
        val alwaysSame = PristineVerifier { "same".padEnd(64, '0') }
        assertEquals(Verdict.UNKNOWN, alwaysSame.classify(onDisk, trusted = null))
    }

    // ==================================================================
    // 3. 计划验收：被修改过再存成的备份 → 不得作为干净基底
    // ==================================================================

    /**
     * **必测反例 3**：一份备份与它**记录在案**的摘要不符 → 判 MODIFIED，
     * 调用方（`tryUseBackup`）据此弃用它。
     *
     * 模拟的是：备份存下来之后被外部改写（或当初就存的是被改过的内容）
     * —— sidecar 里那串摘要才是它**当初**的样子，对不上就说明它不再是那份字节。
     */
    @Test
    fun aBackupWhoseBytesNoLongerMatchItsRecordedDigestIsModified() {
        val root = temporary.newFolder("root")
        val originalBytes = ByteArray(2048) { 0x11 }
        val backup = makeBundleFile(root, "backup/b", "h1", originalBytes, "__data.game")

        val digester = RecordingDigester()
        val verifier = PristineVerifier(digester)
        // sidecar 记的是**原始**字节的摘要（模拟采集当时算的）
        val recorded = digester.digest(backup)

        // 备份被改过（模拟「被修改过再存成的备份」）
        backup.writeBytes(ByteArray(2048) { 0x22 })

        val baseline = TrustedBaseline(
            sha256 = recorded, size = 2048,
            source = BaselineSource.RESTORE_PRODUCT, expectedHashDir = "h1",
        )
        assertEquals(
            "与记录的摘要不符的备份不得再当干净基底",
            Verdict.MODIFIED, verifier.classifyBackup(backup, baseline),
        )
    }

    /** 没被动过的备份：同一套基准下必须是 PRISTINE —— 否则上面那条只是恒假。 */
    @Test
    fun anUntouchedBackupMatchingItsRecordedDigestIsPristine() {
        val root = temporary.newFolder("root")
        val backup = makeBundleFile(root, "backup/b", "h1", ByteArray(2048) { 0x11 }, "__data.game")
        val digester = RecordingDigester()
        val baseline = TrustedBaseline(
            sha256 = digester.digest(backup), size = 2048,
            source = BaselineSource.RESTORE_PRODUCT, expectedHashDir = "h1",
        )
        assertEquals(Verdict.PRISTINE, PristineVerifier(digester).classifyBackup(backup, baseline))
    }

    // ==================================================================
    // 4. 版本判定与 ABSENT
    // ==================================================================

    /** 目录名（catalog 的内容哈希）对不上 → VERSION_MISMATCH，且**优先于**内容判定。 */
    @Test
    fun hashDirMismatchIsVersionMismatchEvenIfContentMatches() {
        val root = temporary.newFolder("root")
        val bytes = ByteArray(512) { 9 }
        val onDisk = makeBundleFile(root, "b", "oldhash", bytes)
        val digester = RecordingDigester()
        val baseline = TrustedBaseline(
            sha256 = digester.digest(onDisk), size = 512,
            source = BaselineSource.CATALOG_DOWNLOAD, expectedHashDir = "newhash",
        )
        val verifier = PristineVerifier(digester)
        assertEquals(Verdict.VERSION_MISMATCH, verifier.classify(onDisk, baseline))
        // 版本判定先于内容判定：不该为了比内容去读那份文件
        // （digester 造 baseline 时读过一次，之后不应再被调用）
        val callsAfterBaseline = digester.calls.size
        verifier.classify(onDisk, baseline)
        assertEquals("版本不符时不应再读内容", callsAfterBaseline, digester.calls.size)
    }

    /** 不指定 expectedHashDir 时跳过版本判定 —— 只按内容判。 */
    @Test
    fun absentExpectedHashDirSkipsVersionCheck() {
        val root = temporary.newFolder("root")
        val onDisk = makeBundleFile(root, "b", "whatever", ByteArray(64) { 3 })
        val digester = RecordingDigester()
        val baseline = TrustedBaseline(
            sha256 = digester.digest(onDisk), size = 64,
            source = BaselineSource.CATALOG_DOWNLOAD, expectedHashDir = null,
        )
        assertEquals(Verdict.PRISTINE, PristineVerifier(digester).classify(onDisk, baseline))
    }

    @Test
    fun missingFileIsAbsent() {
        val root = temporary.newFolder("root")
        val missing = File(root, "nope/__data")
        val baseline = TrustedBaseline("x".repeat(64), 1, BaselineSource.CATALOG_DOWNLOAD)
        val verifier = PristineVerifier(RecordingDigester())
        assertEquals(Verdict.ABSENT, verifier.classify(missing, baseline))
        assertEquals(Verdict.ABSENT, verifier.classify(null, baseline))
        assertEquals(Verdict.ABSENT, verifier.classifyBackup(missing, baseline))
    }

    /** 目录不是文件 → ABSENT（`isFile` 为假），不是 UNKNOWN。 */
    @Test
    fun directoryIsAbsentNotUnknown() {
        val root = temporary.newFolder("root")
        val dir = File(root, "b/h1/__data").apply { mkdirs() }
        val baseline = TrustedBaseline("x".repeat(64), 1, BaselineSource.CATALOG_DOWNLOAD)
        assertEquals(Verdict.ABSENT, PristineVerifier(RecordingDigester()).classify(dir, baseline))
    }

    /** 读不出内容（摘要器抛异常）→ UNKNOWN，**不**谎报成 MODIFIED/ABSENT。 */
    @Test
    fun digestFailureIsUnknown() {
        val root = temporary.newFolder("root")
        val onDisk = makeBundleFile(root, "b", "h1", ByteArray(64) { 1 })
        val boom = PristineVerifier { throw java.io.IOException("模拟读失败") }
        val baseline = TrustedBaseline("x".repeat(64), 64, BaselineSource.CATALOG_DOWNLOAD, "h1")
        assertEquals(Verdict.UNKNOWN, boom.classify(onDisk, baseline))
    }

    // ==================================================================
    // 5. 清单级基准（catalog 只有 name+size，没有摘要）
    // ==================================================================

    /**
     * `sha256 == null` 的基准只能做到 name+size 级 —— 这正是 catalog 能提供的最强证据
     * （实测 `m_Hash` 不是 `__data` 字节摘要，见 `T09.md` §「m_Hash 语义」）。
     *
     * 这条用例把「catalog 锚点只到这里」这件事**可执行地**钉住，免得将来有人
     * 以为 catalog 能验内容。
     */
    @Test
    fun digestlessBaselineFallsBackToNameAndSize() {
        val root = temporary.newFolder("root")
        val onDisk = makeBundleFile(root, "b", "h1", ByteArray(2966) { 0x41 })
        val verifier = PristineVerifier(RecordingDigester())
        val catalogOnly = TrustedBaseline(
            sha256 = null, size = 2966,
            source = BaselineSource.CATALOG_MANIFEST, expectedHashDir = "h1",
        )
        assertEquals(Verdict.PRISTINE, verifier.classify(onDisk, catalogOnly))
        // 尺寸不同 → MODIFIED
        val bigger = makeBundleFile(root, "b2", "h1", ByteArray(2967) { 0x41 })
        assertEquals(Verdict.MODIFIED, verifier.classify(bigger, catalogOnly))
        assertFalse(catalogOnly.hasContentDigest)
    }

    // ==================================================================
    // 6. classifyListing：扫描层（只有目录名 + 尺寸）
    // ==================================================================

    /** catalog 锚点命中 → PRISTINE；尺寸不符 → MODIFIED。 */
    @Test
    fun listingMatchesCatalogAnchor() {
        val verifier = PristineVerifier(RecordingDigester())
        val catalog = CatalogAnchor(expectedHashDir = "h1", size = 1000)
        assertEquals(Verdict.PRISTINE, verifier.classifyListing("h1", 1000, catalog, null))
        assertEquals(Verdict.MODIFIED, verifier.classifyListing("h1", 1001, catalog, null))
        assertEquals(Verdict.VERSION_MISMATCH, verifier.classifyListing("h2", 1000, catalog, null))
    }

    /**
     * **卸载场景的核心用例**：游戏目录里那份与 catalog 尺寸**不等**
     * （`restore_bundle` 的 lz4 重打包产物比官方差千分之几），但它是从
     * 已锚定的 CDN 字节确定性派生的可信备份 → 必须 PRISTINE。
     *
     * 这正是旧那条「备份大小即原版」判据要解决的场景。替代路径是：
     * 备份带 sidecar（source = RESTORE_PRODUCT，采集时输入被 catalog 锚定），
     * 扫描层认它 —— 于是卸载后状态正确回到 PRISTINE，**不**需要旧判据。
     *
     * 实测数字（T09.md §「删掉大小判据」）：1168 → 1162，差 6 字节。
     */
    @Test
    fun listingAcceptsByteAnchoredRestoreProductEvenThoughSizeDiffersFromCatalog() {
        val verifier = PristineVerifier(RecordingDigester())
        val catalog = CatalogAnchor(expectedHashDir = "h1", size = 1168)     // 官方 1168
        val restoreBackup = TrustedBaseline(
            sha256 = "a".repeat(64), size = 1162,                            // 重打包产物 1162
            source = BaselineSource.RESTORE_PRODUCT, expectedHashDir = "h1",
        )
        assertEquals(
            "目录名对、尺寸是重打包产物 —— 有可信备份锚点时必须是 PRISTINE",
            Verdict.PRISTINE,
            verifier.classifyListing("h1", 1162, catalog, restoreBackup),
        )
    }

    /**
     * 同场景但**没有**可信备份（旧备份 / 无 sidecar）→ MODIFIED，**不是** PRISTINE。
     *
     * 这是「旧备份不再被信任」的行为变化：代价是重新下载一次，换来的是
     * 「来源不明的字节不被当原版」。
     */
    @Test
    fun listingWithoutTrustedBackupFallsBackToModifiedForRepackedBytes() {
        val verifier = PristineVerifier(RecordingDigester())
        val catalog = CatalogAnchor(expectedHashDir = "h1", size = 1168)
        assertEquals(
            Verdict.MODIFIED,
            verifier.classifyListing("h1", 1162, catalog, backup = null),
        )
    }

    /** 两个锚点都没有（读不到 catalog、也没可信备份）→ UNKNOWN，不猜。 */
    @Test
    fun listingWithNoAnchorsAtAllIsUnknown() {
        val verifier = PristineVerifier(RecordingDigester())
        assertEquals(Verdict.UNKNOWN, verifier.classifyListing("h1", 500, null, null))
    }

    /** 尺寸读不到（Shizuku 拿不到 stat，负数）→ UNKNOWN。 */
    @Test
    fun listingWithUnreadableSizeIsUnknown() {
        val verifier = PristineVerifier(RecordingDigester())
        val catalog = CatalogAnchor(expectedHashDir = "h1", size = 1000)
        assertEquals(Verdict.UNKNOWN, verifier.classifyListing("h1", -1, catalog, null))
        assertEquals(Verdict.UNKNOWN, verifier.classifyListing(null, 1000, catalog, null))
    }

    /** 备份基准的 hashDir 与实际的**不同** → 不得当成 PRISTINE。 */
    @Test
    fun listingIgnoresBackupFromADifferentHashDir() {
        val verifier = PristineVerifier(RecordingDigester())
        val stale = TrustedBaseline(
            sha256 = "a".repeat(64), size = 1162,
            source = BaselineSource.RESTORE_PRODUCT, expectedHashDir = "other",
        )
        // catalog 缺失（null），只剩这个不对版的备份
        assertEquals(Verdict.UNKNOWN, verifier.classifyListing("h1", 1162, null, stale))
    }

    // ==================================================================
    // 7. 摘要器注入 / 流式 / 无循环自证
    // ==================================================================

    /**
     * **判定的摘要只能来自可信基准，不能来自被验证文件自己。**
     *
     * 用记录型摘要器：造基准时读的是**另一份**文件；随后验证时，被测文件被读、
     * 但基准那串值**没有**被重新计算。若哪天有人改成「用被验证文件的摘要当基准」，
     * 这条会直接指出来。
     */
    @Test
    fun baselineDigestComesFromElsewhereNotFromTheFileUnderTest() {
        val root = temporary.newFolder("root")
        val trustedSource = makeBundleFile(root, "trusted/b", "h1", ByteArray(256) { 0x31 })
        val underTest = makeBundleFile(root, "game/b", "h1", ByteArray(256) { 0x32 })

        val digester = RecordingDigester()
        val verifier = PristineVerifier(digester)
        val baseline = TrustedBaseline(
            sha256 = digester.digest(trustedSource), size = 256,
            source = BaselineSource.CATALOG_DOWNLOAD, expectedHashDir = "h1",
        )
        digester.calls.clear()      // 只观察判定阶段读了谁

        assertEquals(Verdict.MODIFIED, verifier.classify(underTest, baseline))
        assertEquals(
            "判定阶段只该读被验证文件一次",
            listOf(underTest.absolutePath), digester.calls,
        )
    }

    /**
     * `StreamingDigest.sha256()` 对**同一内容**稳定、对**不同内容**不同，
     * 且与 `MessageDigest` 直接算的结果一致（证明它不是随手拼的串）。
     *
     * 大文件不读进内存这一条由 `CHUNK_BYTES` 的存在与实现保证，
     * 这里用一个**跨多个分块**的文件证明分块循环确实覆盖了全部字节。
     */
    @Test
    fun streamingSha256CoversAllChunksAndMatchesMessageDigest() {
        val root = temporary.newFolder("root")
        // 3.5 个分块，确保循环处理了「整块 + 尾块」
        val size = StreamingDigest.CHUNK_BYTES * 3 + 12345
        val bytes = ByteArray(size) { (it % 251).toByte() }
        val f = File(root, "big.__data").apply { writeBytes(bytes) }

        val streaming = StreamingDigest.sha256().digest(f)
        val expected = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        assertEquals(expected, streaming)
        assertEquals(64, streaming.length)

        // 改一个字节（在最后一个分块里）→ 摘要必须变
        val tampered = bytes.copyOf().also { it[size - 1] = (it[size - 1] + 1).toByte() }
        val g = File(root, "big2.__data").apply { writeBytes(tampered) }
        assertNotEquals(streaming, StreamingDigest.sha256().digest(g))
    }

    /** 空文件也能算（不能因为 read 返回 0 就崩）。 */
    @Test
    fun streamingSha256HandlesEmptyFile() {
        val root = temporary.newFolder("root")
        val f = File(root, "empty").apply { writeBytes(ByteArray(0)) }
        val d = StreamingDigest.sha256().digest(f)
        // 空输入的 SHA-256 是个公开常量
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", d)
    }

    @Test
    fun streamingDigestRequestsOnlyChunkSizedReads() {
        val size = StreamingDigest.CHUNK_BYTES + 20
        val bytes = ByteArray(size) { (it % 251).toByte() }
        val requested = mutableListOf<Int>()
        val input = object : ByteArrayInputStream(bytes) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                requested += len
                return super.read(b, off, len)
            }
        }
        val digest = StreamingDigest.digestChunks(input)
        assertTrue(requested.isNotEmpty())
        assertTrue(requested.all { it == StreamingDigest.CHUNK_BYTES })
        val expected = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        assertArrayEquals(expected, digest)
    }

    // ==================================================================
    // 8. hashDirOf
    // ==================================================================

    @Test
    fun hashDirOfReadsTheMiddleSegment() {
        val root = temporary.newFolder("root")
        val f = makeBundleFile(root, "bundleName", "abc123", ByteArray(4) { 1 })
        assertEquals("abc123", PristineVerifier.hashDirOf(f))
    }

    // ==================================================================
    // 9. BaselineSource 与 BackupProvenance 的边界
    // ==================================================================

    @Test
    fun baselineSourceWireNamesRoundTrip() {
        BaselineSource.values().forEach { s ->
            assertEquals(s, BaselineSource.fromWire(s.wireName))
        }
        assertNull("认不出的来源必须返回 null，不许有默认值",
            BaselineSource.fromWire("no-such-source"))
        assertNull(BaselineSource.fromWire(null))
    }

    /**
     * `BYTE_ANCHORED` 的成员必须恰好是「有外部字节锚点」的那两个。
     *
     * 守护点：`GAME_DIR` 若被误加进来，扫描层就会把「采集时 name+size 对得上」
     * 当成内容证明 —— 而 catalog 内实测有 **21 组**同尺寸不同 `m_Hash` 的 bundle
     * （单份 `catalog-alpha`，涉及 95 个 bundle；HD 单份 23 组、SD 单份 24 组），
     * 尺寸根本证明不了内容。
     *
     * T09 真缺陷 3 的整流守卫在 `BundleBackupRepositoryTest` —— 那里能同时看到
     * `trustedBaseline` 与 `byteAnchoredBaseline` 对**同一份 GAME_DIR 备份**的分歧，
     * 也就是 `tryUseBackup` 曾经踩空的那一格。
     */
    @Test
    fun byteAnchoredSourcesExcludeManifestAndGameDir() {
        assertEquals(
            setOf(BaselineSource.CATALOG_DOWNLOAD, BaselineSource.RESTORE_PRODUCT),
            BaselineSource.BYTE_ANCHORED,
        )
        assertFalse(BaselineSource.GAME_DIR in BaselineSource.BYTE_ANCHORED)
        assertFalse(BaselineSource.CATALOG_MANIFEST in BaselineSource.BYTE_ANCHORED)
    }

    /** `CatalogAnchor` 转成清单级基准时**不能**凭空长出摘要。 */
    @Test
    fun catalogAnchorConvertsToDigestlessBaseline() {
        val b = CatalogAnchor(expectedHashDir = "h", size = 42).asTrustedBaseline()
        assertNull(b.sha256)
        assertFalse(b.hasContentDigest)
        assertEquals(BaselineSource.CATALOG_MANIFEST, b.source)
        assertEquals(42L, b.size)
        assertEquals("h", b.expectedHashDir)
    }

    /** `TrustedBaseline.hasContentDigest` 对空串也算「没有」。 */
    @Test
    fun blankDigestIsNotAContentDigest() {
        assertFalse(TrustedBaseline("", 1, BaselineSource.CATALOG_DOWNLOAD).hasContentDigest)
        assertFalse(TrustedBaseline(null, 1, BaselineSource.CATALOG_DOWNLOAD).hasContentDigest)
        assertTrue(TrustedBaseline("ab", 1, BaselineSource.CATALOG_DOWNLOAD).hasContentDigest)
    }
}
