package com.bd2toolsbox.data.repository

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * 预览缓存的负缓存（无预览产物标记）契约。
 *
 * 真机 bug：约 20 个无 skel/atlas 的产物（立绘/UI 类）永远不满足 isValid，
 * 预解包每次都从头重解它们 —— 用户看到「总是回到开始那二十几个包」。
 * markNoPreview 之后 isKnownNoPreview 必须认账，让预解包的目标筛选跳过。
 */
@RunWith(AndroidJUnit4::class)
class PreviewCacheNegativeTest {
    private lateinit var repo: PreviewCacheRepository
    private lateinit var dir: File

    @Before
    fun setup() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        dir = File(base.cacheDir, "preview-neg-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getExternalFilesDir(type: String?): File? = dir
        }
        repo = PreviewCacheRepository(context)
    }

    @Test
    fun unmarkedTargetIsNotKnownNoPreview() {
        assertFalse(repo.isKnownNoPreview("bundleA", "hashA", 100L))
    }

    @Test
    fun markNoPreviewMakesTargetKnown() {
        repo.markNoPreview("bundleA", "hashA", 100L)
        assertTrue(repo.isKnownNoPreview("bundleA", "hashA", 100L))
    }

    @Test
    fun sizeChangeInvalidatesNegativeMark() {
        repo.markNoPreview("bundleA", "hashA", 100L)
        // 产物换了（重新转换后 size 变）必须重解，不能拿旧结论糊弄
        assertFalse(repo.isKnownNoPreview("bundleA", "hashA", 200L))
    }

    @Test
    fun negativeMarkIsNotValidPreview() {
        // 负缓存不是「有效预览」：预览界面的查询不该把它当有图
        repo.markNoPreview("bundleA", "hashA", 100L)
        assertFalse(repo.isValid("bundleA", "hashA", 100L))
    }

    @Test
    fun deleteClearsNegativeMark() {
        repo.markNoPreview("bundleA", "hashA", 100L)
        repo.delete("bundleA", "hashA")
        assertFalse(repo.isKnownNoPreview("bundleA", "hashA", 100L))
    }
}
