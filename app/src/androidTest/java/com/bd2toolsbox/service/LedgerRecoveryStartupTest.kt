package com.bd2toolsbox.service

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bd2toolsbox.core.storage.JournalEntry
import com.bd2toolsbox.core.storage.JournalStage
import com.bd2toolsbox.core.storage.JournalTarget
import com.bd2toolsbox.core.storage.OperationJournalStore
import com.bd2toolsbox.data.repository.BundleBackupRepository
import com.bd2toolsbox.data.repository.CharacterRepository
import com.bd2toolsbox.data.repository.InstalledModRepository
import com.bd2toolsbox.data.repository.ModRepository
import com.bd2toolsbox.data.repository.PreviewCacheRepository
import com.bd2toolsbox.ui.viewmodel.MainViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * **启动核对真的接上了**：`initialize()` 跑完，`checkRecovery()` 的结论能被读到，
 * 且「文件已换、账本未存」这个窗口会走到**用户可见**的提示通道
 * （T08 二轮整改，复核真缺陷 2）。
 *
 * ## 这条用例为什么必须存在
 *
 * T08 一轮把 `InstalledModRepository.checkRecovery()` 的**判据**做扎实了
 * （21 条用例覆盖三态），但复核者 grep 出**生产代码里一个调用者都没有**：
 * `MainActivity` 零引用、`MainViewModel` 零引用，也没有任何 UI 渲染
 * `RecoveryVerdict.PendingReconciliation`。于是计划 T08 验收第 2 条
 * 「注入『文件已换、账本未存』故障，**重启后进入明确的待核对状态**」
 * 在交付代码里**不可能发生** —— 那句话所依赖的那行代码根本不存在。
 *
 * 一轮的 `T08.md` §7.2(c) 还写着「本轮做的是**核对**那一半」——
 * 而「核对那一半」的消费者当时并不存在。这条用例就是让那句话成立的守卫。
 *
 * ## 为什么走**真正的** `initialize()`，而不是反射调那个私有方法
 *
 * 反射调 `checkRecoveryOnStartup()` 只能证明「那个函数自己工作」，
 * **证明不了 `initialize()` 真的调了它** —— 而后者正是真缺陷 2 的内容
 * （把调用行删掉，反射驱动的用例照样绿，又是一条自我参照的空断言）。
 * 所以这里从**生产的启动入口**进去：`MainViewModel.initialize(context)`。
 *
 * 代价是 `initialize()` 还会拉起一串启动期动作（设置回读、异步初始化、Python 角色表），
 * 那些与本节无关；断言只落在核对结论与提示通道上，两者都由
 * `checkRecoveryOnStartup()` **同步**写入（它是普通函数调用，不在协程里），
 * 所以断言读到的是确定值，不依赖调度时机。
 *
 * ## 夹具的隔离（不碰设备上那个真 app 的任何状态）
 *
 * `ContextWrapper` 覆盖两处，都只为把状态引到临时目录：
 *
 * 1. `getFilesDir()` → 本次用例专属的 cache 子目录（`InstalledModRepository`
 *    唯一的外部依赖就是它，与 `ModSnapshotTest:38-41` 同款）；
 * 2. `getSharedPreferences(name, mode)` → 换一个**测试专属前缀**的名字。
 *    `initialize()` 里的 `restoreModSourceDirs()` 在没有已存目录时会回写一次
 *    空值，不隔离就会改到设备上真 app 的 `app_settings`。
 *
 * 两者一起保证：这条用例在设备上留下的一切都在自己的 cache 子目录里，
 * 由 [cleanupOwnFixture] 逐目录删掉。**不碰真实游戏目录**。
 */
@RunWith(AndroidJUnit4::class)
class LedgerRecoveryStartupTest {

    private val store = ViewModelStore()
    private var fixtureDir: File? = null

