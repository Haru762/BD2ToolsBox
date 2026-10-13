package com.bd2toolsbox.core.model

import android.content.Context
import com.bd2toolsbox.data.repository.PreviewCacheRepository
import com.bd2toolsbox.data.repository.SpineRuntimeRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CachePurgeAndRepositoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun cachePurgeReportsStrayBytesEvenWhenRemainingSlotCountIsZero() {
        val outcome = PurgeOutcome(
            attempted = 2,
            deleted = 2,
            failed = 0,
            remainingCount = 0,
            remainingBytes = 2048L
        )
        assertTrue(
            "remainingCount 为 0 但仍有残留字节时必须提示用户",
            CachePurge.needsAttention(outcome)
        )
        val text = CachePurge.resultText(outcome, "2 KB", "预览缓存")
        assertFalse("仍有残留字节时不得显示『已清理』：$text", text.contains("已清理"))
        assertTrue("应包含实际剩余体积：$text", text.contains("2 KB"))
    }

    @Test
    fun previewCacheClearAllCountsSlotsConsistentWithCount() {
        val extFiles = temp.newFolder("ext-files")
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.getExternalFilesDir(null) } returns extFiles
        val repo = PreviewCacheRepository(ctx)

        // 同一个 bundle 下有两个不同源大小的变体槽位
        val slot1 = repo.prepareDir("bundleA", "hash1", 100L)
        File(slot1, "a.skel").writeBytes(ByteArray(32))
        val slot2 = repo.prepareDir("bundleA", "hash1", 200L)
        File(slot2, "b.skel").writeBytes(ByteArray(64))

        assertEquals("清理前应有 2 个槽位", 2, repo.count())
        val outcome = repo.clearAll()
        assertEquals("attempted 必须按槽位计数（与 count() 口径一致）", 2, outcome.attempted)
        assertEquals(2, outcome.deleted)
        assertEquals(0, outcome.failed)
        assertEquals(0, outcome.remainingCount)
        assertEquals(0L, outcome.remainingBytes)
    }

    @Test
    fun spineRuntimeClearMeasuresAllRemainingFilesInDirectory() {
        val filesDir = temp.newFolder("files")
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.filesDir } returns filesDir
        val repo = SpineRuntimeRepository(ctx)

        val dir = repo.file.parentFile!!.apply { mkdirs() }
        File(dir, "pixi-spine-3.1.2.js").writeBytes(ByteArray(128))
        File(dir, "pixi-spine-3.1.2.js.part").writeBytes(ByteArray(64))

        val outcome = repo.clear()
        assertEquals(2, outcome.attempted)
        assertEquals(2, outcome.deleted)
        assertEquals(0, outcome.remainingCount)
        assertEquals(0L, outcome.remainingBytes)
    }
}
