package com.bd2toolsbox.data.repository

import android.util.Log
import com.bd2toolsbox.data.model.CdkItem
import com.bd2toolsbox.data.model.RedeemOutcome
import com.bd2toolsbox.data.model.RedeemRecord
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 棕尘2官方兑换码接口（官方兑换页 redeem.bd2.pmang.cloud 背后的 AWS 网关）。
 *
 * 官方兑换页是个极简 Preact 表单：游戏昵称 + 兑换码，两个输入框直接 POST 到
 * 网关，没有登录、验证码和会话，App 里可以完全原生复刻。接口行为（2026-09
 * 从官方页面的 settings.js/翻译表与假数据实测确认）：
 *
 *   POST ENDPOINT {"appId":"bd2-live","userId":"<游戏昵称>","code":"<兑换码>"}
 *
 * - appId 全区只有一个 `bd2-live`：网页商店的 /CT/、/KR/ 等前缀只是界面语言，
 *   兑换后端不分服（bd2-kr-live 之类的变体会被 appId 白名单挡掉）
 * - Origin/Referer 服务端其实不校验，带上只是为了贴近官方页面的请求样子
 *   （社区报告里「缺 Origin 会 400」实为撞上限流的误判，见下条）
 * - `userId` 是游戏内昵称（官方文案五种语言均为 Nickname/닉네임/ニックネーム/
 *   暱稱/昵称），对不上返回 IncorrectUser。实测纯数字 5-12 位也能通过用户
 *   校验（疑似 UID 兼容路径），但官方表单从不收 UID，按昵称实现
 * - 业务错误时 HTTP 也是 200，错误码在 body 的 error / errorCode 字段，完整
 *   枚举与文案见 [errorText]（比社区实现多出 ValidationFailed / ExceededUses /
 *   ClaimRewardsFailed 三个）
 * - 限流是「软」的：同 IP 快速打约 25 发后，合法请求也会统一回 BadRequest
 *   "Failed for unknown reason"，静置 5-8 分钟才恢复（没有 429）。批量兑换
 *   逐条串行留间隔（社区工具按 ≤2.5 QPS 限速），并把泛化 BadRequest 当限流
 *   提示处理，不当业务错误
 *
 * 官方没有「查询兑换历史」的端点，成功记录由 App 记在 filesDir 的 JSON 里。
 * 沿用全项目惯例：HttpURLConnection + Gson，不引网络库。
 */
class Bd2RedeemRepository private constructor(private val appContext: android.content.Context) {

    companion object {
        private const val TAG = "Bd2RedeemRepository"

        /** 官方兑换网关。兑换页 settings.js 里写死的端点，社区项目（kingko 插件、
         *  BD2Pulse）直连的也是它。 */
        private const val ENDPOINT =
            "https://loj2urwaua.execute-api.ap-northeast-1.amazonaws.com/prod/coupon"

        /** 全区唯一的 appId。 */
        private const val APP_ID = "bd2-live"

        /** 来源页伪装。服务端不校验，带上让请求长得像官方页面发的。 */
        private const val ORIGIN = "https://redeem.bd2.pmang.cloud"

        private const val UA = "Mozilla/5.0 (Linux; Android) BD2ToolsBox"

        /** 批量兑换的条间隔，压着限流阈值走。 */
        private const val BATCH_INTERVAL_MS = 600L

        /** 本地成功记录的上限，超出丢最旧的。多账号共用一份，按 500 给足。 */
        private const val HISTORY_CAP = 500

        /**
         * 终局错误：再提交结果也一样，自动兑换不再重试（成功同样算终局）。
         * BadRequest 不在此列——限流与偶发拒绝都走它，当可重试处理。
         */
        private val TERMINAL_ERRORS = setOf(
            "ValidationFailed", "InvalidCode", "ExpiredCode", "AlreadyUsed",
            "ExceededUses", "UnavailableCode", "IncorrectUser"
        )

        @Volatile
        private var instance: Bd2RedeemRepository? = null

        fun get(context: android.content.Context): Bd2RedeemRepository =
            instance ?: synchronized(this) {
                instance ?: Bd2RedeemRepository(context.applicationContext).also { instance = it }
            }
    }

