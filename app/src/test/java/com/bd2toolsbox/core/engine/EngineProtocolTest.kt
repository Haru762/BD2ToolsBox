package com.bd2toolsbox.core.engine

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T18：Kotlin–Python 引擎的**协议解码**测试（纯 JVM，不启 Chaquopy）。
 *
 * 桥接异常与非法元组必须走失败路径，不能变成空名单或半张元数据表。
 * Fake 只替换外部 Python 调用，协议校验与解码均运行生产实现。
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
        val scanError = assertThrows(IllegalStateException::class.java) {
            engine.checkScanNeeded("/o", "[]") {}
        }
        assertTrue(scanError.message.orEmpty().contains("check_scan_needed"))
        assertEquals("boom", scanError.cause?.message)
        assertFalse(engine.scanSingleBundle("b", "h", "/t") {}.first)
    }

    // ---------------------------------------------------------------- 非法元组

    @Test
    fun illegalTupleTooShortReturnsDiagnosticFailure() {
        val engine = engineReturning(FakeCell(b = true), FakeCell(i = 1))
        val (ok, count, message) = engine.scanSingleBundle("b", "h", "/t") {}
        assertFalse(ok)
        assertEquals(0, count)
        assertTrue(message.contains("scan_single_bundle"))
    }

    @Test
    fun everyPairOperationRejectsMissingAndExtraCells() {
        val operations: List<(ModEngine) -> Pair<Boolean, String>> = listOf(
            { it.downloadBundle("bundle", "SD", "/out", "key") {} },
            { it.repackBundle("/original", "/mod", "/out", true) {} },
            { it.restoreBundle("/original", "/out") {} },
            { it.unpackBundle("/bundle", "/out", fast = true) { false } },
            { it.validateBundle("/bundle") },
            { it.mergeSpineAssets("/mod") {} },
            { it.checkpointScan("/out") },
            { it.finalizeScan("/out") {} },
        )
        for (operation in operations) {
            for (cells in listOf(
                emptyList(),
                listOf(FakeCell(b = true)),
                listOf(FakeCell(b = true), FakeCell(s = "ok"), FakeCell(s = "extra")),
            )) {
                val result = operation(ChaquopyModEngine(PyCaller { _, _ -> cells }))
                assertFalse("Malformed tuple must not succeed: $cells", result.first)
                assertTrue(result.second.isNotBlank())
            }
        }
    }

    @Test
    fun wrongCellTypeIsReportedAsProtocolFailure() {
        val wrongBoolean = object : PyCell by FakeCell() {
            override fun asBool(): Boolean = throw ClassCastException("expected bool")
        }
        val (ok, message) = engineReturning(wrongBoolean, FakeCell(s = "unused"))
            .validateBundle("/bundle")
        assertFalse(ok)
        assertTrue(message.contains("validate_bundle"))
        assertTrue(message.contains("expected bool"))
    }

    @Test
    fun unreadableMapsNeverBecomeSuccessfulEmptyMaps() {
        for (payload in listOf(
            FakeCell(s = "not a map"),
            FakeCell(map = mapOf("broken" to listOf(FakeCell(l = 1)))),
            FakeCell(map = mapOf("broken" to listOf(FakeCell(l = 1), FakeCell(s = "h"), FakeCell()))),
        )) {
            val engine = engineReturning(FakeCell(b = true), FakeCell(s = "version"), payload)
            assertNull(engine.getBundleMeta("/out", "HD") {})
            assertNull(engine.getBundleHints("/out", "HD") {})
        }
    }

    @Test
    fun validEmptyMetadataRemainsDistinctFromFailure() {
        val engine = engineReturning(FakeCell(b = true), FakeCell(s = "version"), FakeCell(map = emptyMap()))
        assertEquals(emptyMap<String, Pair<Long, String>>() to false, engine.getBundleMeta("/out", "HD") {})
        assertEquals(emptyMap<String, Pair<String, String>>(), engine.getBundleHints("/out", "HD") {})
    }

    @Test
    fun scanCheckRejectsMalformedShapeRatherThanReportingNoWork() {
        for (cells in listOf(
            emptyList(),
            listOf(FakeCell(s = "[]"), FakeCell(s = "extra")),
            listOf(FakeCell(s = "not-json")),
            listOf(FakeCell(s = "{}")),
            listOf(FakeCell(s = "[123]")),
        )) {
            val engine = ChaquopyModEngine(PyCaller { _, _ -> cells })
            assertThrows(IllegalStateException::class.java) {
                engine.checkScanNeeded("/out", "[]") {}
            }
        }
    }

    @Test
    fun cancellationDuringDecodeIsNotConvertedToFailure() {
        val cancelledMap = object : PyCell by FakeCell() {
            override fun asMap(): Map<String, List<PyCell>> = throw CancellationException("map cancelled")
        }
        val engine = engineReturning(FakeCell(b = true), FakeCell(s = "version"), cancelledMap)
        assertThrows(CancellationException::class.java) { engine.getBundleMeta("/out", "HD") {} }
        assertThrows(CancellationException::class.java) { engine.getBundleHints("/out", "HD") {} }
    }

    @Test
    fun resolveModFilesFailureDoesNotParseSecondCell() {
        val (ok, json) = engineReturning(FakeCell(b = false), FakeCell(s = "not-json"))
            .resolveModFiles("[]", "/o", "HD") {}
        assertFalse(ok); assertNull(json)
    }

    @Test
    fun pairEntrypointsPreservePythonArgumentsAndFailureMessages() {
        val calls = mutableListOf<Pair<String, List<Any?>>>()
        val progress = mutableListOf<String>()
        val onProgress: (String) -> Unit = { progress += it }
        val engine = ChaquopyModEngine(PyCaller { function, args ->
            calls += function to args.map { if (it is Function1<*, *>) "<callback>" else it }
            for (arg in args) {
                if (arg is Function1<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    val callback = arg as (String) -> Any?
                    val result = callback(function)
                    if (function == "unpack_bundle") assertEquals(true, result)
                }
            }
            listOf(FakeCell(b = false), FakeCell(s = "operation failed"))
        })
        val results = listOf(
            engine.downloadBundle("bundle", "SD", "/out", "cache-key", onProgress),
            engine.repackBundle("/original", "/assets", "/repacked", true, onProgress),
            engine.restoreBundle("/original", "/restored", onProgress),
            engine.unpackBundle("/bundle", "/unpacked", true) { progress += it; true },
            engine.validateBundle("/bundle"),
            engine.mergeSpineAssets("/assets", onProgress),
            engine.checkpointScan("/index"),
            engine.finalizeScan("/index", onProgress),
        )
        assertTrue(results.all { it == (false to "operation failed") })
        assertEquals(listOf(
            "download_bundle" to listOf("bundle", "SD", "/out", "cache-key", "<callback>"),
            "main" to listOf("/original", "/assets", "/repacked", true, "<callback>"),
            "restore_bundle" to listOf("/original", "/restored", "<callback>"),
            "unpack_bundle" to listOf("/bundle", "/unpacked", "<callback>", true),
            "validate_bundle" to listOf("/bundle"),
            "merge_spine_assets" to listOf("/assets", "<callback>"),
            "checkpoint_scan" to listOf("/index"),
            "finalize_scan" to listOf("/index", "<callback>"),
        ), calls)
        assertEquals(listOf("download_bundle", "main", "restore_bundle", "unpack_bundle", "merge_spine_assets", "finalize_scan"), progress)
    }

    @Test
    fun malformedResolverPayloadsNeverEscapeAsSuccessfulJson() {
        for (payload in listOf("not-json", "null", "123")) {
            val engine = engineReturning(FakeCell(b = true), FakeCell(s = payload))
            assertEquals(false to null, engine.resolveModFiles("[]", "/out", "SD") {})
            assertEquals(false to null, engine.resolveModBatch("[]", "/out", "HD") {})
        }
    }

    @Test
    fun metadataKeepsDegradedFlagAndHintFields() {
        val metadata = engineReturning(
            FakeCell(b = true), FakeCell(s = "degraded"),
            FakeCell(map = mapOf("bundle" to listOf(FakeCell(l = 20L), FakeCell(s = "hash")))),
        )
        assertEquals(mapOf("bundle" to (20L to "hash")) to true, metadata.getBundleMeta("/out", "SD") {})
        val hints = engineReturning(
            FakeCell(b = true), FakeCell(s = "version"),
            FakeCell(map = mapOf("bundle" to listOf(FakeCell(s = "char000001"), FakeCell(s = "idle")))),
        )
        assertEquals(mapOf("bundle" to ("char000001" to "idle")), hints.getBundleHints("/out", "HD") {})
    }

    // ---------------------------------------------------------------- 取消

    @Test
    fun cancellationSurvivesAReceiverWhichCatchesCallbackExceptions() {
        val cancelled = CancellationException("callback cancelled")
        val engine = ChaquopyModEngine(PyCaller { function, args ->
            assertEquals("unpack_bundle", function)
            @Suppress("UNCHECKED_CAST")
            val callback = args[2] as (String) -> Boolean
            try {
                callback("Processing asset")
            } catch (_: CancellationException) {
                // The Python unpacker catches callback exceptions and can return a success tuple.
            }
            listOf(FakeCell(b = true), FakeCell(s = "Unpacking complete."))
        })
        val actual = assertThrows(CancellationException::class.java) {
            engine.unpackBundle("/bundle", "/out") { throw cancelled }
        }
        assertSame(cancelled, actual)
    }

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
