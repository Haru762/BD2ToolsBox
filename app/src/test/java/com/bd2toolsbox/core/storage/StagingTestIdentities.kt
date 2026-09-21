package com.bd2toolsbox.core.storage

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

/**
 * Host-only identity adapter: JDK 17 Windows fileKey is actually null on this host.
 * Use the real Windows File ID, not timestamps or matching payload, to exercise ownership.
 * Production keeps the default NIO provider and fails closed if it cannot provide identity.
 * This adapter is NOT evidence that Android external-cache fileKey is available.
 */
internal fun stagingForJvm(root: File): ConvertedInstallStaging =
    ConvertedInstallStaging(root, JvmTestStagingIdentity)

internal object JvmTestStagingIdentity : StagingDirectoryIdentityReader {
    override fun read(path: Path): Any {
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!attrs.isDirectory || attrs.isSymbolicLink || attrs.isOther) throw IOException("Not a real directory: $path")
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
            return attrs.fileKey() ?: throw IOException("Host has no strong directory identity: $path")
        }
        val systemRoot = System.getenv("SystemRoot") ?: throw IOException("SystemRoot is missing")
        // Read-only native query. No shell, no string-built command, no production fallback.
        val process = ProcessBuilder(File(systemRoot, "System32/fsutil.exe").absolutePath,
            "file", "queryfileid", path.toAbsolutePath().toString()).redirectErrorStream(true).start()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IOException("Windows File ID query timed out: $path")
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (process.exitValue() != 0) throw IOException("Windows File ID query failed: $output")
        val id = Regex("0x[0-9a-fA-F]+").findAll(output).toList().singleOrNull()?.value
            ?: throw IOException("Windows File ID query returned no unique identity: $output")
        return path.toAbsolutePath().root.toString().lowercase() + ":" + id.lowercase()
    }
}
