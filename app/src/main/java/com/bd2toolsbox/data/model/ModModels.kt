package com.bd2toolsbox.data.model

import android.net.Uri

enum class ResolutionState {
    KNOWN,
    MISC,
    UNKNOWN,
    INVALID
}

/** mod 当前在游戏里的装入状态。由「干净检测」叠加装入记账推导，不是用户勾选状态。 */
enum class ModInstallState {
    /** 未装入 —— 目标 bundle 是干净原版，且记账里没有它 */
    NOT_INSTALLED,

    /** 生效中 —— 目标 bundle 已被修改，且记账显示是本 app 装的这个 mod */
    INSTALLED,

    /** 需重新应用 —— 记账里有，但游戏侧已变（游戏更新换了 bundle，或已被还原） */
    STALE,

    /** 目标 bundle 被非本 app 修改（旧版工具 / 手动装入）—— 装入前会先取回官方原版做基底 */
    MODIFIED_BY_OTHER,

    /** 无法校验 —— Shizuku 不可用，拿不到游戏目录的实际情况 */
    UNVERIFIED,
}

/**
 * 一条装入记录 —— **一个 mod 一条**。
 *
 * 主键是 [modUri]。曾经用 [familyKey] 做主键、一个 bundle 只记一条（modUris 是列表），
 * 那是错的：一个 bundle 往往装着多个角色的 mod（实测某个立绘包被 45 个角色共用），
 * 而记录的 familyKey 只取了包内第一个 mod 的。后果有两个，都实测过：
 *   · 卸载包内第二个 mod 时按它自己的 familyKey 查不到记录，被判定为「该包已无 mod」，
 *     于是整包还原原版，把第一个 mod 也一起卸掉；
 *   · 状态推导同样查不到，第二个 mod 显示成「被其他工具改过」而不是「生效中」。
 *
 * 也不能改用 familyKey 一 mod 一条 —— 它不唯一：`_extract_family_key` 会剥掉 `_1`
 * 后缀，所以拆成 Part A / Part B 的 mod（`cutscene_char004091` 与
 * `cutscene_char004091_1`，实为两个资源、需同时装）算出同一个 key，记录会互相覆盖。
 * mod 的 uri 才是真正唯一的。
 *
 * [familyKey] 仍然保留，但降为普通字段：游戏更新后 [snapshotTargetHash] 会失效，
 * 靠资源身份才能认出「这个 mod 需要重新应用」。
 *
 * 不存装入时的大小/哈希快照 —— 干净检测直接拿 catalog 的权威值比对，比自己的快照可信。
 */
data class InstalledModRecord(
    val modUri: String,
    val modName: String,
    val familyKey: String,
    val snapshotTargetHash: String,
    val quality: String,
    val usedAstc: Boolean,
    val installedAt: Long
)

/** 某个 bundle 当前在游戏目录里的干净程度。catalog 权威值 vs 本地实际值的比对结果。 */
enum class BundleCleanState {
    /** 目录名与内容哈希一致、大小等于官方原版 —— 可直接用作重打包基底 */
    PRISTINE,

    /** 目录名对得上但大小不符 —— 里面装着 mod */
    MODIFIED,

    /** 目录名与 catalog 的内容哈希不同 —— 游戏更新过 */
    VERSION_MISMATCH,

    /** 游戏目录里没有这个 bundle（未下载该资源） */
    ABSENT,

    /** 拿不到判定依据（Shizuku 不可用或 catalog 缺失） */
    UNKNOWN,
}

enum class MatchStrategy {
    EXACT,
    NORMALIZED,
    EXTENSION_MAPPING,
    LOCAL_SCAN,
    FALLBACK,
    NONE
}

data class ResolvedTarget(
    val originalFileName: String,
    val normalizedCandidates: List<String> = emptyList(),
    val resolvedAssetKey: String? = null,
    val resolvedBundleName: String? = null,
    val resolvedBundlePath: String? = null,
    val assetType: String? = null,
    val targetHash: String? = null,
    val familyKey: String? = null,
    val matchStrategy: MatchStrategy = MatchStrategy.NONE,
    val confidence: Float = 0f
)

