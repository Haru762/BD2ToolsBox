package com.bd2toolsbox.service

import android.content.Context
import android.util.Log
import com.bd2toolsbox.IFileService
import com.bd2toolsbox.core.fileaccess.AtomicFileWriter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * 经 Shizuku 跑在 shell 权限里的文件服务（AIDL 实现）。
 *
 * App 自己没有权限写游戏目录（/Android/data/...），一切对游戏目录的读写
 * 都经这里的 [copyFile] / [copyDirectory] / [listBundleDirectoryPaged] 完成。
 */
class ShizukuFileService(context: Context? = null) : IFileService.Stub() {

    override fun copyFile(sourcePath: String, destPath: String): Boolean = try {
        val dest = File(destPath)
        dest.parentFile?.mkdirs()
        copyStream(File(sourcePath), dest)
        makeWorldReadable(dest)
        Log.d(TAG, "Successfully copied $sourcePath to $destPath")
        true
    } catch (e: Exception) {
        Log.e(TAG, "Error copying $sourcePath to $destPath", e)
        false
    }

    /**
     * 递归复制一个目录。
     *
     * **这不是事务。** [copyTree] 逐文件调用 [copyStream]，每个文件各自是一次独立的
     * 原子提交（T07）：第 k 个文件失败时前 k-1 个**已经落进目标目录**了，返回的
     * `false` 只表示「这个目录没拷完」。调用方必须按目标分别上报成功与失败，
     * 不许把它说成「整批成功/整批失败」。
     */
    override fun copyDirectory(sourceDirPath: String, destDirPath: String): Boolean {
        return try {
            val sourceDir = File(sourceDirPath)
            // 必须先确认源存在：否则递归里 listFiles() 返回 null 会被 ?. 跳过，
            // 结果是「创建了一个空的目标目录 + 报告成功」—— 对游戏目录等于塞进一个空 bundle
            if (!sourceDir.isDirectory) {
                Log.e(TAG, "Source directory does not exist: $sourceDirPath")
                return false
            }
            copyTree(sourceDir, File(destDirPath))
            Log.d(TAG, "Successfully copied directory $sourceDirPath to $destDirPath")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error copying directory $sourceDirPath to $destDirPath", e)
            false
        }
    }

    override fun listBundleDirectoryPaged(sharedDirPath: String, offset: Int, limit: Int): String {
        return try {
            val sharedDir = File(sharedDirPath)
            if (!sharedDir.isDirectory) {
                Log.w(TAG, "Shared directory does not exist: $sharedDirPath")
                return "[]"
            }

            // Shared/{bundleName}/{hash}/__data；一个 bundle 只取第一个含 __data 的 hash 目录。
            // size 是 __data 实际字节数，供「干净检测」与 catalog 的官方大小比对 ——
            // 重打包产物沿用原版路径结构（目录名相同），只有大小能区分原版和装着 mod。
            // 分页：先全量收进内存（服务进程侧无 binder 限制），再切 [offset, offset+limit)
            // 出去 —— 调用方循环拉到「返回条数 < limit」即最后一页。单页必须显著小于
            // binder 1MB 事务上限：500 条 × ~120B ≈ 60KB，余量充足。
            val all = JSONArray()
            sharedDir.listFiles()?.forEach { bundleDir ->
                if (!bundleDir.isDirectory) return@forEach
                bundleDir.listFiles()?.forEach inner@{ hashDir ->
                    if (!hashDir.isDirectory) return@inner
                    val dataFile = File(hashDir, "__data")
                    if (dataFile.isFile) {
                        all.put(JSONObject().apply {
                            put("name", bundleDir.name)
                            put("hash", hashDir.name)
                            put("size", dataFile.length())
                        })
                        return@forEach
                    }
                }
            }
            val total = all.length()
            val page = JSONArray()
            var i = offset
            while (i < total && i < offset + limit) {
                page.put(all.get(i))
                i++
            }
            Log.d(TAG, "Listed bundles $offset..${i - 1} of $total from $sharedDirPath")
            page.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error listing bundle directory $sharedDirPath", e)
            "[]"
        }
    }

