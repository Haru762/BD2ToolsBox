package com.bd2toolsbox.core.storage

import com.bd2toolsbox.core.fileaccess.AtomicFileWriter
import com.bd2toolsbox.core.model.OperationError
import com.bd2toolsbox.core.model.OperationErrorKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * 一条快照条目（T10）—— 即磁盘上 `mods` 数组里的一项。
 *
 * ## 为什么是独立 DTO，而不是直接持久化 `data.model.ModInfo`
 *
 * 两个理由，缺一不可：
 *
 * 1. **`ModInfo.uri` 是 `android.net.Uri`**。若 DTO 持有 `ModInfo`，本类就得 import
 *    `android.*`，于是它无法在纯 JVM 单测里跑（JVM 上 `Uri.parse` 是 stub，会抛
 *    `Method … not mocked`）。这里把 uri 存成**字符串**，`Uri` ⇄ `String` 的转换留在
 *    `MainViewModel` 那一层 —— 与 `core/fileaccess` 整包「不 import `android.*`」同口径。
 * 2. **持久化字段名要在一处冻结**。`ModInfo` 会继续被后续任务改，而磁盘格式不该跟着漂。
 *    两者之间由 `MainViewModel` 的显式转换连接（同 `LedgerRecord` 之于
 *    `data.model.InstalledModRecord` 的既有做法）。
 *
 * ## 字段名 = 磁盘键名，而且是**故意**写成同一个字面量的
 *
 * 每个字段都带 `@SerialName("<与字段名相同的字面量>")`。看着冗余，实际是两条防线：
 *
 * - 它把 JSON 键**写成编译期常量**，将来谁重命名 Kotlin 属性都不会顺手改掉磁盘格式；
 * - 万一 R8 把注解属性剥掉（见 `app/proguard-rules.pro` 里 T10 那段说明），Gson 会退回
 *   按**字段名**读写 —— 而字段名与注解值相同，格式因此**在两种情况下都一样**。
 *
 * ## 比旧实现少三个字段，这是故意的（附二轮更正）
 *
 * 旧 `MainViewModel.snapshotGson` 用**只作用于序列化**的 `ExclusionStrategy` 按**字段名**排除
 * `resolvedTargets` / `unresolvedFiles` / `convertedDataUri`。T10 首版曾写「那条策略在 release
 * 包里**是坏的**（`FieldAttributes.getName()` 拿到 R8 改名后的名字，匹配不上）」—— **这句是错的**，
 * 二轮复核用本任务自己的夹具反证了它：
 *
 * - `ModInfo` 被 `proguard-rules.pro` 的 `-keep class com.bd2toolsbox.data.model.** { *; }` 覆盖，
 *   而该规则**自 `8423c8c`（"BD2 ToolsBox 0.1"）就存在**，早于 0.2.16 —— 历史 release 的 dex 里
 *   `resolvedTargets` / `unresolvedFiles` / `convertedDataUri` **字段名原样未改**；
 * - 本任务那份**从真实 0.2.16 设备捕获**的夹具（`T10.md` §5.1），5 条条目的键并集里
 *   **这三个键一个都没有** —— 那正是该排除策略**生效**的产物（`null` 字段被排除）。
 *
 * 所以真实的因果是：**旧策略当年确实在生效**，这三个字段本来就**没有**被写进 release 快照。
 * T10 真正修的是**另一件事** —— 「磁盘键名/字段名**可能被 R8 改名**」这类契约脆弱性（0.2.16 的
 * `MainViewModel$ModListSnapshot` 字段被改名为 `a`/`b`/`c`、`version` 被整个折叠掉，就是同型）。
 * 用「让字段根本不存在」替代「按字段名做运行时排除」，收益是**判据不再依赖反射字段名** ——
 * 与 T09 二轮那条教训同型，**不是**「修一个在 release 里失效的策略」。
 *
 * 代价与旧行为对照（读侧）：`resolvedTargets` / `unresolvedFiles` 旧读法也是补空表
 * （它们在文件里本来就不存在），`convertedDataUri` 旧读法是 null（同上）；新 DTO 直接给出
 * 同样的值，见 `MainViewModel` 的转换。**读侧零行为变化；写入体积也零变化**（这三个字段
 * 在 release 里本来就没落盘）。
 */
