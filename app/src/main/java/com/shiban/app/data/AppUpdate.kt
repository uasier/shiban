package com.shiban.app.data

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.shiban.app.BuildConfig
import com.shiban.app.net.ApiException
import com.shiban.app.util.Io
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object AppUpdate {
    data class Release(
        val version: String,
        val versionCode: Int,
        val notes: List<String>,
        val apkUrl: String
    )

    fun check(context: Context): Release {
        val server = SettingsStore(context).serverUrl.trimEnd('/')
        if (server.isNotBlank()) {
            try {
                return fromServer(getJson("$server/api/app"))
            } catch (_: Exception) {
            }
        }
        return fromGithub(getJson(GITHUB_LATEST))
    }

    fun isNewer(release: Release): Boolean {
        if (release.versionCode > 0) return release.versionCode > BuildConfig.VERSION_CODE
        return compareVersion(release.version, BuildConfig.VERSION_NAME) > 0
    }

    private fun fromServer(json: JSONObject): Release {
        val version = json.optString("version").removePrefix("v")
        val apkUrl = json.optString("apkUrl")
        if (version.isBlank() || apkUrl.isBlank() || !json.optBoolean("apkReady", true)) {
            throw ApiException("服务端还没有可安装的版本")
        }
        val notes = buildList {
            val array = json.optJSONArray("notes") ?: return@buildList
            for (index in 0 until array.length()) {
                val note = array.optString(index).trim()
                if (note.isNotBlank()) add(note)
            }
        }
        return Release(version, json.optInt("versionCode"), notes, apkUrl)
    }

    private fun fromGithub(json: JSONObject): Release {
        val version = json.optString("tag_name").removePrefix("v").ifBlank { "未知版本" }
        val notes = json.optString("body")
            .lineSequence()
            .map { it.trim().removePrefix("-").removePrefix("*").trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .take(6)
            .toList()
        val assets = json.optJSONArray("assets")
        var apkUrl = ""
        for (index in 0 until (assets?.length() ?: 0)) {
            val asset = assets?.optJSONObject(index) ?: continue
            val name = asset.optString("name")
            if (name.endsWith(".apk", ignoreCase = true)) {
                apkUrl = asset.optString("browser_download_url")
                break
            }
        }
        if (apkUrl.isBlank()) throw ApiException("GitHub 发布里没有 Android 安装包")
        return Release(version, 0, notes, apkUrl)
    }

    fun promptIfNeeded(activity: AppCompatActivity) {
        val settings = SettingsStore(activity)
        val now = System.currentTimeMillis()
        if (now - settings.updateCheckedAt < CHECK_INTERVAL) return
        settings.updateCheckedAt = now
        Io.run {
            val release = try {
                check(activity)
            } catch (_: Exception) {
                return@run
            }
            if (!isNewer(release)) return@run
            Io.ui {
                if (activity.isFinishing) return@ui
                offer(activity, release)
            }
        }
    }

    fun offer(activity: AppCompatActivity, release: Release, onStatus: (String) -> Unit = {}) {
        val notes = release.notes.joinToString("\n") { "· $it" }
        val message = if (notes.isBlank()) "可以下载并安装。" else notes
        AlertDialog.Builder(activity)
            .setTitle("发现新版本 ${release.version}")
            .setMessage(message)
            .setNegativeButton("以后", null)
            .setPositiveButton("更新") { _, _ -> downloadAndInstall(activity, release, onStatus) }
            .show()
    }

    fun downloadAndInstall(activity: Activity, release: Release, onStatus: (String) -> Unit = {}) {
        if (!ensureInstallPermission(activity)) {
            onStatus("请先允许安装未知应用")
            return
        }
        onStatus("正在下载 ${release.version}…")
        Io.run {
            try {
                val file = download(activity, release) { percent ->
                    Io.ui { onStatus("正在下载 ${release.version}… $percent%") }
                }
                Io.ui {
                    onStatus("下载完成，请确认安装")
                    install(activity, file)
                }
            } catch (error: Exception) {
                Io.ui { onStatus(error.message ?: "下载失败") }
            }
        }
    }

    private fun download(context: Context, release: Release, onProgress: (Int) -> Unit): File {
        val settings = SettingsStore(context)
        val target = if (release.apkUrl.startsWith("http")) release.apkUrl else settings.serverUrl.trimEnd('/') + release.apkUrl
        val connection = open(target)
        try {
            if (connection.responseCode !in 200..299) throw ApiException("安装包下载失败")
            val total = connection.contentLengthLong
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val file = File(dir, "shiban-update.apk")
            connection.inputStream.use { input ->
                FileOutputStream(file).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var readTotal = 0L
                    var lastPercent = -1
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        readTotal += count
                        if (total > 0) {
                            val percent = ((readTotal * 100) / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
            if (file.length() < 1024) throw ApiException("安装包不完整")
            return file
        } finally {
            connection.disconnect()
        }
    }

    fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun ensureInstallPermission(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity.packageManager.canRequestPackageInstalls()) {
            return true
        }
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
        activity.startActivity(intent)
        return false
    }

    private fun getJson(url: String): JSONObject {
        val connection = open(url)
        try {
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (connection.responseCode !in 200..299) {
                val message = try {
                    val body = JSONObject(raw)
                    body.optString("message").ifBlank { body.optString("error") }
                } catch (_: Exception) {
                    ""
                }
                throw ApiException(message.ifBlank { "检查更新失败" })
            }
            return JSONObject(raw)
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 180_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json, application/json, application/octet-stream")
            setRequestProperty("User-Agent", "shiban")
        }
    }

    private fun compareVersion(left: String, right: String): Int {
        fun parts(value: String) = value.removePrefix("v").substringBefore("-").split(".").map { it.toIntOrNull() ?: 0 }
        val a = parts(left)
        val b = parts(right)
        val count = maxOf(a.size, b.size)
        for (index in 0 until count) {
            val diff = a.getOrElse(index) { 0 } - b.getOrElse(index) { 0 }
            if (diff != 0) return diff
        }
        return 0
    }

    private const val GITHUB_LATEST = "https://api.github.com/repos/uasier/shiban/releases/latest"
    private const val CHECK_INTERVAL = 12 * 60 * 60 * 1000L
}
