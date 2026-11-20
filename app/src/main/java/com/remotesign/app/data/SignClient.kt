package com.remotesign.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * 「我去图书馆」（web.traceint.com）签到接口层，逻辑与 gotolibrary 的实测实现一致：
 *
 *   POST https://wechat.v2.traceint.com/index.php/graphql/
 *   Headers: app-version: 2.2.5 / Origin: https://web.traceint.com
 *   Cookie:  Authorization=<JWT，有效期 2 小时>
 *
 * 签到码 = 当前预约的 reserve.token，官方前端每 9 秒轮询 getQrCodeStatus：
 *   - 无错误        → 当前码仍有效
 *   - code=10       → msg 是新 token，换码重画
 *   - code=1 / 55   → 签到成功（服务端把成功也放在 errors 通道里）
 *   - code=40001    → 登录态失效
 */
object SignSpec {
    const val HOST = "https://wechat.v2.traceint.com"
    const val GRAPHQL = "$HOST/index.php/graphql/"
    const val WEB_ORIGIN = "https://web.traceint.com"
    const val APP_VERSION = "2.2.5"

    const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7 Build/TQ3A.230805.001; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/120.0.6099.144 " +
            "Mobile Safari/537.36 MicroMessenger/8.0.49.2600(0x2800313D) " +
            "WeChat/arm64 Weixin NetType/WIFI Language/zh_CN ABI/arm64"

    /** 签到页聚合：当前预约（含签到 token） */
    const val Q_SIGN_INFO =
        "query index { userAuth { reserve { reserve { token status lib_id lib_name lib_floor seat_key seat_name exp_date exp_date_str } qrUrl } } }"

    /** 二维码轮换/签到状态轮询 */
    const val Q_QR_STATUS =
        "query getQrCodeStatus(\$oldToken: String!) { userAuth { reserve { getQrCodeStatus(oldToken: \$oldToken) } } }"
}

/** 业务错误（接口真的没干成） */
class ApiException(message: String, val code: Int = 0) : Exception(message)

/** 服务端通过 errors 通道下发的成功通知：1=操作成功，55=已签到 */
class ApiNotice(val code: Int, notice: String) : Exception(notice)

data class Session(
    val token: String,
    /** 过期时间，毫秒 */
    val expireAtMs: Long,
) {
    fun isValid(now: Long = System.currentTimeMillis()): Boolean =
        token.isNotEmpty() && expireAtMs > now

    fun remainMs(now: Long = System.currentTimeMillis()): Long =
        (expireAtMs - now).coerceAtLeast(0)
}

/** 登录凭证（JWT）存储：粘贴导入、解析过期时间、持久化 */
class TokenStore(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("remote_sign_session", Context.MODE_PRIVATE)

    fun rawToken(): String = sp.getString(KEY_TOKEN, "").orEmpty()

    fun session(): Session? {
        val token = rawToken()
        if (token.isEmpty()) return null
        return parseJwt(token)
    }

    /** 从任意粘贴内容里抠出 JWT 并保存；成功返回解析出的 Session */
    fun save(input: String): Session? {
        val token = extractToken(input)
        if (token.isEmpty()) return null
        val session = parseJwt(token) ?: return null
        val ok = sp.edit().putString(KEY_TOKEN, token).commit()
        return if (ok) session else null
    }

    fun clear() {
        sp.edit().remove(KEY_TOKEN).commit()
    }

    /**
     * 支持三种粘贴形态：
     *   Authorization=<JWT>
     *   Cookie: Authorization=<JWT>; SERVERID=xxx
     *   eyJ...（裸 token）
     */
    fun extractToken(input: String): String {
        var s = input.trim().replace("\r", " ").replace("\n", " ")
        if (s.startsWith("Cookie:", ignoreCase = true)) s = s.substring(7).trim()
        val marker = "Authorization="
        val idx = s.indexOf(marker, ignoreCase = true)
        if (idx >= 0) {
            val rest = s.substring(idx + marker.length)
            return rest.substringBefore(";").trim().removeSurrounding("\"")
        }
        if (s.count { it == '.' } == 2) return s.removeSurrounding("\"")
        return ""
    }

    /** 解 JWT payload 读过期时间（不校验签名，校验在服务端） */
    fun parseJwt(token: String): Session? = try {
        val parts = token.split(".")
        if (parts.size < 2) null else {
            val payload = String(Base64.getUrlDecoder().decode(pad(parts[1])), Charsets.UTF_8)
            val json = JSONObject(payload)
            val raw = json.optLong("expireAt").takeIf { it > 0 }
                ?: json.optLong("exp").takeIf { it > 0 }
                ?: 0L
            // 服务端 expireAt 是秒级时间戳；若拿到的是毫秒级则直接用
            val expireMs = when {
                raw <= 0L -> 0L
                raw < 100_000_000_000L -> raw * 1000
                else -> raw
            }
            Session(token = token, expireAtMs = expireMs)
        }
    } catch (e: Exception) {
        null
    }

    private fun pad(s: String): String {
        val rem = s.length % 4
        return if (rem == 0) s else s + "=".repeat(4 - rem)
    }

    private companion object {
        const val KEY_TOKEN = "jwt"
    }
}

