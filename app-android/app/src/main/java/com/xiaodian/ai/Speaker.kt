package com.xiaodian.ai

// 播报：优先用服务器人声，失败退回手机 TTS。
// - 设置里「使用服务器人声」开着，且 GET /health 返回 "tts": "on" 时，
//   POST {server}/tts/synthesize（Bearer，JSON {"text": "..."}）拿 mp3 再播放。
//   请求体格式取自后端 main.py：{"text": "...", "voice": 可选}，返回音频原始字节。
// - /health 结果缓存：tts 为 on 时 5 分钟；失败或 off 时只缓存 30 秒（0.3.0 失败也缓存 5 分钟）。
//   试听播报每次都重新检查。
// - 合成：连接 5 秒、整次请求 10 秒内拿不到音频就放弃，改用手机 TTS。
// - 同一句话的音频缓存在 cacheDir/tts/（按文字的 SHA-256 命名），最多保留 200 个文件。
// - 一句一句按顺序播：上一句播完再播下一句（服务器音频和手机 TTS 混用时也不会抢）。
// 0.3.1 改动（播报诊断）：
// - 每次播报把每一步的结果记进 SpeechReport，播完写进 SharedPreferences（见 SpeechDiag.kt）。
// - MediaPlayer 等待超时不再当成功（0.3.0 是 `?: true`），超时/出错都退回手机 TTS。
// - 手机 TTS 初始化失败后下次播报会重新初始化（0.3.0 失败一次就永远不再播）；setLanguage 结果记下来，
//   缺数据/不支持时提示安装中文语音引擎。
// - 音频属性统一用 USAGE_ASSISTANCE_NAVIGATION_GUIDANCE + CONTENT_TYPE_SPEECH：
//   音量跟媒体音量走，但系统把它归为「不可被勿扰屏蔽」的用途（USAGE_MEDIA 在勿扰「不允许媒体」时会被静音），
//   并且会让正在放的音乐临时压低音量，而不是被音乐盖住。
// - 播放前申请临时音频焦点（可压低其它声音），播完释放。
// - ttsHttp 不再从 ApiClient.http 派生，单独一个 OkHttpClient（不共用连接池，避免和 SSE 长连接复用同一个
//   HTTP/2 连接时被卡住）；服务器人声返回 401 不再把整个 App 登出，只记进诊断并改用手机 TTS。
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class Speaker(private val context: Context) {
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = CompletableDeferred<Boolean>()
    @Volatile private var initStatus: Int? = null
    @Volatile private var langResult: Int? = null
    @Volatile private var initError: String = ""
    @Volatile private var initFailed = false
    /** utteranceId → 回调结果（"onDone" / "onError …"）。 */
    private val utterances = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val started = ConcurrentHashMap.newKeySet<String>()
    private val playLock = Mutex()

    @Volatile private var healthCheckedAt = 0L
    @Volatile private var serverTtsOn = false
    @Volatile private var healthDesc = "未检查"

    private val attrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** 服务器人声专用，独立于 ApiClient.http（不继承它的连接池和超时）。 */
    private val ttsHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    fun init() {
        val deferred = CompletableDeferred<Boolean>()
        ttsReady = deferred
        initStatus = null
        langResult = null
        initError = ""
        initFailed = false
        var created: TextToSpeech? = null
        try {
            created = TextToSpeech(context.applicationContext) { status ->
                onInit(created ?: tts, status, deferred)
            }
            tts = created
        } catch (e: Exception) {
            initError = "${e.javaClass.simpleName}: ${e.message ?: ""}"
            initFailed = true
            deferred.complete(false)
        }
    }

    private fun onInit(engine: TextToSpeech?, status: Int, deferred: CompletableDeferred<Boolean>) {
        initStatus = status
        if (status != TextToSpeech.SUCCESS || engine == null) {
            initFailed = true
            deferred.complete(false)
            return
        }
        try {
            langResult = engine.setLanguage(Locale.SIMPLIFIED_CHINESE)
        } catch (e: Exception) {
            initError = "setLanguage 异常 ${e.javaClass.simpleName}: ${e.message ?: ""}"
        }
        try { engine.setAudioAttributes(attrs) } catch (_: Exception) {}
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { if (utteranceId != null) started += utteranceId }
            override fun onDone(utteranceId: String?) { finish(utteranceId, "onDone") }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { finish(utteranceId, "onError") }
            override fun onError(utteranceId: String?, errorCode: Int) {
                finish(utteranceId, "onError ${SpeechDiagStore.ttsErrorText(errorCode)}")
            }
        })
        // 缺数据 / 不支持也继续：有的引擎报缺数据但仍能出声，结果写进诊断。
        deferred.complete(true)
    }

    private fun finish(id: String?, result: String) {
        if (id != null) utterances.remove(id)?.complete(result)
    }

    fun shutdown() {
        try { tts?.stop() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null
    }

    /**
     * 按顺序播报若干句。挂起直到播完（或超时）。
     * report 为 null 时按「新单」记一份诊断；播完写进 SharedPreferences。
     */
    suspend fun speakAll(texts: List<String>, report: SpeechReport? = null, forceHealth: Boolean = false) {
        val r = report ?: SpeechReport("新单", isTest = false)
        playLock.withLock {
            try {
                val s = ApiClient.settings
                r.ttsSwitch = if (s.ttsEnabled) "开" else "关"
                r.serverTtsSwitch = if (s.serverTtsEnabled) "开" else "关"
                texts.forEachIndexed { i, t ->
                    val res = try { speakOne(t, r, forceHealth && i == 0) } catch (e: Exception) {
                        "出错 ${e.javaClass.simpleName}: ${e.message ?: ""}"
                    }
                    r.outcomes += if (texts.size > 1) "第 ${i + 1} 句：$res" else res
                }
            } finally {
                SpeechDiagStore.fillSystemState(context, r)
                SpeechDiagStore.save(context, r)
            }
        }
    }

    /** 返回这句话的结果描述。 */
    private suspend fun speakOne(text: String, r: SpeechReport, forceHealth: Boolean): String {
        val settings = ApiClient.settings
        if (settings.serverTtsEnabled) {
            if (serverTtsAvailable(r, forceHealth)) {
                val file = withContext(Dispatchers.IO) { fetchAudio(text, r) }
                if (file != null && playFile(file, r)) return "服务器人声播放完成"
            }
        } else {
            r.health = "未检查（服务器人声开关关闭）"
        }
        return speakWithPhone(text, r)
    }

    // ---------- 服务器人声 ----------

    private suspend fun serverTtsAvailable(r: SpeechReport, force: Boolean): Boolean {
        val now = System.currentTimeMillis()
        val ttl = if (serverTtsOn) HEALTH_TTL_MS else HEALTH_FAIL_TTL_MS
        if (!force && now - healthCheckedAt < ttl) {
            r.health = "$healthDesc（${(now - healthCheckedAt) / 1000} 秒前的缓存结果）"
            return serverTtsOn
        }
        var desc = ""
        val on = withContext(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            try {
                val req = Request.Builder().url(ApiClient.url("/health")).get().also {
                    ApiClient.authProvider.authorize(it)
                }.build()
                ttsHttp.newCall(req).execute().use { resp ->
                    val ms = System.currentTimeMillis() - t0
                    val body = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) {
                        desc = "HTTP ${resp.code}，${ms}ms，tts 字段：—"
                        return@use false
                    }
                    val tv = try { JSONObject(body).opt("tts")?.toString() ?: "（没有 tts 字段）" } catch (_: Exception) {
                        "（不是 JSON：${body.take(60)}）"
                    }
                    desc = "HTTP ${resp.code}，${ms}ms，tts 字段：$tv"
                    tv.equals("on", ignoreCase = true)
                }
            } catch (e: Exception) {
                desc = "异常 ${e.javaClass.simpleName}: ${e.message ?: ""}（${System.currentTimeMillis() - t0}ms）"
                false
            }
        }
        serverTtsOn = on
        healthCheckedAt = now
        healthDesc = desc
        r.health = desc
        if (!on) r.problems += "服务器人声不可用，已改用手机语音"
        return on
    }

    private fun cacheDir(): File = File(context.cacheDir, "tts").apply { mkdirs() }

    private fun cacheFileFor(text: String): File {
        val md = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val name = md.joinToString("") { "%02x".format(it) }
        return File(cacheDir(), "$name.mp3")
    }

    /** 返回本地 mp3 文件；失败返回 null。命中缓存不发请求。 */
    private fun fetchAudio(text: String, r: SpeechReport): File? {
        val target = cacheFileFor(text)
        if (target.exists() && target.length() > 0) {
            target.setLastModified(System.currentTimeMillis())
            r.synth = "命中本地缓存（${target.length()} 字节），未请求"
            return target
        }
        val t0 = System.currentTimeMillis()
        return try {
            val body = JSONObject().put("text", text).toString()
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url(ApiClient.url("/tts/synthesize")).post(body).also {
                ApiClient.authProvider.authorize(it)
            }.build()
            ttsHttp.newCall(req).execute().use { resp ->
                val ct = resp.header("Content-Type") ?: ""
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                val ms = System.currentTimeMillis() - t0
                r.synth = "HTTP ${resp.code}，${ms}ms，Content-Type：${ct.ifBlank { "—" }}，${bytes.size} 字节"
                if (!resp.isSuccessful) {
                    // 401 不在这里登出（其它接口会处理登录失效），只改用手机播报。
                    val detail = String(bytes, Charsets.UTF_8).take(80)
                    if (detail.isNotBlank()) r.synth += "，返回：$detail"
                    r.problems += "服务器合成失败（HTTP ${resp.code}），已改用手机语音"
                    return null
                }
                if (ct.startsWith("application/json") || ct.startsWith("text/") || bytes.isEmpty()) {
                    r.problems += "服务器返回的不是音频，已改用手机语音"
                    return null
                }
                val tmp = File(target.parentFile, target.name + ".tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(target)) { tmp.delete(); return null }
                pruneCache()
                target
            }
        } catch (e: Exception) {
            r.synth = "异常 ${e.javaClass.simpleName}: ${e.message ?: ""}（${System.currentTimeMillis() - t0}ms）"
            r.problems += "服务器合成请求失败，已改用手机语音"
            null
        }
    }

    private fun pruneCache() {
        val files = cacheDir().listFiles { f -> f.name.endsWith(".mp3") } ?: return
        if (files.size <= MAX_CACHE_FILES) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_CACHE_FILES).forEach { it.delete() }
    }

    /** 播放 mp3；播完返回 true；出错或超时返回 false（调用方改用手机 TTS）。 */
    private suspend fun playFile(file: File, r: SpeechReport): Boolean {
        val done = CompletableDeferred<String>() // "ok" 或错误描述
        var durationMs = 0
        val player = withContext(Dispatchers.Main) {
            try {
                MediaPlayer().apply {
                    setAudioAttributes(attrs)
                    setDataSource(file.absolutePath)
                    setOnCompletionListener { done.complete("ok") }
                    setOnErrorListener { _, what, extra -> done.complete("onError what=$what extra=$extra"); true }
                    prepare()
                    durationMs = duration
                }
            } catch (e: Exception) {
                r.player = "准备失败 ${e.javaClass.simpleName}: ${e.message ?: ""}"
                null
            }
        }
        if (player == null) {
            file.delete() // 文件可能损坏，下次重新合成
            r.problems += "服务器音频无法播放，已改用手机语音"
            return false
        }
        val focus = requestFocus(r)
        withContext(Dispatchers.Main) { try { player.start() } catch (e: Exception) { done.complete("start 失败 ${e.javaClass.simpleName}") } }
        val limit = if (durationMs > 0) (durationMs + 5_000L).coerceAtMost(MAX_PLAY_MS * 2) else MAX_PLAY_MS
        val res = withTimeoutOrNull(limit) { done.await() } ?: "超时（${limit}ms 没有播完）"
        withContext(Dispatchers.Main) {
            try { player.stop() } catch (_: Exception) {}
            try { player.release() } catch (_: Exception) {}
        }
        abandonFocus(focus)
        val ok = res == "ok"
        r.player = if (ok) "播放成功（时长 ${durationMs}ms）" else "播放失败：$res（时长 ${durationMs}ms）"
        if (!ok) {
            file.delete()
            r.problems += "服务器音频没播出来，已改用手机语音"
        }
        return ok
    }

    // ---------- 音频焦点 ----------

    private fun requestFocus(r: SpeechReport): AudioFocusRequest? = try {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .build()
        val res = am.requestAudioFocus(req)
        r.focus = when (res) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> "已获得"
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> "延迟（$res）"
            else -> "被拒绝（$res，可能正在通话）"
        }
        req
    } catch (e: Exception) {
        r.focus = "申请失败 ${e.javaClass.simpleName}"
        null
    }

    private fun abandonFocus(req: AudioFocusRequest?) {
        if (req == null) return
        try {
            (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(req)
        } catch (_: Exception) {}
    }

    // ---------- 手机 TTS ----------

    private suspend fun speakWithPhone(text: String, r: SpeechReport): String {
        // 上次初始化失败（或从没初始化）：重新初始化一次。
        if (ttsReady.isCompleted && initFailed) {
            withContext(Dispatchers.Main) { shutdown(); init() }
        }
        val ready = withTimeoutOrNull(10_000) { ttsReady.await() }
        val engine = tts
        r.phoneInit = when {
            ready == null -> "10 秒内没有完成初始化（引擎没有响应）"
            initStatus == TextToSpeech.SUCCESS -> "SUCCESS（0）"
            initStatus != null -> "失败 status=$initStatus${if (initError.isNotBlank()) "，$initError" else ""}"
            else -> "失败：$initError"
        }
        r.phoneLang = langResult?.let { SpeechDiagStore.langResultText(it) }
            ?: (if (initError.isNotBlank()) initError else "未调用（初始化没成功）")
        r.phoneEngine = describeEngines(engine)
        if (ready != true || engine == null) {
            r.problems += "手机语音引擎初始化失败，请安装中文语音引擎（如讯飞语记 / 系统自带「文字转语音」）"
            r.phoneSpeak = "未调用"
            return "没有声音：手机 TTS 不可用"
        }
        val lr = langResult
        if (lr == TextToSpeech.LANG_MISSING_DATA || lr == TextToSpeech.LANG_NOT_SUPPORTED) {
            r.problems += "手机语音引擎没有中文，请安装中文语音引擎"
        }
        try { engine.setAudioAttributes(attrs) } catch (_: Exception) {}
        val id = "xd-${System.nanoTime()}"
        val done = CompletableDeferred<String>()
        utterances[id] = done
        val focus = requestFocus(r)
        var speakErr = ""
        val ret = try { engine.speak(text, TextToSpeech.QUEUE_ADD, null, id) } catch (e: Exception) {
            speakErr = "异常 ${e.javaClass.simpleName}: ${e.message ?: ""}"
            TextToSpeech.ERROR
        }
        r.phoneSpeak = when {
            speakErr.isNotBlank() -> speakErr
            ret == TextToSpeech.SUCCESS -> "SUCCESS（0）"
            else -> "ERROR（$ret）"
        }
        if (ret != TextToSpeech.SUCCESS) {
            utterances.remove(id)
            abandonFocus(focus)
            r.phoneCallback = "没有（speak 失败）"
            return "没有声音：手机 TTS speak 返回 $ret"
        }
        val limit = maxOf(MAX_PLAY_MS, text.length * 500L)
        val cb = withTimeoutOrNull(limit) { done.await() }
        utterances.remove(id)
        val gotStart = started.remove(id)
        abandonFocus(focus)
        r.phoneCallback = when {
            cb == null -> "${limit / 1000} 秒内没有收到 onDone/onError（onStart：${if (gotStart) "收到" else "没收到"}）"
            else -> "$cb（onStart：${if (gotStart) "收到" else "没收到"}）"
        }
        return when {
            cb == "onDone" -> "手机语音播放完成"
            cb == null -> "手机语音没有回调，可能没出声"
            else -> "手机语音出错：$cb"
        }
    }

    private fun describeEngines(engine: TextToSpeech?): String {
        if (engine == null) return "—"
        return try {
            val def = engine.defaultEngine ?: "—"
            val all = engine.engines.map { it.name }
            if (all.isEmpty()) "默认 $def；系统里找不到任何 TTS 引擎" else "默认 $def；已安装 ${all.joinToString()}"
        } catch (e: Exception) {
            "读取失败 ${e.javaClass.simpleName}"
        }
    }

    companion object {
        private const val HEALTH_TTL_MS = 5 * 60_000L
        private const val HEALTH_FAIL_TTL_MS = 30_000L
        private const val MAX_CACHE_FILES = 200
        private const val MAX_PLAY_MS = 20_000L
    }
}
