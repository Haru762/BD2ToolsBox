package com.bd2toolsbox.core.storage

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class SnapshotMigrationRaceTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun migrationCannotPublishOlderDataAfterANewScan() {
        val file = temp.newFile("snapshot.json")
        file.writeText("""{"a":1,"b":[],"c":["source"]}""")
        val read = CountDownLatch(1)
        val writeFinished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val store = ModSnapshotStore(file) { input ->
            val text = input.readText()
            if (Thread.currentThread().name == "legacy-migrate") {
                read.countDown()
                // With a correct atomic migration the writer waits on the Store;
                // without it this gives the new scan time to commit first.
                writeFinished.await(1, TimeUnit.SECONDS)
            }
            text
        }
        val reader = Thread({
            try { store.loadAndMigrate(listOf("source")) } catch (t: Throwable) { failure.set(t) }
        }, "legacy-migrate")
        reader.start()
        assertTrue(read.await(5, TimeUnit.SECONDS))
        val writer = Thread {
            try {
                val entry = SnapshotEntry(name="new", character="c", costume="c", type="t",
                    uri="content://new", resolutionState="KNOWN", installState="NOT_INSTALLED", kind="PC_SOURCE")
                val pending = store.capture(listOf(entry), listOf("source"))
                assertEquals(SnapshotCommit.Written, store.commit(pending, listOf("source")))
            } catch (t: Throwable) { failure.set(t) }
            finally { writeFinished.countDown() }
        }
        writer.start()
        reader.join(10000); writer.join(10000)
        assertFalse(reader.isAlive); assertFalse(writer.isAlive)
        assertNull(failure.get())
        val result = store.load(listOf("source")) as SnapshotLoad.Loaded
        assertEquals(listOf("new"), result.entries.map { it.name })
    }

    private fun entry(name: String) = SnapshotEntry(name = name, character = "c", costume = "c", type = "t",
        uri = "content://$name", resolutionState = "KNOWN", installState = "NOT_INSTALLED", kind = "PC_SOURCE")

    /** Scan captured BEFORE migration, commits AFTER it: migration must not invalidate that revision. */
    @Test fun migrationDoesNotSupersedeAnEarlierCapturedScan() {
        val file = temp.newFile("snapshot.json")
        file.writeText("""{"a":1,"b":[],"c":["source"]}""")
        val store = ModSnapshotStore(file)
        val pending = store.capture(listOf(entry("new")), listOf("source"))
        store.loadAndMigrate(listOf("source"))
        assertEquals(SnapshotCommit.Written, store.commit(pending, listOf("source")))
        val onDisk = store.load(listOf("source")) as SnapshotLoad.Loaded
        assertFalse(onDisk.wasLegacy)
        assertEquals(listOf("new"), onDisk.entries.map { it.name })
    }

    /** With no live state yet, the legacy file is still upgraded, and a later scan still wins. */
    @Test fun migrationUpgradesFormatAndLeavesLaterScansCommittable() {
        val file = temp.newFile("snapshot.json")
        file.writeText("""{"a":1,"b":[],"c":["source"]}""")
        val store = ModSnapshotStore(file)
        assertTrue((store.loadAndMigrate(listOf("source")) as SnapshotLoad.Loaded).wasLegacy)
        assertFalse((store.load(listOf("source")) as SnapshotLoad.Loaded).wasLegacy)
        val pending = store.capture(listOf(entry("later")), listOf("source"))
        assertEquals(SnapshotCommit.Written, store.commit(pending, listOf("source")))
        assertEquals(listOf("later"),
            (store.load(listOf("source")) as SnapshotLoad.Loaded).entries.map { it.name })
    }
}
