package com.bd2toolsbox.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TargetObservationsTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun missingFileIsReadFailed() {
        val obs = TargetObservations.observeFile("op", "bundle", "hash", File(temp.root, "nope"))
        assertTrue(obs is TargetObservation.ReadFailed)
    }

    @Test
    fun regularFileYieldsObservedSha() {
        val file = temp.newFile("data")
        file.writeBytes(byteArrayOf(1, 2, 3, 4))
        val obs = TargetObservations.observeFile("op", "bundle", "hash", file)
        val observed = obs as TargetObservation.Observed
        assertEquals(64, observed.actualSha256.length)
        assertEquals(observed.actualSha256, StreamingDigest.sha256().digest(file))
    }
}
