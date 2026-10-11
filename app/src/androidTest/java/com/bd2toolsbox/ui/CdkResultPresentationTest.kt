package com.bd2toolsbox.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bd2toolsbox.feature.cdk.AccountRedeemResult
import com.bd2toolsbox.feature.cdk.BatchRedeemSummary
import com.bd2toolsbox.feature.cdk.RedeemAccountResultRow
import com.bd2toolsbox.feature.cdk.RedeemSummary
import com.bd2toolsbox.feature.cdk.batchResultText
import com.bd2toolsbox.feature.cdk.toneFor
import com.bd2toolsbox.ui.components.StatusTone
import com.bd2toolsbox.ui.theme.BD2ModManagerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T45：兑换结果的界面语义（设备）。
 *
 * 核心：**「已处理」不等于「已兑换」**——两个账号都终局失败时，
 * 行级标签与整体说明都不能出现成功。
 */
@RunWith(AndroidJUnit4::class)
class CdkResultPresentationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val bothFailed = BatchRedeemSummary(
        listOf(
            AccountRedeemResult("a", "账号A", RedeemSummary(terminalFailureCount = 2)),
            AccountRedeemResult("b", "账号B", RedeemSummary(terminalFailureCount = 1))
        )
    )

    @Test
    fun bothAccountsTerminallyFailedShowFailureNotSuccess() {
        composeRule.setContent {
            BD2ModManagerTheme {
                Column {
                    bothFailed.perAccount.forEach { RedeemAccountResultRow(it) }
                }
            }
        }
        // 两行都是「兑换失败」，没有「已兑换」标签
        composeRule.onNodeWithText("账号A").assertIsDisplayed()
        composeRule.onNodeWithText("账号B").assertIsDisplayed()
        // 两行都是「兑换失败」标签（用 allNodes 因为两行文案相同）
        assertEquals(
            2,
            composeRule.onAllNodesWithText("兑换失败").fetchSemanticsNodes().size
        )
        assertEquals(
            0,
            composeRule.onAllNodesWithText("已兑换").fetchSemanticsNodes().size
        )
        composeRule.onNodeWithContentDescription("兑换结果：账号A：失败 2").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("兑换结果：账号B：失败 1").assertIsDisplayed()
        // 整体说明不得声称成功
        val text = batchResultText(bothFailed)
        assertFalse("整体说明不得出现「成功」", text.contains("成功 1") || text.contains("全部"))
        assertTrue(text.contains("失败"))
    }

    @Test
    fun oneSuccessOneFailureShowsAccurateCountsAndReasons() {
        val mixed = BatchRedeemSummary(
            listOf(
                AccountRedeemResult("a", "账号A", RedeemSummary(successCount = 3)),
                AccountRedeemResult("b", "账号B", RedeemSummary(terminalFailureCount = 1, pendingCount = 1))
            )
        )
        composeRule.setContent {
            BD2ModManagerTheme {
                Column { mixed.perAccount.forEach { RedeemAccountResultRow(it) } }
            }
        }
        composeRule.onNodeWithContentDescription("兑换结果：账号A：成功 3").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("兑换结果：账号B：失败 1，待重试 1").assertIsDisplayed()
        val text = batchResultText(mixed)
        assertTrue(text.contains("成功 3"))
        assertTrue(text.contains("失败 1"))
        assertTrue(text.contains("待重试 1"))
    }

    @Test
    fun toneNeverShowsSuccessForFailureOrUnknown() {
        assertEquals(StatusTone.ERROR, toneFor(RedeemSummary(terminalFailureCount = 2)))
        assertEquals(StatusTone.INFO, toneFor(RedeemSummary(unknownCount = 1)))
        assertEquals(StatusTone.WARNING, toneFor(RedeemSummary(pendingCount = 1)))
        assertEquals(StatusTone.NEUTRAL, toneFor(RedeemSummary()))
        assertEquals(StatusTone.SUCCESS, toneFor(RedeemSummary(alreadyRedeemedCount = 2)))
        assertEquals(
            StatusTone.WARNING,
            toneFor(RedeemSummary(successCount = 1, terminalFailureCount = 1))
        )
    }

    @Test
    fun unknownResultsAreNotPresentedAsRedeemed() {
        val unknown = BatchRedeemSummary(
            listOf(AccountRedeemResult("a", "账号A", RedeemSummary(unknownCount = 1)))
        )
        composeRule.setContent {
            BD2ModManagerTheme { RedeemAccountResultRow(unknown.perAccount.first()) }
        }
        composeRule.onNodeWithText("结果未知").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("兑换结果：账号A：结果未知 1").assertIsDisplayed()
        assertTrue(unknown.canRetry)
        assertFalse(batchResultText(unknown).contains("全部"))
    }

    @Test
    fun emptyBatchIsNotAllRedeemed() {
        val empty = BatchRedeemSummary(emptyList())
        assertFalse(empty.aggregate.allRedeemed)
        assertEquals("没有可兑换的兑换码。", batchResultText(empty))
    }
}
