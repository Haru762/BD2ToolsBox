package com.bd2toolsbox.core.storage

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * 一次恢复所需的持久化事实（T08）。
 *
 * ## 为什么单独立此 DTO 而不复用 [LedgerRecord] 之外的模型
 *
 * 恢复要在副作用发生之前冻结「核对通过后账本应有的记录」：目标内容摘要
 * ([expectedContentSha256]) 用来核对实际目标是否与本次暂存内容一致，[ledgerUpserts]
 * 是核对成功后应当补进账本的完整记录。恢复不重放游戏文件覆盖。这些字段的**磁盘格式**必须稳定，所以它是一份
 * 独立、被显式校验的事实，而不是随手序列化的运行期对象。
 *
 * ## null 的含义
 *
 * `recoveryFacts` 缺失或为 JSON null 只表示这是**旧格式记录**——可以照常读，但没有
 * 内容证据，不能据此自动恢复。它不是「已验证为空」，两者绝不能混同。
 *
 * ## 字段名保留（R8）
 *
 * 与 [LedgerRecord] 同理：混淆后字段改名会让老日志读不回来。保留规则见
 * `app/proguard-rules.pro`。
 */
data class JournalRecoveryFacts(
    val formatVersion: Int,
    val expectedContentSha256: String,
    val ledgerUpserts: List<LedgerRecord>
)

/**
 * 恢复事实的**原始 JSON** 严格校验器（T08）。
 *
 * ## 为什么在 Gson 之前校验原始 [JsonObject]
 *
 * Gson 的 DTO 转换会悄悄把缺失字段当默认值（`false`/`0`）、把数字读成字符串、把
 * 字符串读成数字。恢复事实一旦被这类宽松转换掩盖，就会拿着「看起来完整、实际残缺」
 * 的数据去覆写账本——正是本任务要杜绝的静默数据丢失。因此这里只看原始
 * [JsonObject]：类型不对、字段缺失一律判为不可接受，绝不填默认值、绝不做强制转换。
 *
 * [validate] 返回 `null` 表示可接受；返回非空字符串给出**具体字段错误**。
 * 本对象不做任何 I/O、不解除 pending、不执行恢复——只回答「这份事实能不能信」。
 */
internal object JournalRecoveryFactsCodec {

    private const val REQUIRED_FORMAT_VERSION = 1L
    private val SHA256_LOWER_HEX = Regex("^[0-9a-f]{64}$")
    private val INTEGER = Regex("^-?\\d+$")

