package com.bd2toolsbox.core.fileaccess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [decideBackupGate] 的 JVM 单元测试（T07）。
 *
 * ## 这些用例补的是哪一块空白
 *
 * 三个调用点（`MainViewModel` 的 `:1501` / `:1614` / `:1722`）都在 ViewModel 里，
 * 构造它要 `Context`，而本仓**没有 Robolectric** —— 那三处的编排行为自动化不了。
 * 抽成纯函数之后，「备份开关 + 备份结果 + 是否在提交路径上 ⇒ 放行/中止/放行但上报」
 * 这段**判定本身**有了真实的 JVM 用例，而不是只剩「代码可读」这一层。
 *
 * **说清这一层覆盖不到什么**：本类用例证明的是**判定**正确；「目标文件真的没变」
 * 由 [AtomicFileWriterTest] 证明；「ViewModel 拿到 ABORT 之后真的中止了该目标」
 * **本轮未覆盖**（设备侧可行、未做，见 `T07.md` §7.2）。
 */
class BackupGateTest {

    // ------------------------------------------------------------------
    // 1. 备份关着：不建备份、照常写入（防「静默改回开启」）
    // ------------------------------------------------------------------

    /**
     * 备份关着时，**哪怕传进来的备份结果是 `false`** 也必须放行。
     *
     * 这一格是「用户明确关掉的设置不被静默改回开启」的可执行形态：开关关着就
     * 不会去调用备份（所以 `backupSucceeded` 只是个没意义的占位值 `false`），
     * 判定必须**先看开关**，否则这段纯函数会把用户的明确选择变成一次中止。
     */
    @Test
    fun disabledBackupProceedsEvenThoughNoBackupWasMade() {
        val decision = decideBackupGate(backupEnabled = false, backupSucceeded = false, onCommitPath = true)
        assertEquals(BackupGateDecision.PROCEED, decision)
        assertTrue(decision.allowsWrite)
        assertFalse("开关关着不是「有件事要上报」", decision.mustReport)
    }

    /** 关着开关且**在提交路径上**也照样放行 —— 这是计划明确要求的既有语义。 */
    @Test
    fun disabledBackupProceedsOnTheCommitPathToo() {
        assertEquals(
            BackupGateDecision.PROCEED,
            decideBackupGate(backupEnabled = false, backupSucceeded = false, onCommitPath = true)
        )
    }

    // ------------------------------------------------------------------
    // 2. 开着且成功：放行，且不需要上报
    // ------------------------------------------------------------------

    @Test
    fun successfulBackupProceedsOnCommitPath() {
        val decision = decideBackupGate(backupEnabled = true, backupSucceeded = true, onCommitPath = true)
        assertEquals(BackupGateDecision.PROCEED, decision)
        assertTrue(decision.allowsWrite)
        assertFalse("成功了没什么要上报的", decision.mustReport)
    }

    @Test
    fun successfulBackupProceedsOffCommitPath() {
        assertEquals(
            BackupGateDecision.PROCEED,
            decideBackupGate(backupEnabled = true, backupSucceeded = true, onCommitPath = false)
        )
    }

    // ------------------------------------------------------------------
    // 3. 开着但失败：提交路径中止，非提交路径放行但必须上报
    // ------------------------------------------------------------------

    /**
     * **提交路径上的备份失败必须中止**（计划落实要求第 3 条的前半句）。
     *
     * 这条路径写完，游戏目录里那份原版就永远找不回来了 —— 没有第二次机会。
     */
    @Test
    fun failedBackupOnCommitPathAborts() {
        val decision = decideBackupGate(backupEnabled = true, backupSucceeded = false, onCommitPath = true)
        assertEquals(BackupGateDecision.ABORT, decision)
        assertFalse("中止就是不放行写入", decision.allowsWrite)
    }

    /**
     * **非提交路径上的备份失败放行，但必须上报。**
     *
     * 这一步是判断不是事实（依据与代价写在 [decideBackupGate] 的文档注释与
     * `T07.md` §3.5）：产物本身仍会被完整写进 `Download/Shared`，为一次顺手备份
     * 中止一次已经跑了几十秒的转换，代价大于收益。用例把这条取舍钉住，
     * 谁想改成「一律中止」，改这一处即可、三个调用点自动跟着变。
     */
    @Test
    fun failedBackupOffCommitPathProceedsButMustBeReported() {
        val decision = decideBackupGate(backupEnabled = true, backupSucceeded = false, onCommitPath = false)
        assertEquals(BackupGateDecision.PROCEED_WITH_WARNING, decision)
        assertTrue("放行写入", decision.allowsWrite)
        assertTrue("但必须让用户看见，不许静默", decision.mustReport)
    }

    // ------------------------------------------------------------------
    // 4. 三态语义自身的边界（防有人把它压回布尔）
    // ------------------------------------------------------------------

    /**
     * 「放行但上报」与「放行且无需上报」**必须是两个不同的值**。
     *
     * 若有人把返回值压回 `Boolean`，这一格就会消失：两个都变 `true`，
     * 于是「备份失败了」这件事在调用方无从表达 —— 那正是 T07 之前
     * 「三处丢弃返回值」的同一个坑，只是换了个位置。
     */
    @Test
    fun proceedAndProceedWithWarningAreDistinguishable() {
        val quiet = decideBackupGate(backupEnabled = true, backupSucceeded = true, onCommitPath = false)
        val loud = decideBackupGate(backupEnabled = true, backupSucceeded = false, onCommitPath = false)

        assertTrue(quiet.allowsWrite)
        assertTrue(loud.allowsWrite)
        assertFalse(quiet.mustReport)
        assertTrue(loud.mustReport)
        assertTrue("两者不能是同一个值", quiet != loud)
    }

    /** 只有 [BackupGateDecision.ABORT] 不放行。 */
    @Test
    fun onlyAbortBlocksTheWrite() {
        val all = listOf(
            decideBackupGate(true, true, true),
            decideBackupGate(true, true, false),
            decideBackupGate(true, false, true),
            decideBackupGate(true, false, false),
            decideBackupGate(false, false, true),
            decideBackupGate(false, false, false)
        )
        assertEquals(1, all.count { !it.allowsWrite })
        assertEquals(BackupGateDecision.ABORT, all.single { !it.allowsWrite })
    }

    /**
     * 判定必须**只看这三个输入**（纯函数）：同样输入重复调用结果相同，
     * 不依赖任何外部状态。这条防的是将来有人为了「顺手」把开关读进函数体里。
     */
    @Test
    fun decisionIsAPureFunctionOfItsThreeInputs() {
        val inputs = listOf(true to true, true to false, false to true, false to false)
        for ((enabled, succeeded) in inputs) {
            for (onCommitPath in listOf(true, false)) {
                val first = decideBackupGate(enabled, succeeded, onCommitPath)
                val second = decideBackupGate(enabled, succeeded, onCommitPath)
                assertEquals("同样输入必须同样输出：$enabled/$succeeded/$onCommitPath", first, second)
            }
        }
    }
}
