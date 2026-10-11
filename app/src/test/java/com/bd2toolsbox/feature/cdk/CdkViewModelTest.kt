package com.bd2toolsbox.feature.cdk

import com.bd2toolsbox.data.model.CdkItem
import com.bd2toolsbox.data.model.RedeemOutcome
import com.bd2toolsbox.data.repository.Bd2RedeemRepository
import com.bd2toolsbox.data.repository.GamekeeRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T31：兑换结局/汇总与 ViewModel 提交控制的 JVM 验证。 */
class CdkViewModelTest {

    @After fun teardown() = unmockkAll()

    private fun outcome(
        success: Boolean = false,
        retryable: Boolean = true,
        errorCode: String? = null
    ) = RedeemOutcome("CODE", success, "msg", retryable = retryable, errorCode = errorCode)

    // ---------------------------------------------------------------- 结局映射

    @Test fun dispositionMapsEveryCase() {
        assertEquals(RedeemDisposition.SUCCESS, dispositionOf(outcome(success = true)))
        assertEquals(
            RedeemDisposition.ALREADY_REDEEMED,
            dispositionOf(outcome(retryable = false, errorCode = "AlreadyUsed"))
        )
        assertEquals(
            RedeemDisposition.TERMINAL_FAILURE,
            dispositionOf(outcome(retryable = false, errorCode = "InvalidCode"))
        )
        assertEquals(
            RedeemDisposition.RETRYABLE_FAILURE,
            dispositionOf(outcome(retryable = true, errorCode = "BadRequest"))
        )
        assertEquals(
            RedeemDisposition.UNKNOWN,
            dispositionOf(outcome(retryable = true, errorCode = "UnknownResult"))
        )
    }

    // ---------------------------------------------------------------- 汇总

    @Test fun emptyCollectionIsNeitherSettledNorAllRedeemed() {
        val s = summarizeRedemption(emptyList())
        assertEquals(0, s.total)
        assertFalse("空集合不能显示全部兑换成功", s.allRedeemed)
        assertFalse(s.settled)
    }

    @Test fun allTerminalFailuresAreSettledButZeroSuccess() {
        val s = summarizeRedemption(
            listOf(RedeemDisposition.TERMINAL_FAILURE, RedeemDisposition.TERMINAL_FAILURE)
        )
        assertTrue("全是终局失败可以标为已处理", s.settled)
        assertEquals(0, s.successCount)
        assertFalse(s.allRedeemed)
    }

    @Test fun mixedResultsSummarizeCorrectly() {
        val s = summarizeRedemption(
            listOf(
                RedeemDisposition.SUCCESS,
                RedeemDisposition.ALREADY_REDEEMED,
                RedeemDisposition.TERMINAL_FAILURE,
                RedeemDisposition.RETRYABLE_FAILURE,
                RedeemDisposition.NOT_ATTEMPTED,
                RedeemDisposition.UNKNOWN
            )
        )
        assertEquals(1, s.successCount)
        assertEquals(1, s.alreadyRedeemedCount)
        assertEquals(1, s.terminalFailureCount)
        assertEquals(2, s.pendingCount)
        assertEquals(1, s.unknownCount)
        assertEquals(6, s.total)
        assertFalse(s.settled)
        assertFalse(s.allRedeemed)
    }

    @Test fun successPlusAlreadyRedeemedIsAllRedeemed() {
        val s = summarizeRedemption(
            listOf(RedeemDisposition.SUCCESS, RedeemDisposition.ALREADY_REDEEMED)
        )
        assertTrue(s.allRedeemed)
        assertTrue(s.settled)
    }

    @Test fun unknownOrPendingBlocksSettled() {
        assertFalse(summarizeRedemption(listOf(RedeemDisposition.SUCCESS, RedeemDisposition.UNKNOWN)).settled)
        assertFalse(summarizeRedemption(listOf(RedeemDisposition.SUCCESS, RedeemDisposition.RETRYABLE_FAILURE)).settled)
    }

    // ---------------------------------------------------------------- ViewModel

    private fun item(code: String) = CdkItem(code, "reward", 0L, expired = false)

    private fun gamekee(): GamekeeRepository = mockk(relaxed = true)

    private fun repo(): Bd2RedeemRepository {
        val repo = mockk<Bd2RedeemRepository>(relaxed = true)
        every { repo.savedUserIds() } returns listOf("user")
        every { repo.loadHistory() } returns emptyList()
        every { repo.autoDoneCodes(any()) } returns emptySet()
        every { repo.historyCodes(any()) } returns emptySet()
        return repo
    }

