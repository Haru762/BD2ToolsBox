package com.bd2toolsbox.core.storage

import java.io.File

object JournalMoveFacts {
    fun target(
        sharedRoot: File,
        hashedName: String,
        relativePath: String,
        uris: List<String>,
        upserts: List<LedgerRecord>
    ): JournalTarget {
        val dataFile = DownloadSharedLayout.dataFile(sharedRoot, hashedName, relativePath)
        
        val normalizedPath = relativePath.replace('\\', '/')
        val parts = normalizedPath.split('/')
        val hashDir = if (parts.size >= 2) parts[parts.size - 2] else null
        
        val facts = if (dataFile != null && uris.isNotEmpty() && upserts.isNotEmpty()) {
            JournalRecoveryFacts(1, StreamingDigest.sha256().digest(dataFile), upserts)
        } else {
            null
        }
        
        return JournalTarget(
            bundleName = hashedName,
            hashDir = hashDir,
            dataBytes = dataFile?.length() ?: 0L,
            modUris = uris,
            recoveryFacts = facts
        )
    }
}
