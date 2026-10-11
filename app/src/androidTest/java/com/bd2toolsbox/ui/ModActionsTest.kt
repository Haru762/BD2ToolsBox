package com.bd2toolsbox.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bd2toolsbox.ui.components.ModActionButtons
import com.bd2toolsbox.ui.theme.BD2ModManagerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T42：MOD 行操作入口契约（设备）。
 *
 * 守的几件事：
 * - 预览与「更多」是**两个独立可辨识的入口**（语义名不同），点击互不串；
 * - 预览禁用时**说明原因**且按钮仍可见（不是「看起来可点但什么也不做」）；
 * - 首次使用者无需长按即可找到预览。
 */
@RunWith(AndroidJUnit4::class)
class ModActionsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun previewAndMoreAreDistinctEntriesThatDoNotCrossFire() {
        var previews = 0
        var mores = 0
        composeRule.setContent {
            BD2ModManagerTheme {
                ModActionButtons(
                    onPreview = { previews++ },
                    onMore = { mores++ }
                )
            }
        }
        composeRule.onNodeWithContentDescription("预览").assertIsDisplayed().performClick()
        assertEquals("点预览只触发预览", 1, previews)
        assertEquals("点预览不触发更多", 0, mores)

        composeRule.onNodeWithContentDescription("更多操作").assertIsDisplayed().performClick()
        assertEquals(1, mores)
        assertEquals("点更多不启动预览", 1, previews)
    }

    @Test
    fun disabledPreviewExplainsReasonAndStaysVisible() {
        var previews = 0
        composeRule.setContent {
            BD2ModManagerTheme {
                ModActionButtons(
                    onPreview = { previews++ },
                    onMore = {},
                    previewEnabled = false,
                    previewDisabledReason = "未安装资源包"
                )
            }
        }
        composeRule.onNodeWithContentDescription("预览不可用：未安装资源包")
            .assertIsDisplayed()
            .assertIsNotEnabled()
        // 禁用的按钮点不动
        runCatching {
            composeRule.onNodeWithContentDescription("预览不可用：未安装资源包").performClick()
        }
        assertEquals(0, previews)
    }

    @Test
    fun defaultDisabledReasonIsProvidedWhenNoneGiven() {
        composeRule.setContent {
            BD2ModManagerTheme {
                ModActionButtons(
                    onPreview = {},
                    onMore = {},
                    previewEnabled = false
                )
            }
        }
        composeRule.onNodeWithContentDescription("预览不可用：该资源暂不支持直接预览")
            .assertIsDisplayed()
    }

    @Test
    fun moreEntryIsAlwaysAvailable() {
        composeRule.setContent {
            BD2ModManagerTheme {
                ModActionButtons(onPreview = {}, onMore = {}, previewEnabled = false)
            }
        }
        composeRule.onNodeWithContentDescription("更多操作").assertIsDisplayed()
        assertTrue(true)
    }
}
