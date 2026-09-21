package com.bd2toolsbox.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DownloadSharedLayoutTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun findsDataUnderHashedNameAndHashDir() {
        val root = temp.newFolder("Shared")
        val data = File(File(File(root, "bundle-a"), "hash-a"), "__data")
        data.parentFile.mkdirs()
        data.writeBytes(byteArrayOf(1))
        
        val found = DownloadSharedLayout.dataFile(root, "bundle-a", "bundle-a/hash-a/__data")
        assertEquals(data, found)
        
        val digest = StreamingDigest.sha256().digest(found!!)
        assertEquals(64, digest.length)
        assertEquals(digest, StreamingDigest.sha256().digest(found))
    }

    @Test
    fun findsDataUnderHashedNameDirectly() {
        val root = temp.newFolder("Shared")
        val data = File(File(root, "bundle-a"), "__data")
        data.parentFile.mkdirs()
        data.writeBytes(byteArrayOf(2))
        assertEquals(data, DownloadSharedLayout.dataFile(root, "bundle-a", ""))
        
        val data2 = File(root, "bundle-b")
        data2.writeBytes(byteArrayOf(3))
        assertEquals(data2, DownloadSharedLayout.dataFile(root, "bundle-b", ""))
    }

    @Test
    fun findsDataUnderRelativePath() {
        val root = temp.newFolder("Shared")
        val data = File(root, "some/rel/path.zip")
        data.parentFile.mkdirs()
        data.writeBytes(byteArrayOf(4))
        assertEquals(data, DownloadSharedLayout.dataFile(root, "hashed", "some/rel/path.zip"))
    }

    @Test
    fun missingFileReturnsNull() {
        assertNull(DownloadSharedLayout.dataFile(temp.newFolder("Shared"), "bundle-a", "bundle-a/hash-a/__data"))
    }

    @Test
    fun emptyOrMalformedInputReturnsNullWithoutThrow() {
        val root = temp.newFolder("Shared")
        assertNull(DownloadSharedLayout.dataFile(root, "", ""))
        assertNull(DownloadSharedLayout.dataFile(root, "  ", "  "))
    }
}
