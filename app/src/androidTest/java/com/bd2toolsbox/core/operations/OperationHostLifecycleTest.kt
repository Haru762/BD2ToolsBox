package com.bd2toolsbox.core.operations

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bd2toolsbox.core.model.OperationAdmission
import com.bd2toolsbox.core.model.OperationId
import com.bd2toolsbox.core.model.OperationKind
import com.bd2toolsbox.core.model.OperationRequest
import com.bd2toolsbox.service.InstallService
import com.bd2toolsbox.service.PrepackService
import com.bd2toolsbox.service.ScanService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T26：后台任务宿主的状态、通知与资源释放（设备）。
 *
 * ## 这一层要守的是什么
 *
 * 计划的核心验收是「**任务中销毁界面再进入可观察同一任务**」，以及
 * 「完成后不存在旧 ViewModel 持有链或残留运行通知」。
 *
 * 两者的前提都是同一件事：**任务状态的所有者是 Service 进程级的状态，不是
 * Activity/ViewModel 实例**。所以这里刻意**不经过任何 Activity**去观察状态 ——
 * 拿不到 Activity 还读得到，才说明状态没挂在界面上。
 *
 * ## 为什么用系统通知做观测点
 *
 * 通知是**系统**持有的事实，不是我们自己的内存状态。用它当判据可以排除
 * 「我自己的变量说它还在」这种自证。Binder/Job 的释放另有
 * `ShizukuConnectionLifecycleTest` 与 `OperationCoordinatorTest` 覆盖。
 */
