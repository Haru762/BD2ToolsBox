package com.bd2toolsbox.compat

import android.app.Instrumentation
import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T13 设备冒烟：在真实 Application（@HiltAndroidApp）下验证 Hilt/KSP 生成代码、
 * kotlinx.serialization 往返、Chaquopy Python 调用与 AboutLibraries 生成的清单。
 * 不使用 HiltTestApplication：要证明的是生产形态的 Application 入口能工作。
 */
@RunWith(AndroidJUnit4::class)
class CompatibilitySmokeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun report(line: String) {
        instrumentation.sendStatus(2, Bundle().apply {
            putString(Instrumentation.REPORT_KEY_STREAMRESULT, "BD2_COMPAT $line\n")
        })
    }

    @Test
    fun hiltSingletonGraphAndEntryPoint() {
        val app = context.applicationContext
        assertTrue("Application must be the Hilt application", app is CompatApp)
        val ep = EntryPointAccessors.fromApplication(app, ProbeEntryPoint::class.java)
        assertEquals("hilt-ok", ep.greeter().greet())
        assertSame("@Singleton must return the same instance", ep.repository(), ep.repository())
        assertEquals(context.packageName, ep.repository().packageName)
        report("hilt entryPoint=OK singleton=OK")
    }

    @Test
    fun hiltActivityInjectionAndViewModel() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(context.packageName, activity.repository.packageName)
                val vm = ViewModelProvider(activity)[ProbeViewModel::class.java]
                assertEquals("hilt-ok", vm.message())
            }
        }
        report("hilt activity=OK viewModel=OK")
    }

    @Test
    fun serializationRoundTrip() {
        val text = ProbeChecks.serializationRoundTrip()
        assertTrue(text, text.contains("\"schemaVersion\":1") && text.contains("\"savedAt\":1789992348967"))
        report("serialization $text")
    }

    @Test
    fun pythonCall() {
        val result = ProbeChecks.pythonCall(context)
        val parts = result.split('|')
        assertEquals(result, 4, parts.size)
        assertEquals("Target Python", "3.8", parts[0])
        val payloadAbi = context.applicationInfo.nativeLibraryDir.trimEnd('/').substringAfterLast('/')
            .let { if (it == "arm64") "arm64-v8a" else it }
        assertEquals("Chaquopy ABI must match installed payload", payloadAbi, parts[1])
        assertEquals("lz4-ok", parts[2])
        assertEquals("PIL-9.2.0", parts[3])
        report("python $result nativeLibraryDir=${context.applicationInfo.nativeLibraryDir}")
    }

    @Test
    fun aboutLibrariesGenerated() {
        val count = ProbeChecks.aboutLibrariesCount(context)
        assertTrue("aboutlibraries.json must list Gradle dependencies, got $count", count > 10)
        report("aboutlibraries libraries=$count")
    }
}
