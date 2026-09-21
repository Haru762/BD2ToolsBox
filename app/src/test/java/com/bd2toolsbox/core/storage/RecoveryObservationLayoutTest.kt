package com.bd2toolsbox.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RecoveryObservationLayoutTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `safe names destFile is under destRoot and ends with __data`() {
        val destRoot = tempFolder.newFolder("recovery-obs")
        val destFile = RecoveryObservationLayout.destFile(
            destRoot = destRoot,
            operationId = "op123",
            bundleName = "bundle.zip",
            hashDir = "hash456"
        )

        val rootPath = destRoot.absoluteFile.toPath().normalize()
        val destPath = destFile.absoluteFile.toPath().normalize()

        assertTrue("destFile should start with destRoot", destPath.startsWith(rootPath))
        assertEquals("__data", destFile.name)
        
        // Assert expected structure
        val expectedRelative = "op123/bundle.zip/hash456/__data".replace("/", File.separator)
        assertTrue("destFile path string should contain the expected structure", destFile.absolutePath.endsWith(expectedRelative))
    }

    @Test
    fun `safe names with null hashDir uses root`() {
        val destRoot = tempFolder.newFolder("recovery-obs")
        val destFile = RecoveryObservationLayout.destFile(
            destRoot = destRoot,
            operationId = "op123",
            bundleName = "bundle.zip",
            hashDir = null
        )

        val expectedRelative = "op123/bundle.zip/root/__data".replace("/", File.separator)
        assertTrue(destFile.absolutePath.endsWith(expectedRelative))
    }

    @Test
    fun `unsafe segment throws IllegalArgumentException operationId with dotdot`() {
        val destRoot = tempFolder.newFolder("recovery-obs")
        assertThrows(IllegalArgumentException::class.java) {
            RecoveryObservationLayout.destFile(destRoot, "..", "bundle", "hash")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RecoveryObservationLayout.destFile(destRoot, "op/123", "bundle", "hash")
        }
    }

    @Test
    fun `unsafe segment throws IllegalArgumentException bundleName with dotdot or slash`() {
        val destRoot = tempFolder.newFolder("recovery-obs")
        assertThrows(IllegalArgumentException::class.java) {
            RecoveryObservationLayout.destFile(destRoot, "op123", "../bundle", "hash")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RecoveryObservationLayout.destFile(destRoot, "op123", "bun/dle", "hash")
        }
    }

    @Test
    fun `unsafe segment throws IllegalArgumentException hashDir with dotdot or slash`() {
        val destRoot = tempFolder.newFolder("recovery-obs")
        assertThrows(IllegalArgumentException::class.java) {
            RecoveryObservationLayout.destFile(destRoot, "op123", "bundle", "hash/dir")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RecoveryObservationLayout.destFile(destRoot, "op123", "bundle", "..")
        }
    }

    @Test
    fun `dest file path does not escape destRoot`() {
        val destRoot = tempFolder.newFolder("recovery-obs")
        
        assertThrows(IllegalArgumentException::class.java) {
            RecoveryObservationLayout.destFile(destRoot, "..", "bundle", "hash")
        }
    }
}
