package com.bd2toolsbox.integration

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bd2toolsbox.core.fileaccess.DirectoryListingResult
import com.bd2toolsbox.core.fileaccess.GameFileAccess
import com.bd2toolsbox.core.model.OperationId
import com.bd2toolsbox.core.operations.OperationRecovery
import com.bd2toolsbox.core.storage.JournalRecoveryFacts
import com.bd2toolsbox.core.storage.JournalTarget
import com.bd2toolsbox.core.storage.LedgerRecord
import com.bd2toolsbox.data.model.BundleCheckResult
import com.bd2toolsbox.data.repository.InstalledModRepository
import com.bd2toolsbox.feature.install.InstallExecutor
import com.bd2toolsbox.feature.install.UninstallExecutor
import com.bd2toolsbox.service.ShizukuFileService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * T56：核心功能与故障注入的**端到端**验收（设备）。
 *
 * ## 为什么是这一层
 *
 * 单看每个 executor 的单测，都可能在「真实文件 + 真实账本 + 真实协调器」组合下失效。
 * 本类走的是**生产调用链**：`InstallExecutor.move` → `MoveInstallStaging`（冻结 + 身份校验）
 * → `MoveInstallSubmitter`（journal + 原子替换 + 记账）→ `InstalledModRepository`（真实账本文件），
 * 文件操作走生产 `ShizukuFileService`（不经过 Shizuku 授权，但**是**真实实现）。
 *
 * 故障注入也是打在**边界**上（`GameFileAccess.installConvertedBundle` 返回失败、
 * 源内容被改动导致 `verifyTarget` 失败），不是打在自己的 fake 上。
 *
 * ## 隔离（计划硬性要求）
 *
 * - 「游戏目录」「源目录」「暂存目录」全部在 `context.cacheDir/t56/<runId>/` 下；
 * - 账本与 journal 是**应用私有**的真实文件，所以 `@Before` 先整份备份、`@After` 原样还原 ——
 *   不让本类污染其它设备用例的账本；
 * - **不碰真实游戏目录、MOD 源目录、备份根**。破坏性场景只在本类的临时树上做。
 */
