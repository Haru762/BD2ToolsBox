package com.bd2toolsbox.core.storage

import java.io.File
import java.security.MessageDigest

/**
 * 「这个 bundle 还是不是官方原版」的判定结果。
 *
 * 取值与 `data/model/ModModels.kt` 的 `BundleCleanState` **逐字对齐** ——
 * `MainViewModel` 据此一一映射，替换判定链时不需要转换表，也就没有
 * 「两个枚举悄悄漂移」的空间。
 */
enum class Verdict {
    /** 与可信基准一致。**注意「一致」的强度取决于基准来源**，见 [TrustedBaseline.source]。 */
    PRISTINE,

    /** 目录名/尺寸对得上某个可信基准，但内容或尺寸与它不符 —— 里面装着别的东西。 */
    MODIFIED,

    /** 落在路径里的内容哈希与 catalog 登记的不一致 —— 游戏更新过，这份基准已经过期。 */
    VERSION_MISMATCH,

    /** 待判定的文件根本不在（或不是普通文件）。 */
    ABSENT,

    /**
     * **无法确认。**
     *
     * 拿不到可信基准、或读不出待判定文件时返回它，**不猜**。调用方应当据此走
     * 「联网取权威原版」这条路，而不是把未知当原版用 —— 计划落实要求第 3 条
     * 明写「不能为减少联网次数而假称已验证原版」。
     */
    UNKNOWN,
}

/**
 * 可信基准的**来源**。每个取值都携带一个**独立于被验证文件自身**的锚点 ——
 * 这是本文件存在的全部理由。
 *
 * ## 为什么需要它（T09 的核心命题）
 *
 * 旧实现里有这么一条判据（`MainViewModel.refreshBundleCleanStates`）：
 *
 * ```kotlin
 * actual.second == backupRepository.backupSize(name, actual.first) -> PRISTINE
 * ```
 *
 * 它拿「游戏目录里那份的大小」比「我们自己存下来的备份的大小」。而备份是我们**自己**
 * 在早先某次操作里、**很可能正是用同一条判据**判成原版之后才存下来的 ——
 * 脏文件被洗白成原版，再拿它当重打包基底，错上加错。这是**循环证明自己**。
 *
 * 破法只有一个：基准必须能追到一个不来自被验证文件、也不来自我们自己产物的来源。
 */
enum class BaselineSource(val wireName: String) {
    /**
     * **官方 catalog 清单**：内容哈希目录名 + 官方字节数（`m_Hash` / `m_BundleSize`）。
     *
     * 这是最弱的一档，而且弱在明处 —— **本任务实测证明 `m_Hash` 不是 `__data` 字节的
     * 内容摘要**（见 `docs/refactor/evidence/T09.md` §「m_Hash 语义」：63 个真实样本、
     * 跨 1 KB–272 MB，md5/sha256 命中数都是 0；目录名只与 catalog 登记值相等）。
     * 所以这个来源**只能证明「游戏把目录命名成了官方清单里的那个哈希、且字节数一致」**，
     * 它**不证明**内容与官方逐字节相同。
     *
     * 保留它是因为：它是既有的、已被用户依赖的快速判据，且锚点在**外部**（官方清单），
     * 不是自证。真正的字节级判定在三个使用点由 [PristineVerifier.classify] 完成。
     */
    CATALOG_MANIFEST("catalog-manifest"),

