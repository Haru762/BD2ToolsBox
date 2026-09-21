package com.bd2toolsbox.core.storage

import com.bd2toolsbox.core.fileaccess.SafeArchivePaths
import java.io.File

/**
 * 游戏 UnityCache/Shared 下 converted bundle 的 `__data` 路径（T08）。
 * 只做路径合成与逃逸拒绝，不读文件、不调 Shizuku。
 */
object GameBundleLayout {
    fun dataFile(sharedRoot: File, bundleName: String, hashDir: String?): File {
        val bundleDir = SafeArchivePaths.resolveInside(sharedRoot, bundleName)
        val parent = if (hashDir.isNullOrEmpty()) bundleDir
        else SafeArchivePaths.resolveInside(bundleDir, hashDir)
        return SafeArchivePaths.resolveInside(parent, "__data")
    }

    fun remoteDataPath(sharedRoot: String, bundleName: String, hashDir: String?): String =
        dataFile(File(sharedRoot), bundleName, hashDir).invariantSeparatorsPath
}
