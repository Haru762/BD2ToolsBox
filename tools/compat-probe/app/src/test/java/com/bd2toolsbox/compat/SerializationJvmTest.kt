package com.bd2toolsbox.compat

import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM 侧确认 serialization 编译器插件生成了 serializer（不依赖设备）。 */
class SerializationJvmTest {
    @Test
    fun roundTrip() {
        val text = ProbeChecks.serializationRoundTrip()
        assertTrue(text, text.startsWith("{\"schemaVersion\":1"))
    }
}
