package com.bd2toolsbox.core.storage

import com.bd2toolsbox.core.fileaccess.SafeArchivePaths
import java.io.File
import java.io.IOException

/**
 * 把游戏 Shared 树里的一份 `__data` 拷到独占目录再观察（T08）。
 * 调用方负责提供已导出的 Shared 根（设备上应是 Shizuku 只读拷出的结果）。
 */
object RecoveredTargetCopy {
    fun copyThenObserve(
        sharedRoot: File,
        destRoot: File,
        operationId: String,
        bundleName: String,
        hashDir: String?
    ): TargetObservation {
        val source = try {
            GameBundleLayout.dataFile(sharedRoot, bundleName, hashDir)
        } catch (e: IllegalArgumentException) {
            return TargetObservation.ReadFailed(operationId, bundleName, hashDir, e.message ?: "unsafe path")
        }
        if (!source.isFile) {
            return TargetObservation.ReadFailed(operationId, bundleName, hashDir, "missing ${source.path}")
        }
        val destDir = SafeArchivePaths.resolveInside(destRoot, "obs-$operationId")
        if (!destDir.exists() && !destDir.mkdirs()) {
            return TargetObservation.ReadFailed(operationId, bundleName, hashDir, "cannot create ${destDir.path}")
        }
        val dest = SafeArchivePaths.resolveInside(destDir, "__data")
        return try {
            source.inputStream().use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            TargetObservations.observeFile(operationId, bundleName, hashDir, dest)
        } catch (e: IOException) {
            TargetObservation.ReadFailed(operationId, bundleName, hashDir, e.message ?: "copy failed")
        }
    }
}
