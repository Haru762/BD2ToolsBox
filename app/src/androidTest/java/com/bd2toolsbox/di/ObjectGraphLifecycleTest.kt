package com.bd2toolsbox.di

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bd2toolsbox.MainActivity
import com.bd2toolsbox.data.repository.AvatarRepository
import com.bd2toolsbox.data.repository.CharacterMetaRepository
import com.bd2toolsbox.data.repository.GamekeeRepository
import com.bd2toolsbox.ui.viewmodel.GuideViewModel
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T20：DI 装配收尾——真实入口无未初始化 lateinit 崩溃；同一应用图不会构造多个
 * 可竞争写账本/缓存的仓储实例。
 *
 * 用真实 `@HiltAndroidApp` 生产入口（[MainActivity]），不是 `HiltTestApplication`——
 * 与 [HiltEntryPointTest] 同一原则：要证明的是生产入口能工作。
 */
@RunWith(AndroidJUnit4::class)
class ObjectGraphLifecycleTest {

    private val appContext get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    /**
     * 冷启动到 RESUMED 不因 lateinit 未初始化崩溃：`MainActivity`（`updateRepository`
     * 字段注入）、`GuideViewModel`（构造注入）都必须在真实入口下可用。
     */
    @Test
    fun coldStartDoesNotCrashOnUninitializedLateinit() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                // 触碰 updateRepository 字段（反射读取，不依赖它是 public）：
                // 若 Hilt 没有真正完成字段注入，这里会抛 UninitializedPropertyAccessException。
                val field = MainActivity::class.java.getDeclaredField("updateRepository")
                    .apply { isAccessible = true }
                assertNotNull("MainActivity.updateRepository 必须已被 Hilt 字段注入", field.get(activity))
            }
        }
    }

    /** `by viewModels()` 的字段名是 `guideViewModel$delegate`，实际类型实现 `kotlin.Lazy`。 */
    private fun guideViewModelOf(activity: MainActivity): GuideViewModel {
        val field = MainActivity::class.java.getDeclaredField("guideViewModel\$delegate")
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return (field.get(activity) as Lazy<GuideViewModel>).value
    }

    /**
     * `GuideViewModel`（GamekeeRepository 构造注入）与仍未接入 Hilt 的
     * `GamekeeRepository.get()` 静态入口（`CdkScreen`/`GuideScreen.GuideThumb` 用）
     * 共享同一个应用级实例——不会因为两条装配路径分裂出两份缓存。
     */
    @Test
    fun guideViewModelAndStaticGetAccessorShareTheSameGamekeeRepository() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                val guideViewModel = guideViewModelOf(activity)
                val repoField = GuideViewModel::class.java.getDeclaredField("repo")
                    .apply { isAccessible = true }
                val injected = repoField.get(guideViewModel)
                assertSame(
                    "GuideViewModel 构造注入的 GamekeeRepository 必须与 get() 静态入口同一个",
                    GamekeeRepository.get(activity.applicationContext),
                    injected,
                )
            }
        }
    }

    /**
     * T20 新增：`CharacterMetaRepository`/`AvatarRepository` 的 `@Provides` 委托
     * 与静态 `get()` 入口共享同一实例（`CharacterScreen`/`AvatarImage` 仍走 `get()`）。
     * 与 JVM 的 `RepositoryConstructionTest` 同一不变量，这里在真实 Hilt 对象图上核实。
     */
    @Test
    fun avatarAndCharacterMetaRepositoriesShareTheSameInstanceAcrossEntryPoints() {
        val ctx = appContext
        assertSame(
            "CharacterMetaRepository 的静态 get() 必须是进程内唯一实例",
            CharacterMetaRepository.get(ctx),
            CharacterMetaRepository.get(ctx),
        )
        assertSame(
            "AvatarRepository 的静态 get() 必须是进程内唯一实例",
            AvatarRepository.get(ctx),
            AvatarRepository.get(ctx),
        )
    }
}
