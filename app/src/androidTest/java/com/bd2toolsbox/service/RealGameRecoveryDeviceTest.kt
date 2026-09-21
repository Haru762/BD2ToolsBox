package com.bd2toolsbox.service

import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bd2toolsbox.core.fileaccess.FileDirectoryCopySource
import com.bd2toolsbox.core.fileaccess.StrictDirectoryCopy
import com.bd2toolsbox.core.storage.ConvertedInstallPreparation
import com.bd2toolsbox.core.storage.ConvertedInstallStaging
import com.bd2toolsbox.core.storage.JournalMoveFacts
import com.bd2toolsbox.core.storage.JournalStage
import com.bd2toolsbox.core.storage.LedgerRecord
import com.bd2toolsbox.core.storage.LedgerWrite
import com.bd2toolsbox.core.storage.OperationJournalStore
import com.bd2toolsbox.core.storage.StreamingDigest
import com.bd2toolsbox.data.model.InstalledModRecord
import com.bd2toolsbox.data.repository.ConvertedInstallSubmitter
import com.bd2toolsbox.data.repository.InstalledModRepository
import com.bd2toolsbox.data.repository.MoveInstallSubmitter
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Explicitly authorized T08 fault injection on ONE host-selected real game __data.
 * Not in ordinary device suites: missing explicit arguments fail before side effects.
 * Host must stop the game, back up target/app data, watch ready.json, force-stop this
 * process, drive the real recovery UI, and restore backups. No automatic cleanup: a
 * crashed/failed run must retain its journal and staged evidence for investigation.
 */