/** 一个条目是「待转换的 PC mod」还是「已经转换好的安卓产物」。 */
enum class ModKind {
    /** PC 版 mod 源文件（.skel/.atlas/.png 或 zip）—— 需要重打包成 bundle 才能装 */
    PC_SOURCE,

    /**
     * 已转换好的安卓产物（`<bundle名>/<hash>/__data`）。
     * 它本身就是完整的 Unity bundle，装入只是放到正确位置，不需要重打包。
     */
    CONVERTED_BUNDLE,
}

/**
 * mod 按玩家的认知分成哪一类。用于 PC 页「角色 → 类别」的两级导航。
 *
 * 顺序即列表里的展示顺序：过场动画最常被替换，放最前；「其他」兜底垫底。
 */
enum class ModCategory(val label: String) {
    CUTSCENE("过场动画"),
    ILLUST("立绘"),
    DATING("心契之约"),
    OTHER("其他"),
}

/**
 * 判断一个 mod 属于哪一类。
 *
 * 两个坑：
 *
 * 1. **`type` 有两种写法**。PC 版 mod 走 ModRepository 解析，拿到的是角色表里的原值
 *    `cutscene`/`idle`/`misc`；已转换产物走 [BundleNameResolver.resolve]，那边已经
 *    翻成了中文「过场动画」/「立绘」。两条来源都要认，否则 PC 页和移动端页会分错。
 *
 * 2. **「心契之约」必须先判**。它在角色表里没有独立的 type —— 实测 411 条里 20 条属于
 *    心契之约，其 type 既有 `idle` 也有 `cutscene`（如 Scheherazade 两条都有）。
 *    靠 costume 末尾的 `/ DATING SIM` 标记识别，且要在 type 判断之前，
 *    否则它们会被拆进过场动画和立绘两堆里。
 */
fun categoryOf(type: String?, costume: String?): ModCategory {
    if (costume?.contains("DATING SIM", ignoreCase = true) == true) return ModCategory.DATING
    val t = type?.trim().orEmpty()
    return when {
        t.equals("cutscene", true) || t == "过场动画" -> ModCategory.CUTSCENE
        t.equals("idle", true) || t == "立绘" -> ModCategory.ILLUST
        else -> ModCategory.OTHER
    }
}

/** 角色表没收录时 character 会是这些值，列表里要归到末尾的「未识别」组。 */
fun isUnknownCharacter(character: String?): Boolean {
    val c = character?.trim().orEmpty()
    return c.isEmpty() || c == "Unknown" || c == "Unknown Character" ||
            c == "未识别" || c == "未知角色"
}

data class ModInfo(
    val name: String,
    val character: String,
    val costume: String,
    val type: String,
    val isEnabled: Boolean,
    val uri: Uri,
    val targetHashedName: String?,
    val isDirectory: Boolean,
    val resolutionState: ResolutionState = ResolutionState.UNKNOWN,
    val targetHash: String? = targetHashedName,
    val resolvedFamilyKey: String? = null,
    val resolvedTargets: List<ResolvedTarget> = emptyList(),
    val unresolvedFiles: List<String> = emptyList(),
    val errorReason: String? = null,
    /** 装入状态。不进 ModCacheInfo —— 它与扫描缓存是两份独立数据，
     *  扫描缓存会随 bundle 索引整体作废，而装入状态必须在游戏更新后存活。 */
    val installState: ModInstallState = ModInstallState.NOT_INSTALLED,
    val kind: ModKind = ModKind.PC_SOURCE,
    /** 仅 CONVERTED_BUNDLE：产物里的 hash 目录名，装入时要保持这层结构 */
    val convertedHashDir: String? = null,
    /** 仅 CONVERTED_BUNDLE：__data 字节数，用于显示体积与装入前的空间预估 */
    val convertedDataSize: Long = 0L
)

data class ModCacheInfo(
    val uriString: String,
    val lastModified: Long,
    val name: String,
    val character: String,
    val costume: String,
    val type: String,
    val targetHashedName: String?,
    val isDirectory: Boolean,
    val resolutionState: ResolutionState = ResolutionState.UNKNOWN,
    val targetHash: String? = targetHashedName,
    val resolvedFamilyKey: String? = null,
    val unresolvedFiles: List<String> = emptyList(),
    val errorReason: String? = null
)

