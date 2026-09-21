package com.bd2toolsbox.core.storage

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 独立原始 JSON 输入，真实文件与 Store；不以被测 serializer 生成期望值。 */
class JournalRecoveryFactsTest {
    @get:Rule val temp = TemporaryFolder()

    private fun target(): JsonObject = JsonParser.parseString("""
        {"bundleName":"bundle-a","hashDir":"hash-a","dataBytes":4,"modUris":["uri-a"],
         "recoveryFacts":{"formatVersion":1,"expectedContentSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
          "ledgerUpserts":[{"modUri":"uri-a","modName":"Alice","familyKey":"family-a",
          "snapshotTargetHash":"bundle-a","quality":"HD","usedAstc":true,"installedAt":123456789}]}}
    """).asJsonObject

    private fun envelope(target: JsonObject): String = """
        {"schemaVersion":1,"entries":[{"operationId":"op-a","stage":"target_replacing",
        "atMillis":123,"targets":[$target]}]}
    """

    private fun fixture(target: JsonObject): Pair<File, OperationJournalStore> {
        val file = File(temp.newFolder(), "install_journal.json")
        file.writeText(envelope(target))
        return file to OperationJournalStore(file)
    }

    private fun facts(target: JsonObject): JsonObject = target.getAsJsonObject("recoveryFacts")
    private fun record(target: JsonObject): JsonObject = facts(target).getAsJsonArray("ledgerUpserts")[0].asJsonObject
    private fun reject(target: JsonObject) {
        val (file, store) = fixture(target)
        val before = file.readBytes()
        assertTrue("Invalid recovery facts must fail closed: $target", store.read() is JournalRead.Unreadable)
        assertArrayEquals(before, file.readBytes())
        assertNotNull(store.append(JournalEntry("new", "planned", 456L)))
        assertArrayEquals("Rejected append must preserve recovery evidence", before, file.readBytes())
    }

    @Test fun fullRecoveryFactsSurviveReadAppendAndFreshStore() {
        val target = target()
        val (file, store) = fixture(target)
        val loaded = store.read() as JournalRead.Loaded
        assertNull(store.append(loaded.entries.single().copy(stage = "target_replaced")))
        val root = JsonParser.parseString(file.readText()).asJsonObject
        val entries = root.getAsJsonArray("entries")
        assertEquals(2, entries.size())
        for (entry in entries) {
            assertEquals("Every captured ledger field and SHA must survive", target,
                entry.asJsonObject.getAsJsonArray("targets")[0].asJsonObject)
        }
        assertTrue(OperationJournalStore(file).read() is JournalRead.Loaded)
    }

    @Test fun legacyWithoutRecoveryFactsRemainsReadableButHasNoContentEvidence() {
        val t = target().apply { remove("recoveryFacts") }
        val (_, store) = fixture(t)
        assertTrue(store.read() is JournalRead.Loaded)
        assertTrue(store.recoveryVerdict(emptyMap()) is OperationJournalStore.RecoveryVerdict.PendingReconciliation)
    }

    @Test fun explicitNullFactsAreLegacyAndStillPending() {
        val t = target().apply { add("recoveryFacts", com.google.gson.JsonNull.INSTANCE) }
        val (_, store) = fixture(t)
        assertTrue(store.read() is JournalRead.Loaded)
        assertTrue(store.recoveryVerdict(emptyMap()) is OperationJournalStore.RecoveryVerdict.PendingReconciliation)
    }

    @Test fun invalidFactsShapeAndVersionAreRejected() {
        reject(target().apply { addProperty("recoveryFacts", "not-object") })
        reject(target().apply { facts(this).remove("formatVersion") })
        for (version in listOf("0", "2", "1.5", "\"1\"", "true", "null")) {
            reject(target().apply { facts(this).add("formatVersion", JsonParser.parseString(version)) })
        }
    }

    @Test fun missingOrInvalidContentDigestIsRejected() {
        reject(target().apply { facts(this).remove("expectedContentSha256") })
        for (sha in listOf("", "short", "A".repeat(64), "g".repeat(64))) {
            reject(target().apply { facts(this).addProperty("expectedContentSha256", sha) })
        }
    }

