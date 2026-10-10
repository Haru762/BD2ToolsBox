package com.bd2toolsbox.feature.update

import com.bd2toolsbox.data.repository.UpdateRepository

/**
 * 按设备 ABI 从发布资产里选 APK（T32）。
 *
 * 规则：
 * - 按 [supportedAbis] 的**顺序**取第一个能匹配上的资产（`Build.SUPPORTED_ABIS` 已是
 *   从首选到备选）；
 * - ABI 作为**独立 token** 匹配，`x86` 不会命中 `x86_64`；
 * - **没有匹配资产时返回 `null`（明确失败）**，绝不回退到 `universal`，也不退回错误架构。
 *
 * 为 T49 的 arm64 / x86_64 两个独立包做兼容。
 */
object ApkSelector {

    /** 设备 ABI → 资产名里可能出现的 token（按优先级）。 */
    fun tokensFor(abi: String): List<String> = when (abi.lowercase()) {
        "arm64-v8a", "arm64" -> listOf("arm64-v8a", "arm64")
        "armeabi-v7a", "armeabi" -> listOf("armeabi-v7a", "armeabi")
        "x86_64" -> listOf("x86_64")
        "x86" -> listOf("x86")
        else -> listOf(abi.lowercase()).filter { it.isNotBlank() }
    }

    /** token 必须作为独立片段出现：前后不能紧邻 `[a-z0-9_]`。 */
    private fun matches(name: String, token: String): Boolean {
        if (token.isBlank()) return false
        val re = Regex(
            "(?<![a-z0-9_])" + Regex.escape(token) + "(?![a-z0-9_])",
            RegexOption.IGNORE_CASE
        )
        return re.containsMatchIn(name)
    }

    /**
     * 选包。返回 `null` 表示**没有可用资产**（调用方必须明确失败）。
     */
    fun select(assets: List<UpdateRepository.ApkAsset>, supportedAbis: List<String>): UpdateRepository.ApkAsset? {
        for (abi in supportedAbis) {
            for (token in tokensFor(abi)) {
                assets.firstOrNull { matches(it.name, token) }?.let { return it }
            }
        }
        return null
    }
}
