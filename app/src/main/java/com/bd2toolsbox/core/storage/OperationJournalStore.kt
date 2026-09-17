package com.bd2toolsbox.core.storage

import com.bd2toolsbox.core.fileaccess.AtomicFileWriter
import com.bd2toolsbox.core.model.OperationError
import com.bd2toolsbox.core.model.OperationErrorKind
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/**
 * 一次装入提交走到哪一步（T08）。
 *
 * 这四个阶段**只用来核对一个窗口**：`ShizukuManager.moveDownloadToGame()` 的
 * `copyDirectory` 把游戏文件换掉之后、`InstalledModRepository.putAll()` 把账本落盘之前
 * 那一小段。在那段里崩溃，磁盘上会出现「游戏文件已经是新的、账本还记着旧的」这个
 * 不自洽状态。
 *
 * **它不承担「任意步骤断点续跑」的承诺**（计划原文写得很明确）：journal 不做通用事务
 * 回滚、不做重放、不驱动恢复动作。它只回答一个问题 —— 「上次那次提交，文件到底换没换？」
 *
 * 阶段顺序即语义：`PLANNED → STAGED → TARGET_REPLACED → LEDGER_COMMITTED`。
 * 出现 [TARGET_REPLACED] 而**没有** [LEDGER_COMMITTED]，就是那个窗口。
 */
enum class JournalStage(val id: String) {
    /** 已决定要装这一批，游戏文件**一个字节都还没动**。 */
    PLANNED("planned"),

    /** 产物已就位（转换产物落进 `Download/Shared`），还没写游戏目录。 */
    STAGED("staged"),

    /** **游戏文件已替换** —— 由 `ShizukuManager` 的提交返回值驱动，不是靠上层猜。 */
    TARGET_REPLACED("target_replaced"),

    /** 账本已落盘。到了这里，窗口关闭。 */
    LEDGER_COMMITTED("ledger_committed");

    /** 是否已经动过游戏文件（[TARGET_REPLACED] 起为真）。 */
    val touchesGameFiles: Boolean get() = this.ordinal >= TARGET_REPLACED.ordinal
}

/**
 * 一个提交目标（一个 bundle）在 journal 里的摘要。
 *
 * 字段刻意都是**基本类型与字符串**：journal 要在崩溃后、在只有 `File` 可用的环境里
 * 被读回来核对，不能依赖任何 Android 对象，也不能依赖 `ModInfo`（它的 `uri` 是
 * `android.net.Uri`）。这也让整个类能在 JVM 单测里直接构造。
 */
data class JournalTarget(
    /** bundle 名（= `targetHash`）。核对时用它去游戏目录找文件。 */
    val bundleName: String,
    /** 产物里那层 hash 目录名。`null` = 这条路径没这一层（如 `moveFilesToGame`）。 */
    val hashDir: String? = null,
    /** 写入的 `__data` 字节数摘要；取不到时为 0。核对「文件是不是这一批换的」用。 */
    val dataBytes: Long = 0L,
    /** 这次提交涉及哪些 mod（uri）。用来在恢复时判断「该不该补记这一条」。 */
    val modUris: List<String> = emptyList()
)

/**
 * 一次装入提交的 journal 条目（T08）。
 *
 * 持久化形态（顶层 `schemaVersion` + 单一载荷键 `entries`，照本仓既有约定）：
 *
 * ```json
 * {"schemaVersion":1,"entries":[{"operationId":"...","stage":"target_replaced", ...}]}
 * ```
 *
 * **字段名必须保留**（R8），理由同 [LedgerRecord]：混淆后 Gson 读不回自己的文件。
 * 保留规则见 `app/proguard-rules.pro`。
 */
data class JournalEntry(
    /** 这次操作的标识。同一批提交的多次阶段推进用同一个 id 串起来。 */
    val operationId: String,
    val stage: String,
    /** 阶段落盘时刻（epoch ms）。 */
    val atMillis: Long,
    /** 这次提交要处理的全部目标。 */
    val targets: List<JournalTarget> = emptyList(),
    /** 人类可读的补充说明（失败原因等），可为空。 */
    val note: String? = null
) {
    /** 反序列化出来的 [stage] 字符串对应的枚举；不认识则为 null（不猜）。 */
    val stageOrNull: JournalStage? get() = JournalStage.values().firstOrNull { it.id == stage }
}