    /** T17：用指定 context 直接构造 MainViewModel（appContext 与仓储均构造注入）。 */
    private fun newViewModel(ctx: android.content.Context): MainViewModel {
        val character = CharacterRepository(ctx)
        val metadata = com.bd2toolsbox.data.repository.CharacterMetaRepository(ctx)
        val installed = InstalledModRepository(ctx)
        return MainViewModel(
            SavedStateHandle(), ctx, character, ModRepository(ctx, character),
            installed, BundleBackupRepository(ctx), PreviewCacheRepository(ctx),
            com.bd2toolsbox.core.engine.ChaquopyModEngine(),
            metadata,
            com.bd2toolsbox.data.repository.AvatarRepository(ctx, metadata),
            com.bd2toolsbox.data.repository.SpineRuntimeRepository(ctx),
            com.bd2toolsbox.data.repository.UpdateRepository(ctx),
            com.bd2toolsbox.feature.install.InstallExecutor(installed, com.bd2toolsbox.core.fileaccess.GameFileAccess.get()),
            com.bd2toolsbox.feature.install.UninstallExecutor(installed),
        )
    }

    @After
    fun cleanupOwnFixture() {
        store.clear()
        fixtureDir?.let { dir ->
            // 只删自己造的那一个（名字前缀 + 直接父目录是 cacheDir 双重校验）
            val base = InstrumentationRegistry.getInstrumentation().targetContext
            check(dir.name.startsWith(FIXTURE_PREFIX) && dir.parentFile == base.cacheDir) {
                "拒绝删除非本用例夹具的目录：${dir.absolutePath}"
            }
            dir.deleteRecursively()
        }
        fixtureDir = null
    }

    /**
     * 造一个干净的夹具目录与一个把 `filesDir` 指向它的 context。
     *
     * @param journalEntries 预先写进 `install_journal.json` 的条目（空 = 不写这个文件）。
     */
    private fun newViewModelWithFixture(journalEntries: List<JournalEntry>): MainViewModel {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(base.cacheDir, "$FIXTURE_PREFIX${UUID.randomUUID()}").apply { mkdirs() }
        fixtureDir = dir
        if (journalEntries.isNotEmpty()) {
            val journalStore = OperationJournalStore(File(dir, "install_journal.json"))
            journalEntries.forEach { entry ->
                assertNull("前提：夹具要能写进 journal", journalStore.append(entry))
            }
        }

        val wrapper = object : ContextWrapper(base) {
            override fun getFilesDir(): File = dir
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences(PREFIX_SHARED_PREFS + name, mode)
        }
        // T17：appContext 与仓储改构造注入；把 wrapper 作为 appContext 传进去，initialize() 无参。
        val vm = newViewModel(wrapper)
        store.put("t08r2-recovery", vm)
        vm.initialize()
        return vm
    }

    private fun replacedEntry(bundleName: String, vararg modUris: String) = JournalEntry(
        operationId = "op-t08r2",
        stage = JournalStage.TARGET_REPLACED.id,
        atMillis = 1_700_000_000_000L,
        targets = listOf(JournalTarget(bundleName, "hash-$bundleName", 4096L, modUris.toList()))
    )

    /**
     * **窗口真的裂开**：journal 说目标已替换、账本里没有对应的记录 →
     * `initialize()` 之后 `recoveryVerdict` 必须是 [OperationJournalStore.RecoveryVerdict.PendingReconciliation]，
     * 并且用户可见的提示通道被置位。
     *
     * 账本文件是**不存在**的（全新安装的形态），所以这里同时覆盖了
     * 「账本读不出来 ≠ 记着这条」这一格。
     */
    @Test
    fun startupSurfacesAPendingReconciliationAndTellsTheUser() {
        val vm = newViewModelWithFixture(
            listOf(replacedEntry("bundle-alpha", "uri-a", "uri-b"))
        )

        val verdict = vm.recoveryVerdict.value
        assertTrue(
            "启动核对必须报出「文件已换、账本未存」这个窗口，实际 $verdict",
            verdict is OperationJournalStore.RecoveryVerdict.PendingReconciliation
        )
        val pending = verdict as OperationJournalStore.RecoveryVerdict.PendingReconciliation
        assertEquals(
            "核对结论要指名是哪个 bundle —— 不然用户不知道该去查什么",
            listOf("bundle-alpha"), pending.bundleNames
        )
        assertEquals(listOf("uri-a", "uri-b"), pending.modUris)

        val notice = vm.ledgerResetNotice.value
        assertNotNull(
            "待核对状态必须走到**用户可见**的通道（LedgerResetDialog 渲染的就是它）——" +
                "只写进 StateFlow 里没人看等于没发生",
            notice
        )
        assertTrue(
            "给用户的话里要说清是哪个资源包（实际：$notice）",
            notice!!.contains("bundle-alpha")
        )
    }

