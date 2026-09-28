package com.bd2toolsbox.core.engine

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T18：Kotlin–Python 引擎的**协议解码**测试（纯 JVM，不启 Chaquopy）。
 *
 * 用 [PyCell] fake 喂入元组，覆盖每个入口的：成功元组、失败元组 `(false,msg)`、
 * 桥接异常 → 各入口约定失败值（`null`/`(false,…)`/空名单）、非法元组（元素不够）、
 * 协程取消（CancellationException 不被吞成一般失败）。PyObject 已封闭在 PyObjectCell 里，
 * 这里不碰它的 native 方法。
 */
class EngineProtocolTest {

    /** 可控的 cell。 */
    private class FakeCell(
        val s: String = "", val b: Boolean = false, val i: Int = 0, val l: Long = 0L,
        val map: Map<String, List<PyCell>>? = null,
    ) : PyCell {
        override fun asBool() = b
        override fun asInt() = i
        override fun asLong() = l
        override fun asText() = s
        override fun asMap() = map
    }

    private fun engineReturning(vararg cells: PyCell): ChaquopyModEngine =
        ChaquopyModEngine(PyCaller { _, _ -> cells.toList() })

    private fun engineThrowing(t: Throwable): ChaquopyModEngine =
        ChaquopyModEngine(PyCaller { _, _ -> throw t })

    // ---------------------------------------------------------------- 成功路径

    @Test
    fun downloadBundleDecodesSuccessTuple() {
        val (ok, msg) = engineReturning(FakeCell(b = true), FakeCell(s = "/out/data"))
            .downloadBundle("h", "HD", "/out", "k") {}
        assertTrue(ok); assertEquals("/out/data", msg)
    }

    @Test
    fun repackBundleDecodesFailureTuple() {
        val (ok, msg) = engineReturning(FakeCell(b = false), FakeCell(s = "no assets replaced"))
            .repackBundle("orig", "mod", "out", true) {}
        assertFalse(ok); assertEquals("no assets replaced", msg)
    }

    @Test
    fun scanSingleBundleDecodesTriple() {
        val (ok, count, msg) = engineReturning(FakeCell(b = true), FakeCell(i = 42), FakeCell(s = "ok"))
            .scanSingleBundle("b", "hash", "/tmp/data") {}
        assertTrue(ok); assertEquals(42, count); assertEquals("ok", msg)
    }

    @Test
    fun checkScanNeededTakesFirstCellAsJson() {
        assertEquals(
            "[\"a\",\"b\"]",
            engineReturning(FakeCell(s = "[\"a\",\"b\"]")).checkScanNeeded("/out", "[]") {},
        )
    }

    @Test
    fun getBundleMetaDecodesMap() {
        val meta = FakeCell(map = mapOf("bundleA" to listOf(FakeCell(l = 1234L), FakeCell(s = "hashA"))))
        val result = engineReturning(FakeCell(b = true), FakeCell(s = "ok"), meta)
            .getBundleMeta("/o", "HD") {}
        assertEquals(1234L to "hashA", result!!.first["bundleA"])
        assertFalse("非 degraded", result.second)
    }

    // ---------------------------------------------------------------- 桥接异常 → 各入口失败值

    @Test
    fun bridgeExceptionYieldsTypedFailureNotCrash() {
        val engine = engineThrowing(RuntimeException("boom"))
        assertFalse(engine.downloadBundle("h", "HD", "/o", "k") {}.first)
        assertFalse(engine.repackBundle("a", "b", "c", false) {}.first)
        assertFalse(engine.validateBundle("p").first)
        assertNull(engine.getBundleMeta("/o", "HD") {})
        assertNull(engine.getBundleHints("/o", "HD") {})
        val (ok, json) = engine.resolveModFiles("[]", "/o", "HD") {}
        assertFalse(ok); assertNull(json)
        assertEquals("[]", engine.checkScanNeeded("/o", "[]") {})
        assertFalse(engine.scanSingleBundle("b", "h", "/t") {}.first)
    }

    // ---------------------------------------------------------------- 非法元组

    @Test
    fun illegalTupleTooShortIsHandled() {
        // scan_single_bundle 约定三元组；只给两个 → 读 r[2] 越界（明确失败，不静默给错值）。
        val engine = engineReturning(FakeCell(b = true), FakeCell(i = 1))
        assertThrows(IndexOutOfBoundsException::class.java) {
            engine.scanSingleBundle("b", "h", "/t") {}
        }
    }

    @Test
    fun resolveModFilesFailureDoesNotParseSecondCell() {
        val (ok, json) = engineReturning(FakeCell(b = false), FakeCell(s = "not-json"))
            .resolveModFiles("[]", "/o", "HD") {}
        assertFalse(ok); assertNull(json)
    }

    // ---------------------------------------------------------------- 取消

    @Test
    fun cancellationExceptionIsRethrownNotSwallowed() {
        val engine = engineThrowing(CancellationException("cancelled"))
        assertThrows(CancellationException::class.java) {
            engine.downloadBundle("h", "HD", "/o", "k") {}
        }
        assertThrows(CancellationException::class.java) {
            engine.unpackBundle("p", "/o") { false }
        }
    }
}