/**
 * journal 的一次读取结果。
 *
 * 与 [LedgerRead] 同样是显式分态，但**语义不同、不能照抄**：
 * journal 是**诊断证据**，不是要保护的唯一事实源 —— 它丢了只损失「能不能核对」，
 * 不损失用户数据（真实事实在游戏目录与账本里）。所以它的失败处置比账本轻：
 * 损坏时**只报错、不阻断**任何业务动作，也**不做改名保留**（没有那个必要，
 * 而且会在每次启动时累积垃圾文件）。
 */
sealed class JournalRead {
    /** 没有 journal 文件：没有进行中的提交。**这是正常态**，不是错误。 */
    object Absent : JournalRead()

    /** 有 journal 且能读。 */
    data class Loaded(val entries: List<JournalEntry>) : JournalRead()

    /** 有 journal 但读不成（损坏/半截）。**不阻断业务**，只上报。 */
    data class Unreadable(val error: OperationError) : JournalRead()
}

/**
 * 「文件已换、账本未存」这个窗口的核对（T08）。
 *
 * ## 它解决的到底是什么
 *
 * 唯一的游戏文件提交点在 `ShizukuManager.kt` 的 `copyDirectory`（两处：
 * `moveDownloadToGame` 与 `installConvertedBundle`）。提交成功之后、账本落盘之前，
 * 中间还夹着一次 `withContext(Dispatchers.IO)` 切线程，窗口不算窄。在那里崩溃，
 * 重启后账本说「这个 mod 没装」，而游戏目录里那份 `__data` 已经是 mod 版 ——
 * 用户的下一步动作（比如「卸载」）会建立在一个错的事实上。
 *
 * [recoveryVerdict] 就是回答这一格的：把 journal 的最后一条与账本现状对一下，
 * 给出一个**明确的待核对状态**，而不是把它伪装成「全成功」或「全失败」。
 *
 * ## 只吃 `File`，不吃 `Context`
 *
 * 同 [InstalledLedgerStore]：能在纯 JVM 单测里构造。`Context` 依赖留在上层。
 *
 * ## 并发所有者
 *
 * 同 [InstalledLedgerStore]：本类**自己不加锁**，由 `InstalledModRepository`
 * 的实例锁罩住整段「写游戏文件之后的 journal 推进 + 账本落盘」。
 */
class OperationJournalStore(private val journalFile: File) {

    /**
     * 读 journal。**不抛异常、零副作用**。
     *
     * 与账本的一个关键差别：**文件不存在是正常的**（没有进行中的提交），
     * 所以 [JournalRead.Absent] 不是错误态。
     */
    fun read(): JournalRead {
        if (!journalFile.exists()) return JournalRead.Absent
        val text = try {
            journalFile.readText()
        } catch (e: Exception) {
            return JournalRead.Unreadable(
                OperationError(
                    kind = OperationErrorKind.IO,
                    message = "操作日志读取失败：" + e.message.orEmpty(),
                    detail = e.toString(),
                    target = journalFile.path
                )
            )
        }
        if (text.isBlank()) return JournalRead.Absent
        return try {
            parse(text)
        } catch (e: Exception) {
            JournalRead.Unreadable(
                OperationError(
                    kind = OperationErrorKind.MALFORMED_DATA,
                    message = "操作日志无法解析（" + e.javaClass.simpleName + "）：" + e.message.orEmpty(),
                    detail = e.toString(),
                    target = journalFile.path
                )
            )
        }
    }

