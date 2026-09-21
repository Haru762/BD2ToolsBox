package com.bd2toolsbox.core.storage

import com.bd2toolsbox.core.fileaccess.SafeArchivePaths
import java.io.File

object RecoveryObservationLayout {
    fun destFile(destRoot: File, operationId: String, bundleName: String, hashDir: String?): File {
        val hash = hashDir ?: "root"
        
        var current = destRoot
        val segments = listOf(operationId, bundleName, hash, "__data")
        for (segment in segments) {
            if (segment.isEmpty() || segment.contains("/") || segment.contains("\\") || segment.contains("..")) {
                throw IllegalArgumentException("Segment contains separators, empty, or '..'")
            }
            current = SafeArchivePaths.resolveInside(current, segment)
        }
        
        return current
    }
}
