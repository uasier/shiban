package com.shiban.app.data

import android.content.Context
import com.shiban.app.net.ApiClient
import com.shiban.app.net.ApiException
import com.shiban.app.net.RemoteTodo
import com.shiban.app.reminder.Reminders
import java.io.File

object Sync {
    private val lock = Any()
    private val imageJobs = mutableListOf<ImageJob>()

    fun canKeep(error: Exception): Boolean {
        if (error !is ApiException) return true
        return error.httpCode == 0 || error.httpCode == 401 || error.httpCode >= 500
    }

    fun keepLocal(context: Context, draft: TodoDraft): Todo {
        val id = TodoStore.get(context).insert(draft)
        val saved = TodoStore.get(context).get(id) ?: error("本地保存失败")
        Reminders.sync(context, saved)
        return saved
    }

    fun capture(context: Context, text: String?, imagePaths: List<String>): CaptureResult {
        val drafts = OfflineDrafts.fromShare(text, imagePaths).ifEmpty {
            val line = text?.trim().orEmpty()
            if (line.isEmpty() && imagePaths.isEmpty()) emptyList()
            else listOf(OfflineDrafts.fromLine(line.ifBlank { "查看导入的图片" }, imagePaths))
        }
        if (drafts.isEmpty()) throw IllegalArgumentException("没有可记的内容")
        if (imagePaths.isEmpty() && alreadyOpen(context, drafts)) {
            return CaptureResult("还在待办里", 0, needLogin = false)
        }
        if (!SettingsStore(context).isLoggedIn) {
            drafts.forEach { keepLocal(context, it) }
            return CaptureResult("已先记在本机，登录后会同步", drafts.size, needLogin = false)
        }
        return try {
            val count = ingest(context, text, imagePaths)
            CaptureResult("已记入 $count 条", count, needLogin = false)
        } catch (error: Exception) {
            if (!canKeep(error)) throw error
            val fresh = drafts.filterNot { draft -> alreadyOpen(context, listOf(draft)) }
            if (fresh.isEmpty()) return CaptureResult("还在待办里", 0, needLogin = false)
            fresh.forEach { keepLocal(context, it) }
            val expired = error is ApiException && error.httpCode == 401
            CaptureResult(
                if (expired) "登录已失效，已先记在本机" else "网络不稳，已先记在本机",
                fresh.size,
                needLogin = expired
            )
        }
    }

    private fun alreadyOpen(context: Context, drafts: List<TodoDraft>): Boolean {
        if (drafts.isEmpty()) return false
        val open = TodoStore.get(context).all().filter { it.completedAt == null }.map { it.content }.toSet()
        return drafts.all { it.content in open }
    }

    fun pull(context: Context) = synchronized(lock) {
        val settings = SettingsStore(context)
        if (!settings.isLoggedIn) return
        val api = ApiClient(settings)
        val me = api.me()
        Account.adopt(context, settings.userId, me.id)
        settings.username = me.username
        settings.displayName = me.displayName
        settings.applyAi(me.ai.baseUrl, me.ai.model, me.ai.apiKey)
        val store = TodoStore.get(context)
        pushDirty(api, store)
        val remote = api.listTodos()
        val remoteIds = remote.map { it.id }.toSet()
        remote.forEach { item -> mergeRemote(context, api, store, item) }
        store.all().filter { !it.serverId.isNullOrBlank() && it.serverId !in remoteIds }.forEach { local ->
            if (local.dirty) {
                val created = api.createTodo(local.asDraft(), local.completedAt, local.createdAt)
                store.markClean(local.id, created.updatedAt, created.id, local.updatedAt)
            } else {
                store.delete(local.id)
            }
        }
    }

    fun ingest(context: Context, text: String?, imagePaths: List<String>): Int = synchronized(lock) {
        val settings = SettingsStore(context)
        if (!settings.isLoggedIn) throw IllegalStateException("请先登录")
        val store = TodoStore.get(context)
        val before = store.all().mapNotNull { it.serverId }.toSet()
        val count = ApiClient(settings).ingest(text, imagePaths)
        try {
            pull(context)
            attachLocalImages(store, before, imagePaths)
            val created = store.all().filter { todo ->
                val serverId = todo.serverId
                !serverId.isNullOrBlank() && serverId !in before
            }
            created.forEach { Reminders.sync(context, it) }
            imageJobs.removeAll { job ->
                val todo = store.get(job.localId) ?: return@removeAll false
                todo.imagePaths.count { File(it).exists() } >= job.urls.size
            }
        } catch (_: Exception) {
        }
        count
    }

    fun create(context: Context, draft: TodoDraft): Todo = synchronized(lock) {
        val settings = SettingsStore(context)
        if (!settings.isLoggedIn) throw IllegalStateException("请先登录识办账号")
        val remote = ApiClient(settings).createTodo(draft)
        val id = TodoStore.get(context).insert(
            draft,
            remote.id,
            remote.createdAt,
            remote.completedAt,
            remote.updatedAt,
            dirty = false
        )
        TodoStore.get(context).get(id) ?: throw IllegalStateException("本地保存失败")
    }

    fun update(context: Context, todo: Todo): Todo = synchronized(lock) {
        val store = TodoStore.get(context)
        val pending = todo.copy(updatedAt = System.currentTimeMillis(), dirty = true)
        store.update(pending)
        val settings = SettingsStore(context)
        val serverId = pending.serverId
        if (settings.isLoggedIn && !serverId.isNullOrBlank()) {
            val remote = ApiClient(settings).updateTodo(serverId, pending.asDraft(), pending.completedAt)
            store.markClean(pending.id, remote.updatedAt, unlessNewerThan = pending.updatedAt)
        }
        store.get(pending.id) ?: pending
    }

