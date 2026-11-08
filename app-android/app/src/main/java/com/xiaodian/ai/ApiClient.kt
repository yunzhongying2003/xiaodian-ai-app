package com.xiaodian.ai

// 统一的网络入口：一个共享的 OkHttpClient + 一个 AuthProvider。
// 所有 Repository 都只通过这里发请求。
// 鉴权：登录后每个请求带 Authorization: Bearer <token>（BearerAuthProvider 一处）。
// 任何接口返回 401：清掉 token，session 变成「未登录」，界面回到登录页。
//   POST /app/login  {"username","password","device_name"} -> {"token","expires_at","username"}
//                    401 {"detail":"用户名或密码错误"}；429 {"detail":"尝试次数过多…"}
//   POST /app/logout （Bearer）
//   GET  /app/me     （Bearer）
import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** 给请求加鉴权信息。以后换鉴权方式只改实现。 */
interface AuthProvider {
    fun authorize(builder: Request.Builder)
}

/** 登录 token：Authorization: Bearer <token>。 */
class BearerAuthProvider(private val settings: AppSettings) : AuthProvider {
    override fun authorize(builder: Request.Builder) {
        val token = settings.token
        if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
    }
}

class ApiException(val code: Int, message: String) : Exception(message)

/** 收到 401：登录失效。 */
class UnauthorizedException(message: String) : Exception(message)

data class Session(val username: String, val expiresAt: String)

object ApiClient {
    @Volatile private var settingsRef: AppSettings? = null
    @Volatile var authProvider: AuthProvider = object : AuthProvider {
        override fun authorize(builder: Request.Builder) {}
    }

    private val _session = MutableStateFlow<Session?>(null)
    /** 当前登录状态；null 表示未登录（显示登录页）。 */
    val session: StateFlow<Session?> = _session.asStateFlow()

    /** 被动登出（401）时给登录页的提示。 */
    private val _logoutReason = MutableStateFlow("")
    val logoutReason: StateFlow<String> = _logoutReason.asStateFlow()

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    fun init(context: Context) {
        if (settingsRef != null) return
        synchronized(this) {
            if (settingsRef == null) {
                val s = AppSettings(context.applicationContext)
                s.clearLegacySecrets()
                // 本地已知过期的 token 直接作废，省一次 401。
                val exp = TimeFmt.parseMillis(s.tokenExpiresAt)
                if (s.token.isNotBlank() && exp != null && exp < System.currentTimeMillis()) {
                    s.clearSession()
                }
                settingsRef = s
                authProvider = BearerAuthProvider(s)
                _session.value = if (s.token.isNotBlank()) Session(s.username, s.tokenExpiresAt) else null
            }
        }
    }

    val settings: AppSettings
        get() = settingsRef ?: throw IllegalStateException("ApiClient 未初始化")

    fun isLoggedIn(): Boolean = settingsRef?.token?.isNotBlank() == true

    fun consumeLogoutReason() { _logoutReason.value = "" }

    private fun baseUrl(override: String? = null): String {
        val base = (override ?: settings.serverUrl).trim().trimEnd('/')
        if (base.isEmpty()) throw IllegalStateException("请先填写服务器地址")
        return base
    }

    fun url(path: String): String = baseUrl() + path

    private fun builder(path: String): Request.Builder {
        val b = Request.Builder().url(url(path))
        authProvider.authorize(b)
        return b
    }

    private fun detailOf(body: String): String =
        try { JSONObject(body).optString("detail") } catch (_: Exception) { "" }

    /** 登录失效：清 token、切回登录页。 */
    fun onUnauthorized(reason: String = "登录已过期，请重新登录") {
        val s = settingsRef ?: return
        if (s.token.isBlank() && _session.value == null) return
        s.clearSession()
        _session.value = null
        _logoutReason.value = reason
    }

