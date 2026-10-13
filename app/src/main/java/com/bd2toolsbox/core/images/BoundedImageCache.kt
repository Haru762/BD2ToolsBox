package com.bd2toolsbox.core.images

import androidx.compose.ui.graphics.ImageBitmap

/**
 * 有上限的解码图 LRU 缓存（T34）。
 *
 * ## 为什么按**字节**而不是按条数计上限
 *
 * 一张 256×256 的缩略图约 256 KB，一张全尺寸立绘可以到 4 MB —— 差 16 倍。
 * 按条数设上限（比如「最多 200 张」）在缩略图上刚好，在立绘上就是 800 MB。
 * 所以这里按 [ImageBitmap] 的实际像素字节数累加。
 *
 * ## 为什么需要它
 *
 * 替换之前那两个**无上限**的 `ConcurrentHashMap<String, ImageBitmap>`：
 * 滑一遍长列表就把每一张解码图永久留在内存里，直到用户手动清缓存。
 * 那是实打实的内存泄漏，不是「缓存策略不够优」。
 *
 * ## 线程安全
 *
 * 读写在同一把锁内。缓存命中是热路径，所以用 [LinkedHashMap] 的访问序 +
 * 单锁 —— 比 ConcurrentHashMap + 手工 LRU 链表简单得多，而这个量级的竞争
 * （几十次/秒）根本不构成瓶颈。
 *
 * [maxBytes] <= 0 表示**不缓存**（只用来在测试里关掉缓存），不是「无上限」——
 * 没有「无上限」这个选项，那正是要修的东西。
 */
class BoundedImageCache(private val maxBytes: Long) {

    private val lock = Any()
    private val entries = LinkedHashMap<String, ImageBitmap>(16, 0.75f, /* accessOrder = */ true)
    private var bytes = 0L

    /** 命中就返回并把该条挪到队尾（最近使用）。 */
    fun get(key: String): ImageBitmap? = synchronized(lock) {
        entries[key]
    }

    fun put(key: String, value: ImageBitmap) {
        if (maxBytes <= 0) return
        val size = sizeOf(value)
        if (size > maxBytes) {
            // 单张就超过总预算：不缓存它，否则会把整个缓存挤空。
            // 图仍然能用（调用方拿得到），只是每次都要重新解码。
            synchronized(lock) {
                entries.remove(key)?.let { bytes -= sizeOf(it) }
            }
            return
        }
        synchronized(lock) {
            entries.remove(key)?.let { bytes -= sizeOf(it) }
            entries[key] = value
            bytes += size
            trimLocked()
        }
    }

    fun clear() = synchronized(lock) {
        entries.clear()
        bytes = 0L
    }

    /** 当前占用的估算字节数（见 [sizeOf] 的说明）。 */
    fun sizeBytes(): Long = synchronized(lock) { bytes }

    fun size(): Int = synchronized(lock) { entries.size }

    fun contains(key: String): Boolean = synchronized(lock) { entries.containsKey(key) }

    private fun trimLocked() {
        val it = entries.entries.iterator()
        while (bytes > maxBytes && it.hasNext()) {
            val e = it.next()
            bytes -= sizeOf(e.value)
            it.remove()
        }
    }

    companion object {
        /**
         * 一张 [ImageBitmap] 的估算字节数。
         *
         * 用 `宽 × 高 × 4`（ARGB_8888）—— Compose 的 [ImageBitmap] 没有暴露底层
         * 分配大小，而 Android 上这些位图实际就是这个格式。估算偏保守（可能略大）
         * 对缓存上限来说是对的方向：宁可少缓存几张，也不要算小了撑爆内存。
         */
        fun sizeOf(bitmap: ImageBitmap): Long =
            bitmap.width.toLong() * bitmap.height.toLong() * 4L
    }
}

/**
 * 有上限的字符串 LRU 缓存（T34）。
 *
 * 用来替掉同样无上限的 HTML 正文缓存：一篇攻略正文几十 KB，滑一遍列表
 * 同样是只增不减。
 */
class BoundedStringCache(
    private val maxBytes: Long,
    private val weigher: (String) -> Long = { it.length.toLong() * 2L }
) {
    private val lock = Any()
    private val entries = LinkedHashMap<Any, String>(16, 0.75f, true)
    private var bytes = 0L

    fun get(key: Any): String? = synchronized(lock) { entries[key] }

    fun put(key: Any, value: String) {
        if (maxBytes <= 0) return
        val size = weigher(value)
        if (size > maxBytes) {
            synchronized(lock) { entries.remove(key)?.let { bytes -= weigher(it) } }
            return
        }
        synchronized(lock) {
            entries.remove(key)?.let { bytes -= weigher(it) }
            entries[key] = value
            bytes += size
            val it = entries.entries.iterator()
            while (bytes > maxBytes && it.hasNext()) {
                val e = it.next()
                bytes -= weigher(e.value)
                it.remove()
            }
        }
    }

    fun clear() = synchronized(lock) {
        entries.clear()
        bytes = 0L
    }

    fun size(): Int = synchronized(lock) { entries.size }

    fun sizeBytes(): Long = synchronized(lock) { bytes }

    fun contains(key: Any): Boolean = synchronized(lock) { entries.containsKey(key) }
}