    /**
     * 校验一个 target 的 JSON。
     *
     * - `recoveryFacts` 缺失或 JSON null：旧格式，直接接受（返回 `null`）。
     * - 存在时必须是对象，且其中每个字段都按下述严格规则通过，否则返回错误说明。
     *
     * 存在恢复事实时，同时严格校验其绑定的 target 字段
     * （bundleName/hashDir/dataBytes/modUris），因为内容核对与补账要靠它们绑定同一目标。
     */
    fun validate(target: JsonObject): String? {
        val factsEl = target.get("recoveryFacts")
        // 缺失或显式 null：旧格式，可读但无内容证据。
        if (factsEl == null || factsEl.isJsonNull) return null
        if (!factsEl.isJsonObject) return "recoveryFacts 必须是 JSON 对象"
        val facts = factsEl.asJsonObject

        // formatVersion 必须是 JSON 整数 1（不接受字符串 "1"、布尔、1.5、其他数值）。
        val formatVersion = asNonNegativeLong(facts.get("formatVersion"))
        if (formatVersion != REQUIRED_FORMAT_VERSION) {
            return "formatVersion 必须是 JSON 整数 1"
        }

        // expectedContentSha256 必须是 64 位小写十六进制字符串。
        val sha = asJsonString(facts.get("expectedContentSha256"))
        if (sha == null || !SHA256_LOWER_HEX.matches(sha)) {
            return "expectedContentSha256 必须是 64 位小写十六进制字符串"
        }

        // 绑定的 target 字段：bundleName / hashDir 必须是安全的单路径段。
        val bundleName = asSafeSegment(target.get("bundleName"))
            ?: return "bundleName 必须是安全的单路径段（非空、非 . 或 ..、不含 / \\ : 或控制字符）"
        if (asSafeSegment(target.get("hashDir")) == null) {
            return "hashDir 必须是安全的单路径段（非空、非 . 或 ..、不含 / \\ : 或控制字符）"
        }

        // dataBytes 必须是显式非负 JSON 整数 Long（不接受字符串、小数、溢出）。
        val dataBytes = asNonNegativeLong(target.get("dataBytes"))
        if (dataBytes == null) {
            return "dataBytes 必须是非负 JSON 整数"
        }

        // modUris 必须是非空数组，元素为非空白字符串且唯一。
        val modUrisEl = target.get("modUris")
        if (modUrisEl == null || !modUrisEl.isJsonArray) return "modUris 必须是 JSON 数组"
        val modUrisArr = modUrisEl.asJsonArray
        if (modUrisArr.size() == 0) return "modUris 不能为空数组"
        val modUriSet = LinkedHashSet<String>()
        for (e in modUrisArr) {
            val uri = asNonBlankString(e) ?: return "modUris 元素必须是非空字符串"
            if (!modUriSet.add(uri)) return "modUris 不能有重复项"
        }

        // ledgerUpserts 必须是非空数组，且每条 7 个字段全部按严格类型通过。
        val upsertsEl = facts.get("ledgerUpserts")
        if (upsertsEl == null || !upsertsEl.isJsonArray) return "ledgerUpserts 必须是 JSON 数组"
        val upsertsArr = upsertsEl.asJsonArray
        if (upsertsArr.size() == 0) return "ledgerUpserts 不能为空数组"

        val upsertUriSet = LinkedHashSet<String>()
        for (e in upsertsArr) {
            if (!e.isJsonObject) return "ledgerUpserts 元素必须是 JSON 对象"
            val rec = e.asJsonObject

            val modUri = asNonBlankString(rec.get("modUri"))
                ?: return "ledgerUpserts.modUri 必须是非空字符串"
            if (asNonBlankString(rec.get("modName")) == null) {
                return "ledgerUpserts.modName 必须是非空字符串"
            }
            if (asNonBlankString(rec.get("familyKey")) == null) {
                return "ledgerUpserts.familyKey 必须是非空字符串"
            }
            val snapshotTargetHash = asNonBlankString(rec.get("snapshotTargetHash"))
                ?: return "ledgerUpserts.snapshotTargetHash 必须是非空字符串"
            if (asNonBlankString(rec.get("quality")) == null) {
                return "ledgerUpserts.quality 必须是非空字符串"
            }
            if (!isJsonBoolean(rec.get("usedAstc"))) {
                return "ledgerUpserts.usedAstc 必须是 JSON 布尔"
            }
            if (asNonNegativeLong(rec.get("installedAt")) == null) {
                return "ledgerUpserts.installedAt 必须是非负 JSON 整数"
            }

            // 每条 upsert 必须精确指向本 target 的 bundle。
            if (snapshotTargetHash != bundleName) {
                return "ledgerUpserts.snapshotTargetHash 必须等于 bundleName"
            }
            if (!upsertUriSet.add(modUri)) {
                return "ledgerUpserts 的 modUri 不能重复"
            }
        }

        // upsert 的 URI 集合必须与 modUris 完全一致（不多不少）。
        if (upsertUriSet != modUriSet) {
            return "ledgerUpserts 的 URI 集合必须与 modUris 完全一致"
        }

        return null
    }

    /**
     * 只在 [el] 是 JSON 字符串时返回其值，否则 `null`。不做数字/布尔到字符串的强制转换。
     */
    private fun asJsonString(el: JsonElement?): String? {
        if (el == null || !el.isJsonPrimitive) return null
        val p = el.asJsonPrimitive
        if (!p.isString) return null
        return p.asString
    }

    /**
     * 只在 [el] 是**非空白** JSON 字符串时返回其值，否则 `null`。
     */
    private fun asNonBlankString(el: JsonElement?): String? {
        val s = asJsonString(el) ?: return null
        return if (s.isBlank()) null else s
    }

    /**
     * 单路径段安全校验：必须是非空白字符串，且非 `.` / `..`，不含 `/` `\` `:` 或控制字符。
     * 通过返回原值，否则 `null`。
     */
    private fun asSafeSegment(el: JsonElement?): String? {
        val s = asNonBlankString(el) ?: return null
        if (s == "." || s == "..") return null
        if (s.any { it == '/' || it == '\\' || it == ':' || it.isISOControl() }) return null
        return s
    }

    /**
     * 只在 [el] 是 JSON 布尔时返回 `true`，否则 `false`。不接受字符串 "true"/"false"。
     */
    private fun isJsonBoolean(el: JsonElement?): Boolean {
        if (el == null || !el.isJsonPrimitive) return false
        return el.asJsonPrimitive.isBoolean
    }

    /**
     * 只在 [el] 是**显式非负整数** JSON 数值且能装进 Long 时返回其值，否则 `null`。
     *
     * 严格拒绝：字符串（含数字字符串 "4"）、布尔、小数（1.5）、负数、超出 Long 范围的溢出值。
     * 通过读取原始文本并用整数正则匹配，避免 Gson 的 `LazilyParsedNumber` 把 1.5 截成 1。
     */
    private fun asNonNegativeLong(el: JsonElement?): Long? {
        if (el == null || !el.isJsonPrimitive) return null
        val p = el.asJsonPrimitive
        if (!p.isNumber) return null
        val raw = p.asString
        if (!INTEGER.matches(raw)) return null
        val value = raw.toLongOrNull() ?: return null
        return if (value < 0) null else value
    }
}
