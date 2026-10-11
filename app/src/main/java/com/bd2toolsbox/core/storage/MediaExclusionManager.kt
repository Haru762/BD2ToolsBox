package com.bd2toolsbox.core.storage

import java.io.File
import java.io.IOException

/** 一次「确保 .nomedia 标记」的结果。 */
sealed interface MediaExclusionResult {
    /** 本次新建了标记。 */
    object Created : MediaExclusionResult

    /** 标记本来就在（同名普通文件），保持不变。 */
    object AlreadyPresent : MediaExclusionResult

    /** 目录是混合用途根，**拒绝**默认加标记。 */
    data class Rejected(val reason: String) : MediaExclusionResult

    /** 创建/核对失败（不存在、只读、provider 不支持等）。 */
    data class Failed(val reason: String) : MediaExclusionResult
}

/**
 * 目录级媒体扫描隔离（T62）。
 *
 * 用 Android 的 `.nomedia` 目录约定：在**明确属于本应用的 MOD 专用目录**里放一个名为
 * `.nomedia` 的普通空文件，保护该目录及子目录不被系统媒体扫描收录。不改贴图内容、
 * 扩展名或 MOD 文件夹名，不新增依赖库。
 *
 * 本类只做**可独立测试**的纯逻辑与普通文件系统操作：
 * - 混合用途根（存储根、DCIM、Pictures、Movies、Download/Documents）**拒绝**默认加标记；
 * - provider 返回的实际名称必须**恰好**是 `.nomedia`（`.nomedia.txt`/`.nomedia.bin` 不算）；
 * - 已有同名文件保持不变（不覆盖、不删除）；
 * - 标记自身必须从 MOD 枚举/签名/重打包输入里排除。
 *
 * SAF（content URI）侧的创建/核对由 Android 适配层完成，不把 content URI 拼成文件路径；
 * 本类不接收也不解析 content URI。
 */
object MediaExclusionManager {

    /** 标记文件名。目录约定固定为这个字面量，不加扩展名。 */
    const val MARKER_NAME = ".nomedia"

    /**
     * 禁止默认加标记的混合用途根目录名（小写比较）。
     *
     * 这些目录里既有 MOD 贴图也有用户自己的照片/视频/文档；默认加标记会连带隐藏
     * 用户的真实相册内容。要保护就选它们下面明确的 MOD 子目录，不递归改无关相册。
     */
    private val MIXED_USE_ROOTS = setOf(
        "dcim", "pictures", "movies", "download", "downloads",
        "documents", "sdcard", "storage", "emulated", "0"
    )

    /** 名称是否**恰好**是标记（不接受 `.nomedia.txt` 等变体）。 */
    fun isMarkerName(name: String): Boolean = name == MARKER_NAME

    /** 目录名是否是混合用途根（应拒绝默认加标记）。 */
    fun isMixedUseRoot(dirName: String?): Boolean =
        dirName?.trim()?.lowercase() in MIXED_USE_ROOTS

    /** MOD 枚举/内容签名/重打包输入应跳过的条目：只有标记自身。 */
    fun shouldSkipInModScan(name: String): Boolean = isMarkerName(name)

    /**
     * 在普通目录（应用可写）里确保标记存在。
     *
     * @param refuseMixedUseRoot 是否拒绝混合用途根；默认 true。设为 false 只用于
     *   调用方已经拿到用户**明确确认**的场景，且仍不覆盖/删除已有文件。
     */
    fun ensureMarker(dir: File, refuseMixedUseRoot: Boolean = true): MediaExclusionResult {
        if (!dir.isDirectory) {
            return MediaExclusionResult.Failed("目录不存在或不是目录：${dir.path}")
        }
        if (refuseMixedUseRoot && isMixedUseRoot(dir.name)) {
            return MediaExclusionResult.Rejected(
                "「${dir.name}」是混合用途根目录，默认不加 .nomedia；请选择它下面明确的 MOD 子目录"
            )
        }
        val marker = File(dir, MARKER_NAME)
        if (marker.exists()) {
            // 已有同名文件保持不变：不覆盖、不删除，也不把它当成本次新建。
            return if (marker.isFile) MediaExclusionResult.AlreadyPresent
            else MediaExclusionResult.Failed("同名路径已存在但不是普通文件：${marker.path}")
        }
        return try {
            // 空普通文件；createNewFile 在并发/已存在时返回 false。
            if (marker.createNewFile()) MediaExclusionResult.Created
            else if (marker.isFile) MediaExclusionResult.AlreadyPresent
            else MediaExclusionResult.Failed("无法创建标记：${marker.path}")
        } catch (e: IOException) {
            MediaExclusionResult.Failed("创建标记失败：" + e.message.orEmpty())
        } catch (e: SecurityException) {
            MediaExclusionResult.Failed("没有写入权限：" + e.message.orEmpty())
        }
    }

    /**
     * 核对 provider 返回的实际名称是否确实是标记。
     *
     * provider 可能把文件名改成 `.nomedia.txt`/`.nomedia.bin`，或写入失败却返回一个
     * 别的名字；那些都不算「已保护」。
     */
    fun verifyProviderName(actualName: String?): Boolean = actualName == MARKER_NAME
}
