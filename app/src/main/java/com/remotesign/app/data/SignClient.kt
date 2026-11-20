package com.remotesign.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.UUID
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
 *
 * 获取本人凭证（微信 OAuth code 换 JWT）：
 *   GET https://wechat.v2.traceint.com/index.php/urlNew/auth.html?r=...&code=<code>&state=1
 *   → 302，Set-Cookie: Authorization=<JWT>
 */
object SignSpec {
    const val HOST = "https://wechat.v2.traceint.com"
    const val GRAPHQL = "$HOST/index.php/graphql/"
    const val AUTH = "$HOST/index.php/urlNew/auth.html"
    const val WEB_ORIGIN = "https://web.traceint.com"
    const val APP_VERSION = "2.2.5"

    const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7 Build/TQ3A.230805.001; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/120.0.6099.144 " +
            "Mobile Safari/537.36 MicroMessenger/8.0.49.2600(0x2800313D) " +
            "WeChat/arm64 Weixin NetType/WIFI Language/zh_CN ABI/arm64"

    /** 官方公众号 appid（与 gotolibrary 实测一致） */
    const val WX_APPID = "wx2996d437cd442527"

    /** 直接把 GraphQL 端点当 redirect_uri，微信授权后回跳到这里并带上 code */
    const val REDIRECT_URI = "$HOST/index.php/graphql"

