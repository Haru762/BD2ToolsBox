package com.bd2toolsbox.data.repository

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
 * 预览缓存按 size 分槽 —— 真机缺陷的回归。
 *
 * 用户库里 836 个产物挤在 155 个 (bundle, hashDir) 槽里，同槽的变体
 * （同一张图的不同内容，size 各异）互相覆盖缓存：预解包全部白干，
 * 预览每个变体都要现场重解（「点预览还是要准备预览」）。
 * 修复：缓存目录带上 size 段，每个变体一个独立槽。
 */
@RunWith(AndroidJUnit4::class)
class PreviewCacheSizeSlotTest {
    private lateinit var repo: PreviewCacheRepository
    private lateinit var dir: File

    @Before
    fun setup() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        dir = File(base.cacheDir, "preview-slot-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getExternalFilesDir(type: String?): File? = dir
        }
        repo = PreviewCacheRepository(context)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** 造一个已提交的缓存槽（skel + atlas + gen + size 戳）。 */
    private fun commitSlot(bundle: String, hash: String, size: Long) {
        val d = repo.prepareDir(bundle, hash, size)
        File(d, "x.skel").writeText("skel")
        File(d, "x.atlas").writeText("atlas")
        repo.commit(bundle, hash, size)
    }

    @Test
    fun variantsOfSameBundleGetIndependentSlots() {
        // 同 bundle 同 hashDir、不同 size 的两个变体
        commitSlot("bundleA", "hashA", 100L)
        commitSlot("bundleA", "hashA", 200L)

        // 两个变体各自命中自己的缓存，互不覆盖
        assertTrue("变体A(size=100) 的缓存必须有效",
            repo.isValid("bundleA", "hashA", 100L))
        assertTrue("变体B(size=200) 的缓存必须有效",
            repo.isValid("bundleA", "hashA", 200L))

        // resolvePair 各自取到自己的文件（不同槽不同内容）
        val pairA = repo.resolvePair("bundleA", "hashA", 100L)
        val pairB = repo.resolvePair("bundleA", "hashA", 200L)
        assertTrue(pairA != null && pairB != null)
        assertTrue("两个变体必须是不同目录的文件",
            pairA!!.first != pairB!!.first)
    }

    @Test
    fun committedVariantSurvivesAnotherCommit() {
        commitSlot("bundleA", "hashA", 100L)
        commitSlot("bundleA", "hashA", 200L)
        // 先提交的变体在后来者提交后依然有效 —— 修复前会被覆盖
        assertTrue(repo.isValid("bundleA", "hashA", 100L))
    }

    @Test
    fun deleteOnlyRemovesThatVariant() {
        commitSlot("bundleA", "hashA", 100L)
        commitSlot("bundleA", "hashA", 200L)
        repo.delete("bundleA", "hashA", 100L)
        assertFalse(repo.isValid("bundleA", "hashA", 100L))
        assertTrue("删变体A不该连坐变体B",
            repo.isValid("bundleA", "hashA", 200L))
    }

    @Test
    fun invalidSizeStillMisses() {
        commitSlot("bundleA", "hashA", 100L)
        // 不存在的变体照旧 miss（触发现场解包）
        assertFalse(repo.isValid("bundleA", "hashA", 999L))
    }

    @Test
    fun negativeCacheIsAlsoPerSize() {
        repo.markNoPreview("bundleA", "hashA", 100L)
        assertTrue(repo.isKnownNoPreview("bundleA", "hashA", 100L))
        // 变体B没标记过——负缓存不能误伤它
        assertFalse(repo.isKnownNoPreview("bundleA", "hashA", 200L))
    }

    @Test
    fun countCountsVariantSlots() {
        commitSlot("bundleA", "hashA", 100L)
        commitSlot("bundleA", "hashA", 200L)
        commitSlot("bundleB", "hashB", 100L)
        // 三个槽三个条目（修复前同槽只算一个）
        assertEquals(3, repo.count())
    }
}