    private fun fail(what: String, code: Int, body: String): Exception {
        if (code == 401) {
            onUnauthorized()
            return UnauthorizedException("登录已过期，请重新登录")
        }
        val detail = detailOf(body).ifBlank { body.take(200) }
        val hint = if (code == 403) "（当前账号没有这个操作的权限）" else ""
        return ApiException(code, "${what}失败：$code $detail$hint")
    }

    private fun parse(body: String): JSONObject =
        if (body.isBlank()) JSONObject() else JSONObject(body)

    fun getJson(path: String, what: String): JSONObject {
        val req = builder(path).get().build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw fail(what, resp.code, body)
            return parse(body)
        }
    }

    fun postJson(path: String, json: JSONObject, what: String): JSONObject {
        val req = builder(path)
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw fail(what, resp.code, body)
            return parse(body)
        }
    }

    fun postBody(path: String, requestBody: RequestBody, what: String): JSONObject {
        val req = builder(path).post(requestBody).build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw fail(what, resp.code, body)
            return parse(body)
        }
    }

    /** 带鉴权头下载到文件，返回服务器给的 Content-Type（可能为空）。 */
    fun downloadTo(path: String, target: File, what: String): String {
        val req = builder(path).get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw fail(what, resp.code, resp.body?.string() ?: "")
            val body = resp.body ?: throw ApiException(resp.code, "${what}失败：空内容")
            target.parentFile?.mkdirs()
            body.byteStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return resp.header("Content-Type") ?: ""
        }
    }

    // ---------- 登录 ----------

    /** 登录成功保存 token 并切到已登录；失败抛出可直接显示的中文错误。在 IO 线程调用。 */
    fun login(serverUrl: String, username: String, password: String) {
        val base = baseUrl(serverUrl)
        val json = JSONObject()
            .put("username", username)
            .put("password", password)
            .put("device_name", Build.MODEL ?: "Android")
        val req = Request.Builder().url("$base/app/login")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val res: JSONObject = try {
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    val detail = detailOf(body)
                    throw ApiException(
                        resp.code,
                        when (resp.code) {
                            401 -> detail.ifBlank { "用户名或密码错误" }
                            429 -> detail.ifBlank { "尝试次数过多，请稍后再试" }
                            404 -> "服务器还不支持登录（/app/login 不存在），请检查服务器地址"
                            else -> "登录失败：${resp.code} ${detail.ifBlank { body.take(120) }}"
                        },
                    )
                }
                parse(body)
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: java.io.IOException) {
            throw IllegalStateException("连不上服务器：${e.message ?: "网络错误"}")
        } catch (e: org.json.JSONException) {
            throw IllegalStateException("服务器返回的内容不是 JSON，请检查服务器地址")
        }
        val token = res.optString("token")
        if (token.isBlank()) throw IllegalStateException("登录失败：服务器没有返回 token")
        val name = res.optString("username").ifBlank { username }
        val expiresAt = res.optString("expires_at")
        settings.serverUrl = base
        settings.saveSession(token, name, expiresAt)
        _logoutReason.value = ""
        _session.value = Session(name, expiresAt)
    }

    /** 主动退出：尽量通知后端作废 token，无论成功与否都清掉本地登录。在 IO 线程调用。 */
    fun logout() {
        try {
            val req = builder("/app/logout")
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().close()
        } catch (_: Exception) {
        }
        settingsRef?.clearSession()
        _logoutReason.value = ""
        _session.value = null
    }

    /** 校验 token 并刷新用户名 / 有效期（/app/me）。401 会自动登出。在 IO 线程调用。 */
    fun refreshMe() {
        if (!isLoggedIn()) return
        val o = getJson("/app/me", "获取当前用户")
        val name = o.optString("username").ifBlank { settings.username }
        val exp = o.optString("expires_at").ifBlank { settings.tokenExpiresAt }
        settings.username = name
        settings.tokenExpiresAt = exp
        if (isLoggedIn()) _session.value = Session(name, exp)
    }
}
