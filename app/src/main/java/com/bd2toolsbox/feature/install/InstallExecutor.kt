package com.bd2toolsbox.feature.install

import android.util.Log
import com.bd2toolsbox.core.fileaccess.GameFileAccess
import com.bd2toolsbox.core.model.OperationError
import com.bd2toolsbox.core.model.OperationId
import com.bd2toolsbox.core.storage.JournalTarget
import com.bd2toolsbox.core.storage.LedgerWrite
import com.bd2toolsbox.core.storage.MoveInstallStaging
import com.bd2toolsbox.data.model.InstalledModRecord
import com.bd2toolsbox.data.repository.ConvertedSubmitResult
import com.bd2toolsbox.data.repository.InstalledModRepository
import com.bd2toolsbox.data.repository.MoveInstallSubmitter
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** 账本写入的附带提示由宿主展示，不把 ViewModel/Toast 回调传进提交过程。 */
data class LedgerNotice(val action: String, val result: LedgerWrite)

data class MoveExecutionResult(
    val submission: ConvertedSubmitResult,
    val ledgerNotices: List<LedgerNotice> = emptyList()
)

/**
 * 已转换产物的真实提交路径：冻结暂存、逐目标校验/替换、记账、精确清理和连接收尾。
 * 只接受已经冻结的目标事实，不读取 UI 状态、不保存调用方闭包、不拥有后台 scope。
 * 重打包与 Service 宿主迁移是 T22 的后续步骤，当前先替换 MainViewModel 中的这条提交链。
 */
@Singleton
class InstallExecutor @Inject constructor(
    private val repository: InstalledModRepository,
    private val access: GameFileAccess
) {
    suspend fun move(
        id: OperationId,
        shared: File,
        stagingRoot: File,
        targets: List<JournalTarget>,
        restoredTargets: Set<String>
    ): MoveExecutionResult {
        repository.checkSubmitAllowed()?.let {
            return MoveExecutionResult(ConvertedSubmitResult(false, it, it.message, true))
        }
        val notices = mutableListOf<LedgerNotice>()
        val connection = access.acquire()
        try {
            val prepared = MoveInstallStaging(stagingRoot).prepare(shared, targets)
            try {
                prepared.verifyUnchanged()?.let {
                    return MoveExecutionResult(ConvertedSubmitResult(false, it, it.message, false))
                }
                // 只删除本次实际还原的目标；失败/未选中的目标永远不能被批量清账。
                val removals = prepared.targets.filter {
                    it.modUris.isEmpty() && it.bundleName in restoredTargets
                }.map { it.bundleName }
                val submitted = MoveInstallSubmitter(repository).submit(
                    operationId = id.value,
                    targets = prepared.targets,
                    moveFiles = move@{
                        for (target in prepared.targets) {
                            prepared.verifyTarget(target.bundleName)?.let { return@move false to it.message }
                            val copied = access.installConvertedBundle(
                                File(prepared.sourceDir, target.bundleName).absolutePath, target.bundleName
                            )
                            if (!copied.first) return@move copied
                            prepared.verifyTarget(target.bundleName)?.let { return@move false to it.message }
                        }
                        true to "已成功装入游戏目录！"
                    },
                    commitLedger = {
                        var error = recordSuccessfulInstall(prepared.targets, notices)
                        if (error == null) {
                            for (hash in removals) {
                                val removal = repository.removeByTargetHash(hash)
                                notices.add(LedgerNotice("卸载后清理账本", removal))
                                if (removal is LedgerWrite.Failed) {
                                    error = removal.error
                                    break
                                }
                            }
                        }
                        error
                    }
                )
                val result = if (submitted.completedSuccessfully) {
                    val warnings = prepared.cleanupSources()
                    if (warnings.isEmpty()) submitted else submitted.copy(
                        message = submitted.message + "\n部分源文件已变化或无法清理，已保留：\n" + warnings.joinToString("\n")
                    )
                } else submitted
                return MoveExecutionResult(result, notices.toList())
            } finally {
                try { prepared.close() } catch (cleanup: Exception) {
                    Log.w("InstallExecutor", "本次暂存清理失败，保留以便检查", cleanup)
                }
            }
        } finally {
            access.release(connection)
        }
    }

    private fun recordSuccessfulInstall(targets: List<JournalTarget>, notices: MutableList<LedgerNotice>): OperationError? {
        val records = targets.flatMap { it.recoveryFacts?.ledgerUpserts.orEmpty() }.map {
            InstalledModRecord(it.modUri, it.modName, it.familyKey, it.snapshotTargetHash,
                it.quality, it.usedAstc, it.installedAt)
        }
        return when (val result = repository.putAll(records)) {
            is LedgerWrite.Written -> null
            is LedgerWrite.Repaired -> {
                notices.add(LedgerNotice("装入记账", result))
                null
            }
            is LedgerWrite.Failed -> {
                notices.add(LedgerNotice("装入记账", result))
                result.error
            }
        }
    }
}
