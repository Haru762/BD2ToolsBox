package com.bd2toolsbox.feature.mods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** 纯 JVM：真目录树，验证分页快照不重扫、不遗漏、不重复。 */
class BundleListingSnapshotTest {
    @get:Rule val temp = TemporaryFolder()

    private fun bundle(root: File, name: String, hash: String, bytes: Int = 8) {
        val dir = File(root, "$name/$hash")
        dir.mkdirs()
        File(dir, "__data").writeBytes(ByteArray(bytes))
    }

    @Test fun enumeratesOneEntryPerBundleAndRecordsSize() {
        val shared = temp.newFolder("Shared")
        bundle(shared, "a", "hashA", bytes = 11)
        bundle(shared, "b", "hashB", bytes = 3)
        val all = BundleListingSnapshot.enumerate(shared)
        assertEquals(setOf("a", "b"), all.map { it.name }.toSet())
        val a = all.first { it.name == "a" }
        assertEquals("hashA", a.hash)
        assertEquals(11L, a.sizeBytes)
    }

    @Test fun usesFirstHashDirectoryContainingData() {
        val shared = temp.newFolder("Shared")
        File(shared, "a/emptyHash").mkdirs()          // 无 __data，应被跳过
        bundle(shared, "a", "realHash")
        val all = BundleListingSnapshot.enumerate(shared)
        assertEquals(1, all.size)
        assertEquals("realHash", all.single().hash)
    }

    @Test fun pagesCoverTheWholeListWithoutOverlapOrOmission() {
        val shared = temp.newFolder("Shared")
        repeat(7) { bundle(shared, "b$it", "h$it") }
        val snapshot = BundleListingSnapshot()
        val collected = mutableListOf<String>()
        var offset = 0
        val limit = 3
        while (true) {
            val page = snapshot.page(shared, offset, limit)
            collected += page.map { it.name }
            if (page.size < limit) break
            offset += limit
        }
        assertEquals(7, collected.size)
        assertEquals((0 until 7).map { "b$it" }.toSet(), collected.toSet())
        assertEquals(collected.size, collected.toSet().size)  // 无重复
    }

    @Test fun offsetZeroRebuildsSnapshotWhileLaterPagesReuseIt() {
        val shared = temp.newFolder("Shared")
        bundle(shared, "a", "ha")
        val snapshot = BundleListingSnapshot()

        assertEquals(listOf("a"), snapshot.page(shared, 0, 10).map { it.name })
        assertEquals(1, snapshot.cachedSize())

        // 目录变化：新增一个 bundle，但**不**从 offset 0 重取 —— 后续页必须复用旧快照。
        bundle(shared, "b", "hb")
        assertEquals(0, snapshot.page(shared, 1, 10).size)   // 旧快照只有 1 条
        assertEquals(1, snapshot.cachedSize())               // 证明没有重扫

        // 新一轮列举从 offset 0 开始，才重建快照看到新 bundle。
        assertEquals(setOf("a", "b"), snapshot.page(shared, 0, 10).map { it.name }.toSet())
        assertEquals(2, snapshot.cachedSize())
    }

    @Test fun missingDirectoryFailsInsteadOfReturningEmpty() {
        val missing = File(temp.root, "does-not-exist")
        assertThrows(IOException::class.java) { BundleListingSnapshot.enumerate(missing) }
    }

    @Test fun emptyDirectoryIsAValidEmptySnapshot() {
        val shared = temp.newFolder("Shared")
        assertEquals(0, BundleListingSnapshot.enumerate(shared).size)
        assertEquals(0, BundleListingSnapshot().page(shared, 0, 10).size)
    }

    @Test fun zeroLimitYieldsEmptyPageWithoutSpinning() {
        val shared = temp.newFolder("Shared")
        bundle(shared, "a", "ha")
        assertTrue(BundleListingSnapshot().page(shared, 0, 0).isEmpty())
    }

    // ---- T59：目录枚举次数（性能回归的可判定面） ----

    /**
     * 计数的 [File]：`listFiles()` 被调几次都记下来，返回真实结果。
     *
     * `java.io.File` 不是 final、`listFiles()` 可覆盖，所以不用 mock 框架就能
     * 在**真实目录树**上数调用次数 —— 数的不是「我以为会调几次」，是实际调用。
     *
     * **计数器必须共享**：`listFiles()` 每次返回**新**实例，把计数放在实例字段里
     * 只会数到根目录那一次（实测踩过：断言期望 7、实际 1）。
     */
    private class CountingDir private constructor(path: String, private val counter: IntArray) :
        File(path) {
        constructor(path: File) : this(path.absolutePath, IntArray(1))

        val listCalls: Int get() = counter[0]

        override fun listFiles(): Array<File>? {
            counter[0]++
            return super.listFiles()?.map { CountingDir(it.absolutePath, counter) }?.toTypedArray()
        }
    }

    @Test fun eachDirectoryIsEnumeratedExactlyOnce() {
        val shared = temp.newFolder("Shared")
        repeat(6) { i -> bundle(shared, "bundle$i", "hash$i") }
        val counting = CountingDir(shared)

        val all = BundleListingSnapshot.enumerate(counting)

        assertEquals("6 个 bundle 必须都读到", 6, all.size)
        // 顶层 1 次 + 每个 bundle 目录 1 次；多了就是重复枚举，少了就是漏了
        assertEquals("每个目录只该枚举一次", 7, counting.listCalls)
    }

    @Test fun aBundleWithSeveralHashDirsStopsAtTheFirstWithData() {
        val shared = temp.newFolder("Shared")
        // 三个 hash 目录，只有第二个有 __data
        File(shared, "b/h1").mkdirs()
        File(shared, "b/h2").mkdirs()
        File(shared, "b/h3").mkdirs()
        File(File(shared, "b/h2"), "__data").writeBytes(ByteArray(5))
        val counting = CountingDir(shared)

        val all = BundleListingSnapshot.enumerate(counting)

        assertEquals(1, all.size)
        assertEquals("h2", all.single().hash)
        // 顶层 + b 目录；命中后立刻 break，**不该**再进 h3
        assertEquals("命中后必须停止枚举", 2, counting.listCalls)
    }

    @Test fun pagingDoesNotEnumerateTheTreeAgain() {
        val shared = temp.newFolder("Shared")
        repeat(5) { i -> bundle(shared, "bundle$i", "hash$i") }
        val counting = CountingDir(shared)

        // 第一页（offset=0）建快照，后续页必须复用
        BundleListingSnapshot().apply {
            page(counting, 0, 2)
            val afterFirst = counting.listCalls
            page(counting, 2, 2)
            page(counting, 4, 2)
            assertEquals("后续分页不该再枚举目录树", afterFirst, counting.listCalls)
        }
    }
}
