package com.bd2toolsbox.ui.viewmodel

import android.content.Context
import com.bd2toolsbox.feature.install.DirectMoveCommitHost
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.bd2toolsbox.data.model.BundleCleanState
import com.bd2toolsbox.data.repository.BundleBackupRepository
import com.bd2toolsbox.data.repository.CharacterRepository
import com.bd2toolsbox.data.repository.InstalledModRepository
import com.bd2toolsbox.data.repository.ModRepository
import com.bd2toolsbox.data.repository.PreviewCacheRepository
import com.bd2toolsbox.service.ShizukuManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Audit item 1 at the real call site: a game-directory bundle that the listing marked
 * PRISTINE (catalog name + size match) must not be reused as a conversion base unless a
 * byte-anchored digest confirms it. Only the Shizuku/Log boundaries are replaced.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalBaseReuseTest {
    @get:Rule val temp = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private lateinit var vm: MainViewModel
    private lateinit var cacheDir: File
    private lateinit var context: Context

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        cacheDir = temp.newFolder("cache")
        context = mockk(relaxed = true)
        every { context.cacheDir } returns cacheDir
        every { context.filesDir } returns temp.newFolder("files")
        every { context.getExternalFilesDir(null) } returns temp.newFolder("external-files")
        every { context.applicationContext } returns context
        val character = CharacterRepository(context)
        vm = MainViewModel(
            SavedStateHandle(),
            context,
            character,
            ModRepository(context, character),
            InstalledModRepository(context),
            BundleBackupRepository(context),
            PreviewCacheRepository(context),
            com.bd2toolsbox.core.engine.ChaquopyModEngine(),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            DirectMoveCommitHost(mockk(relaxed = true)),
            mockk(relaxed = true), // uninstall executor is not used by base selection
            com.bd2toolsbox.feature.mods.DirectScanRunHost(mockk(relaxed = true)),
            mockk(relaxed = true), // scan executor is not used by base selection
            mockk(relaxed = true), // prepack executor is not used by base selection
        )
        setField("bundleCleanStates", mapOf("b" to BundleCleanState.PRISTINE))
        setField("gameBundleHashes", mapOf("b" to "h1"))
        mockkObject(ShizukuManager)
        // Same-size, modified bytes: exactly what a size-only check would accept.
        coEvery { ShizukuManager.copyLocalBundleToCache("b", "h1", any()) } answers {
            File(thirdArg<String>()).writeBytes(ByteArray(2966) { 0x4D })
            true
        }
    }

    @After fun cleanup() {
        vm.viewModelScope.cancel()
        unmockkAll()
        Dispatchers.resetMain()
    }

    private fun setField(name: String, value: Any) {
        MainViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(vm, value)
    }

    private suspend fun tryUseLocalBundle(): String? {
        val m = MainViewModel::class.java.declaredMethods.single { it.name == "tryUseLocalBundle" }
        m.isAccessible = true
        return kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn { cont ->
            m.invoke(vm, context, "b", cont)
        }
    }

    @Test fun sizeOnlyPristineWithoutTrustedDigestIsNotReused() = runTest(dispatcher) {
        // backupRepository is never initialized here: no byte-anchored baseline exists.
        assertNull(tryUseLocalBundle())
        assertFalse("copied unverified base must be discarded", File(cacheDir, "b/h1/__data").exists())
    }

    @Test fun nonPristineListingIsNeverCopied() = runTest(dispatcher) {
        setField("bundleCleanStates", mapOf("b" to BundleCleanState.MODIFIED))
        assertNull(tryUseLocalBundle())
        assertFalse(File(cacheDir, "b").exists())
    }
}
