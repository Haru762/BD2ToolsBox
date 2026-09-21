package com.bd2toolsbox.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * Device Chaquopy check for restore_bundle determinism.
 * Input and outputs must stay inside this app's external cache. The game tree is never written.
 */
@RunWith(AndroidJUnit4::class)
class RestoreBundleDeterminismDeviceTest {
    @Test
    fun restoreBundleTwiceIsByteIdentical() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context))
        }
        val cache = requireNotNull(context.externalCacheDir)
        val root = File(cache, "restore-probe").canonicalFile
        val input = File(root, "input").canonicalFile
        val first = File(root, "out-1").canonicalFile
        val second = File(root, "out-2").canonicalFile
        assertTrue("probe input missing", input.isFile)
        assertTrue(input.canonicalPath.startsWith(cache.canonicalPath + File.separator))
        assertTrue(!input.canonicalPath.contains("com.neowizgames.game.browndust2"))
        first.delete()
        second.delete()

        val module = Python.getInstance().getModule("main_script")
        val firstResult = module.callAttr("restore_bundle", input.absolutePath, first.absolutePath).asList()
        val secondResult = module.callAttr("restore_bundle", input.absolutePath, second.absolutePath).asList()
        val report = JSONObject()
            .put("firstOk", firstResult[0].toBoolean())
            .put("firstMessage", firstResult[1].toString())
            .put("secondOk", secondResult[0].toBoolean())
            .put("secondMessage", secondResult[1].toString())
            .put("firstBytes", first.length())
            .put("secondBytes", second.length())
        if (first.isFile && second.isFile) {
            val a = sha(first)
            val b = sha(second)
            report.put("firstSha256", a).put("secondSha256", b).put("identical", a == b)
        }
        File(root, "result.json").writeText(report.toString())

        assertTrue(firstResult[0].toBoolean())
        assertTrue(secondResult[0].toBoolean())
        assertTrue(first.isFile && second.isFile)
        assertEquals(sha(first), sha(second))
        assertTrue(first.length() > 0)
    }

    private fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
