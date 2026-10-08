package com.bd2toolsbox.core.model

/**
 * 领域操作模型（T05）。
 *
 * 这里放的是**业务参数与结果**：任务身份、阶段、可取消性、已提交/失败目标、错误分类与
 * 可重试性，以及各类「计划」。它们此前散落在 `MainViewModel` 的类体内，于是
 * 「一个计划是什么」既取决于 ViewModel 的成员状态，也取决于 UI 怎么读它 —— 想在 JVM
 * 单测里断言一次计划，就得先造一个带 Context 的 ViewModel。
 *
 * 硬约束（有测试直接扫字段类型来守）：
 *   · **不持有** ViewModel / Context / Composable / PyObject；
 *   · **不引用** `android.*`、`com.bd2toolsbox.ui.*` 或经 `ModInfo` 传递依赖
 *     `android.net.Uri`（`ModInfo.uri` 就是 `Uri`）；
 *   · 需要 `ModInfo` 的场合一律用领域标识（uri 字符串、bundle 名、hash 目录名），
 *     由调用侧做一次显式 adapter。
 *
 * 错误只有**一套**分类（[OperationErrorKind]），文件访问、归档边界与提交语义都复用它，
 * 不另造平行异常或第二份「可重试吗」的判断。
 */

// ---------------------------------------------------------------------------
// 任务身份、阶段、可取消性
// ---------------------------------------------------------------------------

/** 任务身份。由调用方分配；模型不生成、不持有全局状态，因此可自由比较与持久化。 */
data class OperationId(val value: String) {
    init {
        require(value.isNotBlank()) { "OperationId 不能为空" }
    }
}

/** 宿主启动前登记的任务参数；目标是领域标识，不是可直接删除的文件路径。 */
data class OperationRequest(
    val id: OperationId,
    val kind: OperationKind,
    val targets: List<String>
) {
    init {
        require(targets.isNotEmpty()) { "任务缺少目标参数" }
        require(targets.all { it.isNotBlank() && it == it.trim() }) { "目标标识不能为空或带首尾空白" }
        require(targets.distinct().size == targets.size) { "同一任务不能重复登记目标" }
    }
}

sealed class OperationAdmission {
    data class Accepted(val id: OperationId) : OperationAdmission()
    data class Rejected(val conflictingId: OperationId, val error: OperationError) : OperationAdmission()
}

