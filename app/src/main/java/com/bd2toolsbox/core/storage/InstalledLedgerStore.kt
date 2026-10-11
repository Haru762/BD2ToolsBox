package com.bd2toolsbox.core.storage

import com.bd2toolsbox.core.fileaccess.AtomicFileWriter
import com.bd2toolsbox.core.model.OperationError
import com.bd2toolsbox.core.model.OperationErrorKind
import com.bd2toolsbox.core.storage.dto.StoredInstalledMod
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 一条装入记录（T08）。
 *
 * **为什么是独立 DTO 而不是复用 `data.model.InstalledModRecord`**：字段逐个相同，
 * 本可以复用。另立一份是为了让**持久化字段名**在这一处冻结 —— `InstalledModRecord`
 * 将来会被 T10/T25 动到，而账本的磁盘格式不该跟着漂。两者之间由
 * `InstalledModRepository` 的显式 adapter 连接（同 `RepackJob` 的既有做法：
 * 「迁移期的显式 adapter，不是第二份事实模型」）。
 *
 * **字段名必须保留**（R8），否则混淆后 Gson 按新名字读写、老账本读不回来。
 * 保留规则见 `app/proguard-rules.pro`。
 */
data class LedgerRecord(
    val modUri: String,
    val modName: String,
    val familyKey: String,
    val snapshotTargetHash: String,
    val quality: String,
    val usedAstc: Boolean,
    val installedAt: Long
)

/**
 * 账本的一次读取结果（T08）。
 *
 * ## 为什么必须是四态而不是「表 + 出错」
 *
 * 改动前 `InstalledModRepository.load()` 把「文件不存在」「合法空表」「读失败/损坏」
 * 一律返回 `emptyMap()`。后果不是「少显示几条」，而是**静默的数据丢失**：账本损坏后
 * 第一次 `put()` 内部再 `load()` 又得空表，`save()` 把「只有新记录」的表整体覆写上去，
 * 旧记录永久消失且没有任何日志。这正是本任务要消灭的形态。
 *
 * 三种情况必须能区分，且**写入方的行为不同**：
 *
 * | 态 | 含义 | 是否允许接着写 |
 * |---|---|---|
 * | [NotFound] | 文件不存在（全新安装） | **允许** |
 * | [Empty] | 文件在、内容是合法空表 | **允许** |
 * | [Loaded] | 文件在、有记录 | **允许**（在它基础上改） |
 * | [Corrupt] | 文件在、读不成一张可用的表 | 先把原件改名保留，**保留成功才允许** |
 * | [IoError] | 读文件本身失败（权限/占用） | **拒绝**（内容可能完好，别动它） |
 *
 * **最容易被写错的地方**：把 [NotFound] 也当成「损坏」而拒绝写入 ——
 * 那会让**全新安装永远写不进去**。两者的区别不是「有没有内容」，
 * 而是「有没有一份**读不出来的**已有数据需要保护」。
 */
sealed class LedgerRead {
    /** 文件不存在：全新安装。**允许写入。** */
    object NotFound : LedgerRead()

    /** 文件存在且是合法的空表。**允许写入。** */
    object Empty : LedgerRead()

    /** 文件存在且有记录。**允许写入**（调用方在 [records] 基础上改）。 */
    data class Loaded(val records: Map<String, LedgerRecord>) : LedgerRead()

    /**
     * 文件在，但读不成一张可用的表（半截 JSON、未知 schema 版本、结构不对）。
     * 原件**还没有**被动过 —— 改名保留是调用方在决定继续写之前显式做的一步
     * （[InstalledLedgerStore.preserveCorruptFile]），读路径本身零副作用。
     */
    data class Corrupt(val reason: String) : LedgerRead()

    /**
     * 读文件本身失败。这与 [Corrupt] 的区别是「内容可能完好，只是这次没读到」，
     * 所以**不做**改名保留：改名一个读不出来的文件同样会失败，
     * 而且会把「暂时读不到」变成「原件被搬走」。
     */
    data class IoError(val error: OperationError) : LedgerRead()
}


