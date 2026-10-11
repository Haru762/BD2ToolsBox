package com.bd2toolsbox.core.storage

import com.bd2toolsbox.core.model.OperationError
import com.bd2toolsbox.core.model.OperationErrorKind
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CancellationException

sealed class ConvertedInstallPreparation {
    data class Ready(val prepared: PreparedConvertedInstall) : ConvertedInstallPreparation()
    data class Failed(val error: OperationError) : ConvertedInstallPreparation()
}

/**
 * 一次准备好的 converted 安装：绑定到独占暂存副本，事实全部来自**实际**暂存文件。
 * [close] 只删本对象拥有的目录，幂等；close 后或身份不符时 [verifyUnchanged] 恒失败。
 */
class PreparedConvertedInstall internal constructor(
    val sourceDir: File,
    val target: JournalTarget,
    private val lease: StagingDirectoryLease,
    private val manifest: TreeSnapshot,
) : Closeable {
    @Volatile private var closed = false
    val dataFile: File get() = File(sourceDir, "${target.hashDir}/__data")

    /** 核对前先确认仍是本次 acquired 目录，再重新枚举与冻结清单比对（文件 + 目录集合）。 */
    fun verifyUnchanged(): OperationError? {
        if (closed) return OperationError(OperationErrorKind.INVALID_INPUT, "暂存已关闭，无法核对内容")
        if (!lease.stillOwned()) {
            return OperationError(OperationErrorKind.MALFORMED_DATA, "暂存目录身份已改变（路径改指/被替换/身份不可确认），拒绝提交")
        }
        val now = try {
            BoundStagingTree.snapshot(lease.dir)
        } catch (e: Exception) {
            return OperationError(OperationErrorKind.IO, "暂存内容核对失败：" + e.message.orEmpty(), e.toString())
        }
        if (now != manifest) {
            return OperationError(OperationErrorKind.MALFORMED_DATA, "暂存内容与准备时不一致，拒绝提交")
        }
        return null
    }

    /** 幂等；身份不符时 release 内部拒绝删除，清理失败向上抛。 */
    override fun close() {
        if (closed) return
        closed = true
        lease.release()
    }
}

/**
 * 纯 JVM 的 converted 暂存所有权与内容绑定（T08）。
 *
 * public 单参数构造用默认 NIO [StagingDirectoryLease.NIO_FILE_KEY] 身份 reader（生产 MainVM 用）；
 * internal 双参数构造允许测试注入真实平台 File ID reader。**不提供任何 nonce/mtime/字节 fallback**。
 * [prepare] 先冻结账本、copy 前校验与 root 安全检查，再建独占子目录、复制、在 copy 后/snapshot 前
 * 及返回 Ready 前核对所有权，任何失败只清理本次 partial 且保留清理错误，取消清理后原样重抛。
 */
