package com.xiaodian.ai

// 播报诊断（0.3.1）：每次播报（真实新单或设置页「试听播报」）把整条链路的结果记下来，
// 设置页显示，可以复制给开发排查。
// - 真实播报：写进 SharedPreferences（speech_diag_real_*），设置页显示「最近一次播报：时间 + 摘要」。
// - 试听：写进 speech_diag_test_*，设置页按钮下方显示完整诊断。
// - 两者都推到 SpeechDiagStore 的 StateFlow，设置页打开时实时更新。
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一次播报（一批句子）的诊断。字段都是给人看的中文短句。 */
class SpeechReport(val source: String, val isTest: Boolean) {
    val startedAt: Long = System.currentTimeMillis()
    var ttsSwitch: String = "—"
    var serverTtsSwitch: String = "—"
    var health: String = "未检查"
    var synth: String = "未请求"
    var player: String = "未使用"
    var phoneInit: String = "—"
    var phoneLang: String = "—"
    var phoneEngine: String = "—"
    var phoneSpeak: String = "未使用"
    var phoneCallback: String = "—"
    var focus: String = "—"
    var volume: String = "—"
    var dnd: String = "—"
    /** 每句的结果，如「第 1 句：服务器人声播放完成」。 */
    val outcomes = mutableListOf<String>()
    /** 需要用户处理的问题（摘要里优先显示）。 */
    val problems = LinkedHashSet<String>()

    fun render(): String = buildString {
        appendLine("时间：${SpeechDiagStore.fmt(startedAt)}（$source）")
        appendLine("结果：${outcomes.joinToString("；").ifBlank { "没有播报" }}")
        if (problems.isNotEmpty()) appendLine("问题：${problems.joinToString("；")}")
        appendLine("语音播报开关：$ttsSwitch")
        appendLine("服务器人声开关：$serverTtsSwitch")
        appendLine("/health：$health")
        appendLine("/tts/synthesize：$synth")
        appendLine("MediaPlayer：$player")
        appendLine("手机 TTS 初始化：$phoneInit")
        appendLine("手机 TTS setLanguage(zh-CN)：$phoneLang")
        appendLine("手机 TTS 引擎：$phoneEngine")
        appendLine("手机 TTS speak：$phoneSpeak")
        appendLine("手机 TTS 回调：$phoneCallback")
        appendLine("音频焦点：$focus")
        appendLine("媒体音量（STREAM_MUSIC）：$volume")
        append("勿扰模式：$dnd")
    }

    fun summary(): String {
        val main = outcomes.joinToString("；").ifBlank { "没有播报" }
        return if (problems.isEmpty()) main else "$main。${problems.joinToString("；")}"
    }
}

object SpeechDiagStore {
    private const val K_REAL_TIME = "speech_diag_real_time"
    private const val K_REAL_SUMMARY = "speech_diag_real_summary"
    private const val K_REAL_TEXT = "speech_diag_real_text"
    private const val K_TEST_TEXT = "speech_diag_test_text"

    data class Snapshot(
        val realTime: Long,
        val realSummary: String,
        val realText: String,
        val testText: String,
        val testRunning: Boolean,
    )

