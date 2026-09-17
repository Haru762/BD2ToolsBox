package com.bd2toolsbox.data.repository

import android.content.Context
import android.util.Log
import com.bd2toolsbox.core.model.OperationError
import com.bd2toolsbox.core.model.OperationErrorKind
import com.bd2toolsbox.core.storage.InstalledLedgerStore
import com.bd2toolsbox.core.storage.JournalEntry
import com.bd2toolsbox.core.storage.JournalStage
import com.bd2toolsbox.core.storage.JournalTarget
import com.bd2toolsbox.core.storage.LedgerRead
import com.bd2toolsbox.core.storage.LedgerRecord
import com.bd2toolsbox.core.storage.LedgerWrite
import com.bd2toolsbox.core.storage.OperationJournalStore
import com.bd2toolsbox.data.model.InstalledModRecord
import java.io.File

/**
 * 「哪些 mod 当前装在游戏里」的账本。
 *
 * 刻意独立于 [ModRepository] 的 mod_cache.json：那份缓存一旦 bundle 索引更新就整体作废
 * （见 ModRepository.loadModCache），而装入记录必须在游戏更新后继续存活 —— 正是要靠它
 * 判断出「这个 mod 需要重新应用」。
 *
 * 记账只回答「装了哪些 mod、装在哪个 bundle 上」。至于那个 bundle 现在究竟是干净原版
 * 还是装着 mod，由干净检测（catalog 权威值 vs 游戏目录实际值）客观给出，不靠这里推断。
 *
 * **主键是 mod 的 uri，一个 mod 一条记录**（见 [InstalledModRecord] 的说明：一个 bundle
 * 常装着多个角色的 mod，按 familyKey 一包一条会导致卸载连带和状态误判；而 familyKey
 * 本身也不唯一）。
 *
 * ## T08 之后的形状
 *
 * 落盘与四态读取搬去了 [InstalledLedgerStore]（`core/storage`，只吃 `File`、
 * 不依赖 `android.*`，因此能在纯 JVM 单测里跑）。本类保留**两件它比 Store 更清楚的事**：
 *
 * 1. **`Context` 依赖**（[file] 的唯一外部输入是 `context.filesDir`）；
 * 2. **并发所有权** —— 见下。
 *
 * ## 并发所有者：本类是唯一的所有者
 *
 * 账本的写入是「读全表 → 改一条 → 写回全表」的 read-modify-write。锁必须罩住**整段**，
 * 否则并发的两次写入各自读了旧表、后写的把先写的覆盖掉（丢记录）。
 *
 * 所以 [InstalledLedgerStore] / [OperationJournalStore] **自己不加锁**，
 * 锁留在这里的 `@Synchronized` 方法上（改动前就有、本轮**原样保留**这层保护）：
 * 它们是同一把实例锁、重入安全，于是 `put()` 里调 [load] 不会自锁，
 * 而 `put()` 的整个「读-改-写」在同一段临界区里。**这仍然只是单实例锁** ——
 * 两个 `MainViewModel` 实例（多进程 / 重复初始化）之间不互斥，与改动前一致，
 * 本轮没有把口径扩大到跨进程。
 *
 * ## 失败必须传回上层
 *
 * [save] 返回 `OperationError?`（null = 成功）。改动前它返回 `Unit` 且只
 * `printStackTrace()`，调用方无从知道写失败了 —— 「报错了」与「写成功了」在调用点
 * 长得一模一样。现在失败会经 [lastError] 留在实例上，由上层取用；
 * 破坏性的 `put`/`remove` 系列**不因为写失败而回滚内存**（账本没有内存态，
 * 每次都是现读现写），但旧文件始终可用。
 */
class InstalledModRepository(private val context: Context) {

    companion object {
        private const val FILENAME = "installed_mods.json"
        private const val TAG = "InstalledModRepository"

        /**
         * journal 文件名。
         *
         * 用**独立文件**而不是塞进账本：journal 要能在账本根本没写成的情况下留下痕迹，
         * 塞进账本会让「账本写失败」连带把证据也弄丢。
         */
        private const val JOURNAL_FILENAME = "install_journal.json"
    }

    private val ledgerStore: InstalledLedgerStore
    private val journalStore: OperationJournalStore

