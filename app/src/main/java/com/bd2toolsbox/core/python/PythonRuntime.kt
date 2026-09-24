package com.bd2toolsbox.core.python

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/** The only production entry point for initializing the process-wide Python interpreter. */
object PythonRuntime {
    @JvmStatic
    @Suppress("DEPRECATION") // AndroidPlatform.ABI is internal to pinned Chaquopy 15.0.1.
    fun ensureStarted(context: Context) {
        PythonStartup(
            // Python.start/isStarted/getInstance also synchronize on Python.class.
            lock = Python::class.java,
            isStarted = Python::isStarted,
            startWithAbi = { abi ->
                val appContext = context.applicationContext ?: context
                // Fail before entering native initialization if packaging is inconsistent.
                // Close each stream: AndroidPlatform's own ABI scan doesn't close its stream.
                appContext.assets.open("chaquopy/stdlib-$abi.imy").use { }
                appContext.assets.open("chaquopy/bootstrap-native/$abi/zlib.so").use { }
                val platform = AndroidPlatform(appContext)
                // MUST be after construction: the constructor selects from device ABIs,
                // which may prefer x86_64 even when this process loads ARM64 via translation.
                // Keep this compatibility adaptation local; do not use reflection or bypass
                // Python.start's failed-start protection, and never reset ABI on a live runtime.
                AndroidPlatform.ABI = abi
                Python.start(platform)
            }
        ).ensureStarted { context.applicationInfo.nativeLibraryDir }
    }
}
