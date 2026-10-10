package com.bd2toolsbox.feature.mods

import com.bd2toolsbox.core.fileaccess.GameFileAccess
import com.bd2toolsbox.data.model.BundleCheckResult
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** 纯 JVM：只替换 GameFileAccess 边界，验证检查/执行的四种结果与连接占用收尾。 */
class ScanExecutorTest {
    private lateinit var access: GameFileAccess
    private lateinit var executor: ScanExecutor
    private var connected = false
    private var acquired = 0
    private var released = 0

    @Before fun setup() {
        access = mockk()
        val token = Any()
        every { access.acquire() } answers { acquired++; check(!connected); connected = true; token }
        every { access.release(token) } answers { released++; connected = false }
        executor = ScanExecutor(access)
    }

    @After fun teardown() = unmockkAll()

    private fun checkResult(needs: Int) = BundleCheckResult(
        bundleListJson = "[]", needsScanJson = "[]", hashMap = emptyMap(), needsScanCount = needs
    )

    @Test fun notConnectedIsDistinctFromFailed() = runTest {
        every { access.isAvailable() } returns false
        assertEquals(ScanCheck.NotConnected, executor.check("files"))
    }

    @Test fun failedReadIsNotUpToDate() = runTest {
        every { access.isAvailable() } returns true
        coEvery { access.checkLocalBundles(any(), any()) } returns null
        assertTrue(executor.check("files") is ScanCheck.Failed)
    }

    @Test fun zeroNeedsIsUpToDate() = runTest {
        every { access.isAvailable() } returns true
        coEvery { access.checkLocalBundles(any(), any()) } returns checkResult(0)
        assertEquals(ScanCheck.UpToDate, executor.check("files"))
    }

    @Test fun positiveNeedsCarriesTheFrozenCheckResult() = runTest {
        every { access.isAvailable() } returns true
        val frozen = checkResult(3)
        coEvery { access.checkLocalBundles(any(), any()) } returns frozen
        val check = executor.check("files")
        assertTrue(check is ScanCheck.NeedsScan)
        assertSame(frozen, (check as ScanCheck.NeedsScan).checkResult)
    }

    @Test fun runHoldsOccupancyAcrossTheScanAndReleasesIt() = runTest {
        coEvery { access.executeBundleScan(any(), any(), any(), any()) } coAnswers {
            assertTrue("扫描期间必须持有连接占用", connected)
            Triple(true, 8, 2)
        }
        val outcome = executor.run("files", "cache", checkResult(10)) { _, _, _, _ -> }
        assertEquals(ScanRun.Finished(8, 2), outcome)
        assertEquals(1, acquired)
        assertEquals(1, released)
        assertFalse(connected)
    }

    @Test fun failedScanReleasesOccupancy() = runTest {
        coEvery { access.executeBundleScan(any(), any(), any(), any()) } returns Triple(false, 0, 0)
        val outcome = executor.run("files", "cache", checkResult(1)) { _, _, _, _ -> }
        assertTrue(outcome is ScanRun.Failed)
        assertFalse(connected)
        assertEquals(1, released)
    }

    @Test fun exceptionStillReleasesOccupancy() = runTest {
        coEvery { access.executeBundleScan(any(), any(), any(), any()) } throws IllegalStateException("boom")
        try {
            executor.run("files", "cache", checkResult(1)) { _, _, _, _ -> }
            fail("异常必须向上抛给宿主")
        } catch (_: IllegalStateException) {
            assertFalse("异常路径也必须释放占用", connected)
            assertEquals(1, released)
        }
    }
}
