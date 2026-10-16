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
        val settings = SettingsStore(context)
        val json = getJson(settings.serverUrl.trimEnd('/') + "/api/app")
        val notes = json.optJSONArray("notes")
        return Release(
            version = json.optString("version").ifBlank { "未知版本" },
            versionCode = json.optInt("versionCode"),
            notes = List(notes?.length() ?: 0) { notes!!.optString(it) }.filter { it.isNotBlank() },
            apkUrl = json.optString("apkUrl").ifBlank { "/api/app/download" }
        )
    }

    fun isNewer(release: Release): Boolean = release.versionCode > BuildConfig.VERSION_CODE

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
                    JSONObject(raw).optString("error")
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
            setRequestProperty("Accept", "application/json, application/vnd.android.package-archive")
        }
    }

    private const val CHECK_INTERVAL = 12 * 60 * 60 * 1000L
}
