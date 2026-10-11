package com.bd2toolsbox.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T38：开源许可页的离线可读性。
 *
 * 断言：标题可见；AboutLibraries 列表**无需网络**就渲染出内容（已知库名 + 许可全文标签）。
 * 数据来自随 APK 分发的 `res/raw/aboutlibraries.json`（T37）。
 *
 * 注意渲染文案是 AboutLibraries 的展示形式（如「Apache License 2.0」），不是 SPDX id
 * 「Apache-2.0」—— 断言用前者。
 */
@RunWith(AndroidJUnit4::class)
class LicensesScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun licensesScreenRendersOfflineWithTitleAndLibraryContent() {
        composeRule.setContent { LicensesScreen(onBack = {}) }
        composeRule.onNodeWithText("开源许可").assertIsDisplayed()

        // 等待列表加载（AboutLibraries 从资源读取，无网络）。
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodes(hasText("Activity", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        // 至少一个已知库条目，且带许可全文标签（离线数据）。
        assertTrue(
            "必须渲染出 Activity 库条目",
            composeRule.onAllNodes(hasText("Activity", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        )
        assertTrue(
            "必须带许可标签（离线）",
            composeRule.onAllNodes(hasText("Apache License 2.0", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        )
    }
}
