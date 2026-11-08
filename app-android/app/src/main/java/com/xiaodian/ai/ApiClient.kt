package com.xiaodian.ai

// 统一的网络入口：一个共享的 OkHttpClient + 一个 AuthProvider。
// 所有 Repository 都只通过这里发请求。以后从「设备密钥 + PIN」
// 换成登录 token 时，只需要换 AuthProvider 的实现（或改
// DeviceKeyAuthProvider 这一处），其它代码不用动。
import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** 给请求加鉴权信息。write=true 表示写操作（改状态、回复、改菜单等）。 */
interface AuthProvider {
    fun authorize(builder: Request.Builder, write: Boolean)
}

/** 当前方案：X-Device-Key 每个请求都带；X-Device-Pin 只在写操作带。
 *  后端没配 APP_DEVICE_KEY 时这些头会被忽略（试点开发模式）。 */
class DeviceKeyAuthProvider(private val settings: AppSettings) : AuthProvider {
    override fun authorize(builder: Request.Builder, write: Boolean) {
        val key = settings.deviceKey
        if (key.isNotBlank()) builder.header("X-Device-Key", key)
        val pin = settings.devicePin
        if (write && pin.isNotBlank()) builder.header("X-Device-Pin", pin)
    }
}

class ApiException(val code: Int, message: String) : Exception(message)

object ApiClient {
    @Volatile private var settingsRef: AppSettings? = null
    @Volatile var authProvider: AuthProvider = object : AuthProvider {
        override fun authorize(builder: Request.Builder, write: Boolean) {}
    }

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
                settingsRef = s
                authProvider = DeviceKeyAuthProvider(s)
            }
        }
    }

    val settings: AppSettings
        get() = settingsRef ?: throw IllegalStateException("ApiClient 未初始化")

    fun url(path: String): String {
        val base = settings.serverUrl.trim().trimEnd('/')
        if (base.isEmpty()) throw IllegalStateException("还没有填后端地址，请到「设置」页填写")
        return base + path
    }

    private fun builder(path: String, write: Boolean): Request.Builder {
        val b = Request.Builder().url(url(path))
        authProvider.authorize(b, write)
        return b
    }

    private fun fail(what: String, code: Int, body: String): ApiException {
        val hint = if (code == 401 || code == 403) "（鉴权失败，请到「设置」检查设备密钥和 PIN）" else ""
        return ApiException(code, "${what}失败：$code ${body.take(200)}$hint")
    }

    private fun parse(body: String): JSONObject =
        if (body.isBlank()) JSONObject() else JSONObject(body)

    fun getJson(path: String, what: String): JSONObject {
        val req = builder(path, write = false).get().build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw fail(what, resp.code, body)
            return parse(body)
        }
    }

    fun postJson(path: String, json: JSONObject, what: String, write: Boolean = true): JSONObject {
        val req = builder(path, write)
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw fail(what, resp.code, body)
            return parse(body)
        }
    }

    fun postBody(path: String, requestBody: RequestBody, what: String, write: Boolean = true): JSONObject {
        val req = builder(path, write).post(requestBody).build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw fail(what, resp.code, body)
            return parse(body)
        }
    }

    /** 带鉴权头下载到文件，返回服务器给的 Content-Type（可能为空）。 */
    fun downloadTo(path: String, target: File, what: String): String {
        val req = builder(path, write = false).get().build()
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
}
