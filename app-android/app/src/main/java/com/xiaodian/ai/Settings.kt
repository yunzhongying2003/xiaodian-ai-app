package com.xiaodian.ai

// 本机设置：后端地址、登录 token、当前模式、提醒/播报开关。
// 全部存在 SharedPreferences（文件名 xiaodian，MODE_PRIVATE，沿用旧版键名）。
// token 暂存普通 SharedPreferences（应用私有目录，其它 App 读不到；未用
// EncryptedSharedPreferences，避免新增依赖的编译风险和部分机型 Keystore 崩溃）。
import android.content.Context
import android.content.SharedPreferences

class AppSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER, DEFAULT_SERVER) ?: DEFAULT_SERVER
        set(value) { prefs.edit().putString(KEY_SERVER, value).apply() }

    /** 登录 token；为空表示未登录。 */
    var token: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) { prefs.edit().putString(KEY_TOKEN, value).apply() }

    var username: String
        get() = prefs.getString(KEY_USERNAME, "") ?: ""
        set(value) { prefs.edit().putString(KEY_USERNAME, value).apply() }

    /** 后端返回的 expires_at 原文。 */
    var tokenExpiresAt: String
        get() = prefs.getString(KEY_EXPIRES, "") ?: ""
        set(value) { prefs.edit().putString(KEY_EXPIRES, value).apply() }

    fun saveSession(token: String, username: String, expiresAt: String) {
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_USERNAME, username)
            .putString(KEY_EXPIRES, expiresAt)
            .apply()
    }

    fun clearSession() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_EXPIRES).apply()
    }

    /** 清掉旧版的设备密钥 / PIN（改成登录后不再使用）。 */
    fun clearLegacySecrets() {
        prefs.edit().remove("device_key").remove("device_pin").apply()
    }

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
        private const val KEY_TOKEN = "auth_token"
        private const val KEY_USERNAME = "auth_username"
        private const val KEY_EXPIRES = "auth_expires_at"
        private const val KEY_MODE = "app_mode"
        private const val KEY_ALERTS = "alerts_enabled"
        private const val KEY_TTS = "tts_enabled"
    }
}