    private val _state = MutableStateFlow(Snapshot(0L, "", "", "", false))
    val state: StateFlow<Snapshot> = _state.asStateFlow()
    @Volatile private var loaded = false

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        if (loaded) return
        val p = prefs(ctx)
        _state.value = Snapshot(
            realTime = p.getLong(K_REAL_TIME, 0L),
            realSummary = p.getString(K_REAL_SUMMARY, "") ?: "",
            realText = p.getString(K_REAL_TEXT, "") ?: "",
            testText = p.getString(K_TEST_TEXT, "") ?: "",
            testRunning = _state.value.testRunning,
        )
        loaded = true
    }

    fun markTestRunning(ctx: Context) {
        load(ctx)
        _state.value = _state.value.copy(testRunning = true, testText = "正在试听…")
    }

    /** 试听 60 秒还没有结果（服务正在停止等）：解除「正在试听」。 */
    fun testTimedOut(ctx: Context) {
        load(ctx)
        if (!_state.value.testRunning) return
        val text = "试听 60 秒内没有结果：播报没有执行完（可能后台服务正在停止）。请再点一次试听。"
        _state.value = _state.value.copy(testRunning = false, testText = text)
    }

    fun save(ctx: Context, r: SpeechReport) {
        load(ctx)
        val text = r.render()
        if (r.isTest) {
            prefs(ctx).edit().putString(K_TEST_TEXT, text).apply()
            _state.value = _state.value.copy(testText = text, testRunning = false)
        } else {
            prefs(ctx).edit()
                .putLong(K_REAL_TIME, r.startedAt)
                .putString(K_REAL_SUMMARY, r.summary())
                .putString(K_REAL_TEXT, text)
                .apply()
            _state.value = _state.value.copy(realTime = r.startedAt, realSummary = r.summary(), realText = text)
        }
    }

    fun fmt(ms: Long): String =
        if (ms <= 0) "—" else SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(ms))

    // ---------- 系统状态 ----------

    fun fillSystemState(ctx: Context, r: SpeechReport) {
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            r.volume = "$cur / $max"
            if (cur == 0) r.problems += "媒体音量为 0，播报没有声音（请调高媒体音量）"
        } catch (e: Exception) {
            r.volume = "读取失败：${e.javaClass.simpleName} ${e.message ?: ""}"
        }
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            r.dnd = when (val f = nm.currentInterruptionFilter) {
                NotificationManager.INTERRUPTION_FILTER_ALL -> "关闭（全部允许，$f）"
                NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "开启：仅优先打扰（$f）"
                NotificationManager.INTERRUPTION_FILTER_ALARMS -> "开启：仅闹钟（$f）"
                NotificationManager.INTERRUPTION_FILTER_NONE -> "开启：完全静音（$f）"
                else -> "未知（$f）"
            }
        } catch (e: Exception) {
            r.dnd = "读取失败：${e.javaClass.simpleName} ${e.message ?: ""}"
        }
    }

    fun langResultText(code: Int): String = when (code) {
        TextToSpeech.LANG_AVAILABLE -> "$code 可用（LANG_AVAILABLE）"
        TextToSpeech.LANG_COUNTRY_AVAILABLE -> "$code 可用（LANG_COUNTRY_AVAILABLE）"
        TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> "$code 可用（LANG_COUNTRY_VAR_AVAILABLE）"
        TextToSpeech.LANG_MISSING_DATA -> "$code 缺少中文语音数据（LANG_MISSING_DATA），请安装中文语音引擎或下载中文语音包"
        TextToSpeech.LANG_NOT_SUPPORTED -> "$code 当前引擎不支持中文（LANG_NOT_SUPPORTED），请安装中文语音引擎"
        else -> "$code 未知返回值"
    }

    fun ttsErrorText(code: Int): String = when (code) {
        TextToSpeech.ERROR_SYNTHESIS -> "$code 合成失败（ERROR_SYNTHESIS）"
        TextToSpeech.ERROR_SERVICE -> "$code 引擎服务出错（ERROR_SERVICE）"
        TextToSpeech.ERROR_OUTPUT -> "$code 音频输出失败（ERROR_OUTPUT）"
        TextToSpeech.ERROR_NETWORK -> "$code 网络错误（ERROR_NETWORK）"
        TextToSpeech.ERROR_NETWORK_TIMEOUT -> "$code 网络超时（ERROR_NETWORK_TIMEOUT）"
        TextToSpeech.ERROR_INVALID_REQUEST -> "$code 请求无效（ERROR_INVALID_REQUEST）"
        TextToSpeech.ERROR_NOT_INSTALLED_YET -> "$code 语音数据还没装好（ERROR_NOT_INSTALLED_YET），请安装中文语音引擎"
        TextToSpeech.ERROR -> "$code 一般错误（ERROR）"
        else -> "$code"
    }
}
