package com.shiban.app.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.shiban.app.reminder.Reminders
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object Backup {
    fun share(context: Context) {
        val todos = TodoStore.get(context).all()
        val array = JSONArray()
        todos.forEach { todo ->
            array.put(
                JSONObject()
                    .put("content", todo.content)
                    .put("timeMillis", todo.timeMillis ?: JSONObject.NULL)
                    .put("timeText", todo.timeText ?: JSONObject.NULL)
                    .put("location", todo.location ?: JSONObject.NULL)
                    .put("importance", todo.importance)
                    .put("necessity", todo.necessity)
                    .put("note", todo.note ?: JSONObject.NULL)
                    .put("sourceText", todo.sourceText ?: JSONObject.NULL)
                    .put("createdAt", todo.createdAt)
                    .put("completedAt", todo.completedAt ?: JSONObject.NULL)
            )
        }
        val root = JSONObject()
            .put("app", "shiban")
            .put("version", 1)
            .put("exportedAt", System.currentTimeMillis())
            .put("todos", array)
        val dir = File(context.cacheDir, "export").apply { mkdirs() }
        val file = File(dir, "shiban-todos.json")
        file.writeText(root.toString(2))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "识办待办")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("shiban-todos.json", uri)
        }
        context.startActivity(Intent.createChooser(send, "导出待办"))
    }

    fun importJson(context: Context, text: String): Int {
        val root = try {
            JSONObject(text)
        } catch (_: Exception) {
            error("这不是识办的备份文件")
        }
        if (!root.has("todos")) error("这不是识办的备份文件")
        val array = root.getJSONArray("todos")
        val store = TodoStore.get(context)
        var count = 0
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            val content = item.optString("content").trim()
            if (content.isEmpty()) continue
            val createdAt = item.optLongOrNull("createdAt") ?: System.currentTimeMillis()
            val id = store.insert(
                TodoDraft(
                    content = content,
                    timeMillis = item.optLongOrNull("timeMillis"),
                    timeText = item.optText("timeText"),
                    location = item.optText("location"),
                    importance = item.optString("importance").ifBlank { Levels.MEDIUM },
                    necessity = item.optString("necessity").ifBlank { Levels.SHOULD },
                    note = item.optText("note"),
                    sourceText = item.optText("sourceText"),
                    imagePaths = emptyList()
                ),
                createdAt = createdAt,
                completedAt = item.optLongOrNull("completedAt")
            )
            store.get(id)?.let { Reminders.sync(context, it) }
            count++
        }
        return count
    }

    private fun JSONObject.optText(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val value = optString(key).trim()
        return value.ifBlank { null }
    }

    private fun JSONObject.optLongOrNull(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        return optLong(key)
    }
}
