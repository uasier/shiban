package com.shiban.app.data

import android.content.Context

class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("shiban_settings", Context.MODE_PRIVATE)

    var serverUrl: String
        get() {
            val saved = prefs.getString(KEY_SERVER, "").orEmpty()
            val resolved = normalizeServer(saved)
            if (resolved != saved.trim().trimEnd('/')) {
                prefs.edit().putString(KEY_SERVER, resolved).apply()
            }
            return resolved
        }
        set(value) = prefs.edit().putString(KEY_SERVER, normalizeServer(value)).apply()

    var token: String
        get() = prefs.getString(KEY_TOKEN, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_TOKEN, value.trim()).apply()

    var username: String
        get() = prefs.getString(KEY_USER, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_USER, value.trim()).apply()

    var userId: String
        get() = prefs.getString(KEY_USER_ID, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_USER_ID, value.trim()).apply()

    var displayName: String
        get() = prefs.getString(KEY_DISPLAY, "").orEmpty().ifBlank { username }
        set(value) = prefs.edit().putString(KEY_DISPLAY, value.trim()).apply()

    var aiBaseUrl: String
        get() = prefs.getString(KEY_AI_URL, DEFAULT_AI_URL).orEmpty().ifBlank { DEFAULT_AI_URL }
        set(value) = prefs.edit().putString(KEY_AI_URL, value.trim().trimEnd('/')).apply()

    var aiModel: String
        get() = prefs.getString(KEY_AI_MODEL, DEFAULT_AI_MODEL).orEmpty().ifBlank { DEFAULT_AI_MODEL }
        set(value) = prefs.edit().putString(KEY_AI_MODEL, value.trim()).apply()

    var aiApiKey: String
        get() = prefs.getString(KEY_AI_KEY, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_AI_KEY, value.trim()).apply()

    var seenVersionCode: Int
        get() = prefs.getInt(KEY_SEEN_VERSION, 0)
        set(value) = prefs.edit().putInt(KEY_SEEN_VERSION, value).apply()

    var updateCheckedAt: Long
        get() = prefs.getLong(KEY_UPDATE_CHECKED, 0L)
        set(value) = prefs.edit().putLong(KEY_UPDATE_CHECKED, value).apply()

    val isLoggedIn: Boolean
        get() = token.isNotBlank() && serverUrl.isNotBlank()

    fun applyAi(baseUrl: String, model: String, apiKey: String) {
        aiBaseUrl = baseUrl
        aiModel = model
        aiApiKey = apiKey
    }

    fun logout() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_USER)
            .remove(KEY_USER_ID)
            .remove(KEY_DISPLAY)
            .remove(KEY_AI_URL)
            .remove(KEY_AI_MODEL)
            .remove(KEY_AI_KEY)
            .apply()
    }

    fun expireSession() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    companion object {
        const val PUBLIC_SERVER = "https://shiban.uasier.com:40400"
        const val DEFAULT_SERVER = PUBLIC_SERVER
        const val DEFAULT_AI_URL = "https://api.x.ai/v1"
        const val DEFAULT_AI_MODEL = "grok-4.7"
        const val DEEPSEEK_AI_URL = "https://api.deepseek.com"
        const val DEEPSEEK_AI_MODEL = "deepseek-chat"

        fun normalizeServer(value: String?): String {
            val raw = value.orEmpty().trim().trimEnd('/')
            if (raw.isBlank() || isLocalServer(raw)) return PUBLIC_SERVER
            return raw
        }

        private fun isLocalServer(value: String): Boolean {
            val authority = value.substringAfter("://", value).substringBefore('/').lowercase()
            val host = authority.substringBefore(':')
            val port = authority.substringAfter(':', "")
            if (host == "154.36.168.192" || host == "shiban.uasier.com") return true
            if (host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2" || host == "0.0.0.0") return true
            if (host.startsWith("192.168.") || host.startsWith("10.")) return true
            return port == "8787" || port == "18787"
        }

        private const val KEY_SERVER = "server_url"
        private const val KEY_TOKEN = "token"
        private const val KEY_USER = "username"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_DISPLAY = "display_name"
        private const val KEY_AI_URL = "ai_base_url"
        private const val KEY_AI_MODEL = "ai_model"
        private const val KEY_AI_KEY = "ai_api_key"
        private const val KEY_SEEN_VERSION = "seen_version_code"
        private const val KEY_UPDATE_CHECKED = "update_checked_at"
    }
}