    override fun destroy() {
        System.exit(0)
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 把 [source] 的字节提交到 [dest]。
     *
     * T07 之前这里是 `dest.outputStream().use { input.copyTo(it) }` —— 打开 [dest]
     * 就把它截断了，拷贝中途失败会留下一个半截的 `__data`，而调用方只看到 `false`。
     * 现在走 [AtomicFileWriter]：同目录暂存 → 校验 → 原子替换，失败时 [dest]
     * 保持旧内容。
     *
     * **一次调用 = 一次单文件提交。** [copyTree] 是逐文件调用的，所以一个目录 N 个
     * 文件是 N 次独立提交，第 k 个失败时前 k-1 个已经提交 —— 这是真实存在的
     * 「批次部分成功」，调用方按目标分别上报，**不**宣称整批事务。
     *
     * 校验只做**常数内存**的两件事（`__data` 单份是 26 MB 级，读进内存不是选项）：
     * 写完的字节数与源文件相等，且（[allowEmptySource] 为 false 时）非空。
     * 格式层面的完整校验仍在 app 侧过 Binder **之前**由
     * `MainViewModel.validateConvertedForInstall` 做（它要 Python，
     * 而 lambda 过不了 Binder）—— 见 `T07.md` §3.4。
     *
     * @param allowEmptySource 源是合法但 0 字节的文件时，是否照常拷成空文件。
     *
     *   **为什么这个开关存在（T07 二轮整改，编排者裁定 3）**：初版把「空源拒绝」
     *   无条件写在这里，而 [copyTree] 对树里**每个**文件都调这个方法 —— 于是树里
     *   任何一个合法的 0 字节文件（Android 上惯例的 `.nomedia` 就是 0 字节）都会让
     *   整个 `copyDirectory` 失败。复核者的对照实验（`T07.md` §8.9）：同设备同目录树，
     *   旧写法 `ok=true`（两个文件都落地）、新写法 `ok=false`（连非空的 `__data`
     *   都没落地）。可达路径很真实：`ShizukuManager:281` 的
     *   `copyDirectory(DOWNLOAD_SHARED_PATH, …)`，源是用户可写的 `Download/Shared`。
     *
     *   所以这条规则**收窄到单文件路径**：只有「一个文件直接顶掉游戏目录里那份
     *   `__data`」时才拒绝空源（那种情况下空源 = 把用户的 `__data` 清空却报成功，
     *   是必须挡的）；目录树拷贝里每个文件只是把源原样搬过去，空文件搬成空文件，
     *   没有「清空目标」这回事 —— 目标被截断这件事已由同目录暂存 + 原子替换挡掉。
     *
     *   判定本身仍然**只在这一处**，由调用方声明意图（[copyFile] 用默认的 false、
     *   [copyTree] 显式传 true），不散成多份 `if`。
     */
    /** shell/root 写入 app 私有目录后，把属主改回父目录的 uid，否则 app 读 EACCES。 */
    private fun makeWorldReadable(dest: File) {
        try {
            val parent = dest.parentFile ?: return
            val st = android.system.Os.stat(parent.absolutePath)
            android.system.Os.chown(dest.absolutePath, st.st_uid, st.st_gid)
            android.system.Os.chmod(dest.absolutePath, 384) // 0600
        } catch (e: Exception) {
            Log.w(TAG, "chown/chmod dest failed: ${dest.absolutePath}", e)
            dest.setReadable(true, false)
        }
    }

    private fun copyStream(source: File, dest: File, allowEmptySource: Boolean = false) {
        val expectedSize = source.length()
        AtomicFileWriter().write(
            target = dest,
            verifyBeforeCommit = { staged ->
                val written = staged.length()
                if (written != expectedSize) {
                    throw IOException(
                        "暂存文件字节数与源不一致（源 $expectedSize，暂存 $written）：${source.path}"
                    )
                }
                if (written <= 0L && !allowEmptySource) {
                    throw IOException("暂存文件为空，拒绝提交：${source.path}")
                }
            }
        ) { output ->
            source.inputStream().use { input -> input.copyTo(output) }
        }
    }

    /**
     * 递归复制目录树；单个文件失败即抛出（由调用方统一报告失败）。
     *
     * 树里的每个文件都按 [allowEmptySource] = **true** 提交：目录树拷贝必须允许
     * 合法的 0 字节文件（见 [copyStream] 的说明）。
     */
    private fun copyTree(source: File, dest: File) {
        if (!dest.exists()) dest.mkdirs()
        source.listFiles()?.forEach { file ->
            val target = File(dest, file.name)
            if (file.isDirectory) {
                copyTree(file, target)
            } else {
                try {
                    copyStream(file, target, allowEmptySource = true)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to copy individual file: ${file.absolutePath} to ${target.absolutePath}", e)
                    throw e
                }
            }
        }
    }

    private companion object {
        const val TAG = "ShizukuFileService"
    }
}