/**
 * 一次账本写入的结局（T08）。
 *
 * ## 为什么是三态而不是 `OperationError?`
 *
 * `null = 成功` 这个约定无法表达真实存在的第三种结局：**写成功了，但过程中把一份损坏的
 * 原件改名保留、并从空表重建**。那不是失败（账本现在可用、原件也在），也绝不是
 * 「什么都没发生」—— 用户必须被告知，因为他会有一次「mod 显示未安装」的体验，
 * 而原因不是他做错了什么。
 *
 * 把它挤进 `OperationError?` 会逼出歧义：`MALFORMED_DATA` 既可能是「序列化失败、
 * 什么都没写成」，也可能是「损坏已修复、写成功了」，调用方无法据此决定要不要提示。
 * 这与 `LedgerRead` 坚持把五种读取结局分开是同一个理由：
 * 能被区分开的结局，就不该被合并成一个布尔。
 */
sealed class LedgerWrite {
    /** 正常写入，没有任何需要上报的事。 */
    object Written : LedgerWrite()

    /**
     * 写入成功，但这次写入是在处置了一份损坏账本之后完成的。
     *
     * [notice] 是给用户看的那条消息（含保留件文件名），[preservedAs] 是原件的新名字。
     * 调用方应当把它提示出来 —— 这不是错误，是一条必须让人知道的事实。
     */
    data class Repaired(val notice: OperationError, val preservedAs: String) : LedgerWrite()

    /** 写入失败，旧账本保持原样。[error] 说明原因。 */
    data class Failed(val error: OperationError) : LedgerWrite()
}

/**
 * 安装账本的落盘（T08）。
 *
 * ## 它替换掉的是什么
 *
 * 改动前的 `InstalledModRepository`：
 *
 * ```kotlin
 * private fun save(records) {
 *     try { file().writeText(gson.toJson(records)) }   // 直接覆写目标
 *     catch (e: Exception) { e.printStackTrace() }     // 失败不上报
 * }
 * ```
 *
 * 两个缺陷各自独立：**非原子**（写到一半进程被杀 → 半截文件，下次读就是损坏）
 * 与 **返回 `Unit`**（失败只打印，调用方以为写成功了）。本类把两者一起修掉：
 * 写入走 T07 的 [AtomicFileWriter]（同目录暂存 + 原子替换），
 * [save] 返回 `OperationError?`（null = 成功），失败必须由调用方处置。
 *
 * ## 只吃 `File`，不吃 `Context`
 *
 * 构造参数只有 [ledgerFile]，于是整个类能在纯 JVM 单测里用 `TemporaryFolder` 直接跑，
 * 不需要 mockk、不需要 Robolectric。`Context` 依赖留在 `InstalledModRepository` 那一层，
 * 与 `core/fileaccess` 整包「不 import `android.*`」同口径。
 *
 * ## 并发所有者：`InstalledModRepository` 是**唯一**的所有者
 *
 * 本类**自己不加锁**，这是刻意的：账本写入是「读全表 → 改一条 → 写回全表」的
 * read-modify-write，锁必须罩住**整段**才有意义 —— 若本类对单次读 / 单次写各自加锁，
 * 两次调用之间仍能被别的线程插进来，等于没锁。所以锁留在
 * `InstalledModRepository` 的方法上（它原有的 `@Synchronized` 实例锁，本轮保留），
 * 由它罩住整段读改写。本类只被它一个人调用，是「被独占使用的无锁实现」——
 * **别把这里的无锁读成并发安全**。
 *
 * ## schema 版本与两种在盘形态
 *
 * 写出去的形态是顶层 `schemaVersion` + 单一载荷键 `records`，照本仓既有约定
 * （`mod_cache.json` = `schemaVersion` + `indexStamp` + `entries`；
 * `local_bundle_index.json` = `schemaVersion` + `scannedAt` + …）。**照它写，不另创一套。**
 *
 * 但**读**必须接受两种形态（见 [parse]）：
 *
 * 1. **v1**（本类写出的）：`{"schemaVersion":1,"records":{...}}`；
 * 2. **v0 legacy**（0.2.16 及更早写出的）：**裸表** `{"<modUri>":{...}}`，没有版本号。
 *
 * v0 不是假想：改动前 `save()` 写的就是 `gson.toJson(records)` 这么一个裸 Map
 * （`ModRepository` 之外唯一一处没有版本号的持久化），**字段名与 v1 内的记录逐个相同**，
 * 所以能无歧义读出来。若不认它，升级后第一次运行就会把用户真实的装入记录当损坏改名掉
 * 再重建空表 —— 用户所有 mod 变成「未安装」。**能无歧义读出的旧格式要读，不能丢。**
 */