/** 一个操作走到哪一步。终态由 [isTerminal] 判定。 */
enum class OperationStage(val id: String) {
    QUEUED("queued"),
    PREPARING("preparing"),
    RUNNING("running"),
    ENUMERATING("enumerating"),
    PARSING("parsing"),
    COMMITTING("committing"),
    COMPLETED("completed"),
    PARTIALLY_FAILED("partially-failed"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    INTERRUPTED("interrupted");

    val isTerminal: Boolean
        get() = when (this) {
            COMPLETED, PARTIALLY_FAILED, FAILED, CANCELLED, INTERRUPTED -> true
            else -> false
        }
}

/**
 * 操作类别。
 *
 * 「扫描来源目录」「扫描游戏索引」「预解包」是**三个不同操作**，不是同一个「任务」的
 * 三个名字：输入不同（SAF 目录 / 游戏 Shared 目录 / 已转换产物）、产物不同
 * （mod 列表 / bundle 清单 / 预览缓存）、失败后果也不同。把它们折成同一个类别，
 * UI 就没法说清「这次没结果」到底是哪一件事没成。
 */
enum class OperationKind(
    val id: String,
    /** 该操作在正常路径上是否允许取消（终态一律不可取消，见 [OperationStage.isTerminal]）。 */
    val cancellable: Boolean,
    /** 正常路径；细分的枚举/解析阶段可由对应执行器使用。 */
    val stages: List<OperationStage> = listOf(
        OperationStage.QUEUED, OperationStage.PREPARING, OperationStage.RUNNING,
        OperationStage.COMMITTING, OperationStage.COMPLETED
    )
) {
    /** 扫描 mod **来源目录**（SAF）。产物是「发现了哪些 mod」。 */
    SCAN_MOD_SOURCES(
        "scan-mod-sources",
        cancellable = true,
        stages = listOf(
            OperationStage.QUEUED,
            OperationStage.PREPARING,
            OperationStage.RUNNING,
            OperationStage.ENUMERATING,
            OperationStage.PARSING,
            OperationStage.COMMITTING,
            OperationStage.COMPLETED
        )
    ),

    /** 扫描**游戏索引**（Shizuku 读游戏 Shared 目录 + 对照 catalog）。产物是 bundle 清单。 */
    SCAN_GAME_INDEX(
        "scan-game-index",
        cancellable = true,
        stages = listOf(
            OperationStage.QUEUED,
            OperationStage.PREPARING,
            OperationStage.RUNNING,
            OperationStage.ENUMERATING,
            OperationStage.COMMITTING,
            OperationStage.COMPLETED
        )
    ),

    /** **预解包**：把已转换产物解成可预览素材并落缓存。产物是缓存条目。 */
    PREPACK(
        "prepack",
        cancellable = true,
        stages = listOf(
            OperationStage.QUEUED,
            OperationStage.PREPARING,
            OperationStage.RUNNING,
            OperationStage.PARSING,
            OperationStage.COMMITTING,
            OperationStage.COMPLETED
        )
    ),

    INSTALL("install", cancellable = true),
    UNINSTALL("uninstall", cancellable = true),
    PREVIEW("preview", cancellable = true),
    CLEAR_CACHE("clear-cache", cancellable = true);

    init {
        require(stages.firstOrNull() == OperationStage.QUEUED) { "$id 的阶段序列必须从 QUEUED 开始" }
        require(stages.last() == OperationStage.COMPLETED) {
            "$id 的阶段序列必须以 COMPLETED 收尾，实际是 ${stages.last()}"
        }
        require(stages.dropLast(1).none { it.isTerminal }) {
            "$id 的阶段序列不得含旁路终态"
        }
    }
}

// ---------------------------------------------------------------------------
// 错误分类与可重试性
// ---------------------------------------------------------------------------

/**
 * 错误的**唯一**分类。重试性由类别决定（[retryable]），不允许调用方各自判断
 * 「这个异常要不要重试」—— 那正是「权限被拒」被当成「重试一下就好」的来路。
 *
 * 刻意不写成 `RuntimeException` 子类：这些错误是**结果值**，不是控制流。
 */
enum class OperationErrorKind(val retryable: Boolean) {
    /** 权限被拒（SAF 授权被撤、shell 权限不足）。重试同一个动作无用，要用户重新授权。 */
    PERMISSION_DENIED(false),

    /** 与服务端的连接断了（Shizuku 未运行、binder 断连）。恢复连接后重试有意义。 */
    NOT_CONNECTED(true),

    /** 目标不存在。要外部状态先变化（补文件、游戏下载资源），重试同一个动作无用。 */
    NOT_FOUND(false),

    /** 拿到的数据无法解析（分页 JSON 被截断、清单格式不对）。数据本身有问题，原样重试无用。 */
    MALFORMED_DATA(false),

    /** 输入非法（归档条目越界、参数不合法）。 */
    INVALID_INPUT(false),

    /** 平台/文件系统不支持所需语义（如不支持原子提交）。重试无用，只能降级或中止。 */
    UNSUPPORTED(false),

    /** I/O 失败（磁盘满、文件被占用、单文件读失败）。 */
    IO(true),

    /** 资源正被另一任务占用，待其结束后可重新提交。 */
    BUSY(true),

    /** 被取消。是终止，不是错误 —— 重试与否由调用方按业务决定。 */
    CANCELLED(false),

    /** 归类不出来。按不可重试处理，避免用重试掩盖未知问题。 */
    UNKNOWN(false)
}

/**
 * 一次失败的领域描述。
 *
 * [target] 只在**目标级**失败时非空（哪个 bundle / 哪个 uri / 哪个路径）；
 * 整个操作级失败留 null。UI 只读 [message]（人话）与 [detail]（技术细节，收进折叠区），
 * 不接触底层异常类型。
 */
data class OperationError(
    val kind: OperationErrorKind,
    val message: String,
    val detail: String? = null,
    val target: String? = null
) {
    init {
        require(message.isNotBlank()) { "OperationError.message 不能为空" }
    }

    /** 是否值得原样重试 —— 由 [kind] 唯一决定，不提供按实例覆盖的入口。 */
    val retryable: Boolean get() = kind.retryable
}

// ---------------------------------------------------------------------------
// 已提交 / 失败目标
// ---------------------------------------------------------------------------

/** 单个目标在一次操作里的结局。 */
enum class TargetStatus {
    /** 已提交：写入已完成，可以对外声称已生效。 */
    COMMITTED,

