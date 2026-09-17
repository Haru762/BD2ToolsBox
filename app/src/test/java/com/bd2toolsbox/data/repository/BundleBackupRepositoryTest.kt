package com.bd2toolsbox.data.repository

import android.content.Context
import com.bd2toolsbox.core.storage.BackupProvenance
import com.bd2toolsbox.core.storage.BaselineSource
import com.bd2toolsbox.core.storage.Digester
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
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

/**
 * [BundleBackupRepository] 的 JVM 单元测试（T09）。
 *
 * ## 为什么需要这一份（**范围新增，计划未列出**）
 *
 * 见 `docs/refactor/evidence/T09.md` §3 的偏差登记。理由：
 *
 * 必测反例 2 是「**有备份但无来源元数据 → UNKNOWN**」。它的后半截（「没有可信基准
 * 就 UNKNOWN」）由 `PristineVerifierTest` 覆盖；但**前半截** ——「旧备份凭什么拿不到
 * 基准」—— 是 `BundleBackupRepository.trustedBaseline()` 的规则，判定器看不到。
 * 不测它，就等于把「旧备份不再被信任」这条**行为变化**留给人工阅读。
 *
 * ## 接缝（brief §4.2）
 *
 * `BundleBackupRepository` 唯一的外部依赖是 `context.getExternalFilesDir(null)`，
 * 其余全是 `java.io.File` + Gson + T07 的 `AtomicFileWriter`。所以这里
 * **只桩这一个边界**（范本：`PrepackCacheIntegrationTest.kt:53-55`），
 * 之后走**真实文件系统**：真写 sidecar、真读 sidecar、真算摘要。
 * **不用** Robolectric —— 本仓没有，且不需要。
 */
class BundleBackupRepositoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var external: File
    private lateinit var repo: BundleBackupRepository

    @Before
    fun setUp() {
        // `android.util.Log` 的静态方法在 JVM 单测里会抛
        // `RuntimeException: Method w in android.util.Log not mocked`（本仓没有
        // `unitTests.isReturnDefaultValues`）。范本：`InstalledModRepositoryLedgerTest`。
        // 只桩 Log 这一个 Android 边界，其余走真实文件系统。
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0

        external = temp.newFolder("external")
        val context = mockk<Context>()
        every { context.getExternalFilesDir(isNull()) } returns external
        repo = BundleBackupRepository(context, DeterministicDigester())
    }

    @After
    fun tearDown() = unmockkAll()

    /**
     * 确定性的假摘要器：内容 → 稳定串。真 SHA-256 也行，但假摘要器让
     * 「写入时记的摘要」与「读出来比对的摘要」这两件事在测试里一眼可算。
     */
    private class DeterministicDigester : Digester {
        override fun digest(file: File): String {
            val h = file.readBytes().joinToString(",")
            return "d$h".padEnd(64, '0').take(64)
        }
    }

    /** 造一个源文件（待备份的字节）。 */
    private fun srcFile(bytes: ByteArray): File =
        temp.newFolder("src-${System.nanoTime()}").let { dir ->
            File(dir, "__data").apply { writeBytes(bytes) }
        }

    /** 便捷：定长、单值填充的字节数组。 */
    private fun bytes(n: Int, value: Int) = ByteArray(n) { value.toByte() }

    private val provenance = BackupProvenance(
        source = BaselineSource.CATALOG_DOWNLOAD,
        catalogSize = 2048,
        catalogVersion = "20260904214954",
        quality = "HD",
    )

    // ==================================================================
    // 1. saveBackup 写出 sidecar，且 sidecar 内容可从磁盘复算
    // ==================================================================

    @Test
    fun saveBackupWritesSidecarNextToTheBackup() {
        val src = srcFile(bytes(2048, 0x11))
        assertTrue(repo.saveBackup("b1", "h1", src, provenance))

        val backup = repo.backupFile("b1", "h1")
        val meta = repo.metaFile("b1", "h1")
        assertTrue("备份本体要落盘", backup.isFile)
        assertTrue("sidecar 要落在**同一目录**", meta.isFile)
        assertEquals(
            "sidecar 必须与备份同目录（deleteRecursively 才会连它一起清）",
            backup.parentFile, meta.parentFile,
        )

        // 独立复算：不调用仓储的读方法，直接读磁盘 JSON 对字段
        val text = meta.readText()
        assertTrue("必须记 sha256", text.contains("\"sha256\""))
        assertTrue("必须记 source", text.contains("\"catalog-download\""))
        assertTrue("必须记 catalogVerified", text.contains("\"catalogVerified\":true"))
        assertTrue("必须记 size", text.contains("\"size\":2048"))
        assertTrue("必须记 capturedAt", text.contains("\"capturedAt\""))
        assertTrue("必须记 schemaVersion", text.contains("\"schemaVersion\":1"))
        assertTrue("catalogVersion 要带上", text.contains("20260904214954"))
    }

    /**
     * **磁盘键集合的机器检查** —— T09 真缺陷 1（R8 改名）的守卫。
     *
     * ## 守的是什么
     *
     * 首版 sidecar 用 `gson.toJson(BackupMeta)` 写、`obj.get("schemaVersion")` 读，
     * 并声称「`-keep class com.bd2toolsbox.core.storage.**` 保护了字段名」。
     * 那条规则**护不到** `data.repository` 包里的 `BackupMeta`，R8 把字段名改成了
     * `a`/`b`/`c`，于是**写出来的键名与读侧字面量对不上，release 下整条链静默失效**。
     *
     * 现在两侧都用显式键。这条用例把「写出的键集合**恰好**等于约定的那组」变成
     * **可执行的合约**：谁再引入一个会被 R8 改名的字段名、或加字段时忘了同步
     * `META_KEYS`，这里当场红 —— 不再靠 KDoc 声明。
     *
     * 它**不**依赖反射，也就**不会**因为 R8 怎么改名而改变结论：断言的是磁盘上那串
     * JSON 的键，而不是 `BackupMeta` 的字段。
     */
    @Test
    fun theSidecarOnDiskKeySetIsExactlyTheAgreedContract() {
        val src = srcFile(bytes(2048, 0x12))
        repo.saveBackup("b1k", "h1k", src, provenance)

        val text = repo.metaFile("b1k", "h1k").readText(Charsets.UTF_8)
        val obj = com.google.gson.JsonParser.parseString(text).asJsonObject
        val keys = obj.keySet()

        assertEquals(
            "sidecar 的磁盘键集合必须恰好是约定的那组（多/少都说明格式变了，" +
                    "该回来看 META_SCHEMA_VERSION 要不要 bump）",
            BundleBackupRepository.META_KEYS, keys,
        )
        // 显式列出每个键的**字面量**：即使 META_KEYS 被整体改错，这里也钉得住。
        assertEquals(
            setOf(
                "schemaVersion", "sha256", "size", "source", "catalogVerified",
                "anchoredInputSize", "catalogVersion", "quality", "capturedAt",
            ),
            keys,
        )
        // 反向：任何**不是**约定键的名字都不许出现（首版的 a/b/c/d 会被这条抓住）
        assertEquals(
            "磁盘上不许出现约定之外的键名（例如 R8 改写后的字段名）",
            emptySet<String>(),
            keys - BundleBackupRepository.META_KEYS,
        )
    }

    /**
     * 键集合在**参数为 null 时也稳定** —— 读侧依赖「键一定在（值是 `null` 字面量）」。
     *
     * 若哪天有人把 null 字段改成 `omit`（Gson 默认就省略 null），键集合会随之缩小，
     * 而 `readMeta` 里 `?: return null` 的那些必需项就会静默失效。
     */
    @Test
    fun theSidecarKeepsEveryKeyEvenWhenOptionalValuesAreNull() {
        val src = srcFile(bytes(64, 0x13))
        repo.saveBackup(
            "b1n", "h1n", src,
            BackupProvenance(BaselineSource.GAME_DIR, catalogSize = 64),   // 三个可选项都是 null
        )
        val obj = com.google.gson.JsonParser
            .parseString(repo.metaFile("b1n", "h1n").readText(Charsets.UTF_8)).asJsonObject
        assertEquals(BundleBackupRepository.META_KEYS, obj.keySet())
        // `null` 字面量确实写出来了（不是被省略）
        assertTrue("anchoredInputSize 必须存在且为 null 字面量", obj.get("anchoredInputSize").isJsonNull)
        assertTrue("catalogVersion 必须存在且为 null 字面量", obj.get("catalogVersion").isJsonNull)
        assertTrue("quality 必须存在且为 null 字面量", obj.get("quality").isJsonNull)
    }

    /**
     * **被改名的键名读不回来** —— 真缺陷 1 的**直接证伪对象**。
     *
     * 模拟「写侧用了 R8 改后的字段名（`a`/`b`/`c`）」这个历史形态：把一个明明含
     * 全部**值**、但键名是短名的 JSON 塞进 sidecar，`trustedBaseline` 必须返回 null ——
     * 因为读侧只认约定的字面量键。这条与 [theSidecarOnDiskKeySetIsExactlyTheAgreedContract]
     * 一起构成「两侧都是显式键」的闭环证明：
     *
     * - 写侧键名错了 → 上一条红；
     * - 读侧接受了错键名 → 这一条红。
     */
    @Test
    fun aSidecarWrittenWithRenamedKeysYieldsNoBaseline() {
        val src = srcFile(bytes(2048, 0x14))
        repo.saveBackup("b1r", "h1r", src, provenance)
        // 用真 sidecar 的**值**，但换成 R8 改名后的短键名（首版 release 包写出的形状）
        val good = com.google.gson.JsonParser
            .parseString(repo.metaFile("b1r", "h1r").readText(Charsets.UTF_8)).asJsonObject
        val renamed = com.google.gson.JsonObject().apply {
            addProperty("a", good.get("sha256").asString)
            addProperty("b", good.get("size").asLong)
            addProperty("c", good.get("source").asString)
            addProperty("d", good.get("catalogVerified").asBoolean)
            addProperty("schemaVersion", 1)      // 首版唯一用字面量读的那个键
        }
        repo.metaFile("b1r", "h1r").writeText(renamed.toString(), Charsets.UTF_8)

        assertNull(
            "键名被改过的 sidecar 读不回来 —— 读侧只认约定字面量",
            repo.trustedBaseline("b1r", "h1r"),
        )
    }

    /** 写完立刻能作为可信基准读回来，且摘要与**源文件**一致。 */
    @Test
    fun trustedBaselineRoundTripsForAProperlyProvenancedBackup() {
        val src = srcFile(bytes(2048, 0x22))
        repo.saveBackup("b1", "h1", src, provenance)

        val baseline = repo.trustedBaseline("b1", "h1")
        assertNotNull(baseline)
        assertEquals(BaselineSource.CATALOG_DOWNLOAD, baseline!!.source)
        assertEquals(2048L, baseline.size)
        assertEquals("h1", baseline.expectedHashDir)
        assertEquals(
            "摘要应当是**源文件**算出来的那份",
            DeterministicDigester().digest(src), baseline.sha256,
        )
    }

    // ==================================================================
    // 2. **必测反例 2**：旧备份（无 sidecar）→ 拿不到基准（不是「大小即可信」）
    // ==================================================================

    /**
     * 模拟 T09 之前存下来的备份：磁盘上只有 `__data.game`，没有 sidecar。
     *
     * 必须 `trustedBaseline` 返回 null —— 于是判定落 UNKNOWN。
     * **这正是「旧备份不再被信任」这条行为变化的可执行形态。**
     */
    @Test
    fun legacyBackupWithoutSidecarHasNoTrustedBaseline() {
        // 直接手工造一个旧式备份（绕开 saveBackup，模拟历史遗留）
        val legacy = repo.backupFile("b2", "h2")
        legacy.parentFile!!.mkdirs()
        legacy.writeBytes(bytes(2048, 0x33))

        assertTrue("备份本体确实在，且非空", repo.hasBackup("b2", "h2"))
        assertEquals(2048L, repo.backupSize("b2", "h2"))
        assertNull(
            "旧备份没有 sidecar —— 必须拿不到可信基准，不得回退到大小判据",
            repo.trustedBaseline("b2", "h2"),
        )
        assertNull(repo.byteAnchoredBaseline("b2", "h2"))
    }

    /** sidecar 损坏 / schema 不认识 → 同样拿不到基准（读不出来等于没有）。 */
    @Test
    fun corruptSidecarYieldsNoBaseline() {
        val src = srcFile(bytes(2048, 0x44))
        repo.saveBackup("b3", "h3", src, provenance)

        repo.metaFile("b3", "h3").writeText("{ 这不是 JSON")
        assertNull(repo.trustedBaseline("b3", "h3"))

        // schema 版本不认识
        repo.metaFile("b3", "h3").writeText(
            "{\"schemaVersion\":999,\"sha256\":\"${"a".repeat(64)}\"," +
                    "\"size\":2048,\"source\":\"catalog-download\",\"catalogVerified\":true}"
        )
        assertNull(repo.trustedBaseline("b3", "h3"))
    }

    // ==================================================================
    // 3. 来源强度：byteAnchoredBaseline 只认 BYTE_ANCHORED
    // ==================================================================

    /**
     * `GAME_DIR` 的备份在 `trustedBaseline` 层面是「有基准的」（采集时确实过了
     * catalog 锚点），但**扫描层与 `tryUseBackup` 都不许认它** —— 它的锚点就是 name+size。
     *
     * ## 为什么这条是 T09 真缺陷 3 的守卫
     *
     * `MainViewModel.tryUseBackup` 的 KDoc 声称「备份要能当基底，来源必须属于
     * BYTE_ANCHORED」，首版代码却调的 `trustedBaseline`（不筛来源）。
     * 复核者的攻击因此红了（runId `20260918-031658`）：一个**同尺寸的 mod** 躺在
     * 游戏目录 → 扫描层按 `size == catalog.size` 判它 PRISTINE →
     * `backupPristineOriginal` 以 `GAME_DIR` 存成「可信」备份 → `tryUseBackup`
     * 拿它当干净基底复用。**这正是 T09 要消灭的形态。**
     *
     * 本条把两个方法在**同一份 GAME_DIR 备份**上的分歧钉死：`trustedBaseline` 有值、
     * `byteAnchoredBaseline` 为 null。`tryUseBackup` 与扫描层现在用的都是后者，
     * 所以这个分歧一旦被抹平（比如把 GAME_DIR 加进 BYTE_ANCHORED、或改回调用
     * `trustedBaseline`），语义就与声称不符。
     *
     * 阳性对照（`restoreProductWithInputAnchoredIsByteAnchoredEvenThoughSizeDiffers`）
     * 紧随其后：换成被字节锚定的来源，`byteAnchoredBaseline` 必须变成有值 ——
     * 否则上面那条的 null 可能只是「这个方法恒返回 null」。
     */
    @Test
    fun gameDirBackupIsTrustedButNotByteAnchored() {
        val src = srcFile(bytes(2048, 0x55))
        repo.saveBackup(
            "b4", "h4", src,
            BackupProvenance(BaselineSource.GAME_DIR, catalogSize = 2048),
        )
        assertNotNull("采集时过了 catalog 锚点，是可信的", repo.trustedBaseline("b4", "h4"))
        assertNull(
            "但扫描层与 tryUseBackup 不许认它（锚点只是 name+size）",
            repo.byteAnchoredBaseline("b4", "h4"),
        )
    }

    /** `RESTORE_PRODUCT`：产物尺寸与 catalog **不等**也必须可信（这是卸载场景）。 */
    @Test
    fun restoreProductWithInputAnchoredIsByteAnchoredEvenThoughSizeDiffers() {
        val src = srcFile(bytes(2048, 0x66))     // 产物 2048
        repo.saveBackup(
            "b5", "h5", src,
            BackupProvenance(
                source = BaselineSource.RESTORE_PRODUCT,
                catalogSize = 2054,             // 官方 2054（与产物不同）
                anchoredInputSize = 2054,       // 输入 = 官方 → 被锚定
            ),
        )
        val b = repo.byteAnchoredBaseline("b5", "h5")
        assertNotNull("输入被锚定的派生产物必须可用作字节级基准", b)
        assertEquals(BaselineSource.RESTORE_PRODUCT, b!!.source)
        assertEquals(
            "基准尺寸记的是产物自己的尺寸（2048），不是 catalog 的",
            2048L, b.size,
        )
    }

    // ==================================================================
    // 4. deriveCatalogVerified 的逐来源规则（可信链上唯一「凭空断言」处）
    // ==================================================================

    @Test
    fun deriveCatalogVerifiedRequiresAnActualAnchorPerSource() {
        // CDN 下载 / 游戏目录：比**产物**尺寸
        assertTrue(
            repo.deriveCatalogVerified(
                BackupProvenance(BaselineSource.CATALOG_DOWNLOAD, catalogSize = 100), 100,
            ),
        )
        assertFalse(
            "产物尺寸不等于 catalog 登记值 → 没有锚点",
            repo.deriveCatalogVerified(
                BackupProvenance(BaselineSource.CATALOG_DOWNLOAD, catalogSize = 100), 101,
            ),
        )
        assertTrue(
            repo.deriveCatalogVerified(
                BackupProvenance(BaselineSource.GAME_DIR, catalogSize = 100), 100,
            ),
        )
        assertFalse(
            "拿不到 catalog → 无锚点",
            repo.deriveCatalogVerified(
                BackupProvenance(BaselineSource.GAME_DIR, catalogSize = null), 100,
            ),
        )

        // 还原产物：比**输入**尺寸（产物尺寸天然对不上）
        assertTrue(
            repo.deriveCatalogVerified(
                BackupProvenance(
                    BaselineSource.RESTORE_PRODUCT, catalogSize = 2054, anchoredInputSize = 2054,
                ),
                2048,
            ),
        )
        assertFalse(
            "没记输入尺寸 → 无法回溯派生链 → 不可信",
            repo.deriveCatalogVerified(
                BackupProvenance(BaselineSource.RESTORE_PRODUCT, catalogSize = 2054), 2048,
            ),
        )
        assertFalse(
            "输入尺寸与 catalog 不符 → 输入根本没被锚定",
            repo.deriveCatalogVerified(
                BackupProvenance(
                    BaselineSource.RESTORE_PRODUCT, catalogSize = 2054, anchoredInputSize = 999,
                ),
                2048,
            ),
        )

        // 清单来源不是备份的来源
        assertFalse(
            repo.deriveCatalogVerified(
                BackupProvenance(BaselineSource.CATALOG_MANIFEST, catalogSize = 100), 100,
            ),
        )
    }

    // ==================================================================
    // 5. 已存在则不覆盖（也不补写 sidecar）
    // ==================================================================

    /**
     * 已有备份时 `saveBackup` 返回 true 但**不动**任何字节 —— 包括**不补写 sidecar**。
     *
     * 补写 sidecar 等于替一份来源不明的旧字节凭空声明来源，那正是要消灭的形态。
     */
    @Test
    fun existingBackupIsNotOverwrittenAndSidecarIsNotBackfilled() {
        val legacy = repo.backupFile("b6", "h6")
        legacy.parentFile!!.mkdirs()
        legacy.writeBytes(bytes(2048, 0x77))

        val src = srcFile(bytes(999, 0x01))
        assertTrue("已有备份 → 返回 true", repo.saveBackup("b6", "h6", src, provenance))

        assertEquals("旧备份的字节不许被改", 2048L, legacy.length())
        assertFalse("不许给旧备份补写 sidecar", repo.metaFile("b6", "h6").exists())
        assertNull(repo.trustedBaseline("b6", "h6"))
    }

    /** 摘要算不出来就整个失败 —— 不留下一份「没有基准」的备份污染后续判定。 */
    @Test
    fun digestFailureAbortsTheSaveEntirely() {
        val context = mockk<Context>()
        every { context.getExternalFilesDir(isNull()) } returns external
        val failing = BundleBackupRepository(context) { throw java.io.IOException("模拟读失败") }

        val src = srcFile(bytes(128, 0x01))
        assertFalse(failing.saveBackup("b7", "h7", src, provenance))
        assertFalse("失败时不许留下备份本体", failing.hasBackup("b7", "h7"))
        assertFalse(failing.metaFile("b7", "h7").exists())
    }

    // ==================================================================
    // 6. 删除要连 sidecar 一起清（不留孤儿元数据）
    // ==================================================================

    @Test
    fun deleteBackupRemovesSidecarToo() {
        val src = srcFile(bytes(2048, 0x88))
        repo.saveBackup("b8", "h8", src, provenance)
        assertTrue(repo.metaFile("b8", "h8").isFile)

        repo.deleteBackup("b8")
        assertFalse(repo.backupFile("b8", "h8").exists())
        assertFalse("孤儿 sidecar 会被后续判定误认", repo.metaFile("b8", "h8").exists())
    }

    @Test
    fun clearAllRemovesEverythingUnderRoot() {
        repo.saveBackup("b9", "h9", srcFile(bytes(16, 0x01)), provenance)
        repo.clearAll()
        assertNull(repo.trustedBaseline("b9", "h9"))
        assertEquals(0, repo.count())
    }
}