class InstalledLedgerStore(private val ledgerFile: File) {

    /**
     * 读账本。**不抛异常、零副作用**：所有失败都编码进返回的 [LedgerRead]。
     *
     * 「读失败」在业务上是一个**正常分支**（据此决定能不能接着写、要不要提示用户），
     * 不是异常控制流 —— 与 `core/model/OperationModels.kt` 的取向一致
     * （「这些错误是结果值，不是控制流」）。
     */
    fun read(): LedgerRead {
        // 目录（而不是文件）占着这个路径时**不能**当「不存在」，也**不能**当「损坏」：
        // - 当不存在 → 下一次 save() 会去跟一个目录抢名字；
        // - 当损坏 → 写入路径会把那个目录改名搬走，而目录里可能有用户的东西。
        // 所以它单独归到「读不出来」这一支：拒绝写入、不动磁盘。
        if (ledgerFile.isDirectory) {
            return LedgerRead.IoError(
                OperationError(
                    kind = OperationErrorKind.IO,
                    message = "账本路径被一个目录占着，本次不写入：${ledgerFile.path}",
                    target = ledgerFile.path
                )
            )
        }
        if (!ledgerFile.exists()) return LedgerRead.NotFound

        val text = try {
            ledgerFile.readText()
        } catch (e: Exception) {
            // 读不出来 ≠ 损坏：内容可能完好，只是这次没读到（权限、被占用、被删除）。
            return LedgerRead.IoError(
                OperationError(
                    kind = OperationErrorKind.IO,
                    message = "装入记录读取失败：" + e.message.orEmpty(),
                    detail = e.toString(),
                    target = ledgerFile.path
                )
            )
        }

        // A present blank file can be the result of an interrupted legacy write.
        // Only an actual empty JSON map is evidence of an empty ledger.
        if (text.isBlank()) return LedgerRead.Corrupt("装入记录为空白，无法确认原有记录")

        return try {
            parse(text)
        } catch (e: Exception) {
            LedgerRead.Corrupt(
                "装入记录无法解析（" + e.javaClass.simpleName + "）：" + e.message.orEmpty()
            )
        }
    }

    /**
     * 把 [records] 原子地写进账本。
     *
     * @return `null` = 成功；非 null = 失败原因。**不许**再返回 `Unit` ——
     *   改动前正是「返回 `Unit` + `printStackTrace`」让写失败在调用方看起来像成功。
     *
     * 失败时**旧账本保持原样**：这一条由 [AtomicFileWriter] 的提交语义保证
     * （字节先落同目录暂存件、最后一步才原子替换），本类不额外做任何补救式写入。
     */
    fun save(records: Map<String, LedgerRecord>): OperationError? {
        val payload = try {
            encode(records)
        } catch (e: Exception) {
            // 序列化失败与 IO 失败必须分开报：前者是数据问题、原样重试无用；
            // 后者可能只是这一次没写成。混成一个码会让调用方做错决定。
            return OperationError(
                kind = OperationErrorKind.MALFORMED_DATA,
                message = "装入记录序列化失败：" + e.message.orEmpty(),
                detail = e.toString(),
                target = ledgerFile.path
            )
        }
        return try {
            AtomicFileWriter().write(target = ledgerFile) { out ->
                out.write(payload.toByteArray(Charsets.UTF_8))
            }
            null
        } catch (e: Exception) {
            OperationError(
                kind = mapWriteFailure(e),
                message = "装入记录写入失败（旧账本保持原样）：" + e.message.orEmpty(),
                detail = e.toString(),
                target = ledgerFile.path
            )
        }
    }

