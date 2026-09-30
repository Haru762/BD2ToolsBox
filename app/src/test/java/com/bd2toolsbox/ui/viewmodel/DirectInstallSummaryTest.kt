package com.bd2toolsbox.ui.viewmodel

import com.bd2toolsbox.data.model.MoveState
import org.junit.Assert.*
import org.junit.Test

class DirectInstallSummaryTest {
    @Test fun allCommittedIsSuccess() {
        val s = directInstallOutcome(3, 0, 0)
        assertTrue(s is MoveState.Success)
        assertTrue((s as MoveState.Success).message.contains("已装入 3 个"))
    }

    /** Partial result must say the committed ones are live, not read as "whole batch failed". */
    @Test fun partialResultStatesWhatIsAlreadyInEffect() {
        val s = directInstallOutcome(2, 1, 0) as MoveState.Failed
        assertTrue(s.error, s.error.startsWith("已装入 2 个"))
        assertTrue(s.error, s.error.contains("1 个未装入"))
        assertFalse(s.error, s.error.contains("核对"))
    }

    @Test fun nothingCommittedDoesNotClaimAnyInstall() {
        val s = directInstallOutcome(0, 2, 0) as MoveState.Failed
        assertFalse(s.error, s.error.contains("已装入"))
        assertTrue(s.error, s.error.contains("2 个未装入"))
    }

    /** Reconciliation cases are not ordinary retryable failures and must be called out. */
    @Test fun reconciliationIsCalledOutSeparately() {
        val s = directInstallOutcome(1, 2, 1) as MoveState.Failed
        assertTrue(s.error, s.error.contains("其中 1 个可能已改动游戏文件"))
        assertTrue(s.error, s.error.contains("不要直接重试"))
    }

    /** The headline no longer embeds the first failure's raw error; details stay in the list. */
    @Test fun headlineDoesNotEmbedPerTargetErrors() {
        val s = directInstallOutcome(1, 1, 0) as MoveState.Failed
        assertFalse(s.error.contains("完整提交"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun reconciliationCannotExceedFailures() { directInstallOutcome(1, 1, 2) }
}