    /**
     * journal 里当前这次提交的 id。一次 [beginSubmit] 到 [finishSubmit] 之间不变，
     * 让同一批提交的多个阶段在 journal 里能串起来。
     */
    @Volatile
    private var currentOperationId: String? = null

    /**
     * 当前这次提交的目标摘要（[beginSubmit] 时记下）。
     *
     * 存在理由：`markTargetReplaced()` 默认不带参数，必须能沿用这一批目标 ——
     * 那条 journal 的 `targets` 是恢复判据唯一的输入，写成空表会让
     * 「文件已换、账本未存」这个窗口查不出来（第一版就是这么写的，被用例抓红）。
     */
    @Volatile
    private var currentTargets: List<JournalTarget> = emptyList()

    /**
     * 最近一次读/写失败，供上层取用与提示。
     *
     * **是实例状态、不是全局单例**：两个仓储实例各有各的账本文件，
     * 把它们的最新错误混在一个静态变量里会让 A 实例的失败被 B 实例的调用方取走。
     *
     * 由 [consumeLastError] 以 take 语义取走（取过即清），避免同一条错误被反复提示。
     */
    @Volatile
    private var lastError: OperationError? = null

    init {
        val filesDir = context.filesDir
        ledgerStore = InstalledLedgerStore(File(filesDir, FILENAME))
        journalStore = OperationJournalStore(File(filesDir, JOURNAL_FILENAME))
    }

    /** 账本文件。测试与排查用；唯一的外部依赖就是 `context.filesDir`。 */
    fun file(): File = File(context.filesDir, FILENAME)

    /** journal 文件。 */
    fun journalFile(): File = File(context.filesDir, JOURNAL_FILENAME)

    // ------------------------------------------------------------------ 读

    /**
     * modUri -> 记录。**失败时返回空表**，与改动前的返回类型保持一致 ——
     * 但不再是「失败看起来像空表」：失败的分类由 [readLedger] 给出，
     * 需要区分的地方（尤其是写入前）应当调它而不是 [load]。
     */
    @Synchronized
    fun load(): Map<String, InstalledModRecord> = when (val read = readLedger()) {
        is LedgerRead.Loaded -> read.records.mapValues { it.value.toModel() }
        // NotFound / Empty / Corrupt / IoError 在「只想要一张表」的调用点上
        // 都退化成空表 —— 这正是改动前的行为，保留它以免打乱所有只读调用方。
        // **写入路径不许走这里**：它必须先看 readLedger() 的分态。
        else -> emptyMap()
    }

    /**
     * 账本的**分态**读取（T08）。写入前的判断一律走这里。
     *
     * 读失败会把错误留在 [consumeLastError] 里，且**不改动磁盘上的任何东西**
     * （连损坏原件都不改名 —— 改名的决定属于写入路径，见 [put]）。
     */
    @Synchronized
    fun readLedger(): LedgerRead = try {
        ledgerStore.read()
    } catch (e: Exception) {
        // Store 自称不抛，这里是纵深防御：真抛了也不能让读账本崩掉调用方。
        LedgerRead.IoError(
            OperationError(
                kind = OperationErrorKind.UNKNOWN,
                message = "装入记录读取异常：" + e.message.orEmpty(),
                detail = e.toString(),
                target = file().path
            )
        )
    }