    // ---------------------------------------------------------------- 账号与记录（本地）

    private val prefs by lazy {
        appContext.getSharedPreferences("bd2_redeem", android.content.Context.MODE_PRIVATE)
    }

    /**
     * 已存的游戏昵称（可多个，按添加先后）。字段名随官方 API 叫 user_id，含义是
     * 昵称。0.2.1 之前只存过一个（prefs 的 user_id 键），首次读取时迁移成列表，
     * 并把旧兑换记录的空账号字段补成那个昵称。
     */
    fun savedUserIds(): List<String> {
        if (!prefs.getBoolean("user_migrated", false)) {
            // 首次升级：单账号老数据并进列表、旧记录回填归属。两步都幂等，中途被
            // 打断（进程被杀 / IO 失败）下次进来接着续跑，标记只在最后置位。
            val legacy = prefs.getString("user_id", "").orEmpty().trim()
            if (legacy.isNotEmpty()) {
                writeUserIds((readUserIds() + legacy).distinct())
                rewriteLegacyHistory(legacy)
            }
            prefs.edit().putBoolean("user_migrated", true).remove("user_id").apply()
        }
        return readUserIds()
    }

    private fun readUserIds(): List<String> = try {
        JsonParser.parseString(prefs.getString("user_ids", "[]"))
            .takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { el -> el.takeIf { it.isJsonPrimitive }?.asString }
            .orEmpty()
    } catch (e: Exception) {
        Log.w(TAG, "账号列表读取失败", e)
        emptyList()
    }

    /** 添加账号；空白或重复返回 false、不动存储。 */
    fun addUserId(userId: String): Boolean {
        val id = userId.trim()
        if (id.isEmpty()) return false
        val cur = savedUserIds()
        if (id in cur) return false
        writeUserIds(cur + id)
        return true
    }

    /** 删账号：顺带清掉它的自动兑换清单（兑换记录保留，那是历史事实）。持批量锁，
     *  等正在跑的兑换轮结束后再删——「删了才停」不用等下一轮。 */
    suspend fun removeUserId(userId: String) = withContext(Dispatchers.IO) {
        batchMutex.withLock {
            writeUserIds(readUserIds().filterNot { it == userId })
            try {
                if (autoDoneFile.exists()) {
                    val root = JsonParser.parseString(autoDoneFile.readText())
                        .takeIf { it.isJsonObject }?.asJsonObject ?: return@withLock
                    if (root.remove(userId) != null) autoDoneFile.writeText(root.toString())
                }
            } catch (e: Exception) {
                Log.w(TAG, "自动兑换清单清理失败", e)
            }
        }
    }

    private fun writeUserIds(list: List<String>) {
        prefs.edit()
            .putString("user_ids", JsonArray().apply { list.forEach(::add) }.toString())
            .apply()
    }

    /** 旧记录（没有账号字段）全部归到迁移前的唯一昵称名下。 */
    private fun rewriteLegacyHistory(legacyId: String) {
        try {
            if (!historyFile.exists()) return
            val list = loadHistory()
            if (list.none { it.userId.isBlank() }) return
            writeHistory(list.map { if (it.userId.isBlank()) it.copy(userId = legacyId) else it })
        } catch (e: Exception) {
            Log.w(TAG, "旧记录迁移失败", e)
        }
    }

    private val historyFile: File get() = File(appContext.filesDir, "redeem_history.json")

