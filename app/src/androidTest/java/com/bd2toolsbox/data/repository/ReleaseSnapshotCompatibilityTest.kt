package com.bd2toolsbox.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bd2toolsbox.core.storage.ModSnapshotStore
import com.bd2toolsbox.core.storage.SnapshotCommit
import com.bd2toolsbox.core.storage.SnapshotEntry
import com.bd2toolsbox.core.storage.SnapshotLoad
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * 真实 release 冷启动快照的兼容性（T10）。
 *
 * ## 这个类**不是在跑 minified release**
 *
 * 计划的验收命令是 `Test-Bd2ReleaseSmoke -Case 'snapshot-compatibility'`，本轮
 * **NOT_RUN**（三条原因见 `T10.md` §4.1：case 未实现、无 arm64 设备、x86_64 那条的
 * 产物钉在 0.2.16 源码重建包上 —— 验不到 T10 的代码）。
 *
 * 所以本类跑的是 **debug instrumentation**，它守的是**夹具所代表的那份磁盘契约**：
 * 「0.2.16 release 实际写出的 `{"a":…,"b":…,"c":…}`，T10 之后还能不能读回来」。
 * 夹具是从设备上真实捕获的那份 `mod_list_snapshot.json` **脱敏派生**而来的
 * （来源与脱敏规则见 `T10.md` §5）。
 *
 * **不许**把这个类读成「release 包已验证」。真正需要 release 包才能回答的问题
 * （R8 是否会再次改掉字段名、混淆包里这条链是否还通）由 `T10.md` §6 的
 * 「静态 + 本机 R8 实测」回答，且只对**本工作区当前 R8 配置**成立。
 *
 * ## 夹具从 `assets/` 读，不是 `src/test/resources`
 *
 * 计划原文写的是 `app/src/test/resources/legacy/mod-snapshot-release.json` +
 * 测试放 `androidTest/`。**那样会 `FileNotFoundException`**：androidTest 的 classpath
 * 不含 `src/test/resources`（实测见 `T10.md` §4.3）。改为夹具同时放
 * `app/src/androidTest/assets/legacy/`，用 `InstrumentationRegistry…context.assets` 读。
 * 两份副本逐字节相同，sha256 登记在 `fixture-manifest.json` 的 `derivedTargets`。
 */
@RunWith(AndroidJUnit4::class)
class ReleaseSnapshotCompatibilityTest {

    private lateinit var dir: File

    /** 夹具里的两个源目录（脱敏后的合成值，与 JVM 夹具同一份）。 */
    private val fixtureSources = listOf(
        "content://com.example.documents/tree/primary%3Asample-mods-alpha",
        "content://com.example.documents/tree/primary%3Asample-mods-beta"
    )

    private fun store() = ModSnapshotStore(File(dir, ModSnapshotStore.SNAPSHOT_FILENAME))

    private fun snapshotFile() = File(dir, ModSnapshotStore.SNAPSHOT_FILENAME)

    private fun fixtureBytes(): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("legacy/mod-snapshot-release.json").use { it.readBytes() }

