package com.bd2toolsbox.core.storage

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** 暂存树中一个文件的指纹：字节数 + 内容 SHA-256。 */
internal data class FileFact(val size: Long, val sha256: String)

/** 一次冻结的暂存树快照：全部普通文件指纹 + 全部（含空）子目录集合。 */
internal data class TreeSnapshot(val files: Map<String, FileFact>, val dirs: Set<String>)

/**
 * 绑定到某个**实际**暂存目录的完整内容清单（文件 + 目录集合，覆盖空目录增删）。
 *
 * 枚举**不跟随链接**：symbolic link / junction / 特殊文件（Windows junction 可能 isOther
 * 而非 isSymbolicLink）一律拒绝，避免复制或核对越出 owned 边界。默认 walkFileTree NOFOLLOW
 * 假设已被证伪，故对每个目录显式 NOFOLLOW 复核并核对真实路径仍在 root 内。root 自身属性同样核对。
 */
internal object BoundStagingTree {
    private val digester = StreamingDigest.sha256()

    /** 遇链接/非普通文件/root 非真实目录时抛 [IOException]。 */
    fun snapshot(root: Path): TreeSnapshot = snapshot(root) { true }

    /**
     * Same walk and link rejection as [snapshot], but only files for which [digestFile]
     * returns true are hashed; the rest record size only (sha256 = ""). Structure (every
     * file, size and directory) is always checked in full.
     */
    fun snapshot(root: Path, digestFile: (String) -> Boolean): TreeSnapshot {
        val ra = Files.readAttributes(root, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!ra.isDirectory || ra.isSymbolicLink || ra.isOther) throw IOException("暂存根非真实目录：$root")
        val realRoot = root.toRealPath()
        val files = LinkedHashMap<String, FileFact>()
        val dirs = LinkedHashSet<String>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, a: BasicFileAttributes): FileVisitResult {
                if (dir != root) {
                    val la = Files.readAttributes(dir, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    if (la.isSymbolicLink || la.isOther) throw IOException("暂存树含链接/特殊目录：$dir")
                    if (!dir.toRealPath().startsWith(realRoot)) throw IOException("暂存树逃出受控根：$dir")
                    dirs.add(rel(root, dir))
                }
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, a: BasicFileAttributes): FileVisitResult {
                if (a.isSymbolicLink || a.isOther) throw IOException("暂存树含链接/特殊文件：$file")
                if (!a.isRegularFile) throw IOException("暂存树含非普通文件：$file")
                val relative = rel(root, file)
                files[relative] = FileFact(a.size(), if (digestFile(relative)) digester.digest(file.toFile()) else "")
                return FileVisitResult.CONTINUE
            }
        })
        return TreeSnapshot(files, dirs)
    }

    // Relative to the walk root, not realRoot: visited paths descend from `root` as given,
    // and a non-canonical root (e.g. 8.3 short names) would otherwise yield "../.." keys.
    private fun rel(root: Path, p: Path) = root.relativize(p).toString().replace('\\', '/')
}
