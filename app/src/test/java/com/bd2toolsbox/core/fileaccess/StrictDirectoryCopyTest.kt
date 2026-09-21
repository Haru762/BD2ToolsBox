package com.bd2toolsbox.core.fileaccess

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

class StrictDirectoryCopyTest {
    @get:Rule val temp = TemporaryFolder()
    private class Source : DirectoryCopySource {
        val listings = mutableMapOf<String, List<DirectoryCopyEntry>?>(
            "root" to listOf(DirectoryCopyEntry("hash", "hash-a", true)),
            "hash" to listOf(DirectoryCopyEntry("data", "__data", false)))
        var closed = false
        var opened = 0
        var open: () -> InputStream? = { object : ByteArrayInputStream("source".toByteArray()) {
            override fun close() { closed = true; super.close() }
        } }
        override fun listChildren(directoryId: String) = listings[directoryId]
        override fun openFile(fileId: String): InputStream? { opened++; return open() }
    }
    private fun expectFailure(block: () -> Unit) {
        try { block(); fail("Incomplete or unsafe copy must throw") }
        catch (expected: IOException) { /* observable read/write failure */ }
        catch (expected: IllegalArgumentException) { /* rejected path/shape */ }
    }
    @Test fun copiesTheWholeTreeAndClosesTheSourceStream() {
        val source = Source()
        val dest = temp.newFolder()
        StrictDirectoryCopy.copy(source, "root", dest)
        assertEquals("source", File(dest, "hash-a/__data").readText())
        assertTrue(source.closed)
        assertEquals(1, source.opened)
    }
    @Test fun emptyListingIsValidButNullListingIsReadFailure() {
        val source = Source().apply { listings["root"] = emptyList() }
        val dest = temp.newFolder()
        StrictDirectoryCopy.copy(source, "root", dest)
        assertTrue(dest.listFiles()!!.isEmpty())
        source.listings["root"] = null
        expectFailure { StrictDirectoryCopy.copy(source, "root", dest) }
    }
    @Test fun aNullInputStreamCannotBeSilentlySkipped() {
        val source = Source().apply { open = { null } }
        expectFailure { StrictDirectoryCopy.copy(source, "root", temp.newFolder()) }
        assertEquals(1, source.opened)
    }
    @Test fun streamReadFailureIsPropagatedAndTheStreamIsClosed() {
        val source = Source()
        source.open = { object : InputStream() {
            override fun read(): Int = throw IOException("read denied")
            override fun close() { source.closed = true }
        } }
        expectFailure { StrictDirectoryCopy.copy(source, "root", temp.newFolder()) }
        assertTrue(source.closed)
    }
    @Test fun traversalAndEmptyNamesCannotWriteOutsideTheDestination() {
        val parent = temp.newFolder()
        val sentinel = File(parent, "keep").apply { writeText("original") }
        for (name in listOf("", ".", "..", "../keep", "a/b", "a\\b", "C:", "x\n")) {
            val source = Source().apply { listings["root"] = listOf(DirectoryCopyEntry("data", name, false)) }
            expectFailure { StrictDirectoryCopy.copy(source, "root", File(parent, "dest-" + java.util.UUID.randomUUID())) }
            assertEquals("original", sentinel.readText())
            assertEquals(0, source.opened)
        }
    }
    @Test fun duplicateNamesOrDirectoryCyclesDoNotProduceSuccessfulCopies() {
        val duplicate = Source().apply { listings["hash"] = listOf(
            DirectoryCopyEntry("data", "__data", false), DirectoryCopyEntry("other", "__data", false)) }
        expectFailure { StrictDirectoryCopy.copy(duplicate, "root", temp.newFolder()) }
        val cycle = Source().apply { listings["hash"] = listOf(DirectoryCopyEntry("root", "loop", true)) }
        expectFailure { StrictDirectoryCopy.copy(cycle, "root", temp.newFolder()) }
    }
    @Test fun existingDestinationFileIsNeverTruncated() {
        val source = Source()
        val dest = temp.newFolder()
        File(dest, "hash-a").mkdirs()
        val original = File(dest, "hash-a/__data").apply { writeText("existing") }
        expectFailure { StrictDirectoryCopy.copy(source, "root", dest) }
        assertEquals("existing", original.readText())
    }

    @Test fun failureInADeeperDirectoryIsNotSuccessEvenAfterEarlierFilesWereCopied() {
        val source = Source().apply {
            listings["hash"] = listOf(DirectoryCopyEntry("data", "__data", false), DirectoryCopyEntry("level2", "level2", true))
            listings["level2"] = listOf(DirectoryCopyEntry("unreadable", "level3", true))
            listings["unreadable"] = null
        }
        val dest = temp.newFolder()
        expectFailure { StrictDirectoryCopy.copy(source, "root", dest) }
        assertEquals("source", File(dest, "hash-a/__data").readText())
        assertTrue(source.closed)
        // Copy itself does not own cleanup; this partial destination must not be submitted.
    }

    @Test fun cancellationDuringReadingPropagatesAndClosesTheInput() {
        val source = Source()
        val cancelled = java.util.concurrent.CancellationException("cancel-copy")
        source.open = { object : InputStream() {
            override fun read(): Int = throw cancelled
            override fun close() { source.closed = true }
        } }
        try {
            StrictDirectoryCopy.copy(source, "root", temp.newFolder())
            fail("Cancellation must not become success or be swallowed")
        } catch (caught: java.util.concurrent.CancellationException) {
            assertSame(cancelled, caught)
        }
        assertTrue(source.closed)
    }
}
