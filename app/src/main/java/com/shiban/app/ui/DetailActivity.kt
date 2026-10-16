package com.shiban.app.ui

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.shiban.app.R
import com.shiban.app.ai.Parser
import com.shiban.app.data.Levels
import com.shiban.app.data.ParsedItem
import com.shiban.app.data.SettingsStore
import com.shiban.app.data.Sync
import com.shiban.app.data.Todo
import com.shiban.app.data.TodoStore
import com.shiban.app.net.ApiClient
import com.shiban.app.databinding.ActivityDetailBinding
import com.shiban.app.reminder.Reminders
import com.shiban.app.util.Io
import com.shiban.app.util.Thumbs
import com.shiban.app.util.TimeFmt
import com.shiban.app.util.toast

class DetailActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDetailBinding
    private val store by lazy { TodoStore.get(this) }
    private var id: Long = -1
    private var timeMillis: Long? = null
    private var timeText: String? = null
    private var imagePaths: List<String> = emptyList()
    private var sourceText: String? = null
    private var createdAt: Long = 0
    private var completedAt: Long? = null
    private var loaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        id = intent.getLongExtra(EXTRA_ID, -1)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = saveOrLeave()
        })
        binding.back.setOnClickListener { saveOrLeave() }
        binding.timeButton.setOnClickListener {
            TimeFmt.pick(this, timeMillis) {
                timeMillis = it
                refreshTime()
            }
        }
        binding.timeClear.setOnClickListener {
            timeMillis = null
            refreshTime()
        }
        TimeFmt.fillShortcuts(binding.timeQuick) {
            timeMillis = it
            refreshTime()
        }
        binding.save.setOnClickListener { persist(finishAfter = true) }
        binding.complete.setOnClickListener {
            if (binding.content.text?.toString()?.trim().isNullOrEmpty()) {
                toast("内容不能为空")
                return@setOnClickListener
            }
            val nextDone = completedAt == null
            completedAt = if (nextDone) System.currentTimeMillis() else null
            refreshCompleteLabel()
            persist(finishAfter = false, success = if (nextDone) "已完成" else "已恢复为待办")
        }
        binding.delete.setOnClickListener { confirmDelete() }
        binding.reparse.setOnClickListener { reparse() }
        binding.sourceText.setOnClickListener {
            binding.sourceText.maxLines = if (binding.sourceText.maxLines == 8) Integer.MAX_VALUE else 8
        }
    }

    override fun onResume() {
        super.onResume()
        val todo = store.get(id)
        if (!loaded) {
            if (todo == null) {
                finish()
                return
            }
            bind(todo)
            loaded = true
            return
        }
        if (todo == null) return
        val fresh = todo.imagePaths.filter { java.io.File(it).exists() }
        val shown = imagePaths.filter { java.io.File(it).exists() }
        if (fresh.isNotEmpty() && fresh != shown) {
            imagePaths = todo.imagePaths
            showImages(imagePaths)
        }
        binding.reparse.isVisible = canReparse()
    }

    private fun bind(todo: Todo) {
        timeMillis = todo.timeMillis
        timeText = todo.timeText
        imagePaths = todo.imagePaths
        sourceText = todo.sourceText
        createdAt = todo.createdAt
        completedAt = todo.completedAt
        binding.content.setText(todo.content)
        binding.location.setText(todo.location.orEmpty())
        binding.note.setText(todo.note.orEmpty())
        binding.importance.check(
            when (todo.importance) {
                Levels.HIGH -> R.id.impHigh
                Levels.LOW -> R.id.impLow
                else -> R.id.impMedium
            }
        )
        binding.necessity.check(
            when (todo.necessity) {
                Levels.MUST -> R.id.necMust
                Levels.OPTIONAL -> R.id.necOptional
                else -> R.id.necShould
            }
        )
        refreshTime()
        refreshCompleteLabel()
        binding.created.text = "创建于 ${TimeFmt.format(todo.createdAt)}"
        val hasSource = !todo.sourceText.isNullOrBlank()
        binding.sourceLabel.isVisible = hasSource
        binding.sourceText.isVisible = hasSource
        binding.sourceText.text = todo.sourceText
        binding.sourceText.maxLines = 8
        binding.reparse.isVisible = canReparse()
        showImages(todo.imagePaths)
    }

    private fun canReparse(): Boolean {
        return !sourceText.isNullOrBlank() || imagePaths.any { java.io.File(it).exists() }
    }

    private fun reparse() {
        if (!SettingsStore(this).isLoggedIn) {
            toast("请先登录后再识别")
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        if (!binding.reparse.isEnabled) return
        binding.reparse.isEnabled = false
        val text = sourceText
        val images = imagePaths
        Io.run {
            try {
                val raw = ApiClient(SettingsStore(applicationContext)).parse(text, images)
                val items = Parser.parse(raw)
                Io.ui {
                    if (isDestroyed) return@ui
                    binding.reparse.isEnabled = true
                    if (items.isEmpty()) {
                        toast("没有识别出待办")
                        return@ui
                    }
                    if (items.size == 1) {
                        applyParsed(items[0])
                        toast("已填入识别结果，确认后保存")
                        return@ui
                    }
                    AlertDialog.Builder(this)
                        .setTitle("识别出 ${items.size} 条")
                        .setItems(items.map { it.content }.toTypedArray()) { _, which ->
                            applyParsed(items[which])
                            toast("已填入，确认后保存")
                        }
                        .setNegativeButton("取消", null)
                        .setPositiveButton("全部另存") { _, _ -> saveParsed(items) }
                        .show()
                }
            } catch (error: Exception) {
                Io.ui {
                    if (isDestroyed) return@ui
                    binding.reparse.isEnabled = true
                    toast(error.message ?: "识别失败")
                }
            }
        }
    }

    private fun applyParsed(item: ParsedItem) {
        binding.content.setText(item.content)
        binding.location.setText(item.location.orEmpty())
        if (!item.note.isNullOrBlank()) binding.note.setText(item.note)
        if (item.timeMillis != null) {
            timeMillis = item.timeMillis
            timeText = item.timeText
            refreshTime()
        }
        binding.importance.check(
            when (item.importance) {
                Levels.HIGH -> R.id.impHigh
                Levels.LOW -> R.id.impLow
                else -> R.id.impMedium
            }
        )
        binding.necessity.check(
            when (item.necessity) {
                Levels.MUST -> R.id.necMust
                Levels.OPTIONAL -> R.id.necOptional
                else -> R.id.necShould
            }
        )
    }

    private fun saveParsed(items: List<ParsedItem>) {
        val current = binding.content.text?.toString()?.trim().orEmpty()
        val extra = items.filter { it.content.isNotBlank() && it.content != current }
        if (extra.isEmpty()) {
            applyParsed(items.first())
            toast("已填入，确认后保存")
            return
        }
        binding.reparse.isEnabled = false
        val app = applicationContext
        Io.run {
            var count = 0
            extra.forEach { item ->
                val draft = com.shiban.app.data.TodoDraft(
                    content = item.content,
                    timeMillis = item.timeMillis,
                    timeText = item.timeText,
                    location = item.location,
                    importance = item.importance,
                    necessity = item.necessity,
                    note = item.note,
                    sourceText = sourceText,
                    imagePaths = imagePaths
                )
                try {
                    if (SettingsStore(app).isLoggedIn) {
                        val saved = Sync.create(app, draft)
                        Reminders.sync(app, saved)
                    } else {
                        Sync.keepLocal(app, draft)
                    }
                    count++
                } catch (_: Exception) {
                    Sync.keepLocal(app, draft)
                    count++
                }
            }
            Io.ui {
                if (isDestroyed) return@ui
                binding.reparse.isEnabled = true
                toast("已另存 $count 条")
            }
        }
    }

    private fun showImages(paths: List<String>) {
        binding.imageRow.removeAllViews()
        binding.imageScroll.isVisible = paths.isNotEmpty()
        val density = resources.displayMetrics.density
        val size = (168 * density).toInt()
        val gap = (8 * density).toInt()
        paths.forEach { path ->
            val image = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = gap }
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = ContextCompat.getDrawable(this@DetailActivity, R.drawable.bg_thumb)
                contentDescription = "查看图片"
                tag = path
                setOnClickListener { showPreview(path) }
            }
            binding.imageRow.addView(image)
            Thumbs.load(path, 480) { bitmap ->
                if (isDestroyed || image.tag != path || bitmap == null) return@load
                image.setImageBitmap(bitmap)
            }
        }
    }

    private fun showPreview(path: String) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val image = ImageView(this).apply {
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "关闭预览"
            setOnClickListener { dialog.dismiss() }
        }
        dialog.setContentView(image)
        dialog.show()
        Thumbs.decode(path, 1600) { bitmap ->
            if (dialog.isShowing && bitmap != null) image.setImageBitmap(bitmap)
        }
    }

    private fun refreshTime() {
        binding.timeButton.text = timeMillis?.let { TimeFmt.format(it) } ?: "选择时间"
        binding.timeOriginal.isVisible = !timeText.isNullOrBlank()
        binding.timeOriginal.text = "原文：$timeText"
    }

    private fun refreshCompleteLabel() {
        binding.complete.text = if (completedAt == null) "完成" else "恢复未完成"
    }

    private fun saveOrLeave() {
        if (!loaded) {
            leave()
            return
        }
        val content = binding.content.text?.toString()?.trim().orEmpty()
        if (content.isEmpty()) {
            if (!dirty()) {
                leave()
                return
            }
            AlertDialog.Builder(this)
                .setTitle("放弃修改？")
                .setMessage("内容是空的，这次修改不会保存。")
                .setNegativeButton("继续编辑", null)
                .setPositiveButton("放弃") { _, _ -> leave() }
                .show()
            return
        }
        if (!dirty()) {
            leave()
            return
        }
        persist(finishAfter = true, success = null)
    }

    private fun leave() {
        if (isTaskRoot) startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun dirty(): Boolean {
        val existing = store.get(id) ?: return false
        return binding.content.text?.toString()?.trim().orEmpty() != existing.content ||
            timeMillis != existing.timeMillis ||
            binding.location.text?.toString()?.trim()?.ifBlank { null } != existing.location ||
            binding.note.text?.toString()?.trim()?.ifBlank { null } != existing.note ||
            importance() != existing.importance ||
            necessity() != existing.necessity ||
            completedAt != existing.completedAt
    }

    private fun importance(): String = when (binding.importance.checkedButtonId) {
        R.id.impHigh -> Levels.HIGH
        R.id.impLow -> Levels.LOW
        else -> Levels.MEDIUM
    }

    private fun necessity(): String = when (binding.necessity.checkedButtonId) {
        R.id.necMust -> Levels.MUST
        R.id.necOptional -> Levels.OPTIONAL
        else -> Levels.SHOULD
    }

    private fun persist(finishAfter: Boolean, success: String? = "已保存") {
        val content = binding.content.text?.toString()?.trim().orEmpty()
        if (content.isEmpty()) {
            toast("内容不能为空")
            return
        }
        val existing = store.get(id) ?: return
        val updated = existing.copy(
            content = content,
            timeMillis = timeMillis,
            timeText = timeText,
            location = binding.location.text?.toString()?.trim()?.ifBlank { null },
            importance = importance(),
            necessity = necessity(),
            note = binding.note.text?.toString()?.trim()?.ifBlank { null },
            sourceText = sourceText,
            imagePaths = imagePaths,
            createdAt = createdAt,
            completedAt = completedAt
        )
        binding.save.isEnabled = false
        binding.complete.isEnabled = false
        Io.run {
            try {
                val saved = Sync.update(applicationContext, updated)
                Reminders.sync(
                    applicationContext,
                    saved,
                    dropSnooze = updated.timeMillis != existing.timeMillis
                )
                Io.ui {
                    if (isDestroyed) return@ui
                    binding.save.isEnabled = true
                    binding.complete.isEnabled = true
                    if (success != null) toast(success)
                    if (finishAfter) leave() else refreshCompleteLabel()
                }
            } catch (error: Exception) {
                Io.ui {
                    if (isDestroyed) return@ui
                    binding.save.isEnabled = true
                    binding.complete.isEnabled = true
                    toast((error.message ?: "同步失败") + "，修改已留在本机")
                    if (finishAfter) leave()
                }
            }
        }
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("删除这条待办？")
            .setMessage("删除后不能恢复。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                val existing = store.get(id)
                Reminders.forget(this, id)
                Io.run {
                    try {
                        if (existing != null) Sync.delete(applicationContext, existing) else store.delete(id)
                        Io.ui {
                            toast("已删除")
                            finish()
                        }
                    } catch (error: Exception) {
                        Io.ui { toast(error.message ?: "删除失败") }
                    }
                }
            }
            .show()
    }

    companion object {
        const val EXTRA_ID = "id"
    }
}