    /**
     * 把一份被判损坏的账本改名保留，返回改成的那个文件；失败返回 `null`。
     *
     * **只在调用方决定继续写之前显式调用**（读路径绝不碰盘）。名字是**确定性**的：
     * `installed_mods.json.corrupt-<yyyyMMdd-HHmmss>`。用时间戳是为了同一台设备上多次
     * 损坏各自留一份而不是互相覆盖 —— 覆盖就等于销毁上一份证据；用本地时间与固定宽度
     * 格式，是为了让用户在文件管理器里一眼看出什么时候坏的、`ls` 的升序即时间序。
     *
     * **返回 `null` 的调用方必须拒绝写入**：原件还在原位，此时写就是把唯一一份
     * 可能还能人工抢救的账本盖掉。宁可让用户看见一次失败。
     *
     * ## 代价（写进证据文件，不藏）
     *
     * 每次损坏都会**累积**一个文件，且**本轮没有任何自动清理**——
     * 谁清理、何时清理都还没定（留给后续按「账本损坏上报」一起做的任务）。
     * 账本本身是 KB 级，所以累积速度极慢，但这是**登记在案的债**，不是不存在。
     */
    fun preserveCorruptFile(): File? {
        if (!ledgerFile.exists()) return null
        val stem = ledgerFile.name + CORRUPT_SUFFIX_PREFIX + CORRUPT_STAMP_FORMAT.format(LocalDateTime.now())
        var suffix = 0
        while (true) {
            val target = File(ledgerFile.parentFile, stem + if (suffix == 0) "" else "-$suffix")
            try {
                // No REPLACE_EXISTING: a second repair must not overwrite evidence.
                java.nio.file.Files.move(ledgerFile.toPath(), target.toPath())
                return target
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                suffix++
            } catch (_: Exception) {
                return null
            }
        }
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 解析一份非空文本。**版本判定只有这一处**，不散在读侧各处。
     *
     * 判据（顺序即优先级）：
     *
     * 1. 顶层不是 JSON 对象（含字面量 `null`、数组、标量、半截 JSON）→ 损坏。
     *    字面量 `null` 是一个**合法 JSON**，但一份「应该是表」的文件写着 `null`
     *    说明它被谁改过；改动前这里 `gson.fromJson(...) ?: emptyMap()` 会当空表（静默）。
     * 2. 顶层有 `records` 键 → **v1 形态**：必须同时有数字型 `schemaVersion`，
     *    且等于 [SCHEMA_VERSION]。版本**不认识**就判废 —— 那种情况下字段语义无保证，
     *    按空表读会静默丢记录。
     * 3. 顶层没有 `records` 键 → **v0 legacy 裸表**。整份顶层对象当作记录表来读。
     * 4. 读出来的每条记录必须字段齐全、且**键等于它自己的 `modUri`**；
     *    任一条不满足就整份判损坏（静默丢单条 = 静默丢数据，正是要消灭的形态）。
     *
     * 已知的未知顶层键一律忽略（向前兼容：将来加字段不该让今天的账本判废）。
     */
    private fun parse(text: String): LedgerRead {
        val root = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            return LedgerRead.Corrupt("账本不是合法 JSON：" + e.message.orEmpty())
        }
        if (root is JsonNull) return LedgerRead.Corrupt("账本内容是 JSON null，不是一张表")
        if (root !is JsonObject) {
            return LedgerRead.Corrupt("账本顶层不是 JSON 对象")
        }
        val obj = root

        val recordsObject: JsonObject = if (obj.containsKey(RECORDS_KEY)) {
            // v1 形态
            val versionPrimitive = obj[SCHEMA_VERSION_KEY] as? JsonPrimitive
            if (versionPrimitive == null || versionPrimitive.isString) {
                return LedgerRead.Corrupt("账本有 $RECORDS_KEY 但缺少数字型 $SCHEMA_VERSION_KEY")
            }
            val version = versionPrimitive.content.toIntOrNull()
                ?: return LedgerRead.Corrupt("账本 $SCHEMA_VERSION_KEY 不是整数")
            if (version != SCHEMA_VERSION) {
                return LedgerRead.Corrupt("账本 schemaVersion=$version，本版本只认 $SCHEMA_VERSION")
            }
            val element = obj[RECORDS_KEY]
            if (element !is JsonObject) {
                return LedgerRead.Corrupt("账本的 $RECORDS_KEY 不是 JSON 对象")
            }
            element
        } else {
            // v0 legacy 裸表：整个顶层对象就是记录表
            obj
        }

        // T35：kotlinx.serialization 反序列化。**逐条解码**，这样缺必需字段/类型不符时
        // 能点出是哪一条记录；可选字段（usedAstc/installedAt）缺失时用 DTO 默认值。
        val cleaned = LinkedHashMap<String, LedgerRecord>(recordsObject.size)
        for ((key, value) in recordsObject) {
            if (value !is JsonObject) {
                return LedgerRead.Corrupt("记录 $key 不是 JSON 对象")
            }
            val stored = try {
                json.decodeFromJsonElement(StoredInstalledMod.serializer(), value)
            } catch (e: Exception) {
                return LedgerRead.Corrupt("记录 $key 缺字段或类型不符：" + e.message.orEmpty())
            }
            if (key != stored.modUri) {
                // 主键必须等于记录自己的 modUri：两者不一致说明文件被外部改过，
                // 而「按 key 查得到、按 modUri 查不到」会让删除与回滚对不上账。
                return LedgerRead.Corrupt("记录键 $key 与它自己的 modUri ${stored.modUri} 不一致")
            }
            cleaned[key] = LedgerRecord(
                stored.modUri, stored.modName, stored.familyKey,
                stored.snapshotTargetHash, stored.quality, stored.usedAstc, stored.installedAt
            )
        }
        return if (cleaned.isEmpty()) LedgerRead.Empty else LedgerRead.Loaded(cleaned)
    }