@Serializable
data class SnapshotEntry(
    @SerialName("name") val name: String? = null,
    @SerialName("character") val character: String? = null,
    @SerialName("costume") val costume: String? = null,
    @SerialName("type") val type: String? = null,
    @SerialName("isEnabled") val isEnabled: Boolean = false,
    @SerialName("uri") val uri: String? = null,
    @SerialName("targetHashedName") val targetHashedName: String? = null,
    @SerialName("isDirectory") val isDirectory: Boolean = false,
    @SerialName("resolutionState") val resolutionState: String? = null,
    @SerialName("targetHash") val targetHash: String? = null,
    @SerialName("resolvedFamilyKey") val resolvedFamilyKey: String? = null,
    @SerialName("errorReason") val errorReason: String? = null,
    @SerialName("installState") val installState: String? = null,
    @SerialName("kind") val kind: String? = null,
    @SerialName("convertedHashDir") val convertedHashDir: String? = null,
    @SerialName("convertedDataSize") val convertedDataSize: Long = 0L,
    @SerialName("defect") val defect: String? = null,
    @SerialName("outdatedCurrentHash") val outdatedCurrentHash: String? = null
)

/**
 * 一条快照条目是否**结构完整**。
 *
 * 判据与改动前 `MainViewModel.normalizeSnapshotEntry` 的 8 个非空判断**逐个相同**：
 * 少任何一个都说明这条 JSON 被改坏或来自别的 schema，整条丢掉（丢一条 = 少显示一个 mod，
 * 远好过让一个 uri 为 null 的条目在很远的转换路径上炸 NPE）。
 *
 * 枚举名合不合法**不在这里判** —— 那是领域知识（`ResolutionState` 等住在 `data.model`）。
 * 本类只守文件格式，名字到枚举的解析在 `MainViewModel`，那里解析失败同样整条丢。
 */
internal fun SnapshotEntry.isStructurallyValid(): Boolean =
    uri != null && name != null && character != null && costume != null &&
        type != null && resolutionState != null && installState != null && kind != null

/**
 * 一次快照读取的结局（T10）。
 *
 * ## 为什么必须是分态而不是「表 + 出错」
 *
 * 改动前 `loadModListSnapshot()` 把「文件不存在」「空白文件」「版本不认识」「JSON 半截」
 * 「字段缺失」**一律**返回 `emptyList()`，并在日志里写「按没有快照处理」。
 * 对显示来说这些确实都是「空列表」，但**对处置来说它们完全不同**：
 *
 * | 态 | 含义 | 调用方该做什么 |
 * |---|---|---|
 * | [Absent] | 文件不存在（全新安装 / 从没扫过） | 什么都不用做 |
 * | [Loaded] | 读出一张可用的表（新格式或旧 release 格式迁移而来） | 拿 [Loaded.entries] 填列表 |
 * | [StaleVersion] | 顶层有 `version` 但不认识 | **显式失效**：只删快照文件（[ModSnapshotStore.discard]） |
 * | [SourceDirsChanged] | 表是好的，但属于另一组源目录 | 不显示、**不删**（新扫描收尾会覆盖它） |
 * | [Corrupt] | 半截 JSON / 空白 / 顶层不是对象 / `mods` 不是数组 | **显式失效**：只删快照文件 |
 * | [IoError] | 读文件本身失败（权限 / 被占用 / 路径被目录占着） | **什么都不做**（内容可能完好，别动它） |
 *
 * 这个分表就是计划里「损坏/未知版本快照触发明确重新加载，**不制造**『用户没有安装过 MOD』
 * 的事实」的落实：返回空表只是**结果**，`Corrupt` / `StaleVersion` 才是那个**事实**。
 */
sealed class SnapshotLoad {
    /** 文件不存在。不是错误，也不是失效 —— 没有东西需要清。 */
    object Absent : SnapshotLoad()

    /**
     * 读出一张可用的表。[savedAt] 仅供排查（新旧由扫描结果决定，不由它决定）。
     *
     * [droppedEntries] 是**因为结构不完整而被丢掉**的条目数。它必须能被看到：
     * 「表读出来了」和「表读出来了但少了两条」对排查是两件事，而旧实现把后者
     * 静默吞在 `mapNotNull` 里。
     */
    data class Loaded(
        val entries: List<SnapshotEntry>,
        val savedAt: Long,
        val droppedEntries: Int,
        val wasLegacy: Boolean = false
    ) : SnapshotLoad()