    @Test fun everyLedgerFieldIsRequiredRatherThanDefaultedByGson() {
        for (field in listOf("modUri", "modName", "familyKey", "snapshotTargetHash", "quality", "usedAstc", "installedAt")) {
            reject(target().apply { record(this).remove(field) })
        }
    }

    @Test fun ledgerPrimitiveTypesMustNotBeCoerced() {
        reject(target().apply { record(this).addProperty("modName", 12) })
        reject(target().apply { record(this).addProperty("usedAstc", "true") })
        reject(target().apply { record(this).addProperty("installedAt", "123") })
        reject(target().apply { record(this).addProperty("installedAt", 1.5) })
        reject(target().apply { record(this).addProperty("installedAt", -1) })
        reject(target().apply { record(this).add("installedAt", JsonParser.parseString("9223372036854775808")) })
    }

    @Test fun upsertsCannotBeAbsentEmptyNullOrDuplicate() {
        reject(target().apply { facts(this).remove("ledgerUpserts") })
        reject(target().apply { facts(this).add("ledgerUpserts", JsonArray()) })
        reject(target().apply { facts(this).getAsJsonArray("ledgerUpserts").add(com.google.gson.JsonNull.INSTANCE) })
        reject(target().apply { facts(this).getAsJsonArray("ledgerUpserts").add(record(this).deepCopy()) })
    }

    @Test fun ledgerMustMatchTheExactTargetAndUriSet() {
        reject(target().apply { record(this).addProperty("snapshotTargetHash", "bundle-other") })
        reject(target().apply { record(this).addProperty("modUri", "uri-other") })
        reject(target().apply { getAsJsonArray("modUris").add("uri-other") })
        reject(target().apply { getAsJsonArray("modUris").add("uri-a") })
        reject(target().apply { add("modUris", JsonArray()) })
    }

    @Test fun recoverableTargetMustHaveSafeBundleAndHashDirectory() {
        for (field in listOf("bundleName", "hashDir")) {
            for (bad in listOf("", ".", "..", "../escape", "a/b", "a\\b", "C:", "bad\nname")) {
                reject(target().apply { addProperty(field, bad) })
            }
            reject(target().apply { remove(field) })
        }
    }

    @Test fun dataBytesMustBeAnExplicitNonnegativeInteger() {
        reject(target().apply { remove("dataBytes") })
        reject(target().apply { addProperty("dataBytes", -1) })
        reject(target().apply { addProperty("dataBytes", "4") })
        reject(target().apply { addProperty("dataBytes", 4.5) })
    }

