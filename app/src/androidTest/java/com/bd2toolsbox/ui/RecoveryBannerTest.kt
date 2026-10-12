package com.bd2toolsbox.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bd2toolsbox.core.operations.RecoveryDisposition
import com.bd2toolsbox.ui.components.RecoveryBanner
import com.bd2toolsbox.ui.components.RecoveryPresentation
import com.bd2toolsbox.ui.theme.BD2ModManagerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T25：启动恢复处置的用户可见结论（设备）。
 *
 * 守的几件事：
 * - 四态不被压成两态：中断 / 待核对 / 未知各自说清下一步；
 * - `Committed`（事实完整、只是 journal 没清）**不打扰用户**；
 * - 空列表不出横幅；
 * - 正文含可执行的下一步（「核对并修复记录」/「不要重新安装」）。
 */
@RunWith(AndroidJUnit4::class)
class RecoveryBannerTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val interrupted = RecoveryDisposition.Interrupted(
        operationId = "op-1", bundleNames = listOf("b1", "b2"), modUris = listOf("u1")
    )
    private val needsReconcile = RecoveryDisposition.NeedsReconciliation(
        operationId = "op-2", bundleNames = listOf("b3"), modUris = listOf("u2")
    )
    private val unknown = RecoveryDisposition.Unknown("journal 损坏")
    private val committed = RecoveryDisposition.Committed("op-3", listOf("b4"))

    @Test
    fun committedDispositionsDoNotDisturbTheUser() {
        assertFalse(RecoveryPresentation.shouldShow(listOf(committed)))
        assertFalse(RecoveryPresentation.shouldShow(emptyList()))
        assertEquals(emptyList<RecoveryDisposition>(), RecoveryPresentation.actionable(listOf(committed)))
        composeRule.setContent {
            BD2ModManagerTheme { RecoveryBanner(dispositions = listOf(committed), onDismiss = {}) }
        }
        assertEquals(
            0,
            composeRule.onAllNodesWithContentDescription("启动恢复：有装入没核对完")
                .fetchSemanticsNodes().size
        )
    }

    @Test
    fun needsReconciliationTellsTheUserNotToReinstall() {
        composeRule.setContent {
            BD2ModManagerTheme { RecoveryBanner(dispositions = listOf(needsReconcile), onDismiss = {}) }
        }
        composeRule.onNodeWithText("有装入没核对完").assertIsDisplayed()
        composeRule.onNodeWithText("不要重新安装", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("核对并修复记录", substring = true).assertIsDisplayed()
    }

    @Test
    fun interruptedSaysTheFilesMayBeHalfReplaced() {
        composeRule.setContent {
            BD2ModManagerTheme { RecoveryBanner(dispositions = listOf(interrupted), onDismiss = {}) }
        }
        composeRule.onNodeWithText("上次操作被打断").assertIsDisplayed()
        composeRule.onNodeWithText("换了一半", substring = true).assertIsDisplayed()
    }

    @Test
    fun unknownIsSurfacedAndSaysNothingAutomaticHappened() {
        composeRule.setContent {
            BD2ModManagerTheme { RecoveryBanner(dispositions = listOf(unknown), onDismiss = {}) }
        }
        composeRule.onNodeWithText("装入记录读不出来").assertIsDisplayed()
        composeRule.onNodeWithText("未做任何自动动作", substring = true).assertIsDisplayed()
        assertTrue(RecoveryPresentation.shouldShow(listOf(unknown)))
    }

    @Test
    fun titlePrefersTheMostSevereShape() {
        assertEquals("有装入没核对完", RecoveryPresentation.title(listOf(interrupted, needsReconcile)))
        assertEquals("上次操作被打断", RecoveryPresentation.title(listOf(interrupted, unknown)))
        assertEquals("装入记录读不出来", RecoveryPresentation.title(listOf(unknown)))
    }

    @Test
    fun dismissHidesTheBanner() {
        var dismissed = 0
        composeRule.setContent {
            BD2ModManagerTheme {
                RecoveryBanner(dispositions = listOf(needsReconcile), onDismiss = { dismissed++ })
            }
        }
        composeRule.onNodeWithText("知道了").assertIsDisplayed().performClick()
        assertEquals(1, dismissed)
    }

    // ---- T25 补：正文指路 → 界面必须有入口 ----

    @Test
    fun reconcileEntryIsOfferedForFixableDispositions() {
        assertTrue(
            "中断与待核对必须能一键核对",
            RecoveryPresentation.canReconcile(
                listOf(RecoveryDisposition.Interrupted("op", listOf("b"), emptyList()))
            )
        )
        assertTrue(
            RecoveryPresentation.canReconcile(
                listOf(RecoveryDisposition.NeedsReconciliation("op", listOf("b"), emptyList()))
            )
        )
    }

    @Test
    fun reconcileEntryIsNotOfferedForUnreadableRecords() {
        // 记录读不出来时重跑核对只会再失败一次 —— 该做的是查存储权限。
        // 给一个按下去必然失败的按钮比不给按钮更糟。
        assertFalse(
            RecoveryPresentation.canReconcile(
                listOf(RecoveryDisposition.Unknown("journal 解析失败"))
            )
        )
        assertFalse("已提交不打扰用户", RecoveryPresentation.canReconcile(emptyList()))
    }

    @Test
    fun mixedDispositionsStillOfferReconcile() {
        assertTrue(
            "只要有一条能修，就该给入口",
            RecoveryPresentation.canReconcile(
                listOf(
                    RecoveryDisposition.Unknown("读不出来"),
                    RecoveryDisposition.NeedsReconciliation("op2", listOf("b"), emptyList())
                )
            )
        )
    }
}