@RunWith(AndroidJUnit4::class)
class RealGameRecoveryDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun config(): Pair<File, JSONObject> {
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("allowGameWrite") == "single-target-backed-up") { "Explicit game-write authorization required" }
        val runId = requireNotNull(args.getString("runId"))
        check(runId.matches(Regex("[a-z0-9-]{1,64}")))
        val root = File(requireNotNull(context.externalCacheDir), "t08-real-$runId")
        val cfg = JSONObject(File(root, "config.json").readText())
        
        val expectedAppAndroidId = cfg.getString("expectedAppAndroidId")
        check(expectedAppAndroidId.matches(Regex("[a-f0-9]{16}"))) { "Malformed expectedAppAndroidId" }
        val appAndroidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        // Android 8+ scopes this ID by signing key and user. The host separately
        // checks the same device's shell ID against its whitelist before every write.
        check(appAndroidId == expectedAppAndroidId) { "App-scoped Android ID mismatch: $appAndroidId" }
        check(Build.SUPPORTED_ABIS.first() == "x86_64" && Build.VERSION.SDK_INT == 32)
        
        check(cfg.getString("runId") == runId)
        check(cfg.getString("bundleName").matches(Regex("[a-f0-9]{32}")))
        check(cfg.getString("hashDir").matches(Regex("[a-f0-9]{32}")))
        check(cfg.getString("oldSha256").matches(Regex("[a-f0-9]{64}")))
        check(cfg.getString("newSha256").matches(Regex("[a-f0-9]{64}")))
        check(cfg.getString("newSha256") != cfg.getString("oldSha256"))
        check(cfg.getString("mode") in setOf("converted", "move"))
        return root to cfg
    }

    private fun record(cfg: JSONObject) = LedgerRecord(
        modUri = "content://com.bd2toolsbox.e2e/" + cfg.getString("runId"),
        modName = "T08 crash acceptance " + cfg.getString("mode"),
        familyKey = cfg.getString("bundleName"),
        snapshotTargetHash = cfg.getString("bundleName"),
        quality = "HD", usedAstc = false, installedAt = cfg.getLong("installedAt")
    )

    private fun hash(file: File): String? = if (file.isFile) StreamingDigest.sha256().digest(file) else null

    private fun marker(root: File, name: String, json: JSONObject) {
        json.put("pid", Process.myPid())
        FileOutputStream(File(root, name)).use {
            it.write(json.toString().toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
    }

    private fun awaitShizuku() {
        val until = android.os.SystemClock.elapsedRealtime() + 10_000
        while (!ShizukuManager.isAvailable() && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(100)
        check(ShizukuManager.isAvailable()) { "Shizuku must already be running and authorized" }
    }

    private suspend fun observedGameHash(root: File, cfg: JSONObject, phase: String): String {
        val destination = File(root, "$phase-observed.__data")
        check(!destination.exists()) { "Use a fresh phase destination" }
        check(ShizukuManager.copyGameDataForRecovery(cfg.getString("bundleName"), cfg.getString("hashDir"), destination.absolutePath))
        return requireNotNull(hash(destination))
    }

    @Test
    fun replaceOneTargetAndWaitForHostProcessDeath() {
        val (root, cfg) = config()
        check(!File(root, "ready.json").exists()) { "This run has already reached the crash point" }
        awaitShizuku()
        runBlocking {
            val repo = InstalledModRepository(context)
            check(repo.checkSubmitAllowed() == null) { "An existing operation needs recovery; do not overwrite it" }
            check(repo.checkRecovery() is OperationJournalStore.RecoveryVerdict.Consistent)
            val unrelated = InstalledModRecord(
                "content://com.bd2toolsbox.e2e/unrelated/" + cfg.getString("runId"),
                "Preserved unrelated acceptance record", "e2e-unrelated", "e2e-unrelated-bundle", "HD", false, 7L
            )
            assertEquals(LedgerWrite.Written, repo.put(unrelated))
            val ledgerBefore = requireNotNull(hash(repo.file()))
            assertEquals(cfg.getString("oldSha256"), observedGameHash(root, cfg, "before"))
            val frozen = record(cfg)
            check(repo.load()[frozen.modUri] == null)
            val bundle = cfg.getString("bundleName")
            val hashDir = cfg.getString("hashDir")
            val operation = "e2e-" + cfg.getString("runId")

            // Invoked by production submitter ONLY after TARGET_REPLACED is durable.
            val crashBeforeLedger: () -> Nothing = {
                assertEquals(ledgerBefore, hash(repo.file()))
                assertTrue(repo.journalFile().readText().contains(JournalStage.TARGET_REPLACED.id))
                marker(root, "ready.json", JSONObject()
                    .put("runId", cfg.getString("runId"))
                    .put("mode", cfg.getString("mode"))
                    .put("operationId", operation)
                    .put("ledgerBeforeSha256", ledgerBefore)
                    .put("journalSha256", hash(repo.journalFile()))
                    .put("expectedTargetSha256", cfg.getString("newSha256")))
                // No voluntary cancellation/exception: the external host kills this process.
                // A missing host yields failure without clearing the recovery evidence.
                CountDownLatch(1).await(120, TimeUnit.SECONDS)
                error("Host did not terminate the process at the crash point")
            }
            if (cfg.getString("mode") == "converted") {
                val source = File(root, "source/$bundle")
                assertEquals(cfg.getString("newSha256"), hash(File(source, "$hashDir/__data")))
                val adapter = FileDirectoryCopySource(source)
                val preparation = ConvertedInstallStaging(File(root, "bound-staging")).prepare(
                    bundle, hashDir, listOf(frozen)
                ) { destination -> StrictDirectoryCopy.copy(adapter, adapter.rootId, destination); true }
                check(preparation is ConvertedInstallPreparation.Ready) { "Bound staging failed: $preparation" }
                val prepared = preparation.prepared
                assertEquals(cfg.getString("newSha256"), prepared.target.recoveryFacts!!.expectedContentSha256)
                prepared.use {
                    ConvertedInstallSubmitter(repo).submit(operation, prepared.target,
                        replaceTarget = {
                            check(prepared.verifyUnchanged() == null)
                            ShizukuManager.installConvertedBundle(prepared.sourceDir.absolutePath, bundle)
                        },
                        commitLedger = { crashBeforeLedger() })
                }
            } else {
                val shared = File("/storage/emulated/0/Download/Shared")
                assertEquals(setOf(bundle), shared.listFiles()!!.map { it.name }.toSet())
                assertEquals(setOf(hashDir), File(shared, bundle).listFiles()!!.map { it.name }.toSet())
                assertEquals(setOf("__data"), File(shared, "$bundle/$hashDir").listFiles()!!.map { it.name }.toSet())
                val target = JournalMoveFacts.target(shared, bundle, "$bundle/$hashDir/__data", listOf(frozen.modUri), listOf(frozen))
                assertEquals(cfg.getString("newSha256"), target.recoveryFacts!!.expectedContentSha256)
                MoveInstallSubmitter(repo).submit(operation, listOf(target),
                    moveFiles = { ShizukuManager.moveDownloadToGame() },
                    commitLedger = { crashBeforeLedger() })
            }
            fail("The crash-point submission must not return normally")
        }
    }

    @Test
    fun freshProcessRejectsDuplicateSubmission() {
        val (root, cfg) = config()
        val ready = JSONObject(File(root, "ready.json").readText())
        assertNotEquals(ready.getInt("pid"), Process.myPid())
        val repo = InstalledModRepository(context)
        assertTrue(repo.checkRecovery() is OperationJournalStore.RecoveryVerdict.PendingReconciliation)
        assertNotNull(repo.checkSubmitAllowed())
        val beforeJournal = repo.journalFile().readBytes()
        val beforeLedger = repo.file().readBytes()
        var moveCalls = 0
        var ledgerCalls = 0
        runBlocking {
            val result = MoveInstallSubmitter(repo).submit("must-not-run", emptyList(),
                moveFiles = { moveCalls++; true to "must not move" },
                commitLedger = { ledgerCalls++; null })
            assertNotNull(result.error)
        }
        assertEquals(0, moveCalls)
        assertEquals(0, ledgerCalls)
        assertArrayEquals(beforeJournal, repo.journalFile().readBytes())
        assertArrayEquals(beforeLedger, repo.file().readBytes())
        marker(root, "pending-verified.json", JSONObject().put("pending", true).put("moveCalls", moveCalls))
    }

    @Test
    fun recoveredLedgerHasFrozenRecordAndUnrelatedRecord() {
        val (root, cfg) = config()
        val repo = InstalledModRepository(context)
        assertTrue(repo.checkRecovery() is OperationJournalStore.RecoveryVerdict.Consistent)
        assertNull(repo.checkSubmitAllowed())
        val expected = record(cfg)
        val actual = requireNotNull(repo.load()[expected.modUri])
        assertEquals(InstalledModRecord(expected.modUri, expected.modName, expected.familyKey,
            expected.snapshotTargetHash, expected.quality, expected.usedAstc, expected.installedAt), actual)
        assertEquals("Preserved unrelated acceptance record",
            repo.load()["content://com.bd2toolsbox.e2e/unrelated/" + cfg.getString("runId" )]?.modName)
        assertFalse(repo.journalFile().exists())
        marker(root, "recovered-verified.json", JSONObject().put("ledgerSha256", hash(repo.file())).put("recordCount", repo.load().size))
    }
}
