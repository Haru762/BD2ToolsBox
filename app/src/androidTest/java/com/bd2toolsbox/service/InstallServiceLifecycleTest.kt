package com.bd2toolsbox.service

import android.app.Notification
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * InstallService 的通知进度条契约（T22）：
 *  - start：常驻通知出现，0/total，频道 install
 *  - update：进度与文案更新到通知上
 *  - stop：通知消失
 *  - 重复 start 不把进度归零
 *
 * 与 [ScanServiceTest] 同样的两个前提：
 *  1. **app 要在前台**：instrumentation 进程没有前台 Activity 时 startService 会被
 *     后台限制静默丢弃。@BeforeClass 先拉起 MainActivity。
 *  2. **通知权限**：API 33+ 没授权时 notify() 静默丢弃。
 *
 * 本类只验证服务宿主的通知/生命周期契约，不驱动真实的转换/装入执行器 ——
 * 执行器侧的提交协议由 JVM 的 `InstallExecutorTest` / `UninstallExecutorTest`
 * 用真文件覆盖。Service 真正驱动 executor 的接线属于后续增量。
 */
@RunWith(AndroidJUnit4::class)
class InstallServiceLifecycleTest {
    private lateinit var context: android.content.Context

    companion object {
        @JvmStatic
        @BeforeClass
        fun bringAppToForeground() {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            ctx.startActivity(
                Intent(ctx, com.bd2toolsbox.MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Thread.sleep(3000)
        }
    }

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            assertTrue(
                "缺少通知权限，notify() 会静默丢弃。先执行：\n" +
                    "adb shell pm grant com.bd2toolsbox android.permission.POST_NOTIFICATIONS",
                granted)
        }
        InstallService.stop(context)
        waitFor { activeNotification() == null }
        // 通知没了 ≠ 服务拆完。等销毁落定，避免 stop→start 撞上销毁中的 ServiceRecord。
        Thread.sleep(600)
    }

    @After
    fun tearDown() {
        InstallService.stop(context)
        Thread.sleep(600)
    }

    private fun activeNotification() =
        context.getSystemService(android.app.NotificationManager::class.java)
            ?.activeNotifications
            ?.firstOrNull { it.id == InstallService.NOTIFICATION_ID }

    private fun waitFor(timeoutMs: Long = 5000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            assertTrue("等待通知更新超时", System.currentTimeMillis() < deadline)
            Thread.sleep(50)
        }
    }

    private fun progress() =
        activeNotification()?.notification?.extras?.getInt(Notification.EXTRA_PROGRESS) ?: -1

    @Test
    fun startShowsOngoingNotificationWithZeroProgress() {
        assertTrue("前台服务应被系统接受", InstallService.start(context, 10))
        waitFor { activeNotification() != null }
        val n = activeNotification()!!.notification
        assertEquals(InstallService.CHANNEL_ID, n.channelId)
        assertTrue("进度中通知必须是常驻的", n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(10, n.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals(0, n.extras.getInt(Notification.EXTRA_PROGRESS))
    }

    @Test
    fun updateMovesProgressAndShowsText() {
        InstallService.start(context, 10)
        waitFor { activeNotification() != null }
        InstallService.update(context, 5, 10, "正在装入 selected")
        waitFor { progress() == 5 }
        val text = activeNotification()!!.notification.extras
            .getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        assertTrue("通知文案应含文本: $text", text.contains("selected"))
    }

    @Test
    fun stopRemovesNotification() {
        InstallService.start(context, 10)
        waitFor { activeNotification() != null }
        InstallService.stop(context)
        waitFor { activeNotification() == null }
        assertNull(activeNotification())
    }

    @Test
    fun startDuringExistingInstallKeepsFirstTotals() {
        // 装入中重复 start（误触/界面重建）不该把进度归零
        InstallService.start(context, 10)
        waitFor { activeNotification() != null }
        InstallService.update(context, 4, 10, "bbbb")
        waitFor { progress() == 4 }
        InstallService.start(context, 99)
        Thread.sleep(300)
        assertEquals("重复 start 不应重置进度", 4, progress())
    }
}