class ConvertedInstallStaging internal constructor(
    private val stagingRoot: File,
    private val identityReader: StagingDirectoryIdentityReader,
) {
    /** 生产入口：默认 NIO fileKey 身份 reader。 */
    constructor(stagingRoot: File) : this(stagingRoot, StagingDirectoryLease.NIO_FILE_KEY)

    fun prepare(
        bundleName: String,
        hashDir: String?,
        ledgerUpserts: List<LedgerRecord>,
        copySource: (File) -> Boolean,
    ): ConvertedInstallPreparation {
        // 1) 冻结账本列表，copy 前做完整目标/账本校验。
        val frozen = ledgerUpserts.toList()
        val modUris = frozen.map { it.modUri }
        validateInput(bundleName, hashDir, frozen, modUris)?.let { return ConvertedInstallPreparation.Failed(it) }
        val safeHash = hashDir!!

        // 2) root 必须是真实目录或可创建；拒绝文件/链接/junction（copy 前）。
        val rootAttrs = try {
            Files.readAttributes(stagingRoot.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (e: NoSuchFileException) {
            null
        } catch (e: Exception) {
            return failed("暂存根状态读取失败：" + e.message.orEmpty(), e)
        }
        when {
            rootAttrs == null -> if (!stagingRoot.mkdirs()) return failed("无法创建暂存根：${stagingRoot.path}")
            rootAttrs.isSymbolicLink || rootAttrs.isOther -> return failed("暂存根是链接/特殊文件，拒绝：${stagingRoot.path}")
            !rootAttrs.isDirectory -> return failed("暂存根不是目录：${stagingRoot.path}")
        }

        // 3) 独占子目录；root 身份不可用则不建子目录、不 copy（fail-closed）。
        val lease = try {
            StagingDirectoryLease.acquire(stagingRoot, identityReader)
        } catch (e: Exception) {
            return failed("无法创建独占暂存目录：" + e.message.orEmpty(), e)
        }
        val sourceDir = lease.dir.toFile()

        // 4) 复制回调：取消清理后原样重抛；false/异常一律清理并转 Failed，保留清理错误。
        val copied = try {
            copySource(sourceDir)
        } catch (c: CancellationException) {
            releasePreservingSuppressed(lease, c); throw c
        } catch (e: Exception) {
            return failed("暂存复制失败：" + e.message.orEmpty(), e, lease)
        }
        if (!copied) {
            return failed("暂存复制回调返回 false", null, lease)
        }

        // 5) copy 后立即核对所有权：回调若替换/改指整个目录，绝不返回 Ready。
        if (!lease.stillOwned()) {
            return failed("复制回调后暂存目录身份已改变（被替换/改指），拒绝", null, lease)
        }

        // 6) 枚举实际暂存树（拒绝链接/特殊文件），确认 __data 是普通文件。
        val manifest = try {
            BoundStagingTree.snapshot(lease.dir)
        } catch (e: Exception) {
            return failed("暂存树枚举失败：" + e.message.orEmpty(), e, lease)
        }
        val dataFact = manifest.files["$safeHash/__data"]
            ?: return failed("暂存缺少普通文件 $safeHash/__data", null, lease)

        // 7) 返回 Ready 前再核对一次所有权。
        if (!lease.stillOwned()) {
            return failed("枚举后暂存目录身份已改变，拒绝返回 Ready", null, lease)
        }

        // 8) 事实来自实际 dataFile；账本字段原样冻结。
        val target = JournalTarget(
            bundleName = bundleName,
            hashDir = safeHash,
            dataBytes = dataFact.size,
            modUris = modUris,
            recoveryFacts = JournalRecoveryFacts(1, dataFact.sha256, frozen),
        )
        return ConvertedInstallPreparation.Ready(PreparedConvertedInstall(sourceDir, target, lease, manifest))
    }

    private fun validateInput(
        bundleName: String, hashDir: String?, frozen: List<LedgerRecord>, modUris: List<String>,
    ): OperationError? {
        if (hashDir == null) return OperationError(OperationErrorKind.INVALID_INPUT, "hashDir 不能为空")
        val target = buildJsonObject {
            put("bundleName", bundleName)
            put("hashDir", hashDir)
            put("dataBytes", 0L)
            put("modUris", buildJsonArray { modUris.forEach { add(it) } })
            put("recoveryFacts", buildJsonObject {
                put("formatVersion", 1)
                put("expectedContentSha256", "0".repeat(64))
                put("ledgerUpserts", buildJsonArray {
                    frozen.forEach { r ->
                        add(buildJsonObject {
                            put("modUri", r.modUri)
                            put("modName", r.modName)
                            put("familyKey", r.familyKey)
                            put("snapshotTargetHash", r.snapshotTargetHash)
                            put("quality", r.quality)
                            put("usedAstc", r.usedAstc)
                            put("installedAt", r.installedAt)
                        })
                    }
                })
            })
        }
        val msg = JournalRecoveryFactsCodec.validate(target) ?: return null
        return OperationError(OperationErrorKind.INVALID_INPUT, msg)
    }

    /** Failed 构造：可选清理 [lease]，清理失败并入 detail，不覆盖主要错误。 */
    private fun failed(message: String, cause: Throwable? = null, lease: StagingDirectoryLease? = null):
        ConvertedInstallPreparation.Failed {
        var detail = cause?.toString()
        if (lease != null) {
            try {
                lease.release()
            } catch (ce: Exception) {
                detail = (detail?.plus("; ") ?: "") + "清理失败：" + ce.toString()
            }
        }
        return ConvertedInstallPreparation.Failed(OperationError(OperationErrorKind.IO, message, detail))
    }

    /** 取消路径：清理失败作为 suppressed 挂到 CancellationException，绝不掩盖取消。 */
    private fun releasePreservingSuppressed(lease: StagingDirectoryLease, primary: Throwable) {
        try {
            lease.release()
        } catch (ce: Exception) {
            primary.addSuppressed(ce)
        }
    }
}
