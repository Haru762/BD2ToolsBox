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
}