    fun delete(context: Context, todo: Todo) = synchronized(lock) {
        val settings = SettingsStore(context)
        val serverId = todo.serverId
        if (settings.isLoggedIn && !serverId.isNullOrBlank()) {
            ApiClient(settings).deleteTodo(serverId)
        }
        TodoStore.get(context).delete(todo.id, purgeFiles = false)
    }

    private fun pushDirty(api: ApiClient, store: TodoStore) {
        store.all().filter { it.dirty && !it.serverId.isNullOrBlank() }.forEach { local ->
            val remote = api.updateTodo(local.serverId.orEmpty(), local.asDraft(), local.completedAt)
            store.markClean(local.id, remote.updatedAt, unlessNewerThan = local.updatedAt)
        }
        store.all().filter { it.serverId.isNullOrBlank() }.forEach { local ->
            val remote = api.createTodo(local.asDraft(), local.completedAt, local.createdAt)
            store.markClean(local.id, remote.updatedAt, remote.id, local.updatedAt)
        }
    }

    private fun mergeRemote(context: Context, api: ApiClient, store: TodoStore, item: RemoteTodo) {
        val existing = store.findByServerId(item.id)
        if (existing != null && existing.dirty && existing.updatedAt >= item.updatedAt) {
            val pushed = api.updateTodo(item.id, existing.asDraft(), existing.completedAt)
            store.markClean(existing.id, pushed.updatedAt, unlessNewerThan = existing.updatedAt)
            return
        }
        val localImages = existing?.imagePaths?.filter { File(it).exists() }.orEmpty()
        val images = when {
            localImages.isNotEmpty() -> localImages
            existing != null -> existing.imagePaths
            else -> emptyList()
        }
        if (existing == null) {
            store.insert(item.toDraft(images), item.id, item.createdAt, item.completedAt, item.updatedAt, dirty = false)
        } else {
            store.replaceIfFresh(
                existing.copy(
                    content = item.content,
                    timeMillis = item.timeMillis,
                    timeText = item.timeText,
                    location = item.location,
                    importance = item.importance,
                    necessity = item.necessity,
                    note = item.note,
                    sourceText = item.sourceText,
                    imagePaths = images,
                    createdAt = item.createdAt,
                    completedAt = item.completedAt,
                    serverId = item.id,
                    updatedAt = item.updatedAt,
                    dirty = false
                )
            )
        }
        val saved = store.findByServerId(item.id)
        val urls = item.imageUrls.ifEmpty { listOfNotNull(item.imageUrl) }
        val have = saved?.imagePaths?.count { File(it).exists() } ?: 0
        if (saved != null && urls.isNotEmpty() && have < urls.size) {
            imageJobs += ImageJob(saved.id, item.id, urls)
        }
    }

    fun cacheImages(context: Context): Boolean {
        val jobs = synchronized(lock) { imageJobs.toList().also { imageJobs.clear() } }
        if (jobs.isEmpty()) return false
        val settings = SettingsStore(context)
        if (!settings.isLoggedIn) return false
        val api = ApiClient(settings)
        val store = TodoStore.get(context)
        var changed = false
        jobs.forEach { job ->
            val downloaded = job.urls.mapIndexed { index, url ->
                downloadImage(context, api, job.serverId, index, url)
            }
            if (store.fillImages(job.localId, downloaded)) changed = true
        }
        return changed
    }

    private fun attachLocalImages(store: TodoStore, before: Set<String>, paths: List<String>) {
        val files = paths.filter { File(it).exists() }
        if (files.isEmpty()) return
        val fresh = store.all().filter { todo ->
            val serverId = todo.serverId
            !serverId.isNullOrBlank() && serverId !in before
        }.sortedBy { it.createdAt }
        if (fresh.isEmpty()) return
        fresh.forEachIndexed { index, todo ->
            val chosen = imagesFor(files, fresh.size, index)
            if (chosen.isNotEmpty()) store.setImages(todo.id, chosen)
        }
    }

    private fun imagesFor(paths: List<String>, count: Int, index: Int): List<String> {
        if (count <= 1) return paths
        if (index == 0 && paths.size > count) return listOf(paths[0]) + paths.drop(count)
        return paths.getOrNull(index)?.let { listOf(it) }.orEmpty()
    }

    private fun downloadImage(context: Context, api: ApiClient, serverId: String, index: Int, imageUrl: String): String? {
        val name = if (index == 0) "srv_$serverId.jpg" else "srv_${serverId}_$index.jpg"
        val dest = File(File(context.filesDir, "inbox").apply { mkdirs() }, name)
        if (dest.exists() && dest.length() > 0) return dest.absolutePath
        return try {
            api.downloadImage(imageUrl, dest)
            dest.absolutePath
        } catch (_: Exception) {
            if (dest.exists()) dest.delete()
            null
        }
    }

    private data class ImageJob(val localId: Long, val serverId: String, val urls: List<String>)

    private fun RemoteTodo.toDraft(images: List<String>) = TodoDraft(
        content = content,
        timeMillis = timeMillis,
        timeText = timeText,
        location = location,
        importance = importance,
        necessity = necessity,
        note = note,
        sourceText = sourceText,
        imagePaths = images
    )
}

data class CaptureResult(val message: String, val count: Int, val needLogin: Boolean)
