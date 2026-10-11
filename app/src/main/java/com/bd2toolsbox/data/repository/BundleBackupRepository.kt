package com.bd2toolsbox.data.repository

import android.content.Context
import android.util.Log
import com.bd2toolsbox.core.fileaccess.AtomicFileWriter
import com.bd2toolsbox.core.storage.BackupProvenance
import com.bd2toolsbox.core.storage.BaselineSource
import com.bd2toolsbox.core.storage.Digester
import com.bd2toolsbox.core.storage.StreamingDigest
import com.bd2toolsbox.core.storage.TrustedBaseline
import com.bd2toolsbox.core.model.BackupPurge
import com.bd2toolsbox.core.model.PurgeOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.OutputStream

/**
 * 原版 bundle 的本地备份。
 *
 * 安卓装 mod 是就地改写游戏缓存里的 `__data`，游戏自己不会察觉（Unity 判断缓存有效只看
 * 目录名与内容哈希，不校验内容），所以卸载必须显式把原版盖回去。原本唯一的还原途径是从
 * 官方 CDN 重新下载 —— 要流量、要等。
 *
 * 但装入流程里其实已经拿到过权威原版：`tryUseLocalBundle` 会在干净检测判定 PRISTINE 时
 * 从游戏目录取一份原版当重打包基底，走 CDN 那条路拿到的同样是原版。以往这份文件用完就在
 * finally 里删掉，这里只是在删之前另存一份，于是备份几乎是免费的 —— 装哪个备份哪个。
 *
 * 目录结构刻意与游戏缓存保持一致：
 * ```
 * <外部私有目录>/origin_backup/<bundle 名>/<内容哈希>/__data.game
 *          游戏侧            Shared/<bundle 名>/<内容哈希>/__data
 * ```
 * 还原时不需要任何换算，直接 Shizuku copyFile 拷回去即可。
 *
 * 备份放外部私有目录（`/sdcard/Android/data/<包名>/files/`）而不是 `filesDir`：
 * 用户能自己看到和清理，且卸载 app 时会一起消失，不会在内部存储里悄悄堆几百 MB。
 *
 * 哈希目录名带在路径里是有意的 —— 游戏更新后同一个 bundle 的内容哈希会变（实测 176 个
 * bundle 跨版本后哈希 176/176 全变），旧备份对新版本没有意义，靠路径天然区分开，不会误用。
 *
 * ## T09：备份现在带一份来源元数据（sidecar）
 *
 * 上面这套结构有个致命缺口：**磁盘上只有 `__data.game` 一个文件，没有任何关于
 * 「它是不是真的原版」的信息**。旧代码因此只能拿「游戏目录里的文件与备份大小相等」
 * 当原版判据 —— 而备份是我们自己存的，那是**循环证明自己**。
 *
 * 现在每份新备份旁边多一个 `__data.game.meta.json`，记录采集时算出的 `sha256`、
 * 来源枚举、以及**采集那一刻它有没有通过 catalog 校验**。旧备份没有这份 sidecar
 * → [trustedBaseline] 一律返回 null → 判定落 UNKNOWN，**不会**退回「大小相等即可信」。
 */