    /** 顶层有 `version`，值不是本类认识的 [ModSnapshotStore.SCHEMA_VERSION]。 */
    data class StaleVersion(val version: Int) : SnapshotLoad()

    /** 表本身可用，但记录的是另一组源目录。[from] 是文件里的，[to] 是调用方期望的。 */
    data class SourceDirsChanged(val from: List<String>, val to: List<String>) : SnapshotLoad()

    /** 文件在，但读不成一张可用的表。[reason] 说明是哪种坏法。 */
    data class Corrupt(val reason: String) : SnapshotLoad()

    /** 读文件本身失败。与 [Corrupt] 的区别是「内容可能完好，只是这次没读到」。 */
    data class IoError(val error: OperationError) : SnapshotLoad()
}

/**
 * 一次快照写入的结局（T10）。
 *
 * [Skipped] 必须与 [Written] 分开：`commit` 的「这次不落盘」是**正常**结局（迟到的旧扫描
 * 被更新的结果取代），不是失败，也不该被当成成功。改动前它是 `return`，什么都没说，
 * 于是「为什么这份快照没写进去」在测试里只能靠间接推断。
 */
sealed class SnapshotCommit {
    /** 已原子落盘。 */
    object Written : SnapshotCommit()

    /** 按规则本次不落盘（revision 过期，或源目录已变）。旧快照保持原样。 */
    data class Skipped(val reason: String) : SnapshotCommit()

    /** 写入失败。旧快照保持原样。 */
    data class Failed(val error: OperationError) : SnapshotCommit()
}

/**
 * 一次**已捕获、待提交**的快照写（T10）。
 *
 * 为什么要分成「捕获」与「提交」两步：冷启动快照是**扫描**的产物，而扫描可以在
 * 用户操作下被重跑；旧扫描的 IO 任务完全可能晚于新扫描完成。若写入时不带一个序号，
 * 迟到的旧结果就会盖掉新结果 —— 尤其「新扫描扫出空表（用户清空了目录）被旧快照覆盖」
 * 那种，用户会看到已删的 mod 又回来了，而且不会再有下一次扫描来纠正。
 *
 * [revision] 在 [ModSnapshotStore.capture] 里单调递增；[ModSnapshotStore.commit] 只认
 * 当前最新的那一个。
 */
data class PendingWrite(
    /** 捕获序号，由 [ModSnapshotStore.capture] 递增分配。 */
    val revision: Long,
    /** 落盘时刻（epoch ms），仅供排查。 */
    val savedAt: Long,
    /** 捕获时刻的整张表。 */
    val entries: List<SnapshotEntry>,
    /** 捕获时刻的源目录快照，提交时要与「此刻的源目录」再比一次。 */
    val sourceDirs: List<String>
)

/**
 * 冷启动列表快照的落盘（T10）。
 *
 * ## 它替换掉的是什么
 *
 * 改动前快照的读写散在 `MainViewModel` 的 190 行里（`captureModListSnapshot` /
 * `saveModListSnapshot` / `loadModListSnapshot` / `normalizeSnapshotEntry` /
 * `clearModListSnapshot`），其中写路径有三个真缺陷：
 *
 * 1. **失败不上报**：`Log.w("MainViewModel", "写入冷启动快照失败（不影响功能）", e)`
 *    之后就没了，调用方无从知道这次没写成功；
 * 2. **回退不是原子的**（`if (!tmp.renameTo(file)) { file.delete(); if (!tmp.renameTo(file)) … }`）
 *    —— 先删目标再改名：第二次改名再失败，**新旧两份都没有了**。这是「消灭非原子提交」
 *    这件事里最危险的一种回退，因为它把「改名失败」升级成了「数据丢失」；
 * 3. **schema 校验散在读侧各处**（版本闸、sourceDirs 闸、逐字段的 `normalizeSnapshotEntry`），
 *    没有一处能说清「这份文件到底算不算一份快照」。
 *
 * 本类把三者一起收进来：写入走 T07 的 [AtomicFileWriter]（同目录暂存 + 原子替换，
 * **不支持原子替换时安全失败、绝不退化成先删后改名**），提交返回 [SnapshotCommit]，
 * 校验集中在 [load] 的分态结果里。
 *
 * ## 只吃 `File`，不吃 `Context`
 *
 * 构造参数只有 [snapshotFile]，于是整个类能在纯 JVM 单测里用 `TemporaryFolder` 直接跑，
 * 不需要 mockk、不需要 Robolectric；同一套语义也能原样搬进设备 instrumentation。
 * `Context`/`filesDir` 的依赖留在 `MainViewModel` 那一层。
 *
 * ## 磁盘格式（新写入的）
 *
 * ```json
 * {"version":1,"savedAt":1789327404471,"mods":[ … ],"sourceDirs":["content://…"]}
 * ```
 *
 * **绝不写 `a`/`b`/`c`**。那三个键是 0.2.16 那一次 R8 混淆的**产物**，把它们钉进新格式
 * 等于把一次性的混淆结果升格成永久契约 —— 与计划「过渡期可用精确 Gson 注解，最终由
 * T35–T36 移除」直接冲突。旧格式**只读不写**，见 [LegacyObfuscatedSnapshot]。
 */
