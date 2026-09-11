package com.bd2toolsbox.data.repository

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.UUID

/** Uses the production discovery code with an in-memory DocumentsProvider; no user files. */
@RunWith(AndroidJUnit4::class)
class ModDiscoveryTest {
    private lateinit var sandbox: File
    private lateinit var provider: TreeProvider
    private lateinit var repository: ModRepository
    private val rootUri = DocumentsContract.buildTreeDocumentUri("test.mod.discovery", "root")

    @Before
    fun setup() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        sandbox = File(base.cacheDir, "scan-tests-${UUID.randomUUID()}").apply { mkdirs() }
        provider = TreeProvider().apply {
            attachInfo(base, android.content.pm.ProviderInfo().apply { authority = "test.mod.discovery" })
        }
        val resolver = ContentResolver.wrap(provider)
        val context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = sandbox
            override fun getContentResolver(): ContentResolver = resolver
        }
        repository = ModRepository(context, CharacterRepository(context))
    }

    @After
    fun cleanupOwnFixture() {
        // Created by this exact test invocation; never deletes outside this UUID directory.
        check(sandbox.name.startsWith("scan-tests-") && sandbox.parentFile?.name == "cache")
        sandbox.deleteRecursively()
    }

    private fun discover(): List<Any> {
        val method = ModRepository::class.java.getDeclaredMethod("discoverMods", Uri::class.java)
        method.isAccessible = true
        try {
            @Suppress("UNCHECKED_CAST")
            return method.invoke(repository, rootUri) as List<Any>
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    @Test
    fun addingPcFoldersMustNotHideExistingConvertedMods() {
        // 1,000 independent variants use 3,002 directories, beyond the old 3,000 cap.
        // With 700 PC folders there are exactly 1,700 mod entries and 3,703 directories.
        provider.dir("root", "PC")
        repeat(700) { i ->
            val pc = provider.dir("root/PC", "pc_$i")
            provider.file(pc, "char${i.toString().padStart(6, '0')}.atlas")
        }
        provider.dir("root", "variants")
        repeat(1000) { i ->
            val owner = provider.dir("root/variants", "variant_$i")
            val bundle = provider.dir(owner, "1234567890abcdef1234567890abcdef")
            val hash = provider.dir(bundle, "abcdef1234567890abcdef1234567890")
            provider.file(hash, "__data")
        }
        assertEquals(1700, discover().size)
        provider.reverse = true
        assertEquals("Provider ordering must not change completeness", 1700, discover().size)
    }

    @Test
    fun rescanningSeesNewFoldersEvenIfRootTimestampDoesNotChange() {
        val first = provider.dir("root", "one")
        provider.file(first, "char000001.atlas")
        assertEquals(1, discover().size)
        val added = provider.dir("root", "two")
        provider.file(added, "char000002.atlas")
        assertEquals(2, discover().size)
        assertEquals(2, discover().size) // re-adding the same tree must keep both
    }

    @Test
    fun differentVariantFoldersOfSameTargetAreNotDeduplicated() {
        repeat(2) { i ->
            val owner = provider.dir("root", "author_$i")
            val bundle = provider.dir(owner, "1234567890abcdef1234567890abcdef")
            val hash = provider.dir(bundle, "abcdef1234567890abcdef1234567890")
            provider.file(hash, "__data")
        }
        assertEquals(2, discover().size)
    }

    @Test
    fun loadingCursorMustNotBeAcceptedAsComplete() {
        provider.queryExtras.putBoolean(DocumentsContract.EXTRA_LOADING, true)
        assertThrows(ModRepository.IncompleteScanException::class.java) { discover() }
    }

    @Test
    fun errorCursorMustNotBeAcceptedAsEmpty() {
        provider.queryExtras.putString(DocumentsContract.EXTRA_ERROR, "Provider not ready")
        assertThrows(ModRepository.IncompleteScanException::class.java) { discover() }
    }

    @Test
    fun providerFailureMustNotMasqueradeAsAnEmptyFolder() {
        provider.failAt = "root"
        assertThrows(ModRepository.IncompleteScanException::class.java) { discover() }
    }

    @Test
    fun visitLimitMustReportIncompleteScanInsteadOfPartialSuccess() {
        repeat(15010) { provider.dir("root", "empty_$it") }
        assertThrows(ModRepository.IncompleteScanException::class.java) { discover() }
    }

    @Test
    fun depthLimitMustReportIncompleteScanInsteadOfPartialSuccess() {
        var parent = "root"
        repeat(10) { parent = provider.dir(parent, "nested_$it") }
        provider.file(parent, "char000001.atlas")
        assertThrows(ModRepository.IncompleteScanException::class.java) { discover() }
    }

    @Test
    fun staleIndexStampInvalidatesResolutionCache() {
        File(sandbox, "local_bundle_index.json").apply {
            writeText("{}")
            setLastModified(20000L)
        }
        File(sandbox, "mod_cache.json").writeText("""
          {"schemaVersion":2,"indexStamp":10000,"entries":{"old":{
          "uriString":"old","lastModified":1,"name":"old","character":"Unknown",
          "costume":"Unknown","type":"unknown","isDirectory":true,
          "resolutionState":"UNKNOWN","unresolvedFiles":[]}}}
        """.trimIndent())
        val method = ModRepository::class.java.getDeclaredMethod("loadModCache")
        method.isAccessible = true
        val cache = method.invoke(repository)
        val entries = cache.javaClass.getDeclaredMethod("getEntries").apply { isAccessible = true }
            .invoke(cache) as Map<*, *>
        assertTrue("Resolver index changed; cached UNKNOWN must be re-resolved", entries.isEmpty())
    }

    @Test
    fun addingAssetWithUnchangedParentTimestampReResolvesFolder() = runBlocking {
        val pc = provider.dir("root", "pc")
        provider.file(pc, "cutscene_char000707.atlas")
        writeLocalIndex()
        assertEquals(1, repository.scanMods(rootUri).single().resolvedTargets.size)
        // Provider reports exactly the same parent timestamp before and after.
        provider.file(pc, "cutscene_char000707.png")
        val after = repository.scanMods(rootUri).single()
        assertEquals(setOf("cutscene_char000707.atlas", "cutscene_char000707.png"),
            after.resolvedTargets.map { it.originalFileName }.toSet())
    }

    @Test
    fun cacheHitKeepsResolvedTargets() = runBlocking {
        val pc = provider.dir("root", "pc")
        provider.file(pc, "cutscene_char000707.atlas")
        writeLocalIndex()
        val first = repository.scanMods(rootUri).single()
        assertEquals(1, first.resolvedTargets.size)
        val second = repository.scanMods(rootUri).single()
        assertEquals(first.resolvedTargets, second.resolvedTargets)
    }

    private fun writeLocalIndex() {
        File(sandbox, "local_bundle_index.json").writeText("""
            {"schemaVersion":3,"scannedBundles":{},"catalogAssetToBundle":{},
             "assetToBundles":{
              "cutscene_char000707.atlas":["00044c1c0b4b673e127e271e219f70b2"],
              "cutscene_char000707.png":["00044c1c0b4b673e127e271e219f70b2"]}}
        """.trimIndent())
    }

    @Test
    fun realLibraryPreservesAllPreviouslyVisibleAndroidVariants() {
        val path = InstrumentationRegistry.getArguments().getString("libraryManifest")
        org.junit.Assume.assumeTrue("Optional read-only directory snapshot", path != null)
        val root = org.json.JSONObject(File(requireNotNull(path)).readText())
        val nodes = root.getJSONArray("nodes")
        for (i in 0 until nodes.length()) {
            val n = nodes.getJSONObject(i)
            provider.add(n.getString("parent"), n.getString("name"), n.getBoolean("directory"))
        }
        provider.hiddenRoot = setOf("PC")
        val androidOnly = discover()
        assertEquals("Existing Android-only collection", 1044, androidOnly.size)
        val expectedUris = androidOnly.map { item ->
            item.javaClass.getDeclaredMethod("getUri").apply { isAccessible = true }.invoke(item).toString()
        }.toSet()
        provider.hiddenRoot = emptySet()
        val all = discover()
        assertEquals("Complete captured mixed collection", 1741, all.size)
        val actualUris = all.map { item ->
            item.javaClass.getDeclaredMethod("getUri").apply { isAccessible = true }.invoke(item).toString()
        }.toSet()
        assertTrue("Adding PC folders must not remove any Android variant", actualUris.containsAll(expectedUris))
        provider.reverse = true
        assertEquals(1741, discover().size)
    }

    private class TreeProvider : ContentProvider() {
        data class Node(val id: String, val name: String, val directory: Boolean)
        private val nodes = linkedMapOf("root" to Node("root", "mods", true))
        private val children = linkedMapOf<String, MutableList<Node>>()
        var reverse = false
        var hiddenRoot: Set<String> = emptySet()
        var failAt: String? = null
        val queryExtras = android.os.Bundle()
        fun dir(parent: String, name: String) = add(parent, name, true)
        fun file(parent: String, name: String) = add(parent, name, false)
        fun add(parent: String, name: String, directory: Boolean): String {
            val node = Node("$parent/$name", name, directory)
            nodes[node.id] = node
            children.getOrPut(parent) { mutableListOf() }.add(node)
            return node.id
        }
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                           selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val id = DocumentsContract.getDocumentId(uri)
            if (id == failAt) throw IllegalStateException("Injected provider failure")
            val fields = requireNotNull(projection)
            val cursor = object : MatrixCursor(fields) {
                override fun getExtras() = queryExtras
            }
            val list = if (uri.lastPathSegment == "children") {
                children[id].orEmpty()
                    .filterNot { id == "root" && it.name in hiddenRoot }
                    .let { if (reverse) it.reversed() else it }
            } else listOfNotNull(nodes[id])
            for (node in list) {
                cursor.addRow(fields.map { field ->
                    when (field) {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> node.id
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME -> node.name
                        DocumentsContract.Document.COLUMN_MIME_TYPE ->
                            if (node.directory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED -> 1000L
                        DocumentsContract.Document.COLUMN_SIZE -> 64L
                        else -> null
                    }
                })
            }
            return cursor
        }
        override fun getType(uri: Uri) = "application/octet-stream"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read-only fixture")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = error("Read-only fixture")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = error("Read-only fixture")
    }
}
