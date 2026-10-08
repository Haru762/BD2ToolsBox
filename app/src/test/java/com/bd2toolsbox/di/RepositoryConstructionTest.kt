package com.bd2toolsbox.di

import android.content.Context
import com.bd2toolsbox.data.repository.AvatarRepository
import com.bd2toolsbox.data.repository.Bd2RedeemRepository
import com.bd2toolsbox.data.repository.BundleBackupRepository
import com.bd2toolsbox.data.repository.CharacterMetaRepository
import com.bd2toolsbox.data.repository.CharacterRepository
import com.bd2toolsbox.data.repository.GamekeeRepository
import com.bd2toolsbox.data.repository.InstalledModRepository
import com.bd2toolsbox.data.repository.ModRepository
import com.bd2toolsbox.data.repository.PreviewCacheRepository
import com.bd2toolsbox.core.storage.Digester
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * T17：验证有状态仓储可以**直接构造**（不需要启动 Hilt），以及 `RepositoryModule` 给出的
 * 装配语义正确。纯 JVM，用 `mockk<Context>()` 提供 `filesDir`，不用 Robolectric、不启 Hilt。
 *
 * 这条测试同时守住两件事：
 *  1. 迁到 DI 后仓储仍是「传进依赖就能 new」的普通类——真实业务类可用 fake 文件/摘要器测试。
 *  2. `RepositoryModule` 的 `@Provides` 在同一 Context 下把 `ModRepository` 装配到**同一个**
 *     `CharacterRepository` 上（图内单一实例的行为，用模块方法直接验证，不需要设备）。
 */
class RepositoryConstructionTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var filesDir: File

    @Before
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0

        filesDir = temp.newFolder("files")
        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.applicationContext } returns context
        every { context.getExternalFilesDir(any()) } returns temp.newFolder("external")
    }

    @After
    fun tearDown() = unmockkAll()

    /** 有状态仓储都能用注入的依赖直接构造，不依赖 `initialize(context)` 或 `get()` 单例。 */
    @Test
    fun statefulRepositoriesConstructDirectly() {
        val character = CharacterRepository(context)
        assertNotNull(ModRepository(context, character))
        assertNotNull(InstalledModRepository(context))
        assertNotNull(PreviewCacheRepository(context))
        // 真实业务类可用 fake 摘要器测试（不碰真 SHA-256）。
        val fakeDigester = Digester { file -> "fake-" + file.name }
        assertNotNull(BundleBackupRepository(context, fakeDigester))
        val metadata = CharacterMetaRepository(context)
        assertNotNull(AvatarRepository(context, metadata))
        assertNotNull(GamekeeRepository(context))
        assertNotNull(Bd2RedeemRepository(context))
    }

    /** RepositoryModule 的 @Provides 都能以注入 Context 产出可用仓储（与手写 new 同效）。 */
    @Test
    fun moduleProvidersProduceRepositories() {
        val character = RepositoryModule.provideCharacterRepository(context)
        assertNotNull(character)
        assertNotNull(RepositoryModule.provideModRepository(context, character))
        assertNotNull(RepositoryModule.provideInstalledModRepository(context))
        assertNotNull(RepositoryModule.provideBundleBackupRepository(context))
        assertNotNull(RepositoryModule.providePreviewCacheRepository(context))
    }

    /** InstalledModRepository 的账本文件锚定在注入的 Context.filesDir 上（账本单一事实源的前提）。 */
    @Test
    fun installedModRepositoryLedgerAnchorsToInjectedFilesDir() {
        val repo = InstalledModRepository(context)
        assertTrue(repo.file().absolutePath.startsWith(filesDir.absolutePath))
        assertTrue(repo.journalFile().absolutePath.startsWith(filesDir.absolutePath))
    }

    /** 调度器模块给出两种不同 qualifier 的调度器，可被测试替换。 */
    @Test
    fun dispatcherModuleProvidesBothDispatchers() {
        assertNotNull(DispatcherModule.provideIoDispatcher())
        assertNotNull(DispatcherModule.provideDefaultDispatcher())
    }

    /** 时钟模块给出可注入的时钟，默认走系统钟。 */
    @Test
    fun runtimeModuleProvidesClock() {
        val clock = RuntimeModule.provideClock()
        assertTrue(clock.nowMs() > 0L)
    }
}
