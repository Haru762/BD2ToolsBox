package com.bd2toolsbox.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** T62：.nomedia 目录级隔离的纯 JVM 契约（真文件、临时目录）。 */
class MediaExclusionManagerTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun createsAnEmptyRegularMarkerFile() {
        val dir = temp.newFolder("ModAssets")
        assertEquals(MediaExclusionResult.Created, MediaExclusionManager.ensureMarker(dir))
        val marker = File(dir, MediaExclusionManager.MARKER_NAME)
        assertTrue(marker.isFile)
        assertEquals("标记必须是空文件", 0L, marker.length())
    }

    @Test fun existingMarkerIsLeftUnchangedAndReportedAsPresent() {
        val dir = temp.newFolder("ModAssets")
        val marker = File(dir, MediaExclusionManager.MARKER_NAME).apply { writeText("keep me") }
        assertEquals(MediaExclusionResult.AlreadyPresent, MediaExclusionManager.ensureMarker(dir))
        assertEquals("已有同名文件不许被覆盖/清空", "keep me", marker.readText())
    }

    @Test fun mixedUseRootsAreRejectedByDefault() {
        for (name in listOf("DCIM", "Pictures", "Movies", "Download", "Downloads", "Documents")) {
            val dir = temp.newFolder(name)
            val result = MediaExclusionManager.ensureMarker(dir)
            assertTrue("$name 必须被拒绝，实得 $result", result is MediaExclusionResult.Rejected)
            assertFalse("被拒时不能留下标记", File(dir, MediaExclusionManager.MARKER_NAME).exists())
        }
    }

    @Test fun modSubdirectoryUnderAMixedUseRootIsAllowed() {
        val pictures = temp.newFolder("Pictures")
        val modDir = File(pictures, "BD2Mods").apply { mkdirs() }
        assertEquals(MediaExclusionResult.Created, MediaExclusionManager.ensureMarker(modDir))
    }

    @Test fun explicitConfirmationCanOverrideTheMixedUseRefusal() {
        val dir = temp.newFolder("Download")
        assertEquals(
            MediaExclusionResult.Created,
            MediaExclusionManager.ensureMarker(dir, refuseMixedUseRoot = false)
        )
    }

    @Test fun missingDirectoryFailsInsteadOfCreatingIt() {
        val missing = File(temp.root, "does-not-exist")
        assertTrue(MediaExclusionManager.ensureMarker(missing) is MediaExclusionResult.Failed)
        assertFalse(missing.exists())
    }

    @Test fun markerNameIsExactNotAPrefix() {
        assertTrue(MediaExclusionManager.isMarkerName(".nomedia"))
        assertFalse(".nomedia.txt", MediaExclusionManager.isMarkerName(".nomedia.txt"))
        assertFalse(MediaExclusionManager.isMarkerName(".nomedia.bin"))
        assertFalse(MediaExclusionManager.isMarkerName("nomedia"))
        assertFalse(MediaExclusionManager.isMarkerName(".nomedia2"))
    }

    @Test fun providerReturningARenamedMarkerIsNotProtected() {
        assertTrue(MediaExclusionManager.verifyProviderName(".nomedia"))
        assertFalse(MediaExclusionManager.verifyProviderName(".nomedia.txt"))
        assertFalse(MediaExclusionManager.verifyProviderName(".nomedia.bin"))
        assertFalse(MediaExclusionManager.verifyProviderName(null))
        assertFalse(MediaExclusionManager.verifyProviderName(""))
    }

    @Test fun modScanSkipsOnlyTheMarker() {
        assertTrue(MediaExclusionManager.shouldSkipInModScan(".nomedia"))
        assertFalse(MediaExclusionManager.shouldSkipInModScan("char000104.dat"))
        assertFalse(MediaExclusionManager.shouldSkipInModScan("skel.atlas"))
        assertFalse(MediaExclusionManager.shouldSkipInModScan(".nomedia.txt"))
    }

    @Test fun mixedUseRootCheckIsCaseInsensitiveAndTrimmed() {
        assertTrue(MediaExclusionManager.isMixedUseRoot("  DCIM "))
        assertTrue(MediaExclusionManager.isMixedUseRoot("downloads"))
        assertFalse(MediaExclusionManager.isMixedUseRoot("BD2Mods"))
        assertFalse(MediaExclusionManager.isMixedUseRoot(null))
    }
    // ---------------------------------------------------------------- T62：SAF 半边

    @Test fun safResultMessagesOnlySpeakWhenSomethingWentWrong() {
        // 成功与「已存在」都不出声 —— 用户没做错什么，不需要被打扰
        assertNull(SafMediaExclusion.message(MediaExclusionResult.Created))
        assertNull(SafMediaExclusion.message(MediaExclusionResult.AlreadyPresent))
        // 被拒与失败都要说清原因
        val rejected = SafMediaExclusion.message(
            MediaExclusionResult.Rejected("「BD2mods」是混合用途根目录，默认不加 .nomedia")
        )
        assertNotNull(rejected)
        assertTrue(rejected!!.contains("混合用途根目录"))
        val failed = SafMediaExclusion.message(MediaExclusionResult.Failed("只有读取权限"))
        assertNotNull(failed)
        assertTrue("失败文案要说清是哪一步没成", failed!!.contains("媒体隔离标记未建立"))
        assertTrue(failed.contains("只有读取权限"))
    }

    @Test fun safMarkerNameAndMixedUseRulesMatchTheFileVariant() {
        // 两条路径必须用同一组常量与同一套判据，否则 SAF 版会悄悄放宽保护范围
        assertEquals(".nomedia", MediaExclusionManager.MARKER_NAME)
        // 混合用途根是「系统相册/下载/存储根」这类；大小写与首尾空白都归一
        assertTrue(MediaExclusionManager.isMixedUseRoot("DCIM"))
        assertTrue(MediaExclusionManager.isMixedUseRoot("  downloads "))
        assertFalse("用户自建的 MOD 目录不是混合用途根", MediaExclusionManager.isMixedUseRoot("BD2mods"))
        assertFalse(MediaExclusionManager.isMixedUseRoot("pc_test"))
        assertTrue(MediaExclusionManager.verifyProviderName(".nomedia"))
        assertFalse(MediaExclusionManager.verifyProviderName(".nomedia.txt"))
        assertFalse(MediaExclusionManager.verifyProviderName(null))
    }
}
