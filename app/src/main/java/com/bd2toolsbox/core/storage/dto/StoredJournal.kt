package com.bd2toolsbox.core.storage.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 操作日志（journal）的持久化 DTO（T35）。
 *
 * 字段名用 `@SerialName` 与 Gson 时代逐字相同，旧日志原样可读。
 * 必需字段（operationId/stage/targets、bundleName/modUris）**不给默认值**：
 * 缺失即反序列化失败，由调用方判「不可读」，而不是静默补空。
 */
@Serializable
data class StoredJournalEntry(
    @SerialName("operationId") val operationId: String,
    @SerialName("stage") val stage: String,
    @SerialName("atMillis") val atMillis: Long = 0L,
    @SerialName("targets") val targets: List<StoredJournalTarget>,
    @SerialName("note") val note: String? = null
)

@Serializable
data class StoredJournalTarget(
    @SerialName("bundleName") val bundleName: String,
    @SerialName("hashDir") val hashDir: String? = null,
    @SerialName("dataBytes") val dataBytes: Long = 0L,
    @SerialName("modUris") val modUris: List<String>,
    @SerialName("recoveryFacts") val recoveryFacts: StoredJournalRecoveryFacts? = null
)

@Serializable
data class StoredJournalRecoveryFacts(
    @SerialName("formatVersion") val formatVersion: Int,
    @SerialName("expectedContentSha256") val expectedContentSha256: String,
    @SerialName("ledgerUpserts") val ledgerUpserts: List<StoredInstalledMod> = emptyList()
)
