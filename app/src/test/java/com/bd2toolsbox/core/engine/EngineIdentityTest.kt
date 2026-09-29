package com.bd2toolsbox.core.engine

import com.bd2toolsbox.di.EngineModule
import org.junit.Assert.assertSame
import org.junit.Test

class EngineIdentityTest {
    @Test
    fun diDoesNotReplaceAnEngineAlreadyUsedByLegacyCallers() {
        val earlyEngine = ModEngine.get()
        val injected = EngineModule.provideModEngine()
        assertSame(earlyEngine, injected)
        assertSame(earlyEngine, ModEngine.get())
        assertSame(injected, EngineModule.provideModEngine())
    }
}
