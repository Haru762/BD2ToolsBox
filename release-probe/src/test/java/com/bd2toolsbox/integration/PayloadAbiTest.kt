package com.bd2toolsbox.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PayloadAbiTest {

    @Test
    fun testArm64AliasAndCanonicalSpelling() {
        assertEquals("arm64-v8a", payloadAbiFromNativeLibraryDir("/data/app/example/lib/arm64"))
        assertEquals("arm64-v8a", payloadAbiFromNativeLibraryDir("/data/app/example/lib/arm64-v8a"))
        assertEquals("arm64-v8a", payloadAbiFromNativeLibraryDir("/data/app/example/lib/arm64/"))
        assertEquals("arm64-v8a", payloadAbiFromNativeLibraryDir("/data/app/example/lib/arm64-v8a/"))
    }

    @Test
    fun testX86_64() {
        assertEquals("x86_64", payloadAbiFromNativeLibraryDir("/data/app/example/lib/x86_64"))
        assertEquals("x86_64", payloadAbiFromNativeLibraryDir("/data/app/example/lib/x86_64/"))
    }

    @Test
    fun testNullEmptyAndRootPath() {
        assertNull(payloadAbiFromNativeLibraryDir(null))
        assertNull(payloadAbiFromNativeLibraryDir(""))
        assertNull(payloadAbiFromNativeLibraryDir("/"))
        assertNull(payloadAbiFromNativeLibraryDir("///"))
    }

    @Test
    fun testUnknownAndAmbiguousArmRejected() {
        assertNull(payloadAbiFromNativeLibraryDir("/data/app/example/lib/unknown"))
        assertNull(payloadAbiFromNativeLibraryDir("/data/app/example/lib/arm"))
        assertNull(payloadAbiFromNativeLibraryDir("/data/app/example/lib/armeabi-v7a"))
        assertNull(payloadAbiFromNativeLibraryDir("/data/app/example/lib/x86"))
    }
}
