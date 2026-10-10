package com.bd2toolsbox.feature.preview

import android.net.Uri
import com.bd2toolsbox.data.model.ModInfo
import com.bd2toolsbox.data.model.ModKind
import com.bd2toolsbox.data.repository.PreviewCacheRepository
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** 纯 JVM：mock 掉缓存仓储，验证目标筛选与计划同源。 */
class PrepackExecutorTest {
    private lateinit var cache: PreviewCacheRepository
    private lateinit var executor: PrepackExecutor

    @Before fun setup() {
        cache = mockk(relaxed = true)
        executor = PrepackExecutor(cache)
    }

    @After fun teardown() = unmockkAll()

    private fun converted(
        name: String,
        bundle: String? = "bundle_$name",
        hash: String? = "hash_$name",
        size: Long = 100L
    ): ModInfo {
        val uri = mockk<Uri>()
        every { uri.toString() } returns "content://mods/$name"
        return ModInfo(
            name = name, character = "C", costume = "S", type = "t",
            isEnabled = true, uri = uri, targetHashedName = bundle, isDirectory = false,
            kind = ModKind.CONVERTED_BUNDLE, convertedHashDir = hash, convertedDataSize = size
        )
    }

    private fun source(name: String): ModInfo {
        val uri = mockk<Uri>()
        every { uri.toString() } returns "content://mods/$name"
        return ModInfo(
            name = name, character = "C", costume = "S", type = "t",
            isEnabled = true, uri = uri, targetHashedName = "src_$name", isDirectory = true,
            kind = ModKind.PC_SOURCE
        )
    }

    @Test fun validCacheIsSkipped() {
        every { cache.isValid("b", "h", 10L) } returns true
        val targets = executor.selectTargets(listOf(converted("a", "b", "h", 10L)))
        assertTrue(targets.isEmpty())
    }

    @Test fun negativeCacheIsSkipped() {
        every { cache.isValid("b", "h", 10L) } returns false
        every { cache.isKnownNoPreview("b", "h", 10L) } returns true
        val targets = executor.selectTargets(listOf(converted("a", "b", "h", 10L)))
        assertTrue(targets.isEmpty())
    }

    @Test fun uncachedConvertedBundleBecomesATarget() {
        val targets = executor.selectTargets(listOf(converted("a", "b", "h", 10L)))
        assertEquals(1, targets.size)
        val t = targets.single()
        assertEquals("content://mods/a", t.treeUri)
        assertEquals("b", t.bundleName)
        assertEquals("h", t.hashDir)
        assertEquals(10L, t.size)
        assertEquals("a", t.displayName)
    }

    @Test fun nonConvertedAndIncompleteEntriesAreIgnored() {
        val missingHash = converted("noHash", "b", hash = null)
        val missingBundle = converted("noBundle", bundle = null)
        val targets = executor.selectTargets(listOf(source("s"), missingHash, missingBundle))
        assertTrue(targets.isEmpty())
    }

    @Test fun distinctVariantsOfTheSameBundleAreSeparateTargets() {
        // 同一 bundle 的两个变体：hash 相同、size 不同 —— 槽位不同，不能去重。
        val a = converted("a", "bundle", "hash", 10L)
        val b = converted("b", "bundle", "hash", 20L)
        val targets = executor.selectTargets(listOf(a, b))
        assertEquals(2, targets.size)
        assertEquals(setOf(10L, 20L), targets.map { it.size }.toSet())
    }

    @Test fun planTodoMatchesSelectTargets() {
        every { cache.isValid("bundle", "hash", 10L) } returns true
        every { cache.isKnownNoPreview("bundle", "hash2", 20L) } returns true
        val mods = listOf(
            converted("cached", "bundle", "hash", 10L),
            converted("nopreview", "bundle", "hash2", 20L),
            converted("todo", "bundle", "hash3", 30L)
        )
        val plan = executor.plan(mods, freeBytes = 1_000L)
        val targets = executor.selectTargets(mods)
        assertEquals(3, plan.total)
        assertEquals(1, plan.alreadyCached)
        assertEquals(targets.size, plan.todo)   // 同源：计划 todo = 实际目标数
        assertEquals(1, plan.todo)
        assertEquals(1_000L, plan.freeBytes)
    }

    @Test fun planUsesMeasuredRatioWhenAvailable() {
        every { cache.measuredExpansionRatio() } returns 2.0
        val plan = executor.plan(listOf(converted("a", "b", "h", 50L)), freeBytes = 0L)
        assertEquals(100L, plan.estimatedBytes)
        assertTrue(plan.ratioFromSamples)
    }

    @Test fun planFallsBackToConservativeRatioWithoutSamples() {
        every { cache.measuredExpansionRatio() } returns null
        val plan = executor.plan(listOf(converted("a", "b", "h", 50L)), freeBytes = 0L)
        assertEquals((50L * 1.6).toLong(), plan.estimatedBytes)
        assertFalse(plan.ratioFromSamples)
    }
}