class ModSnapshotStore internal constructor(
    private val snapshotFile: File,
    private val readSnapshot: (File) -> String
) {
    constructor(snapshotFile: File) : this(snapshotFile, { it.readText() })

    /**
     * Explicit legacy migration; ordinary load remains read-only.
     *
     * The migration is only a format upgrade of what is already on disk, so it must never
     * compete with live list state: it runs only while no [capture]/[discard] has happened
     * on this Store (revision == 0), and it writes WITHOUT allocating a revision. A scan
     * that captured earlier therefore keeps its revision and can still commit afterwards,
     * and a scan that captures later simply supersedes the migrated file.
     */
    @Synchronized
    fun loadAndMigrate(expectedSourceDirs: List<String>): SnapshotLoad {
        val loaded = load(expectedSourceDirs)
        if (loaded is SnapshotLoad.Loaded && loaded.wasLegacy && revision == 0L) {
            persist(
                PendingWrite(
                    revision = revision,
                    savedAt = System.currentTimeMillis(),
                    entries = loaded.entries.toList(),
                    sourceDirs = expectedSourceDirs.toList()
                )
            )
        }
        return loaded
    }

    /**
     * **0.2.16 release 的真实磁盘形态**（T10 起只读，不写）。
     *
     * 历史 release 的 `MainViewModel$ModListSnapshot` 在 R8 之后字段名是 `a`/`b`/`c`，
     * 而 `version` **整个字段被消掉了** ——
     * `local/refactor-artifacts/historical-0.2.16/mapping.txt` 的
     * `com.bd2toolsbox.ui.viewmodel.MainViewModel$ModListSnapshot -> r1.p:` 条目下只有
     * `long savedAt -> a` / `java.util.List mods -> b` / `java.util.List sourceDirs -> c`
     * 三行，**没有 `version` 的任何条目**，但构造器签名仍是 `(int, long, List, List)`
     * （参数在、字段被优化掉）。这不是猜测：那份 mapping 给了**无歧义**的映射。
     *
     * 内层 `ModInfo` 的字段名**没有变** —— `proguard-rules.pro` 的
     * `-keep class com.bd2toolsbox.data.model.** { *; }` 覆盖了它，实测夹具里
     * `character`/`convertedHashDir`/`name`/`uri` 等 14 个字段全是可读原名。
     * 所以 `mods` 数组可以直接用 [SnapshotEntry] 解析，**不需要为它再定义第二份 DTO**。
     *
     * **没有 `version` 可读**，所以这份 DTO 的版本闸只能靠「顶层有没有 `version` 键」
     * 来分形态（见 [load]），不能靠一个不存在的字段。
     *
     * 这份 DTO 是**过渡期**的东西，按计划由 T35–T36 连同这条兼容分支一起移除。
     */
    @Serializable
    internal data class LegacyObfuscatedSnapshot(
        @SerialName("a") val savedAt: Long = 0L,
        @SerialName("b") val mods: List<SnapshotEntry?>? = null,
        @SerialName("c") val sourceDirs: List<String>? = null
    )

    /**
     * **本类写入的形态**。字段名是规范名（不是 `a`/`b`/`c`），但同样是磁盘契约。
     *
     * [version] 是这份类的**题眼**：它就是 0.2.16 里被 R8 整个折叠掉的那个字段。
     * 当年 R8 能证明它恒等于 `SNAPSHOT_VERSION`（只在构造函数里被写入该常量、
     * 只被用来和同一个常量比较），于是把比较折成 `false`、把分支变死、再把字段删掉 ——
     * 生产包里因此**根本没有版本闸**（详见 `docs/refactor/evidence/T10.md` §2）。
     *
     * 这里的 [version] 由 Gson 反射写入（值来自磁盘上的文本），R8 无法证明它是常量；
     * 剩下唯一能毁掉它的手段是**把字段改名或删掉**，而这正是
     * `app/proguard-rules.pro` 里 T10 那条精确 keep 规则挡住的事。
     */
    @Serializable
    internal data class SnapshotV1(
        @SerialName("version") val version: Int = SCHEMA_VERSION,
        @SerialName("savedAt") val savedAt: Long = 0L,
        @SerialName("mods") val mods: List<SnapshotEntry?>? = null,
        @SerialName("sourceDirs") val sourceDirs: List<String>? = null
    )

    /** 快照文件本身（测试与调用方排查用）。 */
    val file: File get() = snapshotFile

    /** 捕获序号。只在 [capture] / [discard] 里递增，只在 [commit] 里读。 */
    private var revision = 0L

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    /**
     * 把「此刻要写的这张表」连同源目录快照一起捕获下来，分配一个递增序号。
     *
     * **必须在提交之前调用、且与列表状态的更新同序**：序号就是在这一刻定下的，
     * 晚到的旧扫描会拿到更小的序号，于是 [commit] 会拒收它。
     *
     * `@Synchronized`：删除成功、扫描收尾、清目录三处都会捕获，交错会拿乱序号。
     */
    @Synchronized
    fun capture(entries: List<SnapshotEntry>, sourceDirs: List<String>): PendingWrite {
        revision += 1
        return PendingWrite(
            revision = revision,
            savedAt = System.currentTimeMillis(),
            entries = entries.toList(),
            sourceDirs = sourceDirs.toList()
        )
    }

    /**
     * 把 [write] 原子地提交到磁盘。
     *
     * 两道闸，都**不改动磁盘**：
     * 1. [write] 的序号必须还是最新的 —— 否则说明它是被更晚的捕获取代的旧结果；
     * 2. [write] 捕获时的源目录必须与 [currentSourceDirs] 完全相同 —— 否则这份表
     *    属于一组用户已经改掉的源目录，写进去只会在下次冷启动显出一批点不动的幽灵条目。
     *
     * 失败（序列化 / 暂存 / 替换）**旧快照保持原样**：这一条由 [AtomicFileWriter] 的提交
     * 语义保证，本类不做任何补救式写入 —— 尤其是**绝不**先删目标再改名。
     *
     * `@Synchronized`：同一进程内只有这一处写这个文件，串起来才不会被交错写坏。
     */
    @Synchronized
    fun commit(write: PendingWrite, currentSourceDirs: List<String>): SnapshotCommit {
        if (write.revision != revision) {
            return SnapshotCommit.Skipped(
                "被更晚的捕获取代（write.revision=${write.revision}, 当前=$revision）"
            )
        }
        if (write.sourceDirs != currentSourceDirs) {
            return SnapshotCommit.Skipped(
                "源目录已变化（快照=${write.sourceDirs.size} 个，当前=${currentSourceDirs.size} 个）"
            )
        }
        return persist(write)
    }

    /** Serialization + atomic write only; callers own the revision/source gates. */
    private fun persist(write: PendingWrite): SnapshotCommit {
        val payload = SnapshotV1(
            version = SCHEMA_VERSION,
            savedAt = write.savedAt,
            mods = write.entries,
            sourceDirs = write.sourceDirs
        )
        val jsonText = try {
            json.encodeToString(SnapshotV1.serializer(), payload)
        } catch (e: Exception) {
            // 序列化失败与 IO 失败必须分开报：前者原样重试无用，后者可能只是这一次没写成。
            return SnapshotCommit.Failed(
                OperationError(
                    kind = OperationErrorKind.MALFORMED_DATA,
                    message = "快照序列化失败：" + e.message.orEmpty(),
                    detail = e.toString(),
                    target = snapshotFile.path
                )
            )
        }

        return try {
            AtomicFileWriter().write(target = snapshotFile) { out ->
                out.write(jsonText.toByteArray(Charsets.UTF_8))
            }
            SnapshotCommit.Written
        } catch (e: Exception) {
            SnapshotCommit.Failed(
                OperationError(
                    kind = OperationErrorKind.IO,
                    message = "快照写入失败（旧快照保持原样）：" + e.message.orEmpty(),
                    detail = e.toString(),
                    target = snapshotFile.path
                )
            )
        }
    }

    /**
     * 读快照并按 [expectedSourceDirs] 收口。**零副作用** —— 读路径绝不删、不改任何文件。
     *
     * 形态判定只看**顶层有没有 `version` 键**，不看内容，因为旧 release 格式里这个字段
     * 压根不存在（见 [LegacyObfuscatedSnapshot]）。这比「先按新 DTO 解析、失败了再试旧 DTO」
     * 稳：后者在版本不认识时会把一份**新格式**文件误判成旧格式。
     *
     * 闸门顺序与改动前一致：**版本 → 源目录 → 逐条校验**。
     */
    fun load(expectedSourceDirs: List<String>): SnapshotLoad {
        // 目录（而不是文件）占着这个路径时既不能当「不存在」也不能当「损坏」：
        // 当不存在会在下次写入时跟一个目录抢名字；当损坏会把那个目录当快照删掉，
        // 而目录里可能有用户的东西。归到「读不出来」这一支：不显示、也绝不动磁盘。
        if (snapshotFile.isDirectory) {
            return SnapshotLoad.IoError(
                OperationError(
                    kind = OperationErrorKind.IO,
                    message = "快照路径被一个目录占着，本次不读：${snapshotFile.path}",
                    target = snapshotFile.path
                )
            )
        }
        if (!snapshotFile.exists()) return SnapshotLoad.Absent

        val text = try {
            readSnapshot(snapshotFile)
        } catch (e: Exception) {
            return SnapshotLoad.IoError(
                OperationError(
                    kind = OperationErrorKind.IO,
                    message = "快照读取失败：" + e.message.orEmpty(),
                    detail = e.toString(),
                    target = snapshotFile.path
                )
            )
        }

        // 空白文件判**失效**而不是「合法空表」。与账本的处理刻意相反：账本读空表等于
        // 「用户没装过 mod」，那是关于**事实**的断言；而快照只是显示缓存，0 字节说明
        // 上次写坏了，清掉重建即可。计划把「空文件」列在必须显式失效的一侧。
        if (text.isBlank()) return SnapshotLoad.Corrupt("快照文件是空的（0 字节或全空白）")

        return try {
            parse(text, expectedSourceDirs)
        } catch (e: Exception) {
            SnapshotLoad.Corrupt(
                "快照无法解析（" + e.javaClass.simpleName + "）：" + e.message.orEmpty()
            )
        }
    }

    /**
     * 显式失效：**只删快照文件本身**，并把捕获序号推进一格。
     *
     * 推进序号是必需的：假如此刻还有一个更早捕获、尚未提交的写任务在队列里，
     * 删完文件它会把旧快照又写回来 —— 那就成了「失效完又复活」。
     *
     * 计划第 2 条：快照失效**只允许**清快照可重建缓存，**绝不**清安装账本 / 配置 /
     * `bundle_meta` / `origin_backup` / shared_prefs。本方法只碰 [snapshotFile] 一个路径，
     * 这一点由 `ModSnapshotStoreTest` 的「失效不越界」用例守着。
     *
     * @return 快照文件此刻是否已不存在（本来就没有也算成功）。
     */
    @Synchronized
    fun discard(): Boolean {
        revision += 1
        if (snapshotFile.exists()) snapshotFile.delete()
        return !snapshotFile.exists()
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 解析一份非空文本。**形态判定与版本闸只有这一处**，不散在读侧各处。
     *
     * 判据（顺序即优先级）：
     * 1. 顶层不是 JSON 对象（字面量 `null`、数组、标量、半截 JSON）→ 损坏；
     * 2. 顶层有 `version` → **v1 形态**：反序列化后由 [SnapshotV1.version] 判版本，
     *    不认识就 [SnapshotLoad.StaleVersion]（字段语义无保证，按空表读会静默丢条目）；
     * 3. 顶层没有 `version` 但有旧格式的 `b`（`mods`）→ **0.2.16 release 形态**，迁移读取；
     * 4. 都不像 → 损坏（这里的「都不像」是**得出结论**，不是「猜一种格式继续读」）。
     *
     * `mods` 缺失或为 `null` 一律判损坏：`mods` 为 `null` 说明这份表被谁改过，
     * 而「按空表继续」会把「用户有 5 个 mod」变成「用户没有 mod」。
     */
    private fun parse(text: String, expectedSourceDirs: List<String>): SnapshotLoad {
        val root = try {
            Json.parseToJsonElement(text)
        } catch (e: Exception) {
            return SnapshotLoad.Corrupt("快照不是合法 JSON：" + e.message.orEmpty())
        }
        if (root is JsonNull) return SnapshotLoad.Corrupt("快照内容是 JSON null，不是一张表")
        if (root !is JsonObject) {
            return SnapshotLoad.Corrupt("快照顶层不是 JSON 对象")
        }
        val obj = root

        val savedAt: Long
        val mods: List<SnapshotEntry?>?
        val sourceDirs: List<String>?
        var wasLegacy = false

        if (obj.containsKey(KEY_VERSION)) {
            val snapshot = try {
                json.decodeFromJsonElement(SnapshotV1.serializer(), obj)
            } catch (e: Exception) {
                return SnapshotLoad.Corrupt("新格式解析失败：" + e.message.orEmpty())
            }
            if (snapshot.version != SCHEMA_VERSION) {
                return SnapshotLoad.StaleVersion(snapshot.version)
            }
            savedAt = snapshot.savedAt
            mods = snapshot.mods
            sourceDirs = snapshot.sourceDirs
        } else if (obj.containsKey(KEY_LEGACY_MODS)) {
            // 0.2.16 release 的形态。它**没有版本字段可读**（R8 删掉了），所以这里
            // 能做的只有「按 mapping.txt 的无歧义映射读出来」，没有版本闸可过 ——
            // 这正是本任务要消灭的契约脆弱性，迁移完之后新文件才重新有了版本闸。
            val legacy = try {
                json.decodeFromJsonElement(LegacyObfuscatedSnapshot.serializer(), obj)
            } catch (e: Exception) {
                return SnapshotLoad.Corrupt("旧格式解析失败：" + e.message.orEmpty())
            }
            savedAt = legacy.savedAt
            mods = legacy.mods
            sourceDirs = legacy.sourceDirs
            wasLegacy = true
        } else {
            return SnapshotLoad.Corrupt(
                "顶层既没有新格式的 $KEY_VERSION，也没有旧格式的 $KEY_LEGACY_MODS"
            )
        }

        if (mods == null) {
            return SnapshotLoad.Corrupt("快照缺少 $KEY_MODS（或它为 null）")
        }
        if (sourceDirs != expectedSourceDirs) {
            return SnapshotLoad.SourceDirsChanged(from = sourceDirs ?: emptyList(), to = expectedSourceDirs)
        }

        // kotlinx 允许数组里出现 null 元素（元素类型可空）；逐条做结构校验，
        // 坏的整条丢掉并计数，而不是静默吞掉。
        val nonNull = mods.filterNotNull()
        val valid = nonNull.filter { it.isStructurallyValid() }
        return SnapshotLoad.Loaded(
            entries = valid,
            savedAt = savedAt,
            droppedEntries = mods.size - valid.size,
            wasLegacy = wasLegacy
        )
    }

    companion object {
        /** 快照文件名。放在这里而不是 `MainViewModel`，因为它是**磁盘契约**的一部分。 */
        const val SNAPSHOT_FILENAME = "mod_list_snapshot.json"

        /**
         * 快照结构版本。改了磁盘上字段的**含义**就 +1 —— 老快照会被 [load] 判成
         * [SnapshotLoad.StaleVersion] 并显式失效，按「没有快照」重建，比按缺字段的
         * 半截数据渲染强（那种数据会以 NPE 的形式在很远的地方炸）。
         */
        const val SCHEMA_VERSION = 1

        /** 新格式的顶层键。与 [SnapshotV1] 的 `@SerializedName` 必须一致（有用例钉住）。 */
        private const val KEY_VERSION = "version"
        private const val KEY_MODS = "mods"

        /** 0.2.16 release 的旧键（R8 改名产物），**只读**。 */
        private const val KEY_LEGACY_MODS = "b"
    }
}