    /** 失败：这个目标没成。必须带 [TargetOutcome.error]。 */
    FAILED,

    /** 跳过：按规则不该做（目标已是最新、已存在同名）。**不是失败**，不报错给用户。 */
    SKIPPED
}

/**
 * 一个目标的结局。
 *
 * [SKIPPED] 与 [FAILED] 必须分开：跳过是「在这种情形下唯一安全的选择」，
 * 报成失败只会让用户以为坏了、反复重试。
 */
data class TargetOutcome(
    /** 目标标识：bundle 名、hash 目录名、mod uri 或路径。 */
    val target: String,
    val status: TargetStatus,
    val error: OperationError? = null
) {
    init {
        require(target.isNotBlank()) { "TargetOutcome.target 不能为空" }
        require((status == TargetStatus.FAILED) == (error != null)) {
            "只有 FAILED 才带 error：status=$status, error=${error != null}"
        }
        val failure = error
        if (status == TargetStatus.FAILED && failure != null) {
            require(failure.target == null || failure.target == target) {
                "FAILED 的 error.target 必须是这个目标本身，实际是 ${failure.target}"
            }
        }
    }
}

/**
 * 一次操作的进度/结果快照。只描述业务要素，可在 JVM 单测里直接构造与断言。
 *
 * 「已提交」与「失败」两列同时存在，是因为长任务（批量装入、一键卸载）本来就是
 * **部分成功**：一半目标写成功、一半失败。只给一个总错误码，用户就没法知道
 * 哪些已经生效、要不要再点一次。
 */
data class OperationState(
    val id: OperationId,
    val kind: OperationKind,
    val stage: OperationStage,
    val committedTargets: List<String> = emptyList(),
    val failedTargets: List<TargetOutcome> = emptyList(),
    val error: OperationError? = null,
    val skippedTargets: List<String> = emptyList(),
    val totalTargets: Int = committedTargets.size + failedTargets.size + skippedTargets.size,
    val cancellationRequested: Boolean = false,
    val progressMessage: String = ""
) {
    init {
        val outcomes = committedTargets + failedTargets.map { it.target } + skippedTargets
        require(outcomes.distinct().size == outcomes.size) { "同一目标只能有一个结果" }
        require(failedTargets.all { it.status == TargetStatus.FAILED }) { "失败列表只能包含失败目标" }
        require(totalTargets >= outcomes.size) { "已报告结果不能超过任务目标数" }
    }

    /** 请求可以在提交时登记，但不能立即中断正在落盘的临界区。 */
    val cancellable: Boolean
        get() = kind.cancellable && !isTerminal && stage != OperationStage.COMMITTING && !cancellationRequested

    val completedTargets: Int get() = committedTargets.size + failedTargets.size + skippedTargets.size
    val isTerminal: Boolean get() = stage.isTerminal
}

// ---------------------------------------------------------------------------
// 计划模型（此前内嵌在 MainViewModel）
// ---------------------------------------------------------------------------

/**
 * 扫描 mod 来源目录的进度。null（不在扫）由持有方表示，这里只描述「正在扫」。
 *
 * 粒度是**目录**级（逐目录 SAF 遍历 + 解析是耗时大头）加上后段的固定阶段（校验/装机状态）。
 * 用户反馈「点了重新扫描根本不知道有没有开始」—— 只给一个布尔，冷启动/大库时好几秒
 * 毫无反馈。
 */
data class ModScanProgress(
    val doneDirs: Int,
    val totalDirs: Int,
    /** 当前阶段的说明文案，如「扫描目录 2/3」「校验 mod 状态」。 */
    val stage: String
) {
    init {
        require(doneDirs >= 0) { "doneDirs 不能为负" }
        require(totalDirs >= 0) { "totalDirs 不能为负" }
    }
}

/** 批量预解包的评估结果，给确认框用。 */
data class PrepackPlan(
    val total: Int,
    val alreadyCached: Int,
    val todo: Int,
    val estimatedBytes: Long,
    val freeBytes: Long,
    val ratioFromSamples: Boolean
) {
    /** 预估占用超过可用空间的九成就拦下 —— 塞满存储比多等一会儿糟得多。 */
    val enoughSpace: Boolean get() = estimatedBytes < freeBytes * 0.9
}

/**
 * 一键卸载的预估结果，用于确认对话框。
 *
 * [failure] 非空表示这次「压根没测出来」，而不是「没东西要卸」——
 * 对话框据此改成报错，不能再说「所有资源都是官方原版」。之前的 `error: String?` /
 * `detail: String?` 两个裸字段承载的是同一件事，现在由分类后的 [OperationError]
 * 唯一承载，[error] / [detail] 只是给 UI 的取文案入口。
 */
data class UninstallPlan(
    val bundles: List<String>,
    val fromBackup: Int,
    val needDownload: Int,
    val downloadBytes: Long,
    val failure: OperationError? = null
) {
    /** 非空 = 这次没测出结果。UI 直接显示。 */
    val error: String? = failure?.message

    /** [error] 的技术细节，对话框里收进「详细原因」折叠区。 */
    val detail: String? = failure?.detail
}

/**
 * 「装产物会覆盖同包已装 mod」的预警。
 *
 * 产物是已经打包好的完整 bundle，直接覆盖写进游戏目录，没有重打包环节，
 * 所以**无法**像 [RepackPlanBuilder] 那样把同包已装的 mod 并进去 ——
 * 只能在动手前说清会损失什么。
 *
 * 这里存的是**领域标识**（产物 uri 字符串）而不是 `ModInfo`：`ModInfo.uri` 是
 * `android.net.Uri`，把它塞进计划会让整个计划在 JVM 单测里不可构造。调用方在
 * 确认装入时用 uri 反查一次 `ModInfo` 即可（显式 adapter）。
 */
data class ConvertedOverwritePlan(
    val modUris: List<String>,
    /** bundle 名 -> 会被覆盖掉的已装 mod 名字 */
    val casualties: Map<String, List<String>>,
    /** 其中源文件仍在、可改走转换流程保住的 bundle 名 */
    val recoverable: Set<String>
) {
    val total: Int get() = casualties.values.sumOf { it.size }
}

/** `collectPreviewFiles` 的产物：spine 三件套 + 全部贴图（静态查看兜底用）。 */
data class PreviewAssets(
    val skelPath: String?,
    val atlasPath: String?,
    val pngPaths: List<String>
)

/**
 * 一批 hash 目录改名的结果。**跳过与失败必须分开**：跳过（目标名已存在）不是错误，
 * 是在那种情形下唯一安全的选择，报成失败只会让用户以为坏了、反复重试。
 */
sealed class HashRenameResult {
    /** 改好了（或本来就已经是新哈希，无需改）。 */
    object Renamed : HashRenameResult()

