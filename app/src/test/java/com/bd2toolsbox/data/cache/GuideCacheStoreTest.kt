package com.bd2toolsbox.data.cache

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * T30：拆出来的磁盘缓存层的契约（JVM，真文件 + 临时目录）。
 *
 * 拆出前这些语义散在 1415 行的 `GamekeeRepository` 里，只有「跑一遍攻略页」才能碰到；
 * 现在可以单独钉住：目录布局、`.part` 原子写、占用统计排除残片、清空。
 */
class GuideCacheStoreTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var store: GuideCacheStore

    @Before
    fun setup() {
        root = temp.newFolder("guides")
        val context = mockk<Context>(relaxed = true)
        every { context.externalCacheDir } returns root.parentFile
        store = GuideCacheStore(context)
        // store.root 是 <externalCacheDir>/guides，正好是 temp 下那个目录
        assertEquals(root.absolutePath, store.root.absolutePath)
    }

    @After
    fun teardown() = unmockkAll()

    @Test
    fun layoutMatchesWhatTheOldCodeWrote() {
        // 目录名与拆分前逐字相同 —— 改了会让所有老缓存失效
        assertEquals(File(root, "tree.json").absolutePath, store.treeFile.absolutePath)
        assertEquals(File(root, "articles").absolutePath, store.articleDir.absolutePath)
        assertEquals(File(root, "thumbs").absolutePath, store.thumbDir.absolutePath)
        assertEquals(File(root, "battles").absolutePath, store.battleDir.absolutePath)
        assertEquals(File(root, "emoji.json").absolutePath, store.emojiFile.absolutePath)
        assertEquals(File(root, "articles/697421.html").absolutePath, store.articleFile(697421L).absolutePath)
        assertEquals(File(root, "battles/v2_9.html").absolutePath, store.battleFile("v2_9").absolutePath)
        assertEquals(File(root, "thumbs/12.img").absolutePath, store.thumbFile("12.img").absolutePath)
    }

    @Test
    fun atomicWriteCreatesTheFileAndLeavesNoPartResidue() {
        val target = store.articleFile(1L)
        assertTrue(store.writeAtomic(target, "<html>x</html>"))
        assertEquals("<html>x</html>", target.readText())
        assertFalse("改名之后不许留下 .part", File(store.articleDir, "1.html.part").exists())
    }

    @Test
    fun atomicWriteCreatesMissingParentDirectories() {
        assertFalse(store.battleDir.exists())
        assertTrue(store.writeAtomic(store.battleFile("v3_7"), "<html>y</html>"))
        assertTrue(store.battleDir.isDirectory)
    }

    @Test
    fun usageCountsBytesAndFilesButSkipsPartResidue() {
        store.writeAtomic(store.articleFile(1L), "12345")
        store.writeAtomic(store.articleFile(2L), "123")
        // 模拟一份写到一半的残片
        File(store.articleDir, "3.html.part").writeText("9999999")

        val (bytes, count) = store.usage()
        assertEquals("残片不计入文件数", 2, count)
        assertEquals("残片不计入字节数", 8L, bytes)
    }

    @Test
    fun clearRemovesEverythingUnderRoot() {
        store.writeAtomic(store.treeFile, "{}")
        store.writeAtomic(store.articleFile(1L), "x")
        store.writeAtomic(store.emojiFile, "{}")
        assertTrue(store.usage().second > 0)

        store.clear()

        assertFalse("根目录应被整体删除", root.exists())
        assertEquals(0L to 0, store.usage())
    }

    @Test
    fun emptyCacheReportsZeroRatherThanThrowing() {
        // 一次都没用过时 root 还不存在：不能抛，要如实报 0
        assertEquals(0L to 0, store.usage())
    }
}
