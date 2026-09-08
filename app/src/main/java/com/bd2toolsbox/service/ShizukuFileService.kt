package com.bd2toolsbox.service

import android.content.Context
import com.bd2toolsbox.IFileService
import java.io.File
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

class ShizukuFileService(context: Context? = null) : IFileService.Stub() {

    override fun copyFile(sourcePath: String, destPath: String): Boolean {
        return try {
            val source = File(sourcePath)
            val dest = File(destPath)
            dest.parentFile?.mkdirs()
            source.inputStream().use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Log.d("ShizukuFileService", "Successfully copied $sourcePath to $destPath")
            true
        } catch (e: Exception) {
            Log.e("ShizukuFileService", "Error copying $sourcePath to $destPath", e)
            false
        }
    }

    override fun copyDirectory(sourceDirPath: String, destDirPath: String): Boolean {
        return try {
            val sourceDir = File(sourceDirPath)
            // 必须先确认源存在：否则 copyDirRecursive 里 listFiles() 返回 null 会被 ?. 跳过，
            // 结果是「创建了一个空的目标目录 + 报告成功」。对游戏目录来说等于塞进一个空 bundle。
            if (!sourceDir.isDirectory) {
                Log.e("ShizukuFileService", "Source directory does not exist: $sourceDirPath")
                return false
            }
            val destDir = File(destDirPath)
            copyDirRecursive(sourceDir, destDir)
            Log.d("ShizukuFileService", "Successfully copied directory $sourceDirPath to $destDirPath")
            true
        } catch (e: Exception) {
            Log.e("ShizukuFileService", "Error copying directory $sourceDirPath to $destDirPath", e)
            false
        }
    }

    private fun copyDirRecursive(source: File, dest: File) {
        if (!dest.exists()) dest.mkdirs()
        source.listFiles()?.forEach { file ->
            val target = File(dest, file.name)
            if (file.isDirectory) {
                copyDirRecursive(file, target)
            } else {
                try {
                    file.inputStream().use { input ->
                        target.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ShizukuFileService", "Failed to copy individual file: ${file.absolutePath} to ${target.absolutePath}", e)
                    throw e // Re-throw to be caught by the outer catch block
                }
            }
        }
    }

    /**
     * List all bundles in the game's Shared/ directory.
     * Returns a JSON array string:
     *   [{"name":"bundleName","hash":"hashDirName","size":123456}, ...]
     *
     * `size` 是 __data 的实际字节数，用于「干净检测」：与 catalog 里该 bundle 的
     * m_BundleSize 一致才说明是干净原版。重打包产物沿用原版的路径结构，所以
     * 目录名对 mod 版和原版是相同的 —— 只有大小能区分两者。
     *
     * Expected directory structure:
     *   Shared/{bundleName}/{hash}/__data
     */
    override fun listBundleDirectory(sharedDirPath: String): String {
        return try {
            val sharedDir = File(sharedDirPath)
            if (!sharedDir.isDirectory) {
                Log.w("ShizukuFileService", "Shared directory does not exist: $sharedDirPath")
                return "[]"
            }

            val result = JSONArray()
            sharedDir.listFiles()?.forEach { bundleDir ->
                if (!bundleDir.isDirectory) return@forEach
                val bundleName = bundleDir.name

                // Find the first hash subdirectory containing __data
                bundleDir.listFiles()?.forEach inner@{ hashDir ->
                    if (!hashDir.isDirectory) return@inner
                    val dataFile = File(hashDir, "__data")
                    if (dataFile.isFile) {
                        result.put(JSONObject().apply {
                            put("name", bundleName)
                            put("hash", hashDir.name)
                            put("size", dataFile.length())
                        })
                        return@forEach // Only take the first valid hash directory
                    }
                }
            }

            Log.d("ShizukuFileService", "Listed ${result.length()} bundles from $sharedDirPath")
            result.toString()
        } catch (e: Exception) {
            Log.e("ShizukuFileService", "Error listing bundle directory $sharedDirPath", e)
            "[]"
        }
    }

    override fun destroy() {
        System.exit(0)
    }
}
