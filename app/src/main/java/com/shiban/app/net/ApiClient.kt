package com.shiban.app.net

import android.util.Base64
import com.shiban.app.data.SettingsStore
import com.shiban.app.data.TodoDraft
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

class ApiException(message: String, val httpCode: Int = 0) : Exception(message)

data class AiConfig(
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val configured: Boolean,
    val source: String
)

data class AccountProfile(
    val id: String,
    val username: String,
    val displayName: String,
    val ai: AiConfig = AiConfig(SettingsStore.DEFAULT_AI_URL, SettingsStore.DEFAULT_AI_MODEL, "", false, "none")
)

data class RemoteTodo(
    val id: String,
    val content: String,
    val timeMillis: Long?,
    val timeText: String?,
    val location: String?,
    val importance: String,
    val necessity: String,
    val note: String?,
    val sourceText: String?,
    val imageUrl: String?,
    val createdAt: Long,
    val completedAt: Long?,
    val updatedAt: Long = 0,
    val imageUrls: List<String> = emptyList()
)

class ApiClient(private val settings: SettingsStore) {
    fun register(username: String, password: String, displayName: String): AccountProfile =
        auth("/api/auth/register", username, password, displayName)

    fun login(username: String, password: String): AccountProfile =
        auth("/api/auth/login", username, password, "")

    fun me(): AccountProfile {
        val json = request("GET", "/api/me", null)
        return json.toProfile()
    }

    fun updateProfile(displayName: String): AccountProfile {
        val json = request("PATCH", "/api/me", JSONObject().put("displayName", displayName))
        return remember(json.toProfile())
    }

    fun saveAi(baseUrl: String, model: String, apiKey: String): AccountProfile {
        val ai = JSONObject()
            .put("baseUrl", baseUrl.trim())
            .put("model", model.trim())
            .put("apiKey", apiKey.trim())
        val json = request("PATCH", "/api/me", JSONObject().put("ai", ai))
        return remember(json.toProfile())
    }

    fun changePassword(oldPassword: String, newPassword: String) {
        request(
            "POST",
            "/api/auth/password",
            JSONObject().put("oldPassword", oldPassword).put("newPassword", newPassword)
        )
    }

    fun health(): String {
        val json = request("GET", "/api/health", null, auth = false)
        return if (json.optBoolean("modelConfigured")) "服务可用，模型已配置" else "服务可用，但还没配置模型"
    }

    fun ingest(text: String?, imagePaths: List<String>): Int {
        val json = request("POST", "/api/ingest", shareBody(text, imagePaths), readTimeout = 90_000)
        val count = json.optInt("count")
        if (count == 0 && json.optBoolean("duplicate")) throw ApiException("还在待办里", 409)
        return count
    }

    fun parse(text: String?, imagePaths: List<String>): String {
        val json = request("POST", "/api/parse", shareBody(text, imagePaths), readTimeout = 180_000)
        return json.optString("raw").ifBlank { json.toString() }
    }

    private fun shareBody(text: String?, imagePaths: List<String>): JSONObject {
        val images = org.json.JSONArray()
        imagePaths.take(4).forEach { path ->
            images.put(Base64.encodeToString(File(path).readBytes(), Base64.NO_WRAP))
        }
        return JSONObject()
            .put("text", text ?: JSONObject.NULL)
            .put("images", images)
    }

