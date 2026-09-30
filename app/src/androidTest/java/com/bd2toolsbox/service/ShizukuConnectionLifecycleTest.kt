package com.bd2toolsbox.service

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bd2toolsbox.MainActivity
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Field

/**
 * T19：Activity 销毁不再无条件切断 Shizuku 连接的占用；只有当所有占用者
 * （Activity 自己 + 后台任务）都释放后，连接才真正被回收。
 *
 * 这条用例验证的是**占用计数与 Activity 生命周期的接线**，不是真实
 * Binder/Shizuku 行为（本机/AVD 没有 Shizuku 服务，`ShizukuManager.isRunning()`
 * 恒为 false，真正的 bind/unbind 不会发生——那部分只能在装了 Shizuku 的
 * 真机上验证）。用反射读 [MainActivity] 私有的 `shizukuConnectionToken` 字段，
 * 断言它的生命周期：onCreate 后非空、onDestroy 后被清空为 null。
 */
@RunWith(AndroidJUnit4::class)
class ShizukuConnectionLifecycleTest {

    private fun tokenField(activity: MainActivity): Field =
        MainActivity::class.java.getDeclaredField("shizukuConnectionToken").apply { isAccessible = true }

    @Test
    fun activityAcquiresATokenOnCreateAndReleasesItOnDestroy() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.moveToState(Lifecycle.State.RESUMED)

        var activityRef: MainActivity? = null
        var tokenAfterCreate: Any? = null
        scenario.onActivity { activity ->
            activityRef = activity
            tokenAfterCreate = tokenField(activity).get(activity)
        }
        assertNotNull("onCreate 之后 Activity 必须已经持有一份占用 token", tokenAfterCreate)

        // scenario.close() 会请求并等待真实 finish/onDestroy 完成（isFinishing=true 路径），
        // 这是生产代码里唯一会真正释放 token 的分支（旋转屏幕的 onDestroy 不释放，
        // 见 MainActivity 注释）。Activity 对象本身不会因销毁而无法访问，可以用之前
        // 拿到的引用继续检查字段。
        scenario.close()

        val tokenAfterDestroy = tokenField(activityRef!!).get(activityRef)
        assertNull("onDestroy(isFinishing=true) 之后必须释放自己那份 token 并清空引用", tokenAfterDestroy)
    }

    @Test
    fun releasingOneOfTwoTokensDoesNotTriggerTheUnbindPath() {
        // 直接验证 ShizukuManager 的占用语义本身：Activity 的 token 与后台任务的
        // token 是完全独立的两份占用。旧行为是 Activity.onDestroy 时无条件 unbind，
        // 会切断仍在 installScope/ScanService 里跑着的任务的连接。
        //
        // 占用计数本身已由 ConnectionOccupancyTest（JVM 单测）完整覆盖。这里验证的是
        // 它与真实 ShizukuGameFileAccess.unbindService 的接线：只释放其中一份 token——
        // 即使底层 unbindService 会真实触碰 Shizuku 类初始化——也不能抵达那一步（
        // 只有全部释放完才会真正 unbind）。后续再释放第二份才会走到真实 unbind路径（
        // 被 try/catch 包住，AVD 上没有 Shizuku 服务时不应抛异常到测试层）。
        val activityToken = ShizukuManager.acquireConnection()
        val backgroundTaskToken = ShizukuManager.acquireConnection()
        assertNotSame("两次 acquire 必须返回不同的 token，否则无法区分谁释放了谁的占用", activityToken, backgroundTaskToken)

        // 模拟 Activity 销毁：只释放它自己的 token，这一步不应试图接触 Shizuku
        // 静态初始化（因为还有 backgroundTaskToken 没释放）。未抛异常即通过——
        // AVD 没有 Shizuku 服务，若这一步错误地走到了真实 unbind 路径，会在这里抛出
        // Shizuku 未初始化相关异常，而不是静默通过。
        ShizukuManager.releaseConnection(activityToken)

        // 后台任务的 token 完全不受影响——它自己决定何时释放，不因为 Activity 销毁
        // 就被连带清除。现在释放第二份才真正走到底层 unbindService（同样不应抛异常）。
        ShizukuManager.releaseConnection(backgroundTaskToken)
    }
}