    /**
     * **阳性对照**：同一套夹具、同一个入口，**没有** journal 时不许报待核对、不许弹提示。
     *
     * 没有这一条，上面那条断言可以在「`recoveryVerdict` 恒为待核对」的实现上照样通过
     * —— 那正是 T08 自己反复强调要避免的空转断言（见 `OperationRecoveryTest` 里
     * `replacedTargetMissing…` 与 `replacedTargetPresentInLedgerIsConsistent` 的配对）。
     */
    @Test
    fun startupWithNoJournalStaysConsistentAndSaysNothing() {
        val vm = newViewModelWithFixture(emptyList())

        assertEquals(
            "没有进行中的提交时必须是一致，不是待核对",
            OperationJournalStore.RecoveryVerdict.Consistent,
            vm.recoveryVerdict.value
        )
        assertNull(
            "没有窗口时不许拿一条提示去吓用户（否则用户会学会忽略这个对话框）",
            vm.ledgerResetNotice.value
        )
    }

    /** 无内容证据的 TARGET_REPLACED：账本里已有记录也不能把窗口判成一致。 */
    @Test
    fun startupWithLegacyReplacedAndLedgerStillPending() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(base.cacheDir, "$FIXTURE_PREFIX${UUID.randomUUID()}").apply { mkdirs() }
        fixtureDir = dir
        File(dir, "installed_mods.json").writeText(
            """{"schemaVersion":1,"records":{""" +
                """"uri-a":{"modUri":"uri-a","modName":"A","familyKey":"f","snapshotTargetHash":"bundle-alpha","quality":"HD","usedAstc":false,"installedAt":1},""" +
                """"uri-b":{"modUri":"uri-b","modName":"B","familyKey":"f","snapshotTargetHash":"bundle-alpha","quality":"HD","usedAstc":false,"installedAt":1}""" +
                """}}"""
        )
        val journalStore = OperationJournalStore(File(dir, "install_journal.json"))
        assertNull(journalStore.append(replacedEntry("bundle-alpha", "uri-a", "uri-b")))

        val wrapper = object : ContextWrapper(base) {
            override fun getFilesDir(): File = dir
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences(PREFIX_SHARED_PREFS + name, mode)
        }
        val vm = newViewModel(wrapper)
        store.put("t08r2-recovery-2", vm)
        vm.initialize()

        assertTrue(
            "缺本次内容证明时不能仅靠账本放行，实际 ${vm.recoveryVerdict.value}",
            vm.recoveryVerdict.value is OperationJournalStore.RecoveryVerdict.PendingReconciliation
        )
        assertNotNull("待核对必须仍提示用户去核对并修复", vm.ledgerResetNotice.value)
    }

    @Test
    fun startupWithOwnLedgerCommitStaysConsistent() {
        val vm = newViewModelWithFixture(
            listOf(
                replacedEntry("bundle-alpha", "uri-a"),
                JournalEntry(
                    operationId = "op-t08r2",
                    stage = JournalStage.LEDGER_COMMITTED.id,
                    atMillis = 1_700_000_000_001L,
                    targets = listOf(JournalTarget("bundle-alpha", "hash-bundle-alpha", 4096L, listOf("uri-a")))
                )
            )
        )
        assertEquals(
            OperationJournalStore.RecoveryVerdict.Consistent,
            vm.recoveryVerdict.value
        )
        assertNull(vm.ledgerResetNotice.value)
    }

    private companion object {
        const val FIXTURE_PREFIX = "t08r2-recovery-"
        const val PREFIX_SHARED_PREFS = "t08r2-test-"
    }
}