    fun listTodos(): List<RemoteTodo> {
        val json = request("GET", "/api/todos", null)
        val array = json.optJSONArray("todos") ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { add(it.toRemote()) }
            }
        }
    }

    fun createTodo(draft: TodoDraft, completedAt: Long? = null, createdAt: Long? = null): RemoteTodo {
        val json = draft.toJson(includeImage = true)
        if (createdAt != null) json.put("createdAt", createdAt)
        if (completedAt != null) json.put("completedAt", completedAt)
        return request("POST", "/api/todos", json, readTimeout = 45_000).toRemote()
    }

    fun updateTodo(serverId: String, draft: TodoDraft, completedAt: Long?): RemoteTodo {
        val body = draft.toJson(includeImage = false).put("completedAt", completedAt ?: JSONObject.NULL)
        val json = request("PATCH", "/api/todos/$serverId", body)
        return json.toRemote()
    }

    fun deleteTodo(serverId: String) {
        request("DELETE", "/api/todos/$serverId", null)
    }

    fun downloadImage(imageUrl: String, dest: File) {
        val connection = open(imageUrl, "GET", auth = true, readTimeout = 30_000)
        try {
            if (connection.responseCode !in 200..299) throw ApiException("图片下载失败", connection.responseCode)
            dest.parentFile?.mkdirs()
            connection.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
        } finally {
            connection.disconnect()
        }
    }

    private fun auth(path: String, username: String, password: String, displayName: String): AccountProfile {
        val body = JSONObject().put("username", username.trim()).put("password", password)
        if (displayName.isNotBlank()) body.put("displayName", displayName.trim())
        val json = request("POST", path, body, auth = false)
        val token = json.optString("token")
        if (token.isBlank()) throw ApiException("登录没有返回凭证")
        val profile = json.optJSONObject("user")?.toProfile()
            ?: throw ApiException("登录没有返回账号")
        settings.token = token
        return remember(profile)
    }

    private fun remember(profile: AccountProfile): AccountProfile {
        settings.username = profile.username
        settings.userId = profile.id
        settings.displayName = profile.displayName
        settings.applyAi(profile.ai.baseUrl, profile.ai.model, profile.ai.apiKey)
        return profile
    }

    private fun request(
        method: String,
        path: String,
        body: JSONObject?,
        auth: Boolean = true,
        readTimeout: Int = 20_000
    ): JSONObject {
        val connection = open(path, method, auth, readTimeout)
        try {
            if (body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(bytes) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw ApiException(errorMessage(code, raw), code)
            if (raw.isBlank()) return JSONObject()
            return JSONObject(raw)
        } catch (error: ApiException) {
            throw error
        } catch (error: SocketTimeoutException) {
            throw ApiException("服务响应超时")
        } catch (error: UnknownHostException) {
            throw ApiException("连不上识办服务，请检查地址和网络")
        } catch (error: Exception) {
            throw ApiException(error.message ?: "请求失败")
        } finally {
            connection.disconnect()
        }
    }

    private fun open(path: String, method: String, auth: Boolean, readTimeout: Int = 20_000): HttpURLConnection {
        val url = if (path.startsWith("http")) path else settings.serverUrl.trimEnd('/') + path
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            this.readTimeout = readTimeout
            setRequestProperty("Accept", "application/json")
            if (auth) {
                if (settings.token.isBlank()) throw ApiException("请先登录")
                setRequestProperty("Authorization", "Bearer ${settings.token}")
            }
        }
    }

    private fun errorMessage(code: Int, raw: String): String {
        val message = try {
            JSONObject(raw).optString("error")
        } catch (_: Exception) {
            raw
        }.ifBlank { "请求失败" }
        return if (code == 401) "登录已失效，请重新登录" else message
    }

    private fun TodoDraft.toJson(includeImage: Boolean): JSONObject {
        val json = JSONObject()
            .put("content", content)
            .put("timeMillis", timeMillis ?: JSONObject.NULL)
            .put("timeText", timeText ?: JSONObject.NULL)
            .put("location", location ?: JSONObject.NULL)
            .put("importance", importance)
            .put("necessity", necessity)
            .put("note", note ?: JSONObject.NULL)
            .put("sourceText", sourceText ?: JSONObject.NULL)
        if (includeImage) {
            val files = imagePaths.filter { File(it).exists() }.take(4)
            if (files.isNotEmpty()) {
                val images = org.json.JSONArray()
                files.forEach { images.put(Base64.encodeToString(File(it).readBytes(), Base64.NO_WRAP)) }
                json.put("imageBase64", images.getString(0))
                json.put("imagesBase64", images)
            }
        }
        return json
    }

    private fun JSONObject.toProfile(): AccountProfile {
        val username = optString("username")
        return AccountProfile(
            id = optString("id"),
            username = username,
            displayName = optString("displayName").ifBlank { username },
            ai = optJSONObject("ai")?.let {
                AiConfig(
                    baseUrl = it.optString("baseUrl").ifBlank { SettingsStore.DEFAULT_AI_URL },
                    model = it.optString("model").ifBlank { SettingsStore.DEFAULT_AI_MODEL },
                    apiKey = it.optString("apiKey"),
                    configured = it.optBoolean("configured"),
                    source = it.optString("source")
                )
            } ?: AiConfig(SettingsStore.DEFAULT_AI_URL, SettingsStore.DEFAULT_AI_MODEL, "", false, "none")
        )
    }

    private fun JSONObject.toRemote(): RemoteTodo {
        return RemoteTodo(
            id = optString("id"),
            content = optString("content"),
            timeMillis = optLongOrNull("timeMillis"),
            timeText = optText("timeText"),
            location = optText("location"),
            importance = optString("importance").ifBlank { "medium" },
            necessity = optString("necessity").ifBlank { "should" },
            note = optText("note"),
            sourceText = optText("sourceText"),
            imageUrl = optText("imageUrl"),
            createdAt = optLong("createdAt"),
            completedAt = optLongOrNull("completedAt"),
            updatedAt = optLongOrNull("updatedAt") ?: 0L,
            imageUrls = imageUrls()
        )
    }

    private fun JSONObject.imageUrls(): List<String> {
        val array = optJSONArray("imageUrls")
        val urls = buildList {
            if (array != null) {
                for (index in 0 until array.length()) {
                    val url = array.optString(index).trim()
                    if (url.isNotEmpty()) add(url)
                }
            }
        }
        if (urls.isNotEmpty()) return urls
        return listOfNotNull(optText("imageUrl"))
    }

    private fun JSONObject.optText(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).trim().ifBlank { null }
    }

    private fun JSONObject.optLongOrNull(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        return optLong(key)
    }
}