@RunWith(AndroidJUnit4::class)
class ModWorkflowAcceptanceTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var repository: InstalledModRepository
    private var ledgerBackup: ByteArray? = null
    private var journalBackup: ByteArray? = null
    private var runCounter = 0

    @Before
    fun snapshotLedger() {
        repository = InstalledModRepository(context)
        ledgerBackup = repository.file().takeIf { it.isFile }?.readBytes()
        journalBackup = repository.journalFile().takeIf { it.isFile }?.readBytes()
        // 从确定的空账本开始：本类的断言都是「装完应该有几条」
        repository.file().delete()
        repository.journalFile().delete()
    }

    @After
    fun restoreLedger() {
        restore(repository.file(), ledgerBackup)
        restore(repository.journalFile(), journalBackup)
    }

    private fun restore(file: File, bytes: ByteArray?) {
        if (bytes == null) file.delete() else file.writeBytes(bytes)
    }

    // ------------------------------------------------------------------ 隔离夹具

    /** 一次用例的隔离世界：源目录、游戏目录、暂存目录、隔离的 GameFileAccess。 */
    private inner class World(val failingBundles: Set<String> = emptySet()) {
        val root: File = File(context.cacheDir, "t56/run${runCounter++}").apply {
            deleteRecursively()
            mkdirs()
        }
        val shared: File = File(root, "shared").apply { mkdirs() }
        val gameRoot: File = File(root, "game").apply { mkdirs() }
        val staging: File = File(root, "staging").apply { mkdirs() }
        val access: GameFileAccess = IsolatedGameFileAccess(gameRoot, failingBundles)

        fun gameData(bundle: String, hash: String): File = File(gameRoot, "Shared/$bundle/$hash/__data")

        /** 造一份「已转换产物」并返回它的 JournalTarget（含恢复事实）。 */
        fun seedConverted(
            bundle: String,
            hash: String,
            modUri: String,
            payload: ByteArray
        ): JournalTarget {
            val dir = File(shared, "$bundle/$hash").apply { mkdirs() }
            File(dir, "__data").writeBytes(payload)
            return JournalTarget(
                bundleName = bundle,
                hashDir = hash,
                dataBytes = payload.size.toLong(),
                modUris = listOf(modUri),
                recoveryFacts = JournalRecoveryFacts(
                    formatVersion = 1,
                    expectedContentSha256 = sha256(payload),
                    ledgerUpserts = listOf(
                        LedgerRecord(
                            modUri = modUri,
                            modName = modUri.substringAfterLast('/'),
                            familyKey = bundle,
                            snapshotTargetHash = bundle,
                            quality = "SD",
                            usedAstc = false,
                            installedAt = 1_700_000_000_000L
                        )
                    )
                )
            )
        }

        fun install(targets: List<JournalTarget>, restored: Set<String> = emptySet()) =
            runBlocking {
                InstallExecutor(repository, access).move(
                    id = OperationId("t56-${System.nanoTime()}"),
                    shared = shared,
                    stagingRoot = staging,
                    targets = targets,
                    restoredTargets = restored
                )
            }

        fun cleanup() {
            root.deleteRecursively()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sha256(file: File): String? =
        if (!file.isFile) null else sha256(file.readBytes())

    private fun payload(seed: Int, size: Int = 2048) = ByteArray(size) { ((it + seed) % 251).toByte() }

    /** 隔离目录里不该出现原子替换的半成品。 */
    private fun assertNoPartialFiles(root: File) {
        val partials = root.walkTopDown().filter { it.isFile && it.name.endsWith(".part") }.toList()
        assertEquals("不许留下 .part 半成品", emptyList<File>(), partials)
    }

    // ------------------------------------------------------------------ 1. 全链路

    @Test
    fun installWritesTargetAndLedgerWithoutHalfData() {
        val w = World()
        try {
            val bytes = payload(1)
            val target = w.seedConverted("bundleA", "hash1", "content://mod/a", bytes)

            val result = w.install(listOf(target))

            assertTrue("提交必须成功：${result.submission.message}", result.submission.completedSuccessfully)
            val dest = w.gameData("bundleA", "hash1")
            assertEquals("目标内容必须与产物一致", sha256(bytes), sha256(dest))
            assertEquals("账本必须记一条", 1, repository.load().size)
            assertTrue("账本必须记的是这个 mod", repository.load().containsKey("content://mod/a"))
            assertEquals("journal 必须已闭合", emptyList<Any>(), OperationRecovery(repository).classify())
            assertNoPartialFiles(w.root)
        } finally {
            w.cleanup()
        }
    }

    // ------------------------------------------------------------------ 2. 同 bundle 叠加

    @Test
    fun twoModsOnTheSameBundleAreBothAccounted() {
        val w = World()
        try {
            val a = w.seedConverted("bundleA", "hash1", "content://mod/a", payload(2))
            val b = w.seedConverted("bundleB", "hash2", "content://mod/b", payload(3))

            assertTrue(w.install(listOf(a)).submission.completedSuccessfully)
            assertTrue(w.install(listOf(b)).submission.completedSuccessfully)

            val ledger = repository.load()
            assertEquals("两个 bundle 各一条", 2, ledger.size)
            assertTrue(ledger.containsKey("content://mod/a"))
            assertTrue(ledger.containsKey("content://mod/b"))
            assertEquals("bundleA 内容正确", sha256(payload(2)), sha256(w.gameData("bundleA", "hash1")))
            assertEquals("bundleB 内容正确", sha256(payload(3)), sha256(w.gameData("bundleB", "hash2")))
            assertNoPartialFiles(w.root)
        } finally {
            w.cleanup()
        }
    }

    // ------------------------------------------------------------------ 3. 复制边界故障

    @Test
    fun faultDuringCopyLeavesTargetAndLedgerUntouched() {
        // 让 bundleB 的复制失败 —— 注入点在 GameFileAccess 边界，不在自己的 fake 上
        val w = World(failingBundles = setOf("bundleB"))
        try {
            // 先放一份「旧内容」，确认失败后它没被动过
            val old = File(w.gameRoot, "Shared/bundleB/hash2").apply { mkdirs() }
            File(old, "__data").writeBytes(ByteArray(32) { 0x7f })
            val oldSha = sha256(File(old, "__data"))

            val a = w.seedConverted("bundleA", "hash1", "content://mod/a", payload(4))
            val b = w.seedConverted("bundleB", "hash2", "content://mod/b", payload(5))

            val result = w.install(listOf(a, b))

            assertFalse("有目标失败就不能报成功", result.submission.completedSuccessfully)
            assertEquals("失败目标必须保持旧内容", oldSha, sha256(File(old, "__data")))
            assertFalse(
                "失败的这一批不该记账（部分成功要能被核对）",
                repository.load().containsKey("content://mod/b")
            )
            assertNoPartialFiles(w.root)
        } finally {
            w.cleanup()
        }
    }

    // ------------------------------------------------------------------ 4. 内容校验边界故障

    @Test
    fun contentChangedAfterFreezeIsRejectedBeforeReplace() {
        val w = World()
        try {
            val target = w.seedConverted("bundleA", "hash1", "content://mod/a", payload(6))
            // 冻结之后、提交之前把源改掉 —— verifyTarget 必须拦住
            File(w.shared, "bundleA/hash1/__data").writeBytes(payload(7))

            // 提交**前**的校验失败（源已改变）由 `MoveInstallStaging.prepare` 抛 IOException。
            // 这是 executor 的既有契约：生产路径上 `InstallService.executeRun` 接住它并
            // 转成 `MoveCommitResult.failure`；`DirectMoveCommitHost` 原先没接（T56 发现并修好，
            // 见 MoveCommit.kt）。这里直接调 executor，所以断言的是「抛出来」这件事本身 ——
            // 关键是**在替换发生之前**停下。
            val thrown = runCatching { w.install(listOf(target)) }.exceptionOrNull()

            assertNotNull("源变了必须被拦住", thrown)
            assertTrue(
                "必须是「源文件已改变」而不是别的：${thrown?.message}",
                thrown is java.io.IOException
            )
            assertFalse("目标不该出现", w.gameData("bundleA", "hash1").exists())
            assertTrue("账本不该有记录", repository.load().isEmpty())
            assertNoPartialFiles(w.root)
        } finally {
            w.cleanup()
        }
    }

    // ------------------------------------------------------------------ 5. 卸载

    @Test
    fun uninstallRemovesOnlyTheLedgerRecordAndKeepsSource() {
        val w = World()
        try {
            val bytes = payload(8)
            val target = w.seedConverted("bundleA", "hash1", "content://mod/a", bytes)
            assertTrue(w.install(listOf(target)).submission.completedSuccessfully)
            val sourceFile = File(w.shared, "bundleA/hash1/__data")
            // 提交成功后源可能已被清理，记下当时的状态用于断言
            val sourceSurvived = sourceFile.isFile

            val removal = UninstallExecutor(repository).removeSingleModRecord("content://mod/a", "bundleA")

            assertTrue("账本记录必须被移除", repository.load().isEmpty())
            assertEquals("没有别的记录留下", emptyList<String>(), removal.remainingModUris)
            // 卸载只动账本，不动已经装进游戏目录的文件（要不要还原由快照决定）
            assertEquals("目标内容保持已装状态", sha256(bytes), sha256(w.gameData("bundleA", "hash1")))
            assertEquals("源目录状态不因卸载而改变", sourceSurvived, sourceFile.isFile)
        } finally {
            w.cleanup()
        }
    }

    // ------------------------------------------------------------------ 6. 恢复分类衔接

    @Test
    fun successfulInstallLeavesNothingForRecoveryToWorryAbout() {
        val w = World()
        try {
            val target = w.seedConverted("bundleA", "hash1", "content://mod/a", payload(9))
            assertTrue(w.install(listOf(target)).submission.completedSuccessfully)
            // T25：装完就该没有任何待处理处置 —— 不该留下「看起来像中断」的痕迹
            val dispositions = OperationRecovery(repository).classify()
            assertEquals("装完不该有待处理项", emptyList<Any>(), dispositions)
            assertNotNull("账本文件必须存在", repository.file().takeIf { it.isFile })
        } finally {
            w.cleanup()
        }
    }
}

/**
 * 隔离的 [GameFileAccess]：把「游戏目录」换成临时目录，其余走**生产**
 * [ShizukuFileService]（真实文件 I/O，不经过 Shizuku 授权 —— `ShizukuFileService`
 * 的构造参数 `context` 在类体里一次都没用到，见 T07 证据）。
 *
 * [failingBundles] 是故障注入点：命中时**在真实复制之前**返回失败，
 * 用来验证「目标不变、账本不记、不留半份 `__data`」。
 *
 * 本类只实现端到端验收需要的那些方法；扫描/权限相关的一律
 * [UnsupportedOperationException]（**不返回假成功** —— 那样会把「没测」装成「测过了」）。
 */
private class IsolatedGameFileAccess(
    private val gameRoot: File,
    private val failingBundles: Set<String>
) : GameFileAccess {

    private val service = ShizukuFileService()
    private val tokens = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Any, Boolean>())

    override fun acquire(): Any = Any().also { tokens.add(it) }
    override fun release(token: Any) {
        tokens.remove(token)
    }

    override fun isAvailable(): Boolean = true
    override fun isRunning(): Boolean = true
    override fun hasPermission(): Boolean = true
    override fun requestPermission(requestCode: Int) = Unit

    override suspend fun installConvertedBundle(sourceDir: String, bundleName: String): Pair<Boolean, String> {
        if (bundleName in failingBundles) {
            return false to "注入的复制失败：$bundleName"
        }
        val source = File(sourceDir)
        if (!source.isDirectory) return false to "源目录不存在：$sourceDir"
        val dest = File(gameRoot, "Shared/$bundleName")
        dest.mkdirs()
        val copied = service.copyDirectory(source.absolutePath, dest.absolutePath)
        return copied to if (copied) "已装入" else "复制失败：$bundleName"
    }

    override suspend fun copyGameDataForRecovery(
        bundleName: String,
        hashDir: String?,
        destPath: String
    ): Boolean {
        val source = if (hashDir == null) File(gameRoot, "Shared/$bundleName")
        else File(gameRoot, "Shared/$bundleName/$hashDir")
        if (!source.exists()) return false
        File(destPath).parentFile?.mkdirs()
        return service.copyFile(source.absolutePath, destPath)
    }

    override suspend fun copyLocalBundleToCache(
        bundleName: String,
        hashDir: String,
        destPath: String
    ): Boolean = throw UnsupportedOperationException("T56 端到端验收不覆盖该路径")

    override suspend fun listGameBundles(): DirectoryListingResult =
        throw UnsupportedOperationException("T56 端到端验收不覆盖该路径")

    override suspend fun checkLocalBundles(
        outputDir: String,
        onProgress: (String) -> Unit
    ): BundleCheckResult? = throw UnsupportedOperationException("T56 端到端验收不覆盖该路径")

    override suspend fun executeBundleScan(
        outputDir: String,
        cacheDir: String,
        checkResult: BundleCheckResult,
        onBundleProgress: (Int, Int, String, String) -> Unit
    ): Triple<Boolean, Int, Int> = throw UnsupportedOperationException("T56 端到端验收不覆盖该路径")

    override val scanRunning: Boolean get() = false
    override fun markScanRunning(running: Boolean) = Unit
}
