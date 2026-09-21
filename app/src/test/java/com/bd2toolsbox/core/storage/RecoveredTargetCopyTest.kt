package com.bd2toolsbox.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RecoveredTargetCopyTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun copiesRegularDataFileThenObservesSha() {
        val shared = temp.newFolder("Shared")
        val src = File(File(File(shared, "bundle-a"), "hash-a"), "__data")
        src.parentFile?.mkdirs()
        src.writeBytes(byteArrayOf(4, 5, 6))
        val dest = temp.newFolder("obs")
        val obs = RecoveredTargetCopy.copyThenObserve(shared, dest, "op-a", "bundle-a", "hash-a")
        val observed = obs as TargetObservation.Observed
        assertEquals(StreamingDigest.sha256().digest(src), observed.actualSha256)
        assertTrue(File(File(dest, "obs-op-a"), "__data").isFile)
    }

    @Test
    fun missingTargetIsReadFailed() {
        val obs = RecoveredTargetCopy.copyThenObserve(
            temp.newFolder("Shared"), temp.newFolder("obs"), "op-a", "bundle-a", "hash-a"
        )
        assertTrue(obs is TargetObservation.ReadFailed)
    }

    @Test
    fun bundleNameWithTraversalOrSeparatorYieldsReadFailed() {
        val shared = temp.newFolder("Shared")
        val dest = temp.newFolder("obs")
        
        val obs1 = RecoveredTargetCopy.copyThenObserve(shared, dest, "op-1", "../bundle", "hash")
        assertTrue(obs1 is TargetObservation.ReadFailed)
        
        val obs2 = RecoveredTargetCopy.copyThenObserve(shared, dest, "op-2", "a/b", "hash")
        assertTrue(obs2 is TargetObservation.ReadFailed)
        
        val obs3 = RecoveredTargetCopy.copyThenObserve(shared, dest, "op-3", "a\\b", "hash")
        assertTrue(obs3 is TargetObservation.ReadFailed)
    }

    @Test
    fun deletingDestChildrenLeavesSharedRootUnchanged() {
        val shared = temp.newFolder("Shared")
        val src = File(File(File(shared, "bundle-a"), "hash-a"), "__data")
        src.parentFile?.mkdirs()
        val originalBytes = byteArrayOf(4, 5, 6)
        src.writeBytes(originalBytes)
        val dest = temp.newFolder("obs")
        RecoveredTargetCopy.copyThenObserve(shared, dest, "op-a", "bundle-a", "hash-a")
        
        dest.listFiles()?.forEach { it.deleteRecursively() }
        
        org.junit.Assert.assertArrayEquals(originalBytes, src.readBytes())
    }
}
