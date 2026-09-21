package com.bd2toolsbox.core.fileaccess

import java.io.File
import java.io.InputStream

/**
 * 本地目录树的 [DirectoryCopySource]（T08 converted 真实路径）。
 * `listFiles() == null` 视为读取失败（返回 null），空数组才是合法空目录。
 */
class FileDirectoryCopySource(private val root: File) : DirectoryCopySource {
    val rootId: String get() = root.absolutePath

    override fun listChildren(directoryId: String): List<DirectoryCopyEntry>? {
        val dir = File(directoryId)
        if (!dir.isDirectory) return null
        val children = dir.listFiles() ?: return null
        return children.map { child ->
            DirectoryCopyEntry(child.absolutePath, child.name, child.isDirectory)
        }
    }

    override fun openFile(fileId: String): InputStream? {
        val file = File(fileId)
        if (!file.isFile) return null
        return file.inputStream()
    }
}
