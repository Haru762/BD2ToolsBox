package com.bd2toolsbox.core.storage

import com.bd2toolsbox.core.storage.dto.StoredInstalledMod
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T35：旧格式（Gson 时代）账本 JSON 的兼容读取与 DTO 往返。
 *
 * 不依赖设备；fixture 直接内联（旧格式样本来自 Gson 的字段名约定）。
 */
class JsonMigrationTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun legacyLedger(record: String) =
        """{"schemaVersion":1,"records":{"content://mods/a":$record}}"""

    // ---------------------------------------------------------------- 往返

    @Test fun dtoRoundTripsThroughKotlinxSerialization() {
        val original = StoredInstalledMod(
            modUri = "content://mods/中文",
            modName = "测试 Mod",
            familyKey = "family",
            snapshotTargetHash = "abc123",
            quality = "HD",
            usedAstc = true,
            installedAt = 1700000000L
        )
        val text = json.encodeToString(StoredInstalledMod.serializer(), original)
        val back = json.decodeFromString(StoredInstalledMod.serializer(), text)
        assertEquals(original, back)
        assertTrue("多字节中文必须原样往返", text.contains("中文"))
    }

    // ---------------------------------------------------------------- 旧格式读取

    @Test fun readsLegacyRecordWithAllFields() {
        val result = LegacyJsonReader.readLedger(
            legacyLedger(
                """{"modUri":"content://mods/a","modName":"Old","familyKey":"f",""" +
                    """"snapshotTargetHash":"h","quality":"SD","usedAstc":true,"installedAt":123}"""
            )
        )
        assertTrue(result is LegacyJsonReader.LedgerReadResult.Loaded)
        val record = (result as LegacyJsonReader.LedgerReadResult.Loaded).records.getValue("content://mods/a")
        assertEquals("Old", record.modName)
        assertEquals("SD", record.quality)
        assertTrue(record.usedAstc)
        assertEquals(123L, record.installedAt)
    }

    @Test fun missingOptionalFieldsUseDefaults() {
        val result = LegacyJsonReader.readLedger(
            legacyLedger(
                """{"modUri":"content://mods/a","modName":"Old","familyKey":"f",""" +
                    """"snapshotTargetHash":"h","quality":"HD"}"""
            )
        )
        val record = (result as LegacyJsonReader.LedgerReadResult.Loaded).records.values.single()
        assertEquals(false, record.usedAstc)
        assertEquals(0L, record.installedAt)
    }

    @Test fun unknownFieldsAreIgnored() {
        val result = LegacyJsonReader.readLedger(
            legacyLedger(
                """{"modUri":"content://mods/a","modName":"Old","familyKey":"f",""" +
                    """"snapshotTargetHash":"h","quality":"HD","futureField":42,"nested":{"x":1}}"""
            )
        )
        assertTrue(result is LegacyJsonReader.LedgerReadResult.Loaded)
    }

    @Test fun missingRequiredFieldIsCorruptNotSilentlyDefaulted() {
        val result = LegacyJsonReader.readLedger(
            legacyLedger("""{"modName":"Old","familyKey":"f","snapshotTargetHash":"h","quality":"HD"}""")
        )
        assertTrue("缺必需字段必须明确失败", result is LegacyJsonReader.LedgerReadResult.Corrupt)
    }

    // ---------------------------------------------------------------- 边界

    @Test fun emptyFileIsEmpty() {
        assertEquals(LegacyJsonReader.LedgerReadResult.Empty, LegacyJsonReader.readLedger(""))
        assertEquals(LegacyJsonReader.LedgerReadResult.Empty, LegacyJsonReader.readLedger("   "))
    }

    @Test fun truncatedJsonIsCorrupt() {
        assertTrue(LegacyJsonReader.readLedger("""{"schemaVersion":1,"records":{""") is
            LegacyJsonReader.LedgerReadResult.Corrupt)
    }

    @Test fun nonObjectRootIsCorrupt() {
        assertTrue(LegacyJsonReader.readLedger("[]") is LegacyJsonReader.LedgerReadResult.Corrupt)
    }

    @Test fun unknownSchemaVersionIsCorrupt() {
        assertTrue(LegacyJsonReader.readLedger("""{"schemaVersion":99,"records":{}}""") is
            LegacyJsonReader.LedgerReadResult.Corrupt)
    }

    @Test fun missingSchemaVersionIsCorrupt() {
        assertTrue(LegacyJsonReader.readLedger("""{"records":{}}""") is
            LegacyJsonReader.LedgerReadResult.Corrupt)
    }

    @Test fun recordsNotAnObjectIsCorrupt() {
        assertTrue(LegacyJsonReader.readLedger("""{"schemaVersion":1,"records":[]}""") is
            LegacyJsonReader.LedgerReadResult.Corrupt)
    }

    @Test fun validEmptyRecordsIsLoadedEmpty() {
        val result = LegacyJsonReader.readLedger("""{"schemaVersion":1,"records":{}}""")
        assertTrue(result is LegacyJsonReader.LedgerReadResult.Loaded)
        assertTrue((result as LegacyJsonReader.LedgerReadResult.Loaded).records.isEmpty())
    }

    @Test fun chineseFieldsSurviveLegacyRead() {
        val result = LegacyJsonReader.readLedger(
            legacyLedger(
                """{"modUri":"content://mods/%E4%B8%AD","modName":"中文名字","familyKey":"角色",""" +
                    """"snapshotTargetHash":"h","quality":"HD"}"""
            )
        )
        val record = (result as LegacyJsonReader.LedgerReadResult.Loaded).records.values.single()
        assertEquals("中文名字", record.modName)
        assertEquals("角色", record.familyKey)
    }
}
