package com.bd2toolsbox.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** T62：.nomedia 目录级隔离的纯 JVM 契约（真文件、临时目录）。 */
class MediaExclusionManagerTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun createsAnEmptyRegularMarkerFile() {
        val dir = temp.newFolder("ModAssets")
        assertEquals(MediaExclusionResult.Created, MediaExclusionManager.ensureMarker(dir))
        val marker = File(dir, MediaExclusionManager.MARKER_NAME)
        assertTrue(marker.isFile)
        assertEquals("标记必须是空文件", 0L, marker.length())
    }

    @Test fun existingMarkerIsLeftUnchangedAndReportedAsPresent() {
        val dir = temp.newFolder("ModAssets")
        val marker = File(dir, MediaExclusionManager.MARKER_NAME).apply { writeText("keep me") }
        assertEquals(MediaExclusionResult.AlreadyPresent, MediaExclusionManager.ensureMarker(dir))
        assertEquals("已有同名文件不许被覆盖/清空", "keep me", marker.readText())
    }

    @Test fun mixedUseRootsAreRejectedByDefault() {
        for (name in listOf("DCIM", "Pictures", "Movies", "Download", "Downloads", "Documents")) {
            val dir = temp.newFolder(name)
            val result = MediaExclusionManager.ensureMarker(dir)
            assertTrue("$name 必须被拒绝，实得 $result", result is MediaExclusionResult.Rejected)
            assertFalse("被拒时不能留下标记", File(dir, MediaExclusionManager.MARKER_NAME).exists())
        }
    }

    @Test fun modSubdirectoryUnderAMixedUseRootIsAllowed() {
        val pictures = temp.newFolder("Pictures")
        val modDir = File(pictures, "BD2Mods").apply { mkdirs() }
        assertEquals(MediaExclusionResult.Created, MediaExclusionManager.ensureMarker(modDir))
    }

    @Test fun explicitConfirmationCanOverrideTheMixedUseRefusal() {
        val dir = temp.newFolder("Download")
        assertEquals(
            MediaExclusionResult.Created,
            MediaExclusionManager.ensureMarker(dir, refuseMixedUseRoot = false)
        )
    }

    @Test fun missingDirectoryFailsInsteadOfCreatingIt() {
        val missing = File(temp.root, "does-not-exist")
        assertTrue(MediaExclusionManager.ensureMarker(missing) is MediaExclusionResult.Failed)
        assertFalse(missing.exists())
    }

    @Test fun markerNameIsExactNotAPrefix() {
        assertTrue(MediaExclusionManager.isMarkerName(".nomedia"))
        assertFalse(".nomedia.txt", MediaExclusionManager.isMarkerName(".nomedia.txt"))
        assertFalse(MediaExclusionManager.isMarkerName(".nomedia.bin"))
        assertFalse(MediaExclusionManager.isMarkerName("nomedia"))
        assertFalse(MediaExclusionManager.isMarkerName(".nomedia2"))
    }

    @Test fun providerReturningARenamedMarkerIsNotProtected() {
        assertTrue(MediaExclusionManager.verifyProviderName(".nomedia"))
        assertFalse(MediaExclusionManager.verifyProviderName(".nomedia.txt"))
        assertFalse(MediaExclusionManager.verifyProviderName(".nomedia.bin"))
        assertFalse(MediaExclusionManager.verifyProviderName(null))
        assertFalse(MediaExclusionManager.verifyProviderName(""))
    }

    @Test fun modScanSkipsOnlyTheMarker() {
        assertTrue(MediaExclusionManager.shouldSkipInModScan(".nomedia"))
        assertFalse(MediaExclusionManager.shouldSkipInModScan("char000104.dat"))
        assertFalse(MediaExclusionManager.shouldSkipInModScan("skel.atlas"))
        assertFalse(MediaExclusionManager.shouldSkipInModScan(".nomedia.txt"))
    }

    @Test fun mixedUseRootCheckIsCaseInsensitiveAndTrimmed() {
        assertTrue(MediaExclusionManager.isMixedUseRoot("  DCIM "))
        assertTrue(MediaExclusionManager.isMixedUseRoot("downloads"))
        assertFalse(MediaExclusionManager.isMixedUseRoot("BD2Mods"))
        assertFalse(MediaExclusionManager.isMixedUseRoot(null))
    }
}
