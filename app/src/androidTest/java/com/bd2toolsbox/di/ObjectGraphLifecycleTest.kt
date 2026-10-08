package com.bd2toolsbox.di

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bd2toolsbox.MainActivity
import dagger.hilt.android.EntryPointAccessors
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

    /** GuideViewModel 与 UI 入口共享生产 Hilt 图中的同一仓储。 */
    @Test
    fun guideViewModelAndUiEntryPointShareTheSameGamekeeRepository() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                val guideViewModel = guideViewModelOf(activity)
                val repoField = GuideViewModel::class.java.getDeclaredField("repo")
                    .apply { isAccessible = true }
                val injected = repoField.get(guideViewModel)
                assertSame(
                    "GuideViewModel 与 UI 必须共享同一应用图的 GamekeeRepository",
                    EntryPointAccessors.fromApplication(activity, UiRepositories::class.java).gamekeeRepository(),
                    injected,
                )
            }
        }
    }

    /** Activity 重建不替换仓储，也不把 Activity Context 存进长寿命仓储。 */
    @Test
    fun repositoriesSurviveActivityRecreationWithoutRetainingActivityContext() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val before = EntryPointAccessors.fromApplication(appContext, UiRepositories::class.java)
            val repositories = listOf(
                before.characterMetaRepository(), before.avatarRepository(),
                before.gamekeeRepository(), before.bd2RedeemRepository()
            )
            scenario.recreate()
            scenario.onActivity { activity ->
                val after = EntryPointAccessors.fromApplication(activity, UiRepositories::class.java)
                val current = listOf(
                    after.characterMetaRepository(), after.avatarRepository(),
                    after.gamekeeRepository(), after.bd2RedeemRepository()
                )
                repositories.zip(current).forEach { (old, new) ->
                    assertSame(old, new)
                    val contextField = new.javaClass.getDeclaredField("appContext").apply { isAccessible = true }
                    assertSame(activity.applicationContext, contextField.get(new))
                }
            }
        }
    }
}
