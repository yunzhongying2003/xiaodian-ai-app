package com.xiaodian.ai

// SSE 长连接：GET {server}/app/events（Authorization: Bearer，text/event-stream）。
// 后端每 15 秒发一行 ": ping"；事件：
//   order_new    {order_id, pickup_no, status, fulfillment}
//   order_paid   {order_id, pickup_no, fulfillment}
//   order_status {order_id, status}
//   handoff_new  {id, customer}
// 不引入 okhttp-sse，直接用 OkHttp 读响应流，按 SSE 规范手写解析（见 SseParser）。
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.channels.BufferOverflow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class SseEvent(val event: String, val data: String, val id: String)

/** SSE 文本解析：一行一行喂进来，遇到空行产出一个事件。注释行（以 : 开头，如 ": ping"）忽略。 */
class SseParser {
    private var event = ""
    private val data = StringBuilder()
    private var hasData = false
    private var lastId = ""

    /** 返回本行结束的事件（空行时），否则 null。 */
    fun feed(rawLine: String): SseEvent? {
        val line = rawLine.removeSuffix("\r")
        if (line.isEmpty()) {
            if (!hasData && event.isEmpty()) return null
            val ev = SseEvent(event.ifEmpty { "message" }, data.toString(), lastId)
            event = ""
            data.setLength(0)
            hasData = false
            return ev
        }
        if (line.startsWith(":")) return null
        val colon = line.indexOf(':')
        val field: String
        var value: String
        if (colon < 0) {
            field = line; value = ""
        } else {
            field = line.substring(0, colon)
            value = line.substring(colon + 1)
            if (value.startsWith(" ")) value = value.substring(1)
        }
        when (field) {
            "event" -> event = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
            "id" -> if (!value.contains('\u0000')) lastId = value
            else -> {} // retry 等字段忽略，重连节奏由客户端自己控制
        }
        return null
    }
}

/** 连接结果，供前台服务决定是重连、退回轮询还是登出。 */
sealed class SseOutcome {
    /** 服务器没有 /app/events（旧后端）：退回 8 秒轮询。 */
    object Unsupported : SseOutcome()
    /** 401：登录失效。 */
    object Unauthorized : SseOutcome()
    /** 连上过（收到过 200），之后断开。 */
    object Disconnected : SseOutcome()
    /** 连不上 / 5xx 等。 */
    data class Failed(val reason: String) : SseOutcome()
}

object EventStream {
    /** 读超时 90 秒：后端每 15 秒有 ping，90 秒没有任何数据就当连接已死，断开重连。 */
    val client: OkHttpClient by lazy {
        ApiClient.http.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * 阻塞读取事件流，直到断开。onOpen 在收到 200 后调用一次；每个事件调用 onEvent。
     * 在 IO 线程调用；取消时由调用方 cancel [call]。
     */
    fun run(
        onCall: (okhttp3.Call) -> Unit,
        onOpen: () -> Unit,
        onEvent: (SseEvent) -> Unit,
    ): SseOutcome {
        val b = Request.Builder().url(ApiClient.url("/app/events"))
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .get()
        ApiClient.authProvider.authorize(b)
        val call = client.newCall(b.build())
        onCall(call)
        return try {
            call.execute().use { resp ->
                when {
                    resp.code == 401 -> return SseOutcome.Unauthorized
                    resp.code == 404 || resp.code == 405 -> return SseOutcome.Unsupported
                    !resp.isSuccessful -> return SseOutcome.Failed("HTTP ${resp.code}")
                }
                val ct = resp.header("Content-Type") ?: ""
                if (!ct.startsWith("text/event-stream")) {
                    // 返回了 200 但不是事件流（例如反向代理的默认页）：按旧后端处理。
                    return SseOutcome.Unsupported
                }
                val source = resp.body?.source() ?: return SseOutcome.Failed("空响应")
                onOpen()
                val parser = SseParser()
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    val ev = parser.feed(line) ?: continue
                    try { onEvent(ev) } catch (_: Exception) {}
                }
                SseOutcome.Disconnected
            }
        } catch (e: Exception) {
            SseOutcome.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
}

/** 前台服务 → 界面：事件到达时让 ViewModel 刷新；SSE 是否在线（决定轮询节奏）。 */
object AppEvents {
    private val _refresh = MutableSharedFlow<Unit>(
        replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val refresh: SharedFlow<Unit> = _refresh.asSharedFlow()

    private val _sseConnected = MutableStateFlow(false)
    /** true = SSE 正常在线，轮询降为每 60 秒一次兜底。 */
    val sseConnected: StateFlow<Boolean> = _sseConnected.asStateFlow()

    fun requestRefresh() { _refresh.tryEmit(Unit) }
    fun setSseConnected(v: Boolean) { _sseConnected.value = v }
}
