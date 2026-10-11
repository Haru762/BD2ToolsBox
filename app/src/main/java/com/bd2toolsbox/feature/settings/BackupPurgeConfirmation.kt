package com.bd2toolsbox.feature.settings

import java.security.MessageDigest

/**
 * 危险操作（清空原版备份）的确认模型（T44）。
 *
 * 三条硬规则：
 * 1. **确认前说清后果**：当前数量、占用空间、以及「失去本地恢复资源」这件事；
 * 2. **确认后重新核对目标集合**：确认时记下目标指纹，执行前比对；集合变了就**重新确认**，
 *    绝不顺手删掉确认之后新产生的备份；
 * 3. **只报真实结果**：失败就报失败，剩余数量/空间用**实际重新统计**的值，
 *    不在删除未成功时把数量/空间直接置 0。
 */
object BackupPurge {

    /** 确认时冻结的目标集合指纹：bundle -> 内容哈希 的稳定摘要。 */
    fun fingerprint(backups: Map<String, String>): String {
        val md = MessageDigest.getInstance("SHA-256")
        backups.entries
            .sortedBy { it.key }
            .forEach { (bundle, hash) ->
                md.update(bundle.toByteArray(Charsets.UTF_8))
                md.update(0)
                md.update(hash.toByteArray(Charsets.UTF_8))
                md.update(0)
            }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 确认文案：数量、空间、后果三件都要有。
     *
     * [bytes] 是人类可读的占用（调用方用现成格式化）。
     */
    fun consequence(count: Int, humanBytes: String): String =
        "将清空 $count 份原版备份（占用 $humanBytes）。" +
            "清空后这些游戏资源包将失去本地原版恢复资源，" +
            "只能重新从游戏内下载；此操作不可撤销。"

    /** 确认按钮文案必须写明做什么，不用泛化的「确定」。 */
    const val CONFIRM_LABEL = "清空原版备份"
    const val CANCEL_LABEL = "取消"

    /** 弹窗标题带问号：与按钮文案区分，读屏与测试可分别定位。 */
    const val DIALOG_TITLE = "清空原版备份？"

    /** 执行前核对：指纹一致才允许按原范围删除。 */
    fun revalidate(confirmed: String, currentBackups: Map<String, String>): PurgeRevalidation =
        if (fingerprint(currentBackups) == confirmed) {
            PurgeRevalidation.Unchanged
        } else {
            PurgeRevalidation.Changed(currentBackups.size)
        }

    /**
     * 删除结果。`deleted + failed` 必须等于本次尝试的目标数；剩余量由**重新统计**得到。
     */
    fun outcome(
        attempted: Int,
        deleted: Int,
        remainingCount: Int,
        remainingBytes: Long
    ): PurgeOutcome {
        val failed = (attempted - deleted).coerceAtLeast(0)
        return PurgeOutcome(
            attempted = attempted,
            deleted = deleted,
            failed = failed,
            remainingCount = remainingCount,
            remainingBytes = remainingBytes
        )
    }

    /** 结果文案：成功、部分成功、全失败三种，绝不把失败说成成功。 */
    fun resultText(outcome: PurgeOutcome, humanBytes: String): String = when {
        outcome.deleted == 0 && outcome.attempted == 0 ->
            "没有需要清空的原版备份。"
        outcome.failed == 0 && outcome.remainingCount == 0 ->
            "已清空 ${outcome.deleted} 份原版备份。"
        outcome.deleted == 0 ->
            "清空失败：${outcome.attempted} 份均未删除，仍占用 $humanBytes。" +
                "请检查存储权限后重试。"
        else ->
            "部分成功：已清空 ${outcome.deleted} 份，${outcome.failed} 份未删除，" +
                "仍剩 ${outcome.remainingCount} 份（$humanBytes）。"
    }

    /** 活跃任务与清空互斥：任务在用时不允许清空。 */
    fun blockedByActiveTask(activeTaskId: String?): String? =
        activeTaskId?.let { "有任务正在进行（$it），请等它结束后再清空原版备份。" }
}

/** 执行前核对结果。 */
sealed interface PurgeRevalidation {
    /** 目标集合没变，可按确认范围删除。 */
    data object Unchanged : PurgeRevalidation

    /** 目标集合变了（多了新备份）：必须重新确认，不删未确认的新备份。 */
    data class Changed(val currentCount: Int) : PurgeRevalidation
}

/** 清空结果（真实统计）。 */
data class PurgeOutcome(
    val attempted: Int,
    val deleted: Int,
    val failed: Int,
    val remainingCount: Int,
    val remainingBytes: Long
) {
    val fullyCleared: Boolean get() = attempted > 0 && failed == 0 && remainingCount == 0
    val partiallyCleared: Boolean get() = deleted > 0 && !fullyCleared
}
