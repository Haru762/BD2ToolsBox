package com.bd2toolsbox.core.fileaccess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileDirectoryCopySourceTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun copiesHashDataLayoutThroughStrictDirectoryCopy() {
        val src = temp.newFolder("bundle")
        val data = File(File(src, "hash-a"), "__data")
        data.parentFile.mkdirs()
        data.writeBytes(byteArrayOf(9, 8, 7))
        val dest = temp.newFolder("dest")
        val source = FileDirectoryCopySource(src)
        StrictDirectoryCopy.copy(source, source.rootId, dest)
        val copied = File(File(dest, "hash-a"), "__data")
        assertTrue(copied.isFile)
        assertEquals(data.readBytes().toList(), copied.readBytes().toList())
    }

    @Test
    fun missingDirectoryListsAsReadFailure() {
        val source = FileDirectoryCopySource(temp.newFolder("root"))
        val missing = File(temp.root, "no-such-dir")
        assertEquals(null, source.listChildren(missing.absolutePath))
    }
}
