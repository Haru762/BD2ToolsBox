package com.bd2toolsbox.core.operations

import com.bd2toolsbox.core.storage.JournalRead
import com.bd2toolsbox.core.storage.JournalStage
import com.bd2toolsbox.core.storage.OperationJournalStore
import com.bd2toolsbox.data.repository.InstalledModRepository
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 重启后对某个未收尾操作的处置结论。
 *
 * 三态而不是「成功/失败」：**「不知道」必须能表达出来**。分类只依据 journal 阶段，
 * 不自动重放安装步骤、不改账本、不碰游戏文件 —— 内容观察与补账由既有
 * `reconcilePendingInstalls` 流程负责。
 */
sealed interface RecoveryDisposition {
    /**
     * 只落下了写前意图（[JournalStage.TARGET_REPLACING]）而没有确认替换：
     * 游戏文件可能没动，也可能动了一半。**中断**，需要观察后再决定，不能当成功。
     */
    data class Interrupted(
        val operationId: String,
        val bundleNames: List<String>,
        val modUris: List<String>
    ) : RecoveryDisposition

    /**
     * 已确认替换（[JournalStage.TARGET_REPLACED]）但没有后续 [JournalStage.LEDGER_COMMITTED]：
     * 游戏文件已变、账本可能没记。**待人工核对/补账**。
     */
    data class NeedsReconciliation(
        val operationId: String,
        val bundleNames: List<String>,
        val modUris: List<String>
    ) : RecoveryDisposition

    /** journal 读不出来或自相矛盾：不能自动动作。 */
    data class Unknown(val message: String) : RecoveryDisposition

    /**
     * 替换已确认且账本已提交（窗口已闭合，但 journal 还没被清掉）：
     * 事实完整，无需动作。列出仅为让上层能区分「已提交」与「待核对」。
     */
    data class Committed(
        val operationId: String,
        val bundleNames: List<String>
    ) : RecoveryDisposition
}

/**
 * 进程死亡后的安全收尾入口（T25）。
 *
 * 只回答「有哪些操作没收尾、各自该按哪种方式处理」，**不自动重放所有安装步骤**：
 * - 转换/安装不承诺任意断点无缝续跑；扫描沿用既有 checkpoint（不在本类）。
 * - 已提交事实不被覆盖：本类只读 journal，写账本由上层显式调用既有恢复流程。
 * - 清理路径必须经 [isCleanupPathAllowed] 校验归属，不得用 journal 里的任意字符串路径。
 */
@Singleton
class OperationRecovery @Inject constructor(
    private val repository: InstalledModRepository
) {
    /**
     * 按 journal 文件顺序、分 operationId 分类所有未闭合的替换窗口。
     *
     * 与 [OperationJournalStore.recoveryVerdict] 的区别：那条只给「有没有待核对」，
     * 这里进一步区分「中断」与「已替换待核对」，供 UI 明示中断与下一步。
     */
    fun classify(): List<RecoveryDisposition> {
        val entries = when (val read = OperationJournalStore(repository.journalFile()).read()) {
            is JournalRead.Loaded -> read.entries
            JournalRead.Absent -> return emptyList()
            is JournalRead.Unreadable -> return listOf(RecoveryDisposition.Unknown(read.error.message))
        }
        val out = mutableListOf<RecoveryDisposition>()
        // 按 operationId 分组，保留文件内下标用于判断「提交是否在替换之后」。
        val byOperation = entries.withIndex().groupBy { it.value.operationId }
        for ((operationId, indexed) in byOperation) {
            val lastCommit = indexed
                .filter { it.value.stage == JournalStage.LEDGER_COMMITTED.id }
                .maxOfOrNull { it.index }
            val open = indexed.filter {
                val stage = it.value.stageOrNull
                (stage == JournalStage.TARGET_REPLACING || stage == JournalStage.TARGET_REPLACED) &&
                    (lastCommit == null || it.index > lastCommit)
            }
            val replacedEvents = indexed.filter { it.value.stageOrNull == JournalStage.TARGET_REPLACED }
            if (open.isEmpty()) {
                // 窗口已闭合，但 journal 还在（进程死在 ledger commit 与 clear 之间）：
                // 事实完整，列出来只为与「待核对」区分。
                if (lastCommit != null && replacedEvents.any { it.index < lastCommit }) {
                    val bundles = replacedEvents.flatMap { it.value.targets }
                        .map { it.bundleName }.distinct()
                    out += RecoveryDisposition.Committed(operationId, bundles)
                }
                continue
            }
            val targets = open.flatMap { it.value.targets }
            val bundles = targets.map { it.bundleName }.distinct()
            val uris = targets.flatMap { it.modUris }.distinct()
            val replaced = open.any { it.value.stageOrNull == JournalStage.TARGET_REPLACED }
            out += if (replaced) {
                RecoveryDisposition.NeedsReconciliation(operationId, bundles, uris)
            } else {
                RecoveryDisposition.Interrupted(operationId, bundles, uris)
            }
        }
        return out
    }

    companion object {
        /**
         * 清理路径必须严格位于某个 [allowedRoots] 之内（或等于根本身）。
         *
         * 用 `canonicalFile` 解析 `..`、符号链接与相对路径，再做**路径分量**包含性判断
         * （不是字符串前缀），所以 `/a/bc` 不会被 `/a/b` 放行。
         * 防伪造/过期 journal 路径导致边界外递归删除。
         */
        fun isCleanupPathAllowed(candidate: File, allowedRoots: List<File>): Boolean {
            val target = try {
                candidate.canonicalFile
            } catch (_: Exception) {
                return false
            }
            for (root in allowedRoots) {
                val canonicalRoot = try {
                    root.canonicalFile
                } catch (_: Exception) {
                    continue
                }
                if (target == canonicalRoot) return true
                val rootPath = canonicalRoot.path
                val targetPath = target.path
                if (targetPath.length > rootPath.length &&
                    targetPath.startsWith(rootPath) &&
                    (rootPath.endsWith(File.separator) || targetPath[rootPath.length] == File.separatorChar)
                ) {
                    return true
                }
            }
            return false
        }
    }
}