    /**
     * 从**官方 CDN 下载**的原版字节。
     *
     * 锚点两重：TLS 传输自 `cdn.bd2.pmang.cloud`（无法本地伪造），以及落盘后
     * 字节数与 catalog 登记的 `m_BundleSize` 一致。**这是唯一能在字节层面
     * 锚定的来源。**
     *
     * ## CDN 那份与游戏缓存里的 `__data` 是**同一串字节**（T09 实测，不是推断）
     *
     * 本任务的实测把这件事钉死到 **5/5**（T09.md §「m_Hash 语义」；独立复核者另取
     * 样本扩到 **7/7**，含 8 MB 与 25 MB 两条）。对撞法：把 CDN 下载的 `.bundle`
     * 与设备上 `Shared/<bundle>/<hash>/__data` 各算 sha256，逐条相等：
     *
     * | bundle | 设备 size | CDN 长度 | sha256 相等 |
     * |---|---|---|---|
     * | `3675aee9…` | 1168 | 1168 | **True** |
     * | `db1bc0fe…` | 50738 | 50738 | **True** |
     * | `ce563ad3…` | 500254 | 500254 | **True** |
     * | `817a044b…` | 3037103 | 3037103 | **True** |
     * | `6c62066a…` | 15237355 | 15237355 | **True** |
     *
     * 这一条是整条替代判据链的基石：正因为 CDN 字节 == `__data` 字节，
     * 「从 CDN 下载」才构成一个真正可用的**字节级外部锚点**。
     *
     * ## 关于「CDN 压缩包与游戏缓存编码不同、6.2 MB vs 26 MB」那个旧说法
     *
     * 本仓多处（`app/src/main/python/main_script.py` 的 `restore_bundle` 文档、
     * `MainViewModel` 里 `processSingleJob` 与 `restoreBaseBundle` 的注释、
     * `BundleBackupRepository.DATA_FILENAME`）仍带着这串数字。**它早于 T09、来自
     * 旧版游戏/旧版 catalog**：实测在 T09 能拿到的两份 catalog 与设备目录里
     * **复现不出来** ——
     *
     * - 两份 catalog 里没有任何 bundle 的 HD/SD 尺寸比接近 26/6.2 ≈ 4.19
     *   （最接近的一对是 3.29）；
     * - `6,243,701` 这个值在两份 catalog 里根本不出现；`6,238,441` 只出现一次
     *   （`dde133044094…` 的 HD 条目），它与自己的 SD 条目（2,274,584）之比是 2.74，
     *   凑不出「6.2 vs 26」。
     *
     * 最可能的来源是**跨画质档或跨 catalog 版本的误比**（把 HD 的 `m_BundleSize`
     * 与另一档、或另一个版本的同名 bundle 放在一起看）。**结论：本 KDoc 之前的
     * 说法「编码不同、字节数不同、不能直接比」与 T09 的实测正面冲突，已按实测改正。**
     *
     * 那几处**其它文件**里的同一串数字描述的是另一件事（`restore_bundle` 为什么存在
     * 这个**操作事实**：重打包过的产物与 CDN 原件确实不是同一串字节 —— 实测
     * 1168 → 1162），与「CDN 与 `__data` 是否同字节」不是同一个命题，故本轮未动它们
     * （见 T09.md §11 的范围说明）。
     */
    CATALOG_DOWNLOAD("catalog-download"),

    /**
     * 由**已锚定的官方原版**经**确定性转换**（`restore_bundle`：UnityPy 载入 + lz4 原样
     * 保存）得到的游戏缓存格式字节。
     *
     * 卸载走 CDN 那条路必须用它：转换产物是 lz4 重打包，字节数与官方 catalog 差千分之几
     * （实测 6,243,701 vs 6,238,441），**永远**通不过「大小等于官方」那条判据。
     * 但「输入已锚定 + 转换确定性 + 输出摘要当场记录」把可信**派生**到输出 ——
     * 这是派生，不是自证。
     */
    RESTORE_PRODUCT("restore-product"),

    /**
     * 从**游戏缓存目录**采集下来的字节，采集当时它是 catalog 意义上的 PRISTINE
     * （目录名 = `m_Hash` 且字节数 = `m_BundleSize`）。
     *
     * 这一档的强度**不超过** [CATALOG_MANIFEST] —— 采集那一刻的锚点就是 name+size。
     * 它存在的意义是**变更检测**：一旦记下当时那串摘要，之后文件只要被动过就能被认出来。
     * 它**不构成**「内容是官方原版」的证明。
     */
    GAME_DIR("game-dir");

