package com.bd2toolsbox.core.storage

import com.bd2toolsbox.core.model.OperationError
import com.bd2toolsbox.core.model.OperationErrorKind
import com.google.gson.Gson
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.Collections

/** One move owns only its selected bundle/hash/__data copies, never Download/Shared. */
class MoveInstallStaging internal constructor(
    private val root: File,
    private val identity: StagingDirectoryIdentityReader
) {
    constructor(root: File) : this(root, StagingDirectoryLease.NIO_FILE_KEY)

    @Throws(IOException::class)
    fun prepare(shared: File, targets: List<JournalTarget>): PreparedMoveInstall {
        val frozen = Collections.unmodifiableList(targets.map { target ->
            target.copy(
                modUris = Collections.unmodifiableList(target.modUris.toList()),
                recoveryFacts = target.recoveryFacts?.let {
                    it.copy(ledgerUpserts = Collections.unmodifiableList(it.ledgerUpserts.toList()))
                }
            )
        })
        if (frozen.isEmpty()) throw IOException("没有可提交的目标")
        val paths = frozen.map { target ->
            if (!safeSegment(target.bundleName) || !safeSegment(target.hashDir) || target.dataBytes <= 0) {
                throw IOException("提交目标路径或长度无效")
            }
            if (target.recoveryFacts != null) {
                JournalRecoveryFactsCodec.validate(Gson().toJsonTree(target).asJsonObject)?.let {
                    throw IOException("提交事实无效：$it")
                }
            } else if (target.modUris.isNotEmpty()) {
                throw IOException("安装目标缺少内容摘要或账本事实")
            }
            "${target.bundleName}/${target.hashDir}/__data"
        }
        if (frozen.map { it.bundleName }.toSet().size != frozen.size) {
            throw IOException("提交目标重复")
        }
        val sharedRoot = shared.toPath().toAbsolutePath().normalize()
        requireDirectory(sharedRoot)
        val sharedReal = sharedRoot.toRealPath()
        Files.createDirectories(root.toPath())
        requireDirectory(root.toPath())
        val stageReal = root.toPath().toRealPath()
        if (stageReal.startsWith(sharedReal) || sharedReal.startsWith(stageReal)) {
            throw IOException("暂存根与源目录不能重叠")
        }
        val sourceIdentity = identity.read(sharedReal) ?: throw IOException("源目录身份不可确认")
        val lease = StagingDirectoryLease.acquire(root, identity)
        try {
            val expected = LinkedHashMap<String, FileFact>()
            for ((index, target) in frozen.withIndex()) {
                val relative = paths[index]
                val source = checkedSource(sharedReal, relative)
                val fact = fileFact(source)
                if (fact.size != target.dataBytes ||
                    (target.recoveryFacts != null && fact.sha256 != target.recoveryFacts.expectedContentSha256)) {
                    throw IOException("源文件已改变，拒绝提交：$relative")
                }
                val destination = lease.dir.resolve(relative)
                Files.createDirectories(destination.parent)
                Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS).use { input ->
                    Files.newOutputStream(destination, java.nio.file.StandardOpenOption.CREATE_NEW).use { output ->
                        input.copyTo(output)
                    }
                }
                expected[relative] = fact
            }
            val manifest = BoundStagingTree.snapshot(lease.dir)
            if (!lease.stillOwned() || manifest.files != expected) {
                throw IOException("暂存内容或目录身份与冻结目标不一致")
            }
            return PreparedMoveInstall(lease, frozen, manifest, sharedRoot, sharedReal, sourceIdentity, identity)
        } catch (failure: Throwable) {
            try { lease.release() } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    private fun safeSegment(value: String?): Boolean = !value.isNullOrBlank() &&
        value != "." && value != ".." && value.none { it == '/' || it == '\\' || it == ':' || it.isISOControl() }
}

class PreparedMoveInstall internal constructor(
    private val lease: StagingDirectoryLease,
    val targets: List<JournalTarget>,
    private val manifest: TreeSnapshot,
    private val sharedPath: Path,
    private val sharedReal: Path,
    private val sourceIdentity: Any,
    private val identity: StagingDirectoryIdentityReader
) : Closeable {
    val sourceDir: File get() = lease.dir.toFile()
    @Volatile private var closed = false

    fun verifyUnchanged(): OperationError? {
        if (closed) return OperationError(OperationErrorKind.INVALID_INPUT, "暂存已关闭")
        return try {
            if (!lease.stillOwned() || BoundStagingTree.snapshot(lease.dir) != manifest) {
                OperationError(OperationErrorKind.MALFORMED_DATA, "暂存内容或目录身份已改变，拒绝提交")
            } else null
        } catch (failure: Exception) {
            OperationError(OperationErrorKind.IO, "暂存核对失败：${failure.message.orEmpty()}")
        }
    }

    /**
     * Per-target check for batch commits: full structure (lease, every file/size/dir, no
     * injected entries) but only [bundleName]'s bytes are hashed. Call before and after that
     * target's copy; total hashing stays linear in batch size instead of O(n²).
     */
    fun verifyTarget(bundleName: String): OperationError? {
        if (closed) return OperationError(OperationErrorKind.INVALID_INPUT, "暂存已关闭")
        if (targets.none { it.bundleName == bundleName }) {
            return OperationError(OperationErrorKind.INVALID_INPUT, "不是本次冻结的目标：$bundleName")
        }
        val prefix = "$bundleName/"
        // Fail closed: if no frozen file matches the prefix, nothing would be hashed and the
        // check would pass vacuously.
        if (manifest.files.keys.none { it.startsWith(prefix) }) {
            return OperationError(OperationErrorKind.MALFORMED_DATA, "暂存清单中找不到目标文件：$bundleName")
        }
        return try {
            val current = BoundStagingTree.snapshot(lease.dir) { it.startsWith(prefix) }
            val expected = manifest.copy(files = manifest.files.mapValues { (path, fact) ->
                if (path.startsWith(prefix)) fact else fact.copy(sha256 = "")
            })
            if (!lease.stillOwned() || current != expected) {
                OperationError(OperationErrorKind.MALFORMED_DATA, "暂存内容或目录身份已改变，拒绝提交：$bundleName")
            } else null
        } catch (failure: Exception) {
            OperationError(OperationErrorKind.IO, "暂存核对失败：${failure.message.orEmpty()}")
        }
    }

    /** Call only after both file submission and bookkeeping succeed. Changed sources are retained. */
    fun cleanupSources(): List<String> {
        if (closed) return listOf("暂存已关闭，保留源文件")
        val warnings = mutableListOf<String>()
        for ((relative, expected) in manifest.files) {
            try {
                requireDirectory(sharedPath)
                if (sharedPath.toRealPath() != sharedReal || identity.read(sharedReal) != sourceIdentity) {
                    throw IOException("源目录身份已改变")
                }
                val source = checkedSource(sharedReal, relative)
                if (fileFact(source) != expected) throw IOException("源文件已改变")
                // Never recurse or delete parent directories: they may contain another batch's output.
                Files.delete(source)
            } catch (failure: Exception) {
                warnings.add("保留 $relative：${failure.message.orEmpty()}")
            }
        }
        return warnings
    }

    override fun close() {
        if (closed) return
        closed = true
        lease.release()
    }
}

private fun requireDirectory(path: Path) {
    val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    if (!attrs.isDirectory || attrs.isSymbolicLink || attrs.isOther) throw IOException("不是普通目录：$path")
}

/** Reject links in every selected path component, not merely the leaf. */
private fun checkedSource(root: Path, relative: String): Path {
    var current = root
    for (segment in relative.split('/').dropLast(1)) {
        current = current.resolve(segment)
        requireDirectory(current)
    }
    val source = current.resolve("__data")
    val attrs = Files.readAttributes(source, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    if (!attrs.isRegularFile || attrs.isSymbolicLink || attrs.isOther || !source.toRealPath().startsWith(root)) {
        throw IOException("源文件不是受控普通文件：$relative")
    }
    return source
}

private fun fileFact(path: Path): FileFact = FileFact(Files.size(path), StreamingDigest.sha256().digest(path.toFile()))