    @Test fun redeemAllPublishesSummaryFromOutcomes() = runTest {
        val repo = repo()
        coEvery { repo.autoRedeem(any()) } returns listOf(
            "user" to outcome(success = true),
            "user" to outcome(retryable = false, errorCode = "InvalidCode")
        )
        val vm = CdkViewModel(repo, gamekee())
        val summary = vm.redeemAll(listOf(item("A"), item("B")))
        assertEquals(1, summary.successCount)
        assertEquals(1, summary.terminalFailureCount)
        assertEquals(summary, vm.ui.value.lastSummary)
        assertFalse(vm.ui.value.batchRedeeming)
    }

    @Test fun concurrentSubmitsDoNotInterleave() = runTest {
        val repo = repo()
        val gate = CompletableDeferred<Unit>()
        var active = 0
        var maxActive = 0
        coEvery { repo.autoRedeem(any()) } coAnswers {
            active++
            if (active > maxActive) maxActive = active
            gate.await()
            active--
            emptyList()
        }
        val vm = CdkViewModel(repo, gamekee())
        val first = async { vm.redeemAll(listOf(item("A"))) }
        val second = async { vm.redeemAll(listOf(item("B"))) }
        // 让两个协程都到达互斥锁
        kotlinx.coroutines.yield()
        gate.complete(Unit)
        first.await()
        second.await()
        assertEquals("同一时刻只能有一次提交在跑", 1, maxActive)
        coVerify(exactly = 2) { repo.autoRedeem(any()) }
    }

    @Test fun repositoryFailureIsSurfacedNotSwallowed() = runTest {
        val repo = repo()
        coEvery { repo.autoRedeem(any()) } throws RuntimeException("network down")
        val vm = CdkViewModel(repo, gamekee())
        try {
            vm.redeemAll(listOf(item("A")))
            org.junit.Assert.fail("异常必须上抛")
        } catch (_: RuntimeException) {
            assertFalse(vm.ui.value.batchRedeeming)
            assertTrue(vm.ui.value.error?.contains("network down") == true)
        }
    }
    // ------------------------------------------------- T45：账号维度结果

    @Test fun perAccountBreakdownIsPublishedFromOutcomes() = runTest {
        val repo = repo()
        coEvery { repo.autoRedeem(any()) } returns listOf(
            "alice" to outcome(retryable = false, errorCode = "InvalidCode"),
            "bob" to outcome(retryable = false, errorCode = "InvalidCode")
        )
        val vm = CdkViewModel(repo, gamekee())
        vm.redeemAll(listOf(item("A")))
        val batch = vm.ui.value.lastBatch
        assertNotNull("必须留下账号维度结果", batch)
        assertEquals(2, batch!!.perAccount.size)
        // 两个账号都终局失败：没有任何成功行级标签
        batch.perAccount.forEach { row ->
            assertEquals("兑换失败", row.label())
            assertFalse(row.describe().contains("成功"))
        }
        assertEquals("兑换失败", batch.aggregate.resultLabel())
        assertEquals(0, batch.aggregate.successCount)
        assertFalse("全终局失败没有可重试内容", batch.canRetry)
        assertFalse(vm.ui.value.canRetry)
    }

    @Test fun perAccountBreakdownKeepsAccurateCountsForMixedResult() = runTest {
        val repo = repo()
        coEvery { repo.autoRedeem(any()) } returns listOf(
            "alice" to outcome(success = true),
            "bob" to outcome(retryable = true, errorCode = "Timeout")
        )
        val vm = CdkViewModel(repo, gamekee())
        vm.redeemAll(listOf(item("A")))
        val batch = requireNotNull(vm.ui.value.lastBatch)
        val byId = batch.perAccount.associateBy { it.accountId }
        assertEquals("成功 1", byId.getValue("alice").describe().substringAfter("："))
        assertEquals("待重试 1", byId.getValue("bob").describe().substringAfter("："))
        assertEquals(1, batch.aggregate.successCount)
        assertEquals(1, batch.aggregate.pendingCount)
        assertTrue("还有可重试项，入口允许重试", batch.canRetry)
        assertEquals("部分成功", batch.aggregate.resultLabel())
    }

    @Test fun unknownResultsKeepTheEntryRetryable() = runTest {
        val repo = repo()
        coEvery { repo.autoRedeem(any()) } returns listOf(
            "alice" to outcome(retryable = true, errorCode = "UnknownResult")
        )
        val vm = CdkViewModel(repo, gamekee())
        vm.redeemAll(listOf(item("A")))
        val batch = requireNotNull(vm.ui.value.lastBatch)
        assertEquals("结果未知", batch.perAccount.single().label())
        assertTrue(batch.canRetry)
        assertFalse("未知不得被当成已兑换", batch.aggregate.allRedeemed)
    }
}