    companion object {
        /** 认不出的取值返回 null（旧 sidecar、被改坏的文件都走这里）。 */
        fun fromWire(value: String?): BaselineSource? =
            values().firstOrNull { it.wireName == value }

        /**
         * **能当字节级基准用的来源** —— 也就是「有一个外部锚点把具体字节钉住了」的那些。
         *
         * [CATALOG_MANIFEST] 与 [GAME_DIR] 不在其中：前者没有摘要，后者只是
         * 「采集时 name+size 对得上」，两者都**证明不了内容**。
         * 计划验收第 1 条「不得用待验证文件自己的摘要循环证明自己」就落在
         * 这个集合的边界上。
         */
        val BYTE_ANCHORED: Set<BaselineSource> = setOf(CATALOG_DOWNLOAD, RESTORE_PRODUCT)
    }
}

/**
 * 一份可信基准 —— 「这个 bundle 的原版长什么样」的可用证据。
 *
 * @param sha256 基准字节的 SHA-256（小写 hex，64 位）。**null 表示只有清单级锚点
 *   （name+size），没有内容摘要** —— 这时 [PristineVerifier.classify] 只能做到
 *   与 [CATALOG_MANIFEST] 同强度的判定，调用方必须知道这一点。
 * @param size 基准字节数。
 * @param source 来源，见 [BaselineSource]。
 * @param expectedHashDir 该 bundle 的**官方内容哈希**（游戏目录里那一层子目录名）。
 *   给了就顺带做版本判定；null 表示不判。
 */
data class TrustedBaseline(
    val sha256: String?,
    val size: Long,
    val source: BaselineSource,
    val expectedHashDir: String? = null,
) {
    /** 是否够格做字节级比对。 */
    val hasContentDigest: Boolean get() = !sha256.isNullOrBlank()
}

/**
 * 一份备份的**来源声明**，采集时由调用方提供。
 *
 * sidecar 里的 `catalogVerified` **不是**这里某个字段的直抄，而是由
 * [BundleBackupRepository] 按 [source] 逐条推导（规则见那个类的 `deriveCatalogVerified`）。
 * 拆开是为了让「凭什么说它可信」落到具体依据上，而不是一个随手可填的布尔。
 *
 * @param source 来源枚举，写进 sidecar 的 `source` 字段。
 * @param catalogSize 采集当时 catalog 登记的 `m_BundleSize`；null 表示当时拿不到 catalog。
 *   三种来源都用它做锚点，但比的对象不同：前两种比**产物**，[BaselineSource.RESTORE_PRODUCT]
 *   比**输入**（见 [anchoredInputSize]）。
 * @param anchoredInputSize [BaselineSource.RESTORE_PRODUCT] 专用：被外部锚点钉住的
 *   **输入**字节数（也就是那次 CDN 下载的实际大小）。
 *
 *   为什么比输入而不是比对产物算摘要：产物是 lz4 重打包，与官方差千分之几
 *   （T09 实测 1168 → 1162），拿它比 catalog 的 `m_BundleSize` 永远不等 ——
 *   旧代码那条丑判据存在的全部原因。而「输入来自官方 CDN 的 TLS 通道、且大小
 *   等于官方清单登记值」就已经把输入钉死了，转换本身是确定性的（实测两次运行
 *   字节完全相同），可信因此可以**派生**到产物上。记输入大小而不是输入摘要，
 *   是为了避免为了审计再全量读一遍几百 MB 的输入。
 * @param catalogVersion 采集当时的 catalog 版本号，仅供追溯。
 * @param quality 采集时的画质档（HD/SD），仅供追溯。
 */
data class BackupProvenance(
    val source: BaselineSource,
    val catalogSize: Long? = null,
    val anchoredInputSize: Long? = null,
    val catalogVersion: String? = null,
    val quality: String? = null,
)

/**
 * 摘要算法。**做成可注入的构造参数，不是写死的调用** —— 两个理由：
 *
 * 1. 测试要能构造「内容不同但摘要相同」的反例，验证判定链真的按摘要走、
 *    而不是又偷偷退化成比大小。
 * 2. 要能证明**没有循环自证**：如果哪天有人把 `trusted.sha256` 换成
 *    「待验证文件自己的摘要」，注入一个记录调用参数的 digester 就能当场抓住。
 */
fun interface Digester {
    fun digest(file: File): String
}

/** 流式摘要工具。**不把文件读进内存** —— 实测最大的 bundle 是 434,105,568 B（≈ 414 MB）。 */
object StreamingDigest {

