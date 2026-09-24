package com.bd2toolsbox.integration

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier

/**
 * Exercises the real production PythonRuntime entry in the minified, non-debuggable target app.
 * All app/Chaquopy classes come from targetContext.classLoader; this probe never constructs a
 * platform or writes AndroidPlatform.ABI. Only the production entry may select the bootstrap.
 *
 * Run ONLY #productionRuntimeImportsAndBc1 using an explicit instrumentation method filter,
 * after force-stopping com.bd2toolsbox, in a fresh OS process. Never combine it with another
 * Python/startup probe (including partial native initialization); force-stop again afterwards.
 * A previously started interpreter is a failure, not a skipped precondition. The optional
 * instrumentation argument expectedPayloadAbi must equal the canonical installed payload ABI.
 *
 * Success is scoped to the actual installed payload and environment exercised. An ARM64 payload
 * running under translation proves translated execution only, NOT native ARM64 device acceptance.
 * Build.SUPPORTED_ABIS is diagnostic metadata, neither the selection source nor proof of native
 * execution. No Activity, network request, or game operation is invoked by this probe.
 */
@RunWith(AndroidJUnit4::class)
class ReleasePythonRuntimeProbeTest {
    private companion object {
        const val TARGET_PACKAGE = "com.bd2toolsbox"
        const val TAG = "PythonRuntimeProbe"
        const val STREAM_PREFIX = "BD2_PYTHON_RUNTIME"
        const val RUNTIME_CLASS = "com.bd2toolsbox.core.python.PythonRuntime"
        const val PY_CLASS = "com.chaquo.python.Python"
        const val PLATFORM_CLASS = "com.chaquo.python.android.AndroidPlatform"
        const val PYOBJECT_CLASS = "com.chaquo.python.PyObject"
    }

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun report(message: String) {
        val line = "$STREAM_PREFIX PID=${Process.myPid()} $message"
        val status = Bundle().apply {
            putString(Instrumentation.REPORT_KEY_STREAMRESULT, "$line\n")
        }
        // Zero is reserved for test completion in the instrumentation runner protocol.
        instrumentation.sendStatus(2, status)
        Log.i(TAG, line)
    }

    private fun <T> stage(name: String, action: () -> T): T {
        report("before $name")
        return try {
            val result = action()
            report("after $name")
            result
        } catch (error: Throwable) {
            // Remove reflection wrappers only: preserve the actual assertion/runtime failure.
            var failure = error
            while (failure is InvocationTargetException) {
                val target = failure.targetException
                if (target == null) break
                failure = target
            }
            try {
                val detail = failure.message?.replace('\n', ' ')?.replace('\r', ' ')
                report("FAILED stage=$name type=${failure.javaClass.name} detail=$detail")
                Log.e(TAG, "Failed stage: $name", failure)
            } catch (reportError: Throwable) {
                // A broken status channel must not replace the original test failure.
                if (reportError !== failure) failure.addSuppressed(reportError)
            }
            throw failure
        }
    }

