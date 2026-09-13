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
 * 缓存键是 `<bundle 名>/<hash>_<size>`，**size 参与路径**：同一个 bundle 常有
 * 多个不同内容的变体拷贝（实测一个包 44 份、size 各异），此前键里没有 size，
 * 变体们挤一个槽互相覆盖 —— 预解包全部白干、预览每个变体都现场重解
 * （真机：「点预览还是要准备预览」）。带 size 后每个变体独立一个槽。
 * 代价是缓存条目数 = 产物数（不再去重），空间占用按变体数放大 —— 用户
 * 明确接受（测试库 836 产物 ≈ 17GB；正常库几十个 mod 远到不了）。
 *
 * `.srcsize` 仍保留在槽内：count/体积统计与膨胀比测量都读它。
 *
 * 放外部私有目录（`/sdcard/Android/data/<包名>/files/preview_cache/`），
 * 和原版备份一致 —— 用户看得见、能自己清，卸载 app 时一起消失。
 */
class PreviewCacheRepository(private val context: Context) {

    companion object {
        /**
         * 缓存换代戳。解码逻辑修过一次大 bug（0.2.5 之前 ASTC 全部静默解成
         * 黑图），但没有版本戳的话修完也白修——isValid 只认 skel/atlas/大小，
         * 旧的黑图缓存会一直被当作有效命中继续显示。换代即整体失效，下次
         * 预解包重新生成。解码逻辑再改必须 bump。
         */
        private const val CACHE_GENERATION = 4
        private const val DIR_NAME = "preview_cache"
        private const val SRC_SIZE_FILE = ".srcsize"

        /**
         * 负缓存标记文件：产物解包成功但没有 skel/atlas（立绘/UI 类），
         * 没有预览价值。此前这类产物永远不满足 [isValid]，预解包每次都
         * 从头重解它们（用户实测：约 20 个这种的卡在列表开头反复解）。
         * 标记后预解包的目标筛选跳过；换产物（size 变）自动失效。
         */
        private const val NO_PREVIEW_FILE = ".nopreview"
    }

    private fun root(): File = File(context.getExternalFilesDir(null), DIR_NAME)

    /** size=0/-1（拿不到产物大小的异常场景）退回无后缀路径，不参与分槽。 */
    private fun slotName(hashDir: String, srcSize: Long): String =
        if (srcSize > 0) "${hashDir}_$srcSize" else hashDir

    fun entryDir(bundleName: String, hashDir: String, srcSize: Long = 0L): File =
        File(root(), "$bundleName/${slotName(hashDir, srcSize)}")

    /**
     * 缓存是否可用。
     *
     * 要求目录里既有骨架（.skel 或 .json）又有 .atlas —— 少一个都没法预览。
     * size 已编进目录名，这里的 .srcsize 校验是双保险（目录对得上但戳丢失
     * 的半成品不能算数）。
     */
    fun isValid(bundleName: String, hashDir: String, srcSize: Long): Boolean {
        val dir = entryDir(bundleName, hashDir, srcSize)
        if (!dir.isDirectory) return false
        // 旧代缓存（无戳或代数不符）一律无效，逼一次重新解包
        val genFile = File(dir, "gen")
        if (!genFile.isFile || genFile.readText().trim() != CACHE_GENERATION.toString())
            return false
        val files = dir.listFiles()?.filter { it.isFile } ?: return false
        val hasSkel = files.any { it.name.endsWith(".skel", true) || it.name.endsWith(".json", true) }
        val hasAtlas = files.any { it.name.endsWith(".atlas", true) }
        if (!hasSkel || !hasAtlas) return false
        return readSrcSize(dir) == srcSize
    }

    /** 缓存里的 skel 与 atlas 路径；不完整则返回 null。 */
    fun resolvePair(bundleName: String, hashDir: String, srcSize: Long): Pair<String, String>? {
        val files = entryDir(bundleName, hashDir, srcSize).listFiles()
            ?.filter { it.isFile } ?: return null
        val skel = files.firstOrNull { it.name.endsWith(".skel", true) }
            ?: files.firstOrNull { it.name.endsWith(".json", true) }
        val atlas = files.firstOrNull { it.name.endsWith(".atlas", true) }
        if (skel == null || atlas == null) return null
        return skel.absolutePath to atlas.absolutePath
    }

    /** 解包前调用：清空目标目录并返回它，让解包结果直接落在缓存位置。 */
    fun prepareDir(bundleName: String, hashDir: String, srcSize: Long): File {
        val dir = entryDir(bundleName, hashDir, srcSize)
        if (dir.exists()) dir.deleteRecursively()
        dir.mkdirs()
        return dir
    }

    /** 解包成功后记录源产物大小，作为后续校验依据。 */
    fun commit(bundleName: String, hashDir: String, srcSize: Long) {
        try {
            File(entryDir(bundleName, hashDir, srcSize), "gen").writeText(CACHE_GENERATION.toString())
        } catch (_: Exception) {
            // 写不进戳只是下次会重复解包，不当错误处理
        }
        try {
            File(entryDir(bundleName, hashDir, srcSize), SRC_SIZE_FILE).writeText(srcSize.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun readSrcSize(dir: File): Long =
        try {
            File(dir, SRC_SIZE_FILE)
                .takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull() ?: -1L
        } catch (e: Exception) {
            -1L
        }

    /** 已缓存的条目数（槽数 = 变体数，不含内部文件）。 */
    fun count(): Int {
        var n = 0
        root().listFiles()?.forEach { bundleDir ->
            bundleDir.listFiles()?.forEach { slotDir ->
                if (slotDir.isDirectory) n++
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
            bundleDir.listFiles()?.forEach { slotDir ->
                if (!slotDir.isDirectory) return@forEach
                val s = File(slotDir, SRC_SIZE_FILE).takeIf { it.isFile }
                    ?.readText()?.trim()?.toLongOrNull() ?: return@forEach
                if (s <= 0) return@forEach
                val o = slotDir.listFiles()?.filter { it.isFile && it.name != SRC_SIZE_FILE }
                    ?.sumOf { it.length() } ?: 0L
                if (o <= 0) return@forEach
                src += s
                out += o
            }
        }
        return if (src > 0) out.toDouble() / src else null
    }

    fun delete(bundleName: String, hashDir: String, srcSize: Long) {
        entryDir(bundleName, hashDir, srcSize).deleteRecursively()
    }

    /**
     * 标记「解包成功但没有预览价值」（无 skel/atlas）。只对**解包成功**的产物用 ——
     * 解包失败可能是临时原因（磁盘满/进程被杀），不该拿负缓存糊弄掉重试机会。
     */
    fun markNoPreview(bundleName: String, hashDir: String, srcSize: Long) {
        try {
            val dir = entryDir(bundleName, hashDir, srcSize)
            dir.mkdirs()
            File(dir, NO_PREVIEW_FILE).writeText(CACHE_GENERATION.toString())
            File(dir, SRC_SIZE_FILE).writeText(srcSize.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** 是否已标记为无预览价值（且产物没换过）。 */
    fun isKnownNoPreview(bundleName: String, hashDir: String, srcSize: Long): Boolean {
        val dir = entryDir(bundleName, hashDir, srcSize)
        val mark = File(dir, NO_PREVIEW_FILE)
        if (!mark.isFile) return false
        // 代数不符 = 解码逻辑换过，负缓存作废重判
        if (mark.readText().trim() != CACHE_GENERATION.toString()) return false
        return readSrcSize(dir) == srcSize
    }

    fun clearAll() {
        root().deleteRecursively()
    }
}
