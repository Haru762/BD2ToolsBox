package com.bd2toolsbox.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [TargetContentAudit] 内容证据判定的契约（T08）。
 *
 * 用真实临时文件 + 真实 [StreamingDigest.sha256] 生成 SHA，绝不拿字符串伪装内容验证。
 * 键 = operationId + bundleName + hashDir，精确匹配。[ContentAuditResult.AllMatched]
 * 只代表内容证据齐全一致，**不**解除 ledger gate。
 *
 * 先在 fail-closed skeleton 上实跑 RED，再验证实现；本组不触发恢复写入。
 */
class TargetContentAuditTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val audit = TargetContentAudit()
    private val digester = StreamingDigest.sha256()

    /** 写一份内容并返回其真实 SHA-256。 */
    private fun sha(content: ByteArray): String {
        val f = temp.newFile()
        f.writeBytes(content)
        return digester.digest(f)
    }

    private fun expected(op: String, bundle: String, hash: String?, sha: String?) =
        ExpectedTarget(op, bundle, hash, sha)

    private fun observed(op: String, bundle: String, hash: String?, sha: String) =
        TargetObservation.Observed(op, bundle, hash, sha)

    private fun readFailed(op: String, bundle: String, hash: String?) =
        TargetObservation.ReadFailed(op, bundle, hash, "read failed")

    // ---------------------------------------------------------------- MATCH

    @Test
    fun identicalBytesMatchAndBatchAllMatched() {
        val sha = sha("hello-bundle".toByteArray())
        val result = audit.audit(
            expected = listOf(expected("op-1", "bundle-a", "hashA", sha)),
            observations = listOf(observed("op-1", "bundle-a", "hashA", sha))
        )
        assertTrue("相同字节内容应整批通过", result is ContentAuditResult.AllMatched)
        assertEquals(TargetVerdict.MATCH, result.targets.single().verdict)
    }

    @Test
    fun multipleDistinctBundlesAllMatch() {
        val shaA = sha("aaaa".toByteArray())
        val shaB = sha("bbbbbb".toByteArray())
        val result = audit.audit(
            expected = listOf(
                expected("op-1", "bundle-a", "hashA", shaA),
                expected("op-1", "bundle-b", "hashB", shaB)
            ),
            observations = listOf(
                observed("op-1", "bundle-a", "hashA", shaA),
                observed("op-1", "bundle-b", "hashB", shaB)
            )
        )
        assertTrue("多个不同 bundle 全一致应通过", result is ContentAuditResult.AllMatched)
        assertTrue(result.targets.all { it.verdict == TargetVerdict.MATCH })
    }

    // ---------------------------------------------------------------- MISMATCH

    @Test
    fun sameLengthDifferentBytesMismatch() {
        val shaExpected = sha("AAAA".toByteArray())
        val shaActual = sha("BBBB".toByteArray()) // 同长度、不同字节
        assertTrue("同长度不同字节的 SHA 必须不同", shaExpected != shaActual)
        val result = audit.audit(
            expected = listOf(expected("op-1", "bundle-a", "hashA", shaExpected)),
            observations = listOf(observed("op-1", "bundle-a", "hashA", shaActual))
        )
        assertTrue("内容不一致不能通过", result is ContentAuditResult.NotAllMatched)
        assertEquals(TargetVerdict.MISMATCH, result.targets.single().verdict)
    }

    // ---------------------------------------------------------------- UNVERIFIED / 不通过

    @Test
    fun legacyJournalWithoutShaIsUnverified() {
        val result = audit.audit(
            expected = listOf(expected("op-1", "bundle-a", "hashA", null)),
            observations = listOf(observed("op-1", "bundle-a", "hashA", sha("x".toByteArray())))
        )
        assertTrue("旧 journal 无预期摘要不能通过", result is ContentAuditResult.NotAllMatched)
        assertEquals(TargetVerdict.UNVERIFIED, result.targets.single().verdict)
    }

    @Test
    fun invalidExpectedShaIsUnverified() {
        val result = audit.audit(
            expected = listOf(expected("op-1", "bundle-a", "hashA", "not-a-valid-sha")),
            observations = listOf(observed("op-1", "bundle-a", "hashA", sha("x".toByteArray())))
        )
        assertTrue("非法预期 SHA 不能通过", result is ContentAuditResult.NotAllMatched)
        assertEquals(TargetVerdict.UNVERIFIED, result.targets.single().verdict)
    }

    @Test
    fun wrongOperationIdIsNotReused() {
        val sha = sha("x".toByteArray())
        val result = audit.audit(
            expected = listOf(expected("op-1", "bundle-a", "hashA", sha)),
            observations = listOf(observed("op-2", "bundle-a", "hashA", sha)) // 错 operationId
        )
        assertTrue("错 operationId 不得复用观察", result is ContentAuditResult.NotAllMatched)
        assertEquals(TargetVerdict.UNVERIFIED, result.targets.single().verdict)
    }

    @Test
    fun wrongTargetKeyIsNotReused() {
        val sha = sha("x".toByteArray())
        val result = audit.audit(
            expected = listOf(expected("op-1", "bundle-a", "hashA", sha)),
            observations = listOf(observed("op-1", "bundle-b", "hashB", sha)) // 错 bundle/hash
        )
        assertTrue("错 target 键不得复用观察", result is ContentAuditResult.NotAllMatched)
        assertEquals(TargetVerdict.UNVERIFIED, result.targets.single().verdict)
    }

    @Test
    fun readFailedIsUnverified() {
        val result = audit.audit(
            expected = listOf(expected("op-1", "bundle-a", "hashA", sha("x".toByteArray()))),
            observations = listOf(readFailed("op-1", "bundle-a", "hashA"))
        )
        assertTrue("读失败不能通过", result is ContentAuditResult.NotAllMatched)
        assertEquals(TargetVerdict.UNVERIFIED, result.targets.single().verdict)
    }

    @Test
    fun missingObservationIsUnverified() {
        val result = audit.audit(
            expected = listOf(expected("op-1", "bundle-a", "hashA", sha("x".toByteArray()))),
            observations = emptyList() // 缺记录
        )
        assertTrue("缺观察不能通过", result is ContentAuditResult.NotAllMatched)
        assertEquals(TargetVerdict.UNVERIFIED, result.targets.single().verdict)
    }

    @Test
    fun emptyExpectedIsNotAllMatched() {
        val result = audit.audit(expected = emptyList(), observations = emptyList())
        assertTrue("空列表绝不能 AllMatched", result is ContentAuditResult.NotAllMatched)
        assertTrue(result.targets.isEmpty())
    }

    @Test
    fun duplicateExpectedKeyIsAmbiguousAndNotAllMatched() {
        val sha = sha("x".toByteArray())
        val result = audit.audit(
            expected = listOf(
                expected("op-1", "bundle-a", "hashA", sha),
                expected("op-1", "bundle-a", "hashA", sha) // 重复 expected 键
            ),
            observations = listOf(observed("op-1", "bundle-a", "hashA", sha))
        )
        assertTrue("重复 expected 键有歧义，不能通过", result is ContentAuditResult.NotAllMatched)
        assertTrue(result.targets.all { it.verdict == TargetVerdict.UNVERIFIED })
    }

    @Test
    fun duplicateObservationKeyIsAmbiguousAndNotAllMatched() {
        val sha = sha("x".toByteArray())
        val result = audit.audit(
            expected = listOf(expected("op-1", "bundle-a", "hashA", sha)),
            observations = listOf(
                observed("op-1", "bundle-a", "hashA", sha),
                observed("op-1", "bundle-a", "hashA", sha) // 重复 observation 键
            )
        )
        assertTrue("重复 observation 键有歧义，不能通过", result is ContentAuditResult.NotAllMatched)
        assertEquals(TargetVerdict.UNVERIFIED, result.targets.single().verdict)
    }

    @Test
    fun extraObservationIsNotAllMatched() {
        val shaA = sha("aaaa".toByteArray())
        val shaB = sha("bbbb".toByteArray())
        val result = audit.audit(
            expected = listOf(expected("op-1", "bundle-a", "hashA", shaA)),
            observations = listOf(
                observed("op-1", "bundle-a", "hashA", shaA),
                observed("op-1", "bundle-b", "hashB", shaB) // 多出一个观察
            )
        )
        assertTrue("额外观察不能通过", result is ContentAuditResult.NotAllMatched)
    }

    @Test
    fun mixedMatchAndMismatchIsNotAllMatched() {
        val shaA = sha("aaaa".toByteArray())
        val shaBExpected = sha("BBBB".toByteArray())
        val shaBActual = sha("CCCC".toByteArray())
        val result = audit.audit(
            expected = listOf(
                expected("op-1", "bundle-a", "hashA", shaA),
                expected("op-1", "bundle-b", "hashB", shaBExpected)
            ),
            observations = listOf(
                observed("op-1", "bundle-a", "hashA", shaA),
                observed("op-1", "bundle-b", "hashB", shaBActual)
            )
        )
        assertTrue("部分匹配不能整批通过", result is ContentAuditResult.NotAllMatched)
        val byBundle = result.targets.associateBy { it.bundleName }
        assertEquals(TargetVerdict.MATCH, byBundle.getValue("bundle-a").verdict)
        assertEquals(TargetVerdict.MISMATCH, byBundle.getValue("bundle-b").verdict)
    }

    @Test
    fun nullHashDirMatchesOnlySameNullKey() {
        val sha = sha("x".toByteArray())
        val result = audit.audit(
            expected = listOf(expected("op-1", "bundle-a", null, sha)),
            observations = listOf(observed("op-1", "bundle-a", null, sha))
        )
        assertTrue("null hashDir 同键应可匹配", result is ContentAuditResult.AllMatched)
        assertEquals(TargetVerdict.MATCH, result.targets.single().verdict)
    }

    @Test
    fun invalidActualShaIsUnverifiedNotMismatch() {
        val valid = sha("actual-content".toByteArray())
        for (invalid in listOf("", "g".repeat(64), valid.uppercase(), valid.dropLast(1))) {
            val result = audit.audit(
                listOf(expected("op-1", "bundle-a", "hashA", valid)),
                listOf(observed("op-1", "bundle-a", "hashA", invalid))
            )
            assertTrue(result is ContentAuditResult.NotAllMatched)
            assertEquals(TargetVerdict.UNVERIFIED, result.targets.single().verdict)
        }
    }

    @Test
    fun sameBundleInDifferentHashDirectoryIsNotReused() {
        val valid = sha("actual-content".toByteArray())
        val result = audit.audit(
            listOf(expected("op-1", "bundle-a", "hashA", valid)),
            listOf(observed("op-1", "bundle-a", "hashB", valid))
        )
        assertTrue(result is ContentAuditResult.NotAllMatched)
        assertEquals(TargetVerdict.UNVERIFIED, result.targets.single().verdict)
    }
}
