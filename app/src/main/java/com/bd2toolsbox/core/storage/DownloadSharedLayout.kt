package com.bd2toolsbox.core.storage

import java.io.File

/**
 * 转换产物落到 Download/Shared 后的 `__data` 定位（T08 move 路径）。
 * 只做路径合成，不读 MediaStore。
 */
object DownloadSharedLayout {
    fun dataFile(sharedRoot: File, hashedName: String, relativePath: String): File? {
        val rel = relativePath.replace('\\', '/')
        val candidates = mutableListOf(
            File(sharedRoot, rel),
            File(sharedRoot, hashedName),
            File(File(sharedRoot, hashedName), "__data")
        )
        val hashDir = rel.split('/').firstOrNull { it.isNotEmpty() && it != hashedName && it != "__data" }
        if (hashDir != null) {
            try {
                candidates += GameBundleLayout.dataFile(sharedRoot, hashedName, hashDir)
            } catch (_: IllegalArgumentException) {
            }
            candidates += File(File(File(sharedRoot, hashedName), hashDir), "__data")
        }
        return candidates.firstOrNull { it.isFile }
    }
}
