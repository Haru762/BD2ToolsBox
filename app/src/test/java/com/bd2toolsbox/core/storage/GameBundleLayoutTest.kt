package com.bd2toolsbox.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GameBundleLayoutTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun convertedLayoutUsesBundleHashAndData() {
        val root = temp.newFolder("Shared")
        val file = GameBundleLayout.dataFile(root, "bundle-a", "hash-a")
        assertEquals("__data", file.name)
        assertTrue(file.absolutePath.replace('\\', '/').endsWith("Shared/bundle-a/hash-a/__data"))
    }

    @Test
    fun missingHashDirPutsDataDirectlyUnderBundle() {
        val root = temp.newFolder("Shared")
        val file = GameBundleLayout.dataFile(root, "bundle-a", null)
        assertTrue(file.absolutePath.replace('\\', '/').endsWith("Shared/bundle-a/__data"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun pathEscapeInBundleNameIsRejected() {
        GameBundleLayout.dataFile(temp.newFolder("Shared"), "..", "hash-a")
    }

    @Test
    fun remoteDataPathUsesForwardSlashesUnderSharedRoot() {
        val path = GameBundleLayout.remoteDataPath(
            "/storage/emulated/0/Android/data/game/files/UnityCache/Shared",
            "bundle-a",
            "hash-a"
        )
        assertTrue(path.replace('\\', '/').endsWith("Shared/bundle-a/hash-a/__data"))
        assertTrue(!path.contains(".."))
    }
}
