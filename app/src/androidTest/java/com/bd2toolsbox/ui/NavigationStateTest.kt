package com.bd2toolsbox.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T33：顶层路由状态的契约（设备）。
 *
 * 这一层要守的是「切 tab / 开弹层 / 返回 / 旋转都不丢关键状态，也不叠出冲突弹层」。
 * 路由状态本身是纯状态逻辑，但「配置变更后还在不在」只有真的走一遍 Compose 的
 * 保存/恢复才作数 —— 所以用 [StateRestorationTester] 模拟一次重建，
 * 而不是在断言里自己调一遍 Saver 就算过。
 *
 * 用 `StateRestorationTester` 而不是真的旋转设备：MIUI 上 instrumentation 起
 * Activity 会被拒（`MIUILOG- Permission Denied Activity`），而且真旋转在无头
 * 模拟器上不可靠。`StateRestorationTester` 走的是**同一套** SavedInstanceState
 * 机制，验的是同一个东西。
 */
@RunWith(AndroidJUnit4::class)
class NavigationStateTest {

    @get:Rule
    val compose = createComposeRule()

    // ---------------------------------------------------------------- 纯状态

    @Test
    fun tabSelectionIsExplicitAndFlat() {
        val nav = TopLevelNavState()
        assertEquals("默认应当落在 Mods", TopLevelTab.MODS, nav.tab)

        nav.select(TopLevelTab.GUIDES)
        assertEquals(TopLevelTab.GUIDES, nav.tab)
        nav.select(TopLevelTab.CDK)
        assertEquals(TopLevelTab.CDK, nav.tab)
        // 三个 tab 平级：从兑换码直接回 Mods 是一步，不需要先回攻略
        nav.select(TopLevelTab.MODS)
        assertEquals(TopLevelTab.MODS, nav.tab)
    }

    @Test
    fun settingsOverlaysAnyTabAndIsTheOnlyThingBackConsumes() {
        val nav = TopLevelNavState()
        nav.select(TopLevelTab.CDK)

        // 没有弹层时返回**不被消费** —— 让系统去退 App，而不是硬把用户带回 Mods
        assertFalse("没有盖层时不该消费返回键", nav.handleBack())

        nav.openSettings()
        assertTrue("设置开着时返回键应当被消费", nav.handleBack())
        assertFalse("消费一次之后应当已经关上", nav.settingsOpen)
        assertEquals("关设置不该改变当前 tab", TopLevelTab.CDK, nav.tab)
        // 再按一次返回就交还给系统
        assertFalse(nav.handleBack())
    }

    @Test
    fun unknownSavedValuesFallBackToModsInsteadOfCrashing() {
        // 存档来自旧版本 / 被改坏时不能崩
        assertEquals(TopLevelTab.MODS, TopLevelTab.fromName(null))
        assertEquals(TopLevelTab.MODS, TopLevelTab.fromName("SOMETHING_ELSE"))
        assertEquals(TopLevelTab.MODS, TopLevelTab.fromId(99))
        assertEquals(TopLevelTab.CDK, TopLevelTab.fromId(2))
        // 名字匹配应当大小写敏感（存的就是 enum 名）
        assertEquals(TopLevelTab.MODS, TopLevelTab.fromName("mods"))
    }

    @Test
    fun saverRoundTripKeepsTabAndSheetState() {
        val nav = TopLevelNavState(TopLevelTab.CDK, settingsOpenInitially = true)
        // `Saver.save` 是 Saver 的成员，但它的**扩展接收者**是 SaverScope ——
        // 两个接收者都要给：`with(Saver) { scope.save(nav) }`。
        val scope = object : androidx.compose.runtime.saveable.SaverScope {
            override fun canBeSaved(value: Any) = true
        }
        val saved = with(TopLevelNavState.Saver) { scope.save(nav) }
        assertNotNull("保存不该产出 null（那等于什么都没存）", saved)
        val restored = TopLevelNavState.Saver.restore(saved!!)!!
        assertEquals(TopLevelTab.CDK, restored.tab)
        assertTrue("设置面板的开合状态也要一起活下来", restored.settingsOpen)
    }

    // ---------------------------------------------------------------- 真的走一遍保存/恢复

    @Test
    fun tabSurvivesASimulatedRecreation() {
        val restorer = StateRestorationTester(compose)
        restorer.setContent {
            val nav = rememberTopLevelNavState()
            androidx.compose.material3.TextButton(onClick = { nav.select(TopLevelTab.GUIDES) }) {
                Text("tab=${nav.tab.name}")
            }
        }
        compose.onNodeWithText("tab=MODS").assertIsDisplayed()
        compose.onNodeWithText("tab=MODS").performClick()
        compose.onNodeWithText("tab=GUIDES").assertIsDisplayed()

        // 模拟一次配置变更（旋转/字体缩放/深浅切换走的是同一套）
        restorer.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("tab=GUIDES")
            .assertIsDisplayed()
    }

    @Test
    fun openSettingsSheetSurvivesASimulatedRecreation() {
        val restorer = StateRestorationTester(compose)
        restorer.setContent {
            val nav = rememberTopLevelNavState()
            androidx.compose.material3.TextButton(onClick = { nav.openSettings() }) {
                Text("settings=${nav.settingsOpen}")
            }
        }
        compose.onNodeWithText("settings=false").performClick()
        compose.onNodeWithText("settings=true").assertIsDisplayed()

        restorer.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("settings=true").assertIsDisplayed()
    }
}
