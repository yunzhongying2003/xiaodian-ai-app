package com.xiaodian.ai

// 播报：优先用服务器人声，失败退回手机 TTS。
// - 设置里「使用服务器人声」开着，且 GET /health 返回 "tts": "on" 时，
//   POST {server}/tts/synthesize（Bearer，JSON {"text": "..."}）拿 mp3 再播放。
//   请求体格式取自后端 main.py：{"text": "...", "voice": 可选}，返回音频原始字节。
// - /health 结果缓存 5 分钟；合成或下载失败、超过 5 秒、tts 不是 "on" 时，用手机 TTS。
// - 同一句话的音频缓存在 cacheDir/tts/（按文字的 SHA-256 命名），最多保留 200 个文件。
// - 一句一句按顺序播：上一句播完再播下一句（服务器音频和手机 TTS 混用时也不会抢）。
import android.content.Context
import android.media.AudioAttributes
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
    private var tts: TextToSpeech? = null
    private val ttsReady = CompletableDeferred<Boolean>()
    private val utterances = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val playLock = Mutex()

    @Volatile private var healthCheckedAt = 0L
    @Volatile private var serverTtsOn = false

    /** 服务器人声专用：总耗时 5 秒内拿不到音频就放弃。 */
    private val ttsHttp: OkHttpClient by lazy {
        ApiClient.http.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    fun init() {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                try { tts?.setLanguage(Locale.SIMPLIFIED_CHINESE) } catch (_: Exception) {}
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { finish(utteranceId) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { finish(utteranceId) }
                    override fun onError(utteranceId: String?, errorCode: Int) { finish(utteranceId) }
                })
                ttsReady.complete(true)
            } else {
                ttsReady.complete(false)
            }
        }
    }

    private fun finish(id: String?) {
        if (id != null) utterances.remove(id)?.complete(Unit)
    }

    fun shutdown() {
        try { tts?.stop() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null
    }

    /** 按顺序播报若干句。挂起直到播完（或超时）。 */
    suspend fun speakAll(texts: List<String>) {
        if (texts.isEmpty()) return
        playLock.withLock {
            for (t in texts) speakOne(t)
        }
    }

    private suspend fun speakOne(text: String) {
        val settings = ApiClient.settings
        if (settings.serverTtsEnabled && serverTtsAvailable()) {
            val file = withContext(Dispatchers.IO) { fetchAudio(text) }
            if (file != null && playFile(file)) return
        }
        speakWithPhone(text)
    }

    // ---------- 服务器人声 ----------

    private suspend fun serverTtsAvailable(): Boolean {
        val now = System.currentTimeMillis()
        if (now - healthCheckedAt < HEALTH_TTL_MS) return serverTtsOn
        val on = withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(ApiClient.url("/health")).get().also {
                    ApiClient.authProvider.authorize(it)
                }.build()
                ttsHttp.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use false
                    val body = resp.body?.string() ?: ""
                    JSONObject(body).optString("tts").equals("on", ignoreCase = true)
                }
            } catch (_: Exception) {
                false
            }
        }
        serverTtsOn = on
        healthCheckedAt = now
        return on
    }

    private fun cacheDir(): File = File(context.cacheDir, "tts").apply { mkdirs() }

    private fun cacheFileFor(text: String): File {
        val md = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val name = md.joinToString("") { "%02x".format(it) }
        return File(cacheDir(), "$name.mp3")
    }

    /** 返回本地 mp3 文件；失败返回 null。命中缓存不发请求。 */
    private fun fetchAudio(text: String): File? {
        val target = cacheFileFor(text)
        if (target.exists() && target.length() > 0) {
            target.setLastModified(System.currentTimeMillis())
            return target
        }
        return try {
            val body = JSONObject().put("text", text).toString()
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url(ApiClient.url("/tts/synthesize")).post(body).also {
                ApiClient.authProvider.authorize(it)
            }.build()
            ttsHttp.newCall(req).execute().use { resp ->
                if (resp.code == 401) {
                    // 登录失效走统一逻辑；这句话改用手机播报。
                    ApiClient.onUnauthorized()
                    return null
                }
                if (!resp.isSuccessful) return null
                val ct = resp.header("Content-Type") ?: ""
                if (ct.startsWith("application/json") || ct.startsWith("text/")) return null
                val bytes = resp.body?.bytes() ?: return null
                if (bytes.isEmpty()) return null
                val tmp = File(target.parentFile, target.name + ".tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(target)) { tmp.delete(); return null }
                pruneCache()
                target
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun pruneCache() {
        val files = cacheDir().listFiles { f -> f.name.endsWith(".mp3") } ?: return
        if (files.size <= MAX_CACHE_FILES) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_CACHE_FILES).forEach { it.delete() }
    }

    /** 播放 mp3；播完返回 true，出错返回 false（调用方会改用手机 TTS）。 */
    private suspend fun playFile(file: File): Boolean {
        val done = CompletableDeferred<Boolean>()
        val player = withContext(Dispatchers.Main) {
            try {
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    setDataSource(file.absolutePath)
                    setOnCompletionListener { done.complete(true) }
                    setOnErrorListener { _, _, _ -> done.complete(false); true }
                    prepare()
                    start()
                }
            } catch (_: Exception) {
                null
            }
        }
        if (player == null) {
            file.delete() // 文件可能损坏，下次重新合成
            return false
        }
        val ok = withTimeoutOrNull(MAX_PLAY_MS) { done.await() } ?: true
        withContext(Dispatchers.Main) { try { player.release() } catch (_: Exception) {} }
        if (!ok) file.delete()
        return ok
    }

    // ---------- 手机 TTS ----------

    private suspend fun speakWithPhone(text: String) {
        val ready = withTimeoutOrNull(10_000) { ttsReady.await() } ?: false
        val engine = tts
        if (!ready || engine == null) return
        val id = "xd-${System.nanoTime()}"
        val done = CompletableDeferred<Unit>()
        utterances[id] = done
        val r = try { engine.speak(text, TextToSpeech.QUEUE_ADD, null, id) } catch (_: Exception) { TextToSpeech.ERROR }
        if (r != TextToSpeech.SUCCESS) { utterances.remove(id); return }
        withTimeoutOrNull(MAX_PLAY_MS) { done.await() }
        utterances.remove(id)
    }

    companion object {
        private const val HEALTH_TTL_MS = 5 * 60_000L
        private const val MAX_CACHE_FILES = 200
        private const val MAX_PLAY_MS = 20_000L
    }
}