    private fun encode(records: Map<String, LedgerRecord>): String {
        val stored = records.mapValues { (_, r) ->
            StoredInstalledMod(
                r.modUri, r.modName, r.familyKey, r.snapshotTargetHash,
                r.quality, r.usedAstc, r.installedAt
            )
        }
        val root = buildJsonObject {
            put(SCHEMA_VERSION_KEY, SCHEMA_VERSION)
            put(RECORDS_KEY, json.encodeToJsonElement(recordsMapSerializer, stored))
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }

    /**
     * 把写入异常映射到**唯一**那套分类（`OperationErrorKind`）。
     *
     * `SecurityException` 是权限问题，映射到 [OperationErrorKind.PERMISSION_DENIED]
     * 而不是 IO —— 调用方据此提示「重新授权」而不是「再试一次」。
     *
     * **[已知边界]** `AtomicFileWriter` 把 `AtomicMoveNotSupportedException` 包成了
     * `IOException`（它的类注释里写明了这是刻意的：安全失败、不退化），
     * 所以「文件系统不支持原子替换」这一路**在这里落进 `IO`**、无法与普通 IO 失败区分。
     * 下面的分支保留 `AtomicMoveNotSupportedException` 的显式映射以防将来写入器改成
     * 原样抛出；**今天它不会命中**。这一条登记在 `T08.md` 的未覆盖项里。
     */
    private fun mapWriteFailure(e: Exception): OperationErrorKind = when (e) {
        is SecurityException -> OperationErrorKind.PERMISSION_DENIED
        is java.nio.file.AtomicMoveNotSupportedException -> OperationErrorKind.UNSUPPORTED
        else -> OperationErrorKind.IO
    }

    companion object {
        /** 顶层 schema 版本。字段语义变了才递增；递增即让旧表判废（见 [parse]）。 */
        const val SCHEMA_VERSION = 1

        /** 顶层版本键。 */
        const val SCHEMA_VERSION_KEY = "schemaVersion"

        /** 单一载荷键。 */
        const val RECORDS_KEY = "records"

        /** 损坏原件改名时加的后缀前缀：`installed_mods.json.corrupt-20260917-213000`。 */
        const val CORRUPT_SUFFIX_PREFIX = ".corrupt-"

        /**
         * 损坏件时间戳格式。固定宽度、无分隔歧义，升序即时间序。
         *
         * 用 `java.time`（线程安全）而不是 `SimpleDateFormat`：后者不是线程安全的，
         * 共享一个实例迟早出事，而本类没有任何理由去承担那个风险。`minSdk = 29`
         * 高于 `java.time` 所需的 API 26。
         */
        private val CORRUPT_STAMP_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        /** kotlinx Json 实例线程安全，共享一个即可。 */
        private val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            // 与 Gson 默认一致：默认值字段也写出（usedAstc=false / installedAt=0 不省）。
            encodeDefaults = true
        }

        private val recordsMapSerializer =
            MapSerializer(String.serializer(), StoredInstalledMod.serializer())
    }
}
