package com.bd2toolsbox.data.repository

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bd2toolsbox.core.storage.ModSnapshotStore
import com.bd2toolsbox.data.model.ModInfo
import com.bd2toolsbox.data.model.ModInstallState
import com.bd2toolsbox.data.model.ModKind
import com.bd2toolsbox.data.model.ResolutionState
import com.bd2toolsbox.ui.viewmodel.MainViewModel
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * `MainViewModel` 的**快照调用点必须复用同一个 Store 实例**（T10 二轮，复核真缺陷 3）。
 *
 * ## 这条用例为什么必须存在
 *
 * T10 实现期自查出一个真缺陷：`snapshotStore()` 起初写成 `get() = ModSnapshotStore(...)`
 * —— **每次访问都新建实例**。`capture` 分配的 `revision` 是 Store 的**实例状态**，
 * 于是 `capture` 与 `commit` 落在两个实例上，后者的计数器还是 0 →
 * **每一次提交都被判成 `Skipped`，快照永远写不出去**，而且 debug 下完全看不出来
 * （没有异常，只有一条 `Log.i`）。
 *
 * T10 首版声称「JVM 侧补了两条对照用例钉住这条不变式」—— **二轮复核证明这句话不成立**：
 * 那两条（`ModSnapshotStoreTest.pendingWriteIsOnlyHonouredByItsOriginatingInstance` 等）
 * 守的是 **Store 自己的 API 语义**，把 `MainViewModel.snapshotStore()` 改回每次新建之后
 * **全量 JVM 套件 221 条全绿**。JVM 上构造不了 `MainViewModel`（无 Robolectric），
 * 所以这条不变式在交付态里**没有任何自动化用例守卫**。本用例补上那个守卫。
 *
 * ## 它驱动的是**生产入口本身**，不是被搬走的私有方法
 *
 * [MainViewModel.deleteAbnormalMods] 是公开的生产方法，它在收尾的 `finally` 里
 * **无条件**执行 `captureModListSnapshot(...)` 紧接着 `saveModListSnapshot(...)`
 * （即便一条都没删成 —— 合成的 uri 没有匹配的 provider，删除必然失败，但收尾照跑）。
 * 这两步在健康实现里命中**同一个** Store 实例，`commit` 的 revision 闸通过 →
 * **快照文件落到磁盘上**。
 *
 * 把 `snapshotStore()` 改回「每次新建实例」之后：`capture` 在实例 A 上把 revision 推到 1，
 * `commit` 落在实例 B 上（revision 还是 0）→ `1 != 0` → `Skipped` → **文件根本不出现** →
 * 本用例红。断言的对象是**磁盘上的文件**，不是任何内存状态。
 *
 * ## 为什么**不**走 `initialize()`
 *
 * 二轮实测：在设备上跑 `initialize()` 会让 `startAsyncInitialization()`
 * → `ShizukuManager.checkLocalBundles()` → Chaquopy `PyObject.fromJavaNative` 抛
 * `UnsatisfiedLinkError` 并**打死整个 instrumentation 进程**。那会让「红」变成
 * 「进程崩溃」这种无法归因的形状（本用例首版就踩到了：注入攻击时崩在 Chaquopy，
 * 而不是崩在被测断言上）。所以这里只做最小接线：把 `appContext` 指到夹具目录，
 * 然后直接驱动生产写路径。
 *
 * ## 反射只用在一处，且与仓内既有范本同款
 *
 * `appContext` 是 `private var`，除了 `initialize()` 没有别的赋值点；
 * 这里用与 `TryUseBackupTrustGateTest:80-82`、`LedgerRecoveryStartupTest` 相同的做法
 * 反射写这一个字段。**被测的调用点本身（`deleteAbnormalMods` → capture/commit）
 * 走的是生产代码，没有任何反射** —— 这与裁定 C「去掉 `ModSnapshotTest` 里的反射」
 * 不冲突：那条针对的是**用反射去够被测方法**，而这里够的只是依赖注入。
 *
 * ## 不碰真实游戏目录
 *
 * `ContextWrapper` 覆盖 `filesDir` → 本用例专属的 cache 子目录，并覆盖
 * `getSharedPreferences` 换一个测试专属前缀（`restoreSettings` 之类不在本路径上，
 * 但保险起见隔离掉）。留下的一切都在 [cleanupOwnFixture] 里按
 * 「前缀 + 直接父目录是 cacheDir」双重校验后删掉。
 */
@RunWith(AndroidJUnit4::class)
class SnapshotStoreInstanceReuseTest {

    private val store = ViewModelStore()
    private var fixtureDir: File? = null

