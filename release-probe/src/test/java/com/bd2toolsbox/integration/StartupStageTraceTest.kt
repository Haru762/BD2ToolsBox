package com.bd2toolsbox.integration

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class StartupStageTraceTest {
    @Test
    fun testStageSuccess() {
        val events = mutableListOf<String>()
        val trace = StartupStageTrace { events += it }
        val result = trace.stage("sample") { events += "work"; 7 }
        assertEquals(7, result)
        assertEquals(listOf("before sample", "work", "after sample"), events)
    }

    @Test
    fun testStageError() {
        val events = mutableListOf<String>()
        val trace = StartupStageTrace { events += it }
        var thrown: Throwable? = null
        try {
            trace.stage("sample") {
                events += "work"
                throw RuntimeException("test error")
            }
        } catch (t: Throwable) {
            thrown = t
        }
        assertEquals("test error", thrown?.message)
        assertEquals(listOf("before sample", "work", "error sample"), events)
    }
}
