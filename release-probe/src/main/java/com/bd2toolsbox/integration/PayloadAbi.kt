package com.bd2toolsbox.integration

/**
 * 载荷 ABI 目录名称规范化。
 *
 * 只接受本项目支持的两个载荷 ABI（arm64-v8a 与 x86_64）。
 * 将 Android 系统常见的 arm64 目录别名映射为规范 ABI 名称 "arm64-v8a"；
 * 空串、根目录、未知末段、含糊的 "arm" 均不猜测，返回 null。
 */
internal fun payloadAbiFromNativeLibraryDir(path: String?): String? =
    when (path?.trimEnd('/')?.substringAfterLast('/')) {
        "arm64", "arm64-v8a" -> "arm64-v8a"
        "x86_64" -> "x86_64"
        else -> null
    }
