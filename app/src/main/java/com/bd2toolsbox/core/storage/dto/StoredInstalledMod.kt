package com.bd2toolsbox.core.storage.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 装入账本一条记录的持久化 DTO（T35）。
 *
 * 字段名与 Gson 时代的 `LedgerRecord` **逐字相同**（`@SerialName` 显式钉住），
 * 所以旧账本可以原样读回；缺可选字段用默认值，缺必需字段则显式失败，不静默补。
 *
 * 与领域模型 `LedgerRecord` 的转换由仓储的显式 adapter 完成 —— 这是迁移期的 adapter，
 * 不是第二份事实模型。
 */
@Serializable
data class StoredInstalledMod(
    @SerialName("modUri") val modUri: String,
    @SerialName("modName") val modName: String,
    @SerialName("familyKey") val familyKey: String,
    @SerialName("snapshotTargetHash") val snapshotTargetHash: String,
    @SerialName("quality") val quality: String,
    @SerialName("usedAstc") val usedAstc: Boolean = false,
    @SerialName("installedAt") val installedAt: Long = 0L
)
