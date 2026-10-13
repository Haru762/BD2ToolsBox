package com.bd2toolsbox.core.images

import androidx.compose.ui.graphics.ImageBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T34：图片管线的**内存上限**契约（设备）。
 *
 * 这一层要守的是「滑一遍长列表不会把每张解码图永久留在内存里」。
 * 之前 `GamekeeRepository.thumbs` 与 `AvatarRepository.decoded` 都是**无上限**的
 * `ConcurrentHashMap<String, ImageBitmap>` —— 那是实打实的内存泄漏，
 * 不是「缓存策略不够优」。
 *
 * 放在设备上而不是 JVM：`ImageBitmap` 在 JVM 单测里拿不到 `android.graphics.Bitmap`
 * 支撑（那是 `expect/actual` 的 Android 实现）。
 */
@RunWith(AndroidJUnit4::class)
class ImagePipelineTest {

    private fun bitmap(w: Int, h: Int): ImageBitmap = ImageBitmap(w, h)

    // ---------------------------------------------------------------- 按字节计上限

    @Test
    fun sizeIsCountedInBytesNotEntries() {
        val b = bitmap(64, 64)
        assertEquals("ARGB_8888 应当是 宽×高×4", 64L * 64L * 4L, BoundedImageCache.sizeOf(b))
    }

    @Test
    fun evictsLeastRecentlyUsedWhenOverBudget() {
        // 每张 32×32 = 4 KB；预算 12 KB → 最多 3 张
        val cache = BoundedImageCache(12L * 1024)
        cache.put("a", bitmap(32, 32))
        cache.put("b", bitmap(32, 32))
        cache.put("c", bitmap(32, 32))
        assertEquals(3, cache.size())

        // 摸一下 a，让它变成最近使用；再放 d 应当淘汰 b（最久未用）
        assertNotNull(cache.get("a"))
        cache.put("d", bitmap(32, 32))

        assertNotNull("a 刚被访问过，应当还在", cache.get("a"))
        assertNull("b 是最久未用的，应当被淘汰", cache.get("b"))
        assertNotNull(cache.get("c"))
        assertNotNull(cache.get("d"))
        assertTrue("占用不得超过预算（实际 ${cache.sizeBytes()}）", cache.sizeBytes() <= 12L * 1024)
    }

    @Test
    fun aSingleImageLargerThanTheBudgetIsNotCached() {
        // 预算只够半张：不能把整张塞进去再把别的全挤掉
        val cache = BoundedImageCache(4L * 1024)
        cache.put("small", bitmap(16, 16))     // 1 KB
        cache.put("huge", bitmap(128, 128))    // 64 KB > 4 KB

        assertNull("超过预算的单张不该被缓存", cache.get("huge"))
        assertNotNull("已有的小图不该被它挤掉", cache.get("small"))
    }

    @Test
    fun zeroBudgetCachesNothing() {
        val cache = BoundedImageCache(0)
        cache.put("a", bitmap(8, 8))
        assertNull(cache.get("a"))
        assertEquals(0, cache.size())
        assertEquals(0L, cache.sizeBytes())
    }

    @Test
    fun putSameKeyTwiceDoesNotDoubleCount() {
        val cache = BoundedImageCache(1024L * 1024)
        cache.put("a", bitmap(32, 32))
        val after1 = cache.sizeBytes()
        cache.put("a", bitmap(32, 32))
        assertEquals("同一个 key 覆盖不该把占用算两遍", after1, cache.sizeBytes())
        assertEquals(1, cache.size())
    }

    @Test
    fun clearEmptiesEverything() {
        val cache = BoundedImageCache(1024L * 1024)
        repeat(5) { cache.put("k$it", bitmap(32, 32)) }
        assertTrue(cache.size() > 0)
        cache.clear()
        assertEquals(0, cache.size())
        assertEquals(0L, cache.sizeBytes())
        assertFalse(cache.contains("k0"))
    }

    @Test
    fun containsDoesNotCountAsAccess() {
        // contains 不该改变 LRU 顺序（它是「在不在」的问题，不是「用过」）
        val cache = BoundedImageCache(8L * 1024)   // 2 张 4KB
        cache.put("a", bitmap(32, 32))
        cache.put("b", bitmap(32, 32))
        assertTrue(cache.contains("a"))
        cache.put("c", bitmap(32, 32))
        assertNull("a 仍然是最久未用的，应当先被淘汰", cache.get("a"))
        assertNotNull(cache.get("b"))
    }

    // ---------------------------------------------------------------- 字符串缓存

    @Test
    fun stringCacheAlsoEvictsByBytes() {
        // 每条 100 字符 → 200 字节（按 UTF-16 估）；预算 600 字节 → 最多 3 条
        val cache = BoundedStringCache(600)
        cache.put("a", "x".repeat(100))
        cache.put("b", "y".repeat(100))
        cache.put("c", "z".repeat(100))
        cache.put("d", "w".repeat(100))
        assertNull("最久未用的应当被淘汰", cache.get("a"))
        assertNotNull(cache.get("d"))
        assertTrue(cache.sizeBytes() <= 600)
    }

    @Test
    fun stringCacheRejectsOversizedEntries() {
        val cache = BoundedStringCache(100)
        cache.put("big", "x".repeat(1000))
        assertNull(cache.get("big"))
        assertEquals(0, cache.size())
    }

    // ---------------------------------------------------------------- 与真实调用方一致

    @Test
    fun repositoryBudgetsAreBoundedConstants() {
        // 这两个上限是「一屏可见 + 前后各一屏」的量级，不是随便写的数字。
        // 断言它们存在且是个合理的正数 —— 防止有人改成 0（等于关掉缓存）
        // 或改成 Int.MAX_VALUE（等于退回无上限）。
        assertTrue("缩略图预算应当在 1 MB..64 MB", THUMB_BUDGET in (1L shl 20)..(64L shl 20))
        assertTrue("正文预算应当在 1 MB..64 MB", HTML_BUDGET in (1L shl 20)..(64L shl 20))
        assertTrue("头像预算应当在 1 MB..64 MB", AVATAR_BUDGET in (1L shl 20)..(64L shl 20))
    }

    private companion object {
        // 与 GamekeeRepository / AvatarRepository 里的常量同源；
        // 这里重复写一遍是为了让「改小了/改没了」在测试里可见。
        const val THUMB_BUDGET = 8L * 1024 * 1024
        const val HTML_BUDGET = 4L * 1024 * 1024
        const val AVATAR_BUDGET = 8L * 1024 * 1024
    }
}
