package com.bd2toolsbox.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bd2toolsbox.core.storage.ModSnapshotStore
import com.bd2toolsbox.core.storage.SnapshotCommit
import com.bd2toolsbox.core.storage.SnapshotEntry
import com.bd2toolsbox.core.storage.SnapshotLoad
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * 冷启动快照在**真实设备文件系统**上的提交语义（T10 迁移版）。
 *
 * ## 这个文件是迁移来的，不是新写的
 *
 * 它原来是 `ModSnapshotTest`，靠**反射**摸 `MainViewModel` 的私有成员：
 * `getDeclaredField("appContext")`、`getDeclaredField("_modSourceDirs")`、
 * `declaredMethods.single { it.name == "saveModListSnapshot" }`。T10 把快照区搬进
 * [ModSnapshotStore] 之后，`declaredMethods.single` 会在**运行期**抛
 * `NoSuchElementException`（不是编译错）—— 也就是说，不迁它，T10 就把一条既有用例
 * 打红了。迁移的顺带收益是**全部反射都去掉了**：[ModSnapshotStore] 的构造参数只有
 * `File`，公开 API 足够表达这四条语义，不再需要去够私有成员。
 *
 * 四条用例的**名字与语义逐个保留**（便于与历史对照），只把驱动方式换成 Store 的公开 API。
 *
 * ## 为什么留在设备侧而不是并进 JVM 单测
 *
 * JVM 那份（`ModSnapshotStoreTest`）跑在宿主 NTFS 上，`AtomicFileWriter` 的
 * `preservePermissions` 走的是「平台没有 POSIX 权限概念」的跳过分支。设备上是真实
 * FUSE + 真实 POSIX 权限，那条分支**是活的**。两边合起来才覆盖整个提交路径。
 */
@RunWith(AndroidJUnit4::class)
class ModSnapshotTest {

    private lateinit var dir: File

    private val sources = listOf("content://test/tree/original")

    private val entries = listOf(
        SnapshotEntry(
            name = "test", character = "Eclipse", costume = "Beach", type = "cutscene",
            isEnabled = false, uri = "content://test/tree/original/document/mod",
            targetHashedName = null, isDirectory = true, resolutionState = "KNOWN",
            targetHash = null, resolvedFamilyKey = null, errorReason = null,
            installState = "NOT_INSTALLED", kind = "CONVERTED_BUNDLE",
            convertedHashDir = null, convertedDataSize = 0L, defect = null, outdatedCurrentHash = null
        )
    )

    private fun store() = ModSnapshotStore(File(dir, ModSnapshotStore.SNAPSHOT_FILENAME))

    @Before
    fun setup() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        dir = File(base.cacheDir, "snapshot-tests-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After
    fun cleanupOwnFixture() {
        check(dir.name.startsWith("snapshot-tests-") && dir.parentFile?.name == "cache")
        dir.deleteRecursively()
    }

    @Test
    fun lateScanSnapshotMustNotOverwriteNewerEmptyResult() {
        val s = store()
        val oldScan = s.capture(entries, sources)
        val newerEmpty = s.capture(emptyList(), sources)
        assertTrue("更新的空结果必须先落盘", s.commit(newerEmpty, sources) is SnapshotCommit.Written)
        val late = s.commit(oldScan, sources)
        assertTrue("更早捕获的那份必须被拒，实际是 $late", late is SnapshotCommit.Skipped)
        assertEquals("更晚完成但更旧的扫描不许盖掉新结果", 0, (store().load(sources) as SnapshotLoad.Loaded).entries.size)
    }

    @Test
    fun sourceChangeRejectsOldSnapshot() {
        val s = store()
        val write = s.capture(entries, sources)
        val replacement = listOf("content://test/tree/replacement")
        val result = s.commit(write, replacement)
        assertTrue("换过源目录之后，旧写必须被拒，实际是 $result", result is SnapshotCommit.Skipped)
        // 被拒 = 一个字节都没写：替换后的源目录下**没有**快照可读，
        // 于是绝不会显示出另一组源目录的条目。
        val afterReject = store().load(replacement)
        assertTrue(
            "被拒的写不许留下任何快照，实际是 $afterReject",
            afterReject is SnapshotLoad.Absent
        )
    }

    @Test
    fun pendingWriteForRemovedSourceDoesNotRestoreItsEntries() {
        val s = store()
        val queued = s.capture(entries, sources)
        // 源目录被清空，并且这次的「空」已经落盘。
        val emptied = s.capture(emptyList(), emptyList())
        assertTrue(s.commit(emptied, emptyList()) is SnapshotCommit.Written)
        // 迟到的旧写此刻提交：源目录已变，必须被拒。
        val late = s.commit(queued, emptyList())
        assertTrue("已移除来源的待写快照必须被拒，实际是 $late", late is SnapshotCommit.Skipped)
        assertEquals(0, (store().load(emptyList()) as SnapshotLoad.Loaded).entries.size)
    }

    @Test
    fun unchangedSourcesRestoreSnapshot() {
        val s = store()
        assertTrue(s.commit(s.capture(entries, sources), sources) is SnapshotCommit.Written)
        // 换实例 = 再启动一次，不复用内存状态。
        val restored = (store().load(sources) as SnapshotLoad.Loaded).entries
        assertEquals(1, restored.size)
        assertEquals("test", restored.single().name)
        assertEquals("content://test/tree/original/document/mod", restored.single().uri)
    }

    /**
     * 设备侧的提交走的是真实 FUSE：`AtomicFileWriter` 的权限保留分支在这里是**活的**
     * （宿主 NTFS 上它是跳过的）。这条断言不检查权限位本身（那是 T07 的覆盖），
     * 只钉住「写入 → 读回」在真实设备文件系统上成立。
     */
    @Test
    fun commitSurvivesTheRealDeviceFilesystem() {
        val s = store()
        assertTrue(s.commit(s.capture(entries, sources), sources) is SnapshotCommit.Written)
        val f = File(dir, ModSnapshotStore.SNAPSHOT_FILENAME)
        assertTrue("快照文件必须真的落在设备磁盘上", f.isFile)
        assertTrue("落盘内容必须非空", f.length() > 0)
        // 且它是一个能解析的新格式文件（顶层带 version）。
        assertTrue(f.readText().contains("\"version\""))
    }
}
