package com.bd2toolsbox.core.python

/**
 * Coordinates check/selection/start on the same monitor as the runtime backend.
 * The backend owns its started/failed state; there is deliberately no second flag
 * which could drift from Chaquopy or allow a failed native start to be retried.
 */
internal class PythonStartup(
    private val lock: Any,
    private val isStarted: () -> Boolean,
    private val startWithAbi: (String) -> Unit
) {
    fun ensureStarted(nativeLibraryDir: () -> String?) {
        synchronized(lock) {
            if (isStarted()) return
            val path = nativeLibraryDir()
            require(path != null && path.startsWith("/")) {
                "Missing or invalid installed native library directory: $path"
            }
            val abi = when (path.trimEnd('/').substringAfterLast('/')) {
                "arm64", "arm64-v8a" -> "arm64-v8a"
                "x86_64" -> "x86_64"
                else -> throw IllegalArgumentException("Unsupported Python payload directory: $path")
            }
            startWithAbi(abi)
        }
    }
}
