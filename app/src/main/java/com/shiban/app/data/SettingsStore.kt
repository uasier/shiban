package com.shiban.app.data

import android.content.Context

class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("shiban_settings", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER, DEFAULT_SERVER).orEmpty().ifBlank { DEFAULT_SERVER }
        set(value) = prefs.edit().putString(KEY_SERVER, value.trim().trimEnd('/')).apply()

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

    companion object {
        const val DEFAULT_SERVER = "http://10.0.2.2:8787"
        const val DEFAULT_AI_URL = "https://api.x.ai/v1"
        const val DEFAULT_AI_MODEL = "grok-4.7"

        private const val KEY_SERVER = "server_url"
        private const val KEY_TOKEN = "token"
        private const val KEY_USER = "username"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_DISPLAY = "display_name"
        private const val KEY_AI_URL = "ai_base_url"
        private const val KEY_AI_MODEL = "ai_model"
        private const val KEY_AI_KEY = "ai_api_key"
    }
}
