package com.bd2toolsbox.data.repository

import android.content.Context
import com.bd2toolsbox.core.storage.JournalTarget
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 「统一并发所有者」——**跨实例**的在途门禁与 RMW 归属（T08 剩余要求 1）。
 *
 * ## 这一组守什么
 *
 * 原计划要的是「写游戏文件之后那段 read-modify-write 有单一所有者」。现有实现的锁与
 * `currentOperationId` 都是**实例级**：两个 `InstalledModRepository` 指向同一 `filesDir`
 * （同进程重复初始化、或未来多进程）时互不可见 —— A 开着一次提交，B 照样能再开一次，
 * 于是两次「读账本→改一条→写回」交错，后写的把先写的盖掉（丢记录）。
 *
 * 这一组用**同一个 filesDir 上的两个实例**把这条门禁钉死。它是**确定性**用例
 * （不靠 sleep / 概率竞速）：先手动 begin 占位，再断言另一个实例被拒、journal 逐字节不变。
 * 末尾附一条并发压力用例，但它**只是佐证**，不替代上面的确定性门禁。
 *
 * ## 桩的边界（与 `InstalledModRepositoryLedgerTest` 同口径）
 *
 * 只桩 `Context.filesDir` 与 `android.util.Log` 静态方法，其余走真实文件与真实线程。
 *
 * **注意**：这里的「另一个实例」是同进程 new 出来的第二个对象，**不声称**真实进程重启
 * （journal 从磁盘重读是真的，但内存所有权是否共享正是被测点）。
 */
class InstalledModRepositoryOwnershipTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var filesDir: File

    @Before
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0
        filesDir = temp.newFolder("files")
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    /** 指向给定目录的仓储实例。[dir] 允许传别名路径（如含 `./`）以验证 canonical 同目录。 */
    private fun repoOn(dir: File): InstalledModRepository {
        val context = mockk<Context>()
        every { context.filesDir } returns dir
        return InstalledModRepository(context)
    }

    private fun target(bundle: String = "bundle-a", uri: String = URI_A) =
        JournalTarget(bundle, "hash-$bundle", 4096L, listOf(uri))

    // -------------------------------------------------- 确定性门禁（跨实例）

    /**
     * **A 占位后，B 必须被拒，且 journal 逐字节不变。**
     *
     * A `beginSubmit` 落了 PLANNED；此刻同一 filesDir 上的 B 无论调 `checkSubmitAllowed`
     * 还是 `beginSubmit`，都必须被在途门禁挡住 —— 否则两次提交会叠加、RMW 交错丢记录。
     * 并且 B 被拒时**一个字节都不许写 journal**（不能把 A 的 PLANNED 覆写或追加）。
     */
    @Test
    fun anInFlightSubmitOnOneInstanceBlocksAnotherInstanceOnTheSameDir() {
        val a = repoOn(filesDir)
        val b = repoOn(filesDir)

        assertNull("A 占位必须成功", a.beginSubmit("op-A", listOf(target())))
        val journalBytes = a.journalFile().readBytes()

        assertNotNull("A 在途时 B.checkSubmitAllowed 必须拒绝", b.checkSubmitAllowed())
        assertNotNull("A 在途时 B.beginSubmit 必须拒绝", b.beginSubmit("op-B", listOf(target("bundle-b", URI_B))))
        assertArrayEquals(
            "B 被拒时不许改动 journal 一个字节",
            journalBytes, b.journalFile().readBytes()
        )
    }

    /**
     * **A 释放后，B 才能开始。**
     *
     * A `releaseSubmit("op-A")` 之后在途所有权应当对 B 可见地释放 ——
     * B 的 `beginSubmit` 必须放行。
     */
    @Test
    fun afterOneInstanceReleasesAnotherInstanceMayBegin() {
        val a = repoOn(filesDir)
        val b = repoOn(filesDir)

        assertNull(a.beginSubmit("op-A", listOf(target())))
        a.releaseSubmit("op-A")

        assertNull("A 释放后 B 必须能开始", b.beginSubmit("op-B", listOf(target("bundle-b", URI_B))))
    }

    /**
     * **迟到的旧 id release 不能释放接任者的新所有权。**
     *
     * A 开 op-A → 释放 → B 开 op-B。此时 A 再拿**旧 id**「op-A」调 `releaseSubmit`，
     * 绝不能把 B 的 op-B 所有权抹掉（`releaseSubmit` 只在当前 owner 仍等于传入 id 时才清）。
     * 验证：这次迟到 release 之后，第三方 C 仍被在途门禁挡住。
     */
    @Test
    fun aLateReleaseWithAnOldIdDoesNotFreeTheSuccessorsOwnership() {
        val a = repoOn(filesDir)
        val b = repoOn(filesDir)
        val c = repoOn(filesDir)

        assertNull(a.beginSubmit("op-A", listOf(target())))
        a.releaseSubmit("op-A")
        assertNull("B 接任必须成功", b.beginSubmit("op-B", listOf(target("bundle-b", URI_B))))

        // A 迟到，拿旧 id 再释放一次 —— 不能动 B 的所有权。
        a.releaseSubmit("op-A")

        assertNotNull(
            "迟到旧 id release 不能释放 B 的所有权，C 仍须被拒",
            c.checkSubmitAllowed()
        )
    }

    /**
     * **别名路径（含 `./`）指向同一 canonical 目录 → 视为同一所有者。**
     *
     * 用户/系统可能以不同字符串路径拿到同一目录。所有权必须按 **canonical 路径**归一，
     * 否则 `filesDir` 与 `filesDir/./` 会被当成两个独立所有者，门禁形同虚设。
     */
    @Test
    fun aliasedPathsToTheSameCanonicalDirShareOwnership() {
        val a = repoOn(filesDir)
        val aliasDir = File(filesDir, ".").let { File(it.path) } // 同 canonical、不同字符串
        val b = repoOn(aliasDir)

        assertNull(a.beginSubmit("op-A", listOf(target())))
        assertNotNull(
            "别名路径指向同一目录，B 必须被在途门禁挡住",
            b.beginSubmit("op-B", listOf(target("bundle-b", URI_B)))
        )
    }

    /** **不同 filesDir 互不影响**：各自独立所有者，A 在途不该挡住另一目录上的 B。 */
    @Test
    fun differentFilesDirsDoNotBlockEachOther() {
        val otherDir = temp.newFolder("files-other")
        val a = repoOn(filesDir)
        val b = repoOn(otherDir)

        assertNull(a.beginSubmit("op-A", listOf(target())))
        assertNull(
            "另一个 filesDir 上的提交不该被挡",
            b.beginSubmit("op-B", listOf(target("bundle-b", URI_B)))
        )
    }

    // -------------------------------------------------- 并发压力（仅佐证，非门禁）

    /**
     * **跨实例并发 put 不丢记录**（压力佐证，不替代上面的确定性门禁）。
     *
     * 多个实例（同 filesDir）并发各写一批不重叠的记录，最终账本必须**一条不丢**。
     * 现有单实例锁下两次 RMW 会交错覆盖 —— 这条会红。用 latch 让线程尽量同时起跑，
     * 但断言只看**最终记录集合**（确定性结果），不靠 sleep / 概率作为唯一红灯依据。
     */
    @Test
    fun concurrentPutsAcrossInstancesLoseNoRecords() {
        val instanceCount = 6
        val repos = (0 until instanceCount).map { repoOn(filesDir) }
        val start = CountDownLatch(1)
        val done = CountDownLatch(instanceCount)
        val threads = repos.mapIndexed { i, repo ->
            Thread {
                start.await()
                repo.put(record("uri-$i", "bundle-$i"))
                done.countDown()
            }
        }
        threads.forEach { it.start() }
        start.countDown()
        assertTrue("并发线程必须在超时前完成", done.await(30, TimeUnit.SECONDS))

        val finalLedger = repoOn(filesDir).load()
        assertEquals(
            "每个实例各写一条不重叠记录，最终必须一条不丢",
            instanceCount, finalLedger.size
        )
        (0 until instanceCount).forEach { i ->
            assertTrue("uri-$i 必须保留", finalLedger.containsKey("uri-$i"))
        }
    }

    private fun record(uri: String, bundle: String) = InstalledModRecordFactoryO.of(uri, bundle)

    companion object {
        private const val URI_A = "content://sample/uri-a"
        private const val URI_B = "content://sample/uri-b"
    }
}

/** 测试用 [com.bd2toolsbox.data.model.InstalledModRecord] 构造器（只测试用，不进生产）。 */
private object InstalledModRecordFactoryO {
    fun of(uri: String, bundle: String) = com.bd2toolsbox.data.model.InstalledModRecord(
        modUri = uri,
        modName = uri,
        familyKey = "family-$uri",
        snapshotTargetHash = bundle,
        quality = "HD",
        usedAstc = false,
        installedAt = 1L
    )
}