/** 当前预约的签到信息 */
data class SignInfo(
    val qrContent: String,
    val status: Int,
    val libName: String,
    val floor: String,
    val seatName: String,
    val expText: String,
)

/** 轮询结果 */
sealed interface PollResult {
    /** 当前码仍有效 */
    data object StillValid : PollResult

    /** 换码：带新 token */
    data class Rotated(val newToken: String) : PollResult

    /** 签到成功 */
    data object Signed : PollResult

    /** 登录态失效 */
    data object Expired : PollResult

    /** 其他错误（网络/业务），可继续重试 */
    data class Failed(val message: String) : PollResult
}

class SignApi(private val store: TokenStore) {

    // 注意：服务端只认 "application/json"，带 "; charset=utf-8" 会被当成空 body
    private val jsonType = "application/json".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    private suspend fun gql(
        operation: String,
        query: String,
        variables: Map<String, Any?> = emptyMap(),
    ): JSONObject = withContext(Dispatchers.IO) {
        val token = store.rawToken()
        if (token.isEmpty()) throw ApiException("未导入凭证", 40001)

        val vars = JSONObject()
        variables.forEach { (k, v) -> vars.put(k, v) }
        val payload = JSONObject()
            .put("operationName", operation)
            .put("query", query)
            .put("variables", vars)
            .toString()

        val request = Request.Builder()
            .url(SignSpec.GRAPHQL)
            .header("app-version", SignSpec.APP_VERSION)
            .header("Origin", SignSpec.WEB_ORIGIN)
            .header("Referer", "${SignSpec.WEB_ORIGIN}/")
            .header("User-Agent", SignSpec.UA)
            .header("Cookie", "Authorization=$token")
            .post(payload.toByteArray(Charsets.UTF_8).toRequestBody(jsonType))
            .build()

        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ApiException("HTTP ${resp.code}")
            if (body.isBlank()) throw ApiException("空响应")
            if (body.trimStart().startsWith("<")) {
                throw ApiException("被网关拦截，登录态可能已失效", 40001)
            }
            val json = JSONObject(body)
            val errors = json.optJSONArray("errors")
            if (errors != null && errors.length() > 0) {
                val e = errors.getJSONObject(0)
                val code = e.optInt("code")
                val msg = e.optString("msg", "接口返回错误")
                when {
                    msg.contains("Access Denied", true) || code == 40001 ->
                        throw ApiException("登录态已失效", 40001)
                    code == 1 || code == 55 ->
                        throw ApiNotice(code, msg)
                    else -> throw ApiException(msg, code)
                }
            }
            json.optJSONObject("data") ?: JSONObject()
        }
    }

    /** 拉当前预约的签到信息；没有待签到预约时返回 null */
    suspend fun signInfo(): SignInfo? {
        val data = gql("index", SignSpec.Q_SIGN_INFO)
        val res = data.optJSONObject("userAuth")?.optJSONObject("reserve") ?: return null
        val r = res.optJSONObject("reserve") ?: return null
        val token = r.optString("token")
        if (token.isEmpty()) return null
        return SignInfo(
            qrContent = token,
            status = r.optInt("status"),
            libName = r.optString("lib_name"),
            floor = r.optString("lib_floor"),
            seatName = r.optString("seat_name"),
            expText = r.optString("exp_date_str"),
        )
    }

    /** 轮询二维码状态（换码/签到成功） */
    suspend fun pollQr(oldToken: String): PollResult =
        try {
            gql("getQrCodeStatus", SignSpec.Q_QR_STATUS, mapOf("oldToken" to oldToken))
            PollResult.StillValid
        } catch (e: ApiNotice) {
            PollResult.Signed
        } catch (e: ApiException) {
            when {
                e.code == 40001 -> PollResult.Expired
                e.code == QR_ROTATE_CODE && e.message?.looksLikeQrToken() == true ->
                    PollResult.Rotated(e.message!!)
                else -> PollResult.Failed(e.message ?: "接口错误")
            }
        } catch (e: Exception) {
            PollResult.Failed(e.message ?: "网络错误")
        }

    companion object {
        /** 需要换码（msg 是新 token） */
        const val QR_ROTATE_CODE = 10
    }
}

/** code=10 时 msg 也可能是中文提示，必须确认像 token 才当新码用 */
private fun String.looksLikeQrToken(): Boolean {
    if (isBlank() || length < 8) return false
    if (any { it.isWhitespace() }) return false
    // 含中文等非 ASCII 字符的大概率是提示文案，不是 token
    return all { it.code < 128 }
}