    @Before
    fun setup() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        dir = File(base.cacheDir, "release-compat-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After
    fun cleanupOwnFixture() {
        check(dir.name.startsWith("release-compat-") && dir.parentFile?.name == "cache")
        dir.deleteRecursively()
    }

    /**
     * 计划验收第 1 条的核心：**真实 release 样本**（`a`/`b`/`c`，无 `version`）能被读回。
     *
     * 守的是「序列化契约」本身。破坏它 = 去掉 Store 的 legacy 分支，
     * 这份文件会落进 `Corrupt` → 5 条变 0 条，本用例红（JVM 侧同一形态见
     * `ModSnapshotStoreTest.legacyObfuscatedReleaseSnapshotLoadsAllEntries`）。
     */
    @Test
    fun legacyReleaseSnapshotIsMigratedWithAllFiveEntries() {
        snapshotFile().writeBytes(fixtureBytes())

        val loaded = store().load(fixtureSources)
        assertTrue("旧 release 快照必须能读回，实际是 $loaded", loaded is SnapshotLoad.Loaded)
        val entries = (loaded as SnapshotLoad.Loaded).entries
        assertEquals("夹具里是 5 条 mod", 5, entries.size)
        assertEquals("没有条目被判为结构不完整", 0, loaded.droppedEntries)
        // 期望值是夹具里的原值（脱敏后的合成值），不经过被测函数计算。
        assertEquals("Sample Mod 1（过场动画）", entries[0].name)
        assertEquals("MODIFIED_BY_OTHER", entries[1].installState)
        assertEquals("PC_SOURCE", entries[2].kind)
    }

    /**
     * 读旧快照**不碰**同目录的账本 / 日志 / 配置 / bundle_meta。
     *
     * 计划验收第 1 条：「字段稳定且**安装账本保持不变**」。这是本轮唯一能自动守住
     * 这条的断言（设备侧）。破坏它 = 让读路径或迁移路径去动别的文件，红。
     */
    @Test
    fun readingTheLegacySnapshotLeavesTheLedgerAndConfigUntouched() {
        val ledger = File(dir, "installed_mods.json").apply { writeText(LEDGER_SAMPLE) }
        val journal = File(dir, "operation_journal.json").apply { writeText(JOURNAL_SAMPLE) }
        val prefs = File(dir, "app_settings.xml").apply { writeText(PREFS_SAMPLE) }
        val bundleMeta = File(dir, "bundle_meta_HD.json").apply { writeText(META_SAMPLE) }
        val untouched = listOf(ledger to LEDGER_SAMPLE, journal to JOURNAL_SAMPLE,
            prefs to PREFS_SAMPLE, bundleMeta to META_SAMPLE)
        snapshotFile().writeBytes(fixtureBytes())

        val loaded = store().load(fixtureSources)
        assertTrue(loaded is SnapshotLoad.Loaded)

        for ((f, text) in untouched) {
            assertTrue("读快照不许碰 ${f.name}", f.exists())
            assertEquals("${f.name} 的内容必须逐字节不变", text, f.readText())
        }
    }

    /**
     * 未知版本 / 损坏的快照触发**显式失效**，且失效**只清快照**。
     *
     * 计划验收第 1 条后半：「损坏/未知版本快照触发明确重新加载，**不制造**『用户没有
     * 安装过 MOD』的事实」。这两个半句在一条用例里一起守：前半是分态结果，
     * 后半是 `discard` 的边界。破坏任一半都红。
     */
    @Test
    fun staleOrCorruptSnapshotIsDiscardedWithoutTouchingTheLedger() {
        val ledger = File(dir, "installed_mods.json").apply { writeText(LEDGER_SAMPLE) }

        // (a) 未知版本
        snapshotFile().writeText("""{"version":99,"savedAt":1,"mods":[],"sourceDirs":[]}""")
        val stale = store().load(emptyList())
        assertTrue("未知版本必须报 StaleVersion，实际是 $stale", stale is SnapshotLoad.StaleVersion)
        assertTrue("显式失效必须成功", store().discard())
        assertFalse("快照文件必须已被删掉", snapshotFile().exists())

        // (b) 损坏
        snapshotFile().writeText("""{"version":1,"savedAt":1,"mods":[""")
        val corrupt = store().load(emptyList())
        assertTrue("半截 JSON 必须报 Corrupt，实际是 $corrupt", corrupt is SnapshotLoad.Corrupt)
        assertTrue(store().discard())
        assertFalse(snapshotFile().exists())

        // 两种失效都不许动账本。
        assertTrue("账本必须原样还在", ledger.exists())
        assertEquals(LEDGER_SAMPLE, ledger.readText())
    }

    /**
     * 冷启动读旧快照 → 保存 → **再启动一次**（换一个 Store 实例）→ 列表仍可恢复。
     *
     * 这是计划验收第 2 条在 **debug 侧**能做到的部分（真 release 侧 NOT_RUN）。
     * 关键点：迁移之后**新写出的文件是规范键名**，而再次读回来仍然一致 ——
     * 也就是「旧文件读得进、新文件写得出、两者都能读回」这条闭环。
     *
     * 破坏它 = 让新格式仍写 `a`/`b`/`c`（此时最后一条「新文件顶层带 version」红），
     * 或让迁移丢字段（中间的一致性断言红）。
     */
    @Test
    fun migrateThenSaveThenRestartRestoresTheSameList() {
        snapshotFile().writeBytes(fixtureBytes())

        val first = store()
        val migrated = (first.load(fixtureSources) as SnapshotLoad.Loaded).entries
        assertEquals(5, migrated.size)

        // 保存回去（此刻写的是新格式）。
        assertTrue(first.commit(first.capture(migrated, fixtureSources), fixtureSources) is SnapshotCommit.Written)

        // 新写出的文件必须是规范键名，不许把 a/b/c 升格成永久契约。
        val text = snapshotFile().readText()
        assertTrue("新文件必须带 version", text.contains("\"version\""))
        assertTrue("新文件必须带 mods", text.contains("\"mods\""))
        assertFalse("新文件顶层不许再出现旧键 \"a\":", text.contains("\"a\":"))

        // 「再启动」：换实例读回，内容逐条一致。
        val restarted = store().load(fixtureSources)
        assertTrue("二次启动必须读回，实际是 $restarted", restarted is SnapshotLoad.Loaded)
        assertEquals("迁移→保存→重启之后条数与内容都不许变", migrated, (restarted as SnapshotLoad.Loaded).entries)
    }

    /**
     * 夹具必须与 JVM 那份**逐字节相同**（同一 sha256）。
     *
     * 两份副本分居两个源集，没人会同时打开它们；靠这条把「悄悄改了一份」变成一次红。
     * 期望的 sha256 是**写死的字面量**（登记在 `fixture-manifest.json` 的
     * `derivedTargets`），不在这里现算出来当期望值。
     */
    @Test
    fun deviceAssetFixtureMatchesTheRegisteredSha256() {
        val bytes = fixtureBytes()
        val actual = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(2965, bytes.size)
        assertEquals(
            "夹具 sha256 必须与 fixture-manifest.json 的 derivedTargets 一致" +
                "（同一份也在 app/src/test/resources/legacy/）",
            "cd468d7b1f0ba92c801df3408d64a9e4a3271e8c00d23d09bbb5bb450c1b57be",
            actual
        )
    }

    private companion object {
        const val LEDGER_SAMPLE = """{"schemaVersion":1,"records":{"uri-a":{"modUri":"uri-a"}}}"""
        const val JOURNAL_SAMPLE = """{"stage":"TARGET_REPLACED","operationId":"op-1"}"""
        const val PREFS_SAMPLE = """<map><string name="use_astc">false</string></map>"""
        const val META_SAMPLE = """{"bundle-x":{"size":123}}"""
    }
}