data class CharacterInfo(val character: String, val costume: String, val type: String, val hashedName: String)

data class ModDetails(val fileId: String?, val fileNames: List<String>)

data class RepackJob(val hashedName: String, val modsToInstall: List<ModInfo>)

sealed class JobStatus {
    object Pending : JobStatus()
    data class Downloading(val progressMessage: String = "等待中...") : JobStatus()
    data class Installing(val progressMessage: String = "正在初始化...") : JobStatus()
    data class Finished(val relativePath: String) : JobStatus()
    data class Failed(val displayMessage: String, val detailedLog: String) : JobStatus()
}

data class InstallJob(
    val job: RepackJob,
    val status: JobStatus = JobStatus.Pending
)

data class FailedJobInfo(
    val hashedName: String,
    val error: String
)

data class FinalInstallResult(
    val successfulJobs: Int,
    val failedJobs: Int,
    val command: String?,
    val elapsedTimeMs: Long = 0L,
    val failedJobDetails: List<FailedJobInfo> = emptyList(),
    val shizukuAvailable: Boolean = false,
    /**
     * true = 产物是**直接写进游戏目录**的，压根不存在「再装一次」这一步。
     *
     * 显式标出来，是因为这条路（已转换产物直拷）与「转换后落在 Download/Shared、
     * 需要再装一次」那条路共用同一个对话框和同一个 moveState。早先只靠 command
     * 恰好为 null 来区分，结果被状态更新的时序钻了空子：finalResult 先到、
     * moveState 还是 Idle 的那一帧，对话框弹出了「一键装入游戏」，一点就报
     * 「未找到 Download/Shared 目录」。判断要落在这个字段上，不能靠巧合。
     */
    val alreadyInGame: Boolean = false
)

sealed class UninstallState {
    object Idle : UninstallState()
    data class Downloading(val hashedName: String, val progressMessage: String = "正在初始化...") : UninstallState()
    /**
     * [command] 是手动装入的命令文本；装入游戏成功后会被置为 null，
     * 借此让「一键装入游戏」按钮消失 —— 那时 Download/Shared 已被移空，
     * 再点只会报「未找到 Download/Shared 目录」。
     */
    data class Finished(val command: String?, val shizukuAvailable: Boolean = false) : UninstallState()
    data class Failed(val error: String) : UninstallState()
}

sealed class MoveState {
    object Idle : MoveState()
    object Moving : MoveState()
    data class Success(val message: String) : MoveState()
    data class Failed(val error: String) : MoveState()
}

/**
 * 长按预览的准备过程。
 *
 * PC 版 mod 只是拷几个散文件，快到不需要状态；但已转换产物得先把二进制 bundle 解包出
 * skel/atlas/png，要几秒（还得解码贴图），所以需要让用户看到进度，失败也要说清原因 ——
 * 旧版这里失败是静默的，长按毫无反应，分不清是不支持还是坏了。
 */
sealed class PreviewState {
    object Idle : PreviewState()
    data class Preparing(val message: String) : PreviewState()
    data class Failed(val error: String) : PreviewState()
}

sealed class UnpackState {
    object Idle : UnpackState()
    data class Unpacking(val progressMessage: String = "正在初始化...") : UnpackState()
    data class Finished(val message: String) : UnpackState()
    data class Failed(val error: String) : UnpackState()
}

sealed class MergeState {
    object Idle : MergeState()
    data class Merging(val progressMessage: String = "正在初始化...") : MergeState()
    data class Finished(val message: String) : MergeState()
    data class Failed(val error: String) : MergeState()
}

sealed class BundleScanState {
    object Idle : BundleScanState()
    data class Confirmation(val bundleCount: Int) : BundleScanState()
    data class Scanning(
        val currentIndex: Int,
        val totalCount: Int,
        val currentBundle: String,
        val progressMessage: String = ""
    ) : BundleScanState()
    data class Finished(
        val scannedCount: Int,
        val failedCount: Int,
        val message: String
    ) : BundleScanState()
    data class Failed(val error: String) : BundleScanState()
}

data class BundleCheckResult(
    val bundleListJson: String,
    val needsScanJson: String,
    val hashMap: Map<String, String>,
    val needsScanCount: Int
)
