package com.bd2toolsbox.service

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bd2toolsbox.core.storage.BackupProvenance
import com.bd2toolsbox.core.storage.BaselineSource
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
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * **「一个同尺寸的 mod 备份不得被当作重打包基底」** —— T09 真缺陷 3 的设备侧守卫。
 *
 * ## 它守的是哪个真缺陷
 *
 * T09 首版 `MainViewModel.tryUseBackup` 的 KDoc 声称：
 *
 * > 现在备份要能当基底，必须带 sidecar 且其来源属于 **BYTE_ANCHORED**。
 *
 * 而紧接的代码用的是 `trustedBaseline` —— 它**不筛来源强度**，`GAME_DIR` 照样放行。
 * 复核者的攻击当场证伪（runId `20260918-031658`，`expected:<UNKNOWN> but was:<PRISTINE>`）。
 * 后果是 T09 承诺消灭的形态在这条路径上没被消灭：
 *
 * 1. 一个**同尺寸、内容与官方不同**的 mod 躺在游戏目录；
 * 2. 扫描层看不到游戏目录的字节（在别的应用的 `data` 下），只能按
 *    `size == catalog.size` 判它 PRISTINE；
 * 3. `backupPristineOriginal` 于是以 `GAME_DIR` 来源把它存成「可信」备份
 *    （`deriveCatalogVerified` 对 GAME_DIR 比的正是「产物尺寸 == catalog 登记值」——
 *    与第 2 步同一条判据，**循环**）；
 * 4. `tryUseBackup` 拿它当干净基底 —— 旧 mod 的内容被原样带进新产物。
 *
 * ## 为什么这条在**设备**上跑而不是 JVM
 *
 * `tryUseBackup` 是 [MainViewModel] 的私有方法，而 `MainViewModel` 在 JVM 单测里
 * **构造不了**（构造链要 `SavedStateHandle` + Android 运行时；本仓无 Robolectric ——
 * 实测 `IllegalStateException`，见 `T09.md` §11）。T07 已交付的设备用例
 * （[BackupFailureSummaryTest]）用的正是同一套反射接缝，这里沿用它。
 *
 * ## 为什么它是**真**守卫（不是又一条空转断言）
 *
 * 它驱动的是**生产方法本身**，而不是复制一份判定逻辑：夹具把「一份 GAME_DIR 来源、
 * 采集时过了 catalog 锚点的备份」真的写进磁盘，然后直接调 `tryUseBackup`。
 *
 * - 若有人把它改回 `trustedBaseline` → `GAME_DIR` 被放行 → `classifyBackup` 因摘要
 *   相符判 PRISTINE → `tryUseBackup` **返回路径** → 断言 `assertNull` **红**。
 * - 把 `GAME_DIR` 加进 `BYTE_ANCHORED` 也是同样的红。
 *
 * 阳性对照在 [aByteAnchoredBackupIsStillUsableAsARepackBase]：同一套夹具只把来源换成
 * `RESTORE_PRODUCT`（被字节锚定），`tryUseBackup` 就必须**接受**它 ——
 * 否则上面那条的 null 可能只是「这个方法恒返回 null」。
 *
 * ## 只读约定
 *
 * 全程只写 app 自己的 `cacheDir` 与 `getExternalFilesDir(null)/origin_backup`
 * （应用私有目录），**不碰真实游戏目录**；夹具用随机 bundle 名，`finally` 里清干净。
 */
@RunWith(AndroidJUnit4::class)
class TryUseBackupTrustGateTest {

    private val store = ViewModelStore()
    private val ctx0 = InstrumentationRegistry.getInstrumentation().targetContext
    // T17：appContext 与仓储改构造注入。backupRepo 保留引用供 seedBackup 复用。
    private val backupRepo = BundleBackupRepository(ctx0)
    private val vm: MainViewModel = run {
        val character = CharacterRepository(ctx0)
        val metadata = com.bd2toolsbox.data.repository.CharacterMetaRepository(ctx0)
        MainViewModel(
            SavedStateHandle(), ctx0, character, ModRepository(ctx0, character),
            InstalledModRepository(ctx0), backupRepo, PreviewCacheRepository(ctx0),
            com.bd2toolsbox.core.engine.ChaquopyModEngine(),
            metadata,
            com.bd2toolsbox.data.repository.AvatarRepository(ctx0, metadata),
            com.bd2toolsbox.data.repository.SpineRuntimeRepository(ctx0),
            com.bd2toolsbox.data.repository.UpdateRepository(ctx0),
        )
    }

    private val tryUseBackup = MainViewModel::class.java
        .getDeclaredMethod("tryUseBackup", android.content.Context::class.java, String::class.java)
        .apply { isAccessible = true }

