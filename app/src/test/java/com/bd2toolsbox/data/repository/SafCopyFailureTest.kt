package com.bd2toolsbox.data.repository

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.bd2toolsbox.core.fileaccess.DirectoryCopySource
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException

class SafCopyFailureTest {
    private lateinit var context: Context
    private lateinit var root: DocumentFile
    private lateinit var uri: Uri
    private lateinit var cursor: Cursor
    private lateinit var extras: Bundle

    @Before fun setup() {
        mockkStatic(DocumentsContract::class)
        context = mockk(relaxed = true)
        root = mockk()
        uri = mockk()
        every { uri.toString() } returns "content://test/tree/root"
        every { root.uri } returns uri
        every { root.isDirectory } returns true
        // Characterizes DocumentFile's lossy failure representation: its empty
        // array must not substitute for checking the actual Cursor outcome.
        every { root.listFiles() } returns emptyArray()
        every { DocumentsContract.getDocumentId(any()) } returns "root"
        every { DocumentsContract.buildChildDocumentsUriUsingTree(any(), any()) } returns uri
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } returns uri
        cursor = mockk()
        extras = mockk()
        every { extras.getBoolean(any(), any()) } returns false
        every { extras.getString(any()) } returns null
        every { cursor.extras } returns extras
        every { cursor.close() } just Runs
        every { cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID) } returns 0
        every { cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME) } returns 1
        every { cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE) } returns 2
        every { cursor.getString(0) } returns "data"
        every { cursor.getString(1) } returns "__data"
        every { cursor.getString(2) } returns "application/octet-stream"
        every { context.contentResolver.query(any(), any(), any(), any(), any()) } returns cursor
    }
    @After fun cleanup() { unmockkAll() }
    private fun source(): DirectoryCopySource {
        val type = Class.forName("com.bd2toolsbox.ui.viewmodel.MainViewModel\$DocumentFileCopySource")
        val ctor = type.getDeclaredConstructor(Context::class.java, DocumentFile::class.java)
        ctor.isAccessible = true
        return ctor.newInstance(context, root) as DirectoryCopySource
    }
    @Test fun nullQueryIsNotAnEmptySuccessfulTree() {
        every { context.contentResolver.query(any(), any(), any(), any(), any()) } returns null
        assertThrows(IOException::class.java) { source().listChildren(uri.toString()) }
    }
    @Test fun aCursorFailureAfterOneRowIsNotPartialSuccess() {
        every { cursor.moveToNext() } returns true andThenThrows IOException("provider disconnected")
        assertThrows(IOException::class.java) { source().listChildren(uri.toString()) }
    }
    @Test fun loadingProviderDoesNotCertifyACompleteTree() {
        every { extras.getBoolean(DocumentsContract.EXTRA_LOADING, false) } returns true
        assertThrows(IOException::class.java) { source().listChildren(uri.toString()) }
    }
    @Test fun successfulEmptyCursorIsAValidEmptyTree() {
        every { cursor.moveToNext() } returns false
        assertEquals(emptyList<Any>(), source().listChildren(uri.toString()))
    }
}
