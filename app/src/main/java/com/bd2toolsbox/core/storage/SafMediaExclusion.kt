package com.bd2toolsbox.core.storage

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile

/**
 * SAF（`content://` 树 URI）目录上的 `.nomedia` 媒体扫描隔离（T62）。
 *
 * 为什么需要单独一份：[MediaExclusionManager.ensureMarker] 收的是 `java.io.File`，
 * 只能覆盖「应用可写的普通目录」。而 MOD 源目录是通过 `ACTION_OPEN_DOCUMENT_TREE`
 * 选的，拿到的是树 URI —— 那条路径以前**根本没有实现**，于是用户选的 MOD 目录里
 * 解出来的贴图会进系统相册。
 *
 * 与 `File` 版保持同一组契约：不覆盖已有同名文件、不删除任何东西、混合用途根默认拒绝、
 * 并且**核对 provider 返回的实际文件名**（provider 可能把 `.nomedia` 改写成
 * `.nomedia.txt`/`.nomedia.bin`，那不算「已保护」）。
 */
object SafMediaExclusion {

    private const val TAG = "SafMediaExclusion"

    /** `application/octet-stream`：`.nomedia` 是空文件，MIME 无关紧要但必须给一个。 */
    private const val MARKER_MIME = "application/octet-stream"

    /**
     * 在所选树 URI 下确保 `.nomedia` 标记存在。
     *
     * @param refuseMixedUseRoot 是否拒绝混合用途根；默认 true。只有调用方已经拿到
     *   用户**明确确认**时才传 false，且仍不覆盖/删除已有文件。
     */
    fun ensureMarker(
        context: Context,
        treeUri: Uri,
        refuseMixedUseRoot: Boolean = true
    ): MediaExclusionResult {
        val dir = try {
            DocumentFile.fromTreeUri(context, treeUri)
        } catch (e: Exception) {
            Log.w(TAG, "打不开所选目录: $treeUri", e)
            null
        } ?: return MediaExclusionResult.Failed("无法打开所选目录（授权可能已失效）")

        if (!dir.isDirectory) return MediaExclusionResult.Failed("所选路径不是目录")
        if (!dir.canWrite()) {
            // 只读授权：不能假装已保护。调用方据此告诉用户「这个目录加不了标记」。
            return MediaExclusionResult.Failed("这个目录只有读取权限，无法写入 .nomedia 标记")
        }
        if (refuseMixedUseRoot && MediaExclusionManager.isMixedUseRoot(dir.name)) {
            return MediaExclusionResult.Rejected(
                "「${dir.name}」是混合用途根目录，默认不加 .nomedia；" +
                    "请选择它下面明确的 MOD 子目录"
            )
        }

        dir.findFile(MediaExclusionManager.MARKER_NAME)?.let { existing ->
            // 已有同名文件保持不变：不覆盖、不删除，也不把它当成本次新建。
            return if (existing.isFile) MediaExclusionResult.AlreadyPresent
            else MediaExclusionResult.Failed(
                "同名路径已存在但不是普通文件：${existing.uri}"
            )
        }

        val created = try {
            dir.createFile(MARKER_MIME, MediaExclusionManager.MARKER_NAME)
        } catch (e: Exception) {
            Log.w(TAG, "创建 .nomedia 失败: $treeUri", e)
            null
        } ?: return MediaExclusionResult.Failed("无法创建标记（provider 拒绝写入）")

        // provider 可能改名：不算「已保护」，如实报失败（与 File 版同一判据）。
        return if (MediaExclusionManager.verifyProviderName(created.name)) {
            MediaExclusionResult.Created
        } else {
            MediaExclusionResult.Failed(
                "provider 改写了标记文件名（实际「${created.name}」），未形成有效保护"
            )
        }
    }

    /** 结果的用户可读文案（成功/已存在不出声，其余都要说）。 */
    fun message(result: MediaExclusionResult): String? = when (result) {
        MediaExclusionResult.Created, MediaExclusionResult.AlreadyPresent -> null
        is MediaExclusionResult.Rejected -> result.reason
        is MediaExclusionResult.Failed -> "媒体隔离标记未建立：${result.reason}"
    }
}
