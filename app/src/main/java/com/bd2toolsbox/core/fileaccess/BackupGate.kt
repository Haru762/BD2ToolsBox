package com.bd2toolsbox.core.fileaccess

/**
 * 「自动备份开启时，备份失败该怎么办」这条判定（T07）。
 *
 * ## 为什么把这条判定抽出来
 *
 * 修 T07 之前，三处 `BundleBackupRepository.saveBackup(...)` 的返回值**全被丢弃**
 * （`MainViewModel.kt` 的转换流程、`restoreBaseBundle`、`backupPristineOriginal`），
 * 其中 `backupPristineOriginal` 更是在备份失败后照样打印「已备份原版」——
 * 用户以为有还原点，实际上没有。
 *
 * 修完之后这三处都要按同一套规则判断，而**规则本身**是一段与 Android 无关的纯逻辑。
 * 把它放在这里（而不是在三处各写一遍 `if`）有两个理由：
 *
 *  1. 三处各自判断必然会漂移，而其中一处（提交路径）判断错了会**丢原版**；
 *  2. 调用点都在 `MainViewModel` 里，构造它要 `Context`、本仓没有 Robolectric，
 *     留在那里的判断**一行都测不了**。抽成纯函数后它有真实的 JVM 用例。
 *
 * 本文件不 import 任何 `android.*`（`core/fileaccess` 整包的约定）。
 */

/**
 * 备份结果对本次写入的处置。
 *
 * 三态而不是布尔，是因为「不在提交路径上」的失败**必须留下可见痕迹**却**不该中止**——
 * 用 `true` 表示会丢掉「要上报」这层信息，用 `false` 表示会做出错误的处置。
 */
enum class BackupGateDecision {
    /** 照常写入，且**不需要**额外上报（备份关着，或备份真的成功了）。 */
    PROCEED,

    /**
     * **中止本次对该目标的写入**。目标文件一个字节都不动。
     *
     * 只用于提交路径：这一步写完，游戏目录里那份原版就永远找不回来了。
     */
    ABORT,

    /**
     * 照常写入，但备份失败这件事**必须让用户看见**（日志 + 挂在该任务的状态/详情上）。
     *
     * 用于**不在**提交路径上的顺手备份：那里失败不妨碍产物本身的正确性，
     * 中止转换的代价大于收益 —— 见 [decideBackupGate] 的说明。
     */
    PROCEED_WITH_WARNING;

    /** 本次是否放行写入。 */
    val allowsWrite: Boolean get() = this != ABORT

    /** 调用方是否必须留下可见痕迹。 */
    val mustReport: Boolean get() = this == PROCEED_WITH_WARNING
}

/**
 * 备份开关 + 备份结果 + 是否在提交路径上 ⇒ 处置。
 *
 * | 备份开关 | 备份成功 | 在提交路径上 | 判定 |
 * |---|---|---|---|
 * | 关 | —（没做备份） | — | [BackupGateDecision.PROCEED] |
 * | 开 | 是 | — | [BackupGateDecision.PROCEED] |
 * | 开 | 否 | **是** | [BackupGateDecision.ABORT] |
 * | 开 | 否 | 否 | [BackupGateDecision.PROCEED_WITH_WARNING] |
 *
 * ## 为什么「提交路径」要单独一档（这是判断，不是事实，写在这里以便被推翻）
 *
 * 「提交路径」= 这次写完就会覆盖掉游戏目录里**唯一**的那份原版（`installOneConverted`
 * 直接写游戏目录、`moveDownloadToGame` 搬进游戏缓存）。那里没有第二次机会：
 * 备份失败却继续写，等于把原版永久丢掉。所以失败即中止，代价是这一次装入没做成。
 *
 * 另外两处（转换流程里顺手把下载/复用的基底存一份、还原产物顺手存一份）**不在**这条
 * 路径上：产物本身仍会被完整写进 `Download/Shared`，用户还能手动装；备份失败只是
 * 少了一个「本地秒还原」的便利。为了它中止一次已经跑了几十秒的转换，代价明显大于收益。
 *
 * **这条取舍的代价必须说清**：那两处的用户会看到一条警告，但「卸载时能不能本地还原」
 * 这件事在转换成功的那一刻**没有被保证**。计划落实要求第 3 条原文是「失败即停止该目标
 * 写入」，本实现按「提交路径」收窄了它的适用范围 —— 依据与代价见 `T07.md` §3.5，
 * 若编排者认为该按字面全量中止，改这一处纯函数即可（三个调用点自动跟着变）。
 *
 * @param backupEnabled 用户设置里的自动备份开关（`backup_originals`）。
 *   **关着时返回 [BackupGateDecision.PROCEED]，且调用方不应该去新建备份** ——
 *   这条是防「用户明确关掉的设置被静默改回开启」。
 * @param backupSucceeded 本次备份的结果。开关关着时这个值**不参与判定**（没做备份，
 *   `false` 是「没成功」而不是「失败」），所以传什么都一样。
 * @param onCommitPath 本次写入是否会覆盖游戏目录里唯一的那份原版。
 */
fun decideBackupGate(
    backupEnabled: Boolean,
    backupSucceeded: Boolean,
    onCommitPath: Boolean
): BackupGateDecision = when {
    // 备份关着：不建备份、照常写入。开关的判断必须排在最前面 —— 否则一个「关着开关
    // 且备份结果为 false」的调用会被判成失败，把用户的明确选择变成一次中止。
    !backupEnabled -> BackupGateDecision.PROCEED
    backupSucceeded -> BackupGateDecision.PROCEED
    onCommitPath -> BackupGateDecision.ABORT
    else -> BackupGateDecision.PROCEED_WITH_WARNING
}
