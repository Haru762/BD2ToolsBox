package com.bd2toolsbox.core.python

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PythonStartupTest {
    private fun selectedAbi(path: String?): String {
        var selected: String? = null
        PythonStartup(Any(), { false }, { selected = it }).ensureStarted { path }
        return checkNotNull(selected)
    }

    @Test fun arm64DirectoryUsesArm64V8aAssets() {
        assertEquals("arm64-v8a", selectedAbi("/data/app/example/lib/arm64"))
    }

    @Test fun canonicalArm64DirectoryIsSupported() {
        assertEquals("arm64-v8a", selectedAbi("/data/app/example/lib/arm64-v8a"))
    }

    @Test fun x86PayloadUsesX86Assets() {
        assertEquals("x86_64", selectedAbi("/data/app/example/lib/x86_64"))
    }

    @Test fun trailingSeparatorDoesNotChangeAbi() {
        assertEquals("arm64-v8a", selectedAbi("/data/app/example/lib/arm64/"))
        assertEquals("x86_64", selectedAbi("/data/app/example/lib/x86_64/"))
    }

    @Test fun onlyTheExactFinalDirectoryDeterminesAbi() {
        assertEquals("x86_64", selectedAbi("/data/arm64/lib/x86_64"))
        for (path in listOf("/data/app/arm64/lib", "/data/app/lib/arm", "/data/app/lib/x86",
                "/data/app/lib/arm64-backup", "/data/app/lib/x86_64.tmp", "/data/app/lib/riscv64")) {
            assertThrows(IllegalArgumentException::class.java) { selectedAbi(path) }
        }
    }

    @Test fun missingOrRelativeDirectoryFailsBeforeStarting() {
        for (path in listOf(null, "", " ", "/", "arm64", "relative/lib/x86_64")) {
            var starts = 0
            val startup = PythonStartup(Any(), { false }, { starts++ })
            assertThrows(IllegalArgumentException::class.java) { startup.ensureStarted { path } }
            assertEquals("Must not start for $path", 0, starts)
        }
    }

    @Test fun alreadyStartedDoesNotReadPathsOrModifyThePlatform() {
        val startup = PythonStartup(Any(), { true }, { error("Unexpected initialization") })
        startup.ensureStarted { error("An active runtime must not re-read an ABI") }
    }

    @Test fun subsequentCallsObserveTheRuntimeInsteadOfStartingAgain() {
        var started = false
        val selected = mutableListOf<String>()
        val startup = PythonStartup(Any(), { started }, { selected += it; started = true })
        startup.ensureStarted { "/data/app/lib/arm64" }
        startup.ensureStarted { "/data/app/lib/x86_64" }
        assertEquals(listOf("arm64-v8a"), selected)
    }

    @Test fun startupFailurePropagatesAndDoesNotInventStartedState() {
        val sentinel = IllegalStateException("Python startup previously failed")
        var attempts = 0
        val startup = PythonStartup(Any(), { false }, { attempts++; throw sentinel })
        repeat(2) {
            assertSame(sentinel, assertThrows(IllegalStateException::class.java) {
                startup.ensureStarted { "/data/app/lib/arm64" }
            })
        }
        assertEquals(2, attempts) // Backend owns retry policy (Chaquopy rejects a failed restart).
    }

    @Test fun pathFailurePropagatesWithoutInitializing() {
        val sentinel = SecurityException("No application info")
        val startup = PythonStartup(Any(), { false }, { error("Unexpected initialization") })
        assertSame(sentinel, assertThrows(SecurityException::class.java) {
            startup.ensureStarted { throw sentinel }
        })
    }

    @Test fun pathSelectionAndStartAreUnderTheRuntimeMonitor() {
        val lock = Any()
        val startup = PythonStartup(lock, {
            assertTrue(Thread.holdsLock(lock)); false
        }, {
            assertTrue(Thread.holdsLock(lock))
            assertEquals("arm64-v8a", it)
        })
        startup.ensureStarted {
            assertTrue(Thread.holdsLock(lock))
            "/data/app/lib/arm64"
        }
    }

    @Test fun competingCallersStartExactlyOnceEvenAcrossFacadesSharingALock() {
        val lock = Any()
        var started = false
        val starts = AtomicInteger()
        val ready = CountDownLatch(8)
        val go = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val futures = (1..8).map {
                executor.submit(Callable {
                    val startup = PythonStartup(lock, { started }, {
                        starts.incrementAndGet(); started = true
                    })
                    ready.countDown()
                    check(go.await(5, TimeUnit.SECONDS))
                    startup.ensureStarted { "/data/app/lib/arm64" }
                })
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            go.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, starts.get())
            assertTrue(started)
        } finally {
            go.countDown()
            executor.shutdownNow()
        }
    }
}