    /**
     * 账本是否是需要丢弃的旧格式。
     *
     * 旧格式一条记录含多个 modUris 却只有一个 familyKey，无法还原出「哪个 uri 对应哪个
     * familyKey」，所以没法安全迁移。账本只是记账，清空不动游戏里的实际文件 ——
     * 重新装一次就能重建准确记录，比带着一份错账继续跑安全。
     *
     * **T08 未改本函数的行为**（简报 §3.3 的裁决）。新 Store 的 `schemaVersion` 机制
     * 让这条字符串包含判断逐渐失去意义，但把它改成版本比较**不是 T08 的范围** ——
     * 退役交给 T25/T35。这里只登记这个接缝。
     */
    @Synchronized
    fun needsReset(): Boolean {
        val f = file()
        if (!f.exists()) return false
        return try {
            val text = f.readText()
            if (text.isBlank()) false else text.contains("\"modUris\"")
        } catch (e: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------ 写

    /**
     * 记下一次成功装入。同一个 mod 再装即覆盖（更新画质、时间等）。
     *
     * **返回 [LedgerWrite]**（三态，见那个类的说明）。改动前这些方法返回 `Unit`，
     * 失败只打印 —— 调用方以为写成功了。
     */
    @Synchronized
    fun put(record: InstalledModRecord): LedgerWrite {
        val read = readForWrite()
        if (!read.writable) return LedgerWrite.Failed(read.blocked!!)
        return save(read, read.records!!.toMutableMap().apply { put(record.modUri, record) })
    }

    @Synchronized
    fun putAll(records: List<InstalledModRecord>): LedgerWrite {
        // 空批次既不是「写成功」也不是「失败」：什么都没发生，报 Written 最贴合事实。
        if (records.isEmpty()) return LedgerWrite.Written
        val read = readForWrite()
        if (!read.writable) return LedgerWrite.Failed(read.blocked!!)
        return save(read, read.records!!.toMutableMap().apply { records.forEach { put(it.modUri, it) } })
    }

    /** 某个 bundle 上当前装着哪些 mod。装入时要把它们一起重新打包，否则会丢。 */
    @Synchronized
    fun listByTargetHash(targetHash: String): List<InstalledModRecord> =
        load().values.filter { it.snapshotTargetHash == targetHash }

    /** 移除一个 mod 的记录。调用方需自行判断该 bundle 是否还有剩余（见 [listByTargetHash]）。 */
    @Synchronized
    fun remove(modUri: String): LedgerWrite {
        val read = readForWrite()
        if (!read.writable) return LedgerWrite.Failed(read.blocked!!)
        if (!read.records!!.containsKey(modUri)) return LedgerWrite.Written
        return save(read, read.records.toMutableMap().apply { remove(modUri) })
    }

    /**
     * 读出并销掉一条记录（T08 二轮整改，复核真缺陷 1）。
     *
     * ## 它为什么不再返回 `InstalledModRecord?`
     *
     * 「删源文件」（`MainViewModel.deleteModFolder`）与「批量删除」两条真实用户路径
     * 都是它的调用方。改动前它返回裸的 `InstalledModRecord?`，于是**三件完全不同的事**
     * 在调用方眼里是同一个 `null`：
     *
     * 1. 账本里本来就没有这条 —— 什么都不用做；
     * 2. 账本写失败、记录还在账本里 —— 调用方以为销账了，其实没有；
     * 3. 账本损坏、原件被改名搬成 `.corrupt-*`、账本被重建成空表 —— 原件不在原位了，
     *    账本路径也变了。
     *
     * 第 3 种是 T08 亲手引入的副作用（`readForWrite()` 的「损坏即改名」那一支），
     * 而它在这条路径上**完全静默**：`lastError` 是 null、返回值也是 null、
     * 用户零提示 —— 正是 T08 红线「不许静默丢弃」禁止的形态。
     * 其余写方法（[put] / [putAll] / [remove] / [removeByTargetHash]）T08 都改成了
     * 回传 [LedgerWrite]，唯独漏了这一个。
     *
     * ## 修法
     *
     * 回传 [LedgerTake]，它与其它写入口**同口径**：`take.write` 就是一个 [LedgerWrite]，
     * 调用方把它交给与其它写入口相同的那个处置点即可，不必为这条路径另写一套提示。
     * `take.record` 仍然是「拿来放回」的那条记录（销账成功时才非空，
     * 见 [LedgerTake.record] 的说明）。
     */
    @Synchronized
    fun removeAndReturn(modUri: String): LedgerTake {
        val read = readForWrite()
        if (!read.writable) return LedgerTake.Blocked(read.blocked!!)
        val record = read.records!![modUri]
        if (record == null) {
            // 没有这条记录 → 不写盘。但**损坏原件可能已经在 readForWrite() 里被改名搬走**
            // （那是 T08 新引入的副作用），所以不能直接回一句「本来就没有」：
            // 「原件被搬走 + 账本路径消失 + 用户零提示」正是复核抓到的那个静默形态。
            // 修复消息在这里补记到实例上，让只读调用方（consumeLastError）也拿得到 ——
            // save() 那条路是由 save() 自己记的，这条路进不了 save()。
            val notice = read.repairNotice ?: return LedgerTake.Absent
            recordError(notice)
            return LedgerTake.Repaired(null, notice, read.preservedAs ?: "")
        }
        return when (val write = save(read, read.records.toMutableMap().apply { remove(modUri) })) {
            is LedgerWrite.Failed -> LedgerTake.Blocked(write.error)
            is LedgerWrite.Repaired -> LedgerTake.Repaired(record, write.notice, write.preservedAs)
            LedgerWrite.Written -> LedgerTake.Removed(record)
        }
    }

    /**
     * 按装入时的 bundle 名删记录。
     *
     * 整组还原走的是 targetHash（bundle 名）而不是单个 mod，所以需要反查一次。
     * 注意 targetHash 会随游戏更新变化，因此这里只用于「刚还原了这个 bundle」这种
     * 当下就能对上的场合，不作为长期标识。
     */
    @Synchronized
    fun removeByTargetHash(targetHash: String): LedgerWrite {
        val read = readForWrite()
        if (!read.writable) return LedgerWrite.Failed(read.blocked!!)
        val keys = read.records!!.filterValues { it.snapshotTargetHash == targetHash }.keys
        if (keys.isEmpty()) return LedgerWrite.Written
        return save(read, read.records.toMutableMap().apply { keys.forEach { remove(it) } })
    }

    @Synchronized
    fun clear(): OperationError? = try {
        // 文件本来就不存在不是失败（与「清空一张空表」同结果）。
        val f = file()
        if (!f.exists() || f.delete()) null
        else OperationError(
            kind = OperationErrorKind.IO,
            message = "装入记录清空失败：删除 ${f.name} 未成功",
            target = f.path
        )
    } catch (e: Exception) {
        OperationError(
            kind = OperationErrorKind.IO,
            message = "装入记录清空失败：" + e.message.orEmpty(),
            detail = e.toString(),
            target = file().path
        )
    }

    // ------------------------------------------------------------------ journal

    /**
     * 开始一次有副作用的提交。[targets] 是这一批要动游戏文件的 bundle。
     *
     * 落 [JournalStage.PLANNED]：此刻游戏文件**一个字节都还没动**。
     * 写失败不阻断（journal 是诊断证据，见 [OperationJournalStore]）——
     * 但会记日志，不静默。
     */
    @Synchronized
    fun beginSubmit(operationId: String, targets: List<JournalTarget>, note: String? = null) {
        currentOperationId = operationId
        currentTargets = targets
        val error = journalStore.append(
            JournalEntry(
                operationId = operationId,
                stage = JournalStage.PLANNED.id,
                atMillis = System.currentTimeMillis(),
                targets = targets,
                note = note
            )
        )
        if (error != null) Log.w(TAG, "操作日志（PLANNED）写入失败，诊断能力降级：" + error.message)
    }

    /**
     * 落 [JournalStage.STAGED]：产物已就位，还没写游戏目录。
     *
     * `targets` 传 null 表示沿用 [beginSubmit] 那一批（**默认行为**）。
     */
    @Synchronized
    fun markStaged(targets: List<JournalTarget>? = null, note: String? = null) {
        appendStage(JournalStage.STAGED, targets ?: currentTargets, note)
    }

    /**
     * 落 [JournalStage.TARGET_REPLACED]：**游戏文件已经替换成功**。
     *
     * 这是整个 journal 里最关键的一条 —— 它的存在与否，就是
     * 「文件已换、账本未存」这个窗口的判据。**必须由真实提交成功驱动的调用方来落**，
     * 不许由上层凭「应该成功了吧」猜。
     *
     * `targets` 传 null 表示沿用 [beginSubmit] 那一批（**默认行为**）。
     * 这一条的 `targets` **必须**有内容 —— 恢复判据（`recoveryVerdict`）要拿
     * `modUris` 去账本里核对，写成空表会让那个窗口查不出来。所以这里在
     * [beginSubmit] 没被调用（没有可沿用的目标）时**记一条警告日志**，
     * 让「有人漏调 beginSubmit」变成可见的，而不是静默产生一条查不动的 journal。
     *
     * 写完这一条之后**下一次落盘才是账本**，两者之间就是窗口。
     */
    @Synchronized
    fun markTargetReplaced(targets: List<JournalTarget>? = null, note: String? = null) {
        val effective = targets ?: currentTargets
        if (effective.isEmpty()) {
            Log.w(TAG, "操作日志（TARGET_REPLACED）落盘时没有目标摘要 —— " +
                "恢复核对将无法判断该查哪条记录（漏调 beginSubmit？）")
        }
        appendStage(JournalStage.TARGET_REPLACED, effective, note)
    }

    /**
     * 落 [JournalStage.LEDGER_COMMITTED] 并收尾：账本已落盘，窗口关闭。
     *
     * 只在**账本写入真的成功**之后调用（见 [finishSubmit]）。
     */
    @Synchronized
    fun markLedgerCommitted(note: String? = null): OperationError? =
        journalStore.append(
            JournalEntry(
                operationId = currentOperationId ?: UNKNOWN_OPERATION,
                stage = JournalStage.LEDGER_COMMITTED.id,
                atMillis = System.currentTimeMillis(),
                targets = emptyList(),
                note = note
            )
        )

    /**
     * 一次提交的收尾：账本已成功落盘 → 写 [JournalStage.LEDGER_COMMITTED] 再清掉 journal；
     * 账本**没**写成功 → 保留 journal 不动（那条 [JournalStage.TARGET_REPLACED]
     * 必须留着，next 启动才核对得出来），并返回账本的失败。
     *
     * @param ledgerError 账本写入的结果（null = 成功）。
     */
    @Synchronized
    fun finishSubmit(ledgerError: OperationError?): OperationError? {
        if (ledgerError != null) {
            // 账本没成功：**不清 journal**，「文件已换、账本未存」的痕迹要留到下次启动。
            Log.w(TAG, "账本未落盘，保留操作日志以便下次启动核对：" + ledgerError.message)
            currentOperationId = null
            return ledgerError
        }
        markLedgerCommitted()
        val error = journalStore.clear()
        currentOperationId = null
        if (error != null) {
            Log.w(TAG, "操作日志清理失败（下次启动会多核对一次，无副作用）：" + error.message)
        }
        return null
    }

    /**
     * 启动时的核对：journal 说游戏文件换过、账本却没有对应记录 → 明确的**待核对**态。
     *
     * 本方法**只读**，不改账本、不改 journal、不改游戏目录 —— 它交出的是一份判断，
     * 由上层决定怎么提示用户。**不自动补记**：自动补记等于凭一份可能陈旧的日志
     * 往账本里写事实，而日志本身不承担那个权威性（见 [OperationJournalStore] 的类注释）。
     */
    @Synchronized
    fun checkRecovery(): OperationJournalStore.RecoveryVerdict =
        journalStore.recoveryVerdict(load().mapValues { it.value.toLedgerRecord() })

    /** 抛掉 journal（例如用户确认「已知悉」之后）。 */
    @Synchronized
    fun clearJournal(): OperationError? = journalStore.clear()

    // ------------------------------------------------------------------ 错误取用

    /**
     * 取走最近一次失败（take 语义：取过即清）。没有失败时返回 null。
     *
     * 存在的理由：`load()` / `listByTargetHash()` 这些只读方法的返回类型不能改
     * （会牵动一大片调用方），但它们的失败必须**有地方可查**、不能只 `printStackTrace`。
     */
    fun consumeLastError(): OperationError? {
        val error = lastError
        lastError = null
        return error
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 写入路径专用的读取。
     *
     * 与 [readLedger] 的差别**全在损坏那一支**：这里会把损坏原件**改名保留**，
     * 保留成功才返回一张可写的（空）表。
     *
     * | 读到的态 | 写入路径的处置 |
     * |---|---|
     * | `NotFound` | 直接写（全新安装，**这里写错就会让全新安装永远失败**） |
     * | `Empty` | 直接写 |
     * | `Loaded` | 在它基础上改 |
     * | `Corrupt` | 改名保留 → 成功则接着写（并把修复消息带上去）；**失败则拒绝** |
     * | `IoError` | **拒绝**，不动磁盘（内容可能完好，只是这次没读到） |
     *
     * [ReadForWrite.writable] 为 false 表示「这次不能写」，原因在 [ReadForWrite.blocked] 里。
     */
    private fun readForWrite(): ReadForWrite = when (val read = readLedger()) {
        is LedgerRead.Loaded -> ReadForWrite(read.records.mapValues { it.value.toModel() })
        LedgerRead.NotFound, LedgerRead.Empty -> ReadForWrite(emptyMap())
        is LedgerRead.Corrupt -> {
            val preserved = ledgerStore.preserveCorruptFile()
            if (preserved == null) {
                // 原件没能改名保留 → **拒绝覆盖**。宁可让用户看见一次失败，
                // 也不要把唯一一份可能还能人工抢救的账本盖掉。
                val blocked = OperationError(
                    kind = OperationErrorKind.IO,
                    message = "装入记录已损坏，且原件改名保留失败，本次不写入以免覆盖：" +
                        read.reason,
                    target = file().path
                )
                recordError(blocked)
                ReadForWrite(blocked = blocked)
            } else {
                // 保留成功：留下可见痕迹（日志 + 上报），然后从空表重开。
                // 这不是静默丢弃 —— 原件还在磁盘上、名字里带着时间戳。
                Log.w(TAG, "装入记录损坏，原件已保留为 ${preserved.name}：${read.reason}")
                ReadForWrite(
                    records = emptyMap(),
                    repairNotice = OperationError(
                        kind = OperationErrorKind.MALFORMED_DATA,
                        message = "装入记录已损坏，原件已保留为 ${preserved.name}，" +
                            "本次重建了一张新表：" + read.reason,
                        target = file().path
                    ),
                    preservedAs = preserved.name
                )
            }
        }
        is LedgerRead.IoError -> {
            recordError(read.error)
            ReadForWrite(blocked = read.error)
        }
    }

    /**
     * [readForWrite] 的结果：要么「可以写，且可能带一条待上报的修复消息」，
     * 要么「不能写，带着原因」。
     */
    private class ReadForWrite(
        val records: Map<String, InstalledModRecord>? = null,
        val repairNotice: OperationError? = null,
        val preservedAs: String? = null,
        val blocked: OperationError? = null
    ) {
        val writable: Boolean get() = blocked == null
    }

    /**
     * 把模型映射成持久化 DTO 并落盘。
     *
     * 返回 [LedgerWrite.Repaired] 的条件是「写成功了，而且这次写之前处置过损坏原件」——
     * 修复消息与写结果一起往上传，不让它只留在日志里。
     */
    private fun save(read: ReadForWrite, records: Map<String, InstalledModRecord>): LedgerWrite {
        val dto = records.mapValues { it.value.toLedgerRecord() }
        val error = ledgerStore.save(dto)
        if (error != null) {
            recordError(error)
            return LedgerWrite.Failed(error)
        }
        val notice = read.repairNotice
        return if (notice != null) {
            // 修复消息也留在实例上，供只读调用方取用。
            recordError(notice)
            LedgerWrite.Repaired(notice, read.preservedAs ?: "")
        } else {
            LedgerWrite.Written
        }
    }

    private fun appendStage(stage: JournalStage, targets: List<JournalTarget>, note: String?) {
        val error = journalStore.append(
            JournalEntry(
                operationId = currentOperationId ?: UNKNOWN_OPERATION,
                stage = stage.id,
                atMillis = System.currentTimeMillis(),
                targets = targets,
                note = note
            )
        )
        if (error != null) {
            Log.w(TAG, "操作日志（${stage.id}）写入失败，诊断能力降级：" + error.message)
        }
    }

    private fun recordError(error: OperationError) {
        lastError = error
        Log.w(TAG, error.message + (error.detail?.let { " | $it" } ?: ""))
    }
}

/**
 * 「读出并销掉一条记录」的结局（T08 二轮整改，复核真缺陷 1）。
 *
 * ## 为什么不能再用 `InstalledModRecord?`
 *
 * [InstalledModRepository.removeAndReturn] 曾经返回裸的 `InstalledModRecord?`，
 * 于是**三件完全不同的事**在调用方眼里是同一个 `null`：
 *
 * 1. [Absent] —— 账本里本来就没有这条，什么都不用做；
 * 2. [Blocked] —— 账本写失败或被拒绝，**记录还在账本里**，调用方却以为销账了
 *    （`ledger unchanged after failed remove` 的形态）；
 * 3. [Repaired]（`record == null`）—— 账本损坏，原件被改名搬成 `.corrupt-*`、
 *    账本被重建成空表，原本查的那条记录**已经查不动了**。
 *
 * 第 3 种是 T08 引入的副作用（[InstalledModRepository.readForWrite] 的「损坏即改名」
 * 那一支），在裸 `null` 下**完全静默**：原件被搬走、账本路径消失、`lastError` 为空、
 * 用户零提示 —— 正是 T08 红线「不许静默丢弃」禁止的形态。其余写入口
 * （`put` / `putAll` / `remove` / `removeByTargetHash`）T08 都改成了回传 [LedgerWrite]，
 * 唯独漏了这一个。
 *
 * ## 为什么用 [write] 字段而不是另造一套结局
 *
 * [write] 就是一个 [LedgerWrite]，于是调用方可以把**所有写入口**交给同一个处置点
 * （`MainViewModel.reportLedgerWrite`）。两套提示逻辑迟早会漂移成两种说法。
 *
 * [record] 是「拿来放回」的那条记录（删除源文件失败要原样复原），语义与改动前一致：
 * 只有 [Removed] 与 [Repaired] 带得回它。
 */
sealed class LedgerTake {
    /**
     * 与其它写入口同口径的写结果（[LedgerWrite]）。
     *
     * 声明在基类上，调用方才能把**所有**写入口交给同一个处置点
     * （`MainViewModel.reportLedgerWrite`）。
     */
    abstract val write: LedgerWrite

    /**
     * 可供调用方「原样放回」的那条记录；没有则为 `null`。
     *
     * 只有 [Removed] 与销账成功了的 [Repaired] 非空 —— 其余情形下**没有**任何记录
     * 被销掉（[Absent] 本来就没有、[Blocked] 还在账本里、[Repaired] 的 `record == null`
     * 是损坏前就读不出来）。调用方据「`record != null`」决定要不要复原，
     * 与改动前的用法一致。
     */
    abstract val record: InstalledModRecord?

    /** 销账成功。[record] 是那条被销掉的记录，调用方在后续步骤失败时用它放回。 */
    data class Removed(override val record: InstalledModRecord) : LedgerTake() {
        override val write: LedgerWrite get() = LedgerWrite.Written
    }

    /** 账本里本来就没有这条记录，磁盘没被动过。 */
    object Absent : LedgerTake() {
        override val write: LedgerWrite get() = LedgerWrite.Written
        override val record: InstalledModRecord? get() = null
    }

    /**
     * 销账**没做成**：账本被拒绝写入（[Blocked.error]），那条记录**还在账本里**。
     * 调用方必须据此提示用户 —— 「以为销了、其实没销」会让账本与游戏目录长期脱节。
     */
    data class Blocked(val error: OperationError) : LedgerTake() {
        override val write: LedgerWrite get() = LedgerWrite.Failed(error)
        override val record: InstalledModRecord? get() = null
    }

    /**
     * 账本损坏、原件已被改名保留，本次从空表重建。
     *
     * [record] 为 `null` 表示**原本要查的那条记录不在重建后的表里**（损坏前读不出内容，
     * 无从知道它在不在）—— 这时调用方拿不到可放回的记录，但**必须**把 [notice]
     * 提示给用户：他是那份被搬走的原件的所有者，而这是 T08 自己做的副作用。
     * [record] 非空表示损坏前刚好还能读出这条记录、并已成功销账。
     */
    data class Repaired(
        override val record: InstalledModRecord?,
        val notice: OperationError,
        val preservedAs: String
    ) : LedgerTake() {
        override val write: LedgerWrite get() = LedgerWrite.Repaired(notice, preservedAs)
    }
}

/** 领域模型 → 持久化 DTO（显式 adapter，不是第二份事实模型）。 */
private fun InstalledModRecord.toLedgerRecord() = LedgerRecord(
    modUri = modUri,
    modName = modName,
    familyKey = familyKey,
    snapshotTargetHash = snapshotTargetHash,
    quality = quality,
    usedAstc = usedAstc,
    installedAt = installedAt
)

/** 持久化 DTO → 领域模型。 */
private fun LedgerRecord.toModel() = InstalledModRecord(
    modUri = modUri,
    modName = modName,
    familyKey = familyKey,
    snapshotTargetHash = snapshotTargetHash,
    quality = quality,
    usedAstc = usedAstc,
    installedAt = installedAt
)

/** 没有 [InstalledModRepository.beginSubmit] 就落阶段时的占位 id（不该发生，但不静默）。 */
private const val UNKNOWN_OPERATION = "unknown"
