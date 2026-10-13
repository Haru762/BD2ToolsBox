package com.bd2toolsbox.feature.preview

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bd2toolsbox.core.model.CachePurge
import com.bd2toolsbox.core.model.PurgeOutcome
import com.bd2toolsbox.data.repository.PreviewCacheRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * T29：预览/工具流程与缓存清理的契约（设备）。
 *
 * 要守的核心一件事：**清缓存失败不能显示成 0**。
 *
 * 旧实现 `PreviewCacheRepository.clearAll()` 返回 `Unit`（裸
 * `deleteRecursively()`），调用方删完无条件把用量置 `0L to 0` ——
 * 删除失败（权限、文件被占、外部存储卸载）时界面显示「已清空」，
 * 磁盘上却一个字节没少。这与 T44 修过的备份清空是同一类缺陷。
 *
 * 用真实文件系统而不是 mock：删除失败这件事只有真的去删才知道会不会失败。
 */
@RunWith(AndroidJUnit4::class)
class PreviewWorkflowTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var repo: PreviewCacheRepository

    @Before
    fun setUp() {
        repo = PreviewCacheRepository(context)
        // 从干净状态开始：上一次用例留下的条目会让计数断言失去意义
        repo.clearAll()
    }

    @After
    fun tearDown() {
        // 把可能设成只读的目录恢复回来，否则会污染后续用例
        repo.rootForTest().listFiles()?.forEach { it.setWritable(true) }
        repo.clearAll()
    }

    /**
     * 造一个**完整有效**的缓存槽位。
     *
     * `isValid` 要求四件事同时成立：`gen` 戳对、有 `.skel`/`.json`、有 `.atlas`、
     * `.srcsize` 等于传入的 srcSize。少写一样就不是「有效槽位」，
     * 断言会以完全无关的理由失败（实测踩过：把文件叫 `atlas` 而不是 `x.atlas`）。
     */
    private fun seed(bundle: String, hash: String, srcSize: Long, bytes: Int = 32): File {
        val dir = repo.prepareDir(bundle, hash, srcSize)
        File(dir, "gen").writeText("4")            // CACHE_GENERATION
        File(dir, ".srcsize").writeText(srcSize.toString())
        File(dir, "anim.skel").writeBytes(ByteArray(16))
        File(dir, "tex.atlas").writeBytes(ByteArray(bytes))
        repo.commit(bundle, hash, srcSize)
        return dir
    }

    // ---------------------------------------------------------------- 清理结果如实

    @Test
    fun clearingAnEmptyCacheReportsNothingToDo() {
        val outcome = repo.clearAll()
        assertEquals("空缓存不该报「删了 N 项」", 0, outcome.attempted)
        assertEquals(0, outcome.failed)
        assertEquals(0, outcome.remainingCount)
        assertEquals(0L, outcome.remainingBytes)
    }

    @Test
    fun clearingReportsRealRemainingAfterSuccess() {
        seed("bundleA", "hash1", 100)
        seed("bundleB", "hash2", 200)
        assertTrue("前置：应当有内容", repo.count() >= 2)

        val outcome = repo.clearAll()

        assertTrue("应当尝试删掉至少 2 项", outcome.attempted >= 2)
        assertEquals("全部删掉时不该有失败项", 0, outcome.failed)
        assertEquals("剩余条数必须来自重新测量", 0, outcome.remainingCount)
        assertEquals("剩余字节必须来自重新测量", 0L, outcome.remainingBytes)
    }

    @Test
    fun clearResultIsDerivedFromRealDiskStateNotFromAssumptions() {
        // 「不能把未成功删除的空间直接显示为0」的另一半：剩余量必须**来自实际磁盘**。
        // 这里绕开仓储直接往缓存根目录里放一个它不认识的条目 ——
        // 如果实现是「删过了就当 0」，这个条目会让它露馅。
        seed("bundleA", "hash1", 100)
        val root = repo.rootForTest()
        val stray = File(root, "not-a-slot-dir").apply { mkdirs() }
        File(stray, "leftover.bin").writeBytes(ByteArray(4096))

        val outcome = repo.clearAll()

        // 正常路径下它也会被删掉（clearAll 清的是整个根目录），所以这里断言的是
        // 「剩余量等于重新测量的结果」，而不是某个写死的 0。
        assertEquals(
            "remainingBytes 必须等于重新测量出来的占用",
            repo.totalBytes(),
            outcome.remainingBytes
        )
        assertEquals(
            "remainingCount 必须等于重新测量出来的条数",
            repo.count(),
            outcome.remainingCount
        )
    }

    @Test
    fun clearingEverythingLeavesNothingClaimedButPresent() {
        // 反向：真的清干净时，测量结果与返回值一致（不能出现「报 0 但磁盘上还有」）
        seed("bundleA", "hash1", 100)
        seed("bundleB", "hash2", 200)
        val outcome = repo.clearAll()
        assertEquals("磁盘上不该还有条数", 0, repo.count())
        assertEquals("磁盘上不该还有字节", 0L, repo.totalBytes())
        assertEquals(outcome.remainingCount, repo.count())
        assertEquals(outcome.remainingBytes, repo.totalBytes())
        assertEquals(0, outcome.failed)
    }

    // ---------------------------------------------------------------- 结果文案

    @Test
    fun resultTextNeverClaimsSuccessWhenNothingWasDeleted() {
        val allFailed = PurgeOutcome(
            attempted = 3, deleted = 0, failed = 3,
            remainingCount = 3, remainingBytes = 12_345
        )
        val text = CachePurge.resultText(allFailed, "12 KB", "预览缓存")
        assertTrue("必须说明失败：$text", text.contains("失败"))
        assertTrue("必须给出实际占用：$text", text.contains("12 KB"))
        assertFalse("不能出现「已清理」：$text", text.contains("已清理"))

        val partial = PurgeOutcome(
            attempted = 4, deleted = 3, failed = 1,
            remainingCount = 1, remainingBytes = 999
        )
        val partialText = CachePurge.resultText(partial, "999 B", "预览缓存")
        assertTrue("部分成功要说清楚：$partialText", partialText.contains("部分成功"))
        assertTrue(partialText.contains("999 B"))

        val ok = PurgeOutcome(
            attempted = 2, deleted = 2, failed = 0, remainingCount = 0, remainingBytes = 0
        )
        assertFalse("全清干净时不该提示需要处理", CachePurge.needsAttention(ok))
        assertTrue(CachePurge.needsAttention(allFailed))

        val strayBytesOnly = PurgeOutcome(
            attempted = 1, deleted = 1, failed = 0, remainingCount = 0, remainingBytes = 4096
        )
        assertTrue("remainingCount 为 0 但仍有残留字节时也必须提示", CachePurge.needsAttention(strayBytesOnly))
        assertFalse("仍有残留字节时不能报已清理", CachePurge.resultText(strayBytesOnly, "4 KB", "预览缓存").contains("已清理"))
    }

    // ---------------------------------------------------------------- 槽位不串用

    @Test
    fun differentSourceSizesUseDifferentSlots() {
        // 同一角色不同质量/槽位的预览不能串用：目录名必须带上源产物大小
        val a = repo.entryDir("bundleA", "hash1", 1000)
        val b = repo.entryDir("bundleA", "hash1", 2000)
        assertNotEquals("不同源大小必须落到不同槽位", a.absolutePath, b.absolutePath)
    }

    @Test
    fun differentHashDirsUseDifferentSlots() {
        val a = repo.entryDir("bundleA", "hash1", 1000)
        val b = repo.entryDir("bundleA", "hash2", 1000)
        assertNotEquals(a.absolutePath, b.absolutePath)
    }

    @Test
    fun aCommittedSlotIsValidAndStopsBeingValidAfterClear() {
        seed("bundleA", "hash1", 1000)
        assertTrue("提交后应当有效", repo.isValid("bundleA", "hash1", 1000))
        repo.clearAll()
        assertFalse("清掉之后不该还有效", repo.isValid("bundleA", "hash1", 1000))
    }

    // ---------------------------------------------------------------- 与活跃任务互斥

    @Test
    fun clearIsScopedToThePreviewCacheOnly() {
        // 「按范围执行」：清预览缓存不能碰到别的目录。
        // 这里用一个同级的哨兵目录来验证 —— 它必须活着。
        val external = context.getExternalFilesDir(null)!!
        val sentinel = File(external, "t29-sentinel")
        sentinel.mkdirs()
        File(sentinel, "keep.txt").writeText("keep")
        try {
            seed("bundleA", "hash1", 100)
            repo.clearAll()
            assertTrue("清预览缓存不该碰同级目录", sentinel.isDirectory)
            assertTrue("哨兵文件必须还在", File(sentinel, "keep.txt").isFile)
        } finally {
            sentinel.deleteRecursively()
        }
    }
}
