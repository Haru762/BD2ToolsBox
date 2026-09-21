package com.bd2toolsbox.core.fileaccess

import java.io.File
import java.io.InputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.ArrayDeque

data class DirectoryCopyEntry(val id: String, val name: String, val isDirectory: Boolean)

/** null 表示读取失败，emptyList 才表示合法空目录；流的关闭由复制器负责。 */
interface DirectoryCopySource {
    fun listChildren(directoryId: String): List<DirectoryCopyEntry>?
    fun openFile(fileId: String): InputStream?
}

/** 复制到调用方拥有的暂存目录；不覆盖现有文件，失败向上传播以便所有者清理。 */
object StrictDirectoryCopy {
    fun copy(source: DirectoryCopySource, rootId: String, destination: File) {
        require(rootId.isNotBlank()) { "源目录标识为空" }
        if (!destination.exists() && !destination.mkdirs()) throw IOException("无法建立暂存目录")
        if (!Files.isDirectory(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("暂存根不是普通目录")
        }
        val pending = ArrayDeque<Pair<String, String>>()
        val seenIds = hashSetOf(rootId)
        pending.add(rootId to "")
        while (pending.isNotEmpty()) {
            val (directoryId, relative) = pending.removeLast()
            val entries = source.listChildren(directoryId) ?: throw IOException("无法读取源目录：$directoryId")
            val names = HashSet<String>()
            // 先验证本层所有名字，不能跳过某个坏条目后仍报告整树成功。
            for (entry in entries) {
                require(entry.id.isNotBlank() && entry.name.isNotBlank() && entry.name != "." && entry.name != ".." &&
                    entry.name.none { it == '/' || it == '\\' || it == ':' || it.isISOControl() }) {
                    "源条目名称或标识无效"
                }
                require(names.add(entry.name)) { "源目录有重复名称：" + entry.name }
                require(seenIds.add(entry.id)) { "源目录含重复文档或循环引用" }
            }
            for (entry in entries) {
                val childRelative = if (relative.isEmpty()) entry.name else relative + "/" + entry.name
                val out = SafeArchivePaths.resolveInside(destination, childRelative)
                if (entry.isDirectory) {
                    Files.createDirectory(out.toPath()) // 已有目录也拒绝，避免混入旧文件。
                    pending.add(entry.id to childRelative)
                } else {
                    val input = source.openFile(entry.id) ?: throw IOException("无法打开源文件：" + entry.name)
                    input.use { ins ->
                        Files.newOutputStream(out.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                            ins.copyTo(output)
                        }
                    }
                }
            }
        }
    }
}
