package com.xiaodian.ai

// 不漏单：前台服务每 8 秒拉一次 /kf/orders/list 和 /kf/handoff/list，
// 和已经见过的 id 对比；出现新单 / 新待人工时发通知、响提示音，
// 并用系统 TextToSpeech（中文）播报。
// 没有 WebSocket / FCM，靠轮询。第一次启动时只记录现有 id，不播报旧单。
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
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class OrderAlertService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    private val pendingSpeech = mutableListOf<String>()
    private var nextNotifId = 1000

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ApiClient.init(applicationContext)
        createChannels()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, ONGOING_ID, buildOngoing(), type)
        initTts()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (loopJob?.isActive != true) {
            loopJob = scope.launch {
                while (isActive) {
                    if (!ApiClient.settings.alertsEnabled || !ApiClient.isLoggedIn()) {
                        // 别处（界面）先收到 401 被动退出时，也提醒一次。
                        if (ApiClient.logoutReason.value.isNotBlank()) {
                            notifyAlert("登录已过期，请重新登录", "打开小店AI助手重新登录后恢复新单提醒")
                        }
                        stopSelf()
                        break
                    }
                    try {
                        pollOnce()
                    } catch (e: UnauthorizedException) {
                        // 登录过期：停止轮询，提醒重新登录。
                        notifyAlert("登录已过期，请重新登录", "打开小店AI助手重新登录后恢复新单提醒")
                        stopSelf()
                        break
                    }
                    delay(AppViewModel.POLL_MS)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        try { tts?.stop() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null
        super.onDestroy()
    }

    // ---------- 轮询 ----------

    private suspend fun pollOnce() {
        val settings = ApiClient.settings
        val announcements = mutableListOf<String>()

        try {
            val orders = OrderRepository.fetchOrders()
            val ids = orders.map { it.id }.filter { it.isNotBlank() }.toSet()
            if (settings.hasSeenBaseline(KIND_ORDERS)) {
                val seen = settings.seenIds(KIND_ORDERS)
                orders.filter { it.id.isNotBlank() && it.id !in seen && it.isActive() }.forEach { o ->
                    val text = "新单，取餐号 ${o.pickupNo}，${o.fulfillmentLabel()}"
                    notifyAlert("新订单 · 取餐号 ${o.pickupNo}", "${o.fulfillmentLabel()} · ${statusLabelOf(o.status)}")
                    announcements += text
                }
            }
            settings.saveSeenIds(KIND_ORDERS, ids)
        } catch (e: UnauthorizedException) {
            throw e
        } catch (_: Exception) {
            // 网络抖动：下一轮再拉，不打扰用户。
        }

        try {
            val handoffs = HandoffRepository.fetchHandoffs()
            val ids = handoffs.map { it.key() }.toSet()
            if (settings.hasSeenBaseline(KIND_HANDOFFS)) {
                val seen = settings.seenIds(KIND_HANDOFFS)
                handoffs.filter { it.key() !in seen }.forEach { h ->
                    notifyAlert("有一条待人工消息", "${h.typeLabel()} · 顾客 ${h.externalUserid.take(8)}")
                    announcements += "有一条待人工消息"
                }
            }
            settings.saveSeenIds(KIND_HANDOFFS, ids)
        } catch (e: UnauthorizedException) {
            throw e
        } catch (_: Exception) {
        }

        if (announcements.isNotEmpty()) {
            playTone()
            if (settings.ttsEnabled) {
                delay(1200) // 等提示音响完再播报
                announcements.forEach { speak(it) }
            }
        }
    }

    // ---------- 通知 / 声音 / 播报 ----------

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
            NotificationManagerCompat.from(this).notify(nextNotifId++, n)
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

    private fun initTts() {
        tts = TextToSpeech(applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                try { tts?.setLanguage(Locale.SIMPLIFIED_CHINESE) } catch (_: Exception) {}
                ttsReady = true
                val queued: List<String> = synchronized(pendingSpeech) {
                    val q = pendingSpeech.toList()
                    pendingSpeech.clear()
                    q
                }
                queued.forEach { speak(it) }
            }
        }
    }

    private fun speak(text: String) {
        val engine = tts
        if (engine == null || !ttsReady) {
            synchronized(pendingSpeech) { pendingSpeech += text }
            return
        }
        try {
            engine.speak(text, TextToSpeech.QUEUE_ADD, null, "xd-${System.nanoTime()}")
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val CH_ONGOING = "xd_ongoing"
        private const val CH_ALERTS = "xd_alerts_v1"
        private const val ONGOING_ID = 1
        private const val KIND_ORDERS = "orders"
        private const val KIND_HANDOFFS = "handoffs"

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
