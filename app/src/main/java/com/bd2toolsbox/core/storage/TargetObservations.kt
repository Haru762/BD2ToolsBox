package com.bd2toolsbox.core.storage

import java.io.File

/**
 * 把本地可读文件变成 [TargetObservation]（T08 slice C 的 JVM 半截）。
 * 不访问 Shizuku；设备路径应先被受控拷贝到本机文件再调用这里。
 */
object TargetObservations {
    fun observeFile(
        operationId: String,
        bundleName: String,
        hashDir: String?,
        file: File,
        digest: Digester = StreamingDigest.sha256()
    ): TargetObservation {
        if (!file.isFile) {
            return TargetObservation.ReadFailed(
                operationId, bundleName, hashDir,
                reason = "target is not a readable regular file: ${file.path}"
            )
        }
        return try {
            TargetObservation.Observed(
                operationId = operationId,
                bundleName = bundleName,
                hashDir = hashDir,
                actualSha256 = digest.digest(file)
            )
        } catch (e: Exception) {
            TargetObservation.ReadFailed(
                operationId, bundleName, hashDir,
                reason = e.message ?: e.javaClass.simpleName
            )
        }
    }
}
