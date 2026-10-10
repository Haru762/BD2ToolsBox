package com.bd2toolsbox.feature.mods

import java.io.File
import java.io.IOException

/** 一条游戏 bundle 目录记录：bundle 名、hash 目录名、`__data` 实际字节数。 */
data class BundleEntry(val name: String, val hash: String, val sizeBytes: Long)

/**
 * 一次目录列举的稳定快照 + 分页。
 *
 * 旧协议每取一页都重新遍历整个 `Shared/`：1830 个 bundle、每页 500 条时，
 * 4 页就是 4 次完整枚举。这里把枚举结果按路径缓存在服务进程内：
 * **offset==0 时重建快照**，后续页复用同一份，直到一次列举结束。
 *
 * 快照只保证「同一次列举内稳定」：`offset==0` 代表新一轮列举开始，必然重新枚举，
 * 所以上次列举中断、目录变化后再从 0 开始时不会读到旧快照。调用方必须按 offset
 * 递增顺序取页（生产只有 `ShizukuGameFileAccess` 一个调用方）。
 *
 * 枚举语义与 T19 的 envelope 约定一致：目录不存在 / `listFiles()` 返回 null 都是
 * **失败**（抛 [IOException]），不是「确实为空」；只有真正遍历完才返回（可以为空）。
 *
 * 刻意不依赖 `org.json`：本类是纯 JVM 逻辑，JSON 编码留在 `ShizukuFileService`
 * 那一层，这样分页与快照语义可以在 JVM 单测里直接验证。
 */
class BundleListingSnapshot {
    private var cachedPath: String? = null
    private var cached: List<BundleEntry>? = null

    /**
     * 取 `[offset, offset+limit)` 页。`offset==0` 或路径变化时重建快照。
     *
     * 同步：Binder 调用可能并发，快照读写必须在同一把锁内。
     */
    @Synchronized
    fun page(sharedDir: File, offset: Int, limit: Int): List<BundleEntry> {
        val path = sharedDir.absolutePath
        if (offset == 0 || cachedPath != path || cached == null) {
            cached = enumerate(sharedDir)
            cachedPath = path
        }
        val all = cached!!
        if (limit <= 0 || offset >= all.size) return emptyList()
        return all.subList(offset, minOf(offset + limit, all.size)).toList()
    }

    /** 仅测试/诊断：当前缓存条数；null 表示还没有快照。 */
    @Synchronized
    fun cachedSize(): Int? = cached?.size

    companion object {
        /**
         * 枚举 `Shared/{bundleName}/{hash}/__data`；一个 bundle 只取第一个含 `__data`
         * 的 hash 目录。`sizeBytes` 是 `__data` 实际字节数，供干净检测与 catalog 官方大小比对。
         *
         * 读失败（目录不存在、`listFiles()` 返 null）抛 [IOException]，**不**静默跳过 ——
         * 旧实现用 `?.forEach` 跳过，等于把「这个 bundle 目录读失败」当成「没有内容」。
         */
        fun enumerate(sharedDir: File): List<BundleEntry> {
            if (!sharedDir.isDirectory) {
                throw IOException("Shared directory does not exist: ${sharedDir.absolutePath}")
            }
            val topLevel = sharedDir.listFiles()
                ?: throw IOException("Cannot list shared directory (listFiles returned null): ${sharedDir.absolutePath}")
            val all = ArrayList<BundleEntry>()
            for (bundleDir in topLevel) {
                if (!bundleDir.isDirectory) continue
                val hashDirs = bundleDir.listFiles()
                    ?: throw IOException("Cannot list bundle directory (listFiles returned null): ${bundleDir.absolutePath}")
                for (hashDir in hashDirs) {
                    if (!hashDir.isDirectory) continue
                    val dataFile = File(hashDir, "__data")
                    if (dataFile.isFile) {
                        all.add(BundleEntry(bundleDir.name, hashDir.name, dataFile.length()))
                        break
                    }
                }
            }
            return all
        }
    }
}
