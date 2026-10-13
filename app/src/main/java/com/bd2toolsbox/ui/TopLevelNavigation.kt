package com.bd2toolsbox.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * 顶层入口（T33）。
 *
 * 三个 tab 是**平级**的：Mods 处理本地 mod，攻略与兑换码是纯在线阅读，
 * 彼此不牵扯。所以这里没有层级、没有栈 —— 一个「当前在哪一页」的枚举就够了。
 */
enum class TopLevelTab(val id: Int) {
    MODS(0),
    GUIDES(1),
    CDK(2);

    companion object {
        /** 未知值回退 [MODS]（不抛异常）—— 存档来自旧版本时不该崩。 */
        fun fromId(id: Int): TopLevelTab = entries.firstOrNull { it.id == id } ?: MODS

        fun fromName(name: String?): TopLevelTab =
            entries.firstOrNull { it.name == name } ?: MODS
    }
}

/**
 * 顶层路由状态（T33）。
 *
 * ## 为什么单独一个类，而不是散在 Activity 里的几个 `remember`
 *
 * 之前是 `var currentTab by remember { mutableIntStateOf(0) }` +
 * `var settingsOpen by remember { mutableStateOf(false) }` —— 两个问题：
 *
 * 1. **不保存**：`remember` 在配置变更（旋转、字体缩放、深色切换）后归零，
 *    用户回到 Mods 第一页、设置面板被关掉。计划明确要求「使用**可保存的**
 *    小型路由状态」「旋转不丢失关键状态」；
 * 2. **散落**：返回键该关什么、点齿轮该开什么，逻辑分散在几处赋值里，
 *    没有单一地方能回答「现在在哪一页、盖着什么」。
 *
 * 这里把「在哪一页」与「设置面板开没开」收在一处，并用 [Saver] 让它们
 * 进 `SavedInstanceState`。**刻意只放路由**：业务状态归 ViewModel，
 * 不把 ViewModel 的流抄一份到这里（那会变成两套事实来源）。
 */
@Stable
class TopLevelNavState(
    initialTab: TopLevelTab = TopLevelTab.MODS,
    settingsOpenInitially: Boolean = false
) {
    var tab: TopLevelTab by mutableStateOf(initialTab)
        private set

    /** 设置是**全局**的（壁纸/配色/备份都不属于某个 tab），所以它盖在任何 tab 之上。 */
    var settingsOpen: Boolean by mutableStateOf(settingsOpenInitially)
        private set

    fun select(next: TopLevelTab) {
        tab = next
    }

    fun openSettings() {
        settingsOpen = true
    }

    fun closeSettings() {
        settingsOpen = false
    }

    /**
     * 系统返回键该不该被消费。
     *
     * 只处理「盖在上面的那一层」：设置面板开着就先关它。**不处理 tab 回退** ——
     * 三个 tab 是平级的，在「兑换码」页按返回直接退出 App 符合直觉；
     * 硬把返回改成「回 Mods」会让人按两次才退得出去。
     *
     * 返回 `true` 表示已消费，调用方不要继续往下传。
     */
    fun handleBack(): Boolean {
        if (settingsOpen) {
            settingsOpen = false
            return true
        }
        return false
    }

    companion object {
        val Saver: Saver<TopLevelNavState, List<Any>> = Saver(
            save = { listOf(it.tab.name, it.settingsOpen) },
            restore = {
                TopLevelNavState(
                    initialTab = TopLevelTab.fromName(it.getOrNull(0) as? String),
                    settingsOpenInitially = it.getOrNull(1) as? Boolean ?: false
                )
            }
        )
    }
}

/** 配置变更后仍保留当前页与设置面板的开合状态。 */
@Composable
fun rememberTopLevelNavState(): TopLevelNavState =
    rememberSaveable(saver = TopLevelNavState.Saver) { TopLevelNavState() }
