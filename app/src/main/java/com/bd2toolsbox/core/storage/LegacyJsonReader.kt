package com.bd2toolsbox.core.storage

import com.bd2toolsbox.core.storage.dto.StoredInstalledMod
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 旧格式（Gson 时代）持久化 JSON 的兼容读取（T35）。
 *
 * 只负责**读**：把旧字段名/缺字段/null/未知字段按明确规则解释成 DTO，读不出来时给出
 * 明确失败而不是空表。写入由各 Store 的新格式负责；本类不改动任何文件。
 *
 * 规则：
 * - 顶层必须是对象，且带数字型 `schemaVersion`；版本不认识 → [LedgerReadResult.Corrupt]；
 * - `records` 必须是对象；每个条目缺**必需**字段或类型不符 → 整次读取 Corrupt
 *   （不静默补默认值）；
 * - 未知字段忽略；可选字段缺失用 DTO 默认值；
 * - 空文本 → [LedgerReadResult.Empty]。
 */
object LegacyJsonReader {

    /** 与 Gson 语义差异处显式固定：忽略未知字段、显式 null 按缺失处理。 */
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    sealed class LedgerReadResult {
        /** 文件不存在或内容为空。 */
        object Empty : LedgerReadResult()
        data class Loaded(val records: Map<String, StoredInstalledMod>) : LedgerReadResult()
        data class Corrupt(val reason: String) : LedgerReadResult()
    }

    fun readLedger(text: String, supportedSchema: Int = 1): LedgerReadResult {
        if (text.isBlank()) return LedgerReadResult.Empty
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            return LedgerReadResult.Corrupt("账本不是 JSON 对象：" + e.message.orEmpty())
        }
        val version = try {
            root["schemaVersion"]?.jsonPrimitive?.content?.toIntOrNull()
        } catch (e: Exception) {
            null
        } ?: return LedgerReadResult.Corrupt("账本缺少数字型 schemaVersion")
        if (version != supportedSchema) {
            return LedgerReadResult.Corrupt("不支持的账本 schemaVersion=$version")
        }
        val recordsElement = root["records"]
            ?: return LedgerReadResult.Corrupt("账本缺少 records")
        val recordsObject = try {
            recordsElement.jsonObject
        } catch (e: Exception) {
            return LedgerReadResult.Corrupt("records 不是对象：" + e.message.orEmpty())
        }
        val out = LinkedHashMap<String, StoredInstalledMod>()
        for ((key, value) in recordsObject) {
            val obj: JsonObject = try {
                value.jsonObject
            } catch (e: Exception) {
                return LedgerReadResult.Corrupt("记录 $key 不是对象")
            }
            val record = try {
                json.decodeFromJsonElement(StoredInstalledMod.serializer(), obj)
            } catch (e: Exception) {
                return LedgerReadResult.Corrupt("记录 $key 缺字段或类型不符：" + e.message.orEmpty())
            }
            out[key] = record
        }
        return LedgerReadResult.Loaded(out)
    }

    /** 单条记录（无外层包装）的兼容读取。 */
    fun readRecord(text: String): StoredInstalledMod =
        json.decodeFromString(StoredInstalledMod.serializer(), text)
}
