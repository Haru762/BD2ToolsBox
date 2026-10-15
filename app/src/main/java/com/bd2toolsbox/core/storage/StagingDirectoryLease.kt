package com.bd2toolsbox.core.storage

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission

/**
 * 读取一个路径的**真实目录身份**（inode 级实体标识）。
 *
 * 返回非空 = 该文件系统能提供强身份；返回 `null` = 无法确认，调用方必须 fail-closed。
 * **绝不**用 nonce / mtime / creationTime / 路径 / 相同字节做后备——那些都不是身份，
 * 且整目录复制会把 nonce 一起搬进目标，重开同源漏洞。
 */
internal fun interface StagingDirectoryIdentityReader {
    fun read(path: Path): Any?
}

/** 已创建暂存目录但身份不可确认：保留空暂存、不递归猜测清理。 */
internal class StagingIdentityUnavailable(message: String) : IOException(message)

/**
 * 一次准备**独占**的 staging 目录租约（T08）。
 *
 * acquire 时冻结真实路径 [acquiredReal] + **非空**目录身份 [acquiredIdentity]。校验/删除前
 * 都要 [stillOwned]：真实路径改指、目录被换成新实体（身份不同）、或身份读回 `null`（fail-closed）
 * 都判不再拥有，一律拒绝且**不删替换目录/外部内容**。默认 walkFileTree NOFOLLOW 假设已被实测
 * 证伪，遍历时对每个目录显式 NOFOLLOW 复核 link/isOther 并核对真实路径仍在受控根内。[release] 幂等。
 */
internal class StagingDirectoryLease private constructor(
    val dir: Path,
    private val acquiredReal: Path,
    private val acquiredIdentity: Any,
    private val reader: StagingDirectoryIdentityReader,
) {
    @Volatile private var released = false

    companion object {
        /** 默认身份 reader：NIO [BasicFileAttributes.fileKey]；不可用/异常返回 null（不作平台推断）。 */
        val NIO_FILE_KEY = StagingDirectoryIdentityReader { path ->
            try {
                Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey()
            } catch (e: Exception) {
                null
            }
        }

        /**
         * 在**规范化** root 下新建独占子目录。
         * - root 身份在 createTempDirectory **前**检查：为 null 则不建子目录、不 copy。
         * - 子目录创建后身份必须非空；读不回则保留空暂存并 [StagingIdentityUnavailable]，不递归清理。
         */
        fun acquire(root: File, reader: StagingDirectoryIdentityReader): StagingDirectoryLease {
            val realRoot = root.toPath().toRealPath()
            if (reader.read(realRoot) == null) {
                throw StagingIdentityUnavailable(
                    "暂存根目录身份不可确认（文件系统未提供 fileKey），拒绝创建暂存子目录：$realRoot"
                )
            }
            makeCrossUidTraversable(realRoot, isDir = true)
            val d = Files.createTempDirectory(realRoot, "cvt-stage-")
            makeCrossUidTraversable(d, isDir = true)
            val real = d.toRealPath()
            val identity = reader.read(real)
                ?: throw StagingIdentityUnavailable(
                    "已创建暂存目录但无法确认其身份，保留该空暂存不做递归清理：$real"
                )
            return StagingDirectoryLease(d, real, identity, reader)
        }

        private fun makeCrossUidTraversable(path: Path, isDir: Boolean) {
            try {
                val perms = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).toMutableSet()
                perms.add(PosixFilePermission.GROUP_READ)
                perms.add(PosixFilePermission.OTHERS_READ)
                if (isDir) {
                    perms.add(PosixFilePermission.GROUP_WRITE)
                    perms.add(PosixFilePermission.GROUP_EXECUTE)
                    perms.add(PosixFilePermission.OTHERS_EXECUTE)
                }
                Files.setPosixFilePermissions(path, perms)
            } catch (_: Exception) {
                // Windows/NTFS 或不支持 POSIX 权限修改的卷静默忽略
            }
            try {
                val f = path.toFile()
                f.setReadable(true, false)
                if (isDir) f.setExecutable(true, false)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 为暂存树开放跨 UID 读取与目录遍历权限（0755/0644）。
     *
     * `Files.createTempDirectory` 在 POSIX 上默认使用 `0700 (drwx------)`，
     * 会阻断以 `UID=2000 (shell)` 运行的非 Root Shizuku 文件服务进入 `externalCacheDir`
     * 下的 `cvt-stage-*` 暂存目录读取待装入的 `__data`。
     */
    fun grantCrossUidReadAccess() {
        try {
            makeCrossUidTraversable(acquiredReal, isDir = true)
            Files.walkFileTree(acquiredReal, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(d: Path, a: BasicFileAttributes): FileVisitResult {
                    makeCrossUidTraversable(d, isDir = true)
                    return FileVisitResult.CONTINUE
                }
                override fun visitFile(file: Path, a: BasicFileAttributes): FileVisitResult {
                    makeCrossUidTraversable(file, isDir = false)
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (_: Exception) {
        }
    }

    /** 当前 [dir] 是否仍是本次 acquired 的同一实体（真实路径 + 非空身份都匹配）。 */
    fun stillOwned(): Boolean {
        val real = try {
            dir.toRealPath()
        } catch (e: IOException) {
            return false
        }
        if (real != acquiredReal) return false // 父路径被改指/替换
        val attrs = try {
            Files.readAttributes(real, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (e: IOException) {
            return false
        }
        if (!attrs.isDirectory || attrs.isSymbolicLink || attrs.isOther) return false
        val current = try {
            reader.read(real)
        } catch (e: Exception) {
            return false
        }
        if (current == null) return false // null 永不相等：fail-closed，不继承替换目录
        return current == acquiredIdentity
    }

    /** 只删本租约拥有的目录树；身份不符则拒绝（不删替换目录/外部内容）。重复调用安全。 */
    @Throws(IOException::class)
    fun release() {
        if (released) return
        released = true
        if (!stillOwned()) return // 已不是我们的目录：绝不递归删除外部内容
        deleteControlled(acquiredReal)
    }

    private fun deleteControlled(root: Path) {
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(d: Path, a: BasicFileAttributes): FileVisitResult {
                // 显式核对：link/isOther 只删入口不下钻；真实路径逃出受控根也只删入口。
                val la = Files.readAttributes(d, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                if (la.isSymbolicLink || la.isOther || !d.toRealPath().startsWith(root)) {
                    Files.deleteIfExists(d)
                    return FileVisitResult.SKIP_SUBTREE
                }
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, a: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file); return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(d: Path, e: IOException?): FileVisitResult {
                if (e != null) throw e // 不忽略遍历错误
                Files.deleteIfExists(d); return FileVisitResult.CONTINUE
            }
        })
    }
}
