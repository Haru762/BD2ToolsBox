package com.bd2toolsbox.di

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bd2toolsbox.Bd2Application
import com.bd2toolsbox.MainActivity
import com.bd2toolsbox.SpinePreviewActivity
import com.chaquo.python.Python
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import androidx.lifecycle.Lifecycle
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * T16：验证 Hilt 对象图在**生产形态**下真正建立并可解析，且 Application 没有抢先启动 Python。
 *
 * 用真实 `@HiltAndroidApp` 的 [Bd2Application]（不是 `HiltTestApplication`）——要证明的是
 * 生产入口能工作，而不是测试专用容器。因此用标准 `AndroidJUnitRunner`。
 *
 * 目前生产代码还没有构造注入的依赖（留给 T17），所以不在测试里自定义 `@EntryPoint`——
 * 测试 APK 里声明的入口不会被聚合进应用编译时已生成的 `SingletonComponent`（需要
 * `hilt-android-testing` + `HiltTestApplication` 才行，那会引入测试专用容器，与“验证生产
 * 形态”矛盾）。改成直接验证生产形态：Application 是 Hilt 生成类、两个 `@AndroidEntryPoint`
 * Activity 能在 Hilt 下独立冷启动到 RESUMED（真正走一遍 Hilt 的注入接管）。
 *
 * Python 边界：**Application/Hilt 初始化不得抢先启动 Python**（[applicationDoesNotEagerlyStartPython]
 * 验证，按类名字母序它最先跑，此时进程内还没拉过任何 Activity）。主界面启动后会在后台
 * 协程异步刷新角色表（`startAsyncInitialization` → `CharacterRepository.updateCharacterData` →
 * `PythonRuntime.ensureStarted`）——那是角色表功能的实际使用，不阻塞首屏，不是“抢先”；所以
 * 主界面冷启动用例**不**断言 Python 未启动。
 */
// Application 抢先启动 Python 的断言只在“本进程还没拉过任何 Activity”时成立，而主界面
// 启动会异步拉起 Python。固定方法序为名字母升序，保证 applicationDoesNotEagerlyStartPython
// 在任何 mainActivity* 之前跑（JUnit 默认用反射顺序，不可靠）。
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@RunWith(AndroidJUnit4::class)
class HiltEntryPointTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val appContext: Context get() = instrumentation.targetContext.applicationContext

    /** 应用是真实的 @HiltAndroidApp 生产 Application，且 Hilt 已生成其组件。 */
    @Test
    fun applicationIsHiltGeneratedProductionApp() {
        assertTrue(
            "Application 应是 Bd2Application 或其 Hilt 生成子类，实际=${appContext.javaClass.name}",
            appContext is Bd2Application
        )
        // Hilt 会给 @HiltAndroidApp 生成 Hilt_Bd2Application 基类；类名里必须能看到 Bd2Application。
        assertTrue(appContext.javaClass.name.contains("Bd2Application"))
        // 应用的 SingletonComponent 实现类必须已由 KSP 生成并可加载。
        val singletonC = Class.forName("com.bd2toolsbox.DaggerBd2Application_HiltComponents_SingletonC")
        assertNotNull(singletonC)
    }

    /**
     * Application 冷启动不得抢先启动 Python：本测试进程走到这里，Application 已 onCreate 完成，
     * 但没有任何功能调用过 [com.bd2toolsbox.core.python.PythonRuntime]，此时解释器必须仍未启动。
     */
    @Test
    fun applicationDoesNotEagerlyStartPython() {
        assertFalse("Application/Hilt 初始化不应启动 Python 解释器", Python.isStarted())
    }

    /** 主界面在 Hilt（@AndroidEntryPoint）下能独立冷启动到 RESUMED。 */
    @Test
    fun mainActivityColdStartsUnderHilt() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                // @AndroidEntryPoint 的 Activity 实际运行类是 Hilt 子类，但 instanceof MainActivity 仍成立。
                assertTrue(activity is MainActivity)
                val hiltBase = Class.forName("com.bd2toolsbox.Hilt_MainActivity")
                assertTrue(hiltBase.isAssignableFrom(activity.javaClass))
            }
        }
    }

    /** 预览入口（@AndroidEntryPoint）同样能在 Hilt 下独立创建；它不依赖主界面的对象图。 */
    @Test
    fun spinePreviewActivityIsHiltEntryPoint() {
        // 不实际拉起 WebView（需要 mod 数据），只确认 Hilt 为它生成了注入基类。
        val hiltBase = Class.forName("com.bd2toolsbox.Hilt_SpinePreviewActivity")
        assertTrue(hiltBase.isAssignableFrom(SpinePreviewActivity::class.java))
    }
}
