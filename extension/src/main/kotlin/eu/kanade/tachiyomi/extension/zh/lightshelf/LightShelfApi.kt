package eu.kanade.tachiyomi.extension.zh.lightshelf

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import java.io.Closeable
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

internal interface SessionStore {
    var refreshToken: String
    val tokenOnly: Boolean get() = false
    val email: String
    val passwordHash: String
    val visitorId: String
}

internal class ApiException(
    message: String,
    val status: Int = 0,
    val envelope: Boolean = false,
) : IOException(message)

internal class TransportException(message: String) : IOException(message)

/** Uses only read-only manga hub methods, so a single recovery retry is safe. */
internal class LightShelfApi(
    private val client: OkHttpClient,
    private val store: SessionStore,
    private var endpoint: String = "https://api.lightnovel.life",
    private val timeoutSeconds: Long = 30,
    private val tokens: AccessTokenCache = AccessTokenCache(),
    private val idleMillis: Long = 30_000,
    private val pauseBeforeRetry: () -> Unit = { Thread.sleep(500) },
) : Closeable {
    private var hub: HubSession? = null

    private fun accessToken(): String {
        tokens.get()?.let { return it }
        if (store.refreshToken.isNotBlank()) {
            try {
                return tokens.put(post("/api/user/refresh_token", json("token" to store.refreshToken)) as String)
            } catch (e: ApiException) {
                // HTTP 403/404 and gateway errors can be routing/WAF failures, not revoked credentials.
                if (e.status != -100 && e.status != 401 && !(e.envelope && e.status == 404)) throw e
                if (store.tokenOnly) throw ApiException("自定义 Token 已失效，请在源设置中更新 Token，或清空后使用邮箱和密码登录。", 401)
                store.refreshToken = ""
            }
        }
        if (store.email.isBlank() || store.passwordHash.isBlank()) {
            throw ApiException("请先在轻书架的源设置中填写邮箱和密码，或设置刷新 Token。", 401)
        }
        val credentials = post(
            "/api/user/login", json("email" to store.email.trim(), "password" to store.passwordHash),
        ) as JSONObject
        store.refreshToken = credentials.string("RefreshToken")
        return tokens.put(credentials.string("Token"))
    }

    private fun post(path: String, payload: JSONObject): Any {
        val request = Request.Builder().url(endpoint + path)
            .header("Accept", "application/json").header("Origin", WEBSITE)
            .header("Referer", "$WEBSITE/").header("x-id", store.visitorId)
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        val stage = if (path.endsWith("/login")) "登录" else "令牌续期"
        for (attempt in 0..1) {
            try {
                return unwrap(JSONObject(hubHttp(client, request, timeoutSeconds, stage)))
            } catch (e: ApiException) {
                // Retry a returned gateway failure once. Never erase credentials for these statuses.
                if (attempt == 1 || e.status !in GATEWAY_ERRORS) throw e
                pauseBeforeRetry()
            }
        }
        error("Unreachable")
    }

    @Synchronized
    fun call(method: String, params: JSONObject): JSONObject {
        require(method in READ_METHODS) { "Only read-only comic methods may use automatic retry" }
        for (attempt in 0..1) {
            val token = accessToken()
            if (hub?.let { it.token != token || !it.isOpen } == true) closeHub()
            val current = hub ?: HubSession(
                client, Request.Builder().url("$endpoint/hub/api")
                    .header("Authorization", "Bearer $token").header("Origin", WEBSITE)
                    .header("x-id", store.visitorId).build(), token, timeoutSeconds, idleMillis,
            ).also { hub = it }
            try {
                return current.invoke(method, params)
            } catch (e: ApiException) {
                closeHub()
                val authFailed = e.status == 401 || e.status == -100
                if (authFailed) tokens.clear()
                if (attempt == 1 || (!authFailed && e.status !in GATEWAY_ERRORS && e.status !in listOf(204, 404))) throw e
                if (!authFailed) pauseBeforeRetry()
            } catch (e: TransportException) {
                closeHub()
                if (attempt == 1) throw e
                pauseBeforeRetry()
            } catch (e: Exception) {
                closeHub()
                throw e
            }
        }
        error("Unreachable")
    }

    private fun closeHub() { hub?.close(); hub = null }

    /** Called under the same lock when credentials change; never retain another account's session. */
    @Synchronized
    override fun close() { closeHub(); tokens.clear() }

    @Synchronized
    fun setEndpoint(value: String) {
        if (endpoint == value) return
        close()
        endpoint = value
    }

    companion object {
        const val WEBSITE = "https://www.lightnovel.app"
        private val GATEWAY_ERRORS = setOf(502, 503, 504)
        private val READ_METHODS = setOf("GetComicList", "SearchComicSeries", "GetBooksBySeries", "GetBookInfo", "GetComicContent")

        fun passwordHash(password: String): String = MessageDigest.getInstance("SHA-256")
            .digest(password.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        fun newVisitorId(): String = UUID.randomUUID().toString()

        fun unwrap(envelope: JSONObject): Any {
            if (envelope.value("Success") != true) {
                throw ApiException(
                    envelope.optionalString("Msg").ifBlank { "轻书架请求失败，请检查登录状态。" },
                    (envelope.value("Status") as? Number)?.toInt() ?: 0,
                    envelope = true,
                )
            }
            return envelope.value("Response") ?: throw IOException("轻书架返回了空响应。")
        }
    }
}
internal fun json(vararg entries: Pair<String, Any>): JSONObject = JSONObject().apply {
    entries.forEach { (key, value) -> put(key, value) }
}

// The JSON protocol uses camelCase; HTTP and MessagePack can use PascalCase.
internal fun JSONObject.value(key: String): Any? {
    val actual = if (has(key)) key else key.replaceFirstChar { it.lowercaseChar() }
    return opt(actual)?.takeUnless { it == JSONObject.NULL }
}
internal fun JSONObject.string(key: String): String = value(key) as? String
    ?: throw IOException("轻书架响应缺少 $key。")
internal fun JSONObject.optionalString(key: String): String = value(key) as? String ?: ""
internal fun JSONObject.number(key: String): Int = (value(key) as? Number)?.toInt()
    ?: throw IOException("轻书架响应缺少 $key。")
internal fun JSONObject.obj(key: String): JSONObject = value(key) as? JSONObject
    ?: throw IOException("轻书架响应缺少 $key。")
internal fun JSONObject.array(key: String): JSONArray = value(key) as? JSONArray
    ?: throw IOException("轻书架响应缺少 $key。")
internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
