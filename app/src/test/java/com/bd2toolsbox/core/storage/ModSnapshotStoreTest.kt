package com.bd2toolsbox.core.storage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `ModSnapshotStore` 的行为（T10）。
 *
 * ## 为什么这一组必须存在
 *
 * 计划验收的两条命令（`Test-Bd2ReleaseSmoke -Case 'snapshot-compatibility'`）本轮跑不了：
 * case 未实现（`tools/verification.ps1:823` 直接 throw，T04 已把其余 case 划给 T44/T57）、
 * 无 arm64 设备、且 x86_64 那条的产物钉在 `rebuilt-original`（0.2.16 源码重建包，
 * 验的是「0.2.16 能不能读自己」，**验不到 T10 的代码**）。见 `T10.md` §4.1。
 *
 * `ModSnapshotStore` 只收 `File`（不吃 `Context`）这条设计决定，正是为了让
 * **同一套语义在这里可验证**：本文件用 `TemporaryFolder` 跑真实文件系统，
 * 无 mockk、无 Robolectric。设备侧那份（`ReleaseSnapshotCompatibilityTest`）
 * 只负责真机上的同一套往返。
 *
 * ## 断言纪律
 *
 * 期望值全部是**写死的字面量**或直接用夹具里的原值，**不调用被测函数去算**。
 * 每条断言都注明了它守的是哪一层、什么破坏会让它红 —— 见 `T10.md` §6 的证伪实验。
 */
class ModSnapshotStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var filesDir: File

    @Test
    fun jsonKeysArePreserved() {
        // T10/T35: SnapshotV1 序列化必须保留规范键（version/savedAt/mods/sourceDirs），
        // 不能退回 a/b/c 混淆形态。单测测不到 R8，但能钉住 @SerialName 的键名。
        val payload = ModSnapshotStore.SnapshotV1(
            version = 1,
            savedAt = 12345L,
            mods = emptyList(),
            sourceDirs = listOf("test")
        )
        val json = Json { encodeDefaults = true; explicitNulls = false }
            .encodeToString(ModSnapshotStore.SnapshotV1.serializer(), payload)
        assertTrue("JSON should contain version key: $json", json.contains("\"version\""))
        assertTrue("JSON should contain savedAt key: $json", json.contains("\"savedAt\""))
        assertTrue("JSON should contain mods key: $json", json.contains("\"mods\""))
        assertTrue("JSON should contain sourceDirs key: $json", json.contains("\"sourceDirs\""))
        
        // Negative assertions for obfuscated names
        assertFalse("JSON should not use 'a' for top level: $json", json.contains("\"a\":"))
        assertFalse("JSON should not use 'b' for top level: $json", json.contains("\"b\":"))
    }

    /** 每个用例一个全新的临时 files 目录 —— 用例之间不共享任何文件状态。 */
    @Before
    fun setUp() {
        filesDir = temp.newFolder("files")
    }

    /** 被测文件：`<filesDir>/mod_list_snapshot.json`。 */
    private val snapshotFile: File get() = File(filesDir, ModSnapshotStore.SNAPSHOT_FILENAME)

    private fun newStore() = ModSnapshotStore(snapshotFile)

    /**
     * 盘上夹具（0.2.16 release 真实捕获的**脱敏**派生件）。
     *
     * 两份副本必须逐字节相同：这一份在 JVM resources，另一份在
     * `app/src/androidTest/assets/legacy/mod-snapshot-release.json`。
     * sha256 登记在 `docs/refactor/fixture-manifest.json` 的 `derivedTargets`。
     */
    private fun releaseFixtureBytes(): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("legacy/mod-snapshot-release.json")
            ?.use { it.readBytes() }
            ?: error("夹具不在 classpath：legacy/mod-snapshot-release.json（见 fixture-manifest derivedTargets）")

    /** 把夹具铺成一份真实的旧快照，返回它的源目录列表（供 `load` 的期望值用）。 */
    private fun installReleaseFixture(): List<String> {
        filesDir.mkdirs()
        snapshotFile.writeBytes(releaseFixtureBytes())
        return listOf(
            "content://com.example.documents/tree/primary%3Asample-mods-alpha",
            "content://com.example.documents/tree/primary%3Asample-mods-beta"
        )
    }

    private fun entry(
        name: String = "Sample",
        character: String = "SampleCharA",
        costume: String = "Sample Costume 1",
        type: String = "过场动画",
        uri: String,
        installState: String = "NOT_INSTALLED",
        kind: String = "CONVERTED_BUNDLE",
        resolutionState: String = "KNOWN"
    ) = SnapshotEntry(
        name = name, character = character, costume = costume, type = type,
        isEnabled = false, uri = uri, targetHashedName = null, isDirectory = true,
        resolutionState = resolutionState, targetHash = null, resolvedFamilyKey = null,
        errorReason = null, installState = installState, kind = kind,
        convertedHashDir = null, convertedDataSize = 0L, defect = null, outdatedCurrentHash = null
    )

    // ================================================================
    // 1. 旧 release 形态（a/b/c、无 version）—— 本任务的核心
    // ================================================================

    /**
     * 0.2.16 release 写出的 `{"a":…,"b":…,"c":…}` 能按 `mapping.txt` 的无歧义映射读回。
     *
     * 守的是「序列化契约」这一层本身：**破坏它** = 去掉 `LegacyObfuscatedSnapshot` 分支
     * （`obj.has("b")` 那一路），此时旧文件会落进 `Corrupt`（顶层既没有 version 也没有 b）
     * → 5 条变 0 条，本用例红。
     *
     * 期望值是**夹具里的原值**（脱敏后的合成值），不是算出来的。
     */
    @Test
    fun legacyObfuscatedReleaseSnapshotLoadsAllEntries() {
        val dirs = installReleaseFixture()
        val beforeBytes = snapshotFile.readBytes().copyOf()
        val loaded = newStore().load(dirs)
        assertTrue("旧快照必须能读回，实际是 $loaded", loaded is SnapshotLoad.Loaded)
        val loadedState = loaded as SnapshotLoad.Loaded
        assertTrue("读取旧格式应标记 wasLegacy=true", loadedState.wasLegacy)
        assertArrayEqualsBytes(beforeBytes, snapshotFile.readBytes())
        val entries = loadedState.entries

        assertEquals("夹具里是 5 条 mod", 5, entries.size)
        assertEquals("没有条目被判为结构不完整", 0, loaded.droppedEntries)

        // 逐条核对映射：a→savedAt 在下面单测，b→mods 就是这 5 条。
        assertEquals("Sample Mod 1（过场动画）", entries[0].name)
        assertEquals("SampleCharA", entries[0].character)
        assertEquals("Sample Costume 1", entries[0].costume)
        assertEquals("过场动画", entries[0].type)
        assertEquals("CONVERTED_BUNDLE", entries[0].kind)
        assertEquals("NOT_INSTALLED", entries[0].installState)
        assertEquals("KNOWN", entries[0].resolutionState)
        assertEquals(true, entries[0].isDirectory)
        assertEquals(false, entries[0].isEnabled)

        // 第 2 条带一个不同的枚举值，证明「逐条」而不是「全表一个值」。
        assertEquals("MODIFIED_BY_OTHER", entries[1].installState)
        // 第 3 条是 PC_SOURCE 且带 resolvedFamilyKey（夹具里唯一的一条）。
        assertEquals("PC_SOURCE", entries[2].kind)
        assertEquals("samplefamilykey", entries[2].resolvedFamilyKey)
        assertEquals("Sample Mod 3（立绘）", entries[4].name)
    }

    /**
     * `a` → `savedAt` 的映射单独钉一次。
     *
     * 守的是「键名到字段的对应关系」：若 `@SerializedName("a")` 被改成别的键，
     * 这个值会变成默认的 `0L`，用例红。`1789327404471` 是夹具里的原值。
     */
    @Test
    fun legacySavedAtMapsFromKeyA() {
        val dirs = installReleaseFixture()
        val loaded = newStore().load(dirs) as SnapshotLoad.Loaded
        assertEquals(1789327404471L, loaded.savedAt)
    }

    /**
     * `c` → `sourceDirs` 的映射：把期望值改成夹具里的那一组，就能读；换一组就拒收。
     *
     * 守的是两道不同的东西 —— 正向证明 `c` 被读成了 sourceDirs（否则任何期望值都不会
     * 匹配上、第一次断言就红），反向证明这个闸真的在拦（否则第二次断言会读出来）。
     */
    @Test
    fun legacySourceDirsMapFromKeyCAndGateOnMismatch() {
        val dirs = installReleaseFixture()
        assertTrue("与夹具 sourceDirs 一致时必须读出来", newStore().load(dirs) is SnapshotLoad.Loaded)

        val other = listOf("content://com.example.documents/tree/primary%3Asomething-else")
        val mismatched = newStore().load(other)
        assertTrue("换一组源目录必须拒收，实际是 $mismatched", mismatched is SnapshotLoad.SourceDirsChanged)
        assertEquals(dirs, (mismatched as SnapshotLoad.SourceDirsChanged).from)
        assertEquals(other, mismatched.to)
    }

    /**
     * 源目录变化导致的拒收必须是【零副作用】的，不能因为碰巧读了旧格式
     * 就把它当成“加载成功并需要迁移”，从而向盘上写出规范化的新文件。
     */
    @Test
    fun legacyLoadRejectedBySourceDirsMismatchLeavesFileUntouched() {
        installReleaseFixture()
        val beforeBytes = snapshotFile.readBytes().copyOf()
        val differentDirs = listOf("content://different")
        val loaded = newStore().load(differentDirs)
        assertTrue(loaded is SnapshotLoad.SourceDirsChanged)
        assertArrayEqualsBytes(beforeBytes, snapshotFile.readBytes())
    }

    // ================================================================
    // 2. 新写入格式：规范键名，**绝不**写 a/b/c
    // ================================================================

    @Test
    fun legacyLoadFollowedByCommitUpgradesToNewFormat() {
        val dirs = installReleaseFixture()
        val store = newStore()
        val loaded = store.load(dirs) as SnapshotLoad.Loaded
        assertTrue("应为旧格式", loaded.wasLegacy)

        val write = store.capture(loaded.entries, dirs)
        assertTrue(store.commit(write, dirs) is SnapshotCommit.Written)

        val root = Json.parseToJsonElement(snapshotFile.readText()) as JsonObject
        assertEquals(
            "迁移写入后顶层键必须是新格式",
            setOf("version", "savedAt", "mods", "sourceDirs"),
            root.keys
        )
        for (obfuscated in listOf("a", "b", "c")) {
            assertFalse("迁移写入后不许有混淆键 '$obfuscated'", root.containsKey(obfuscated))
        }
    }

    /**
     * 新写出的文件必须用规范键名，而不是把 0.2.16 的混淆产物升格成永久契约。
     *
     * 守的是计划红线「不许把 `a`/`b`/`c` 钉进新写入的持久格式」。
     * **破坏它** = 把 `SnapshotV1` 的三个 `@SerializedName` 改成 `"a"/"b"/"c"`，
     * 此时顶层键集与下面的字面量对不上 → 本用例红（而旧格式读取仍会通过）。
     */
    @Test
    fun newFormatUsesCanonicalKeyNamesNotObfuscatedOnes() {
        filesDir.mkdirs()
        val store = newStore()
        val dirs = listOf("content://com.example.documents/tree/primary%3Asample-mods-alpha")
        val write = store.capture(
            entries = listOf(entry(uri = "content://com.example.documents/tree/x/document/y")),
            sourceDirs = dirs
        )
        assertTrue(store.commit(write, dirs) is SnapshotCommit.Written)

        val root = Json.parseToJsonElement(snapshotFile.readText()) as JsonObject
        assertEquals(
            "新格式的顶层键必须是规范名",
            setOf("version", "savedAt", "mods", "sourceDirs"),
            root.keys
        )
        // 逐条否定：这三个键在新文件里**一个都不许出现**。
        for (obfuscated in listOf("a", "b", "c")) {
            assertFalse("新格式顶层不许出现混淆键 '$obfuscated'", root.containsKey(obfuscated))
        }

        // 内层条目的键名也必须是规范名（ModInfo 的字段名），不能是 R8 产物。
        val firstMod = (root["mods"] as JsonArray)[0] as JsonObject
        assertTrue("mods[0] 必须带 'name'", firstMod.containsKey("name"))
        assertTrue("mods[0] 必须带 'uri'", firstMod.containsKey("uri"))
        assertTrue("mods[0] 必须带 'installState'", firstMod.containsKey("installState"))
        assertFalse("mods[0] 不许有混淆键 'a'", firstMod.containsKey("a"))
    }

    /**
     * 新格式里 `version` 必须**真的写出来、真的被读**。
     *
     * 这正是 0.2.16 被 R8 整个折叠掉的那个字段（`T10.md` §2 实测）。
     * 守的是「版本闸存在」：**破坏它** = 把 `version` 从 DTO 里删掉，
     * 文件里不会有这个键、`has(KEY_VERSION)` 判定改走 legacy 分支或判损坏 → 红。
     */
    @Test
    fun newFormatPersistsAVersionFieldThatIsActuallyRead() {
        filesDir.mkdirs()
        val store = newStore()
        val dirs = listOf("content://com.example.documents/tree/x")
        val write = store.capture(entries = emptyList(), sourceDirs = dirs)
        assertTrue(store.commit(write, dirs) is SnapshotCommit.Written)

        val root = Json.parseToJsonElement(snapshotFile.readText()) as JsonObject
        assertTrue("新格式必须写出 version", root.containsKey("version"))
        assertEquals(ModSnapshotStore.SCHEMA_VERSION, (root["version"] as JsonPrimitive).content.toInt())

        // 真正被读：改成一个不认识的版本，读侧必须判 StaleVersion 而不是当空表或当旧格式。
        val bumped = buildJsonObject {
            put("version", ModSnapshotStore.SCHEMA_VERSION + 98)
            put("mods", root["mods"] as JsonArray)
            put("savedAt", 0L)
            put("sourceDirs", root["sourceDirs"] as JsonArray)
        }
        snapshotFile.writeText(bumped.toString())
        val loaded = newStore().load(dirs)
        assertEquals(SnapshotLoad.StaleVersion(ModSnapshotStore.SCHEMA_VERSION + 98), loaded)
    }

    // ================================================================
    // 3. 往返：保存 → 新建 Store（模拟「再次启动」）→ 恢复一致
    // ================================================================

    /**
     * 保存 → 换一个 Store 实例读回（模拟进程重启）→ 逐字段一致。
     *
     * 守的是「重启后列表还能恢复」这一层的**纯 JVM 部分**。设备上的真 release 版本
     * 未跑（见 `T10.md` §7.2）。期望值是构造时写死的字面量。
     */
    @Test
    fun roundTripThroughANewStoreInstancePreservesEveryField() {
        filesDir.mkdirs()
        val dirs = listOf(
            "content://com.example.documents/tree/primary%3Asample-mods-alpha",
            "content://com.example.documents/tree/primary%3Asample-mods-beta"
        )
        val original = SnapshotEntry(
            name = "往返样本", character = "RoundTripChar", costume = "RoundTrip Costume",
            type = "立绘", isEnabled = true,
            uri = "content://com.example.documents/tree/x/document/y",
            targetHashedName = "aaaabbbbccccddddeeeeffff00001111",
            isDirectory = false, resolutionState = "MISC", targetHash = "1111222233334444",
            resolvedFamilyKey = "family-x", errorReason = "some-reason",
            installState = "STALE", kind = "PC_SOURCE",
            convertedHashDir = "9999888877776666", convertedDataSize = 123456789L,
            defect = "QUALITY_MISMATCH",
            outdatedCurrentHash = "ffff0000ffff0000"
        )

        val first = newStore()
        val write = first.capture(listOf(original), dirs)
        assertTrue(first.commit(write, dirs) is SnapshotCommit.Written)

        // 换实例 = 换一次「启动」，不复用任何内存状态。
        val loaded = newStore().load(dirs)
        assertTrue("二次启动必须读回，实际是 $loaded", loaded is SnapshotLoad.Loaded)
        val loadedState = loaded as SnapshotLoad.Loaded
        assertFalse("读取新格式不应标记 wasLegacy", loadedState.wasLegacy)
        val restored = loadedState.entries
        assertEquals(1, restored.size)
        // 整条相等（data class 的 equals）—— 字段逐个写在上面，不经过被测函数计算。
        assertEquals(original, restored.single())
    }

    // ================================================================
    // 4. 显式失效：未知版本 / 损坏 / 空文件 / mods 为 null
    // ================================================================

    private fun writeRaw(text: String) {
        filesDir.mkdirs()
        snapshotFile.writeText(text)
    }

    /**
     * `{"version":99,…}` → [SnapshotLoad.StaleVersion]，**不是**空表、**不是**「没有快照」。
     *
     * 守的是计划验收「未知版本快照触发明确重新加载，不制造『用户没有安装过 MOD』的事实」。
     * 破坏它 = 去掉版本闸，此时会落进 `Loaded`（按新格式读出来）或 `Corrupt`，两种都红。
     */
    @Test
    fun unknownVersionIsReportedAsStaleNotAsAbsent() {
        writeRaw("""{"version":99,"savedAt":1,"mods":[],"sourceDirs":[]}""")
        val loaded = newStore().load(emptyList())
        assertEquals(SnapshotLoad.StaleVersion(99), loaded)
    }

    /**
     * **被版本闸取代的低版本**（`version` < `SCHEMA_VERSION`）同样不许被静默当成可用快照。
     *
     * ## 为什么要单开一条（二轮补，针对复核真缺陷 1）
     *
     * 上一条只打了「比当前版本**高**」的 99。真正的静默失效发生在**另一个方向**：
     * 一个 `version` 比 `SCHEMA_VERSION` **小**的文件（典型场景 —— 把 `SCHEMA_VERSION`
     * 从 1 抬到 2 之后，磁盘上那些还是 `1` 的老快照）。若闸门写成
     * `if (version > SCHEMA_VERSION)`（一个**很自然**的写法：「版本只会往前走，只需要防更新的」），
     * 这些老文件就会**静默走进 `Loaded`**，把一份 schema 已经变了的表当成当前的读出来 ——
     * 正是计划要防的「制造出用户没有安装过 MOD / 表内容是假的」那类事实。
     *
     * ## 为什么断言是「不许是 `Loaded`」而不是「必须是 `StaleVersion(0)`」
     *
     * 这条件用一条**与键名无关**的性质来表达，才能与 §7.1 的证伪（改 `@SerializedName`
     * 为 `a`/`b`/`c`）**失败集不相交**：
     *
     * - 键名被改成 `a`/`b`/`c` 之后，这份文档会落进 `Corrupt`（`mods` 从改名后的键读不到）
     *   —— **`Corrupt` 也满足「不是 `Loaded`」**，所以本用例在那种破坏下**仍绿**；
     * - 而闸门被收窄成 `>` 之后，它会落进 `Loaded`，本用例**红**。
     *
     * 断言「不是 `Loaded`」正是那句要守的话本身：**未知（含被取代的旧）版本绝不能被当成
     * 一张可用的表读出去**。文档里带**非空**的合法条目，是为了让「静默读出去」这件事
     * 是有后果的（会显示成一批其实不属于当前 schema 的 mod），而不是一张空表。
     *
     * 破坏它 = 把 `parse` 的版本闸从 `!=` 收窄成 `>`（或对未知版本直接放行），红。
     */
    @Test
    fun supersededLowerVersionIsNeverSilentlyLoaded() {
        // version=0：比 SCHEMA_VERSION(=1) 小的未知版本。mods 里放一条**结构完整**的条目，
        // 让「静默读出去」有真实后果。
        writeRaw(
            """{"version":0,"savedAt":42,"sourceDirs":[],"mods":[
               {"uri":"content://x/1","name":"superseded","character":"c","costume":"k",
                "type":"t","resolutionState":"KNOWN","installState":"NOT_INSTALLED","kind":"PC_SOURCE"}
               ]}"""
        )
        val loaded = newStore().load(emptyList())
        assertTrue(
            "被取代的低版本快照绝不能被当成可用快照读出来（静默失效），实际是 $loaded",
            loaded !is SnapshotLoad.Loaded
        )
    }

    /**
     * 半截 JSON / 空文件 / `mods` 为 null / 顶层是数组 —— 四种都必须落 `Corrupt`。
     *
     * 守的是「损坏 ≠ 用户没有安装过 MOD」：这四种若返回 `Loaded(emptyList())`，
     * 调用方就分不出「文件坏了」和「本来就没 mod」。破坏它 = 在 `parse` 里对
     * `mods == null` 返回 `Loaded(emptyList())`，最后一条会红。
     */
    @Test
    fun malformedSnapshotsAreCorruptNotSilentlyEmpty() {
        val cases = mapOf(
            "半截 JSON" to """{"version":1,"savedAt":1,"mods":[""",
            "空文件" to "",
            "全空白" to "   \n\t ",
            "mods 为 null" to """{"version":1,"savedAt":1,"mods":null,"sourceDirs":[]}""",
            "顶层是数组" to """[1,2,3]""",
            "顶层是 JSON null" to """null""",
            "既无 version 也无 b" to """{"savedAt":1,"mods":[]}"""
        )
        for ((label, text) in cases) {
            writeRaw(text)
            val loaded = newStore().load(emptyList())
            assertTrue("$label 必须判 Corrupt，实际是 $loaded", loaded is SnapshotLoad.Corrupt)
        }
    }

    /**
     * 读路径**零副作用**：损坏的文件在读完之后仍原样在盘上。
     *
     * 守的是「读不删」这条边界 —— 删除是调用方（`MainViewModel`）看过分态结果之后的
     * **显式**决定，不是读的副作用。破坏它 = 在 `load` 的损坏分支里顺手 `delete()`，红。
     */
    @Test
    fun loadNeverTouchesDiskEvenWhenCorrupt() {
        writeRaw("""{"broken":""")
        val before = snapshotFile.readBytes().copyOf()
        newStore().load(emptyList())
        assertTrue("读损坏文件之后它必须还在", snapshotFile.exists())
        assertArrayEqualsBytes(before, snapshotFile.readBytes())
    }

    /**
     * `discard()` **只删快照文件本身**，同目录的账本/配置/备份一个都不许动。
     *
     * 守的是计划红线「快照失效只清快照文件，不许清安装账本/配置」。这是**唯一**能自动
     * 守住这条红线的地方 —— 破坏它 = 把 `discard` 改成父目录 `deleteRecursively()`，红。
     */
    @Test
    fun discardRemovesOnlyTheSnapshotFile() {
        filesDir.mkdirs()
        val ledger = File(filesDir, "installed_mods.json").apply { writeText("""{"records":{}}""") }
        val journal = File(filesDir, "operation_journal.json").apply { writeText("""{"stage":"PLANNED"}""") }
        val bundleMeta = File(filesDir, "bundle_meta_HD.json").apply { writeText("""{"x":1}""") }
        val prefs = File(filesDir, "app_settings.xml").apply { writeText("<map/>") }
        val untouched = listOf(ledger, journal, bundleMeta, prefs)
        snapshotFile.writeBytes(releaseFixtureBytes())

        assertTrue("快照文件存在时 discard 必须成功", newStore().discard())
        assertFalse("快照文件必须已被删掉", snapshotFile.exists())
        for (f in untouched) {
            assertTrue("discard 不许碰 ${f.name}", f.exists())
        }
        assertEquals("""{"records":{}}""", ledger.readText())
    }

    /**
     * 未知版本触发失效时，也**只**删快照 —— 这条把「分态」与「只清快照」两条红线接起来。
     *
     * 破坏它 = 让 `MainViewModel` 的 StaleVersion 分支去清账本（本用例直接驱动 Store，
     * 所以它守的是 Store 侧：discard 的边界）。调用方侧的那一半见设备用例。
     */
    @Test
    fun staleVersionDiscardAlsoLeavesOtherFilesAlone() {
        filesDir.mkdirs()
        val ledger = File(filesDir, "installed_mods.json").apply { writeText("""{"records":{"k":1}}""") }
        writeRaw("""{"version":99,"savedAt":1,"mods":[],"sourceDirs":[]}""")

        val store = newStore()
        assertTrue(store.load(emptyList()) is SnapshotLoad.StaleVersion)
        assertTrue(store.discard())
        assertEquals("""{"records":{"k":1}}""", ledger.readText())
    }

    // ================================================================
    // 5. 写入顺序与失败回退
    // ================================================================

    /**
     * 迟到的旧扫描不许覆盖更新的结果。
     *
     * 这是 `ModSnapshotTest.lateScanSnapshotMustNotOverwriteNewerEmptyResult` 的
     * **纯 JVM 迁移版**（迁移前那条靠反射摸 `MainViewModel` 的私有方法，方法搬走后
     * 会在运行期抛 `NoSuchElementException`）。
     *
     * 破坏它 = 去掉 `commit` 里的 `write.revision != revision` 闸，此时后提交的旧快照
     * 会盖掉新的空表，最后一条断言红。
     */
    @Test
    fun lateScanCommitMustNotOverwriteNewerEmptyResult() {
        filesDir.mkdirs()
        val store = newStore()
        val dirs = listOf("content://com.example.documents/tree/x")
        val hasMods = entry(uri = "content://com.example.documents/tree/x/document/y")

        val oldScan = store.capture(listOf(hasMods), dirs)   // revision 1
        val newerEmpty = store.capture(emptyList(), dirs)    // revision 2

        assertTrue(store.commit(newerEmpty, dirs) is SnapshotCommit.Written)
        val late = store.commit(oldScan, dirs)
        assertTrue("迟到的旧扫描必须被拒，实际是 $late", late is SnapshotCommit.Skipped)

        val loaded = newStore().load(dirs)
        assertEquals(0, (loaded as SnapshotLoad.Loaded).entries.size)
    }

    /**
     * 源目录变化后，一份**已捕获**的旧写不许落盘。
     *
     * 与 `ModSnapshotTest.sourceChangeRejectsOldSnapshot` 同语义。
     * 破坏它 = 去掉 `commit` 的 sourceDirs 闸，红。
     */
    @Test
    fun commitIsRejectedWhenSourceDirsChangedSinceCapture() {
        filesDir.mkdirs()
        val store = newStore()
        val captured = store.capture(
            entries = listOf(entry(uri = "content://com.example.documents/tree/x/document/y")),
            sourceDirs = listOf("content://com.example.documents/tree/old")
        )
        val result = store.commit(captured, listOf("content://com.example.documents/tree/new"))
        assertTrue("源目录变了必须拒收，实际是 $result", result is SnapshotCommit.Skipped)
        assertFalse("拒收时不许落盘", snapshotFile.exists())
    }

    /**
     * 源目录被清空 → `capture(emptyList())` 的那份写仍然会被收（它是**当前**的、
     * 且源目录一致），落成一张合法的空表 —— 这是「清目录」的正常路径。
     *
     * 与上一条对照：拒收的是**过期**的写，不是空表本身。
     * 破坏它 = 把空表也当成「没有内容」拒收，红。
     */
    @Test
    fun clearingSourcesWritesAValidEmptyTable() {
        filesDir.mkdirs()
        val store = newStore()
        val empty = emptyList<String>()
        val write = store.capture(emptyList(), empty)
        assertTrue(store.commit(write, empty) is SnapshotCommit.Written)

        val loaded = newStore().load(empty)
        assertTrue(loaded is SnapshotLoad.Loaded)
        assertEquals(0, (loaded as SnapshotLoad.Loaded).entries.size)
    }

    /**
     * 写入失败时**旧快照保持原样**，而且**不许**退化成「先删目标再改名」。
     *
     * 造失败：把目标路径占成一个**目录**（`AtomicFileWriter` 的原子替换对目录会失败）。
     * 期望：`Failed`，且那个目录原样还在（没有被删掉当垫脚石）。
     *
     * 守的是改动前那个真缺陷 —— 旧代码 `if (!tmp.renameTo(file)) { file.delete(); … }`
     * 会先把目标删掉再改名，第二次再失败就**新旧都没有了**。
     * 破坏它 = 把 `commit` 换回那套 `.tmp` + 先删后改名，这个用例会看到目标被删/失败被吞。
     */
    @Test
    fun failedCommitLeavesThePreviousFileUntouched() {
        filesDir.mkdirs()
        // 目标被一个目录占着 —— 原子替换必然失败。
        assertTrue(snapshotFile.mkdirs())
        File(snapshotFile, "keep-me.txt").writeText("原样")

        val store = newStore()
        val dirs = listOf("content://com.example.documents/tree/x")
        val write = store.capture(listOf(entry(uri = "content://com.example.documents/tree/x/document/y")), dirs)
        val result = store.commit(write, dirs)

        assertTrue("对目录目标必须失败，实际是 $result", result is SnapshotCommit.Failed)
        assertTrue("目标目录必须原样还在", snapshotFile.isDirectory)
        assertEquals("原样", File(snapshotFile, "keep-me.txt").readText())
    }

    /**
     * 上次进程被杀留下的 `.tmp` 残留：既不参与读取，也不被误当成快照。
     *
     * 守的是「`.tmp` 残留不许被当有效快照」。旧实现读的是固定文件名，这条本来就成立；
     * 现在写侧改走 `AtomicFileWriter`（暂存件名是 `.bd2staged-*.tmp`，与旧的
     * `<name>.tmp` 不同），所以这条要重新钉一次 —— 两种残留名都测。
     */
    @Test
    fun staleTempFilesAreNeitherReadNorTreatedAsSnapshots() {
        filesDir.mkdirs()
        // 旧实现留下的 `.tmp` 名，以及 AtomicFileWriter 的暂存件名。
        File(filesDir, "${ModSnapshotStore.SNAPSHOT_FILENAME}.tmp")
            .writeText("""{"version":1,"savedAt":9,"mods":[{"uri":"junk"}],"sourceDirs":[]}""")
        File(filesDir, ".bd2staged-abc123.tmp")
            .writeText("""{"version":1,"savedAt":9,"mods":[{"uri":"junk"}],"sourceDirs":[]}""")

        val loaded = newStore().load(emptyList())
        assertEquals("真正的快照不存在时必须报 Absent，不许读到 .tmp", SnapshotLoad.Absent, loaded)
    }

    // ================================================================
    // 6. 结构不完整条目：丢掉并计数，不静默
    // ================================================================

    /**
     * 数组里混进 null / 缺关键字段的条目 → 整条丢掉**并计数**。
     *
     * 守的是「丢了几条必须可见」：旧实现 `mapNotNull` 静默吞掉，排查时看不出
     * 「表读出来了但少了两条」。破坏它 = 把 `droppedEntries` 恒置 0，红。
     */
    @Test
    fun structurallyIncompleteEntriesAreDroppedAndCounted() {
        writeRaw(
            """{"version":1,"savedAt":5,"sourceDirs":[],"mods":[""" +
                """{"uri":"content://x/1","name":"ok","character":"c","costume":"k","type":"t","resolutionState":"KNOWN","installState":"NOT_INSTALLED","kind":"PC_SOURCE"},""" +
                """null,""" +
                """{"name":"缺 uri","character":"c","costume":"k","type":"t","resolutionState":"KNOWN","installState":"NOT_INSTALLED","kind":"PC_SOURCE"}""" +
                """]}"""
        )
        val loaded = newStore().load(emptyList())
        assertTrue(loaded is SnapshotLoad.Loaded)
        val l = loaded as SnapshotLoad.Loaded
        assertEquals("只有 1 条是结构完整的", 1, l.entries.size)
        assertEquals("另外 2 条必须被计数", 2, l.droppedEntries)
        assertEquals("ok", l.entries.single().name)
    }

    /**
     * 空表不是损坏 —— `{"version":1,"mods":[],"sourceDirs":[…]}` 是**合法**的一份快照。
     *
     * 与「损坏」对照：用户确实可能一个 mod 都没有，那要落 `Loaded(0 条)` 而不是 `Corrupt`，
     * 否则每次冷启动都会白删一次文件。破坏它 = 把空 mods 也判 Corrupt，红。
     */
    @Test
    fun anEmptyModArrayIsAValidSnapshotNotCorruption() {
        writeRaw("""{"version":1,"savedAt":7,"mods":[],"sourceDirs":[]}""")
        val loaded = newStore().load(emptyList())
        assertTrue("空表是合法的，实际是 $loaded", loaded is SnapshotLoad.Loaded)
        assertEquals(0, (loaded as SnapshotLoad.Loaded).entries.size)
        assertEquals(7L, loaded.savedAt)
    }

    /**
     * 快照文件路径被一个**目录**占着 → `IoError`，既不当 `Absent` 也不当 `Corrupt`。
     *
     * 守的是「别去跟一个目录抢名字 / 别把目录当快照删掉」。破坏它 = 把 isDirectory
     * 分支去掉，此时会走 `snapshotFile.exists() == true` → `readText()` 抛
     * → `IoError`（巧合仍绿）或 `Corrupt`（红）。断言取 `IoError` 精确值。
     */
    @Test
    fun aDirectoryWhereTheSnapshotShouldBeIsAnIoError() {
        filesDir.mkdirs()
        assertTrue(snapshotFile.mkdirs())
        val loaded = newStore().load(emptyList())
        assertTrue("目录占位必须报 IoError，实际是 $loaded", loaded is SnapshotLoad.IoError)
        // 而且读路径零副作用：那个目录还在。
        assertTrue(snapshotFile.isDirectory)
    }

    /**
     * `Absent` 与 `Corrupt` 必须可区分：文件根本不在时不许报损坏。
     *
     * 守的是调用方的处置正确性 —— 若文件不存在也报 `Corrupt`，`MainViewModel` 会去调
     * `discard()`（无害但无意义），更重要的是「从来没用过这个功能」会被当成一次异常。
     * 破坏它 = 在 `load` 里去掉 `exists()` 判断 → 读不到会走 `readText()` 抛 → `IoError`，红。
     */
    @Test
    fun missingFileIsAbsentNotCorrupt() {
        filesDir.mkdirs()
        assertEquals(SnapshotLoad.Absent, newStore().load(emptyList()))
    }

    // ================================================================
    // 7. 磁盘契约的自检：DTO 注解键名与常量必须一致
    // ================================================================

    /**
     * `@SerialName` 的键名必须与 `parse` 里用的常量一致。
     *
     * 这两处一旦漂移（有人改了注解、常量没跟上），形态判定就会走错分支
     * —— 这类「两处字面量必须相同」的契约靠读代码很容易漏，用一条用例钉住。
     * 破坏它 = 把 `SnapshotV1.version` 的 `@SerialName` 改成别的名字，红。
     */
    @Test
    fun serializedNamesMatchTheFormatConstants() {
        // 直接序列化一个默认实例看键名（比反射 @SerialName 稳：注解 use-site 不保证落在字段上）。
        val keyJson = kotlinx.serialization.json.Json {
            encodeDefaults = true
            explicitNulls = true
        }
        val v1Keys = keyJson
            .encodeToString(ModSnapshotStore.SnapshotV1.serializer(), ModSnapshotStore.SnapshotV1())
            .let { kotlinx.serialization.json.Json.parseToJsonElement(it) }
            .let { (it as kotlinx.serialization.json.JsonObject).keys }
        assertTrue("SnapshotV1 必须有 version/mods 键", v1Keys.containsAll(listOf("version", "mods")))

        val legacyKeys = keyJson
            .encodeToString(
                ModSnapshotStore.LegacyObfuscatedSnapshot.serializer(),
                ModSnapshotStore.LegacyObfuscatedSnapshot()
            )
            .let { kotlinx.serialization.json.Json.parseToJsonElement(it) }
            .let { (it as kotlinx.serialization.json.JsonObject).keys }
        assertEquals(
            "0.2.16 的键名是 mapping.txt 给的 a/b/c，不许漂移",
            setOf("a", "b", "c"),
            legacyKeys
        )
    }

    /**
     * `PendingWrite` 只在**产生它的那个 Store 实例**上有效。
     *
     * 这不是废话，是一条被真实踩到的坑（T10 实现期）：`MainViewModel` 起初把 Store 写成
     * `get() = ModSnapshotStore(...)` 这种「每次现算」的属性，于是 `capture` 与 `commit`
     * 落在**两个不同实例**上 —— 后者计数器还是 0，**每一次提交都被判成过期**，
     * 快照永远写不出去。debug 下没有任何异常，只有一条 `Log.i`，极易漏掉。
     *
     * 本用例把这条不变式钉在 Store 这一层：**调用方必须复用同一个实例**。
     * 破坏它（把 revision 从实例状态改成全局/静态）会让这条红 —— 而那种「修法」正是
     * 掩盖调用方错误、而不是暴露它。
     */
    @Test
    fun pendingWriteIsOnlyHonouredByItsOriginatingInstance() {
        filesDir.mkdirs()
        val dirs = listOf("content://com.example.documents/tree/x")
        val captured = ModSnapshotStore(snapshotFile).capture(
            entries = listOf(entry(uri = "content://com.example.documents/tree/x/document/y")),
            sourceDirs = dirs
        )
        val other = ModSnapshotStore(snapshotFile)
        val result = other.commit(captured, dirs)
        assertTrue(
            "另一个实例必须拒收（它的计数器没到过那个数），实际是 $result",
            result is SnapshotCommit.Skipped
        )
        assertFalse("被拒 = 没有落盘", snapshotFile.exists())
    }

    /**
     * 同一个实例上 `capture` → `commit` 必须成功（上一条的阳性对照）。
     *
     * 两条合起来说明：拒绝的判据是「实例 + 序号」，不是「一律拒绝」。
     */
    @Test
    fun pendingWriteFromTheSameInstanceIsHonoured() {
        filesDir.mkdirs()
        val dirs = listOf("content://com.example.documents/tree/x")
        val store = ModSnapshotStore(snapshotFile)
        val captured = store.capture(
            entries = listOf(entry(uri = "content://com.example.documents/tree/x/document/y")),
            sourceDirs = dirs
        )
        assertTrue(store.commit(captured, dirs) is SnapshotCommit.Written)
        assertTrue("同一实例必须写得出去", snapshotFile.exists())
    }

    // ---------------------------------------------------------------- 辅助

    /** 逐字节比较（避免依赖 `assertArrayEquals` 的重载歧义）。 */
    private fun assertArrayEqualsBytes(expected: ByteArray, actual: ByteArray) {
        assertEquals("字节长度不同", expected.size, actual.size)
        assertTrue("字节内容不同", expected.contentEquals(actual))
    }

    /** 显式确认 `snapshotFile` 就是被测文件（防止夹具路径与断言路径不是同一个）。 */
    @Test
    fun storeExposesTheFileItWasGiven() {
        assertNotNull(newStore().file)
        assertEquals(snapshotFile.absolutePath, newStore().file.absolutePath)
    }
}
