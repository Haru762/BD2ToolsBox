package com.bd2toolsbox.data.repository

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bd2toolsbox.data.model.ModInfo
import com.bd2toolsbox.ui.viewmodel.MainViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ModSnapshotTest {
    private lateinit var dir: File
    private lateinit var vm: MainViewModel
    private val store = ViewModelStore()
    private val sources = listOf(Uri.parse("content://test/tree/original"))
    private val entries = listOf(ModInfo("test", "Eclipse", "Beach", "cutscene", false,
        Uri.parse("content://test/tree/original/document/mod"), null, true))

    @Before
    fun setup() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        dir = File(base.cacheDir, "snapshot-tests-${UUID.randomUUID()}").apply { mkdirs() }
        vm = MainViewModel(SavedStateHandle())
        store.put("test", vm)
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = dir
        }
        MainViewModel::class.java.getDeclaredField("appContext").apply { isAccessible = true }
            .set(vm, context)
        setSources(sources)
    }

    @After
    fun cleanupOwnFixture() {
        store.clear()
        check(dir.name.startsWith("snapshot-tests-") && dir.parentFile?.name == "cache")
        dir.deleteRecursively()
    }

    private fun setSources(value: List<Uri>) {
        @Suppress("UNCHECKED_CAST")
        val state = MainViewModel::class.java.getDeclaredField("_modSourceDirs").apply { isAccessible = true }
            .get(vm) as MutableStateFlow<List<Uri>>
        state.value = value
    }

    // Schedule now and execute later to reproduce IO writes finishing out of UI order.
    private fun queuedWrite(value: List<ModInfo>): () -> Unit {
        val capture = MainViewModel::class.java.declaredMethods.firstOrNull { it.name == "captureModListSnapshot" }
        val write = MainViewModel::class.java.declaredMethods.single { it.name == "saveModListSnapshot" }
            .apply { isAccessible = true }
        val payload = if (capture != null) {
            capture.isAccessible = true
            capture.invoke(vm, value)
        } else value
        return { write.invoke(vm, payload) }
    }

    private fun restored(): List<*> = MainViewModel::class.java.getDeclaredMethod("loadModListSnapshot")
        .apply { isAccessible = true }.invoke(vm) as List<*>

    @Test
    fun lateScanSnapshotMustNotOverwriteNewerEmptyResult() {
        val oldScan = queuedWrite(entries)
        val newerEmpty = queuedWrite(emptyList())
        newerEmpty()
        oldScan()
        assertTrue("Earlier scan completed last, but newer UI state must win", restored().isEmpty())
    }

    @Test
    fun sourceChangeRejectsOldSnapshot() {
        queuedWrite(entries)()
        setSources(listOf(Uri.parse("content://test/tree/replacement")))
        assertTrue("Never show cached mods from a different source selection", restored().isEmpty())
    }

    @Test
    fun pendingWriteForRemovedSourceDoesNotRestoreItsEntries() {
        val queued = queuedWrite(entries)
        setSources(emptyList())
        queuedWrite(emptyList())()
        queued()
        assertTrue(restored().isEmpty())
    }

    @Test
    fun unchangedSourcesRestoreSnapshot() {
        queuedWrite(entries)()
        assertEquals(1, restored().size)
        assertEquals("test", (restored().single() as ModInfo).name)
    }
}
