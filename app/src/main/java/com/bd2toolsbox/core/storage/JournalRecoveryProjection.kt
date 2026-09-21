package com.bd2toolsbox.core.storage

/**
 * 纯日志恢复投影（T08）。无 I/O、不读游戏文件、不改账本。
 *
 * 输入已成功解析的 [JournalEntry] 序列（文件书写顺序），输出：
 * - [Result.Clear]：没有未闭合的写游戏窗口
 * - [Result.Pending]：有未闭合窗口，可列出涉及的 operation/bundle/uri
 * - [Result.Unsafe]：日志自相矛盾，不能安全自动恢复
 */
internal object JournalRecoveryProjection {

    sealed class Result {
        object Clear : Result()

        data class Pending(
            val operations: List<OpenOperation>
        ) : Result() {
            val operationIds: List<String> get() = operations.map { it.operationId }
            val bundleNames: List<String> get() = operations.flatMap { it.bundleNames }.distinct()
            val modUris: List<String> get() = operations.flatMap { it.modUris }.distinct()
            val detectedAtMillis: Long get() = operations.maxOf { it.atMillis }
        }

        data class Unsafe(val reason: String) : Result()
    }

    data class OpenOperation(
        val operationId: String,
        val targets: List<JournalTarget>,
        val atMillis: Long,
        val lastIndex: Int,
        val hasCompleteFacts: Boolean
    ) {
        val bundleNames: List<String> get() = targets.map { it.bundleName }.distinct()
        val modUris: List<String> get() = targets.flatMap { it.modUris }.distinct()
    }

    fun project(entries: List<JournalEntry>): Result {
        if (entries.isEmpty()) return Result.Clear

        val pendingOps = mutableListOf<OpenOperation>()

        val ops = entries.withIndex().groupBy { it.value.operationId }

        for ((opId, opEntries) in ops) {
            val lastCommitIndex = opEntries
                .filter { it.value.stage == JournalStage.LEDGER_COMMITTED.id }
                .maxOfOrNull { it.index }

            val openEvents = opEntries.filter {
                (it.value.stage == JournalStage.TARGET_REPLACING.id || 
                 it.value.stage == JournalStage.TARGET_REPLACED.id) &&
                (lastCommitIndex == null || it.index > lastCommitIndex)
            }

            if (openEvents.isEmpty()) continue

            if (openEvents.any { it.value.targets.isEmpty() }) {
                return Result.Unsafe("Open event has empty targets")
            }

            val mergedTargetsByBundle = mutableMapOf<String, JournalTarget>()
            val bundleByUri = mutableMapOf<String, String>()

            for (event in openEvents) {
                val targets = event.value.targets

                val bundleNamesInEntry = targets.map { it.bundleName }
                if (bundleNamesInEntry.size != bundleNamesInEntry.toSet().size) {
                    return Result.Unsafe("Duplicate bundleName in one entry")
                }

                val urisInEntry = targets.flatMap { it.modUris }
                if (urisInEntry.size != urisInEntry.toSet().size) {
                    return Result.Unsafe("Duplicate URI in one entry")
                }

                for (target in targets) {
                    for (uri in target.modUris) {
                        val existingBundle = bundleByUri[uri]
                        if (existingBundle != null && existingBundle != target.bundleName) {
                            return Result.Unsafe("Same URI mapped to different bundles")
                        }
                        bundleByUri[uri] = target.bundleName
                    }

                    val existing = mergedTargetsByBundle[target.bundleName]
                    if (existing != null) {
                        val ef = existing.recoveryFacts
                        val tf = target.recoveryFacts
                        if (ef == null && tf == null) {
                            // Ok
                        } else if (ef == null || tf == null) {
                            return Result.Unsafe("Incompatible recoveryFacts (one null, one non-null)")
                        } else {
                            if (ef.expectedContentSha256 != tf.expectedContentSha256 ||
                                ef.ledgerUpserts != tf.ledgerUpserts ||
                                existing.hashDir != target.hashDir ||
                                existing.dataBytes != target.dataBytes ||
                                existing.modUris != target.modUris
                            ) {
                                return Result.Unsafe("Incompatible target facts across open events")
                            }
                        }
                    } else {
                        mergedTargetsByBundle[target.bundleName] = target
                    }
                }
            }

            val mergedTargetsList = mergedTargetsByBundle.values.toList()
            val hasCompleteFacts = mergedTargetsList.all { it.recoveryFacts != null }

            pendingOps.add(
                OpenOperation(
                    operationId = opId,
                    targets = mergedTargetsList,
                    atMillis = openEvents.last().value.atMillis,
                    lastIndex = openEvents.last().index,
                    hasCompleteFacts = hasCompleteFacts
                )
            )
        }

        return if (pendingOps.isEmpty()) Result.Clear else Result.Pending(pendingOps)
    }
}
