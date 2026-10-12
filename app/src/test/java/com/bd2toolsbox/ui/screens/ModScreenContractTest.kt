package com.bd2toolsbox.ui.screens

import android.net.Uri
import com.bd2toolsbox.core.model.ModScanProgress
import com.bd2toolsbox.data.model.ModInfo
import com.bd2toolsbox.data.model.ModInstallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * T28：Screen 契约本身的形状（JVM）。
 *
 * 这些是**编译期契约的回归**：`ModScreen` 不再接收 `MainViewModel`，只读
 * [ModScreenState]、只发 [ModScreenEvents]。这里钉住三件事：
 *  1. state 只装「显示所需」的值（构造它不需要任何 Android 组件或 ViewModel）；
 *  2. events 的每个动作都能用普通 lambda 构造（页面可以在不碰 VM 的情况下被驱动）；
 *  3. 默认值语义：空列表/未选中/无进度都是合法状态，页面据此走空态。
 */
class ModScreenContractTest {

    @Before fun setup() = io.mockk.mockkStatic(android.net.Uri::class)
    @After fun teardown() = io.mockk.unmockkAll()

    // android.net.Uri 在 JVM 单测里是未实现的桩：mockk 的静态 mock 顶上。
    // 本文件要钉的是「契约形状」，不是 Uri 解析。
    private fun uri(s: String): Uri {
        val u = io.mockk.mockk<Uri>()
        io.mockk.every { u.toString() } returns "content://test/$s"
        return u
    }

    private fun mod(name: String) = ModInfo(
        name = name,
        character = "c",
        costume = "k",
        type = "idle",
        isEnabled = true,
        uri = uri(name),
        targetHashedName = null,
        isDirectory = false,
        installState = ModInstallState.NOT_INSTALLED
    )

    private fun state(
        mods: List<ModInfo> = emptyList(),
        selected: Set<Uri> = emptySet(),
        progress: ModScanProgress? = null
    ) = ModScreenState(
        modSourceDirectoryUri = null,
        modSourceDirs = emptyList(),
        modsList = mods,
        allMods = mods,
        stateFilter = null,
        searchQuery = "",
        selectedMods = selected,
        isLoading = false,
        showShimmer = false,
        isDeletingAbnormal = false,
        isUpdatingOutdated = false,
        modScanProgress = progress,
        selectedQuality = "SD",
        isSourceRoot = { false }
    )

    private fun events(record: MutableList<String> = mutableListOf()) = ModScreenEvents(
        onSelectModSource = { record += "selectSource" },
        onUninstallRequest = { record += "uninstall:$it" },
        onRemoveModRequest = { record += "remove:${it.name}" },
        onBackupManageRequest = { record += "backup" },
        onRenameRequest = { record += "rename:${it.name}" },
        onDeleteFolderRequest = { record += "deleteFolder:${it.name}" },
        onInstallConverted = { record += "install:${it.size}" },
        onRequestNotification = { record += "requestNotification" },
        onSearchQueryChanged = { record += "search:$it" },
        onSetStateFilter = { record += "filter:$it" },
        onToggleSelection = { record += "toggle:$it" },
        onToggleSelectAll = { record += "selectAll" },
        onToggleSelectAllForUris = { record += "selectAllFor:${it.size}" },
        onRescan = { record += "rescan" },
        onPreview = { record += "preview:${it.name}" },
        onHideMod = { record += "hide:${it.name}" },
        onUpdateOutdated = { record += "update:${it.name}" },
        onUpdateAllOutdated = { record += "updateAll" },
        onDeleteModFolder = { m, done -> record += "deleteModFolder:${m.name}"; done(true, "ok") },
        onDeleteAbnormalMods = { record += "deleteAbnormal:${it.size}" },
        onInitiateBatchRepack = { record += "batchRepack" }
    )

    @Test
    fun stateIsConstructibleWithoutAnyAndroidComponentOrViewModel() {
        // 这条用例本身就是判据：能在这个纯 JVM 文件里 new 出来，说明 state 里没有
        // ViewModel / StateFlow / Context。
        val s = state(mods = listOf(mod("a")))
        assertEquals(1, s.modsList.size)
        assertNull(s.modSourceDirectoryUri)
        assertNull(s.stateFilter)
        assertTrue(s.selectedMods.isEmpty())
        assertFalse(s.isLoading)
    }

    @Test
    fun emptyStateIsAValidState() {
        val s = state()
        assertTrue(s.modsList.isEmpty())
        assertNull("没有进度是合法状态，页面据此不显示进度条", s.modScanProgress)
        assertFalse("空列表时 isSourceRoot 不该被调成 true", s.isSourceRoot(uri("x")))
    }

    @Test
    fun everyEventIsInvokableAsAPlainLambda() {
        val log = mutableListOf<String>()
        val e = events(log)
        e.onSelectModSource()
        e.onUninstallRequest("hash-1")
        e.onRemoveModRequest(mod("a"))
        e.onBackupManageRequest()
        e.onRenameRequest(mod("b"))
        e.onDeleteFolderRequest(mod("c"))
        e.onInstallConverted(listOf(mod("d"), mod("e")))
        e.onRequestNotification()
        e.onSearchQueryChanged("露菲")
        e.onSetStateFilter(ModInstallState.STALE)
        e.onToggleSelection(uri("f"))
        e.onToggleSelectAll()
        e.onToggleSelectAllForUris(setOf(uri("g"), uri("h")))
        e.onRescan()
        e.onPreview(mod("i"))
        e.onHideMod(mod("j"))
        e.onUpdateOutdated(mod("k"))
        e.onUpdateAllOutdated()
        e.onDeleteModFolder(mod("l")) { _, _ -> }
        e.onDeleteAbnormalMods(listOf(mod("m")))
        e.onInitiateBatchRepack()

        assertEquals(21, log.size)
        assertEquals("uninstall:hash-1", log[1])
        assertEquals("filter:STALE", log[9])
        assertEquals("selectAllFor:2", log[12])
        assertEquals("deleteModFolder:l", log[18])
    }

    @Test
    fun selectionAndProgressRoundTripThroughState() {
        val u = uri("picked")
        val s = state(
            mods = listOf(mod("a"), mod("b")),
            selected = setOf(u),
            progress = ModScanProgress(doneDirs = 3, totalDirs = 10, stage = "扫描中")
        )
        assertEquals(setOf(u), s.selectedMods)
        assertEquals(3, s.modScanProgress!!.doneDirs)
        assertEquals(10, s.modScanProgress!!.totalDirs)
    }
}
