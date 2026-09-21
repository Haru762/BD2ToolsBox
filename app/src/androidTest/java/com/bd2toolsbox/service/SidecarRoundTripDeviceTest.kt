package com.bd2toolsbox.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bd2toolsbox.core.storage.BackupProvenance
import com.bd2toolsbox.core.storage.BaselineSource
import com.bd2toolsbox.data.repository.BundleBackupRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Writes and reads one sidecar through the production repository.
 * Files stay under this app's external files dir. The game tree is not touched.
 */
@RunWith(AndroidJUnit4::class)
class SidecarRoundTripDeviceTest {
    private val bundleName = "t09-sidecar-probe"
    private val hashDir = "a".repeat(32)

    @After
    fun cleanup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "origin_backup/$bundleName").deleteRecursively()
    }

    @Test
    fun explicitSidecarKeysRoundTrip() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = BundleBackupRepository(context)
        val source = File(context.cacheDir, "sidecar-probe-input")
        source.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        val provenance = BackupProvenance(
            source = BaselineSource.CATALOG_DOWNLOAD,
            catalogSize = source.length(),
            quality = "HD",
        )
        assertTrue(repo.saveBackup(bundleName, hashDir, source, provenance))
        val baseline = repo.trustedBaseline(bundleName, hashDir)
        assertNotNull(baseline)
        assertEquals(BaselineSource.CATALOG_DOWNLOAD, baseline!!.source)
        assertEquals(source.length(), baseline.size)
        val meta = File(
            context.getExternalFilesDir(null),
            "origin_backup/$bundleName/$hashDir/__data.game.meta.json",
        )
        val text = meta.readText()
        assertTrue(text.contains("\"schemaVersion\""))
        assertTrue(text.contains("\"catalog-download\""))
        assertTrue(!text.contains("\"a\":"))
    }
}