    /** 本地成功记录，新 → 旧。就是个几 KB 的本地文件，直接读即可。 */
    fun loadHistory(): List<RedeemRecord> = try {
        if (!historyFile.exists()) emptyList()
        else JsonParser.parseString(historyFile.readText())
            .takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                val o = el.asJsonObject
                RedeemRecord(
                    code = o.str("code") ?: return@mapNotNull null,
                    reward = o.str("reward").orEmpty(),
                    at = o.get("at")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
                    userId = o.str("user_id").orEmpty()
                )
            }.orEmpty()
    } catch (e: Exception) {
        Log.w(TAG, "兑换记录读取失败", e)
        emptyList()
    }

    /** 某个账号成功兑换过的码（自动兑换的跳过依据之一）。 */
    fun historyCodes(userId: String): Set<String> =
        loadHistory().filter { it.userId == userId }.map { it.code }.toSet()

    private fun appendHistory(record: RedeemRecord) {
        try {
            // 同码同账号只留最新一条；新记录放最前
            val list = mutableListOf(record) +
                loadHistory().filterNot { it.code == record.code && it.userId == record.userId }
            writeHistory(list.take(HISTORY_CAP))
        } catch (e: Exception) {
            Log.w(TAG, "兑换记录写入失败", e)
        }
    }

    private fun writeHistory(list: List<RedeemRecord>) {
        val arr = JsonArray()
        list.forEach { r ->
            arr.add(JsonObject().apply {
                addProperty("code", r.code)
                addProperty("reward", r.reward)
                addProperty("at", r.at)
                addProperty("user_id", r.userId)
            })
        }
        historyFile.writeText(arr.toString())
    }

    // ---------------------------------------------------------------- 自动兑换清单

    private val autoDoneFile: File get() = File(appContext.filesDir, "auto_redeem_done.json")

    /**
     * 该账号「已处理完」的码：成功，或终局失败（过期/无效/已兑换……再提交结果也
     * 一样）。自动兑换据此跳过，不会每次拉清单都拿同样的码去撞官方接口。网络类
     * 失败不进这份清单，下次拉到清单自然重试。
     */
    fun autoDoneCodes(userId: String): Set<String> = try {
        if (!autoDoneFile.exists()) emptySet()
        else JsonParser.parseString(autoDoneFile.readText())
            .takeIf { it.isJsonObject }?.asJsonObject
            ?.get(userId)?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { el -> el.takeIf { it.isJsonPrimitive }?.asString }
            ?.toSet() ?: emptySet()
    } catch (e: Exception) {
        Log.w(TAG, "自动兑换清单读取失败", e)
        emptySet()
    }

    private fun markAutoDone(userId: String, codes: Collection<String>) {
        if (codes.isEmpty()) return
        try {
            // 读不动的坏文件按空表重建自愈——否则坏文件会让 autoDoneCodes 永远返回
            // 空集、自动兑换每轮全量重发，卡死在原地。
            val root = readJsonObjectOrNew(autoDoneFile)
            val merged = (root.get(userId)?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { el -> el.takeIf { it.isJsonPrimitive }?.asString }
                ?: emptyList()) + codes
            root.add(userId, JsonArray().apply { merged.distinct().forEach(::add) })
            autoDoneFile.writeText(root.toString())
        } catch (e: Exception) {
            Log.w(TAG, "自动兑换清单写入失败", e)
        }
    }

    /** 读一个应为 JSON object 的本地文件；不存在、损坏、类型不对都返回新空对象。 */
    private fun readJsonObjectOrNew(file: File): JsonObject = try {
        if (file.exists())
            JsonParser.parseString(file.readText()).takeIf { it.isJsonObject }?.asJsonObject
                ?: JsonObject()
        else JsonObject()
    } catch (e: Exception) {
        Log.w(TAG, "本地清单损坏，按空表重建: ${file.name}", e)
        JsonObject()
    }

    // ---------------------------------------------------------------- 兑换

    /**
     * 兑换一个码。网络与服务端错误都折算成 success=false 的结果（UI 直接展示
     * message），不抛异常；只有成功才写本地记录。
     */
    suspend fun redeem(userId: String, code: String, reward: String = ""): RedeemOutcome =
        withContext(Dispatchers.IO) {
            // 官方页输入时自动去空格、转大写，照做
            val normalized = code.trim().uppercase()
            val body = JsonObject().apply {
                addProperty("appId", APP_ID)
                addProperty("userId", userId.trim())
                addProperty("code", normalized)
            }.toString()

            val resp = httpPost(body)
                ?: return@withContext RedeemOutcome(
                    normalized, false, "网络错误，请求没能发出，请检查网络后重试", retryable = true
                )
            when {
                resp.status == 429 -> RedeemOutcome(
                    normalized, false, "操作太频繁，被官方限流了，稍等一会儿再试",
                    retryable = true, rateLimited = true
                )
                resp.body == null -> RedeemOutcome(
                    normalized, false, "官方服务异常（HTTP ${resp.status}），稍后再试", retryable = true
                )
                else -> parseOutcome(normalized, userId.trim(), resp.body, reward)
            }
        }

    /**
     * 批量入口互斥：自动兑换和手动单码兑换并发时，跳过检查与记账都是读改写，
     * 不锁就会同（账号×码）双发、后写覆盖先写。锁内串行顺带保证 600ms 间隔不被
     * 两股流交错稀释。
     */
    private val batchMutex = Mutex()

    /**
     * 自动兑换：对每个已存账号，把清单里未过期、且该账号还没处理完的码逐条兑换。
     * 终局结果（成功 / 确定性失败）记账，下次不再重复提交；网络类失败不记账，下次
     * 拉到清单自然重试。返回这次真正提交过的（账号, 结果）——没有账号或全处理完
     * 时为空，调用方据此决定要不要提示。连续撞官方限流就提前收工，别把恢复窗口
     * 越推越晚。
     */
    suspend fun autoRedeem(items: List<CdkItem>): List<Pair<String, RedeemOutcome>> =
        withContext(Dispatchers.IO) {
            batchMutex.withLock {
                val accounts = savedUserIds()
                if (accounts.isEmpty()) return@withLock emptyList()
                val results = mutableListOf<Pair<String, RedeemOutcome>>()
                var firstEver = true
                var rateLimitHits = 0
                outer@ for (userId in accounts) {
                    val done = autoDoneCodes(userId) + historyCodes(userId)
                    val todo = items.filterNot { it.expired || it.code in done }
                        .distinctBy { it.code }
                    for (item in todo) {
                        if (!firstEver) delay(BATCH_INTERVAL_MS)
                        firstEver = false
                        val outcome = submitAndSettle(userId, item)
                        results += userId to outcome
                        // 昵称对不上是账号级终局：这个账号剩下的码全是同样的空转，
                        // 直接跳到下一个账号（已提交的那条照常记账）。
                        if (outcome.errorCode == "IncorrectUser") break
                        if (outcome.rateLimited && ++rateLimitHits >= 3) break@outer
                        if (!outcome.rateLimited) rateLimitHits = 0
                    }
                }
                results
            }
        }

    /**
     * 单码多账号（列表行上的手动「兑换」）：每个还没处理完该码的账号各提交一次。
     * 行级按钮只对「还有账号没处理完」的码出现，跳过口径与自动兑换一致（成功 ∪
     * 终局失败），处理完的码不再出现任何兑换入口。
     */
    suspend fun redeemCodeForAll(code: String, reward: String): List<Pair<String, RedeemOutcome>> =
        withContext(Dispatchers.IO) {
            batchMutex.withLock {
                val accounts = savedUserIds()
                if (accounts.isEmpty()) return@withLock emptyList()
                val results = mutableListOf<Pair<String, RedeemOutcome>>()
                var firstEver = true
                var rateLimitHits = 0
                outer@ for (userId in accounts) {
                    if (code in autoDoneCodes(userId) || code in historyCodes(userId)) continue
                    if (!firstEver) delay(BATCH_INTERVAL_MS)
                    firstEver = false
                    val outcome = submitAndSettle(userId, CdkItem(code, reward, 0L, false))
                    results += userId to outcome
                    if (outcome.rateLimited && ++rateLimitHits >= 3) break@outer
                    if (!outcome.rateLimited) rateLimitHits = 0
                }
                results
            }
        }

    /** 单发一次并把终局结果记入该账号的清单。 */
    private suspend fun submitAndSettle(userId: String, item: CdkItem): RedeemOutcome {
        val outcome = redeem(userId, item.code, item.reward)
        if (outcome.success || !outcome.retryable) markAutoDone(userId, listOf(item.code))
        return outcome
    }

    /**
     * 错误码 → 给用户看的文案，对齐官方兑换页自己的翻译表。BadRequest 是
     * 特殊货色：泛化的 "Failed for unknown reason" 是软限流（见类注释），
     * 只有带具体 message 的才是真业务拒绝。
     */
    private fun errorText(error: String, message: String = ""): String = when (error) {
        "ValidationFailed" -> "输入格式不对，请检查昵称与兑换码"
        "InvalidCode" -> "兑换码无效，请检查有没有输错"
        "ExpiredCode" -> "兑换码已过期"
        "AlreadyUsed" -> "这个码已经兑换过了"
        "ExceededUses" -> "这个码的兑换次数已用完"
        "UnavailableCode" -> "这个码现在不可用"
        "IncorrectUser" -> "账号没对上：官方按游戏昵称发奖，请核对昵称拼写"
        "ClaimRewardsFailed" -> "发奖失败，稍后重启游戏看看邮箱"
        "BadRequest" -> when {
            message.contains("unknown reason") ->
                "请求太快，被官方临时限流了（约几分钟后恢复），稍等再试"
            else -> "请求被官方拒绝（${message.ifBlank { "格式问题" } }），请重试"
        }
        else -> "兑换失败（$error）"
    }

    /**
     * 解析网关响应。错误时 HTTP 仍是 200，错误码在 error / errorCode 字段
     * （error 也可能是装着错误码的 {message:"..."} 对象形态，社区实现里见过）；
     * 成功时是 {"success":true,...}。两种信号都没有才判成功。
     */
    private fun parseOutcome(code: String, userId: String, body: String, reward: String): RedeemOutcome {
        return try {
            val root = JsonParser.parseString(body).asJsonObject
            val error = errorField(root)
            val successFlag = root.get("success")?.takeIf { it.isJsonPrimitive }?.asBoolean
            when {
                !error.isNullOrBlank() -> {
                    val msg = root.str("message").orEmpty()
                    RedeemOutcome(
                        code, false, errorText(error, msg),
                        retryable = error !in TERMINAL_ERRORS,
                        rateLimited = error == "BadRequest" && msg.contains("unknown reason"),
                        errorCode = error
                    )
                }
                successFlag == false ->
                    RedeemOutcome(code, false, root.str("message") ?: "兑换失败", retryable = true)
                else -> {
                    appendHistory(RedeemRecord(code, reward, System.currentTimeMillis() / 1000, userId))
                    RedeemOutcome(code, true, "兑换成功，奖励已发往游戏内邮箱（重启游戏后查收）")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "兑换响应解析失败: $body", e)
            RedeemOutcome(code, false, "官方返回了意外的格式，兑换结果未知", retryable = true)
        }
    }

    private fun errorField(root: JsonObject): String? {
        val e = root.get("error") ?: root.get("errorCode") ?: return null
        return when {
            e.isJsonPrimitive -> e.asString
            e.isJsonObject -> e.asJsonObject.str("code") ?: e.asJsonObject.str("message")
            else -> null
        }
    }

    /** 一次网关响应。status 非 200 时 body 为 null；连接失败整体返回 null。 */
    private class HttpResult(val status: Int, val body: String?)

    private fun httpPost(jsonBody: String): HttpResult? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 20000
                doOutput = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Origin", ORIGIN)
                setRequestProperty("Referer", "$ORIGIN/")
            }
            conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val body = if (status == HttpURLConnection.HTTP_OK) {
                conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } else null
            HttpResult(status, body)
        } catch (e: Exception) {
            Log.w(TAG, "兑换请求失败: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString
}
