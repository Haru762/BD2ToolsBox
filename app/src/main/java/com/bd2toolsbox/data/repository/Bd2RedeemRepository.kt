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

        /** 本地成功记录的上限，超出丢最旧的。 */
        private const val HISTORY_CAP = 100

        @Volatile
        private var instance: Bd2RedeemRepository? = null

        fun get(context: android.content.Context): Bd2RedeemRepository =
            instance ?: synchronized(this) {
                instance ?: Bd2RedeemRepository(context.applicationContext).also { instance = it }
            }
    }

    // ---------------------------------------------------------------- 昵称与记录（本地）

    private val prefs by lazy {
        appContext.getSharedPreferences("bd2_redeem", android.content.Context.MODE_PRIVATE)
    }

    /** 记住的游戏昵称。字段名随官方 API 叫 user_id，含义是昵称。 */
    fun savedUserId(): String = prefs.getString("user_id", "").orEmpty()

    fun saveUserId(userId: String) {
        prefs.edit().putString("user_id", userId.trim()).apply()
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
                    at = o.get("at")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
                )
            }.orEmpty()
    } catch (e: Exception) {
        Log.w(TAG, "兑换记录读取失败", e)
        emptyList()
    }

    private fun appendHistory(record: RedeemRecord) {
        try {
            // 同码只留最新一条；新记录放最前
            val list = mutableListOf(record) + loadHistory().filterNot { it.code == record.code }
            val arr = JsonArray()
            list.take(HISTORY_CAP).forEach { r ->
                arr.add(JsonObject().apply {
                    addProperty("code", r.code)
                    addProperty("reward", r.reward)
                    addProperty("at", r.at)
                })
            }
            historyFile.writeText(arr.toString())
        } catch (e: Exception) {
            Log.w(TAG, "兑换记录写入失败", e)
        }
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
                    normalized, false, "网络错误，请求没能发出，请检查网络后重试"
                )
            when {
                resp.status == 429 -> RedeemOutcome(
                    normalized, false, "操作太频繁，被官方限流了，稍等一会儿再试"
                )
                resp.body == null -> RedeemOutcome(
                    normalized, false, "官方服务异常（HTTP ${resp.status}），稍后再试"
                )
                else -> parseOutcome(normalized, resp.body, reward)
            }
        }

    /**
     * 逐条串行兑换（带间隔防限流）。调用方负责过滤出值得试的码——已兑换过的
     * 码服务端会回 AlreadyUsed，重试无害但浪费请求。
     */
    suspend fun redeemAll(userId: String, items: List<CdkItem>): List<RedeemOutcome> {
        val outcomes = mutableListOf<RedeemOutcome>()
        items.forEachIndexed { i, item ->
            if (i > 0) delay(BATCH_INTERVAL_MS)
            outcomes += redeem(userId, item.code, item.reward)
        }
        return outcomes
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
    private fun parseOutcome(code: String, body: String, reward: String): RedeemOutcome {
        return try {
            val root = JsonParser.parseString(body).asJsonObject
            val error = errorField(root)
            val successFlag = root.get("success")?.takeIf { it.isJsonPrimitive }?.asBoolean
            when {
                !error.isNullOrBlank() -> RedeemOutcome(
                    code, false, errorText(error, root.str("message").orEmpty())
                )
                successFlag == false ->
                    RedeemOutcome(code, false, root.str("message") ?: "兑换失败")
                else -> {
                    appendHistory(RedeemRecord(code, reward, System.currentTimeMillis() / 1000))
                    RedeemOutcome(code, true, "兑换成功，奖励已发往游戏内邮箱（重启游戏后查收）")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "兑换响应解析失败: $body", e)
            RedeemOutcome(code, false, "官方返回了意外的格式，兑换结果未知")
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
