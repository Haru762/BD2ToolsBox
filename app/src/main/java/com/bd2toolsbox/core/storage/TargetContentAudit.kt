package com.bd2toolsbox.core.storage

/**
 * 「按目标文件内容对账」的纯 JVM 判定器（T08 内容证据）。
 *
 * 只做**数据判定**：拿「替换前持久化的预期内容摘要」与「重启后只读观察到的实际摘要 /
 * 明确读失败」比对，逐 target 给出 [TargetVerdict]，整批给出 [ContentAuditResult]。
 *
 * **不碰任何 I/O**：不读文件、不写盘、不清 journal、不动 ledger/gate。真实文件的 SHA 由调用方
 * （受控设备上经 Shizuku 拷贝 + [StreamingDigest]）算好后作为 [TargetObservation] 传入。
 *
 * **[ContentAuditResult.AllMatched] 只代表「文件内容证据齐全且一致」**，
 * **不**代表 ledger 已修复、**不**是绕过 pending 的通道 —— 原有 ledger gate 保持不动。
 */
class TargetContentAudit {

    /**
     * 逐 target 内容对账。
     *
     * 键严格为 operationId + bundleName + hashDir（[Triple]，null hashDir 只匹配同 null 键）。
     * - 期望或观察出现重复同键 → 歧义，相关项 UNVERIFIED；
     * - 缺失观察 / [TargetObservation.ReadFailed] / 非法或空 SHA → UNVERIFIED；
     * - 双方均为合法 64 位小写 hex 且相等 → MATCH，不等 → MISMATCH。
     *
     * 逐项保留有效的 MATCH/MISMATCH 结果以便诊断，整批失败不会把有效项抹成 UNVERIFIED。
     * 整批仅当「期望非空、无重复键、无多余观察、每项 MATCH」才 [ContentAuditResult.AllMatched]。
     */
    fun audit(
        expected: List<ExpectedTarget>,
        observations: List<TargetObservation>
    ): ContentAuditResult {
        val expectedByKey = expected.groupBy { keyOf(it.operationId, it.bundleName, it.hashDir) }
        val observationsByKey =
            observations.groupBy { keyOf(it.operationId, it.bundleName, it.hashDir) }

        val ambiguousExpectedKeys = expectedByKey.filterValues { it.size > 1 }.keys
        val ambiguousObservationKeys = observationsByKey.filterValues { it.size > 1 }.keys

        val results = expected.map { exp ->
            val key = keyOf(exp.operationId, exp.bundleName, exp.hashDir)
            val verdict = when {
                key in ambiguousExpectedKeys -> TargetVerdict.UNVERIFIED
                key in ambiguousObservationKeys -> TargetVerdict.UNVERIFIED
                else -> when (val obs = observationsByKey[key]?.singleOrNull()) {
                    null -> TargetVerdict.UNVERIFIED
                    is TargetObservation.ReadFailed -> TargetVerdict.UNVERIFIED
                    is TargetObservation.Observed ->
                        if (!isValidSha256(exp.expectedSha256) || !isValidSha256(obs.actualSha256)) {
                            TargetVerdict.UNVERIFIED
                        } else if (exp.expectedSha256 == obs.actualSha256) {
                            TargetVerdict.MATCH
                        } else {
                            TargetVerdict.MISMATCH
                        }
                }
            }
            TargetAuditResult(exp.operationId, exp.bundleName, exp.hashDir, verdict)
        }

        val hasExtraObservation = observationsByKey.keys.any { it !in expectedByKey.keys }

        val allMatched = expected.isNotEmpty() &&
            ambiguousExpectedKeys.isEmpty() &&
            ambiguousObservationKeys.isEmpty() &&
            !hasExtraObservation &&
            results.all { it.verdict == TargetVerdict.MATCH }

        return if (allMatched) {
            ContentAuditResult.AllMatched(results)
        } else {
            ContentAuditResult.NotAllMatched(results, reason = describeFailure(
                expected = expected,
                ambiguousExpectedKeys = ambiguousExpectedKeys,
                ambiguousObservationKeys = ambiguousObservationKeys,
                hasExtraObservation = hasExtraObservation,
                results = results
            ))
        }
    }

    companion object {
        /** 合法 SHA-256：64 位小写 hex（与 [StreamingDigest.hex] 输出同形）。 */
        fun isValidSha256(value: String?): Boolean =
            value != null && value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

        /** 严格目标键：operationId + bundleName + hashDir（保留 hashDir 的 null 语义）。 */
        private fun keyOf(operationId: String, bundleName: String, hashDir: String?):
            Triple<String, String, String?> = Triple(operationId, bundleName, hashDir)

        private fun describeFailure(
            expected: List<ExpectedTarget>,
            ambiguousExpectedKeys: Set<Triple<String, String, String?>>,
            ambiguousObservationKeys: Set<Triple<String, String, String?>>,
            hasExtraObservation: Boolean,
            results: List<TargetAuditResult>
        ): String = when {
            expected.isEmpty() -> "no expected targets"
            ambiguousExpectedKeys.isNotEmpty() -> "ambiguous duplicate expected keys"
            ambiguousObservationKeys.isNotEmpty() -> "ambiguous duplicate observation keys"
            hasExtraObservation -> "unexpected extra observations"
            results.any { it.verdict == TargetVerdict.MISMATCH } -> "content mismatch"
            else -> "unverified targets remain"
        }
    }
}

/** 待核对目标的期望：键 = operationId + bundleName + hashDir，加预期内容摘要。 */
data class ExpectedTarget(
    val operationId: String,
    val bundleName: String,
    val hashDir: String?,
    /** 替换前持久化的预期内容 SHA-256；旧 journal 无摘要时为 null。 */
    val expectedSha256: String?
)

/** 重启后对某个键的实际只读观察：要么读到内容摘要，要么明确读失败。 */
sealed class TargetObservation {
    abstract val operationId: String
    abstract val bundleName: String
    abstract val hashDir: String?

    /** 读到了内容并算出 SHA-256。 */
    data class Observed(
        override val operationId: String,
        override val bundleName: String,
        override val hashDir: String?,
        val actualSha256: String
    ) : TargetObservation()

    /** 明确读失败（Shizuku 不可用 / 拷贝失败 / 文件缺失等）。 */
    data class ReadFailed(
        override val operationId: String,
        override val bundleName: String,
        override val hashDir: String?,
        val reason: String
    ) : TargetObservation()
}

/** 逐 target 判定：内容一致 / 不一致 / 无法核对。 */
enum class TargetVerdict { MATCH, MISMATCH, UNVERIFIED }

/** 单个 target 的判定结果（带回其键，供上层绑 JournalEntry）。 */
data class TargetAuditResult(
    val operationId: String,
    val bundleName: String,
    val hashDir: String?,
    val verdict: TargetVerdict
)

/**
 * 整批内容对账结论。
 *
 * [AllMatched] 只在「非空、无歧义、每个 target 都恰有同键 Observed 且摘要合法并相等」时成立；
 * 任何缺 target / 空列表 / 重复歧义 / 非法 sha / 无摘要 / 读失败 / 键不匹配 / 部分匹配
 * 一律 [NotAllMatched]。
 */
sealed class ContentAuditResult {
    abstract val targets: List<TargetAuditResult>

    data class AllMatched(override val targets: List<TargetAuditResult>) : ContentAuditResult()

    data class NotAllMatched(
        override val targets: List<TargetAuditResult>,
        val reason: String
    ) : ContentAuditResult()
}
