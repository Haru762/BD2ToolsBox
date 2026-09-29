package com.bd2toolsbox.core.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bd2toolsbox.core.python.PythonRuntime
import com.chaquo.python.Python
import kotlinx.coroutines.CancellationException
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real engine calls on a self-authored TextAsset bundle; never reads or writes game data. */
@RunWith(AndroidJUnit4::class)
class NativeEngineSmokeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val engine: ModEngine get() = ModEngine.get()
    private lateinit var root: File

    @Before
    fun startPython() {
        root = File(context.cacheDir, "t18-engine-${UUID.randomUUID()}")
        check(root.mkdir())
        PythonRuntime.ensureStarted(context)
        assertTrue(Python.isStarted())
    }

    @After
    fun removeOwnFixture() {
        if (::root.isInitialized) {
            check(root.parentFile == context.cacheDir && root.name.startsWith("t18-engine-"))
            check(root.deleteRecursively())
        }
    }

    private fun sourceBundle(): File {
        val code = instrumentation.context.assets.open("engine/text_asset_bundle.py")
            .bufferedReader().use { it.readText() }
        val builtins = Python.getInstance().getModule("builtins")
        val globals = builtins.callAttr("dict")
        builtins.callAttr("exec", code, globals)
        val bytes = globals.callAttr("get", "build_bundle").call().toJava(ByteArray::class.java)
        return File(root, "source.bundle").apply { writeBytes(bytes) }
    }

    @Test
    fun validateBundleOnMissingFileReturnsTypedFailure() {
        val (ok, message) = engine.validateBundle(File(root, "absent.bundle").absolutePath)
        assertFalse(ok)
        assertTrue(message, message.contains("文件不存在"))
    }

    @Test
    fun validateBundleOnGarbageReturnsTypedFailure() {
        val file = File(root, "invalid.bundle").apply { writeBytes(ByteArray(512) { 0x41 }) }
        val (ok, message) = engine.validateBundle(file.absolutePath)
        assertFalse(ok)
        assertTrue(message, message.contains("UnityFS"))
    }

    @Test
    fun restoreAndUnpackPreserveTheTextAssetBytes() {
        val source = sourceBundle()
        val valid = engine.validateBundle(source.absolutePath)
        assertTrue(valid.second, valid.first)
        val output = File(root, "converted.bundle")
        val progress = mutableListOf<String>()
        val restored = engine.restoreBundle(source.absolutePath, output.absolutePath) { progress += it }
        assertTrue(restored.second, restored.first)
        assertTrue("Conversion must write a bundle", output.isFile && output.length() > 0)
        assertTrue("Conversion must report progress", progress.isNotEmpty())
        assertFalse("Conversion must re-encode, not simply copy", source.readBytes().contentEquals(output.readBytes()))
        val validOutput = engine.validateBundle(output.absolutePath)
        assertTrue(validOutput.second, validOutput.first)

        // Both export modes must preserve content, not merely return a non-empty status message.
        for (fast in listOf(false, true)) {
            val directory = File(root, "unpacked-$fast")
            val unpacked = engine.unpackBundle(output.absolutePath, directory.absolutePath, fast) { false }
            assertTrue(unpacked.second, unpacked.first)
            assertArrayEquals(
                "T18 conversion fixture.\n".toByteArray(Charsets.UTF_8),
                File(directory, "t18-text.txt").readBytes(),
            )
        }
    }

    @Test
    fun callbackCancellationStopsBeforeExportingTheAsset() {
        val source = sourceBundle()
        val directory = File(root, "cancelled")
        var cancellationRequested = false
        val result = engine.unpackBundle(source.absolutePath, directory.absolutePath) { message ->
            message.startsWith("Processing asset").also { if (it) cancellationRequested = true }
        }
        assertTrue("Asset callback must be reached", cancellationRequested)
        assertFalse("Requested cancellation must not report success", result.first)
        assertTrue(result.second, result.second.contains("cancel", ignoreCase = true))
        assertFalse(File(directory, "t18-text.txt").exists())
    }

    @Test
    fun callbackCancellationExceptionIsNotLostInsidePython() {
        val source = sourceBundle()
        val directory = File(root, "cancel-exception")
        val cancelled = CancellationException("T18 callback cancelled")
        val actual = assertThrows(CancellationException::class.java) {
            engine.unpackBundle(source.absolutePath, directory.absolutePath) { message ->
                if (message.startsWith("Processing asset")) throw cancelled
                false
            }
        }
        assertSame(cancelled, actual)
        assertFalse(File(directory, "t18-text.txt").exists())
    }

    @Test
    fun scanFailureDoesNotMasqueradeAsAnEmptyWorklist() {
        val failure = assertThrows(EngineBridgeException::class.java) {
            engine.checkScanNeeded(root.absolutePath, "[") {}
        }
        assertEquals("check_scan_needed", failure.function)
        assertEquals(EngineBridgeException.Phase.CALL, failure.phase)
    }

    @Test
    fun emptyResolverBatchDecodesARealJsonArray() {
        val result = engine.resolveModBatch("[]", root.absolutePath, "SD") {}
        assertTrue(result.first)
        assertEquals(0, requireNotNull(result.second).length())
    }

    @Test
    fun repeatedRuntimeAccessKeepsTheSameInterpreter() {
        val python = Python.getInstance()
        repeat(3) {
            PythonRuntime.ensureStarted(context)
            assertSame(python, Python.getInstance())
        }
    }

    @Test
    fun bc1DecodeProducesDeterministicOutput() {
        val builtins = Python.getInstance().getModule("builtins")
        val globals = builtins.callAttr("dict")
        builtins.callAttr(
            "exec",
            """
            import texture2ddecoder as _t2d
            _res = _t2d.decode_bc1(b"\xff\xff\x00\x00\x00\x00\x00\x00", 4, 4)
            _ok = (_res == b"\xff" * 64)
            _len = len(_res)
            """.trimIndent(),
            globals,
        )
        assertEquals(64, globals.callAttr("get", "_len").toInt())
        assertEquals("True", globals.callAttr("get", "_ok").toString())
    }
}
