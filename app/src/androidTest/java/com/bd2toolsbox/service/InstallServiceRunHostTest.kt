package com.bd2toolsbox.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * T22：**提交执行真的在服务里跑**（设备）。
 *
 * 断言的是「谁来执行、结果从哪出来」，不是「装入成功」—— 真装需要游戏目录与
 * Shizuku 授权，那条链在 T19/T56 的范围里。这里用**空目标集**让提交快速落定，
 * 然后验证：
 *  1. `InstallService.run` 接受请求并启动前台服务；
 *  2. 服务在自己的 scope 上执行，并把**真实结果**（submission 或 failure，二者必有其一）
 *     发布到 `runResult`；
 *  3. 绝不出现「既没有结果、也没有失败原因」的谎报。
 *
 * 这也是「提交不再挂在 viewModelScope 上」的可观察证据：整个过程里没有任何
 * ViewModel 参与。
 */
@RunWith(AndroidJUnit4::class)
class InstallServiceRunHostTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun serviceOwnsTheCommitAndPublishesARealResult() {
        InstallService.clearRunResult()
        val request = com.bd2toolsbox.feature.install.MoveCommitRequest(
            id = "t22-" + UUID.randomUUID(),
            sharedPath = File(context.cacheDir, "t22-shared").apply { mkdirs() }.absolutePath,
            stagingPath = File(context.cacheDir, "t22-staging").absolutePath,
            targets = emptyList(),
            removals = emptySet()
        )

        assertTrue("前台服务必须能接受这次提交", InstallService.run(context, request))

        val result = runBlocking {
            withTimeoutOrNull(30_000) {
                InstallService.runResult.first { it?.id == request.id }
            }
        }
        assertNotNull("服务必须发布本次提交的真实结果（id=${request.id}）", result)
        assertTrue(
            "结果二选一：要么有真实 submission，要么有失败原因 —— 不许两者都空",
            result!!.submission != null || result.failure != null
        )
        // 不允许把「没做任何事」包装成成功
        if (result.submission == null) {
            assertNotNull("失败必须带原因，不能只说「未完成」", result.failure)
        }

        InstallService.stop(context)
    }

}
