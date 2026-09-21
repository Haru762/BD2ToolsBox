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
 * 阶段顺序即语义：`PLANNED → STAGED → TARGET_REPLACING → TARGET_REPLACED → LEDGER_COMMITTED`。
 * 出现 [TARGET_REPLACED] 而**没有** [LEDGER_COMMITTED]，就是那个窗口；而 [TARGET_REPLACING]
 * 是**写游戏文件之前**落的**意图**——它一旦落下、在同一 operation 后续的 [LEDGER_COMMITTED]
 * 之前，都算窗口未闭合（文件可能换了一半、可能没换，磁盘状态不明）。
 */
enum class JournalStage(val id: String) {
    /** 已决定要装这一批，游戏文件**一个字节都还没动**。 */
    PLANNED("planned"),

    /** 产物已就位（转换产物落进 `Download/Shared`），还没写游戏目录。 */
    STAGED("staged"),

    /**
     * **即将替换游戏文件的写前意图** —— 在真正调用 `copyDirectory` **之前**落盘。
     *
     * 它回答的不是「换没换成功」，而是「已经打算换、接下来那一下崩了磁盘状态就不明」。
     * 只要它落下、且同一 operation 后续没有 [LEDGER_COMMITTED]，恢复核对就判**待核对**，
     * 哪怕账本里已经有相同 uri/bundle 的旧记录也不例外（那是上一次安装留下的，
     * 与本次意图闭没闭合无关）。
     */
    TARGET_REPLACING("target_replacing"),

    /** **游戏文件已替换** —— 由 `ShizukuManager` 的提交返回值驱动，不是靠上层猜。 */
    TARGET_REPLACED("target_replaced"),

    /** 账本已落盘。到了这里，窗口关闭。 */
    LEDGER_COMMITTED("ledger_committed");

    /** 是否已经动过（或即将动）游戏文件（[TARGET_REPLACING] 起为真）。 */
    val touchesGameFiles: Boolean get() = this.ordinal >= TARGET_REPLACING.ordinal
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
    /** 产物里那层 hash 目录名。`null` = 路径没有这一层。move 路径由 `JournalMoveFacts` 从 relativePath 解析。 */
    val hashDir: String? = null,
    /** 写入的 `__data` 字节数摘要；取不到时为 0。核对「文件是不是这一批换的」用。 */
    val dataBytes: Long = 0L,
    /** 这次提交涉及哪些 mod（uri）。用来在恢复时判断「该不该补记这一条」。 */
    val modUris: List<String> = emptyList(),
    /** 新格式恢复事实；旧记录缺失时只能保留待核对，不能据此猜账本。 */
    val recoveryFacts: JournalRecoveryFacts? = null
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
 * journal 保存写前意图及恢复事实，读失败不是「没有在途操作」。
 * 损坏时保持原件不变、拒绝继续追加，并由仓储门禁将 Unknown 作为不能继续提交的状态。
 * 不自动改名或清空，也不从旧账本猜测缺失事实。
 */
sealed class JournalRead {
    /** 没有 journal 文件：没有进行中的提交。**这是正常态**，不是错误。 */
    object Absent : JournalRead()

    /** 有 journal 且能读。 */
    data class Loaded(val entries: List<JournalEntry>) : JournalRead()