    /** 同目录里已经有一个同名（新哈希）目录，没动任何东西。 */
    object TargetExists : HashRenameResult()

    /**
     * 游戏目录里这个 bundle 还停在别的哈希上（`gameBundleHashes` 有据可查）——
     * 用户还没进游戏把资源更新到 catalog 这一版。这时改名是改早了：改完游戏
     * 根本不读，mod 静默失效，正是最坏的失败模式。宁可不改，让用户先更新资源。
     */
    object GameBehindCatalog : HashRenameResult()
    data class Failed(val reason: String) : HashRenameResult()
}

// ---------------------------------------------------------------------------
// 重打包计划：同 bundle 的多个 mod 必须一起保留
// ---------------------------------------------------------------------------

/**
 * 计划里对一个 mod 的最小领域引用 —— 只带身份，不带 `android.net.Uri` 或 UI 状态。
 */
data class PlannedMod(
    val modUri: String,
    val name: String,
    val targetHash: String,
    /**
     * 资源身份。`_extract_family_key` 会剥掉 `_1` 后缀，所以**同 familyKey 不等于
     * 同一个 mod**。保留只为展示与排查，[RepackPlanBuilder] 绝不按它归并。
     */
    val familyKey: String? = null
) {
    init {
        require(modUri.isNotBlank()) { "modUri 不能为空" }
        require(targetHash.isNotBlank()) { "targetHash 不能为空" }
    }
}

/** 一条装入记账的领域视图。 */
data class InstalledModRef(
    val modUri: String,
    val targetHash: String,
    val modName: String
)

/** 同一个 bundle 的一次重打包：基底是干净原版，[mods] 是要一起打进去的全部 mod。 */
data class RepackPlanJob(val targetHash: String, val mods: List<PlannedMod>) {
    init {
        require(targetHash.isNotBlank()) { "targetHash 不能为空" }
    }
}

/** 重打包计划 + 被丢弃的已装项（记账里有、但源文件已不在，没法一起重打包）。 */
data class RepackPlan(
    val jobs: List<RepackPlanJob>,
    val droppedInstalled: List<InstalledModRef> = emptyList()
)

/**
 * 把「本次选中的 mod」与「该 bundle 上已经装着的 mod」合成重打包计划。
 *
 * 为什么必须并入已装的：游戏把多个角色的资源打进同一个包（实测某个立绘包被 45 个角色
 * 共用，心契之约的 19 个角色更是全在一个包里）。而重打包取基底时只要发现包已被改过就
 * 退回官方原版重打（刻意的保守选择，避免在改过的包上反复叠加导致 Spine 贴图页累积、
 * atlas 错乱）。两者相加的后果是：分两次装同一个包里的两个 mod，第二次会把第一次的
 * 改动抹掉 —— 而按角色浏览的界面天然就是一次装一个。
 *
 * 所以基底仍然用干净原版，但把这个包上所有要保留的 mod **一起**重新打包：
 * 不叠加、不丢失，同一组 mod 无论装几次都得到同样的包。合并顺序是
 * **已装的排前、本次选中的排后**：`processSingleJob` 顺序把各 mod 的文件拷进同一目录，
 * 后者覆盖同名文件，于是「换掉同一资源的另一个版本」与「新增另一个角色」都自然正确。
 *
 * 去重**只按 [PlannedMod.modUri]**，绝不按 familyKey：`_extract_family_key` 会剥掉
 * `_1` 后缀，拆成 Part A / Part B 的 mod（`cutscene_char004091` 与
 * `cutscene_char004091_1`，实为两个资源、需同时装）算出同一个 key，按它去重会把
 * 其中一个弄丢。
 */
object RepackPlanBuilder {
    /**
     * @param selected 本次选中的 mod（已按调用方的资格条件过滤过）。
     * @param installed 装入记账（可含不相关 bundle 的记录，本函数自行筛）。
     * @param availableByUri 可用于还原已装项的 mod 索引。**注意调用方会收紧口径**：
     *   `MainViewModel.buildRepackJobs` 传进来的是「源文件仍在 **且当前解析得出
     *   targetHash**」的那些 —— 一个已装项若在重扫后 targetHash 变空（未解析/有缺陷），
     *   这里查不到它就按「丢弃」处理，尽管源文件其实还在。丢弃原因由调用方区分后记日志。
     */
    fun build(
        selected: List<PlannedMod>,
        installed: List<InstalledModRef>,
        availableByUri: Map<String, PlannedMod>
    ): RepackPlan {
        if (selected.isEmpty()) return RepackPlan(emptyList())

        val targetHashes = selected.mapTo(LinkedHashSet()) { it.targetHash }
        val selectedUris = selected.mapTo(HashSet()) { it.modUri }
        val jobs = LinkedHashMap<String, MutableList<PlannedMod>>(targetHashes.size)
        for (hash in targetHashes) jobs[hash] = ArrayList()

        val dropped = ArrayList<InstalledModRef>()
        // 已装项里同一条记账重复出现时只保留一次。**只按 uri 去重**。
        val keptUris = HashSet<String>()

        for (ref in installed) {
            // 本次没装这个包：它保持原样，不进计划。
            if (ref.targetHash !in jobs) continue
            // 本次已选中：由下面的 selected 那一轮放进去，避免重复。
            if (ref.modUri in selectedUris) continue
            if (!keptUris.add(ref.modUri)) continue
            val mod = availableByUri[ref.modUri]
            if (mod == null) {
                // 源文件已不在（用户删了文件夹、或换了 mod 目录）。没法重打包，只能放弃它，
                // 但**要说出来** —— 静默丢弃的结果是用户发现某个 mod 莫名失效却无从追查。
                dropped.add(ref)
                continue
            }
            jobs.getValue(ref.targetHash).add(mod)
        }

        val seenSelected = HashSet<String>()
        for (mod in selected) {
            if (!seenSelected.add(mod.modUri)) continue
            jobs.getValue(mod.targetHash).add(mod)
        }

        return RepackPlan(
            jobs = jobs.map { (hash, mods) -> RepackPlanJob(hash, mods) },
            droppedInstalled = dropped
        )
    }
}
