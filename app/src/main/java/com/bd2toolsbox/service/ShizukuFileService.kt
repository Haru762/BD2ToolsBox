package com.bd2toolsbox.service

import android.content.Context
import android.util.Log
import com.bd2toolsbox.IFileService
import com.bd2toolsbox.core.fileaccess.AtomicFileWriter
import com.bd2toolsbox.feature.mods.BundleListingSnapshot
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

    /** T23：按路径缓存的列举快照，避免分页时每页重扫整个目录。 */
    private val listing = BundleListingSnapshot()

    override fun copyFile(sourcePath: String, destPath: String): Boolean = try {
        val dest = File(destPath)
        dest.parentFile?.mkdirs()
        copyStream(File(sourcePath), dest)
        restoreParentOwner(dest)
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

    /**
     * 返回 envelope：`{"ok":true,"entries":[...]}` 或 `{"ok":false,"error":"..."}`（T19）。
     *
     * 旧实现把「目录不存在」「listFiles() 返 null」「异常」三种情况都返回裸数组 `[]`，
     * 调用方（`ShizukuManager`）拿到同一个值，无法区分「确实为空」与「读失败」。
     * 现在明确分开：目录不存在/listFiles 返 null 都是 `ok:false`（不是「确实没文件」）；
     * 只有真正遍历完毕且没有子目录无法读取时才返回 `ok:true`（entries 可以为空）。
     */
    override fun listBundleDirectoryPaged(sharedDirPath: String, offset: Int, limit: Int): String {
        fun failure(message: String): String =
            JSONObject().put("ok", false).put("error", message).toString()

        val sharedDir = File(sharedDirPath)
        if (!sharedDir.isDirectory) {
            Log.w(TAG, "Shared directory does not exist: $sharedDirPath")
            return failure("Shared directory does not exist: $sharedDirPath")
        }

        return try {
            // T23：分页不再每页重扫整个目录。BundleListingSnapshot 按路径缓存枚举结果，
            // offset==0 时重建快照，后续页复用同一份；枚举语义（失败不等于空表）不变。
            val page = listing.page(sharedDir, offset, limit)
            val entries = JSONArray()
            for (entry in page) {
                entries.put(JSONObject().apply {
                    put("name", entry.name)
                    put("hash", entry.hash)
                    put("size", entry.sizeBytes)
                })
            }
            Log.d(TAG, "Listed bundles offset=$offset count=${page.size} from $sharedDirPath")
            JSONObject().put("ok", true).put("entries", entries).toString()
        } catch (e: IOException) {
            Log.w(TAG, "Cannot list bundle directory $sharedDirPath: ${e.message}")
            failure(e.message ?: "Cannot list $sharedDirPath")
        } catch (e: Exception) {
            Log.e(TAG, "Error listing bundle directory $sharedDirPath", e)
            failure("Exception while listing $sharedDirPath: ${e.message}")
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
    /**
     * shell/root 写入后，把属主改回父目录的 uid/gid，否则 app 侧读 EACCES。
     *
     * **只改属主，绝不碰权限位。**
     *
     * 权限（已存在的目标保持原样；新建目标先抄同目录兄弟、没兄弟才退回父目录的 rw 位）
     * 是 [AtomicFileWriter] 的契约，由 `GameFileCommitTest` 的
     * `commitDoesNotDowngradeTargetPermissions` 与两条 new-target 用例直接守着。
     *
     * ## 这里曾经有一个真实回归（2026-10-11 真机跑用例时暴露）
     *
     * `e993800`（T08/T10）在这段里加了 `Os.chmod(dest, 0600)`，并在 catch 里
     * 退回 `dest.setReadable(true, false)`（即 0644）。两者都会把
     * `AtomicFileWriter` 刚搬好的权限**覆盖掉**：
     *   - arm64 真机：chown+chmod 都成功 → 目标被降级成 `0600`（游戏进程读不到）；
     *   - x86_64 模拟器：chmod 抛异常 → 退回 0644（把原本 `0640` 的目标悄悄放宽）。
     * 两种结果都违反契约，而且**跟设备走**——同一份代码在两台设备上产出不同权限。
     * 函数名 `makeWorldReadable` 与它实际做的 `chmod 0600` 也自相矛盾。
     *
     * 修法：只保留 chown（这才是「shell/root 写的文件 app 读不到」的真正成因），
     * 去掉 chmod 与 setReadable 回退。若 chown 失败，保持**可见的失败**
     * （调用方读到 EACCES），不用「偷偷放宽权限」把它盖过去。
     */
    private fun restoreParentOwner(dest: File) {
        try {
            val parent = dest.parentFile ?: return
            val st = android.system.Os.stat(parent.absolutePath)
            android.system.Os.chown(dest.absolutePath, st.st_uid, st.st_gid)
        } catch (e: Exception) {
            Log.w(TAG, "chown dest failed: ${dest.absolutePath}", e)
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