    /**
     * 追加一条阶段记录并原子落盘。整个 journal 文件**每次整体重写**（它是小文件、
     * 且「最后一条是什么阶段」就是全部要读的信息）。
     *
     * @return `null` = 成功；非 null = 失败原因。失败**不阻断**业务（调用方按
     *   「诊断能力降级」处置、记日志），因为 journal 写不进去不该让一次已经成功的
     *   游戏文件提交变成失败。
     */
    fun append(entry: JournalEntry): OperationError? {
        val existing = when (val read = read()) {
            is JournalRead.Loaded -> read.entries
            // 损坏时**丢弃**旧内容重开一份：journal 是诊断证据，
            // 保住「以后还能核对」比留住一份读不出来的历史更有价值。
            // 这不是静默丢弃 —— 上一次的 Unreadable 已经由 read() 上报过。
            JournalRead.Absent, is JournalRead.Unreadable -> emptyList()
        }
        return write(existing + entry)
    }

    /**
     * 清掉 journal（一次提交彻底收尾后调用）。
     *
     * 删除失败**不算错误**（返回 `null`）：文件还在只意味着下次启动会多核对一次，
     * 而那一次核对会因为账本已经对得上而给出 [RecoveryVerdict.Consistent]。
     */
    fun clear(): OperationError? = try {
        if (journalFile.exists() && !journalFile.delete()) {
            // 删不掉时退化成写空表而不是留着旧条目：旧条目会让下次启动误报「有待核对」。
            write(emptyList())
        } else {
            null
        }
    } catch (e: Exception) {
        OperationError(
            kind = OperationErrorKind.IO,
            message = "操作日志清理失败：" + e.message.orEmpty(),
            detail = e.toString(),
            target = journalFile.path
        )
    }

    /**
     * 一次提交的核对结论（T08）。
     *
     * 三态而不是「成功/失败」：**「不知道」必须能表达出来**，这正是计划验收第 2 条
     * （「不能自动伪装成全失败或全成功」）要的那一格。
     */
    sealed class RecoveryVerdict {
        /** journal 里没有「已换文件但未提交账本」的痕迹。正常启动。 */
        object Consistent : RecoveryVerdict()

        /**
         * **有待核对**：journal 说游戏文件已经换了（[JournalStage.TARGET_REPLACED]），
         * 但账本里查不到这次提交涉及的记录。窗口真的裂开了。
         *
         * [bundleNames] 是那次提交涉及的 bundle 名，[modUris] 是涉及的 mod ——
         * 上层据此提示用户「这几个包的记账可能不准，建议重新扫描/重装」。
         */
        data class PendingReconciliation(
            val bundleNames: List<String>,
            val modUris: List<String>,
            val detectedAtMillis: Long
        ) : RecoveryVerdict()

        /**
         * journal 读不出来。**这也不是「一切正常」**，但也不是「有待核对」——
         * 我们确实不知道。单独一态，避免上层把「不知道」当成「没问题」。
         */
        data class Unknown(val error: OperationError) : RecoveryVerdict()
    }