    /** 分块大小：够大到摊薄系统调用，又小到与文件大小无关。 */
    const val CHUNK_BYTES = 64 * 1024

    /**
     * 默认 digest：SHA-256，**分块流式**（常数内存）。
     *
     * 为什么不是 MD5：catalog 的 `m_Hash` 是 32 位 hex、与 MD5 同形，但实测
     * **不是** `__data` 字节的 md5（T09.md §「m_Hash 语义」），长度同形不构成证据。
     * 既然对不上任何既有语义，就选没有已知碰撞的 SHA-256。
     */
    fun sha256(): Digester = Digester { file ->
        file.inputStream().use { input -> hex(digestChunks(input)) }
    }

    /** 按 [CHUNK_BYTES] 读取。测试用它确认不会一次索要整份输入。 */
    internal fun digestChunks(input: java.io.InputStream, chunkBytes: Int = CHUNK_BYTES): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(chunkBytes)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            md.update(buffer, 0, read)
        }
        return md.digest()
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

/**
 * 「这份文件还是不是官方原版」的判定器。
 *
 * ## 纯 JVM
 *
 * 本类**不 import 任何 `android.*`**（`core/storage` 整包的约定），只收 `java.io.File`
 * 与可构造的数据，所以 `PristineVerifierTest` 用 `TemporaryFolder` 就能全覆盖，
 * 不需要 Robolectric、也不需要 mockk。
 *
 * ## 它**不**吃 Context / BundleCleanState / ShizukuManager
 *
 * 游戏目录里的 `__data` 这个 app **读不到**（在别的应用的 `Android/data` 下，
 * Android 11+ 只有 Shizuku 那条路能读）。所以本类**不负责**遍历游戏目录 ——
 * 调用方必须把「已经在 app 可读位置的那一份」交给它，能落到这里的实际只有三种：
 * 备份文件、Shizuku 拷进 cache 的副本、转换产物。
 *
 * ## 两个入口，强度不同（这一点必须写清楚）
 *
 * | 入口 | 输入 | 能做到的判定 |
 * |---|---|---|
 * | [classify] | 真实文件 | 有摘要基准时**逐字节**；无摘要时退到 name+size |
 * | [classifyListing] | 目录名 + 尺寸 | **只能** name+size（游戏目录的列举就只有这两样） |
 *
 * 也就是说：**扫描层的 PRISTINE 永远只是「锚点一致」级，字节级判定发生在使用点。**
 * 这是 Android 的读取权限决定的，不是设计上的偷懒。
 */
class PristineVerifier(private val digester: Digester = StreamingDigest.sha256()) {

