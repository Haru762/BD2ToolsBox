package com.bd2toolsbox.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bd2toolsbox.ui.components.TaskPanelUiState
import com.bd2toolsbox.ui.components.TaskProgressPanel
import com.bd2toolsbox.ui.theme.BD2ModManagerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T43：任务面板生命周期契约（设备）。
 *
 * 守的几件事：
 * - **收起/展开只改展示**：点「收起」不触发取消、不触碰任务回调；
 * - 收起后仍能看到标题与「展开」入口（可重新打开同一任务）；
 * - 提交临界区禁用取消：按钮可见但不可点，同时仍允许收起；
 * - `TaskPanelUiState.toggled()` 是纯函数（JVM 语义）。
 */
@RunWith(AndroidJUnit4::class)
class TaskPanelLifecycleTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun toggledStateIsPure() {
        assertEquals(TaskPanelUiState(false).toggled(), TaskPanelUiState(true))
        assertEquals(TaskPanelUiState(true).toggled(), TaskPanelUiState(false))
    }

    @Test
    fun collapseChangesDisplayOnlyAndNeverCancels() {
        var cancelled = false
        composeRule.setContent {
            var state by remember { mutableStateOf(TaskPanelUiState()) }
            BD2ModManagerTheme {
                TaskProgressPanel(
                    title = "转换中",
                    detail = "解包 3/48",
                    progress = 0.4f,
                    collapsed = state.collapsed,
                    onToggleCollapse = { state = state.toggled() },
                    cancelEnabled = true,
                    onCancel = { cancelled = true }
                )
            }
        }
        composeRule.onNodeWithContentDescription("任务进度：转换中").assertIsDisplayed()
        composeRule.onNodeWithText("收起").assertIsDisplayed().performClick()
        assertFalse("收起绝不能触发取消", cancelled)
        // 收起后仍有「展开」入口，可重开同一任务
        composeRule.onNodeWithText("展开").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("收起").assertIsDisplayed()
        assertFalse(cancelled)
    }

    @Test
    fun commitCriticalSectionDisablesCancelButStillAllowsCollapse() {
        var cancelled = false
        var toggled = false
        composeRule.setContent {
            BD2ModManagerTheme {
                TaskProgressPanel(
                    title = "提交中",
                    detail = "正在替换游戏文件",
                    progress = 0.9f,
                    onToggleCollapse = { toggled = true },
                    cancelEnabled = false,
                    onCancel = { cancelled = true }
                )
            }
        }
        composeRule.onNodeWithText("取消").assertIsDisplayed().assertIsNotEnabled()
        composeRule.onNodeWithText("收起").assertIsDisplayed().performClick()
        assertTrue("提交临界区仍允许收起", toggled)
        assertFalse(cancelled)
    }

    @Test
    fun collapsedPanelStillShowsTitleForReconnect() {
        composeRule.setContent {
            BD2ModManagerTheme {
                TaskProgressPanel(
                    title = "预解包中",
                    detail = "不应显示",
                    progress = 0.2f,
                    collapsed = true,
                    onToggleCollapse = {}
                )
            }
        }
        composeRule.onNodeWithContentDescription("任务进度：预解包中").assertIsDisplayed()
        composeRule.onNodeWithText("预解包中").assertIsDisplayed()
    }
}
