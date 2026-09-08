package com.bd2toolsbox.data.repository

import android.content.Context
import java.io.File

/**
 * 已解包素材的持久缓存。
 *
 * 预览一个已转换产物要先把二进制 `__data` 解包成 skel/atlas/png，实测约 3.4 秒（还是在
 * Python 已启动的情况下）。以前每次预览都重跑一遍、看完就删，同一个 mod 反复看就反复等。
 *
 * 这里把解包结果留下来，于是：
 *   - 第二次预览同一个 mod 几乎瞬间打开（只是读文件）
 *   - 「批量预解包」本质上就是提前把这个缓存填满，之后逐个看都不用等
 *
 * 缓存键是 `<bundle 名>/<内容哈希>`，与游戏缓存的目录结构对齐 ——
 * 游戏更新后哈希会变（实测跨版本 176 个 bundle 哈希全变），旧缓存靠路径天然失效，
 * 不会把上个版本的素材拿来当这个版本的用。
 *
 * 另外记一份 `.srcsize` 存产物 `__data` 的字节数：用户可能用同名目录换了新产物，
 * 这时哈希目录名可能没变但内容变了，靠大小对比能发现并重解。
 *
 * 放外部私有目录（`/sdcard/Android/data/<包名>/files/preview_cache/`），
 * 和原版备份一致 —— 用户看得见、能自己清，卸载 app 时一起消失。
 */
class PreviewCacheRepository(private val context: Context) {

    companion object {
        private const val DIR_NAME = "preview_cache"
        private const val SRC_SIZE_FILE = ".srcsize"
    }

    private fun root(): File = File(context.getExternalFilesDir(null), DIR_NAME)

    fun entryDir(bundleName: String, hashDir: String): File =
        File(root(), "$bundleName/$hashDir")

    /**
     * 缓存是否可用。
     *
     * 要求目录里既有骨架（.skel 或 .json）又有 .atlas —— 少一个都没法预览。
     * 同时校验 [srcSize] 与当初解包时记录的一致，防止用户换了产物却沿用旧素材。
     */
    fun isValid(bundleName: String, hashDir: String, srcSize: Long): Boolean {
        val dir = entryDir(bundleName, hashDir)
        if (!dir.isDirectory) return false
        val files = dir.listFiles()?.filter { it.isFile } ?: return false
        val hasSkel = files.any { it.name.endsWith(".skel", true) || it.name.endsWith(".json", true) }
        val hasAtlas = files.any { it.name.endsWith(".atlas", true) }
        if (!hasSkel || !hasAtlas) return false
        return readSrcSize(bundleName, hashDir) == srcSize
    }

    /** 缓存里的 skel 与 atlas 路径；不完整则返回 null。 */
    fun resolvePair(bundleName: String, hashDir: String): Pair<String, String>? {
        val files = entryDir(bundleName, hashDir).listFiles()?.filter { it.isFile } ?: return null
        val skel = files.firstOrNull { it.name.endsWith(".skel", true) }
            ?: files.firstOrNull { it.name.endsWith(".json", true) }
        val atlas = files.firstOrNull { it.name.endsWith(".atlas", true) }
        if (skel == null || atlas == null) return null
        return skel.absolutePath to atlas.absolutePath
    }

    /** 解包前调用：清空目标目录并返回它，让解包结果直接落在缓存位置。 */
    fun prepareDir(bundleName: String, hashDir: String): File {
        val dir = entryDir(bundleName, hashDir)
        if (dir.exists()) dir.deleteRecursively()
        dir.mkdirs()
        return dir
    }

    /** 解包成功后记录源产物大小，作为后续校验依据。 */
    fun commit(bundleName: String, hashDir: String, srcSize: Long) {
        try {
            File(entryDir(bundleName, hashDir), SRC_SIZE_FILE).writeText(srcSize.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun readSrcSize(bundleName: String, hashDir: String): Long =
        try {
            File(entryDir(bundleName, hashDir), SRC_SIZE_FILE)
                .takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull() ?: -1L
        } catch (e: Exception) {
            -1L
        }

    /** 已缓存的条目数（bundle 数，不含内部文件）。 */
    fun count(): Int {
        var n = 0
        root().listFiles()?.forEach { bundleDir ->
            bundleDir.listFiles()?.forEach { hashDir ->
                if (hashDir.isDirectory) n++
            }
        }
        return n
    }

    fun totalBytes(): Long {
        var sum = 0L
        root().walkBottomUp().forEach { if (it.isFile) sum += it.length() }
        return sum
    }

    /**
     * 解包后素材相对源 `__data` 的膨胀倍数，用于预估「全部解包」需要多少空间。
     *
     * 用已有缓存实测出来的比值，比拍一个固定系数靠谱。样本不足时返回 null，
     * 由调用方决定要不要用一个保守的默认值。
     */
    fun measuredExpansionRatio(): Double? {
        var src = 0L
        var out = 0L
        root().listFiles()?.forEach { bundleDir ->
            bundleDir.listFiles()?.forEach { hashDir ->
                if (!hashDir.isDirectory) return@forEach
                val s = File(hashDir, SRC_SIZE_FILE).takeIf { it.isFile }
                    ?.readText()?.trim()?.toLongOrNull() ?: return@forEach
                if (s <= 0) return@forEach
                val o = hashDir.listFiles()?.filter { it.isFile && it.name != SRC_SIZE_FILE }
                    ?.sumOf { it.length() } ?: 0L
                if (o <= 0) return@forEach
                src += s
                out += o
            }
        }
        return if (src > 0) out.toDouble() / src else null
    }

    fun delete(bundleName: String, hashDir: String) {
        entryDir(bundleName, hashDir).deleteRecursively()
    }

    fun clearAll() {
        root().deleteRecursively()
    }
}