    /**
     * 字节级判定：读 [onDisk] 的内容，与 [trusted] 比对。
     *
     * ## 规则
     *
     * 1. [onDisk] 不在或不是普通文件 → [Verdict.ABSENT]
     * 2. [trusted] 为 null → **[Verdict.UNKNOWN]**（**不看**旁边有没有本地备份 ——
     *    见下面「为什么没有 `backup` 参数」）
     * 3. [trusted] 给了 `expectedHashDir` 且与 [onDisk] 的父目录名不一致
     *    → [Verdict.VERSION_MISMATCH]（版本判定先于内容判定）
     * 4. [trusted] 没有内容摘要（`sha256 == null`）→ 退到 name+size：
     *    目录名与 `expectedHashDir` 一致且字节数等于 `trusted.size` → PRISTINE，
     *    否则 MODIFIED。**调用方要清楚这是 [BaselineSource.CATALOG_MANIFEST] 级证据。**
     * 5. 有内容摘要：摘要相等 → [Verdict.PRISTINE]，否则 [Verdict.MODIFIED]
     *
     * ## 为什么**没有** `backup` 参数（T09 二轮整改）
     *
     * 首版这里还收一个 `backup: File?`，KDoc 声称「[trusted] 为 null 时会**额外**与备份
     * 做一次字节比对，调用方据此区分两种『不知道』」。那句话是**假的**，复核者当场证伪：
     *
     * - 返回值被丢弃，判据只看 `trusted`，传不传 `backup` 结论完全一样；
     * - 生产代码**零调用者**（`classifyBackup` 恒传 null，`MainViewModel` 那处走默认值）；
     * - 没有任何断言观察这次比对（结果被丢了，观察不到）。
     *
     * 复核者删掉那一行后（当时 `PristineVerifierTest` 28 条、`BundleBackupRepositoryTest`
     * 11 条）**零红** —— 一行对 414 MB 的 bundle 白读一遍、却「看着在守、实际守不住」的代码，
     * 正是 T07 一轮缺陷的同一形状。
     *
     * **修法是删掉它，不是给它补断言。** 理由：这个参数**在类型上就无法参与判定** ——
     * 它既不能把结论抬成 PRISTINE（那正是要消灭的循环自证），而「区分两种不知道」这件事
     * 需要一个 [Verdict] 之外的返回值才能让调用方看见，那要把 API 与三处 `MainViewModel`
     * 调用点一起改（超出定点整改的范围，且会重新打开一个「要不要信备份」的口子）。
     * 既然没有消费者、又证明不了任何事，留着它只会长期假装在守一道不存在的闸门。
     *
     * 旧备份「拿不到基准 → UNKNOWN」这条**必测反例**并未因此失去守卫，只是换了守的位置：
     * 前半截（旧备份为什么拿不到基准）由 `BundleBackupRepositoryTest.legacyBackupWithoutSidecarHasNoTrustedBaseline`
     * 守，后半截（没有基准就 UNKNOWN）由本类 `noBaselineIsUnknown` 守，组合即结论。
     */
    fun classify(onDisk: File?, trusted: TrustedBaseline?): Verdict {
        if (onDisk == null || !onDisk.isFile) return Verdict.ABSENT

        // 没有外部锚定的基准 → UNKNOWN。这里**不**与任何本地备份比对：
        // 备份是我们自己采集的产物，拿它反证一个文件是原版就是循环证明自己，
        // 旧实现 `actual.second == backupRepository.backupSize(...)` 正是这个形态。
        if (trusted == null) return Verdict.UNKNOWN

        trusted.expectedHashDir?.let { expected ->
            if (onDisk.parentFile?.name != expected) return Verdict.VERSION_MISMATCH
        }

        if (!trusted.hasContentDigest) {
            return if (onDisk.length() == trusted.size) Verdict.UNKNOWN else Verdict.MODIFIED
        }

        val actual = digestOrNull(onDisk) ?: return Verdict.UNKNOWN
        return if (actual == trusted.sha256) Verdict.PRISTINE else Verdict.MODIFIED
    }