    @Test fun corruptJournalCannotBeOverwrittenByAppend() {
        val (file, store) = fixture(target())
        file.writeText("truncated durable journal")
        val before = file.readBytes()
        assertNotNull(store.append(JournalEntry("new", "planned", 456L)))
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun invalidStageCannotReplaceAValidJournal() {
        val (file, store) = fixture(target())
        val before = file.readBytes()
        assertNotNull(store.append(JournalEntry("new", "not-a-stage", 456L)))
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun anExistingBlankJournalIsUnknownAndCannotBeReplacedByAppend() {
        for (text in listOf("", " \n\t")) {
            val (file, store) = fixture(target())
            file.writeText(text)
            val before = file.readBytes()
            assertTrue(store.read() is JournalRead.Unreadable)
            assertTrue(store.recoveryVerdict(emptyMap()) is OperationJournalStore.RecoveryVerdict.Unknown)
            assertNotNull(store.append(JournalEntry("new", "planned", 456L)))
            assertArrayEquals(before, file.readBytes())
        }
    }

    @Test fun fractionalOrOverflowedSchemaVersionsCannotMasqueradeAsVersionOne() {
        for (version in listOf("1.5", "4294967297", "9223372036854775809", "1e100")) {
            val (file, store) = fixture(target())
            file.writeText(envelope(target()).replace("\"schemaVersion\":1", "\"schemaVersion\":$version"))
            val before = file.readBytes()
            assertTrue("Unsupported version $version must not be truncated", store.read() is JournalRead.Unreadable)
            assertNotNull(store.append(JournalEntry("new", "planned", 456L)))
            assertArrayEquals(before, file.readBytes())
        }
    }

    @Test fun validMultipleUpsertsRoundTripEvenWhenUriOrderDiffers() {
        val t = target().apply {
            getAsJsonArray("modUris").add("uri-b")
            val second = record(this).deepCopy().apply {
                addProperty("modUri", "uri-b")
                addProperty("modName", "Bob")
                addProperty("usedAstc", false)
            }
            val first = record(this).deepCopy()
            val list = JsonArray().apply { add(second); add(first) }
            facts(this).add("ledgerUpserts", list)
        }
        val (file, store) = fixture(t)
        val entry = (store.read() as JournalRead.Loaded).entries.single()
        assertNull(store.append(entry.copy(stage = JournalStage.TARGET_REPLACED.id)))
        val reread = OperationJournalStore(file).read() as JournalRead.Loaded
        assertEquals(listOf("uri-b", "uri-a"), reread.entries.last().targets.single()
            .recoveryFacts!!.ledgerUpserts.map { it.modUri })
        assertFalse(reread.entries.last().targets.single().recoveryFacts!!.ledgerUpserts.first().usedAstc)
    }

    @Test fun explicitZeroAndLongMaxValuesAreValidNotMissingDefaults() {
        for (value in listOf(0L, Long.MAX_VALUE)) {
            val t = target().apply {
                addProperty("dataBytes", value)
                record(this).addProperty("installedAt", value)
                record(this).addProperty("usedAstc", false)
            }
            val (_, store) = fixture(t)
            val entry = (store.read() as JournalRead.Loaded).entries.single()
            assertEquals(value, entry.targets.single().dataBytes)
            assertEquals(value, entry.targets.single().recoveryFacts!!.ledgerUpserts.single().installedAt)
            assertNull(store.append(entry.copy(stage = JournalStage.TARGET_REPLACED.id)))
        }
    }

    @Test fun invalidTypedFactsAreRejectedBeforeTheOldFileIsTouched() {
        val (file, store) = fixture(target())
        val entry = (store.read() as JournalRead.Loaded).entries.single()
        val target = entry.targets.single()
        val before = file.readBytes()
        val invalid = listOf(
            target.copy(dataBytes = -1L),
            target.copy(recoveryFacts = target.recoveryFacts!!.copy(expectedContentSha256 = "bad")),
            target.copy(recoveryFacts = target.recoveryFacts!!.copy(ledgerUpserts = emptyList())),
            target.copy(recoveryFacts = target.recoveryFacts!!.copy(formatVersion = 2))
        )
        for (t in invalid) {
            assertNotNull(store.append(entry.copy(targets = listOf(t))))
            assertArrayEquals(before, file.readBytes())
        }
    }

    @Test fun unreadableDirectoryIsNotRemovedOrConvertedIntoANewJournal() {
        val directory = temp.newFolder()
        val sentinel = File(directory, "original").apply { writeText("keep") }
        val store = OperationJournalStore(directory)
        assertTrue(store.read() is JournalRead.Unreadable)
        assertNotNull(store.append(JournalEntry("new", "planned", 456L)))
        assertTrue(directory.isDirectory)
        assertEquals("keep", sentinel.readText())
    }

    @Test fun emptyShaStaysIncompleteAndValidShaFromTempFileIsComplete() {
        // "empty sha" means no facts, simulating MainViewModel mapping to null facts
        val targetWithoutSha = target().apply { remove("recoveryFacts") }
        val (_, storeIncomplete) = fixture(targetWithoutSha)
        
        val loadedIncomplete = storeIncomplete.read() as JournalRead.Loaded
        val projectedIncomplete = JournalRecoveryProjection.project(loadedIncomplete.entries) as JournalRecoveryProjection.Result.Pending
        assertFalse("Target without recovery facts stays incomplete", projectedIncomplete.operations.single().hasCompleteFacts)

        // sha256 of a temp __data file is complete
        val data = temp.newFile("__data")
        data.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        val sha = StreamingDigest.sha256().digest(data)

        val targetWithSha = target().apply {
            facts(this).addProperty("expectedContentSha256", sha)
        }
        val (_, storeComplete) = fixture(targetWithSha)

        val loadedComplete = storeComplete.read() as JournalRead.Loaded
        val projectedComplete = JournalRecoveryProjection.project(loadedComplete.entries) as JournalRecoveryProjection.Result.Pending
        assertTrue("Target with valid facts is complete", projectedComplete.operations.single().hasCompleteFacts)
        assertEquals(sha, projectedComplete.operations.single().targets.single().recoveryFacts?.expectedContentSha256)
    }
}
