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
        val expectedException = RuntimeException("test error")
        var thrown: Throwable? = null
        try {
            trace.stage("sample") {
                events += "work"
                throw expectedException
            }
            trace.stage("never") {
                events += "should not run"
            }
        } catch (t: Throwable) {
            thrown = t
        }
        org.junit.Assert.assertSame(expectedException, thrown)
        assertEquals(listOf("before sample", "work", "error sample"), events)
    }
}