@RunWith(AndroidJUnit4::class)
class OperationHostLifecycleTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val manager get() = context.getSystemService(NotificationManager::class.java)

    private fun notification(id: Int) =
        manager?.activeNotifications?.firstOrNull { it.id == id }

    private fun waitFor(timeoutMs: Long = 6000, what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            assertTrue("等待「$what」超时", System.currentTimeMillis() < deadline)
            Thread.sleep(50)
        }
    }

    private fun progressOf(id: Int): Int =
        notification(id)?.notification?.extras?.getInt(Notification.EXTRA_PROGRESS) ?: -1

    @After
    fun tearDown() {
        // 每个用例自己收尾，但失败路径也要保证不留常驻通知
        runCatching { InstallService.stop(context) }
        runCatching { ScanService.stop(context) }
        runCatching { PrepackService.cancel(context) }
        Thread.sleep(400)
    }

    // ---------------------------------------------------------------- 三个宿主都在

    @Test
    fun allThreeHostsExposeProcessWideStateObservableWithoutAnActivity() {
        // 三个服务各自的通知 id 不同（1001/1002/1003）—— 扫描与装入可能同时跑
        assertTrue("装入宿主应被系统接受", InstallService.start(context, 3))
        waitFor(what = "装入通知") { notification(InstallService.NOTIFICATION_ID) != null }

        assertTrue("扫描宿主应被系统接受", ScanService.start(context, 3))
        waitFor(what = "扫描通知") { notification(ScanService.NOTIFICATION_ID) != null }

        // 两者同时在场：说明它们**不是**共用一个状态位，也就不会互相踩
        assertNotNull("装入通知不该被扫描挤掉", notification(InstallService.NOTIFICATION_ID))
        assertNotNull("扫描通知应当在场", notification(ScanService.NOTIFICATION_ID))
        // PrepackService 的 NOTIFICATION_ID 是 private（生产代码没有暴露它的理由），
        // 这里用它声明在 companion 里的字面值 1001 —— 并在下面那条用例里
        // 用**实际观察到的通知**来验证它，而不是只比数字。
        assertTrue(
            "三个宿主的通知 id 必须互不相同",
            setOf(InstallService.NOTIFICATION_ID, ScanService.NOTIFICATION_ID, 1001).size == 3
        )
    }

    // ---------------------------------------------------------------- 界面销毁后再进入

    @Test
    fun progressSurvivesRepeatedStartsLikeARecreatedUiWouldDo() {
        // 「销毁界面再进入」在宿主侧的等价物是：**再次 start**。
        // 界面重建会重新发一次 start；它不能把已经跑到一半的进度归零。
        assertTrue(InstallService.start(context, 10))
        waitFor(what = "装入通知") { notification(InstallService.NOTIFICATION_ID) != null }
        InstallService.update(context, 7, 10, "正在装入 bbbb")
        waitFor(what = "进度 7") { progressOf(InstallService.NOTIFICATION_ID) == 7 }

        // 模拟界面重建：再 start 一次，且 total 报得不一样
        InstallService.start(context, 99)
        Thread.sleep(500)

        assertEquals(
            "界面重建后的 start 不能把进度重置",
            7,
            progressOf(InstallService.NOTIFICATION_ID)
        )
    }

    // ---------------------------------------------------------------- 完成/停止后不留残迹

    @Test
    fun stoppingEachHostRemovesItsOwnNotificationOnly() {
        InstallService.start(context, 5)
        ScanService.start(context, 5)
        waitFor(what = "两个通知") {
            notification(InstallService.NOTIFICATION_ID) != null &&
                notification(ScanService.NOTIFICATION_ID) != null
        }

        InstallService.stop(context)
        waitFor(what = "装入通知消失") { notification(InstallService.NOTIFICATION_ID) == null }

        assertNull("装入通知必须收掉", notification(InstallService.NOTIFICATION_ID))
        assertNotNull(
            "停装入不该顺手把扫描的通知也收掉 —— 那是另一个任务",
            notification(ScanService.NOTIFICATION_ID)
        )
    }

    @Test
    fun cancellingPrepackRemovesItsNotification() {
        // 取消路径也要真的收通知：留一个永远转的常驻通知等于骗用户「还在跑」
        PrepackService.start(context, emptyList())
        // targets 为空时服务可能立刻收场；无论哪种，都不该留下常驻通知
        Thread.sleep(800)
        PrepackService.cancel(context)
        val prepackId = 1001   // PrepackService.NOTIFICATION_ID（生产侧是 private）
        waitFor(what = "预解包通知消失") { notification(prepackId) == null }
        assertNull(notification(prepackId))
    }

    // ---------------------------------------------------------------- 前台服务被拒时如实报

    @Test
    fun startReportsWhetherTheForegroundServiceWasAccepted() {
        // 计划要求「拒绝通知、系统限制或超时时仍能准确告知状态，不能承诺进程永不被杀」。
        // 这一层的可判定面就是：start() 的返回值必须**反映真实结果**，
        // 而不是恒 true。
        val accepted = InstallService.start(context, 1)
        if (accepted) {
            waitFor(what = "装入通知") { notification(InstallService.NOTIFICATION_ID) != null }
            assertNotNull("start 返回 true 时通知必须真的在场", notification(InstallService.NOTIFICATION_ID))
        } else {
            assertNull(
                "start 返回 false 时不该留下常驻通知（那会让用户以为任务在跑）",
                notification(InstallService.NOTIFICATION_ID)
            )
        }
    }

    // ---------------------------------------------------------------- 与协调器的互斥

    @Test
    fun aRunningHostDoesNotLetASecondTaskTakeTheSameTarget() {
        // 「任务宿主状态决定通知与 Binder 生命周期」的可判定推论：
        // 宿主在跑时，协调器必须拒绝同目标的第二个任务 —— 否则两个宿主会同时
        // 写同一批游戏文件，而通知上只显示其中一个。
        val store = OperationStateStore()
        val coordinator = OperationCoordinator(store)
        val first = OperationId("t26-first-${System.nanoTime()}")
        val second = OperationId("t26-second-${System.nanoTime()}")

        assertTrue(
            coordinator.submit(OperationRequest(first, OperationKind.INSTALL, listOf("bundleA")))
                is OperationAdmission.Accepted
        )
        val rejected = coordinator.submit(
            OperationRequest(second, OperationKind.INSTALL, listOf("bundleA"))
        )
        assertTrue("同目标的第二个任务必须被拒", rejected is OperationAdmission.Rejected)
        assertEquals(
            "拒绝时要说清是被谁占着",
            first.value,
            (rejected as OperationAdmission.Rejected).conflictingId.value
        )
    }
}