    /**
     * 把 journal 的最后一条与账本现状对一下，给出**明确的待核对状态**。
     *
     * ## 判据（只有一条，写在明处）
     *
     * 取 journal 里**时间最新**的一条 [JournalStage.TARGET_REPLACED] 条目，
     * 看它涉及的目标是不是**全部**都已经在 [ledgerRecords] 里：
     *
     * - 全部已在 → [RecoveryVerdict.Consistent]（那次提交后来成功落了账本，
     *   journal 只是没来得及清）；
     * - 有任何一个不在 → [RecoveryVerdict.PendingReconciliation]。
     *
     * **为什么看的是「记录在不在」而不是「文件在不在」**：这一格要回答的正是
     * 「账本有没有漏掉一次已经发生的文件变更」。文件在不在需要设备与 Shizuku，
     * 而账本本身就在手上 —— 判据要能用**手上就有的东西**独立成立，
     * 不能建立在「再去问一次设备」上（那样崩溃恢复就依赖设备在线了）。
     *
     * **为什么不看 [JournalStage.LEDGER_COMMITTED]**：那个阶段是同一个进程在
     * 账本落盘成功之后写的。若在写它之前崩溃，journal 里就没有它 —— 但这**不等于**
     * 账本没落盘（可能在落盘之后、写 journal 之前崩）。所以用它当判据会误报。
     * 账本的实际内容才是事实，journal 只负责指出「去看哪个 bundle」。
     *
     * ## 已知的保守面（登记，不是缺陷）
     *
     * 若那条 [JournalStage.TARGET_REPLACED] 涉及的 mod **后来被用户正常卸载了**，
     * 这里会报 [RecoveryVerdict.PendingReconciliation]（账本里确实没有它了）。
     * 这是**假阳性**，代价是一次多余的提示。反过来（把真窗口当正常）会丢数据，
     * 两害相权取轻。
     */
    fun recoveryVerdict(ledgerRecords: Map<String, LedgerRecord>): RecoveryVerdict {
        val entries = when (val read = read()) {
            is JournalRead.Loaded -> read.entries
            JournalRead.Absent -> return RecoveryVerdict.Consistent
            is JournalRead.Unreadable -> return RecoveryVerdict.Unknown(read.error)
        }
        // 取时间最新的那条「目标已替换」。同一毫秒内多条时用下标兜底取最后一条，
        // 让结果与文件里的书写顺序一致、可复现。
        val last = entries.withIndex()
            .filter { it.value.stageOrNull == JournalStage.TARGET_REPLACED }
            .maxWithOrNull(compareBy({ it.value.atMillis }, { it.index }))
            ?.value
            ?: return RecoveryVerdict.Consistent

        val known = ledgerRecords.keys
        val missing = last.targets.filter { target ->
            target.modUris.isEmpty() || target.modUris.any { it !in known }
        }
        if (missing.isEmpty()) return RecoveryVerdict.Consistent

        return RecoveryVerdict.PendingReconciliation(
            bundleNames = missing.map { it.bundleName }.distinct(),
            modUris = missing.flatMap { it.modUris }.distinct(),
            detectedAtMillis = last.atMillis
        )
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 解析 journal。顶层形状与账本同约定（`schemaVersion` + 单一载荷键 `entries`），
     * 但**没有 legacy 形态要兼容** —— journal 是本任务全新引入的东西，
     * 磁盘上不可能存在更早的版本。
     */
    private fun parse(text: String): JournalRead {
        val root = JsonParser.parseString(text)
        if (!root.isJsonObject) {
            return JournalRead.Unreadable(
                OperationError(
                    kind = OperationErrorKind.MALFORMED_DATA,
                    message = "操作日志顶层不是 JSON 对象",
                    target = journalFile.path
                )
            )
        }
        val obj = root.asJsonObject
        val versionElement = obj.get(InstalledLedgerStore.SCHEMA_VERSION_KEY)
        if (versionElement == null || !versionElement.isJsonPrimitive ||
            !versionElement.asJsonPrimitive.isNumber
        ) {
            return JournalRead.Unreadable(
                OperationError(
                    kind = OperationErrorKind.MALFORMED_DATA,
                    message = "操作日志缺少数字型 ${InstalledLedgerStore.SCHEMA_VERSION_KEY}",
                    target = journalFile.path
                )
            )
        }
        if (versionElement.asInt != SCHEMA_VERSION) {
            return JournalRead.Unreadable(
                OperationError(
                    kind = OperationErrorKind.MALFORMED_DATA,
                    message = "操作日志 schemaVersion=${versionElement.asInt}，" +
                        "本版本只认 $SCHEMA_VERSION",
                    target = journalFile.path
                )
            )
        }
        val entriesElement = obj.get(ENTRIES_KEY)
        if (entriesElement == null || !entriesElement.isJsonArray) {
            return JournalRead.Unreadable(
                OperationError(
                    kind = OperationErrorKind.MALFORMED_DATA,
                    message = "操作日志缺少 $ENTRIES_KEY 数组",
                    target = journalFile.path
                )
            )
        }
        val type = object : com.google.gson.reflect.TypeToken<List<JournalEntry>>() {}.type
        val entries: List<JournalEntry>? = gson.fromJson(entriesElement, type)
        if (entries == null) {
            return JournalRead.Unreadable(
                OperationError(
                    kind = OperationErrorKind.MALFORMED_DATA,
                    message = "操作日志的 $ENTRIES_KEY 解析成了 null",
                    target = journalFile.path
                )
            )
        }
        // 条目本身缺字段时**整份判不可读**而不是丢掉那一条：核对判据建立在
        // 「最后一条 TARGET_REPLACED 说了什么」上，静默丢一条就可能正好丢掉它，
        // 于是窗口被误判成「没有」。这与账本那边的取向一致。
        for ((i, entry) in entries.withIndex()) {
            // 「恒为 false」的 null 判断是**故意的**：Gson 用 Unsafe 造实例、
            // 不理会 Kotlin 的非空约束，运行期真的会是 null（同 InstalledLedgerStore 的说明）。
            @Suppress("SENSELESS_COMPARISON")
            if (entry == null || entry.operationId == null || entry.stage == null) {
                return JournalRead.Unreadable(
                    OperationError(
                        kind = OperationErrorKind.MALFORMED_DATA,
                        message = "操作日志第 $i 条缺少必需字段",
                        target = journalFile.path
                    )
                )
            }
            if (entry.stageOrNull == null) {
                return JournalRead.Unreadable(
                    OperationError(
                        kind = OperationErrorKind.MALFORMED_DATA,
                        message = "操作日志第 $i 条的 stage='${entry.stage}' 不是已知阶段",
                        target = journalFile.path
                    )
                )
            }
            // targets 与嵌套的 modUris **必须在这里判废**（T08 二轮整改，复核真缺陷 3）：
            // [recoveryVerdict] 直接对它们迭代，而 Gson 用 Unsafe 造实例、不理会 Kotlin
            // 的非空约束 —— 一份被外部改过（或未来版本写出）的同 schema journal 里
            // 少了 `targets`，或某个 target 少了 `modUris`，就会让恢复核对抛 NPE。
            // 今天没有生产调用者所以是潜伏的；一旦接进启动路径，那一下就是**启动崩**。
            // 判废（而不是回退成空表）与上面的取向一致：缺字段的条目不可信，宁可说「不知道」。
            @Suppress("SENSELESS_COMPARISON")
            if (entry.targets == null) {
                return JournalRead.Unreadable(
                    OperationError(
                        kind = OperationErrorKind.MALFORMED_DATA,
                        message = "操作日志第 $i 条缺少 targets 数组",
                        target = journalFile.path
                    )
                )
            }
            for ((j, target) in entry.targets.withIndex()) {
                @Suppress("SENSELESS_COMPARISON")
                if (target == null || target.modUris == null) {
                    return JournalRead.Unreadable(
                        OperationError(
                            kind = OperationErrorKind.MALFORMED_DATA,
                            message = "操作日志第 $i 条的第 $j 个目标缺少必需字段",
                            target = journalFile.path
                        )
                    )
                }
            }
        }
        return JournalRead.Loaded(entries)
    }

    private fun write(entries: List<JournalEntry>): OperationError? {
        val payload = try {
            val root = JsonObject()
            root.addProperty(InstalledLedgerStore.SCHEMA_VERSION_KEY, SCHEMA_VERSION)
            root.add(ENTRIES_KEY, gson.toJsonTree(entries))
            gson.toJson(root)
        } catch (e: Exception) {
            return OperationError(
                kind = OperationErrorKind.MALFORMED_DATA,
                message = "操作日志序列化失败：" + e.message.orEmpty(),
                detail = e.toString(),
                target = journalFile.path
            )
        }
        return try {
            AtomicFileWriter().write(target = journalFile) { out ->
                out.write(payload.toByteArray(Charsets.UTF_8))
            }
            null
        } catch (e: Exception) {
            OperationError(
                kind = if (e is SecurityException) OperationErrorKind.PERMISSION_DENIED
                else OperationErrorKind.IO,
                message = "操作日志写入失败（旧日志保持原样）：" + e.message.orEmpty(),
                detail = e.toString(),
                target = journalFile.path
            )
        }
    }

    companion object {
        /** 顶层 schema 版本。 */
        const val SCHEMA_VERSION = 1

        /** 单一载荷键。 */
        const val ENTRIES_KEY = "entries"

        private val gson = Gson()
    }
}
