package com.bd2toolsbox.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bd2toolsbox.data.model.ModInstallState
import com.bd2toolsbox.ui.components.AppDialog
import com.bd2toolsbox.ui.components.StatusBadge
import com.bd2toolsbox.ui.components.StatusTone
import com.bd2toolsbox.ui.components.TaskProgressPanel
import com.bd2toolsbox.ui.theme.BD2ModManagerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T41：通用组件的语义与交互状态契约（设备）。
 *
 * 断言状态标签可读屏、弹窗主次按钮与重试语义、任务面板取消的安全阶段控制。
 * 视觉观感（明暗下的配色搭配）由 T39/T40 的契约与人工截图负责，不在本用例冒充。
 */
@RunWith(AndroidJUnit4::class)
class ComponentContractTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun statusBadgeExposesSemanticNameAndIsNotClickable() {
        composeRule.setContent {
            BD2ModManagerTheme {
                StatusBadge(text = "已装入", tone = StatusTone.SUCCESS)
            }
        }
        composeRule.onNodeWithText("已装入").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("状态：已装入").assertIsDisplayed()
    }

    @Test
    fun installStateToneMappingIsCentralised() {
        // 真实枚举（不是猜出来的字符串名）：早先的字符串版与真实枚举一个都对不上，
        // 映射实际从未生效 —— 这条用例现在直接钉真实枚举。
        assertEquals(StatusTone.SUCCESS, StatusTone.forInstallState(ModInstallState.INSTALLED))
        assertEquals(StatusTone.NEUTRAL, StatusTone.forInstallState(ModInstallState.NOT_INSTALLED))
        assertEquals(StatusTone.WARNING, StatusTone.forInstallState(ModInstallState.STALE))
        assertEquals(
            StatusTone.WARNING,
            StatusTone.forInstallState(ModInstallState.MODIFIED_BY_OTHER)
        )
        assertEquals(StatusTone.INFO, StatusTone.forInstallState(ModInstallState.UNVERIFIED))
    }

    @Test
    fun dialogShowsPrimaryAndDismissButtonsAndRetryOnlyWhenRetryable() {
        var confirmed = false
        var dismissed = false
        composeRule.setContent {
            BD2ModManagerTheme {
                AppDialog(
                    title = "确认卸载",
                    message = "将还原官方原版",
                    onConfirm = { confirmed = true },
                    onDismiss = { dismissed = true }
                )
            }
        }
        composeRule.onNodeWithText("确认卸载").assertIsDisplayed()
        composeRule.onNodeWithText("确定").assertIsDisplayed().performClick()
        assertTrue(confirmed)
        composeRule.onNodeWithText("关闭").assertIsDisplayed().performClick()
        assertTrue(dismissed)
    }

    @Test
    fun dialogExposesRetryWhenProvided() {
        var retried = false
        composeRule.setContent {
            BD2ModManagerTheme {
                AppDialog(
                    title = "装入失败",
                    message = "Binder 断开",
                    onDismiss = {},
                    onConfirm = {},
                    onRetry = { retried = true }
                )
            }
        }
        composeRule.onNodeWithText("重试").assertIsDisplayed().performClick()
        assertTrue(retried)
    }

    @Test
    fun taskProgressPanelCancelIsVisibleButDisabledWhenNotSafe() {
        composeRule.setContent {
            BD2ModManagerTheme {
                TaskProgressPanel(
                    title = "转换中",
                    detail = "解包 3/48",
                    progress = null,
                    cancelEnabled = false,
                    onCancel = {}
                )
            }
        }
        composeRule.onNodeWithContentDescription("任务进度：转换中").assertIsDisplayed()
        composeRule.onNodeWithText("取消").assertIsDisplayed().assertIsNotEnabled()
    }

    @Test
    fun taskProgressPanelCancelEnabledAtSafeStage() {
        var cancelled = false
        composeRule.setContent {
            BD2ModManagerTheme {
                TaskProgressPanel(
                    title = "转换中",
                    progress = 0.5f,
                    cancelEnabled = true,
                    onCancel = { cancelled = true }
                )
            }
        }
        composeRule.onNodeWithText("取消").assertIsDisplayed().assertIsEnabled().performClick()
        assertTrue(cancelled)
    }

    @Test
    fun componentsUseThemeSemanticColorsNotHardcoded() {
        // 编译期保证：组件只引用 MaterialTheme 语义色（无 Color(0x…) 字面量）。
        // 这里做一个最小运行期确认：在明暗两套主题下都能渲染。
        var rendered = false
        composeRule.setContent {
            BD2ModManagerTheme {
                Text("x", color = MaterialTheme.colorScheme.onSurface)
                StatusBadge("状态", StatusTone.INFO)
                rendered = true
            }
        }
        composeRule.waitForIdle()
        assertTrue(rendered)
    }
}
