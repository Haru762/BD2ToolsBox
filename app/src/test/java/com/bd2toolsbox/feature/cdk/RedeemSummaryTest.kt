package com.bd2toolsbox.feature.cdk

import com.bd2toolsbox.data.model.RedeemOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T45：兑换结果语义。
 *
 * 核心回归：**「已处理」不等于「已兑换」**，两个账号都终局失败时绝不出现成功标签。
 */
class RedeemSummaryTest {

    private fun fail(retryable: Boolean = false) =
        RedeemOutcome("CODE", success = false, "msg", retryable = retryable, errorCode = "Err")

    private fun ok() = RedeemOutcome("CODE", success = true, "ok")

    @Test
    fun allTerminalFailuresNeverProduceSuccessLabel() {
        val summary = summarizeRedemption(
            listOf(RedeemDisposition.TERMINAL_FAILURE, RedeemDisposition.TERMINAL_FAILURE)
        )
        assertTrue("已处理完", summary.settled)
        assertEquals("绝不能有成功计数", 0, summary.successCount)
        assertFalse(summary.allRedeemed)
        assertEquals("兑换失败", summary.resultLabel())
        assertFalse("全终局失败没有可重试内容", summary.canRetry)
    }

    @Test
    fun settledAllIsNotASuccessLabel() {
        // 回归 T31/T45：settled=true 但 successCount=0 时，标签不能是成功
        val summary = RedeemSummary(terminalFailureCount = 3)
        assertTrue(summary.settled)
        assertFalse(summary.allRedeemed)
        assertEquals("兑换失败", summary.resultLabel())
    }

    @Test
    fun oneSuccessOneFailureShowsPartialWithCounts() {
        val batch = BatchRedeemSummary(
            listOf(
                AccountRedeemResult("a", "账号A", RedeemSummary(successCount = 1)),
                AccountRedeemResult("b", "账号B", RedeemSummary(terminalFailureCount = 1))
            )
        )
        assertEquals("部分成功", batch.aggregate.resultLabel())
        assertEquals(1, batch.aggregate.successCount)
        assertEquals(1, batch.aggregate.terminalFailureCount)
        assertEquals("账号A：成功 1", batch.perAccount[0].describe())
        assertEquals("账号B：失败 1", batch.perAccount[1].describe())
    }

    @Test
    fun bothAccountsTerminallyFailedHaveNoSuccessRowLabel() {
        val batch = BatchRedeemSummary(
            listOf(
                AccountRedeemResult("a", "账号A", RedeemSummary(terminalFailureCount = 1)),
                AccountRedeemResult("b", "账号B", RedeemSummary(terminalFailureCount = 1))
            )
        )
        batch.perAccount.forEach { row ->
            assertEquals("兑换失败", row.label())
            assertFalse(row.describe().contains("成功"))
        }
        assertEquals("兑换失败", batch.aggregate.resultLabel())
        assertFalse(batch.canRetry)
    }

    @Test
    fun retryEligibilityComesFromResultNatureNotButtonFear() {
        val retryable = BatchRedeemSummary(
            listOf(AccountRedeemResult("a", "A", RedeemSummary(pendingCount = 2)))
        )
        assertTrue(retryable.canRetry)
        assertEquals(1, retryable.retryableAccounts.size)

        val unknown = BatchRedeemSummary(
            listOf(AccountRedeemResult("a", "A", RedeemSummary(unknownCount = 1)))
        )
        assertTrue("结果未知必须允许重试", unknown.canRetry)

        val allDone = BatchRedeemSummary(
            listOf(AccountRedeemResult("a", "A", RedeemSummary(successCount = 5)))
        )
        assertFalse(allDone.canRetry)
        assertEquals("已兑换", allDone.aggregate.resultLabel())
    }

    @Test
    fun emptyBatchIsNotSuccess() {
        val empty = RedeemSummary()
        assertFalse(empty.settled)
        assertFalse(empty.allRedeemed)
        assertEquals("未处理", empty.resultLabel())
    }

    @Test
    fun unknownIsNotPermanentlyTreatedAsRedeemed() {
        val summary = summarizeRedemption(listOf(RedeemDisposition.UNKNOWN))
        assertFalse("结果未知不算已兑换", summary.allRedeemed)
        assertFalse("结果未知不算落定", summary.settled)
        assertEquals("结果未知", summary.resultLabel())
        assertTrue(summary.canRetry)
    }

    @Test
    fun alreadyRedeemedCountsAsRedeemedButNotSuccess() {
        val summary = summarizeRedemption(
            listOf(RedeemDisposition.ALREADY_REDEEMED, RedeemDisposition.ALREADY_REDEEMED)
        )
        assertTrue(summary.allRedeemed)
        assertEquals(0, summary.successCount)
        assertEquals(2, summary.alreadyRedeemedCount)
        assertEquals("已兑换", summary.resultLabel())
    }

    @Test
    fun dispositionMappingDistinguishesCases() {
        assertEquals(
            RedeemDisposition.SUCCESS,
            dispositionOf(ok())
        )
        assertEquals(
            RedeemDisposition.ALREADY_REDEEMED,
            dispositionOf(
                RedeemOutcome("CODE", success = false, "msg", retryable = false, errorCode = "AlreadyUsed")
            )
        )
        assertEquals(
            RedeemDisposition.UNKNOWN,
            dispositionOf(
                RedeemOutcome("CODE", success = false, "msg", retryable = true, errorCode = "UnknownResult")
            )
        )
        assertEquals(RedeemDisposition.TERMINAL_FAILURE, dispositionOf(fail(retryable = false)))
        assertEquals(RedeemDisposition.RETRYABLE_FAILURE, dispositionOf(fail(retryable = true)))
    }

    @Test
    fun addingAnAccountRecomputesPending() {
        val before = BatchRedeemSummary(
            listOf(AccountRedeemResult("a", "A", RedeemSummary(successCount = 1)))
        )
        assertEquals(0, before.aggregate.pendingCount)
        val after = BatchRedeemSummary(
            before.perAccount + AccountRedeemResult("b", "B", RedeemSummary(pendingCount = 3))
        )
        assertEquals("新增账号必须重新计入待处理", 3, after.aggregate.pendingCount)
        assertTrue(after.canRetry)
    }
}