    private val gameBundleHashesField = MainViewModel::class.java
        .getDeclaredField("gameBundleHashes").apply { isAccessible = true }

    private val ctx = ctx0

    /** 夹具造出来的东西，`finally` 里按这个清单清。 */
    private val cleanupDirs = mutableListOf<File>()

    init {
        store.put("t09-try-use-backup", vm)
        // appContext 与 backupRepository 已在构造时注入。
    }

    @After
    fun tearDown() {
        store.clear()
        cleanupDirs.forEach { runCatching { it.deleteRecursively() } }
    }

    /**
     * 造一份 `source` 来源、`catalogSize == 字节数` 的备份，并把 `gameBundleHashes`
     * 指到它 —— 于是 `tryUseBackup` 会走到「读备份 → 验基准」那一段。
     *
     * @return 备份文件的字节数（调用方用它拼 catalog 登记值）
     */
    private fun seedBackup(bundleName: String, hashDir: String, size: Int, source: BaselineSource): File {
        val repo = backupRepo  // vm 构造时注入的同一仓储
        val srcDir = File(ctx.cacheDir, "t09gate/$bundleName").apply { mkdirs() }
        cleanupDirs += srcDir
        val src = File(srcDir, "__data").apply { writeBytes(ByteArray(size) { 0x5A }) }
        // GAME_DIR 与 CATALOG_DOWNLOAD 的 catalogVerified 规则是「产物尺寸 == catalog」；
        // RESTORE_PRODUCT 比的是输入 —— 三种都让它在自己的规则下成立（= 采集时真过了锚点）。
        val provenance = when (source) {
            BaselineSource.RESTORE_PRODUCT -> BackupProvenance(
                source = source, catalogSize = size.toLong(), anchoredInputSize = size.toLong(),
            )
            else -> BackupProvenance(source = source, catalogSize = size.toLong())
        }
        repo.saveBackup(bundleName, hashDir, src, provenance)
        cleanupDirs += File(ctx.getExternalFilesDir(null), "origin_backup/$bundleName")

        // 前提自检：这份备份在「来源是否被字节锚定」之外，必须**确实**是可信的。
        // 否则下面的 assertNull 可能只是因为连 trustedBaseline 都拿不到基准。
        assertNotNull(
            "前提：夹具的备份必须在 trustedBaseline 层面有基准（采集时真的过了 catalog 锚点）",
            repo.trustedBaseline(bundleName, hashDir),
        )

        @Suppress("UNCHECKED_CAST")
        gameBundleHashesField.set(vm, mapOf(bundleName to hashDir))
        return repo.backupFile(bundleName, hashDir)
    }

    /**
     * **真缺陷 3 的核心断言**：`GAME_DIR` 来源的备份（= 一个同尺寸 mod 被扫描层判成
     * PRISTINE 之后存下来的那一份）**不得**被当作重打包基底。
     */
    @Test
    fun aGameDirBackupMustNotBeUsableAsARepackBase() {
        val bundleName = "t09gate-gamedir-${java.util.UUID.randomUUID()}"
        val hashDir = "hash-${java.util.UUID.randomUUID()}"
        seedBackup(bundleName, hashDir, size = 1168, source = BaselineSource.GAME_DIR)

        val used = tryUseBackup.invoke(vm, ctx, bundleName)

        assertNull(
            "GAME_DIR 来源的备份只证明「采集时 name+size 对得上」——它不得当重打包基底。" +
                    "返回非 null 说明 tryUseBackup 用的是不筛来源的 trustedBaseline" +
                    "（真缺陷 3 回来了）",
            used,
        )
    }

    /**
     * **阳性对照**：换成被字节锚定的来源（`RESTORE_PRODUCT`，即卸载时由 CDN 字节
     * 确定性派生的产物），同一套夹具下 `tryUseBackup` 必须**接受**它。
     *
     * 没有这一条，上面那条的「返回 null」可能只是「`tryUseBackup` 恒失败」——
     * 那正是 T07 一轮缺陷的同一形状（断言看着在守、实际守不住）。
     */
    @Test
    fun aByteAnchoredBackupIsStillUsableAsARepackBase() {
        val bundleName = "t09gate-restore-${java.util.UUID.randomUUID()}"
        val hashDir = "hash-${java.util.UUID.randomUUID()}"
        val backup = seedBackup(
            bundleName, hashDir, size = 1162, source = BaselineSource.RESTORE_PRODUCT,
        )

        val used = tryUseBackup.invoke(vm, ctx, bundleName)

        assertNotNull(
            "被字节锚定的派生产物（卸载场景）必须仍能当基底 —— 否则这条链断了",
            used,
        )
        assertEquals(
            "接受的必须是那份备份的字节（拷进 cache 的那一份）",
            backup.readBytes().toList(),
            File(used as String).readBytes().toList(),
        )
    }
}
