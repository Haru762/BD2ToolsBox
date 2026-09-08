package com.bd2toolsbox.service

import android.content.Context
import android.util.Log
import com.bd2toolsbox.IFileService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 经 Shizuku 跑在 shell 权限里的文件服务（AIDL 实现）。
 *
 * App 自己没有权限写游戏目录（/Android/data/...），一切对游戏目录的读写
 * 都经这里的 [copyFile] / [copyDirectory] / [listBundleDirectory] 完成。
 */
class ShizukuFileService(context: Context? = null) : IFileService.Stub() {

    override fun copyFile(sourcePath: String, destPath: String): Boolean = try {
        val dest = File(destPath)
        dest.parentFile?.mkdirs()
        copyStream(File(sourcePath), dest)
        Log.d(TAG, "Successfully copied $sourcePath to $destPath")
        true
    } catch (e: Exception) {
        Log.e(TAG, "Error copying $sourcePath to $destPath", e)
        false
    }

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

    override fun listBundleDirectory(sharedDirPath: String): String {
        return try {
            val sharedDir = File(sharedDirPath)
            if (!sharedDir.isDirectory) {
                Log.w(TAG, "Shared directory does not exist: $sharedDirPath")
                return "[]"
            }

            // Shared/{bundleName}/{hash}/__data；一个 bundle 只取第一个含 __data 的 hash 目录。
            // size 是 __data 实际字节数，供「干净检测」与 catalog 的官方大小比对 ——
            // 重打包产物沿用原版路径结构（目录名相同），只有大小能区分原版和装着 mod。
            val result = JSONArray()
            sharedDir.listFiles()?.forEach { bundleDir ->
                if (!bundleDir.isDirectory) return@forEach
                bundleDir.listFiles()?.forEach inner@{ hashDir ->
                    if (!hashDir.isDirectory) return@inner
                    val dataFile = File(hashDir, "__data")
                    if (dataFile.isFile) {
                        result.put(JSONObject().apply {
                            put("name", bundleDir.name)
                            put("hash", hashDir.name)
                            put("size", dataFile.length())
                        })
                        return@forEach
                    }
                }
            }
            Log.d(TAG, "Listed ${result.length()} bundles from $sharedDirPath")
            result.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error listing bundle directory $sharedDirPath", e)
            "[]"
        }
    }

    override fun destroy() {
        System.exit(0)
    }

    // ---------------------------------------------------------------- 内部

    private fun copyStream(source: File, dest: File) {
        source.inputStream().use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
    }

    /** 递归复制目录树；单个文件失败即抛出（由调用方统一报告失败）。 */
    private fun copyTree(source: File, dest: File) {
        if (!dest.exists()) dest.mkdirs()
        source.listFiles()?.forEach { file ->
            val target = File(dest, file.name)
            if (file.isDirectory) {
                copyTree(file, target)
            } else {
                try {
                    copyStream(file, target)
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