    /** 有 journal 但读不成（损坏/半截）；保留原件，禁止据此放行提交。 */
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
 * [recoveryVerdict] 走 [JournalRecoveryProjection]：未闭合的 TARGET_REPLACING/TARGET_REPLACED
 * 在 facts 完整且无冲突时为待核对；缺 facts / 冲突 / 损坏为 Unknown。
 * **不再**仅凭账本 URI/bundle 把无内容证据的替换当完成。
 *
 * ## 只吃 `File`，不吃 `Context`
 *
 * 同 [InstalledLedgerStore]：能在纯 JVM 单测里构造。`Context` 依赖留在上层。
 *
 * ## 并发所有者
 *
 * 同 [InstalledLedgerStore]：本类**自己不加锁**，由 `InstalledModRepository`
 * 按 canonical 目录共享的进程内锁罩住整段「写游戏文件之后的 journal 推进 + 账本落盘」。
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
        if (text.isBlank()) {
            return JournalRead.Unreadable(
                OperationError(
                    kind = OperationErrorKind.MALFORMED_DATA,
                    message = "操作日志文件已存在但内容为空，无法确认上次提交状态",
                    target = journalFile.path
                )
            )
        }
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
     * @return `null` = 成功；非 null = 失败原因。写前意图持久化失败时调用方必须
     *   停止后续文件副作用；文件已动后失败则保留旧证据并报告待核对，不伪装全成功。
     *   已有日志不可读时原样保留，不用新记录覆盖未知的提交窗口。
     */
    fun append(entry: JournalEntry): OperationError? {
        val existing = when (val read = read()) {
            is JournalRead.Loaded -> read.entries
            JournalRead.Absent -> emptyList()
            is JournalRead.Unreadable -> return read.error
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
     * 按 journal 的文件顺序、分 operationId 投影所有尚未闭合的替换窗口。
     *
     * 同一操作的 [JournalStage.LEDGER_COMMITTED] 只关闭它之前的替换事件；
     * 之后的 [JournalStage.TARGET_REPLACING] / [JournalStage.TARGET_REPLACED]
     * 仍须核对，不能仅凭时间戳或账本已有同一 URI/bundle 就放行。
     *
     * - 没有未闭合窗口 → [RecoveryVerdict.Consistent]；
     * - 仍有窗口（包括缺少恢复 facts 的旧日志）→ [RecoveryVerdict.PendingReconciliation]；
     * - 日志不可读或投影发现不安全的事实冲突 → [RecoveryVerdict.Unknown]。
     *
     * [ledgerRecords] 仅为接口兼容保留，不参与关闭窗口。本层只读；
     * 内容观察、facts 完整性核对与原子补账由后续恢复流程负责。
     */
    @Suppress("UNUSED_PARAMETER")
    fun recoveryVerdict(ledgerRecords: Map<String, LedgerRecord>): RecoveryVerdict {
        // ledgerRecords 留给后续「观察目标后原子补账」。本层门禁不再仅凭账本 URI/bundle 放行。
        val entries = when (val read = read()) {
            is JournalRead.Loaded -> read.entries
            JournalRead.Absent -> return RecoveryVerdict.Consistent
            is JournalRead.Unreadable -> return RecoveryVerdict.Unknown(read.error)
        }
        return when (val projected = JournalRecoveryProjection.project(entries)) {
            JournalRecoveryProjection.Result.Clear -> RecoveryVerdict.Consistent
            is JournalRecoveryProjection.Result.Pending -> RecoveryVerdict.PendingReconciliation(
                bundleNames = projected.bundleNames,
                modUris = projected.modUris,
                detectedAtMillis = projected.detectedAtMillis
            )
            is JournalRecoveryProjection.Result.Unsafe -> RecoveryVerdict.Unknown(
                OperationError(
                    kind = OperationErrorKind.MALFORMED_DATA,
                    message = "操作日志无法安全恢复：${projected.reason}",
                    target = journalFile.path
                )
            )
        }
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 解析 journal。顶层形状与账本同约定（`schemaVersion` + 单一载荷键 `entries`），
     * recoveryFacts 为可选的增量字段；旧记录仍可读，但不能作为内容或补账凭据。
     * 新事实必须在 Gson 转换前校验，避免缺字段默认值与宽松类型转换掩盖坏数据。
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
        if (versionElement.asString != SCHEMA_VERSION.toString()) {
            return JournalRead.Unreadable(
                OperationError(
                    kind = OperationErrorKind.MALFORMED_DATA,
                    message = "操作日志 schemaVersion=${versionElement.asString}，" +
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
        // 先检查原始 JSON 中的恢复事实，再交给 Gson；整个文件任一目标失败即判不可读。
        for ((i, rawEntry) in entriesElement.asJsonArray.withIndex()) {
            if (!rawEntry.isJsonObject) continue // 基础形状由下方 DTO 检查统一报错。
            val rawTargets = rawEntry.asJsonObject.get("targets")
            if (rawTargets == null || !rawTargets.isJsonArray) continue
            for ((j, rawTarget) in rawTargets.asJsonArray.withIndex()) {
                if (!rawTarget.isJsonObject) continue
                val problem = JournalRecoveryFactsCodec.validate(rawTarget.asJsonObject)
                if (problem != null) {
                    return JournalRead.Unreadable(
                        OperationError(
                            kind = OperationErrorKind.MALFORMED_DATA,
                            message = "操作日志第 $i 条的第 $j 个目标恢复事实无效：$problem",
                            target = journalFile.path
                        )
                    )
                }
            }
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
            // Gson 的 Unsafe 实例化会绕过 Kotlin 非空约束。即使旧日志没有恢复 facts，
            // targets、bundleName 与 modUris 也必须在此判废，不能让 null 流入投影或内容观察。
            // 判废而不是回退成空表：缺字段的条目不可信，保留证据并返回「不知道」。
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
                if (target == null || target.bundleName == null || target.modUris == null) {
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
            val encoded = gson.toJson(root)
            // 写入端也必须满足同一契约；拒绝无效对象时尚未触碰旧日志。
            when (val validation = parse(encoded)) {
                is JournalRead.Unreadable -> return validation.error
                else -> Unit
            }
            encoded
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
