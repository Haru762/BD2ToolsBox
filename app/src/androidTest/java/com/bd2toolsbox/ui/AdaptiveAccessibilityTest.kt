package com.bd2toolsbox.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bd2toolsbox.ui.components.AppDialog
import com.bd2toolsbox.ui.components.ModActionButtons
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
 * T46：横竖屏 / 大字体 / 触控与辅助功能契约（设备）。
 *
 * 机器可判的部分：
 * - 关键可点元素的**真实触控边界** >= 48dp（不是只看图标尺寸）；
 * - 语义名存在且装饰图标不重复朗读（同一个语义节点只出现一次）；
 * - 状态不只靠颜色：状态标签始终带文字；
 * - 字体 1.0 / 1.3 / 2.0 倍下关键操作仍可见、不被挤出；
 * - 窄屏（320dp）下操作与文本仍可见。
 *
 * 观感/旋转后任务不重复执行等需要真实交互与截图的部分不在本用例内冒充。
 */
@RunWith(AndroidJUnit4::class)
class AdaptiveAccessibilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * 真实触控边界（[androidx.compose.ui.semantics.SemanticsNode.touchBoundsInRoot]），
     * 不是图标尺寸 —— 不能用「图标看起来够大」推断可点区域达标。
     */
    private fun assertTouchTargetAtLeast(text: String, min: Dp = 48.dp) {
        assertTouchTargetAtLeastByNode(composeRule.onNodeWithText(text), text, min)
    }

    private fun assertTouchTargetAtLeastByDescription(description: String, min: Dp = 48.dp) {
        assertTouchTargetAtLeastByNode(
            composeRule.onNodeWithContentDescription(description), description, min
        )
    }

    private fun assertTouchTargetAtLeastByNode(
        node: androidx.compose.ui.test.SemanticsNodeInteraction,
        label: String,
        min: Dp
    ) {
        // touchBoundsInRoot 单位是像素，与 Dp 比较需按当前 density 换算。
        val bounds = node.fetchSemanticsNode().touchBoundsInRoot
        val minPx = with(composeRule.density) { min.toPx() }
        assertTrue("$label 的触控宽 ${bounds.width}px < ${minPx}px", bounds.width >= minPx)
        assertTrue("$label 的触控高 ${bounds.height}px < ${minPx}px", bounds.height >= minPx)
    }

    @Test
    fun actionIconButtonsHaveRealTouchTargetsOfAtLeast48dp() {
        composeRule.setContent {
            BD2ModManagerTheme {
                ModActionButtons(onPreview = {}, onMore = {})
            }
        }
        assertTouchTargetAtLeastByDescription("预览")
        assertTouchTargetAtLeastByDescription("更多操作")
    }

    @Test
    fun textButtonsInDialogsAndPanelsMeetTouchTarget() {
        composeRule.setContent {
            BD2ModManagerTheme {
                Column {
                    AppDialog(
                        title = "清空原版备份？",
                        message = "后果说明",
                        onDismiss = {},
                        onConfirm = {}
                    )
                    TaskProgressPanel(
                        title = "转换中",
                        onToggleCollapse = {},
                        onCancel = {}
                    )
                }
            }
        }
        assertTouchTargetAtLeast("确定")
        assertTouchTargetAtLeast("关闭")
        assertTouchTargetAtLeast("收起")
        assertTouchTargetAtLeast("取消")
    }

    @Test
    fun decorativeIconsAreNotAnnouncedTwice() {
        composeRule.setContent {
            BD2ModManagerTheme { ModActionButtons(onPreview = {}, onMore = {}) }
        }
        // 每个入口只应有一个语义节点（图标本身不额外朗读）
        assertEquals(
            1,
            composeRule.onAllNodesWithContentDescription("预览").fetchSemanticsNodes().size
        )
        assertEquals(
            1,
            composeRule.onAllNodesWithContentDescription("更多操作").fetchSemanticsNodes().size
        )
    }

    @Test
    fun stateIsNotConveyedByColourAlone() {
        // 每个语义 tone 都带文字；不依赖颜色区分
        composeRule.setContent {
            BD2ModManagerTheme {
                Column {
                    StatusBadge("已装入", StatusTone.SUCCESS)
                    StatusBadge("失败", StatusTone.ERROR)
                    StatusBadge("待重试", StatusTone.WARNING)
                    StatusBadge("结果未知", StatusTone.INFO)
                }
            }
        }
        composeRule.onNodeWithText("已装入").assertIsDisplayed()
        composeRule.onNodeWithText("失败").assertIsDisplayed()
        composeRule.onNodeWithText("待重试").assertIsDisplayed()
        composeRule.onNodeWithText("结果未知").assertIsDisplayed()
    }

    @Test
    fun fontScale2xKeepsCriticalActionsVisible() {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 2.75f, fontScale = 2.0f)
            ) {
                BD2ModManagerTheme {
                    AppDialog(
                        title = "清空原版备份？",
                        message = "将清空 12 份原版备份（3.4 GB），清空后失去本地原版恢复资源，此操作不可撤销。",
                        onDismiss = {},
                        onConfirm = {}
                    )
                }
            }
        }
        composeRule.onNodeWithText("清空原版备份？").assertIsDisplayed()
        composeRule.onNodeWithText("确定").assertIsDisplayed()
        composeRule.onNodeWithText("关闭").assertIsDisplayed()
    }

    @Test
    fun fontScale13xKeepsTaskPanelControlsVisible() {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 2.75f, fontScale = 1.3f)
            ) {
                BD2ModManagerTheme {
                    TaskProgressPanel(
                        title = "转换中：一个相当长的中文资源包名称用于验证不溢出",
                        detail = "解包 3/48",
                        progress = 0.4f,
                        onToggleCollapse = {},
                        onCancel = {}
                    )
                }
            }
        }
        composeRule.onNodeWithText("收起").assertIsDisplayed()
        composeRule.onNodeWithText("取消").assertIsDisplayed()
    }

    @Test
    fun narrowWidth320dpKeepsActionsReachable() {
        composeRule.setContent {
            BD2ModManagerTheme {
                Box(Modifier.size(width = 320.dp, height = 640.dp)) {
                    TaskProgressPanel(
                        title = "转换中：长文件名_0123456789_资源包.zip",
                        detail = "解包 3/48",
                        progress = 0.4f,
                        onToggleCollapse = {},
                        onCancel = {}
                    )
                }
            }
        }
        composeRule.onNodeWithText("收起").assertIsDisplayed()
        composeRule.onNodeWithText("取消").assertIsDisplayed()
        composeRule.onNodeWithText("转换中：长文件名_0123456789_资源包.zip").assertIsDisplayed()
    }

    @Test
    fun longTitlesDoNotHideTheActions() {
        composeRule.setContent {
            BD2ModManagerTheme {
                Column(Modifier.fillMaxWidth()) {
                    TaskProgressPanel(
                        title = "一个非常非常长的中文标题".repeat(4),
                        progress = null,
                        onToggleCollapse = {},
                        onCancel = {}
                    )
                }
            }
        }
        composeRule.onNodeWithText("收起").assertIsDisplayed()
    }
}
