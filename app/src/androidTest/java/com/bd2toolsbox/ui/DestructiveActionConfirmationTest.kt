package com.bd2toolsbox.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bd2toolsbox.core.model.BackupPurge
import com.bd2toolsbox.core.model.PurgeRevalidation
import com.bd2toolsbox.ui.components.AppDialog
import com.bd2toolsbox.ui.dialogs.BackupManageDialog
import com.bd2toolsbox.ui.theme.BD2ModManagerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T44：危险操作确认与真实结果反馈（设备）。
 *
 * 守的几件事：
 * - 确认框写明**数量、空间、后果**，按钮写「清空原版备份」而不是泛化的「确定」；
 * - **取消不产生任何文件操作**；
 * - 确认后目标集合变了 → **重新确认**，不删未确认的新备份；
 * - 结果只报真实：失败/部分失败如实显示，剩余量重新统计，不置 0；
 * - 活跃任务在用时不允许清空。
 */
@RunWith(AndroidJUnit4::class)
class DestructiveActionConfirmationTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun consequenceStatesCountSpaceAndLossOfLocalRecovery() {
        val text = BackupPurge.consequence(count = 12, humanBytes = "3.4 GB")
        assertTrue(text.contains("12"))
        assertTrue(text.contains("3.4 GB"))
        assertTrue("必须说明失去本地恢复资源", text.contains("本地原版恢复资源"))
        assertTrue("必须说明不可撤销", text.contains("不可撤销"))
    }

    @Test
    fun cancelProducesNoFileOperation() {
        var purged = false
        var dismissed = false
        composeRule.setContent {
            BD2ModManagerTheme {
                AppDialog(
                    title = BackupPurge.DIALOG_TITLE,
                    message = BackupPurge.consequence(3, "512 MB"),
                    confirmLabel = BackupPurge.CONFIRM_LABEL,
                    onConfirm = { purged = true },
                    dismissLabel = BackupPurge.CANCEL_LABEL,
                    onDismiss = { dismissed = true }
                )
            }
        }
        composeRule.onNodeWithText(BackupPurge.DIALOG_TITLE).assertIsDisplayed()
        composeRule.onNodeWithText(BackupPurge.CANCEL_LABEL).assertIsDisplayed().performClick()
        assertTrue(dismissed)
        assertFalse("取消绝不能触发删除", purged)
    }

    @Test
    fun confirmButtonSaysWhatItDoes() {
        composeRule.setContent {
            BD2ModManagerTheme {
                AppDialog(
                    title = BackupPurge.DIALOG_TITLE,
                    message = BackupPurge.consequence(1, "80 MB"),
                    confirmLabel = BackupPurge.CONFIRM_LABEL,
                    onConfirm = {},
                    onDismiss = {}
                )
            }
        }
        // 标题与按钮不同名，且按钮写的是动作而不是泛化的「确定」
        composeRule.onNodeWithText(BackupPurge.DIALOG_TITLE).assertIsDisplayed()
        composeRule.onNodeWithText(BackupPurge.CONFIRM_LABEL).assertIsDisplayed()
        assertNotEquals(BackupPurge.DIALOG_TITLE, BackupPurge.CONFIRM_LABEL)
        assertFalse(BackupPurge.CONFIRM_LABEL == "确定")
    }

    @Test
    fun revalidationDetectsChangedTargetSet() {
        val confirmed = mapOf("a" to "h1", "b" to "h2")
        val fingerprint = BackupPurge.fingerprint(confirmed)
        assertEquals(PurgeRevalidation.Unchanged, BackupPurge.revalidate(fingerprint, confirmed))
        // 确认之后新产生了一份备份
        val changed = confirmed + ("c" to "h3")
        val result = BackupPurge.revalidate(fingerprint, changed)
        assertTrue("目标集合变了必须重新确认", result is PurgeRevalidation.Changed)
        assertEquals(3, (result as PurgeRevalidation.Changed).currentCount)
    }

    @Test
    fun fingerprintIsOrderIndependentAndContentSensitive() {
        val a = mapOf("x" to "h1", "y" to "h2")
        val b = mapOf("y" to "h2", "x" to "h1")
        assertEquals(BackupPurge.fingerprint(a), BackupPurge.fingerprint(b))
        assertNotEquals(BackupPurge.fingerprint(a), BackupPurge.fingerprint(mapOf("x" to "h1")))
    }

    @Test
    fun resultNeverReportsFakeSuccessAndRecountsRemaining() {
        val allFailed = BackupPurge.outcome(
            attempted = 4, deleted = 0, remainingCount = 4, remainingBytes = 900L
        )
        assertFalse(allFailed.fullyCleared)
        val failedText = BackupPurge.resultText(allFailed, "900 B")
        assertTrue(failedText.contains("失败"))
        assertFalse("失败不得说成已清空", failedText.contains("已清空 4"))

        val partial = BackupPurge.outcome(
            attempted = 4, deleted = 2, remainingCount = 2, remainingBytes = 400L
        )
        assertTrue(partial.partiallyCleared)
        val partialText = BackupPurge.resultText(partial, "400 B")
        assertTrue(partialText.contains("部分成功"))
        assertTrue("剩余量用真实统计", partialText.contains("400 B"))

        val full = BackupPurge.outcome(
            attempted = 4, deleted = 4, remainingCount = 0, remainingBytes = 0L
        )
        assertTrue(full.fullyCleared)
        assertTrue(BackupPurge.resultText(full, "0 B").contains("已清空 4 份"))
    }

    @Test
    fun purgeIsBlockedWhileTaskIsActive() {
        assertTrue(BackupPurge.blockedByActiveTask("install-42")!!.contains("install-42"))
        assertEquals(null, BackupPurge.blockedByActiveTask(null))
    }

    @Test
    fun destructiveDialogRendersConsequenceNotJustTitle() {
        composeRule.setContent {
            BD2ModManagerTheme {
                Text(BackupPurge.consequence(7, "1.1 GB"))
            }
        }
        composeRule.onNodeWithText(BackupPurge.consequence(7, "1.1 GB")).assertIsDisplayed()
    }
    // ------------------------------------------------ T44：接进真实备份管理弹层

    @Test
    fun backupManageDialogNeedsASecondStepBeforeClearing() {
        var cleared = 0
        composeRule.setContent {
            BD2ModManagerTheme {
                BackupManageDialog(
                    enabled = true,
                    usage = 3L * 1024 * 1024 to 3,
                    onToggle = {},
                    onConfirmClear = { cleared++ },
                    blocker = null,
                    result = null,
                    onAcknowledgeResult = {},
                    onDismiss = {}
                )
            }
        }
        // 第一步只显示占用与后果，不产生任何文件操作
        composeRule.onNodeWithText("当前已备份 3 个，占用 3.0 MB").assertIsDisplayed()
        composeRule.onNodeWithText("清空备份").assertIsDisplayed().performClick()
        assertEquals("点「清空备份」不得直接执行", 0, cleared)

        // 第二步：确认框写明数量/空间/后果，按钮写动作
        composeRule.onNodeWithText(BackupPurge.DIALOG_TITLE).assertIsDisplayed()
        composeRule.onNodeWithText(BackupPurge.CONFIRM_LABEL).assertIsDisplayed()
        composeRule.onNodeWithText(BackupPurge.CANCEL_LABEL).assertIsDisplayed()
        composeRule.onNodeWithText(BackupPurge.CONFIRM_LABEL).performClick()
        assertEquals("确认后才执行一次", 1, cleared)
    }

    @Test
    fun cancellingTheConfirmationStepClearsNothing() {
        var cleared = 0
        composeRule.setContent {
            BD2ModManagerTheme {
                BackupManageDialog(
                    enabled = true,
                    usage = 512L * 1024 to 1,
                    onToggle = {},
                    onConfirmClear = { cleared++ },
                    blocker = null,
                    result = null,
                    onAcknowledgeResult = {},
                    onDismiss = {}
                )
            }
        }
        composeRule.onNodeWithText("清空备份").performClick()
        composeRule.onNodeWithText(BackupPurge.CANCEL_LABEL).performClick()
        assertEquals("取消确认不得产生任何文件操作", 0, cleared)
        // 回到第一步，清空入口仍在
        composeRule.onNodeWithText("清空备份").assertIsDisplayed()
    }

    @Test
    fun activeTaskBlocksTheClearConfirmation() {
        var cleared = 0
        val blocker = BackupPurge.blockedByActiveTask("install-42")!!
        composeRule.setContent {
            BD2ModManagerTheme {
                BackupManageDialog(
                    enabled = true,
                    usage = 1024L * 1024 to 2,
                    onToggle = {},
                    onConfirmClear = { cleared++ },
                    blocker = blocker,
                    result = null,
                    onAcknowledgeResult = {},
                    onDismiss = {}
                )
            }
        }
        composeRule.onNodeWithText("清空备份").performClick()
        // 确认框里说明了为什么现在不能清
        composeRule.onNodeWithText(blocker, substring = true).assertIsDisplayed()
        // 且没有可点的确认按钮
        assertEquals(
            "有活跃任务时不得出现确认按钮",
            0,
            composeRule.onAllNodesWithText(BackupPurge.CONFIRM_LABEL).fetchSemanticsNodes().size
        )
        assertEquals(0, cleared)
    }

    @Test
    fun resultDialogShowsTheRealPartialOutcome() {
        val outcome = BackupPurge.outcome(
            attempted = 4, deleted = 2, remainingCount = 2, remainingBytes = 700L * 1024
        )
        composeRule.setContent {
            BD2ModManagerTheme {
                BackupManageDialog(
                    enabled = true,
                    usage = 0L to 0,
                    onToggle = {},
                    onConfirmClear = {},
                    blocker = null,
                    result = outcome,
                    onAcknowledgeResult = {},
                    onDismiss = {}
                )
            }
        }
        composeRule.onNodeWithText("部分成功", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("700 KB", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("知道了").assertIsDisplayed()
    }
}
