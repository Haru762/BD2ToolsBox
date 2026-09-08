package com.bd2toolsbox.data.repository

import android.content.Context
import java.io.File

/**
 * 原版 bundle 的本地备份。
 *
 * 安卓装 mod 是就地改写游戏缓存里的 `__data`，游戏自己不会察觉（Unity 判断缓存有效只看
 * 目录名与内容哈希，不校验内容），所以卸载必须显式把原版盖回去。原本唯一的还原途径是从
 * 官方 CDN 重新下载 —— 要流量、要等。
 *
 * 但装入流程里其实已经拿到过权威原版：`tryUseLocalBundle` 会在干净检测判定 PRISTINE 时
 * 从游戏目录取一份原版当重打包基底，走 CDN 那条路拿到的同样是原版。以往这份文件用完就在
 * finally 里删掉，这里只是在删之前另存一份，于是备份几乎是免费的 —— 装哪个备份哪个。
 *
 * 目录结构刻意与游戏缓存保持一致：
 * ```
 * <外部私有目录>/origin_backup/<bundle 名>/<内容哈希>/__data
 *          游戏侧            Shared/<bundle 名>/<内容哈希>/__data
 * ```
 * 还原时不需要任何换算，直接 Shizuku copyFile 拷回去即可。
 *
 * 备份放外部私有目录（`/sdcard/Android/data/<包名>/files/`）而不是 `filesDir`：
 * 用户能自己看到和清理，且卸载 app 时会一起消失，不会在内部存储里悄悄堆几百 MB。
 *
 * 哈希目录名带在路径里是有意的 —— 游戏更新后同一个 bundle 的内容哈希会变（实测 176 个
 * bundle 跨版本后哈希 176/176 全变），旧备份对新版本没有意义，靠路径天然区分开，不会误用。
 */
class BundleBackupRepository(private val context: Context) {

    companion object {
        private const val DIR_NAME = "origin_backup"

        /**
         * 备份文件名刻意带 `.game` 后缀，用来自证格式。
         *
         * 原版有两种编码：游戏缓存里的 `__data`，和 CDN 下发的压缩包（同一 bundle 实测
         * 26 MB vs 6.2 MB）。只有前者能直接盖回游戏，所以这里只存前者。加后缀有两个好处：
         * 一是格式自描述，将来不会误把压缩包当成能直接拷的备份；二是早期版本曾把 CDN 包
         * 存成 `__data`，换名后那些坏备份自动失效，不必额外写迁移代码。
         */
        private const val DATA_FILENAME = "__data.game"
    }

    private fun root(): File = File(context.getExternalFilesDir(null), DIR_NAME)

    /** 某个 bundle 在指定内容哈希下的备份文件路径（不保证存在）。 */
    fun backupFile(bundleName: String, hashDir: String): File =
        File(root(), "$bundleName/$hashDir/$DATA_FILENAME")

    fun hasBackup(bundleName: String, hashDir: String): Boolean =
        backupFile(bundleName, hashDir).let { it.isFile && it.length() > 0 }

    /** 备份的字节数；没有备份返回 -1。 */
    fun backupSize(bundleName: String, hashDir: String): Long =
        backupFile(bundleName, hashDir).let { if (it.isFile) it.length() else -1L }

    /**
     * 把一份原版 `__data` 存为备份。已存在则跳过，不重复占空间。
     *
     * @return true 表示备份可用（本次写入成功，或此前已备份过）
     */
    fun saveBackup(bundleName: String, hashDir: String, source: File): Boolean {
        if (hasBackup(bundleName, hashDir)) return true
        if (!source.isFile || source.length() <= 0) return false
        val dest = backupFile(bundleName, hashDir)
        return try {
            dest.parentFile?.mkdirs()
            // 先写临时文件再改名，避免中途失败留下一个残缺备份被当成有效的
            val tmp = File(dest.parentFile, "$DATA_FILENAME.tmp")
            source.inputStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (dest.exists()) dest.delete()
            val ok = tmp.renameTo(dest)
            if (!ok) tmp.delete()
            ok
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /** 已备份的 bundle -> 内容哈希。用于卸载时判断哪些能本地秒还原。 */
    fun listBackups(): Map<String, String> {
        val out = HashMap<String, String>()
        root().listFiles()?.forEach { bundleDir ->
            if (!bundleDir.isDirectory) return@forEach
            bundleDir.listFiles()?.forEach { hashDir ->
                if (hashDir.isDirectory && File(hashDir, DATA_FILENAME).isFile) {
                    out[bundleDir.name] = hashDir.name
                }
            }
        }
        return out
    }

    fun count(): Int = listBackups().size

    fun totalBytes(): Long {
        var sum = 0L
        root().listFiles()?.forEach { bundleDir ->
            bundleDir.listFiles()?.forEach { hashDir ->
                File(hashDir, DATA_FILENAME).takeIf { it.isFile }?.let { sum += it.length() }
            }
        }
        return sum
    }

    /** 删除某个 bundle 的全部备份（含各历史哈希）。 */
    fun deleteBackup(bundleName: String) {
        File(root(), bundleName).deleteRecursively()
    }

    fun clearAll() {
        root().deleteRecursively()
    }
}
