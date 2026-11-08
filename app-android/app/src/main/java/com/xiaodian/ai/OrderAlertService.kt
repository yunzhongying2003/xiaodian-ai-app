package com.xiaodian.ai

// 不漏单：前台服务里保持 SSE 长连接（GET /app/events），收到事件立即提醒，并让界面刷新。
// - SSE 断线：指数退避重连（2s、4s、8s…最多 60s）；每次连上先全量拉一次订单和待人工。
// - SSE 正常时，轮询降为每 60 秒一次兜底；/app/events 返回 404（旧后端）或连不上时，
//   退回每 8 秒轮询（旧后端每 5 分钟再试一次 SSE）。
// - 401：走 ApiClient 统一的清 token 逻辑，发通知提醒重新登录，停止服务。
// - SSE 和轮询共用「已提醒 key」（new:<order_id>、paid:<order_id>、handoff:<id>），同一单不重复播报。
// 播报分两段（按 App 里保存的当前模式）：
// - 老板：order_new →「有新单待收款，取餐号 X」；handoff_new →「有一条待人工消息」。
// - 后厨：order_paid →「新单，取餐号 X，自取/配送」；不播 order_new 和待人工。
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

class OrderAlertService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var sseJob: Job? = null
    private var speakJob: Job? = null
    @Volatile private var sseCall: okhttp3.Call? = null
    private lateinit var speaker: Speaker
    private val nextNotifId = AtomicInteger(1000)

    /** 保护「已提醒 key」和每次检查的串行执行（SSE 事件和轮询可能同时到）。 */
    private val checkLock = Mutex()
    private val announced = LinkedHashSet<String>()
    private var announcedLoaded = false

    /** 待播报的句子，单独一个协程按顺序播，不阻塞 SSE 读取。 */
    private val speechQueue = Channel<List<String>>(Channel.UNLIMITED)
    /** SSE 事件按到达顺序逐个处理，不阻塞读流。 */
    private val eventQueue = Channel<SseEvent>(Channel.UNLIMITED)
    private var eventJob: Job? = null

    /** 上次全量拉取的时间（SSE 在线时用来判断 60 秒兜底）。 */
    @Volatile private var lastFullCheck = 0L
    /** 旧后端（/app/events 404）：下次再试 SSE 的时间。 */
    @Volatile private var sseRetryAt = 0L
    @Volatile private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ApiClient.init(applicationContext)
        createChannels()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, ONGOING_ID, buildOngoing(), type)
        speaker = Speaker(applicationContext)
        speaker.init()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (speakJob?.isActive != true) {
            speakJob = scope.launch {
                for (batch in speechQueue) speaker.speakAll(batch)
            }
        }
        if (eventJob?.isActive != true) {
            eventJob = scope.launch {
                for (ev in eventQueue) {
                    try { handleEvent(ev) } catch (_: Exception) {}
                }
            }
        }
        if (sseJob?.isActive != true) sseJob = scope.launch { sseLoop() }
        if (pollJob?.isActive != true) pollJob = scope.launch { pollLoop() }
        return START_STICKY
    }

    override fun onDestroy() {
        stopping = true
        AppEvents.setSseConnected(false)
        try { sseCall?.cancel() } catch (_: Exception) {}
        scope.cancel()
        speechQueue.close()
        eventQueue.close()
        speaker.shutdown()
        super.onDestroy()
    }

    /** 还应该继续运行吗？不应该时顺便停掉服务。 */
    private fun shouldRun(): Boolean {
        if (stopping) return false
        if (!ApiClient.settings.alertsEnabled || !ApiClient.isLoggedIn()) {
            // 别处（界面）先收到 401 被动退出时，也提醒一次。
            if (ApiClient.isLoggedIn().not() && ApiClient.logoutReason.value.isNotBlank()) notifyLoginExpired()
            stopSelfSafely()
            return false
        }
        return true
    }

    private fun stopSelfSafely() {
        if (stopping) return
        stopping = true
        AppEvents.setSseConnected(false)
        try { sseCall?.cancel() } catch (_: Exception) {}
        stopSelf()
    }

    private fun onUnauthorized() {
        ApiClient.onUnauthorized()
        notifyLoginExpired()
        stopSelfSafely()
    }

    // ---------- SSE ----------

    private suspend fun sseLoop() {
        var backoff = BACKOFF_MIN_MS
        while (scope.isActive && shouldRun()) {
            val wait = sseRetryAt - System.currentTimeMillis()
            if (wait > 0) { delay(minOf(wait, 5_000L)); continue }

            var opened = false
            val outcome = EventStream.run(
                onCall = { sseCall = it },
                onOpen = {
                    opened = true
                    backoff = BACKOFF_MIN_MS
                    AppEvents.setSseConnected(true)
                    // 每次连上先全量刷新一次，补上断线期间漏掉的单（会正常提醒）。
                    scope.launch { fullCheck() }
                    AppEvents.requestRefresh()
                },
                onEvent = { ev -> eventQueue.trySend(ev) },
            )
            sseCall = null
            AppEvents.setSseConnected(false)
            if (!scope.isActive || stopping) break
            when (outcome) {
                is SseOutcome.Unauthorized -> { onUnauthorized(); break }
                is SseOutcome.Unsupported -> {
                    // 旧后端：靠 8 秒轮询，过 5 分钟再试 SSE。
                    sseRetryAt = System.currentTimeMillis() + UNSUPPORTED_RETRY_MS
                    backoff = BACKOFF_MIN_MS
                }
                is SseOutcome.Disconnected, is SseOutcome.Failed -> {
                    if (opened) backoff = BACKOFF_MIN_MS
                    delay(backoff)
                    backoff = minOf(backoff * 2, BACKOFF_MAX_MS)
                }
            }
        }
    }

    private suspend fun handleEvent(ev: SseEvent) {
        val data = try { if (ev.data.isBlank()) JSONObject() else JSONObject(ev.data) } catch (_: Exception) { JSONObject() }
        val mode = ApiClient.settings.mode
        val texts = mutableListOf<String>()
        checkLock.withLock {
            loadAnnounced()
            when (ev.event) {
                "order_new" -> {
                    val id = data.optString("order_id")
                    val no = data.optString("pickup_no")
                    if (id.isNotBlank() && markNew("new:$id") && mode == AppMode.BOSS) {
                        notifyAlert("有新单待收款 · 取餐号 $no", fulfillmentLabelOf(data.optString("fulfillment")))
                        texts += "有新单待收款，取餐号 $no"
                    }
                }
                "order_paid" -> {
                    val id = data.optString("order_id")
                    val no = data.optString("pickup_no")
                    if (id.isNotBlank()) markNew("new:$id")
                    if (id.isNotBlank() && markNew("paid:$id") && mode == AppMode.KITCHEN) {
                        val f = fulfillmentLabelOf(data.optString("fulfillment"))
                        notifyAlert("新单 · 取餐号 $no", f)
                        texts += "新单，取餐号 $no，$f"
                    }
                }
                "handoff_new" -> {
                    val id = data.optString("id")
                    if (id.isNotBlank() && markNew("handoff:$id") && mode == AppMode.BOSS) {
                        val customer = data.optString("customer")
                        notifyAlert("有一条待人工消息", if (customer.isNotBlank()) "顾客 ${customer.take(12)}" else "打开 App 处理")
                        texts += "有一条待人工消息"
                    }
                    // SSE 的 id 不一定等于列表里的 msgid：静默同步一次待人工列表，避免轮询再播一遍。
                    try { syncHandoffs(announce = false) } catch (e: UnauthorizedException) { onUnauthorized() } catch (_: Exception) {}
                }
                else -> {} // order_status 等：只刷新界面
            }
            // SSE 推来的都是新事件，可以直接提醒；但「基线」只由全量对比建立。
            saveAnnounced(baseline = false)
        }
        AppEvents.requestRefresh()
        announce(texts)
    }

    // ---------- 轮询（兜底） ----------

    private suspend fun pollLoop() {
        while (scope.isActive && shouldRun()) {
            val interval = if (AppEvents.sseConnected.value) FALLBACK_POLL_MS else AppViewModel.POLL_MS
            if (System.currentTimeMillis() - lastFullCheck >= interval - 500) {
                fullCheck()
            }
            delay(AppViewModel.POLL_MS)
        }
    }

    /** 全量拉订单和待人工，和「已提醒 key」对比，新的就提醒。 */
    private suspend fun fullCheck() {
        if (!ApiClient.isLoggedIn()) return
        val texts = mutableListOf<String>()
        checkLock.withLock {
            lastFullCheck = System.currentTimeMillis()
            loadAnnounced()
            val firstRun = !ApiClient.settings.hasAnnouncedBaseline()
            val mode = ApiClient.settings.mode
            var ordersOk = false
            var handoffsOk = false
            try {
                val orders = OrderRepository.fetchOrders()
                for (o in orders) {
                    if (o.id.isBlank()) continue
                    val isNew = markNew("new:${o.id}")
                    if (isNew && !firstRun && mode == AppMode.BOSS && o.status == "pending_payment") {
                        notifyAlert("有新单待收款 · 取餐号 ${o.pickupNo}", o.fulfillmentLabel())
                        texts += "有新单待收款，取餐号 ${o.pickupNo}"
                    }
                    if (o.status in PAID_STATUSES) {
                        val isPaid = markNew("paid:${o.id}")
                        if (isPaid && !firstRun && mode == AppMode.KITCHEN && o.isActive()) {
                            notifyAlert("新单 · 取餐号 ${o.pickupNo}", o.fulfillmentLabel())
                            texts += "新单，取餐号 ${o.pickupNo}，${o.fulfillmentLabel()}"
                        }
                    }
                }
                ordersOk = true
            } catch (e: UnauthorizedException) {
                onUnauthorized(); return
            } catch (_: Exception) {
                // 网络抖动：下一轮再拉，不打扰用户。
            }
            try {
                texts += syncHandoffs(announce = !firstRun && mode == AppMode.BOSS)
                handoffsOk = true
            } catch (e: UnauthorizedException) {
                onUnauthorized(); return
            } catch (_: Exception) {
            }
            // 订单和待人工都拉成功过一次，才算建立了基线（之后的新单才提醒）。
            saveAnnounced(baseline = ordersOk && handoffsOk)
        }
        announce(texts)
    }

    /** 拉待人工列表，把没见过的标记为已提醒；announce=true 时发通知并返回播报文字。调用方持有 checkLock。 */
    private fun syncHandoffs(announce: Boolean): List<String> {
        val out = mutableListOf<String>()
        val handoffs = HandoffRepository.fetchHandoffs()
        for (h in handoffs) {
            if (markNew("handoff:${h.key()}") && announce) {
                notifyAlert("有一条待人工消息", "${h.typeLabel()} · 顾客 ${h.externalUserid.take(8)}")
                out += "有一条待人工消息"
            }
        }
        return out
    }

    // ---------- 已提醒 key ----------

    private fun loadAnnounced() {
        if (announcedLoaded) return
        announced.clear()
        announced.addAll(ApiClient.settings.announcedKeys())
        announcedLoaded = true
    }

    /** 第一次见到返回 true 并记下。 */
    private fun markNew(key: String): Boolean = announced.add(key)

    private fun saveAnnounced(baseline: Boolean) {
        // 只保留最近的 MAX 条（LinkedHashSet 保持加入顺序）。
        while (announced.size > MAX_KEYS) announced.remove(announced.first())
        ApiClient.settings.saveAnnouncedKeys(announced, baseline)
    }

    // ---------- 通知 / 声音 / 播报 ----------

    private suspend fun announce(texts: List<String>) {
        if (texts.isEmpty()) return
        playTone()
        if (ApiClient.settings.ttsEnabled) {
            delay(1200) // 等提示音响完再播报
            speechQueue.trySend(texts)
        }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ongoing = NotificationChannel(CH_ONGOING, "后台监听", NotificationManager.IMPORTANCE_LOW)
        ongoing.description = "保持监听新单和待人工消息"
        val alerts = NotificationChannel(CH_ALERTS, "新单和待人工提醒", NotificationManager.IMPORTANCE_HIGH)
        alerts.description = "有新订单或待人工消息时提醒"
        alerts.setSound(null, null) // 提示音由服务自己播放，避免和通知声叠在一起
        alerts.enableVibration(true)
        nm.createNotificationChannel(ongoing)
        nm.createNotificationChannel(alerts)
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun buildOngoing(): Notification =
        NotificationCompat.Builder(this, CH_ONGOING)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("小店AI助手")
            .setContentText("正在监听新单和待人工消息")
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .build()

    private fun notifyLoginExpired() {
        notifyAlert("登录已过期，请重新登录", "打开小店AI助手重新登录后恢复新单提醒")
    }

    private fun notifyAlert(title: String, text: String) {
        val n = NotificationCompat.Builder(this, CH_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        try {
            NotificationManagerCompat.from(this).notify(nextNotifId.getAndIncrement(), n)
        } catch (_: SecurityException) {
            // 没有通知权限：仍然会响提示音和播报。
        }
    }

    private fun playTone() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(applicationContext, uri)?.play()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val CH_ONGOING = "xd_ongoing"
        private const val CH_ALERTS = "xd_alerts_v1"
        private const val ONGOING_ID = 1
        private const val MAX_KEYS = 2000
        private const val BACKOFF_MIN_MS = 2_000L
        private const val BACKOFF_MAX_MS = 60_000L
        private const val UNSUPPORTED_RETRY_MS = 5 * 60_000L
        /** SSE 在线时的兜底轮询间隔。 */
        const val FALLBACK_POLL_MS = 60_000L

        /** 已付款（进入后厨）的状态。 */
        private val PAID_STATUSES = setOf("ordered", "making", "ready", "delivering", "awaiting_pickup", "completed")

        /** 按设置开关和登录状态启动或停止提醒服务。 */
        fun sync(context: Context) {
            ApiClient.init(context.applicationContext)
            val intent = Intent(context, OrderAlertService::class.java)
            if (ApiClient.settings.alertsEnabled && ApiClient.isLoggedIn()) {
                try {
                    ContextCompat.startForegroundService(context, intent)
                } catch (_: Exception) {
                    // Android 12+ 后台不允许启动前台服务；下次打开 App 时再启动。
                }
            } else {
                context.stopService(intent)
            }
        }
    }
}
