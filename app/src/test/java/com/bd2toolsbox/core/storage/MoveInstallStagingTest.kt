package com.bd2toolsbox.core.storage

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MoveInstallStagingTest {
    @get:Rule val temp = TemporaryFolder()
    private fun data(root: File, bundle: String, content: String): File =
        File(root, "$bundle/hash/__data").apply { parentFile.mkdirs(); writeText(content) }
    private fun target(root: File) = JournalMoveFacts.target(root, "selected", "selected/hash/__data",
        listOf("uri"), listOf(LedgerRecord("uri", "Mod", "family", "selected", "HD", false, 7L)))

    @Test fun onlyTheApprovedTargetIsStagedAndInputMutationCannotChangeIt() {
        val shared = temp.newFolder("shared")
        val selected = data(shared, "selected", "approved")
        val unrelated = data(shared, "unrelated", "KEEP")
        val targets = mutableListOf(target(shared))
        MoveInstallStaging(temp.newFolder("staging"), JvmTestStagingIdentity).prepare(shared, targets).use {
            targets.clear()
            selected.writeText("changed")
            assertEquals("approved", File(it.sourceDir, "selected/hash/__data").readText())
            assertFalse(File(it.sourceDir, "unrelated").exists())
            assertEquals(1, it.targets.size)
            assertNull(it.verifyUnchanged())
            it.cleanupSources()
            assertEquals("changed", selected.readText())
            assertEquals("KEEP", unrelated.readText())
        }
    }

    @Test fun cleanupRemovesOnlyTheUnchangedSelectedFileNotTheSharedTree() {
        val shared = temp.newFolder("shared")
        val selected = data(shared, "selected", "approved")
        val unrelated = data(shared, "unrelated", "KEEP")
        MoveInstallStaging(temp.newFolder("staging"), JvmTestStagingIdentity).prepare(shared, listOf(target(shared))).use {
            assertTrue(it.cleanupSources().isEmpty())
            assertFalse(selected.exists())
            assertEquals("KEEP", unrelated.readText())
            assertTrue(shared.isDirectory)
        }
    }

    @Test fun aSourceWhichChangedAfterJournalCaptureIsRejected() {
        val shared = temp.newFolder("shared")
        val selected = data(shared, "selected", "approved")
        val frozen = target(shared)
        selected.writeText("changed!")
        assertThrows(java.io.IOException::class.java) {
            MoveInstallStaging(temp.newFolder("staging"), JvmTestStagingIdentity).prepare(shared, listOf(frozen)).close()
        }
        assertEquals("changed!", selected.readText())
    }

    @Test fun receiptOwnsItsDirectoryAndRejectsTamperingAndUseAfterClose() {
        val shared = temp.newFolder("shared")
        data(shared, "selected", "approved")
        val root = temp.newFolder("staging")
        val sentinel = File(root, "keep.txt").apply { writeText("KEEP") }
        val prepared = MoveInstallStaging(root, JvmTestStagingIdentity).prepare(shared, listOf(target(shared)))
        assertNotEquals(shared.canonicalFile, prepared.sourceDir.canonicalFile)
        File(prepared.sourceDir, "selected/hash/__data").writeText("tampered")
        assertNotNull(prepared.verifyUnchanged())
        prepared.close()
        prepared.close()
        assertFalse(prepared.sourceDir.exists())
        assertNotNull(prepared.verifyUnchanged())
        assertEquals("KEEP", sentinel.readText())
        assertEquals("approved", File(shared, "selected/hash/__data").readText())
    }

    @Test fun extraStagedFilesCannotBeSubmitted() {
        val shared = temp.newFolder("shared")
        data(shared, "selected", "approved")
        MoveInstallStaging(temp.newFolder("staging"), JvmTestStagingIdentity).prepare(shared, listOf(target(shared))).use {
            data(it.sourceDir, "injected", "UNAPPROVED")
            assertNotNull(it.verifyUnchanged())
        }
    }

    @Test fun nestedLedgerAndUriListsAreFrozenBeforeReturning() {
        val shared = temp.newFolder("shared")
        data(shared, "selected", "approved")
        val original = target(shared)
        val uris = original.modUris.toMutableList()
        val facts = original.recoveryFacts!!
        val upserts = facts.ledgerUpserts.toMutableList()
        val input = original.copy(modUris = uris, recoveryFacts = facts.copy(ledgerUpserts = upserts))
        MoveInstallStaging(temp.newFolder("staging"), JvmTestStagingIdentity).prepare(shared, listOf(input)).use {
            uris.clear()
            upserts.clear()
            assertEquals(listOf("uri"), it.targets.single().modUris)
            assertEquals(LedgerRecord("uri", "Mod", "family", "selected", "HD", false, 7L),
                it.targets.single().recoveryFacts!!.ledgerUpserts.single())
        }
    }

    @Test fun invalidEmptyDuplicateAndIncompleteSelectionsNeverProduceAReceipt() {
        val shared = temp.newFolder("shared")
        data(shared, "selected", "approved")
        val root = temp.newFolder("staging")
        val valid = target(shared)
        val invalid = listOf(
            emptyList(), listOf(valid, valid),
            listOf(valid.copy(bundleName = "../selected")),
            listOf(valid.copy(hashDir = "../hash")),
            listOf(valid.copy(recoveryFacts = null)),
            listOf(valid.copy(dataBytes = valid.dataBytes + 1))
        )
        for (targets in invalid) {
            assertThrows(java.io.IOException::class.java) {
                MoveInstallStaging(root, JvmTestStagingIdentity).prepare(shared, targets).close()
            }
            assertTrue(root.listFiles()!!.isEmpty())
            assertEquals("approved", File(shared, "selected/hash/__data").readText())
        }
    }

    @Test fun aLaterMissingSourceCleansOnlyThisPartialStagingAttempt() {
        val shared = temp.newFolder("shared")
        data(shared, "selected", "approved")
        val root = temp.newFolder("staging")
        val valid = target(shared)
        val missing = valid.copy(bundleName = "missing", modUris = emptyList(), recoveryFacts = null)
        assertThrows(java.io.IOException::class.java) {
            MoveInstallStaging(root, JvmTestStagingIdentity).prepare(shared, listOf(valid, missing)).close()
        }
        assertTrue(root.listFiles()!!.isEmpty())
        assertEquals("approved", File(shared, "selected/hash/__data").readText())
    }

    @Test fun restoreWithoutLedgerUpsertsStillBindsTheSelectedContent() {
        val shared = temp.newFolder("shared")
        data(shared, "selected", "original")
        val restore = JournalTarget("selected", "hash", 8L, emptyList())
        MoveInstallStaging(temp.newFolder("staging"), JvmTestStagingIdentity).prepare(shared, listOf(restore)).use {
            assertEquals("original", File(it.sourceDir, "selected/hash/__data").readText())
            assertNull(it.targets.single().recoveryFacts) // legacy restore: no invented install upserts
            assertNull(it.verifyUnchanged())
        }
    }

    @Test fun missingDirectoryIdentityCannotAuthorizeStaging() {
        val shared = temp.newFolder("shared")
        data(shared, "selected", "approved")
        val root = temp.newFolder("staging")
        assertThrows(java.io.IOException::class.java) {
            MoveInstallStaging(root, StagingDirectoryIdentityReader { null }).prepare(shared, listOf(target(shared))).close()
        }
        assertTrue(root.listFiles()!!.isEmpty())
    }

    private fun second(shared: File) = JournalMoveFacts.target(shared, "second", "second/hash/__data",
        listOf("uri2"), listOf(LedgerRecord("uri2", "Mod2", "family", "second", "HD", false, 7L)))

    @Test fun perTargetCheckHashesOnlyThatTargetButStillSeesStructure() {
        val shared = temp.newFolder("shared")
        data(shared, "selected", "approved")
        data(shared, "second", "second!!")
        MoveInstallStaging(temp.newFolder("staging"), JvmTestStagingIdentity)
            .prepare(shared, listOf(target(shared), second(shared))).use {
                assertNull(it.verifyTarget("selected"))
                assertNull(it.verifyTarget("second"))
                // same-size tamper of "second": invisible to selected's check, caught by its own
                File(it.sourceDir, "second/hash/__data").writeText("SECOND!!")
                assertNull(it.verifyTarget("selected"))
                assertNotNull(it.verifyTarget("second"))
                assertNotNull(it.verifyUnchanged())
            }
    }

    @Test fun perTargetCheckRejectsSizeChangesInjectedFilesAndUnknownTargets() {
        val shared = temp.newFolder("shared")
        data(shared, "selected", "approved")
        data(shared, "second", "second!!")
        val targets = listOf(target(shared), second(shared))
        MoveInstallStaging(temp.newFolder("a"), JvmTestStagingIdentity).prepare(shared, targets).use {
            File(it.sourceDir, "second/hash/__data").writeText("longer than before")
            assertNotNull("size change of another target", it.verifyTarget("selected"))
        }
        MoveInstallStaging(temp.newFolder("b"), JvmTestStagingIdentity).prepare(shared, targets).use {
            data(it.sourceDir, "injected", "UNAPPROVED")
            assertNotNull("injected file", it.verifyTarget("selected"))
        }
        MoveInstallStaging(temp.newFolder("c"), JvmTestStagingIdentity).prepare(shared, targets).use {
            assertNotNull("not frozen", it.verifyTarget("unrelated"))
            it.close()
            assertNotNull("closed", it.verifyTarget("selected"))
        }
    }

    @Test fun selectiveSnapshotDigestsOnlyRequestedFiles() {
        val root = temp.newFolder("tree")
        data(root, "a", "aaa")
        data(root, "b", "bbb")
        val asked = mutableListOf<String>()
        val snap = BoundStagingTree.snapshot(root.toPath()) { asked.add(it); it.startsWith("a/") }
        assertEquals(setOf("a/hash/__data", "b/hash/__data"), asked.toSet())
        assertTrue(snap.files.getValue("a/hash/__data").sha256.isNotEmpty())
        assertEquals("", snap.files.getValue("b/hash/__data").sha256)
        assertEquals(3L, snap.files.getValue("b/hash/__data").size)
        assertEquals(BoundStagingTree.snapshot(root.toPath()).dirs, snap.dirs)
    }
}
