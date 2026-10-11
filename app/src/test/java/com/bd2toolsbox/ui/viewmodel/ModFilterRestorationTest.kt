package com.bd2toolsbox.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.bd2toolsbox.data.model.ModInstallState
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * T28：筛选/搜索的**恢复**契约（JVM）。
 *
 * 以前这两个是裸 `MutableStateFlow`，旋转或进程重建后就丢了 —— 用户回到
 * 「全部」的几十上百条里重新找。`SavedStateHandle` 早就在构造函数里注入着，
 * 但一次都没被用过（死参数）。
 *
 * 这里只钉「存了什么、能读回什么、读不回时不炸」：
 * - 搜索词与状态筛选名都落进 handle；
 * - 重建时按 handle 恢复；
 * - handle 里是**未知枚举名**时回退到「全部」，而不是抛异常；
 * - 只存这两个小型参数（不塞列表）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModFilterRestorationTest {

    private lateinit var handle: SavedStateHandle

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        unmockkAll()
        // MainViewModel 的 init 会往 viewModelScope 上挂协程：JVM 单测里没有 Android
        // 主线程，必须先接管 Main。
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        handle = SavedStateHandle()
    }

    @After
    fun teardown() {
        unmockkAll()
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    private fun viewModel(handle: SavedStateHandle): MainViewModel {
        val ctx = mockk<android.content.Context>(relaxed = true)
        every { ctx.applicationContext } returns ctx
        every { ctx.getSharedPreferences(any(), any()) } returns mockk(relaxed = true)
        every { ctx.filesDir } returns java.io.File("/tmp/t28-files")
        every { ctx.externalCacheDir } returns java.io.File("/tmp/t28-cache")
        return MainViewModel(
            handle,
            ctx,
            mockk(relaxed = true), // characterRepository
            mockk(relaxed = true), // modRepository
            mockk(relaxed = true), // installedModRepository
            mockk(relaxed = true), // backupRepository
            mockk(relaxed = true), // previewCacheRepository
            mockk(relaxed = true), // modEngine
            mockk(relaxed = true), // characterMetaRepository
            mockk(relaxed = true), // avatarRepository
            mockk(relaxed = true), // spineRuntimeRepository
            mockk(relaxed = true), // updateRepository
            mockk(relaxed = true), // commitHost
            mockk(relaxed = true), // uninstallExecutor
            mockk(relaxed = true), // scanRunHost
            mockk(relaxed = true), // scanExecutor
            mockk(relaxed = true), // prepackExecutor
        )
    }

    @Test
    fun searchQueryAndStateFilterAreWrittenToTheHandle() {
        val vm = viewModel(handle)
        vm.onSearchQueryChanged("露菲")
        vm.setStateFilter(ModInstallState.INSTALLED)

        assertEquals("露菲", handle.get<String>("mod_search_query"))
        assertEquals("INSTALLED", handle.get<String>("mod_state_filter"))
    }

    @Test
    fun rebuildingRestoresBothFilters() {
        handle["mod_search_query"] = "露菲"
        handle["mod_state_filter"] = "STALE"

        val vm = viewModel(handle)
        assertEquals("露菲", vm.searchQuery.value)
        assertEquals(ModInstallState.STALE, vm.stateFilter.value)
    }

    @Test
    fun unknownEnumNameFallsBackToAllInsteadOfThrowing() {
        // 旧版本写下的枚举名可能在本版本里已经不存在：不能因此崩，回退「全部」
        handle["mod_state_filter"] = "NO_SUCH_STATE"
        val vm = viewModel(handle)
        assertNull(vm.stateFilter.value)
    }

    @Test
    fun nothingStoredMeansNoFilterAndEmptyQuery() {
        val vm = viewModel(handle)
        assertEquals("", vm.searchQuery.value)
        assertNull(vm.stateFilter.value)
    }

    @Test
    fun tappingTheSameStateTwiceClearsIt() {
        val vm = viewModel(handle)
        vm.setStateFilter(ModInstallState.INSTALLED)
        assertEquals(ModInstallState.INSTALLED, vm.stateFilter.value)
        vm.setStateFilter(ModInstallState.INSTALLED)
        assertNull("再点一次同一个状态是取消筛选", vm.stateFilter.value)
        assertNull("取消也要落盘", handle.get<String>("mod_state_filter"))
    }
}
