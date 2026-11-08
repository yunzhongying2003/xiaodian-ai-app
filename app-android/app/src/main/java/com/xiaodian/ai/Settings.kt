package com.xiaodian.ai

// 本机设置：后端地址、设备密钥、操作 PIN、当前模式、提醒/播报开关。
// 全部存在 SharedPreferences（文件名 xiaodian，沿用旧版键名，升级后不丢配置）。
import android.content.Context
import android.content.SharedPreferences

class AppSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER, DEFAULT_SERVER) ?: DEFAULT_SERVER
        set(value) { prefs.edit().putString(KEY_SERVER, value).apply() }

    var deviceKey: String
        get() = prefs.getString(KEY_DEVICE_KEY, "") ?: ""
        set(value) { prefs.edit().putString(KEY_DEVICE_KEY, value).apply() }

    var devicePin: String
        get() = prefs.getString(KEY_DEVICE_PIN, "") ?: ""
        set(value) { prefs.edit().putString(KEY_DEVICE_PIN, value).apply() }

    var mode: AppMode
        get() = if (prefs.getString(KEY_MODE, AppMode.KITCHEN.name) == AppMode.BOSS.name) AppMode.BOSS else AppMode.KITCHEN
        set(value) { prefs.edit().putString(KEY_MODE, value.name).apply() }

    /** 新单 / 待人工提醒（前台服务 + 通知 + 提示音）。 */
    var alertsEnabled: Boolean
        get() = prefs.getBoolean(KEY_ALERTS, true)
        set(value) { prefs.edit().putBoolean(KEY_ALERTS, value).apply() }

    /** 提醒时是否用 TTS 语音播报。 */
    var ttsEnabled: Boolean
        get() = prefs.getBoolean(KEY_TTS, true)
        set(value) { prefs.edit().putBoolean(KEY_TTS, value).apply() }

    // 提醒服务已经见过的 id，用来判断「新」单 / 「新」待人工。
    fun seenIds(kind: String): Set<String> = prefs.getStringSet("seen_$kind", null)?.toSet() ?: emptySet()
    fun hasSeenBaseline(kind: String): Boolean = prefs.getBoolean("seen_init_$kind", false)
    fun saveSeenIds(kind: String, ids: Set<String>) {
        prefs.edit()
            .putStringSet("seen_$kind", HashSet(ids))
            .putBoolean("seen_init_$kind", true)
            .apply()
    }

    companion object {
        const val PREFS_NAME = "xiaodian"
        const val DEFAULT_SERVER = "https://kf.golook.eu.org"
        private const val KEY_SERVER = "server_base_url"
        private const val KEY_DEVICE_KEY = "device_key"
        private const val KEY_DEVICE_PIN = "device_pin"
        private const val KEY_MODE = "app_mode"
        private const val KEY_ALERTS = "alerts_enabled"
        private const val KEY_TTS = "tts_enabled"
    }
}