class BundleBackupRepository(
    private val context: Context,
    private val digester: Digester = StreamingDigest.sha256(),
) {

    companion object {
        private const val DIR_NAME = "origin_backup"

        /**
         * 备份文件名刻意带 `.game` 后缀，用来自证格式。
         *
         * 原版有两种编码：游戏缓存里的 `__data`，和 CDN 下发的压缩包（同一 bundle 实测
         * 26 MB vs 6.2 MB）。只有前者能直接盖回游戏，所以这里只存前者。加后缀有两个好处：
         * 一是格式自描述，将来不会误把压缩包当成能直接拷的备份；二是早期版本曾把 CDN 包
         * 存成 `__data`，换名后那些坏备份自动失效，不必额外写迁移代码。
         */
        private const val DATA_FILENAME = "__data.game"

        /**
         * 来源元数据文件名（T09）。
         *
         * 与备份**同目录同名加后缀**，不是另一个目录 —— 这样 `deleteBackup()` /
         * `clearAll()` 的 `deleteRecursively()` 天然连它一起清掉，不会留下孤儿元数据；
         * 也不会出现「元数据在、备份没了」的半截状态被当成有效基准。
         */
        const val META_SUFFIX = ".meta.json"

        /** sidecar 的结构版本；布局变了就 bump，老 sidecar 自动作废（当没有基准）。 */
        const val META_SCHEMA_VERSION = 1

        /**
         * sidecar 的**磁盘键名**（T09 二轮整改）。
         *
         * ## 这些键名就是磁盘格式的一部分，而且**不许靠反射从字段名推**
         *
         * 首版这里用 `gson.toJson(BackupMeta(...))` 写、`obj.get("schemaVersion")` 读，
         * 并声称「`-keep class com.bd2toolsbox.core.storage.**` 保护了字段名」。
         * **那句话是错的**，而且方向最糟 —— 静默失效：
         *
         * - `BackupMeta` 是 **private 类，位于 `com.bd2toolsbox.data.repository`**，
         *   **不在** `core.storage` 包内，那条包级 `-keep` 护不到它；
         * - release 下 R8 把它的字段名改成了 `a`/`b`/`c`…（本机 R8 产物实测，
         *   见 `docs/refactor/evidence/T09.md` §「release / R8 下的行为」）；
         * - 写侧 Gson 反射取字段名 → 写出 `{"a":…}`；读侧用字面量
         *   `obj.get("schemaVersion")` → **恒 null** → `readMeta` 恒返回 null →
         *   `trustedBaseline()` 恒 null → 扫描层全落 UNKNOWN →
         *   **release 包里「卸载后回到 PRISTINE」完全不工作**，而 debug 下一片绿。
         *
         * ## 修法：两侧都用显式键，**不依赖任何反射字段名**
         *
         * 复核者给了三条修法（加 `@SerializedName` / 挪进 `core.storage` / 加精确 keep），
         * 这里选**显式 JsonObject 读写**，理由是另外三条都依赖「记得回来加规则 / 记得
         * 别再挪出去」这个**易忘前提** —— 而 T09 首版正是栽在「KDoc 声称有保护、
         * 实际没有」上：
         *
         * - `@SerializedName` 仍然依赖「注解本身被 R8 保留」（`-keepattributes` 没配全
         *   就会连注解一起丢），是同一类「看着有保护、实际看配置」的脆弱性；
         * - 挪包 / 加 keep 依赖「下次新增持久化 DTO 时还记得」，而 T08 §345 那条
         *   「包级规则自动覆盖新增 DTO」的假设**恰恰是这次失效的根因**。
         *
         * 显式键没有这个前提：字段名被改成什么、类被挪到哪、注解在不在，
         * **都与磁盘格式无关**。代价是加字段时要手动加一行 —— 由
         * `BundleBackupRepositoryTest.theSidecarOnDiskKeySetIsExactlyTheAgreedContract`
         * 这条**机器检查**兜住（键集合对不上就红），而不是靠注释声明。
         */
        const val KEY_SCHEMA_VERSION = "schemaVersion"
        const val KEY_SHA256 = "sha256"
        const val KEY_SIZE = "size"
        const val KEY_SOURCE = "source"
        const val KEY_CATALOG_VERIFIED = "catalogVerified"
        const val KEY_ANCHORED_INPUT_SIZE = "anchoredInputSize"
        const val KEY_CATALOG_VERSION = "catalogVersion"
        const val KEY_QUALITY = "quality"
        const val KEY_CAPTURED_AT = "capturedAt"

        /**
         * sidecar 的完整键集合 —— **机器检查的基准**。
         *
         * 它就是磁盘合约：写出去的键集合必须**恰好**是这个（多一个少一个都算格式变更，
         * 都该让人回来看一眼 `META_SCHEMA_VERSION` 要不要 bump）。
         */
        val META_KEYS: Set<String> = linkedSetOf(
            KEY_SCHEMA_VERSION, KEY_SHA256, KEY_SIZE, KEY_SOURCE, KEY_CATALOG_VERIFIED,
            KEY_ANCHORED_INPUT_SIZE, KEY_CATALOG_VERSION, KEY_QUALITY, KEY_CAPTURED_AT,
        )
    }

    // T09 二轮整改：这里原先是一个 `private val gson = Gson()`，sidecar 的读写靠它
    // 反射取字段名。改成显式 JSON 键之后不再需要 —— 见 KEY_SCHEMA_VERSION 那组常量的注释。

    private fun root(): File = File(context.getExternalFilesDir(null), DIR_NAME)
    /** 某个 bundle 在指定内容哈希下的备份文件路径（不保证存在）。 */
    fun backupFile(bundleName: String, hashDir: String): File =
        File(root(), "$bundleName/$hashDir/$DATA_FILENAME")

    /** 某个备份的来源元数据路径（不保证存在）。 */
    fun metaFile(bundleName: String, hashDir: String): File =
        File(root(), "$bundleName/$hashDir/$DATA_FILENAME$META_SUFFIX")

    fun hasBackup(bundleName: String, hashDir: String): Boolean =
        backupFile(bundleName, hashDir).let { it.isFile && it.length() > 0 }

    /** 备份的字节数；没有备份返回 -1。 */
    fun backupSize(bundleName: String, hashDir: String): Long =
        backupFile(bundleName, hashDir).let { if (it.isFile) it.length() else -1L }

    /**
     * 把一份原版 `__data` 存为备份。已存在则跳过，不重复占空间。
     *
     * T09 起多两个参数：[provenance] 记录来源，采集时同时算 `sha256` 落进 sidecar。
     *
     * **已有备份时直接返回 `true`，连 sidecar 都不补写** —— 补写等于替一份来源
     * 不明的字节凭空声明来源，那正是本任务要消灭的东西。它保持「没有基准」的状态，
     * 判定落 UNKNOWN。
     *
     * @return true 表示备份可用（本次写入成功，或此前已备份过）
     */
    fun saveBackup(
        bundleName: String,
        hashDir: String,
        source: File,
        provenance: BackupProvenance,
    ): Boolean {
        if (hasBackup(bundleName, hashDir)) return true
        if (!source.isFile || source.length() <= 0) return false
        val dest = backupFile(bundleName, hashDir)
        // 摘要必须在**拷贝之前**在源文件上算，理由有两条：
        // 一是源文件此刻确定存在且非空（上面刚判过），二是拷完再算就要读两遍。
        // 读不了（权限/IO）就整个失败 —— 一份没有摘要的备份是**没有基准**的备份，
        // 存下来只会污染后续判定，不如不存。
        val sha256 = try {
            digester.digest(source)
        } catch (e: Exception) {
            Log.w(TAG, "备份摘要计算失败，放弃本次备份：$bundleName/$hashDir", e)
            return false
        }
        val size = source.length()
        return try {
            dest.parentFile?.mkdirs()
            // 先写临时文件再改名，避免中途失败留下一个残缺备份被当成有效的
            val tmp = File(dest.parentFile, "$DATA_FILENAME.tmp")
            source.inputStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (dest.exists()) dest.delete()
            val ok = tmp.renameTo(dest)
            if (!ok) {
                tmp.delete()
                return false
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "写备份失败：$bundleName/$hashDir", e)
            false
        }.also { ok ->
            // sidecar 在备份**落盘成功之后**才写。反过来的话，写 sidecar 成功而备份失败
            // 会留下「元数据在、备份没了」的状态 —— 那种状态骗得过 trustedBaseline()。
            if (ok) {
                writeMeta(bundleName, hashDir, sha256, size, provenance)
            }
        }
    }

    /**
     * 这份备份能不能当**可信基准**用；不能则返回 null。
     *
     * ## 判据（T09 的核心）
     *
     * 1. 备份文件与 sidecar 都在、都读得出来
     * 2. sidecar 的 `schemaVersion` 认识、`source` 是认得的枚举
     * 3. `catalogVerified == true` —— **采集那一刻它确实被外部锚点钉住**（见
     *    [deriveCatalogVerified]，那是唯一一处推导）
     *
     * 任何一条不满足都返回 null。**旧备份（没有 sidecar）永远走这条返回 null**，
     * 于是判定落 UNKNOWN，而不是退回「大小相等即可信」。
     *
     * ## 调用方还要再看一眼 `source`
     *
     * 本方法**不**替你筛来源强度 —— [TrustedBaseline.source] 原样带出去。
     * 扫描层（只有 name+size 可用）必须只认 [BaselineSource.BYTE_ANCHORED]，
     * 因为 [BaselineSource.GAME_DIR] 的锚点本身就只是 name+size。见
     * `PristineVerifier.classifyListing` 第 4 条。
     */
    fun trustedBaseline(bundleName: String, hashDir: String): TrustedBaseline? {
        val backup = backupFile(bundleName, hashDir)
        if (!backup.isFile || backup.length() <= 0) return null
        val meta = readMeta(bundleName, hashDir) ?: return null
        if (meta.catalogVerified != true) return null
        val source = BaselineSource.fromWire(meta.source) ?: return null
        val sha = meta.sha256
        if (sha.isNullOrBlank()) return null
        return TrustedBaseline(
            sha256 = sha,
            // sidecar 里没记尺寸（老版本或异常写入，字段默认 0）时退回文件实际长度 ——
            // 尺寸只用于扫描层的弱比对，不该因为它缺了就把整份基准作废。
            size = if (meta.size > 0) meta.size else backup.length(),
            source = source,
            expectedHashDir = hashDir,
        )
    }

    /**
     * 扫描层专用：只认**字节级锚定**的来源（[BaselineSource.BYTE_ANCHORED]）。
     *
     * 与 [trustedBaseline] 的差别是**只用 name+size 的场合该不该信它**。
     * `GAME_DIR` 的锚点在扫描层没有任何增量信息（扫描层能看到的也就是 name+size），
     * 认它等于把「采集时 name+size 对得上」当成原版证明 —— 而 catalog 内实测存在
     * 21 组「同尺寸、不同 `m_Hash`」的 bundle，尺寸根本证明不了内容。
     *
     * 所以扫描层只认两种真被钉住字节的来源：
     * - [BaselineSource.CATALOG_DOWNLOAD] —— 从官方 CDN 下的，字节数与官方一致
     * - [BaselineSource.RESTORE_PRODUCT] —— 由上面那种确定性转换来的（走
     *   `restore_bundle` 的卸载路径就是它）
     *
     * 这一档**正是**卸载/还原场景要的：`restore_bundle` 产物与官方差千分之几，
     * 通不过「大小等于官方」那条判据，但它是从已锚定的 CDN 字节确定性派生的，
     * 所以卸载完状态能正确回到 PRISTINE —— 而**不**需要旧那条「备份大小即原版」。
     */
    fun byteAnchoredBaseline(bundleName: String, hashDir: String): TrustedBaseline? =
        trustedBaseline(bundleName, hashDir)
            ?.takeIf { it.source in BaselineSource.BYTE_ANCHORED }

    /** 已备份的 bundle -> 内容哈希。用于卸载时判断哪些能本地秒还原。 */
    fun listBackups(): Map<String, String> {
        val out = HashMap<String, String>()
        root().listFiles()?.forEach { bundleDir ->
            if (!bundleDir.isDirectory) return@forEach
            bundleDir.listFiles()?.forEach { hashDir ->
                if (hashDir.isDirectory && File(hashDir, DATA_FILENAME).isFile) {
                    out[bundleDir.name] = hashDir.name
                }
            }
        }
        return out
    }

    fun count(): Int = listBackups().size

    fun totalBytes(): Long {
        var sum = 0L
        root().listFiles()?.forEach { bundleDir ->
            bundleDir.listFiles()?.forEach { hashDir ->
                File(hashDir, DATA_FILENAME).takeIf { it.isFile }?.let { sum += it.length() }
            }
        }
        return sum
    }

    /** 删除某个 bundle 的全部备份（含各历史哈希与 sidecar）。 */
    fun deleteBackup(bundleName: String) {
        File(root(), bundleName).deleteRecursively()
    }

    fun clearAll() {
        root().deleteRecursively()
    }

    /**
     * 逐 bundle 删除并**重新统计**真实结果（T44）。
     *
     * 为什么不用 [clearAll] 的裸 `deleteRecursively()`：那会「不管成没成都说清空了」——
     * 删一半失败时调用方拿不到任何信号，界面直接把数量/空间置 0，用户以为清干净了。
     *
     * 这里逐 bundle 删、失败单独计数，剩余量与剩余字节都用**重新统计**的值，
     * 不把未删成功的项算成 0。
     */
    fun clearAllTracked(): PurgeOutcome {
        val targets = listBackups().keys.sorted()
        var deleted = 0
        for (bundle in targets) {
            val dir = File(root(), bundle)
            try {
                if (dir.deleteRecursively()) deleted++
            } catch (e: Exception) {
                Log.w(TAG, "删除备份失败：$bundle", e)
            }
        }
        return BackupPurge.outcome(
            attempted = targets.size,
            deleted = deleted,
            remainingCount = count(),
            remainingBytes = totalBytes()
        )
    }

    /** 当前目标集合的指纹（T44 的确认/复核用）。 */
    fun fingerprint(): String = BackupPurge.fingerprint(listBackups())

    // ---------------------------------------------------------------- sidecar 读写

    /**
     * sidecar 的**内存形态**（不是被 Gson 反射序列化的 DTO）。
     *
     * 它只是读写两段显式 JSON 代码之间的一个搬运结构 —— 字段名**不参与磁盘格式**
     * （磁盘键名见 [KEY_SCHEMA_VERSION] 那一组常量）。所以 R8 把它改名成
     * `a`/`b`/`c` 也无所谓：两边都不看字段名。
     */
    private data class BackupMeta(
        val schemaVersion: Int = META_SCHEMA_VERSION,
        val sha256: String = "",
        val size: Long = 0,
        val source: String = "",
        val catalogVerified: Boolean = false,
        val anchoredInputSize: Long? = null,
        val catalogVersion: String? = null,
        val quality: String? = null,
        val capturedAt: Long = 0,
    )

    /**
     * 由 [BackupProvenance] 推导 `catalogVerified` —— 「凭什么说这份字节可信」。
     *
     * 拆成一个纯函数（而不是在 [writeMeta] 里内联）是为了让它**可被单测直接驱动**：
     * 这是整条可信链上唯一一处「凭空断言」可能溜进来的地方，必须有独立用例守着。
     *
     * 逐来源的规则与理由见 [BackupProvenance] 的注释。要点：
     * - [BaselineSource.CATALOG_DOWNLOAD] / [BaselineSource.GAME_DIR]：锚点是
     *   「catalog 登记的 `m_BundleSize` 等于备份产物本身的字节数」。
     * - [BaselineSource.RESTORE_PRODUCT]：产物是 lz4 重打包，**字节数与官方永远不等**
     *   （实测 1168 → 1162），所以这里比的是**输入**（[BackupProvenance.anchoredInputSize]
     *   等于 catalog 登记值）。这正是「输入已锚定 + 转换确定性 → 产物可信」这条派生链。
     * - 三种都要求 `catalogSize` 非空：**拿不到 catalog 就没有锚点**，不许猜。
     */
    internal fun deriveCatalogVerified(
        provenance: BackupProvenance,
        size: Long,
    ): Boolean {
        val catalogSize = provenance.catalogSize ?: return false
        return when (provenance.source) {
            BaselineSource.CATALOG_DOWNLOAD,
            BaselineSource.GAME_DIR -> catalogSize == size

            BaselineSource.RESTORE_PRODUCT ->
                provenance.anchoredInputSize != null && provenance.anchoredInputSize == catalogSize

            // 清单来源**不是**备份的来源 —— 它描述的是 catalog 本身，没有具体字节
            // 可锚定。真有人拿它来存备份，只能判「不可信」。
            BaselineSource.CATALOG_MANIFEST -> false
        }
    }

    /**
     * 写 sidecar。用 T07 的 [AtomicFileWriter]（**不**再造一份原子写）。
     *
     * 写失败只记日志、不算备份失败：备份字节已经在盘上了，卸载照样能本地还原；
     * 丢的只是「它可信」这条证据 —— 那种情况下判定退回 UNKNOWN，是安全的一侧。
     * 反过来说，**绝不能**因为 sidecar 写成功就说备份可信。
     */
    private fun writeMeta(
        bundleName: String,
        hashDir: String,
        sha256: String,
        size: Long,
        provenance: BackupProvenance,
    ) {
        val meta = BackupMeta(
            schemaVersion = META_SCHEMA_VERSION,
            sha256 = sha256,
            size = size,
            source = provenance.source.wireName,
            catalogVerified = deriveCatalogVerified(provenance, size),
            anchoredInputSize = provenance.anchoredInputSize,
            catalogVersion = provenance.catalogVersion,
            quality = provenance.quality,
            capturedAt = System.currentTimeMillis(),
        )
        try {
            AtomicFileWriter().write(metaFile(bundleName, hashDir)) { out: OutputStream ->
                out.write(toJson(meta).toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            Log.w(TAG, "来源元数据写入失败（判定将退回 UNKNOWN）：$bundleName/$hashDir", e)
        }
    }

    /**
     * [BackupMeta] → JSON，**键名全部取自 [KEY_SCHEMA_VERSION] 那一组常量**。
     *
     * 刻意手写而不是 `gson.toJson(meta)`：后者靠反射取字段名，R8 改名后写出的就是
     * `{"a":…}`（T09 首版的真缺陷，见那组常量的注释）。这里键名是编译期常量，
     * **与字段名无关**。
     *
     * `null` 的字段仍然写出来（`JsonNull`）而不是省略 —— 键集合因此永远等于
     * [META_KEYS]，机器检查与读侧都能依赖「键一定在」。读侧把 `JsonNull` 当 null。
     */
    private fun toJson(meta: BackupMeta): String = buildJsonObject {
        put(KEY_SCHEMA_VERSION, meta.schemaVersion)
        put(KEY_SHA256, meta.sha256)
        put(KEY_SIZE, meta.size)
        put(KEY_SOURCE, meta.source)
        put(KEY_CATALOG_VERIFIED, meta.catalogVerified)
        put(KEY_ANCHORED_INPUT_SIZE, meta.anchoredInputSize)
        put(KEY_CATALOG_VERSION, meta.catalogVersion)
        put(KEY_QUALITY, meta.quality)
        put(KEY_CAPTURED_AT, meta.capturedAt)
    }.toString()

    /**
     * 读 sidecar。**任何异常都返回 null** —— 读不出来的元数据等于没有元数据，
     * 于是判定落 UNKNOWN。这里不抛：一条坏 sidecar 不该把整轮干净检测带崩。
     *
     * 键名同样全部取自常量，**不用 `gson.fromJson(obj, BackupMeta::class.java)`** ——
     * 理由与 [toJson] 相同。
     */
    private fun readMeta(bundleName: String, hashDir: String): BackupMeta? {
        val file = metaFile(bundleName, hashDir)
        if (!file.isFile) return null
        return try {
            val root = Json.parseToJsonElement(file.readText(Charsets.UTF_8))
            if (root !is JsonObject) return null
            val obj: JsonObject = root
            val version = (obj[KEY_SCHEMA_VERSION] as? JsonPrimitive)?.takeIf { !it.isString }
                ?.content?.toIntOrNull()
                ?: return null
            if (version != META_SCHEMA_VERSION) return null
            val meta = BackupMeta(
                schemaVersion = version,
                sha256 = obj.str(KEY_SHA256) ?: return null,
                size = obj.long(KEY_SIZE) ?: return null,
                source = obj.str(KEY_SOURCE) ?: return null,
                catalogVerified = obj.bool(KEY_CATALOG_VERIFIED) ?: return null,
                anchoredInputSize = obj.long(KEY_ANCHORED_INPUT_SIZE),
                catalogVersion = obj.str(KEY_CATALOG_VERSION),
                quality = obj.str(KEY_QUALITY),
                capturedAt = obj.long(KEY_CAPTURED_AT) ?: 0L,
            )
            meta.takeIf { it.sha256.isNotBlank() && BaselineSource.fromWire(it.source) != null }
        } catch (e: Exception) {
            Log.w(TAG, "来源元数据不可读（判定将退回 UNKNOWN）：$bundleName/$hashDir", e)
            null
        }
    }

    /** 取字符串成员；缺失或 `null` 字面量都返回 null（**不抛**，由调用方决定要不要作废）。 */
    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** 取长整数成员；缺失、`null` 字面量或不是数字都返回 null。 */
    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()

    /** 取布尔成员；**只认 JSON 布尔**，缺失或别的类型返回 null（不在读侧做类型强转）。 */
    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.let {
            when (it) {
                "true" -> true
                "false" -> false
                else -> null
            }
        }

    private val TAG = "BundleBackupRepository"
}
