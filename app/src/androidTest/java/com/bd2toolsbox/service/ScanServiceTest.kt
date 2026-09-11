package com.bd2toolsbox.service

import android.app.Notification
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ScanService 的通知进度条契约：
 *  - start：常驻通知出现，0/total
 *  - update：进度与文案更新到通知上
 *  - finish：变成可划掉的完成摘要（不再常驻）
 *  - stop：通知消失
 *
 * 测试环境两个必须处理的前提：
 *  1. **app 要在前台**：instrumentation 进程没有前台 Activity 时，startService
 *     会被系统后台限制静默丢弃（isSkip: true，连异常都不抛），STOP 收不到、
 *     通知清不掉。@BeforeClass 先把 MainActivity 拉起来，让进程获得前台状态。
 *     生产环境不受此限：扫描时用户刚在 app 里点过确认，进程必在前台。
 *  2. **通知权限**：API 33+ 没授权时 notify() 静默丢弃，测试会假失败。setup
 *     里显式断言，失败信息指向授权命令而不是让人误判服务坏了。
 *
 * 服务与测试同进程（默认 android:process），NotificationManager 直接可见。
 * 服务动作在主线程异步处理，断言用轮询等通知就位。
 */
@RunWith(AndroidJUnit4::class)
class ScanServiceTest {
    private lateinit var context: android.content.Context

    companion object {
        @JvmStatic
        @BeforeClass
        fun bringAppToForeground() {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            ctx.startActivity(Intent(ctx, com.bd2toolsbox.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            // 等 Activity resume、进程拿到前台状态；不等 Python/角色表初始化 ——
            // 那些不影响服务调用的进程状态
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
        ScanService.stop(context)
        waitFor { activeNotification() == null }
        // 通知没了 ≠ 服务拆完。stopForeground/cancel 先于 onDestroy 生效，
        // 紧接着的 startForegroundService 会撞上销毁中的 ServiceRecord，系统
        // 按没人兑现 startForeground 记违规杀进程（生产流程不存在毫秒级
        // stop→start，这是测试专属的竞态）。等销毁落定再开下一轮。
        Thread.sleep(600)
    }

    @After
    fun tearDown() {
        ScanService.stop(context)
        Thread.sleep(600)
    }

    private fun activeNotification() =
        context.getSystemService(android.app.NotificationManager::class.java)
            ?.activeNotifications
            ?.firstOrNull { it.id == ScanService.NOTIFICATION_ID }

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
        ScanService.start(context, 10)
        waitFor { activeNotification() != null }
        val n = activeNotification()!!.notification
        assertEquals("scan", n.channelId)
        assertTrue("进度中通知必须是常驻的", n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(10, n.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals(0, n.extras.getInt(Notification.EXTRA_PROGRESS))
    }

    @Test
    fun updateMovesProgressAndShowsBundleName() {
        ScanService.start(context, 10)
        waitFor { activeNotification() != null }
        ScanService.update(context, 5, 10, "aabbccdd", "Scanning aabbccdd")
        waitFor { progress() == 5 }
        val text = activeNotification()!!.notification.extras
            .getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        assertTrue("通知文案应含 bundle 名: $text", text.contains("aabbccdd"))
    }

    @Test
    fun finishTurnsNotificationIntoDismissibleSummary() {
        ScanService.start(context, 10)
        waitFor { activeNotification() != null }
        ScanService.finish(context, 8, 2)
        waitFor {
            activeNotification()?.notification
                ?.let { it.flags and Notification.FLAG_ONGOING_EVENT == 0 } == true
        }
        val text = activeNotification()?.notification?.extras
            ?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        assertTrue("完成摘要应含成功/失败数: $text",
            text.contains("8") && text.contains("2"))
    }

    @Test
    fun stopRemovesNotification() {
        ScanService.start(context, 10)
        waitFor { activeNotification() != null }
        ScanService.stop(context)
        waitFor { activeNotification() == null }
        assertNull(activeNotification())
    }

    @Test
    fun startDuringExistingScanKeepsFirstTotals() {
        // 扫描中重复 start（比如误触/重启流程）不该把进度归零
        ScanService.start(context, 10)
        waitFor { activeNotification() != null }
        ScanService.update(context, 4, 10, "bbbb", "Scanning bbbb")
        waitFor { progress() == 4 }
        ScanService.start(context, 99)
        Thread.sleep(300)
        assertEquals("重复 start 不应重置进度", 4, progress())
    }
}
