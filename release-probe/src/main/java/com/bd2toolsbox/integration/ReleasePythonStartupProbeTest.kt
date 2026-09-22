package com.bd2toolsbox.integration

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Process
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReleasePythonStartupProbeTest {

    private companion object {
        const val TARGET_PACKAGE = "com.bd2toolsbox"
        const val TAG = "ReleasePythonStartupProbe"
        const val STREAM_PREFIX = "BD2_PY_STARTUP"

        const val PY_CLASS = "com.chaquo.python.Python"
        const val PLATFORM_CLASS = "com.chaquo.python.android.AndroidPlatform"
        const val PLATFORM_BASE = "com.chaquo.python.Python\$Platform"
        const val PYOBJECT_CLASS = "com.chaquo.python.PyObject"
    }

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun loader(context: Context): ClassLoader = context.classLoader

    private fun report(line: String) {
        val b = Bundle()
        b.putString(Instrumentation.REPORT_KEY_STREAMRESULT, "$STREAM_PREFIX $line\n")
        instrumentation.sendStatus(2, b)
        Log.i(TAG, line)
    }

    private fun assertHostIsTarget(context: Context) {
        assertEquals("探针必须跑在目标包进程里（instrumentation host）",
            TARGET_PACKAGE, Application.getProcessName())
        assertTrue("myPid 必须为正数", Process.myPid() > 0)
        val app = context.applicationInfo
        assertEquals("目标 applicationId", TARGET_PACKAGE, app.packageName)
        assertEquals("目标不得是 debuggable 构建", 0,
            app.flags and ApplicationInfo.FLAG_DEBUGGABLE)
    }

    @Test
    fun controlWithoutPython() {
        val context = instrumentation.targetContext
        assertHostIsTarget(context)
        report("control: PID=${Process.myPid()}")
    }

    private class SetupResult(
        val context: Context,
        val pid: Int,
        val trace: StartupStageTrace,
        val pyClass: Class<*>,
        val platformBase: Class<*>,
        val platformClass: Class<*>,
        val platform: Any
    )

    private fun prepareStartup(): SetupResult {
        val context = instrumentation.targetContext
        assertHostIsTarget(context)
        val pid = Process.myPid()
        
        val trace = StartupStageTrace { msg ->
            report("PID=$pid $msg")
        }

        trace.stage("python.class.load") {
            Class.forName(PY_CLASS, false, loader(context))
        }

        val pyClass = trace.stage("python.class.init") {
            Class.forName(PY_CLASS, true, loader(context))
        }

        val isStarted = trace.stage("python.isStarted") {
            pyClass.getMethod("isStarted").invoke(null) as Boolean
        }
        assertFalse("Python isStarted expected to be false in a fresh process", isStarted)

        val platformBase = trace.stage("platform.base.load") {
            Class.forName(PLATFORM_BASE, true, loader(context))
        }

        val platformClass = trace.stage("platform.class.load") {
            Class.forName(PLATFORM_CLASS, true, loader(context))
        }

        val platform = trace.stage("platform.construct") {
            platformClass.getConstructor(Context::class.java).newInstance(context)
        }

        return SetupResult(context, pid, trace, pyClass, platformBase, platformClass, platform)
    }

    @Test
    fun tracedPythonStartup() {
        val setup = prepareStartup()
        val trace = setup.trace
        val pyClass = setup.pyClass
        val platformBase = setup.platformBase
        val platform = setup.platform
        val context = setup.context
        val pid = setup.pid

        trace.stage("python.start") {
            pyClass.getMethod("start", platformBase).invoke(null, platform)
        }

        val py = trace.stage("python.instance") {
            pyClass.getMethod("getInstance").invoke(null)
                ?: error("Python.getInstance() 返回 null")
        }

        val sys = trace.stage("python.sys") {
            py.javaClass.getMethod("getModule", String::class.java).invoke(py, "sys")
                ?: error("getModule(\"sys\") 返回 null")
        }

        trace.stage("python.version") {
            val pyObjectClass = Class.forName(PYOBJECT_CLASS, false, loader(context))
            val getAttrMethod = pyObjectClass.getMethod("get", Any::class.java)
            val versionInfo = getAttrMethod.invoke(sys, "version_info")!!
            val major = getAttrMethod.invoke(versionInfo, "major").toString().trim()
            val minor = getAttrMethod.invoke(versionInfo, "minor").toString().trim()
            
            assertEquals("3", major)
            assertEquals("8", minor)
        }

        report("DONE PID=$pid")
    }

    @Test
    fun tracedNativeStartOnly() {
        val setup = prepareStartup()
        val trace = setup.trace
        val pyClass = setup.pyClass
        val platformBase = setup.platformBase
        val platform = setup.platform
        val pid = setup.pid

        synchronized(pyClass) {
            val path = trace.stage("platform.getPath") {
                platformBase.getMethod("getPath").invoke(platform) as String
            }
            val nativeStart = trace.stage("python.startNative.lookup") {
                pyClass.getDeclaredMethod("startNative", platformBase, String::class.java)
                    .apply { isAccessible = true }
            }
            trace.stage("python.startNative") {
                nativeStart.invoke(null, platform, path)
            }
        }

        report("NATIVE_ONLY_RETURNED scope=partial-native-init PID=$pid")
    }
}
