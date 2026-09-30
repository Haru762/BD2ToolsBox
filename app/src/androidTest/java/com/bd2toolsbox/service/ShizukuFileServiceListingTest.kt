package com.bd2toolsbox.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

/**
 * [ShizukuFileService.listBundleDirectoryPaged] 的 envelope 协议（T19，设备用例）。
 *
 * 旧实现把「目录不存在」「listFiles() 返回 null」「异常」「确实为空」四种情况
 * 都编码成裸数组 `[]`；调用方拿到同一个值，无法区分「确实没文件」与「读失败」。
 * 这里验证新协议：只有真正遍历完毕才返回 `ok:true`（entries 可以为空），
 * 其余情况一律 `ok:false`，不把失败伪装成「最后一页是空的」。
 *
 * **为什么是 androidTest 而不是 JVM 单测**：`ShizukuFileService` 是
 * `IFileService.Stub`（继承 `android.os.Binder`）；`Stub` 的构造函数会调
 * `attachInterface`，JVM 宿主的单测环境没有真实 `Binder` 实现，构造即抛
 * `Method attachInterface in android.os.Binder not mocked`。与
 * [GameFileCommitTest] 同理，这里用本地 `TemporaryFolder` 而非真实游戏/
 * Shizuku 路径，不需要 Shizuku 授权、也不碰 Binder 之外的 Android API。
 */
@RunWith(AndroidJUnit4::class)
class ShizukuFileServiceListingTest {

    @get:Rule val temp = TemporaryFolder()

    private val service = ShizukuFileService()

    @Test
    fun nonexistentSharedDirIsFailureNotEmptySuccess() {
        val missing = File(temp.root, "does-not-exist")

        val envelope = JSONObject(service.listBundleDirectoryPaged(missing.absolutePath, 0, 500))

        assertFalse("不存在的目录必须是 ok:false，不能伪装成 ok:true 且 entries 为空", envelope.getBoolean("ok"))
        assertTrue(envelope.getString("error").isNotBlank())
    }

    @Test
    fun sharedDirWithNoBundlesIsGenuineEmptySuccess() {
        val shared = temp.newFolder("Shared")

        val envelope = JSONObject(service.listBundleDirectoryPaged(shared.absolutePath, 0, 500))

        assertTrue("真正遍历完毕且确实没有 bundle 时必须是 ok:true", envelope.getBoolean("ok"))
        assertEquals(0, envelope.getJSONArray("entries").length())
    }

    @Test
    fun bundleDirThatCannotBeListedFailsTheWholeCall() {
        val shared = temp.newFolder("Shared")
        File(shared, "good-bundle/hash1").apply { mkdirs() }
        File(shared, "good-bundle/hash1/__data").writeText("data")
        // 真实设备上普通文件当作目录读不会触发失败——本实现先判 isDirectory 提前
        // continue，根本不会走到它的 listFiles()。真正会让目录 listFiles() 返 null
        // 的是权限被拒：chmod 000 对自己进程创建的目录在真实设备上能真实触发。
        val lockedBundleDir = File(shared, "locked-bundle").apply { mkdirs() }
        try {
            check(lockedBundleDir.setReadable(false, false)) { "本用例需要能对自己目录撤掉可读权限（chmod 000）" }
            check(lockedBundleDir.setExecutable(false, false)) { "本用例需要能对自己目录撤掉可执行权限（chmod 000）" }

            val envelope = JSONObject(service.listBundleDirectoryPaged(shared.absolutePath, 0, 500))

            assertFalse(
                "某一个 bundle 子目录读失败（权限被拒）必须让整次列举失败，不能只返回其他能读的部分",
                envelope.getBoolean("ok")
            )
        } finally {
            // 恢复权限才能让 TemporaryFolder 规则正常清理这个目录
            lockedBundleDir.setExecutable(true, false)
            lockedBundleDir.setReadable(true, false)
        }
    }

    @Test
    fun onlyTheFirstHashDirWithDataIsReportedPerBundle() {
        val shared = temp.newFolder("Shared")
        File(shared, "bundle-a/hash1").mkdirs()
        File(shared, "bundle-a/hash1/__data").writeBytes(ByteArray(10))
        File(shared, "bundle-b/hash2").mkdirs()
        File(shared, "bundle-b/hash2/__data").writeBytes(ByteArray(20))

        val envelope = JSONObject(service.listBundleDirectoryPaged(shared.absolutePath, 0, 500))

        assertTrue(envelope.getBoolean("ok"))
        val entries = envelope.getJSONArray("entries")
        assertEquals(2, entries.length())
        val byName = (0 until entries.length()).associate {
            val o = entries.getJSONObject(it)
            o.getString("name") to (o.getString("hash") to o.getLong("size"))
        }
        assertEquals("hash1" to 10L, byName["bundle-a"])
        assertEquals("hash2" to 20L, byName["bundle-b"])
    }

    @Test
    fun pagingSplitsEntriesAndLastPageIsShorterThanLimit() {
        val shared = temp.newFolder("Shared")
        repeat(5) { i ->
            File(shared, "bundle-$i/hash").mkdirs()
            File(shared, "bundle-$i/hash/__data").writeBytes(ByteArray(i))
        }

        val page1 = JSONObject(service.listBundleDirectoryPaged(shared.absolutePath, 0, 2))
        val page2 = JSONObject(service.listBundleDirectoryPaged(shared.absolutePath, 2, 2))
        val page3 = JSONObject(service.listBundleDirectoryPaged(shared.absolutePath, 4, 2))

        assertTrue(page1.getBoolean("ok"))
        assertEquals(2, page1.getJSONArray("entries").length())
        assertTrue(page2.getBoolean("ok"))
        assertEquals(2, page2.getJSONArray("entries").length())
        assertTrue(page3.getBoolean("ok"))
        assertEquals("最后一页应少于 limit，调用方据此判定已读完", 1, page3.getJSONArray("entries").length())
    }
}
