package com.bd2toolsbox.core.storage

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CancellationException

class ConvertedInstallStagingTest {
    @get:Rule val temp = TemporaryFolder()
    private val records get() = listOf(LedgerRecord("uri-a", "Alice", "family-a", "bundle-a", "HD", false, 123456L))
    private fun writePayload(dest: File, text: String = "copied-data") {
        File(dest, "hash-a").mkdirs()
        File(dest, "hash-a/__data").writeText(text)
    }
    private fun prepare(root: File, text: String = "copied-data"): PreparedConvertedInstall {
        val result = stagingForJvm(root).prepare("bundle-a", "hash-a", records) {
            writePayload(it, text)
            true
        }
        assertTrue("Expected an independently staged payload, got $result", result is ConvertedInstallPreparation.Ready)
        return (result as ConvertedInstallPreparation.Ready).prepared
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test fun capturedFactsDescribeTheExactCopiedFileAndEveryLedgerField() {
        val root = temp.newFolder()
        val prepared = prepare(root, "actual bytes, not stale source metadata")
        prepared.use {
            assertEquals("bundle-a", it.target.bundleName)
            assertEquals("hash-a", it.target.hashDir)
            assertEquals(listOf("uri-a"), it.target.modUris)
            assertEquals(it.dataFile.length(), it.target.dataBytes)
            assertEquals(1, it.target.recoveryFacts!!.formatVersion)
            assertEquals(sha(it.dataFile.readBytes()), it.target.recoveryFacts!!.expectedContentSha256)
            assertEquals(records, it.target.recoveryFacts!!.ledgerUpserts)
            assertNull(it.verifyUnchanged())
        }
    }

    @Test fun laterChangesToTheOriginalSourceCannotChangeThePreparedBytes() {
        val source = temp.newFile().apply { writeText("original") }
        val result = stagingForJvm(temp.newFolder()).prepare("bundle-a", "hash-a", records) {
            File(it, "hash-a").mkdirs()
            source.copyTo(File(it, "hash-a/__data"))
            true
        } as ConvertedInstallPreparation.Ready
        result.prepared.use {
            source.writeText("replaced")
            assertEquals("original", it.dataFile.readText())
            assertNull(it.verifyUnchanged())
        }
        assertEquals("replaced", source.readText())
    }

    @Test fun simultaneousSameBundlePreparationsNeverReuseTheSameDirectory() {
        val root = temp.newFolder()
        val first = prepare(root, "one")
        val second = prepare(root, "two")
        assertNotEquals(first.sourceDir.canonicalPath, second.sourceDir.canonicalPath)
        first.close()
        second.use {
            assertEquals("two", it.dataFile.readText())
            assertNull(it.verifyUnchanged())
        }
    }

    @Test fun closeOnlyDeletesItsOwnedDirectoryAndCannotBeReused() {
        val root = temp.newFolder()
        val sentinel = File(root, "unrelated").apply { mkdirs() }
        File(sentinel, "keep").writeText("original")
        val prepared = prepare(root)
        prepared.close()
        prepared.close()
        assertFalse(prepared.sourceDir.exists())
        assertNotNull(prepared.verifyUnchanged())
        assertEquals("original", File(sentinel, "keep").readText())
        assertEquals(listOf("unrelated"), root.listFiles()!!.map { it.name })
    }

    @Test fun sourceReturningFalseCleansOnlyItsAttemptAndReportsFailure() {
        val root = temp.newFolder()
        val sentinel = File(root, "keep").apply { writeText("do not touch") }
        val result = stagingForJvm(root).prepare("bundle-a", "hash-a", records) {
            writePayload(it)
            false
        }
        assertTrue(result is ConvertedInstallPreparation.Failed)
        assertEquals("do not touch", sentinel.readText())
        assertEquals(listOf("keep"), root.listFiles()!!.map { it.name })
    }

    @Test fun copyFailurePreservesTheCauseAndCleansPartialFiles() {
        val root = temp.newFolder()
        var called = false
        val result = stagingForJvm(root).prepare("bundle-a", "hash-a", records) {
            called = true
            writePayload(it)
            throw IOException("source refused input stream")
        }
        assertTrue(called)
        assertTrue(result is ConvertedInstallPreparation.Failed)
        val error = (result as ConvertedInstallPreparation.Failed).error
        assertTrue((error.message + error.detail.orEmpty()).contains("source refused input stream"))
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun cancellationIsRethrownAfterCleaningTheOwnedPartialDirectory() {
        val root = temp.newFolder()
        val cancellation = CancellationException("cancel prepare")
        try {
            stagingForJvm(root).prepare("bundle-a", "hash-a", records) {
                writePayload(it)
                throw cancellation
            }
            fail("Cancellation must not be converted into an ordinary install failure")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun invalidTargetPathsAreRejectedBeforeCallingTheSource() {
        for (bad in listOf("", ".", "..", "../escape", "a/b", "a\\b", "C:", "bad\nname")) {
            for (badBundle in listOf(true, false)) {
                var called = false
                val result = stagingForJvm(temp.newFolder()).prepare(
                    if (badBundle) bad else "bundle-a", if (badBundle) "hash-a" else bad, records
                ) { called = true; true }
                assertFalse(called)
                assertTrue(result is ConvertedInstallPreparation.Failed)
            }
        }
        assertTrue(stagingForJvm(temp.newFolder()).prepare("bundle-a", null, records) {
            fail("null hash must not access source"); true
        } is ConvertedInstallPreparation.Failed)
    }

    @Test fun incompleteOrConflictingLedgerFactsAreRejectedBeforeCopy() {
        val invalid = listOf(emptyList(), records + records, listOf(records.single().copy(modName = "")),
            listOf(records.single().copy(snapshotTargetHash = "wrong-bundle")),
            listOf(records.single().copy(installedAt = -1L)))
        for (list in invalid) {
            var called = false
            val result = stagingForJvm(temp.newFolder()).prepare("bundle-a", "hash-a", list) {
                called = true; true
            }
            assertFalse(called)
            assertTrue(result is ConvertedInstallPreparation.Failed)
        }
    }

    @Test fun ledgerInputIsCapturedBeforeTheSourceCallbackCanMutateIt() {
        val mutableRecords = records.toMutableList()
        val result = stagingForJvm(temp.newFolder()).prepare("bundle-a", "hash-a", mutableRecords) {
            mutableRecords.clear()
            writePayload(it)
            true
        } as ConvertedInstallPreparation.Ready
        result.prepared.use { assertEquals(records, it.target.recoveryFacts!!.ledgerUpserts) }
    }

    @Test fun missingWrongHashOrDirectoryInsteadOfDataNeverProducesReady() {
        for (mode in listOf("missing", "wrong-hash", "directory")) {
            val root = temp.newFolder()
            var called = false
            val result = stagingForJvm(root).prepare("bundle-a", "hash-a", records) {
                called = true
                when (mode) {
                    "wrong-hash" -> File(it, "other").apply { mkdirs(); File(this, "__data").writeText("x") }
                    "directory" -> File(it, "hash-a/__data").mkdirs()
                }
                true
            }
            assertTrue(called)
            assertTrue(result is ConvertedInstallPreparation.Failed)
            assertTrue(root.listFiles()!!.isEmpty())
        }
    }

    @Test fun stagingRootWhichIsAFileIsNeverOverwritten() {
        val root = temp.newFile().apply { writeText("root sentinel") }
        assertTrue(stagingForJvm(root).prepare("bundle-a", "hash-a", records) {
            fail("No writable staging root"); true
        } is ConvertedInstallPreparation.Failed)
        assertEquals("root sentinel", root.readText())
    }

    @Test fun sameLengthMutationAndMissingDataInvalidateThePreparedReceipt() {
        val prepared = prepare(temp.newFolder(), "ABCD")
        prepared.use {
            it.dataFile.writeText("WXYZ")
            assertNotNull(it.verifyUnchanged())
            it.dataFile.delete()
            assertNotNull(it.verifyUnchanged())
        }
    }

    @Test fun unrecordedFileAdditionCannotSneakIntoTheDirectoryCopy() {
        prepare(temp.newFolder()).use {
            File(it.sourceDir, "extra").writeText("not in the prepared source")
            assertNotNull(it.verifyUnchanged())
        }
    }

    @Test fun originalCompanionFilesArePreservedAndBoundByTheReceipt() {
        val result = stagingForJvm(temp.newFolder()).prepare("bundle-a", "hash-a", records) {
            writePayload(it)
            File(it, "hash-a/__info").writeText("metadata")
            true
        } as ConvertedInstallPreparation.Ready
        result.prepared.use {
            assertEquals("metadata", File(it.sourceDir, "hash-a/__info").readText())
            assertNull(it.verifyUnchanged())
            File(it.sourceDir, "hash-a/__info").writeText("modified")
            assertNotNull(it.verifyUnchanged())
        }
    }

    @Test fun emptyDataHasTheRealEmptyDigestRatherThanAnAbsentReceipt() {
        prepare(temp.newFolder(), "").use {
            assertEquals(0L, it.target.dataBytes)
            assertEquals(sha(byteArrayOf()), it.target.recoveryFacts!!.expectedContentSha256)
            assertNull(it.verifyUnchanged())
        }
    }

    // Windows 宿主仅覆盖 NTFS junction，不把它写成实际 POSIX symlink/设备覆盖。
    private fun createDirectoryLink(link: File, target: File) {
        if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
            val child = ProcessBuilder("cmd", "/c", "mklink", "/J", link.absolutePath, target.absolutePath)
                .redirectErrorStream(true).start()
            val output = child.inputStream.bufferedReader().use { it.readText() }
            assertEquals("junction fixture creation: $output", 0, child.waitFor())
        } else {
            java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath())
        }
        assertTrue(link.exists())
    }

    @Test fun linkedCompanionIsRejectedWithoutReadingOrDeletingOutsideTheOwnedDirectory() {
        val root = temp.newFolder()
        val outside = temp.newFolder()
        val sentinel = File(outside, "keep").apply { writeText("outside") }
        var link: File? = null
        var copied = false
        try {
            val result = stagingForJvm(root).prepare("bundle-a", "hash-a", records) {
                copied = true
                writePayload(it)
                link = File(it, "linked-companion")
                createDirectoryLink(link!!, outside)
                true
            }
            assertTrue("Source callback must actually exercise a link", copied)
            assertTrue(result is ConvertedInstallPreparation.Failed)
            assertEquals("outside", sentinel.readText())
            assertTrue(root.listFiles()!!.isEmpty())
        } finally {
            // NIO delete unlinks this directory entry, never recursively follows its target.
            link?.let { java.nio.file.Files.deleteIfExists(it.toPath()) }
        }
    }

    @Test fun aLinkAddedAfterPreparationIsRejectedAndCloseCannotTraverseIt() {
        val root = temp.newFolder()
        val outside = temp.newFolder()
        val sentinel = File(outside, "keep").apply { writeText("outside") }
        val prepared = prepare(root)
        val link = File(prepared.sourceDir, "linked-companion")
        try {
            createDirectoryLink(link, outside)
            assertNotNull(prepared.verifyUnchanged())
            prepared.close()
            assertEquals("outside", sentinel.readText())
            assertFalse(prepared.sourceDir.exists())
        } finally {
            java.nio.file.Files.deleteIfExists(link.toPath())
            prepared.close()
        }
    }

    @Test fun aLinkedStagingRootIsRejectedBeforeCopyAndLeavesTheExternalDirectoryAlone() {
        val parent = temp.newFolder()
        val outside = temp.newFolder()
        val sentinel = File(outside, "keep").apply { writeText("outside") }
        val rootLink = File(parent, "linked-root")
        var copied = false
        try {
            createDirectoryLink(rootLink, outside)
            val result = stagingForJvm(rootLink).prepare("bundle-a", "hash-a", records) {
                copied = true
                writePayload(it)
                true
            }
            assertTrue(result is ConvertedInstallPreparation.Failed)
            assertFalse(copied)
            assertEquals("outside", sentinel.readText())
            assertEquals(listOf("keep"), outside.listFiles()!!.map { it.name })
        } finally {
            java.nio.file.Files.deleteIfExists(rootLink.toPath())
        }
    }

    @Test fun emptyDirectoryAdditionsAndRemovalsArePartOfTheBoundTree() {
        val root = temp.newFolder()
        val added = prepare(root)
        added.use {
            File(it.sourceDir, "extra-empty").mkdirs()
            assertNotNull("A new empty directory is still new copied tree content", it.verifyUnchanged())
        }
        val result = stagingForJvm(root).prepare("bundle-a", "hash-a", records) {
            writePayload(it)
            File(it, "original-empty").mkdirs()
            true
        } as ConvertedInstallPreparation.Ready
        result.prepared.use {
            java.nio.file.Files.delete(File(it.sourceDir, "original-empty").toPath())
            assertNotNull("Removing an original empty directory must invalidate the tree", it.verifyUnchanged())
        }
    }

    @Test fun replacingTheParentWithAJunctionCannotRedirectVerificationOrCleanup() {
        val root = temp.newFolder()
        val prepared = prepare(root)
        val outside = temp.newFolder()
        val externalReplica = File(outside, prepared.sourceDir.name).apply { mkdirs() }
        writePayload(externalReplica)
        val retainedRoot = File(root.parentFile, root.name + "-retained")
        assertEquals(requireNotNull(root.parentFile).canonicalFile, requireNotNull(retainedRoot.parentFile).canonicalFile)
        java.nio.file.Files.move(root.toPath(), retainedRoot.toPath())
        try {
            createDirectoryLink(root, outside)
            val verdict = prepared.verifyUnchanged()
            try { prepared.close() } catch (_: IOException) { /* refusal is safe */ }
            assertEquals("copied-data", File(externalReplica, "hash-a/__data").readText())
            assertNotNull("Same bytes via an external ancestor are not owned staging", verdict)
        } finally {
            java.nio.file.Files.deleteIfExists(root.toPath())
            java.nio.file.Files.move(retainedRoot.toPath(), root.toPath())
            prepared.close()
        }
    }

    @Test fun replacingTheOwnedDirectoryAtTheSamePathCannotGiveOwnershipOfTheReplacement() {
        val root = temp.newFolder()
        val prepared = prepare(root)
        val retained = File(root, "retained-original")
        java.nio.file.Files.move(prepared.sourceDir.toPath(), retained.toPath())
        prepared.sourceDir.mkdirs()
        writePayload(prepared.sourceDir)
        val verdict = prepared.verifyUnchanged()
        try { prepared.close() } catch (_: IOException) { /* refusal is safe */ }
        assertEquals("copied-data", File(prepared.sourceDir, "hash-a/__data").readText())
        assertNotNull("Same path/bytes cannot replace the acquired directory identity", verdict)
        assertEquals("copied-data", File(retained, "hash-a/__data").readText())
    }

    @Test fun replacingOwnedDirectoryInsideCopyCannotYieldReadyOrDeleteReplacement() {
        val root = temp.newFolder()
        val retained = File(root, "retained-from-copy")
        var replacement: File? = null
        val result = stagingForJvm(root).prepare("bundle-a", "hash-a", records) { destination ->
            writePayload(destination)
            java.nio.file.Files.move(destination.toPath(), retained.toPath())
            destination.mkdirs()
            writePayload(destination)
            replacement = destination
            true
        }
        // A copy callback cannot transfer the lease to a different directory at the same path.
        if (result is ConvertedInstallPreparation.Ready) result.prepared.close()
        assertEquals("copied-data", File(requireNotNull(replacement), "hash-a/__data").readText())
        assertEquals("copied-data", File(retained, "hash-a/__data").readText())
        assertTrue("Identity loss must be rejected during prepare, not only at submit", result is ConvertedInstallPreparation.Failed)
    }

    @Test fun missingIdentityIsRejectedBeforeCreatingChildOrCallingCopy() {
        val root = temp.newFolder()
        var copied = false
        val result = ConvertedInstallStaging(root, StagingDirectoryIdentityReader { null })
            .prepare("bundle-a", "hash-a", records) { copied = true; writePayload(it); true }
        assertTrue(result is ConvertedInstallPreparation.Failed)
        assertFalse(copied)
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun identityReadFailureIsRejectedBeforeCreatingChildOrCallingCopy() {
        val root = temp.newFolder()
        var copied = false
        val result = ConvertedInstallStaging(root, StagingDirectoryIdentityReader { throw IOException("identity unavailable") })
            .prepare("bundle-a", "hash-a", records) { copied = true; writePayload(it); true }
        assertTrue(result is ConvertedInstallPreparation.Failed)
        assertFalse(copied)
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun lostIdentityCannotPassVerificationOrAuthorizeDeletion() {
        val root = temp.newFolder()
        var identityAvailable = true
        val reader = StagingDirectoryIdentityReader { path ->
            if (identityAvailable) JvmTestStagingIdentity.read(path) else null
        }
        val result = ConvertedInstallStaging(root, reader).prepare("bundle-a", "hash-a", records) {
            writePayload(it); true
        } as ConvertedInstallPreparation.Ready
        identityAvailable = false
        assertNotNull(result.prepared.verifyUnchanged())
        result.prepared.close()
        assertEquals("copied-data", result.prepared.dataFile.readText())
    }

    @Test fun productionNioProviderNeverTreatsUnknownIdentityAsOwned() {
        val root = temp.newFolder()
        val key = java.nio.file.Files.readAttributes(root.toPath(),
            java.nio.file.attribute.BasicFileAttributes::class.java,
            java.nio.file.LinkOption.NOFOLLOW_LINKS).fileKey()
        var copied = false
        val result = ConvertedInstallStaging(root).prepare("bundle-a", "hash-a", records) {
            copied = true; writePayload(it); true
        }
        if (key == null) {
            assertTrue("Unsupported identity must fail closed, not null == null", result is ConvertedInstallPreparation.Failed)
            assertFalse(copied)
            assertTrue(root.listFiles()!!.isEmpty())
        } else {
            assertTrue(result is ConvertedInstallPreparation.Ready)
            (result as ConvertedInstallPreparation.Ready).prepared.use { assertNull(it.verifyUnchanged()) }
        }
    }
}