    // T17：appContext 与仓储改为构造注入。测试直接用指定 context 构造 VM（不再反射 set）。
    private fun newViewModel(ctx: Context): MainViewModel {
        val character = CharacterRepository(ctx)
        val metadata = CharacterMetaRepository(ctx)
        val installed = InstalledModRepository(ctx)
        return MainViewModel(
            SavedStateHandle(),
            ctx,
            character,
            ModRepository(ctx, character),
            installed,
            BundleBackupRepository(ctx),
            PreviewCacheRepository(ctx),
            com.bd2toolsbox.core.engine.ChaquopyModEngine(),
            metadata,
            com.bd2toolsbox.data.repository.AvatarRepository(ctx, metadata),
            com.bd2toolsbox.data.repository.SpineRuntimeRepository(ctx),
            com.bd2toolsbox.data.repository.UpdateRepository(ctx),
            com.bd2toolsbox.feature.install.InstallExecutor(installed, com.bd2toolsbox.core.fileaccess.GameFileAccess.get()),
            com.bd2toolsbox.feature.install.UninstallExecutor(installed),
            com.bd2toolsbox.feature.mods.ScanExecutor(com.bd2toolsbox.core.fileaccess.GameFileAccess.get()),
            com.bd2toolsbox.feature.preview.PrepackExecutor(com.bd2toolsbox.data.repository.PreviewCacheRepository(ctx)),
        )
    }

    @Before
    fun setUp() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        fixtureDir = File(base.cacheDir, "$FIXTURE_PREFIX${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After
    fun cleanupOwnFixture() {
        store.clear()
        fixtureDir?.let { dir ->
            val base = InstrumentationRegistry.getInstrumentation().targetContext
            check(dir.name.startsWith(FIXTURE_PREFIX) && dir.parentFile == base.cacheDir) {
                "拒绝删除非本用例夹具的目录：${dir.absolutePath}"
            }
            dir.deleteRecursively()
        }
        fixtureDir = null
    }

    /**
     * 驱动一次生产写路径，断言**快照真的落到了磁盘上**。
     *
     * 破坏它 = 把 `MainViewModel.snapshotStore()` 改回 `get() = ModSnapshotStore(...)`
     * （每个调用点一个新实例）→ 提交被判 `Skipped` → 文件不出现 → 红。
     */
    @Test
    fun theSnapshotCallSitesShareOneStoreInstanceSoCommitActuallyLands() {
        val dir = fixtureDir!!
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val snapshotFile = File(dir, ModSnapshotStore.SNAPSHOT_FILENAME)

        // 接线：把 appContext 指到夹具目录（initialize() 在生产里做的就是这一件事；
        // 这里不走 initialize() 的原因见类注释「为什么不走 initialize()」）。
        // snapshotStore() 只从 appContext.filesDir 取路径，所以构造时传这个 context 就够了。
        val vm = newViewModel(object : ContextWrapper(base) {
            override fun getFilesDir(): File = dir
        })
        store.put("t10r2-store-reuse", vm)

        assertTrue("前提：夹具目录里此刻不该有快照", !snapshotFile.exists())

        // 合成条目：源目录为空 → 不是任何源目录的根 → 一定进 pending、一定跑到收尾的
        // capture/commit。它没有匹配的 provider，删除必然失败 —— 但那不影响断言。
        val victim = ModInfo(
            name = "合成条目", character = "Eclipse", costume = "Beach", type = "cutscene",
            isEnabled = false, uri = Uri.parse("content://test/tree/t/document/synthetic"),
            targetHashedName = null, isDirectory = true, resolutionState = ResolutionState.KNOWN,
            installState = ModInstallState.NOT_INSTALLED, kind = ModKind.PC_SOURCE
        )
        vm.deleteAbnormalMods(base, listOf(victim))

        // 等落盘：capture → commit 走 viewModelScope 上的 IO。断言的对象是磁盘上的文件本身。
        val deadline = System.currentTimeMillis() + 10_000
        while (!snapshotFile.exists() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }

        assertTrue(
            "快照调用点必须复用同一个 Store 实例，否则 commit 会被 revision 闸判 Skipped、" +
                "文件永远写不出来。期待 ${snapshotFile.absolutePath} 出现，实际不存在。" +
                "（把 MainViewModel.snapshotStore() 改成每次新建实例即可复现）",
            snapshotFile.exists()
        )
        assertTrue("落盘的快照必须非空", snapshotFile.length() > 0)
    }

    private companion object {
        const val FIXTURE_PREFIX = "t10r2-store-reuse-"
    }
}