    /**
     * 扫描层判定：游戏目录的列举只给出 `(目录名, 尺寸)`，拿不到字节。
     *
     * @param hashDir 游戏目录里的内容哈希目录名，null 表示读不到。
     * @param size `__data` 的字节数；负数表示读不到（Shizuku 侧拿不到 stat）。
     * @param catalog 官方清单锚点（`m_Hash` + `m_BundleSize`），可为 null。
     * @param backup 该 bundle 在**指定 hashDir 下**的**可信**备份基准，可为 null。
     *   **必须**是 [BundleBackupRepository.byteAnchoredBaseline] 的产物
     *   （扫描层只认字节级锚定的来源）—— 调用方不许自己拿备份文件的大小拼一个出来，
     *   那就是本任务要消灭的形态，也**不许**把无 sidecar 的旧备份传进来。
     *
     * ## 规则（顺序即优先级）
     *
     * 1. `size < 0` 或 `hashDir == null` → [Verdict.UNKNOWN]（读不到就不判）
     * 2. `catalog != null && hashDir != catalog.expectedHashDir` → [Verdict.VERSION_MISMATCH]
     * 3. `catalog != null && size == catalog.size` → [Verdict.PRISTINE]（清单锚点）
     * 4. `backup != null && backup.expectedHashDir == hashDir && size == backup.size`
     *    → [Verdict.PRISTINE]（**可信备份锚点**，卸载/还原后走这条）
     * 5. 有任一锚点可用 → [Verdict.MODIFIED]
     * 6. 两个锚点都没有 → [Verdict.UNKNOWN]（**不猜**）
     *
     * ## 第 4 条的诚实说明
     *
     * 它仍然只比了**尺寸**。扫描层没有字节可读，这是能做到的上限。它与旧判据的
     * 区别不在「用不用尺寸」，而在**比的是谁**：
     *
     * - 旧：与 `backupRepository.backupSize(...)` 比 —— 我们自己任意一份备份的原始大小，
     *   没有来源、没有摘要，**来源不明的备份照样能把脏文件洗成 PRISTINE**。
     * - 新：只与 [BundleBackupRepository.byteAnchoredBaseline] 交出来的基准比，
     *   而那要求 sidecar 里的 `source` 是 [BaselineSource.BYTE_ANCHORED] 之一
     *   （官方 CDN 下载 / 由它确定性派生）**且**采集时被 catalog 锚点钉住。
     *   旧备份没有 sidecar → 拿不到基准 → 落进第 6 条 UNKNOWN，**不会**被当成原版。
     *
     * 尺寸级证据的残余风险（一个**恰好同尺寸**的 mod 会被这一步放过）由使用点的
     * 字节级 [classify] 兜住：真正决定「能不能当重打包基底」的地方读得到字节，
     * 那里走的是摘要比对。
     */
    fun classifyListing(
        hashDir: String?,
        size: Long,
        catalog: CatalogAnchor?,
        backup: TrustedBaseline?,
    ): Verdict {
        if (hashDir == null || size < 0) return Verdict.UNKNOWN

        if (catalog != null && hashDir != catalog.expectedHashDir) return Verdict.VERSION_MISMATCH
        if (catalog != null && size == catalog.size) return Verdict.PRISTINE

        // 只有**版本对得上**的备份才算这一份的锚点。属于别的 hashDir 的备份描述的是
        // 另一个版本的 bundle，对本份没有任何信息 —— 不算「有锚点」。
        val usableBackup = backup?.takeIf { it.expectedHashDir == hashDir }
        if (usableBackup != null && size == usableBackup.size) return Verdict.PRISTINE

        return if (catalog != null || usableBackup != null) Verdict.MODIFIED else Verdict.UNKNOWN
    }

    /** 判断「这份**备份**能不能当可信基底用」——[backup] 传备份文件本身即可。 */
    fun classifyBackup(backup: File?, trusted: TrustedBaseline?): Verdict =
        classify(onDisk = backup, trusted = trusted)

    companion object {
        /**
         * `<bundle>/<hashDir>/__data` 里的中间那一段 —— 也就是 catalog 的 `m_Hash`。
         *
         * 游戏缓存的目录结构是 `Shared/<bundle 名>/<内容哈希>/__data`，备份目录刻意
         * 与它同构（见 `BundleBackupRepository` 的类注释），所以两边的「目录名」
         * 都是同一个值，才能直接拿来比。
         */
        fun hashDirOf(file: File): String? = file.parentFile?.name
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 读不出（权限、IO 错误、判定途中文件被删）时返回 null → 上层转 [Verdict.UNKNOWN]。
     * **不抛**：把「读不了」说成 ABSENT/MODIFIED 都是撒谎，说成 UNKNOWN 才是真的。
     */
    private fun digestOrNull(file: File): String? = try {
        digester.digest(file)
    } catch (_: Exception) {
        null
    }
}

/**
 * 官方 catalog 给出的清单锚点。
 *
 * 与 [TrustedBaseline] 分开是因为它**没有摘要** —— 混在一起会让人以为
 * 「catalog 能验证内容」，而 T09 的实测恰恰证明了它不能（`m_Hash` 不是内容摘要）。
 */
data class CatalogAnchor(
    val expectedHashDir: String,
    val size: Long,
) {
    /** 转成清单级 [TrustedBaseline]（无摘要），供字节级入口退到 name+size 用。 */
    fun asTrustedBaseline(): TrustedBaseline =
        TrustedBaseline(
            sha256 = null,
            size = size,
            source = BaselineSource.CATALOG_MANIFEST,
            expectedHashDir = expectedHashDir,
        )
}