    fun authUrl(): String = buildString {
        append("https://open.weixin.qq.com/connect/oauth2/authorize")
        append("?appid=").append(WX_APPID)
        append("&redirect_uri=").append(java.net.URLEncoder.encode(REDIRECT_URI, "UTF-8"))
        append("&response_type=code&scope=snsapi_userinfo&state=1#wechat_redirect")
    }

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

/** 一份已保存的凭证（带用户名标签，可存多份） */
data class SavedCredential(
    val id: String,
    val label: String,
    val token: String,
    val expireAtMs: Long,
    val addedAtMs: Long,
) {
    fun isValid(now: Long = System.currentTimeMillis()): Boolean =
        token.isNotEmpty() && expireAtMs > now

    fun remainMs(now: Long = System.currentTimeMillis()): Long =
        (expireAtMs - now).coerceAtLeast(0)

    fun toSession(): Session = Session(token, expireAtMs)
}

/**
 * 凭证存储：可保存多份，每份带用户名标签，其中一份为「当前使用」。
 * 旧版本只存一份（key=jwt），首次启动自动迁移为带标签的一份。
 */
class TokenStore(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("remote_sign_session", Context.MODE_PRIVATE)

    init {
        migrateLegacy()
    }

    // ---------- 列表管理 ----------

    fun entries(): List<SavedCredential> {
        val raw = sp.getString(KEY_LIST, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        SavedCredential(
                            id = o.getString("id"),
                            label = o.optString("label", "未命名"),
                            token = o.getString("token"),
                            expireAtMs = o.optLong("expireAtMs"),
                            addedAtMs = o.optLong("addedAtMs"),
                        ),
                    )
                }
            }.sortedByDescending { it.addedAtMs }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun persist(list: List<SavedCredential>) {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("label", c.label)
                    .put("token", c.token)
                    .put("expireAtMs", c.expireAtMs)
                    .put("addedAtMs", c.addedAtMs),
            )
        }
        sp.edit().putString(KEY_LIST, arr.toString()).commit()
    }

    fun activeId(): String? = sp.getString(KEY_ACTIVE, null)

    fun active(): SavedCredential? {
        val list = entries()
        val id = activeId()
        return list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }

    fun setActive(id: String) {
        sp.edit().putString(KEY_ACTIVE, id).commit()
    }

    /** 新增一份凭证并设为当前；同一 token 已存在时更新其标签与有效期 */
    fun add(label: String, token: String): SavedCredential? {
        val session = parseJwt(token) ?: return null
        val list = entries().toMutableList()
        val existing = list.firstOrNull { it.token == token }
        val entry = if (existing != null) {
            existing.copy(
                label = label.ifBlank { existing.label },
                expireAtMs = session.expireAtMs,
            )
        } else {
            SavedCredential(
                id = UUID.randomUUID().toString(),
                label = label.ifBlank { "未命名凭证" },
                token = token,
                expireAtMs = session.expireAtMs,
                addedAtMs = System.currentTimeMillis(),
            )
        }
        if (existing != null) {
            list[list.indexOf(existing)] = entry
        } else {
            list.add(entry)
        }
        persist(list)
        setActive(entry.id)
        return entry
    }

    fun rename(id: String, label: String) {
        if (label.isBlank()) return
        persist(entries().map { if (it.id == id) it.copy(label = label) else it })
    }

    fun remove(id: String) {
        val list = entries().filter { it.id != id }
        persist(list)
        if (activeId() == id) {
            val next = list.firstOrNull()
            if (next != null) setActive(next.id) else sp.edit().remove(KEY_ACTIVE).commit()
        }
    }

    // ---------- 当前凭证（供接口层使用） ----------

    fun rawToken(): String = active()?.token.orEmpty()

    fun session(): Session? = active()?.toSession()

    // ---------- 解析（不落盘） ----------

    /** 从任意粘贴内容里抠出 JWT 并解析；不保存 */
    fun parseInput(input: String): Session? {
        val token = extractToken(input)
        if (token.isEmpty()) return null
        return parseJwt(token)
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

    /** 解 JWT payload（不校验签名，校验在服务端） */
    fun jwtClaims(token: String): JSONObject? = try {
        val parts = token.split(".")
        if (parts.size < 2) null else {
            JSONObject(String(Base64.getUrlDecoder().decode(pad(parts[1])), Charsets.UTF_8))
        }
    } catch (e: Exception) {
        null
    }

    fun parseJwt(token: String): Session? {
        val json = jwtClaims(token) ?: return null
        val raw = json.optLong("expireAt").takeIf { it > 0 }
            ?: json.optLong("exp").takeIf { it > 0 }
            ?: 0L
        // 服务端 expireAt 是秒级时间戳；若拿到的是毫秒级则直接用
        val expireMs = when {
            raw <= 0L -> 0L
            raw < 100_000_000_000L -> raw * 1000
            else -> raw
        }
        return Session(token = token, expireAtMs = expireMs)
    }

    /** 从 JWT 声明里猜用户名，作为命名时的默认建议 */
    fun suggestedLabel(token: String): String? {
        val json = jwtClaims(token) ?: return null
        val nameKeys = listOf(
            "name", "nickname", "nickName", "userName", "username",
            "realName", "xm", "studentName", "student_name",
        )
        for (key in nameKeys) {
            val v = json.optString(key).trim()
            if (v.isNotEmpty()) return v
        }
        val uid = json.opt("userId")?.toString()?.trim().orEmpty()
        if (uid.isNotEmpty() && uid != "0") return "用户 $uid"
        return null
    }

    private fun pad(s: String): String {
        val rem = s.length % 4
        return if (rem == 0) s else s + "=".repeat(4 - rem)
    }

    private fun migrateLegacy() {
        if (sp.contains(KEY_LIST)) return
        val legacy = sp.getString(KEY_LEGACY, "").orEmpty()
        if (legacy.isEmpty()) return
        val session = parseJwt(legacy) ?: return
        val entry = SavedCredential(
            id = UUID.randomUUID().toString(),
            label = suggestedLabel(legacy) ?: "我的凭证",
            token = legacy,
            expireAtMs = session.expireAtMs,
            addedAtMs = System.currentTimeMillis(),
        )
        persist(listOf(entry))
        setActive(entry.id)
        sp.edit().remove(KEY_LEGACY).commit()
    }

    private companion object {
        const val KEY_LIST = "credentials"
        const val KEY_ACTIVE = "active_id"
        const val KEY_LEGACY = "jwt"
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

    /**
     * 用微信授权回调里的 code 换 JWT（只换不存，命名保存由界面层处理）。
     * 服务端返回 302 + Set-Cookie: Authorization=<JWT>；code 有效期约 5 分钟。
     */
    suspend fun exchangeCode(code: String): Session = withContext(Dispatchers.IO) {
        val url = "${SignSpec.AUTH}?r=" +
            java.net.URLEncoder.encode("${SignSpec.HOST}/web/index.html", "UTF-8") +
            "&code=$code&state=1"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", SignSpec.UA)
            .get()
            .build()

        client.newCall(request).execute().use { resp ->
            val cookies = Cookie.parseAll(resp.request.url, resp.headers)
            val auth = cookies.firstOrNull { it.name.equals("Authorization", true) }
                ?: throw ApiException("没有拿到登录凭证，授权码可能已过期（有效期约 5 分钟），请重新授权")
            val session = store.parseJwt(auth.value)
                ?: throw ApiException("凭证格式无法识别")
            if (!session.isValid()) {
                throw ApiException("拿到的凭证已过期，请重新授权")
            }
            session
        }
    }

    companion object {
        /** 需要换码（msg 是新 token） */
        const val QR_ROTATE_CODE = 10

        /** 从回调链接（或任意粘贴文本）里抠出微信授权 code */
        fun extractCode(text: String): String? {
            val m = Regex("[?&]code=([A-Za-z0-9_\\-]+)").find(text)
            if (m != null) return m.groupValues[1]
            // 直接粘贴的就是 code 本身
            val t = text.trim()
            return if (t.matches(Regex("[A-Za-z0-9_\\-]{16,64}"))) t else null
        }
    }
}

/** code=10 时 msg 也可能是中文提示，必须确认像 token 才当新码用 */
private fun String.looksLikeQrToken(): Boolean {
    if (isBlank() || length < 8) return false
    if (any { it.isWhitespace() }) return false
    // 含中文等非 ASCII 字符的大概率是提示文案，不是 token
    return all { it.code < 128 }
}
