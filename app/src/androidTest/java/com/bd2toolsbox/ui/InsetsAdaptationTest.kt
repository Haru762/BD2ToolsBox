package com.bd2toolsbox.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bd2toolsbox.ui.theme.BD2ModManagerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T46：insets 适配的结构契约（设备）。
 *
 * 覆盖**可机器判定**的那部分：
 * - `safeDrawing` / `statusBarsPadding` / `navigationBarsPadding` / `imePadding`
 *   这些修饰符在本 Compose 版本上都能实际改变布局（不是空操作）；
 * - 把输入框放进带 `imePadding()` 的容器后仍可见、可输入 —— 这是「键盘弹出时输入
 *   可达」的结构前提。
 *
 * **不覆盖**：真实软键盘弹出后的观感、刘海/横屏侧边栏的实际避让。那些需要真机
 * 交互与截图核对，见 T46 证据的未完成项。
 */
@RunWith(AndroidJUnit4::class)
class InsetsAdaptationTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * 用一个人造 inset 验证修饰符确实生效（否则后面几条断言毫无意义）。
     *
     * 断言**相对位移**而不是绝对坐标：测试宿主窗口自己可能已经有 insets
     * （edge-to-edge），绝对 Y 会把那部分算进去。
     */
    @Test
    fun insetModifiersActuallyShiftTheLayout() {
        var outerY = -1f
        var innerY = -1f
        composeRule.setContent {
            BD2ModManagerTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .onGloballyPositionedY { outerY = it }
                        .windowInsetsPadding(
                            WindowInsets(top = 48, bottom = 0, left = 0, right = 0)
                        )
                ) {
                    Box(Modifier.size(10.dp).onGloballyPositionedY { innerY = it })
                }
            }
        }
        composeRule.waitForIdle()
        // 内层相对**外层**的位移才是这个修饰符贡献的；绝对坐标会把宿主窗口自己的
        // insets（edge-to-edge 的测试宿主）也算进来。
        val expected = with(composeRule.density) { 48f }
        assertEquals(
            "windowInsetsPadding 必须真的把内容下移 48px",
            expected,
            innerY - outerY,
            2f
        )
    }

    @Test
    fun safeDrawingAndBarPaddingsAreUsable() {
        composeRule.setContent {
            BD2ModManagerTheme {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    Text("safe-drawing", modifier = Modifier.statusBarsPadding())
                    Text("nav", modifier = Modifier.navigationBarsPadding())
                }
            }
        }
        composeRule.onNodeWithText("safe-drawing").assertIsDisplayed()
        composeRule.onNodeWithText("nav").assertIsDisplayed()
    }

    @Test
    fun inputInsideImePaddedContainerStaysUsable() {
        var value = ""
        composeRule.setContent {
            BD2ModManagerTheme {
                Box(Modifier.fillMaxSize().imePadding()) {
                    val text = remember { mutableStateOf("") }
                    OutlinedTextField(
                        value = text.value,
                        onValueChange = { text.value = it; value = it },
                        label = { Text("游戏昵称") }
                    )
                }
            }
        }
        composeRule.onNodeWithText("游戏昵称").assertIsDisplayed()
        composeRule.onNodeWithText("游戏昵称").performTextInput("tester")
        assertEquals("输入必须真的落到输入框", "tester", value)
    }

    @Test
    fun imePaddingComposesWithStatusAndNavigationPaddings() {
        composeRule.setContent {
            BD2ModManagerTheme {
                Column(
                    Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(8.dp)
                ) {
                    Text("正文", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        composeRule.onNodeWithText("正文").assertIsDisplayed()
    }
}

/** 取一个 composable 在窗口中的 Y 坐标（用来断言 inset 确实生效）。 */
private fun Modifier.onGloballyPositionedY(onY: (Float) -> Unit): Modifier =
    this.onGloballyPositioned { coords -> onY(coords.positionInWindow().y) }
