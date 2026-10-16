package com.shiban.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

class TodoStore private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val db: SQLiteDatabase = TodoDb(appContext).writableDatabase

    fun all(): List<Todo> = synchronized(db) {
        db.query("todos", null, null, null, null, null, "created_at DESC").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toTodo())
            }
        }
    }

    fun get(id: Long): Todo? = synchronized(db) {
        db.query("todos", null, "id=?", arrayOf(id.toString()), null, null, null).use { cursor ->
            if (cursor.moveToFirst()) cursor.toTodo() else null
        }
    }

    fun insert(
        draft: TodoDraft,
        serverId: String? = null,
        createdAt: Long = System.currentTimeMillis(),
        completedAt: Long? = null,
        updatedAt: Long = createdAt,
        dirty: Boolean = serverId.isNullOrBlank()
    ): Long = synchronized(db) {
        val id = db.insert("todos", null, draft.toValues(createdAt, completedAt, serverId, updatedAt, dirty))
        if (id < 0) error("本地保存失败")
        changed()
        id
    }

    fun findByServerId(serverId: String): Todo? = synchronized(db) {
        db.query("todos", null, "server_id=?", arrayOf(serverId), null, null, null).use { cursor ->
            if (cursor.moveToFirst()) cursor.toTodo() else null
        }
    }

    fun update(todo: Todo) = synchronized(db) {
        val values = ContentValues().apply {
            put("content", todo.content)
            putMillis("time_millis", todo.timeMillis)
            putText("time_text", todo.timeText)
            putText("location", todo.location)
            put("importance", todo.importance)
            put("necessity", todo.necessity)
            putText("note", todo.note)
            putText("source_text", todo.sourceText)
            put("images", todo.imagePaths.joinToString("\n"))
            putMillis("completed_at", todo.completedAt)
            putText("server_id", todo.serverId)
            put("updated_at", todo.updatedAt)
            put("dirty", if (todo.dirty) 1 else 0)
        }
        db.update("todos", values, "id=?", arrayOf(todo.id.toString()))
        changed()
    }

    fun replaceIfFresh(todo: Todo): Boolean = synchronized(db) {
        val current = get(todo.id) ?: return false
        if (keepLocal(current, todo.updatedAt)) return false
        update(todo.copy(dirty = false))
        true
    }

    fun markClean(id: Long, updatedAt: Long, serverId: String? = null, unlessNewerThan: Long) = synchronized(db) {
        val current = get(id) ?: return
        val values = ContentValues()
        if (!serverId.isNullOrBlank() && current.serverId != serverId) values.put("server_id", serverId)
        if (current.updatedAt == unlessNewerThan) {
            values.put("dirty", 0)
            values.put("updated_at", updatedAt)
        }
        if (values.size() > 0) db.update("todos", values, "id=?", arrayOf(id.toString()))
    }

    fun setImages(id: Long, paths: List<String>): Boolean = synchronized(db) {
        val current = get(id) ?: return false
        if (current.imagePaths == paths) return false
        update(current.copy(imagePaths = paths))
        true
    }

    fun fillImages(id: Long, downloaded: List<String?>): Boolean = synchronized(db) {
        val current = get(id) ?: return false
        val existing = current.imagePaths
        val merged = buildList {
            val size = maxOf(existing.size, downloaded.size)
            for (index in 0 until size) {
                val local = existing.getOrNull(index)
                val remote = downloaded.getOrNull(index)
                val chosen = if (local != null && File(local).exists()) local else remote
                if (!chosen.isNullOrBlank()) add(chosen)
            }
        }
        if (merged.isEmpty() || merged == existing) return false
        update(current.copy(imagePaths = merged))
        true
    }

    fun setCompleted(id: Long, completed: Boolean) = synchronized(db) {
        val values = ContentValues().apply {
            if (completed) put("completed_at", System.currentTimeMillis()) else putNull("completed_at")
            put("dirty", 1)
            put("updated_at", System.currentTimeMillis())
        }
        db.update("todos", values, "id=?", arrayOf(id.toString()))
    }

    fun delete(id: Long, purgeFiles: Boolean = true) = synchronized(db) {
        val existing = get(id) ?: return
        db.delete("todos", "id=?", arrayOf(id.toString()))
        if (purgeFiles) purgeUnused(existing.imagePaths)
        changed()
    }

    fun purgeUnused(paths: List<String>) = synchronized(db) {
        val used = all().flatMap { it.imagePaths }.toSet()
        paths.forEach { path ->
            if (path !in used) File(path).delete()
        }
    }

    fun clearAll() = synchronized(db) {
        val images = all().flatMap { it.imagePaths }
        db.delete("todos", null, null)
        purgeUnused(images)
        changed()
    }

    private fun changed() {
        onChange?.invoke(appContext)
    }

    private fun keepLocal(current: Todo, remoteUpdatedAt: Long): Boolean {
        if (current.dirty && current.updatedAt >= remoteUpdatedAt) return true
        return !current.dirty && current.updatedAt > remoteUpdatedAt
    }

    private fun TodoDraft.toValues(
        createdAt: Long,
        completedAt: Long?,
        serverId: String?,
        updatedAt: Long,
        dirty: Boolean
    ): ContentValues {
        return ContentValues().apply {
            put("content", content)
            putMillis("time_millis", timeMillis)
            putText("time_text", timeText)
            putText("location", location)
            put("importance", importance)
            put("necessity", necessity)
            putText("note", note)
            putText("source_text", sourceText)
            put("images", imagePaths.joinToString("\n"))
            put("created_at", createdAt)
            putMillis("completed_at", completedAt)
            putText("server_id", serverId)
            put("updated_at", updatedAt)
            put("dirty", if (dirty) 1 else 0)
        }
    }

    private fun ContentValues.putMillis(key: String, value: Long?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun ContentValues.putText(key: String, value: String?) {
        if (value.isNullOrBlank()) putNull(key) else put(key, value)
    }

    private fun Cursor.toTodo(): Todo {
        val images = optString("images").orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        return Todo(
            id = getLong(getColumnIndexOrThrow("id")),
            content = getString(getColumnIndexOrThrow("content")),
            timeMillis = optLong("time_millis"),
            timeText = optString("time_text"),
            location = optString("location"),
            importance = optString("importance") ?: Levels.MEDIUM,
            necessity = optString("necessity") ?: Levels.SHOULD,
            note = optString("note"),
            sourceText = optString("source_text"),
            imagePaths = images,
            createdAt = getLong(getColumnIndexOrThrow("created_at")),
            completedAt = optLong("completed_at"),
            serverId = optString("server_id"),
            updatedAt = optLong("updated_at") ?: createdAtFallback(),
            dirty = optInt("dirty") == 1
        )
    }

    private fun Cursor.createdAtFallback(): Long {
        val index = getColumnIndex("created_at")
        return if (index < 0 || isNull(index)) 0L else getLong(index)
    }

    private fun Cursor.optLong(column: String): Long? {
        val index = getColumnIndex(column)
        if (index < 0 || isNull(index)) return null
        return getLong(index)
    }

    private fun Cursor.optInt(column: String): Int {
        val index = getColumnIndex(column)
        if (index < 0 || isNull(index)) return 0
        return getInt(index)
    }

    private fun Cursor.optString(column: String): String? {
        val index = getColumnIndex(column)
        if (index < 0 || isNull(index)) return null
        return getString(index)
    }

    private class TodoDb(context: Context) : SQLiteOpenHelper(context, "shiban.db", null, 3) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.enableWriteAheadLogging()
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE todos (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    content TEXT NOT NULL,
                    time_millis INTEGER,
                    time_text TEXT,
                    location TEXT,
                    importance TEXT NOT NULL,
                    necessity TEXT NOT NULL,
                    note TEXT,
                    source_text TEXT,
                    images TEXT,
                    created_at INTEGER NOT NULL,
                    completed_at INTEGER,
                    server_id TEXT,
                    updated_at INTEGER NOT NULL DEFAULT 0,
                    dirty INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_todos_server_id ON todos(server_id)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) db.execSQL("ALTER TABLE todos ADD COLUMN server_id TEXT")
            if (oldVersion < 3) {
                db.execSQL("ALTER TABLE todos ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE todos ADD COLUMN dirty INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE todos SET updated_at = created_at WHERE updated_at = 0")
            }
        }

        override fun onOpen(db: SQLiteDatabase) {
            db.execSQL("PRAGMA busy_timeout=3000")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_todos_server_id ON todos(server_id)")
        }
    }

    companion object {
        @Volatile
        private var instance: TodoStore? = null

        var onChange: ((Context) -> Unit)? = null

        fun get(context: Context): TodoStore {
            return instance ?: synchronized(this) {
                instance ?: TodoStore(context.applicationContext).also { instance = it }
            }
        }
    }
}
