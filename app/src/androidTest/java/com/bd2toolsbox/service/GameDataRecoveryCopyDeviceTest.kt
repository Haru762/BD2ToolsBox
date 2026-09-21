package com.bd2toolsbox.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bd2toolsbox.core.storage.TargetObservation
import com.bd2toolsbox.core.storage.TargetObservations
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * 只读：经 Shizuku 把真实游戏 `__data` 拷到本用例 cache，再算 SHA。
 * 不写游戏目录，不改账本，不 pm clear。
 */
@RunWith(AndroidJUnit4::class)
class GameDataRecoveryCopyDeviceTest {

    private var destDir: File? = null

    @After
    fun cleanupOwnFixture() {
        destDir?.let { dir ->
            val base = InstrumentationRegistry.getInstrumentation().targetContext
            val allowedParent = base.externalCacheDir ?: base.cacheDir
            check(dir.name.startsWith(PREFIX) && dir.parentFile == allowedParent) {
                "拒绝删除非本用例夹具：${dir.absolutePath}"
            }
            dir.deleteRecursively()
        }
        destDir = null
    }

    @Test
    fun copyOneRealGameDataFileThenObserveSha() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("Shizuku must be running+permitted on the whitelist device", ShizukuManager.isAvailable())
        val bundles = runBlocking { ShizukuManager.listGameBundles() }
        assertTrue("must list at least one game bundle, actual $bundles", !bundles.isNullOrEmpty())
        val (bundleName, hashAndSize) = bundles!!.entries.first()
        val hashDir = hashAndSize.first
        val destRoot = base.externalCacheDir ?: base.cacheDir
        val dest = File(destRoot, PREFIX + UUID.randomUUID()).apply { mkdirs() }
        destDir = dest
        val destFile = File(dest, "__data")
        val copied = runBlocking {
            ShizukuManager.copyGameDataForRecovery(bundleName, hashDir, destFile.absolutePath)
        }
        assertTrue("copyGameDataForRecovery($bundleName/$hashDir) must succeed", copied)
        val obs = TargetObservations.observeFile("op-device", bundleName, hashDir, destFile)
        assertTrue("observed copy must be Observed, actual $obs", obs is TargetObservation.Observed)
        val sha = (obs as TargetObservation.Observed).actualSha256
        assertTrue("sha must be 64 hex, actual $sha", sha.length == 64 && sha.all { it in '0'..'9' || it in 'a'..'f' })
        assertTrue("copied file must be non-empty", destFile.length() > 0)
    }

    companion object {
        private const val PREFIX = "t08-game-copy-"
    }
}
