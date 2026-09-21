package com.bd2toolsbox.core.storage

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JournalMoveFactsTest {
    @get:Rule
    val tempDir = TemporaryFolder()

    @Test
    fun existingFile_returnsFactsAndMatchesDigestAndLength() {
        val root = tempDir.newFolder("Shared")
        val bundleName = "b123"
        val hashDir = "abc"
        val targetDir = File(File(root, bundleName), hashDir)
        targetDir.mkdirs()
        val dataFile = File(targetDir, "__data")
        dataFile.writeText("hello world")

        val uris = listOf("uri1")
        val upserts = listOf(LedgerRecord("uri1", "mod", "key", bundleName, "HD", false, 0L))

        val target = JournalMoveFacts.target(
            sharedRoot = root,
            hashedName = bundleName,
            relativePath = "$bundleName/$hashDir/__data",
            uris = uris,
            upserts = upserts
        )

        assertEquals(bundleName, target.bundleName)
        assertEquals(hashDir, target.hashDir)
        assertEquals(dataFile.length(), target.dataBytes)
        assertEquals(uris, target.modUris)
        
        val facts = target.recoveryFacts
        assertNotNull(facts)
        assertEquals(1, facts?.formatVersion)
        assertEquals(StreamingDigest.sha256().digest(dataFile), facts?.expectedContentSha256)
        assertEquals(upserts, facts?.ledgerUpserts)
    }

    @Test
    fun missingFile_returnsNullFactsAndZeroBytes() {
        val root = tempDir.newFolder("Shared")
        val bundleName = "b123"
        val hashDir = "abc"

        val uris = listOf("uri1")
        val upserts = listOf(LedgerRecord("uri1", "mod", "key", bundleName, "HD", false, 0L))

        val target = JournalMoveFacts.target(
            sharedRoot = root,
            hashedName = bundleName,
            relativePath = "$bundleName/$hashDir/__data",
            uris = uris,
            upserts = upserts
        )

        assertEquals(bundleName, target.bundleName)
        assertEquals(hashDir, target.hashDir)
        assertEquals(0L, target.dataBytes)
        assertNull(target.recoveryFacts)
    }

    @Test
    fun emptyUrisOrUpserts_returnsNullFactsEvenIfFileExists() {
        val root = tempDir.newFolder("Shared")
        val bundleName = "b123"
        val hashDir = "abc"
        val targetDir = File(File(root, bundleName), hashDir)
        targetDir.mkdirs()
        val dataFile = File(targetDir, "__data")
        dataFile.writeText("hello world")

        val upserts = listOf(LedgerRecord("uri1", "mod", "key", bundleName, "HD", false, 0L))

        val targetNoUris = JournalMoveFacts.target(
            sharedRoot = root,
            hashedName = bundleName,
            relativePath = "$bundleName/$hashDir/__data",
            uris = emptyList(),
            upserts = upserts
        )
        assertNull(targetNoUris.recoveryFacts)

        val targetNoUpserts = JournalMoveFacts.target(
            sharedRoot = root,
            hashedName = bundleName,
            relativePath = "$bundleName/$hashDir/__data",
            uris = listOf("uri1"),
            upserts = emptyList()
        )
        assertNull(targetNoUpserts.recoveryFacts)
    }
}