    @Test
    fun productionRuntimeImportsAndBc1() {
        val context = instrumentation.targetContext
        stage("target.identity") {
            assertEquals("Target context package", TARGET_PACKAGE, context.packageName)
            assertEquals("Instrumentation host process", TARGET_PACKAGE, Application.getProcessName())
            assertTrue("Target PID must be positive", Process.myPid() > 0)
            assertEquals("Target applicationId", TARGET_PACKAGE, context.applicationInfo.packageName)
            assertEquals("Target must be non-debuggable", 0,
                context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        }

        val payloadAbi = stage("payload.identity") {
            val nativeLibraryDir = context.applicationInfo.nativeLibraryDir
            val abi = payloadAbiFromNativeLibraryDir(nativeLibraryDir)
                ?: error("Unsupported nativeLibraryDir: $nativeLibraryDir")
            val expected = InstrumentationRegistry.getArguments().getString("expectedPayloadAbi")
            report("env scope=ACTUAL_PAYLOAD_ONLY payloadAbi=$abi expectedPayloadAbi=$expected " +
                "nativeLibraryDir=$nativeLibraryDir supportedAbis=${Build.SUPPORTED_ABIS.joinToString(",")}")
            if (expected != null) {
                assertEquals("Expected installed payload ABI", expected, abi)
            }
            abi
        }

        val pyClass = stage("python.class") {
            Class.forName(PY_CLASS, true, context.classLoader)
        }
        val isStarted = pyClass.getMethod("isStarted")
        val getInstance = pyClass.getMethod("getInstance")
        val abiField = stage("platform.abi.read-only") {
            Class.forName(PLATFORM_CLASS, true, context.classLoader).getField("ABI")
        }
        stage("fresh-process.precondition") {
            assertFalse("Python must not be started: run this method alone after force-stop",
                isStarted.invoke(null) as Boolean)
            // This may be null before platform construction: it is not a guessed default ABI.
            report("abiBeforeStartup=${abiField.get(null)} payloadAbi=$payloadAbi")
        }

        val ensureStarted = stage("runtime.entry.lookup") {
            val runtimeClass = Class.forName(RUNTIME_CLASS, false, context.classLoader)
            val method = runtimeClass.getMethod("ensureStarted", Context::class.java)
            assertTrue("PythonRuntime class must be public", Modifier.isPublic(runtimeClass.modifiers))
            assertEquals("Entry must be declared on PythonRuntime", runtimeClass, method.declaringClass)
            assertTrue("Entry must be public", Modifier.isPublic(method.modifiers))
            assertTrue("Entry must be @JvmStatic", Modifier.isStatic(method.modifiers))
            assertEquals("Entry must return Unit/void", java.lang.Void.TYPE, method.returnType)
            method
        }
        val py = stage("runtime.ensureStarted") {
            assertNull("Unit/void invocation result", ensureStarted.invoke(null, context))
            // Check before getInstance: it must not implicitly start Python for a broken facade.
            assertTrue("Production entry must fully start Python", isStarted.invoke(null) as Boolean)
            assertEquals("Production-selected ABI must match the installed payload",
                payloadAbi, abiField.get(null))
            getInstance.invoke(null) ?: error("Python.getInstance() returned null")
        }
        val selectedAbi = abiField.get(null) as String
        report("selectedAbi=$selectedAbi payloadAbi=$payloadAbi")

        val getModule = pyClass.getMethod("getModule", String::class.java)
        val getAttr = Class.forName(PYOBJECT_CLASS, false, context.classLoader)
            .getMethod("get", Any::class.java)
        stage("python.version") {
            val sys = getModule.invoke(py, "sys") ?: error("sys module missing")
            val versionInfo = getAttr.invoke(sys, "version_info") ?: error("sys.version_info missing")
            val major = getAttr.invoke(versionInfo, "major").toString().trim()
            val minor = getAttr.invoke(versionInfo, "minor").toString().trim()
            assertEquals("Target Python major version", "3", major)
            assertEquals("Target Python minor version", "8", minor)
            val version = getAttr.invoke(sys, "version").toString().replace('\n', ' ')
            report("python=$major.$minor version=$version")
        }
        val zlibPath = stage("python.zlib.path") {
            val zlib = getModule.invoke(py, "zlib") ?: error("zlib module missing")
            val path = getAttr.invoke(zlib, "__file__")?.toString() ?: error("zlib.__file__ missing")
            assertTrue("zlib must come from the installed payload bootstrap: $path",
                path.contains("/bootstrap-native/$payloadAbi/") && path.endsWith("/zlib.so"))
            report("zlib=$path selectedAbi=$selectedAbi")
            path
        }

        stage("runtime.repeated-ensureStarted") {
            repeat(3) { index ->
                assertNull("Repeat ${index + 1}: Unit/void invocation result",
                    ensureStarted.invoke(null, context))
                assertTrue("Repeat ${index + 1}: Python must remain started",
                    isStarted.invoke(null) as Boolean)
                assertSame("Repeat ${index + 1}: Python singleton must be identical",
                    py, getInstance.invoke(null))
                assertEquals("Repeat ${index + 1}: ABI must not change after startup",
                    selectedAbi, abiField.get(null))
            }
        }

        // Reuse the actual existing methods and their complete assertions, without modifying them.
        // Python is already fully started, so their fallback startup branches must not execute.
        val dependencies = ReleasePythonDependencyProbeTest()
        stage("dependencies.targetPythonImports") {
            dependencies.targetPythonImports()
        }
        stage("dependencies.nativeTextureDecoderAvailable") {
            dependencies.nativeTextureDecoderAvailable()
        }
        stage("runtime.final-state") {
            assertTrue("Python must remain started after dependency checks", isStarted.invoke(null) as Boolean)
            assertSame("Dependency checks must retain the Python singleton", py, getInstance.invoke(null))
            assertEquals("Dependency checks must not change the ABI", selectedAbi, abiField.get(null))
        }
        report("PYTHON_RUNTIME_READY scope=ACTUAL_PAYLOAD_ONLY payloadAbi=$payloadAbi " +
            "selectedAbi=$selectedAbi python=3.8 zlib=$zlibPath repeatedEnsureCalls=3 " +
            "nativeArm64Acceptance=NOT_ESTABLISHED")
    }
}
