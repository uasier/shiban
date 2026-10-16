package com.shiban.app.ui

import android.Manifest
import android.app.AlarmManager
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.shiban.app.R
import com.shiban.app.ai.Parser
import com.shiban.app.data.AppUpdate
import com.shiban.app.data.Backup
import com.shiban.app.data.Changelog
import com.shiban.app.data.Levels
import com.shiban.app.data.SettingsStore
import com.shiban.app.data.Sync
import com.shiban.app.data.Todo
import com.shiban.app.data.TodoStore
import com.shiban.app.data.asDraft
import com.shiban.app.net.ApiException
import com.shiban.app.databinding.ActivityMainBinding
import com.shiban.app.reminder.Reminders
import com.shiban.app.util.Images
import com.shiban.app.util.Io
import com.shiban.app.util.TimeFmt
import com.shiban.app.util.toast

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val store by lazy { TodoStore.get(this) }
    private var all = listOf<Todo>()
    private var filter = Filter.OPEN
    private var syncError: String? = null
    private var clipOffer: ClipOffer? = null
    private var savingQuick = false
    private var syncing = false
    private var askedNotify = false
    private var authExpired = false
    private var reminderKey: String? = null
    private var lastSyncAt = 0L
    private val pendingDeletes = mutableMapOf<Long, DeleteJob>()

    private val adapter = TodoAdapter(
        onOpen = { todo ->
            startActivity(Intent(this, DetailActivity::class.java).putExtra(DetailActivity.EXTRA_ID, todo.id))
        },
        onToggle = { todo, done -> toggle(todo, done) },
        onDelete = { todo -> remove(todo) },
        onLater = { todo -> askLater(todo) },
        timeOf = { todo -> remindAt(todo) }
    )
    private val prefs by lazy { getSharedPreferences("shiban_ui", MODE_PRIVATE) }
    private val swipeRect = RectF()
    private val swipeFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val swipeLabel = Paint(Paint.ANTI_ALIAS_FLAG)

    private val notifyPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (!isDestroyed) render()
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        val app = applicationContext
        Io.run {
            try {
                val path = Images.importUri(app, uri)
                val result = Sync.capture(app, null, listOf(path))
                Io.ui {
                    toast(result.message)
                    reload()
                    if (result.needLogin) openSettings()
                }
            } catch (e: Exception) {
                Io.ui { toast(e.message ?: "读取图片失败") }
            }
        }
    }

    private val pickBackup = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        val app = applicationContext
        Io.run {
            try {
                val text = app.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("无法读取文件")
                val count = Backup.importJson(app, text)
                Io.ui {
                    if (isDestroyed) return@ui
                    val synced = SettingsStore(this).isLoggedIn
                    toast(if (synced) "已导入 $count 条，正在同步" else "已导入 $count 条，登录后会同步")
                    reload()
                    if (synced) refreshFromServer(manual = true)
                }
            } catch (error: Exception) {
                Io.ui { toast(error.message ?: "导入失败") }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val density = resources.displayMetrics.density
        swipeFill.color = ContextCompat.getColor(this, R.color.ink)
        swipeLabel.color = ContextCompat.getColor(this, R.color.card)
        swipeLabel.textSize = 13f * density
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.list.setHasFixedSize(true)
        ItemTouchHelper(swipeCallback).attachToRecyclerView(binding.list)
        binding.refresh.setColorSchemeColors(ContextCompat.getColor(this, R.color.seal))
        binding.refresh.setProgressBackgroundColorSchemeColor(ContextCompat.getColor(this, R.color.card))
        binding.refresh.setOnRefreshListener { refreshFromServer(manual = true) }
        binding.filterOpen.setOnClickListener { select(Filter.OPEN) }
        binding.filterToday.setOnClickListener { select(Filter.TODAY) }
        binding.filterMust.setOnClickListener { select(Filter.MUST) }
        binding.filterOverdue.setOnClickListener { select(Filter.OVERDUE) }
        binding.filterDone.setOnClickListener { select(Filter.DONE) }
        binding.search.addTextChangedListener {
            binding.searchClear.isVisible = !it.isNullOrBlank()
            render()
        }
        binding.search.setOnEditorActionListener { view, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard(view)
                true
            } else {
                false
            }
        }
        binding.searchClear.setOnClickListener { binding.search.setText("") }
        binding.more.setOnClickListener {
            binding.morePanel.isVisible = !binding.morePanel.isVisible
        }
        binding.menuAlbum.setOnClickListener {
            binding.morePanel.isVisible = false
            openAlbum()
        }
        binding.menuCreate.setOnClickListener {
            binding.morePanel.isVisible = false
            openCreate()
        }
        binding.menuSettings.setOnClickListener {
            binding.morePanel.isVisible = false
            openSettings()
        }
        binding.menuClearDone.setOnClickListener {
            binding.morePanel.isVisible = false
            clearCompleted()
        }
        binding.menuExport.setOnClickListener {
            binding.morePanel.isVisible = false
            if (store.all().isEmpty()) {
                toast("没有可导出的待办")
            } else {
                Backup.share(this)
            }
        }
        binding.menuImport.setOnClickListener {
            binding.morePanel.isVisible = false
            AlertDialog.Builder(this)
                .setTitle("导入待办？")
                .setMessage("会按备份再记一份，不会覆盖现在的。登录后会同步到账号。")
                .setNegativeButton("取消", null)
                .setPositiveButton("选择文件") { _, _ -> pickBackup.launch("*/*") }
                .show()
        }
        binding.composer.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val pad = view.height + (8 * resources.displayMetrics.density).toInt()
            if (binding.list.paddingBottom != pad) {
                binding.list.setPadding(binding.list.paddingLeft, binding.list.paddingTop, binding.list.paddingRight, pad)
            }
        }
        binding.emptySettings.setOnClickListener { openSettings() }
        binding.quickSave.setOnClickListener { quickSave() }
        binding.quickAdd.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                quickSave()
                true
            } else {
                false
            }
        }
        binding.quickAdd.addTextChangedListener { refreshQuickHint(it?.toString().orEmpty()) }
        binding.clipBannerSave.setOnClickListener {
            val offer = clipOffer ?: return@setOnClickListener
            dismissClip(offer)
            rememberClipboard(offer)
        }
        binding.clipBannerIgnore.setOnClickListener {
            val offer = clipOffer ?: return@setOnClickListener
            dismissClip(offer)
        }
        listOf(binding.list, binding.search, binding.emptyIntro).forEach { view ->
            view.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN && binding.morePanel.isVisible) {
                    binding.morePanel.isVisible = false
                }
                false
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.morePanel.isVisible) {
                    binding.morePanel.isVisible = false
                } else {
                    finish()
                }
            }
        })
        select(Filter.OPEN)
        handleShortcut(intent)
        if (!Changelog.promptIfNeeded(this)) AppUpdate.promptIfNeeded(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShortcut(intent)
    }

    override fun onResume() {
        super.onResume()
        reload()
        binding.root.postDelayed({ maybeOfferClipboard() }, 400)
        refreshFromServer(manual = false)
        Io.run { Reminders.catchUp(applicationContext) }
    }

    private fun handleShortcut(intent: Intent) {
        if (intent.action == ACTION_ADD) {
            intent.action = Intent.ACTION_MAIN
            openCreate()
        }
        if (intent.getStringExtra(EXTRA_FILTER) == FILTER_OVERDUE) {
            intent.removeExtra(EXTRA_FILTER)
            select(Filter.OVERDUE)
        }
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun refreshFromServer(manual: Boolean) {
        if (!SettingsStore(this).isLoggedIn) {
            binding.refresh.isRefreshing = false
            if (manual) toast("请先登录")
            return
        }
        if (!manual && System.currentTimeMillis() - lastSyncAt < SYNC_GAP_MS) return
        if (syncing) {
            if (manual) binding.refresh.isRefreshing = true
            return
        }
        syncing = true
        if (manual) {
            syncError = null
            binding.refresh.isRefreshing = true
            render()
        }
        val app = applicationContext
        Io.run {
            try {
                Sync.pull(app)
                val todos = TodoStore.get(app).all()
                syncReminders(todos)
                Io.ui {
                    if (isDestroyed) return@ui
                    binding.refresh.isRefreshing = false
                    reload()
                }
                val imagesChanged = Sync.cacheImages(app)
                Io.ui {
                    if (isDestroyed) return@ui
                    syncing = false
                    lastSyncAt = System.currentTimeMillis()
                    syncError = null
                    authExpired = false
                    binding.refresh.isRefreshing = false
                    if (imagesChanged) reload()
                }
            } catch (error: Exception) {
                Io.ui {
                    if (isDestroyed) return@ui
                    syncing = false
                    binding.refresh.isRefreshing = false
                    authExpired = error is ApiException && error.httpCode == 401
                    syncError = error.message ?: "同步失败"
                    render()
                }
            }
        }
    }

    private fun syncReminders(todos: List<Todo>) {
        val key = todos.joinToString("|") { "${it.id}:${it.timeMillis}:${it.completedAt}" }
        if (key == reminderKey) return
        reminderKey = key
        todos.forEach { Reminders.sync(applicationContext, it) }
    }

    private fun maybeOfferClipboard() {
        if (isFinishing || clipOffer != null) return
        val description = try {
            val clipboard = getSystemService(ClipboardManager::class.java) ?: return
            if (!clipboard.hasPrimaryClip()) return
            clipboard.primaryClipDescription
        } catch (_: Exception) {
            return
        } ?: return
        if (description.mimeTypeCount == 0) return
        val signature = if (description.timestamp > 0) {
            "c:${description.timestamp}"
        } else {
            "c:0:${description.mimeTypeCount}:${description.getMimeType(0)}"
        }
        if (prefs.getString(KEY_CLIP, "") == signature) return
        val mimeTypes = (0 until description.mimeTypeCount).map { description.getMimeType(it) }
        val image = mimeTypes.any { it.startsWith("image/") }
        val textLike = mimeTypes.any { it.startsWith("text/") }
        if (!image && !textLike) return
        val offer = readClipboard(signature)
        if (offer == null) {
            prefs.edit().putString(KEY_CLIP, signature).apply()
            return
        }
        val duplicate = offer.text.isNotBlank() && all.any { it.sourceText == offer.text || it.content == offer.text }
        if (duplicate) {
            prefs.edit().putString(KEY_CLIP, offer.signature).apply()
            return
        }
        clipOffer = offer
        render()
    }

    private fun dismissClip(offer: ClipOffer) {
        prefs.edit().putString(KEY_CLIP, offer.signature).apply()
        clipOffer = null
        render()
    }

    private fun quickSave() {
        val text = binding.quickAdd.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            toast("先写一句")
            return
        }
        if (all.any { it.completedAt == null && it.content == text }) {
            toast("这条还在待办里")
            return
        }
        if (savingQuick) return
        savingQuick = true
        binding.quickSave.isEnabled = false
        val app = applicationContext
        Io.run {
            try {
                val result = Sync.capture(app, text, emptyList())
                Io.ui {
                    if (isDestroyed) return@ui
                    binding.quickAdd.setText("")
                    hideKeyboard(binding.quickAdd)
                    snackbar(result.message).show()
                    reload()
                    if (result.needLogin) openSettings()
                }
            } catch (error: Exception) {
                Io.ui { toast(error.message ?: "记入失败") }
            } finally {
                Io.ui {
                    savingQuick = false
                    if (!isDestroyed) binding.quickSave.isEnabled = true
                }
            }
        }
    }

    private fun refreshQuickHint(raw: String) {
        val text = raw.trim()
        val whenMs = if (text.length >= 2) Parser.parseTime(text) else null
        if (whenMs == null) {
            binding.quickHint.isVisible = false
            return
        }
        val tags = buildList {
            add(TimeFmt.format(whenMs))
            if (Regex("必须|务必|一定要").containsMatchIn(text)) add("必须")
        }
        binding.quickHint.isVisible = true
        binding.quickHint.text = "将提醒 ${tags.joinToString(" · ")}"
    }

    private fun readClipboard(signature: String): ClipOffer? {
        return try {
            val clipboard = getSystemService(ClipboardManager::class.java) ?: return null
            val clip = clipboard.primaryClip ?: return null
            if (clip.itemCount == 0) return null
            val item = clip.getItemAt(0)
            val mime = clip.description?.getMimeType(0).orEmpty()
            if (mime.startsWith("image/") && item.uri != null) {
                return ClipOffer(signature, "", item.uri)
            }
            val text = item.coerceToText(this)?.toString()?.trim().orEmpty()
            if (!worthClipboard(text)) return null
            ClipOffer(signature, text, null)
        } catch (_: Exception) {
            null
        }
    }

    private fun worthClipboard(text: String): Boolean {
        if (text.length >= 4) return true
        return text.length >= 2 && text.any { it.code > 127 }
    }

    private fun rememberClipboard(offer: ClipOffer) {
        val app = applicationContext
        Io.run {
            try {
                val path = offer.imageUri?.let { Images.importUri(app, it) }
                val result = Sync.capture(app, offer.text.ifBlank { null }, listOfNotNull(path))
                Io.ui {
                    toast(result.message)
                    reload()
                    if (result.needLogin) openSettings()
                }
            } catch (error: Exception) {
                Io.ui { toast(error.message ?: "记入失败") }
            }
        }
    }

    private fun openCreate() {
        startActivity(ReviewActivity.manual(this))
    }

    private fun clearCompleted() {
        val done = all.filter { it.completedAt != null }
        if (done.isEmpty()) {
            toast("没有已完成的待办")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("清理已完成？")
            .setMessage("将删除 ${done.size} 条已完成待办，账号里也会一起删掉。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清理") { _, _ ->
                Io.run {
                    var ok = 0
                    var failed = 0
                    done.forEach { todo ->
                        try {
                            Sync.delete(applicationContext, todo)
                            Reminders.forget(applicationContext, todo.id)
                            ok++
                        } catch (_: Exception) {
                            failed++
                        }
                    }
                    Io.ui {
                        if (isDestroyed) return@ui
                        reload()
                        toast(if (failed == 0) "已清理 $ok 条" else "清理了 $ok 条，$failed 条没删掉")
                    }
                }
            }
            .show()
    }

    private fun hideKeyboard(view: android.view.View) {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun openAlbum() {
        pickImage.launch("image/*")
    }

    private fun reload() {
        if (isDestroyed || !::binding.isInitialized) return
        val hidden = pendingDeletes.keys
        all = store.all().filter { it.id !in hidden }
        render()
    }

    private fun render() {
        if (!::binding.isInitialized) return
        val query = binding.search.text?.toString()?.trim().orEmpty()
        val shown = sorted(all.filter { matches(it, filter, query) })
        adapter.submit(shown)
        val noData = all.isEmpty() && query.isBlank()
        binding.emptyIntro.isVisible = shown.isEmpty() && noData
        binding.emptyFilter.isVisible = shown.isEmpty() && !noData
        binding.list.isVisible = shown.isNotEmpty()
        val open = all.filter { it.completedAt == null }
        val must = open.count { it.necessity == Levels.MUST }
        val today = open.count { remindAt(it)?.let(TimeFmt::isToday) == true }
        val overdue = open.count { TimeFmt.isOverdue(remindAt(it)) }
        val account = SettingsStore(this)
        val loggedOut = !account.isLoggedIn
        val aiMissing = account.isLoggedIn && account.aiApiKey.isBlank()
        binding.settingsDot.isVisible = loggedOut || aiMissing
        val prefix = if (syncing && !loggedOut) "同步中 · " else ""
        binding.summary.text = if (loggedOut) {
            "还没登录 · 未完成 ${open.size}"
        } else {
            "$prefix${account.displayName} · 未完成 ${open.size} · 必须 $must · 今天 $today"
        }
        binding.filterOpen.text = "待办 ${open.size}"
        binding.filterToday.text = "今天 $today"
        binding.filterMust.text = "必须 $must"
        binding.filterOverdue.text = "已过期 $overdue"
        binding.filterOverdue.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    filter == Filter.OVERDUE -> R.color.ink
                    overdue > 0 -> R.color.danger
                    else -> R.color.muted
                }
            )
        )
        binding.filterDone.text = "已完成 ${all.count { it.completedAt != null }}"
        binding.emptyFilter.text = if (query.isNotBlank()) "没有找到「$query」" else "这个筛选下没有待办"
        val preview = clipOffer?.text?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }?.take(42)
        binding.clipBanner.isVisible = clipOffer != null
        binding.clipBannerText.text = if (clipOffer?.imageUri != null && preview.isNullOrBlank()) {
            "剪贴板里有一张图"
        } else {
            "剪贴板：${preview ?: "可以记成待办"}"
        }
        binding.emptyTitle.text = if (loggedOut) "先登录你的账号" else "还没有待办"
        binding.emptyLead.text = if (loggedOut) {
            "登录后，待办会跟账号走，手机和桌面能看到同一份。"
        } else if (aiMissing) {
            "底部可以直接写。要识别图片，再到账号页填上模型密钥。"
        } else {
            "底部写一句，或把聊天、海报分享过来。勾选就能完成。"
        }
        binding.emptySettings.text = if (loggedOut) "登录账号" else "账号与模型"
        val upcoming = open.any { it.timeMillis != null && it.timeMillis > System.currentTimeMillis() }
        val needNotify = upcoming && !NotificationManagerCompat.from(this).areNotificationsEnabled()
        val needExact = upcoming && Build.VERSION.SDK_INT >= 31 &&
            !getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        when {
            !syncError.isNullOrBlank() && authExpired -> {
                binding.notifyBanner.isVisible = true
                binding.notifyBanner.text = "登录已失效。点这里重新登录。"
                binding.notifyBanner.setOnClickListener { openSettings() }
            }
            !syncError.isNullOrBlank() -> {
                binding.notifyBanner.isVisible = true
                binding.notifyBanner.text = "$syncError。点这里重试。"
                binding.notifyBanner.setOnClickListener {
                    syncError = null
                    refreshFromServer(manual = true)
                }
            }
            aiMissing -> {
                binding.notifyBanner.isVisible = true
                binding.notifyBanner.text = "还没配置模型。文字会按原文记下，图片识别需要密钥。点这里填写。"
                binding.notifyBanner.setOnClickListener { openSettings() }
            }
            needNotify -> {
                binding.notifyBanner.isVisible = true
                binding.notifyBanner.text = "有定时待办，但通知没开。点这里去开启。"
                binding.notifyBanner.setOnClickListener { openNotificationSettings() }
            }
            needExact -> {
                binding.notifyBanner.isVisible = true
                binding.notifyBanner.text = "定时提醒可能不准。点这里允许精确闹钟。"
                binding.notifyBanner.setOnClickListener { openExactAlarmSettings() }
            }
            else -> binding.notifyBanner.isVisible = false
        }
        binding.swipeHint.isVisible = shown.isNotEmpty() && !prefs.getBoolean(KEY_SWIPE, false)
        binding.searchClear.isVisible = query.isNotEmpty()
    }

    private fun matches(todo: Todo, current: Filter, query: String): Boolean {
        if (query.isNotBlank()) {
            val labels = listOfNotNull(
                Levels.importanceLabel(todo.importance),
                Levels.necessityLabel(todo.necessity),
                if (todo.completedAt == null && TimeFmt.isOverdue(remindAt(todo))) "已过期" else null,
                if (remindAt(todo)?.let(TimeFmt::isToday) == true) "今天" else null
            )
            val haystack = (listOfNotNull(todo.content, todo.location, todo.note, todo.timeText, todo.sourceText) + labels)
                .joinToString(" ")
            if (!haystack.contains(query, ignoreCase = true)) return false
        }
        val done = todo.completedAt != null
        return when (current) {
            Filter.DONE -> done
            Filter.OPEN -> !done
            Filter.TODAY -> !done && remindAt(todo)?.let(TimeFmt::isToday) == true
            Filter.MUST -> !done && todo.necessity == Levels.MUST
            Filter.OVERDUE -> !done && TimeFmt.isOverdue(remindAt(todo))
        }
    }

    private fun remindAt(todo: Todo): Long? = Reminders.activeSnooze(this, todo.id) ?: todo.timeMillis

    private fun sorted(list: List<Todo>): List<Todo> {
        return list.sortedWith(
            compareBy<Todo> { it.completedAt != null }
                .thenBy { remindAt(it) == null }
                .thenBy { remindAt(it) ?: Long.MAX_VALUE }
                .thenBy { Levels.necessityRank(it.necessity) }
                .thenBy { Levels.importanceRank(it.importance) }
                .thenByDescending { it.createdAt }
        )
    }

    private fun askLater(todo: Todo) {
        val morning = TimeFmt.shortcuts().firstOrNull { it.first == "明天上午" }?.second
            ?: System.currentTimeMillis() + 60 * 60_000
        val labels = mutableListOf("10 分钟后再提醒", "1 小时后再提醒", "明天上午")
        val hasSnooze = Reminders.activeSnooze(this, todo.id) != null
        if (hasSnooze) labels += "取消稍后"
        AlertDialog.Builder(this)
            .setTitle(todo.content)
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    hasSnooze && which == labels.lastIndex -> {
                        Reminders.clearSnooze(this, todo.id)
                        Reminders.sync(this, todo)
                        toast("已取消稍后提醒")
                    }
                    which == 0 -> {
                        Reminders.snoozeIn(this, todo.id, 10)
                        toast("10 分钟后再提醒")
                    }
                    which == 1 -> {
                        Reminders.snoozeIn(this, todo.id, 60)
                        toast("1 小时后再提醒")
                    }
                    else -> {
                        Reminders.snooze(this, todo.id, morning)
                        toast("明天上午再提醒")
                    }
                }
                reload()
            }
            .show()
    }

    private fun select(next: Filter) {
        filter = next
        if (!::binding.isInitialized) return
        binding.morePanel.isVisible = false
        mapOf(
            Filter.OPEN to binding.filterOpen,
            Filter.TODAY to binding.filterToday,
            Filter.MUST to binding.filterMust,
            Filter.OVERDUE to binding.filterOverdue,
            Filter.DONE to binding.filterDone
        ).forEach { (value, view) -> view.isSelected = value == next }
        render()
    }

    private fun remove(todo: Todo) {
        if (pendingDeletes.containsKey(todo.id)) return
        prefs.edit().putBoolean(KEY_SWIPE, true).apply()
        Reminders.forget(this, todo.id)
        all = all.filterNot { it.id == todo.id }
        render()
        val job = DeleteJob(todo)
        pendingDeletes[todo.id] = job
        snackbar("已删除")
            .setAction("撤销") {
                job.undone = true
                pendingDeletes.remove(todo.id)
                restore(todo)
            }
            .addCallback(object : Snackbar.Callback() {
                override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                    if (event == DISMISS_EVENT_ACTION || job.undone) return
                    job.dismissed = true
                    purgeIfSettled(job)
                }
            })
            .show()
        Io.run {
            try {
                Sync.delete(applicationContext, todo)
                Io.ui {
                    job.deleted = true
                    pendingDeletes.remove(todo.id)
                    purgeIfSettled(job)
                }
            } catch (error: Exception) {
                Io.ui {
                    pendingDeletes.remove(todo.id)
                    if (!job.undone && !isDestroyed) {
                        all = all + todo
                        render()
                        toast(error.message ?: "删除失败")
                    }
                }
            }
        }
    }

    private fun purgeIfSettled(job: DeleteJob) {
        if (job.deleted && job.dismissed && !job.undone) {
            Io.run { store.purgeUnused(job.todo.imagePaths) }
        }
    }

    private fun restore(todo: Todo) {
        Io.run {
            try {
                val saved = Sync.create(applicationContext, todo.asDraft())
                val restored = if (todo.completedAt != null) {
                    Sync.update(applicationContext, saved.copy(completedAt = todo.completedAt))
                } else {
                    saved
                }
                Reminders.sync(applicationContext, restored)
                Io.ui { reload() }
            } catch (error: Exception) {
                Io.ui { toast(error.message ?: "撤销失败") }
            }
        }
    }

    private fun snackbar(text: String): Snackbar {
        return Snackbar.make(binding.root, text, Snackbar.LENGTH_LONG)
            .setAnchorView(binding.composer)
            .setBackgroundTint(ContextCompat.getColor(this, R.color.ink))
            .setTextColor(ContextCompat.getColor(this, R.color.card))
            .setActionTextColor(ContextCompat.getColor(this, R.color.paper))
    }

    private val swipeCallback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ) = false

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            val todo = adapter.itemAt(viewHolder.bindingAdapterPosition) ?: return
            viewHolder.itemView.post { remove(todo) }
        }

        override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
            return if (adapter.itemAt(viewHolder.bindingAdapterPosition) == null) 0 else super.getSwipeDirs(recyclerView, viewHolder)
        }

        override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder) = 0.38f

        override fun onChildDraw(
            canvas: Canvas,
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            dX: Float,
            dY: Float,
            actionState: Int,
            isCurrentlyActive: Boolean
        ) {
            if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX < 0f) {
                val item = viewHolder.itemView
                val density = item.resources.displayMetrics.density
                swipeRect.set(item.right + dX, item.top.toFloat(), item.right.toFloat(), item.bottom.toFloat())
                canvas.drawRoundRect(swipeRect, 16f * density, 16f * density, swipeFill)
                val text = "删除"
                val x = item.right - 18f * density - swipeLabel.measureText(text)
                val y = item.top + item.height / 2f - (swipeLabel.descent() + swipeLabel.ascent()) / 2f
                if (dX < -56f * density) canvas.drawText(text, x, y, swipeLabel)
            }
            super.onChildDraw(canvas, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
        }
    }

    private fun toggle(todo: Todo, done: Boolean) {
        val updated = todo.copy(completedAt = if (done) System.currentTimeMillis() else null)
        all = all.map { if (it.id == todo.id) updated else it }
        render()
        Reminders.sync(this, updated)
        Io.run {
            try {
                val saved = Sync.update(applicationContext, updated)
                Reminders.sync(applicationContext, saved)
                Io.ui {
                    if (isDestroyed) return@ui
                    all = all.map { if (it.id == todo.id) saved else it }
                    if (done) {
                        snackbar("已完成")
                            .setAction("撤销") { toggle(saved, false) }
                            .show()
                    }
                }
            } catch (error: Exception) {
                Io.ui { toast((error.message ?: "同步失败") + "，已先记在本机") }
            }
        }
    }

    private fun openNotificationSettings() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !askedNotify
        ) {
            askedNotify = true
            notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
        if (intent.resolveActivity(packageManager) != null) startActivity(intent)
    }

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < 31) return
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.parse("package:$packageName")
        }
        if (intent.resolveActivity(packageManager) != null) startActivity(intent)
    }

    private enum class Filter { OPEN, TODAY, MUST, OVERDUE, DONE }

    private data class ClipOffer(val signature: String, val text: String, val imageUri: Uri?)

    private class DeleteJob(val todo: Todo) {
        var undone = false
        var deleted = false
        var dismissed = false
    }

    companion object {
        const val ACTION_ADD = "com.shiban.app.action.ADD"
        const val EXTRA_FILTER = "filter"
        const val FILTER_OVERDUE = "overdue"
        private const val KEY_SWIPE = "seen_swipe"
        private const val KEY_CLIP = "clip_signature"
        private const val SYNC_GAP_MS = 15_000L
    }
}
