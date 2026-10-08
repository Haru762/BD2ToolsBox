package com.bd2toolsbox.di

import android.app.Application
import com.bd2toolsbox.DaggerBd2Application_HiltComponents_SingletonC
import com.bd2toolsbox.data.repository.AvatarRepository
import dagger.hilt.android.internal.modules.ApplicationContextModule
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/** 使用生产 Hilt 生成的图，只替换 Android Application；不启动网络、磁盘加载或 Activity。 */
class RepositoryGraphIsolationTest {
    private fun graph(): UiRepositories {
        val application = mockk<Application>(relaxed = true)
        every { application.applicationContext } returns application
        return DaggerBd2Application_HiltComponents_SingletonC.builder()
            .applicationContextModule(ApplicationContextModule(application))
            .build()
    }

    @Test
    fun allUiRepositoriesAreSharedWithinAnApplicationGraph() {
        val graph = graph()
        assertSame(graph.characterMetaRepository(), graph.characterMetaRepository())
        assertSame(graph.avatarRepository(), graph.avatarRepository())
        assertSame(graph.gamekeeRepository(), graph.gamekeeRepository())
        assertSame(graph.bd2RedeemRepository(), graph.bd2RedeemRepository())
    }

    @Test
    fun characterMetaDoesNotEscapeItsApplicationGraph() {
        assertNotSame(graph().characterMetaRepository(), graph().characterMetaRepository())
    }

    @Test
    fun avatarCacheDoesNotEscapeItsApplicationGraph() {
        assertNotSame(graph().avatarRepository(), graph().avatarRepository())
    }

    @Test
    fun gamekeeCacheDoesNotEscapeItsApplicationGraph() {
        assertNotSame(graph().gamekeeRepository(), graph().gamekeeRepository())
    }

    @Test
    fun redeemMutexAndAccountStateDoNotEscapeTheirApplicationGraph() {
        assertNotSame(graph().bd2RedeemRepository(), graph().bd2RedeemRepository())
    }

    @Test
    fun avatarUsesTheMetadataInstanceFromItsOwnGraph() {
        val graph = graph()
        val field = AvatarRepository::class.java.getDeclaredField("characterMetaRepository")
            .apply { isAccessible = true }
        assertSame(graph.characterMetaRepository(), field.get(graph.avatarRepository()))
    }
}
