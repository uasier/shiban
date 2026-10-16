package com.shiban.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.shiban.app.R
import com.shiban.app.ai.Parser
import com.shiban.app.data.Levels
import com.shiban.app.data.OfflineDrafts
import com.shiban.app.data.ParsedItem
import com.shiban.app.data.SettingsStore
import com.shiban.app.data.Sync
import com.shiban.app.data.TodoDraft
import com.shiban.app.data.TodoStore
import com.shiban.app.net.ApiClient
import com.shiban.app.net.ApiException
import com.shiban.app.databinding.ActivityReviewBinding
import com.shiban.app.databinding.ItemReviewBinding
import com.shiban.app.reminder.Reminders
import com.shiban.app.util.Io
import com.shiban.app.util.Thumbs
import com.shiban.app.util.TimeFmt
import com.shiban.app.util.toast

class ReviewActivity : AppCompatActivity() {
    private lateinit var binding: ActivityReviewBinding
    private val store by lazy { TodoStore.get(this) }
    private var sourceText: String? = null
    private var imagePaths = arrayListOf<String>()
    private var auto = true
    private lateinit var jobKey: String
    private var applied = false
    private val cards = mutableListOf<CardBinder>()
    private var pendingSave: List<TodoDraft>? = null

    private val notifyPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingSave?.let { persist(it) }
        pendingSave = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        sourceText = intent.getStringExtra(EXTRA_TEXT)
        imagePaths = intent.getStringArrayListExtra(EXTRA_IMAGES) ?: arrayListOf()
        auto = intent.getBooleanExtra(EXTRA_AUTO, true)
        jobKey = imagePaths.joinToString("|") + "#" + sourceText.hashCode() + "#" + auto
        if (!auto) binding.title.text = "新建待办"
        showSource()
        binding.cancel.setOnClickListener { finish() }
        binding.reparse.setOnClickListener { startParse(force = true) }
        binding.save.setOnClickListener { save() }
        binding.addItem.setOnClickListener { addCard(blankItem()) }
        binding.openSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.sourceText.setOnClickListener {
            binding.sourceText.maxLines = if (binding.sourceText.maxLines == 6) Integer.MAX_VALUE else 6
        }
        if (auto && SettingsStore(this).isLoggedIn) {
            startParse(force = false)
        } else {
            binding.openSettings.isVisible = !SettingsStore(this).isLoggedIn
            if (!SettingsStore(this).isLoggedIn && auto) {
                binding.error.isVisible = true
                binding.error.text = "还没登录。可以先手动填写，或去登录识办账号。"
            }
            addCard(fallbackItem())
        }
    }

    override fun onStart() {
        super.onStart()
        ParseJob.listener = { text, error ->
            if (!isDestroyed && !isFinishing) applyResult(text, error)
        }
        if (!ParseJob.running && ParseJob.key == jobKey && !applied &&
            (ParseJob.result != null || ParseJob.error != null)
        ) {
            applyResult(ParseJob.result, ParseJob.error)
        }
    }

    override fun onResume() {
        super.onResume()
        val loggedIn = SettingsStore(this).isLoggedIn
        binding.openSettings.isVisible = !loggedIn
        if (auto && loggedIn && !applied && cards.isEmpty() && !ParseJob.running) startParse(force = false)
    }

    override fun onStop() {
        ParseJob.listener = null
        super.onStop()
    }

    private fun showSource() {
        if (imagePaths.isNotEmpty()) {
            binding.imageScroll.isVisible = true
            val density = resources.displayMetrics.density
            val size = (84 * density).toInt()
            val gap = (8 * density).toInt()
            imagePaths.forEach { path ->
                val image = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = gap }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = ContextCompat.getDrawable(this@ReviewActivity, R.drawable.bg_thumb)
                    tag = path
                }
                binding.imageRow.addView(image)
                Thumbs.load(path, 240) { bitmap ->
                    if (isDestroyed || image.tag != path || bitmap == null) return@load
                    image.setImageBitmap(bitmap)
                }
            }
        }
        if (!sourceText.isNullOrBlank()) {
            binding.sourceText.isVisible = true
            binding.sourceText.text = sourceText
        }
        binding.sourceCard.isVisible = imagePaths.isNotEmpty() || !sourceText.isNullOrBlank()
    }

    private fun startParse(force: Boolean) {
        val settings = SettingsStore(this)
        if (!settings.isLoggedIn) {
            binding.openSettings.isVisible = true
            binding.error.isVisible = true
            binding.error.text = "还没登录。可以先手动填写，或去登录识办账号。"
            if (cards.isEmpty()) addCard(fallbackItem())
            return
        }
        applied = false
        binding.loading.isVisible = true
        binding.loadingText.text = "正在交给识办服务识别…"
        binding.error.isVisible = false
        binding.status.isVisible = false
        ParseJob.start(applicationContext, jobKey, sourceText, imagePaths, force)
    }

    private fun applyResult(text: String?, error: String?) {
        if (applied) return
        applied = true
        binding.loading.isVisible = false
        if (!error.isNullOrBlank()) {
            binding.error.isVisible = true
            binding.error.text = error
            binding.status.isVisible = true
            binding.status.text = "识别失败。可以改完手动保存，或重试。"
            if (cards.isEmpty()) addCard(fallbackItem())
            return
        }
        try {
            showItems(Parser.parse(text.orEmpty()))
        } catch (_: Exception) {
            binding.error.isVisible = true
            binding.error.text = "没能解析模型返回的 JSON。\n${text.orEmpty().take(280)}"
            binding.status.isVisible = true
            binding.status.text = "可以把原文改成一条待办再保存。"
            if (cards.isEmpty()) addCard(fallbackItem())
        }
    }

    private fun showItems(items: List<ParsedItem>) {
        binding.cards.removeAllViews()
        cards.clear()
        binding.error.isVisible = false
        binding.status.isVisible = true
        if (items.isEmpty()) {
            binding.status.text = "没有识别出待办。可以手动补一条，或换种分享再试。"
            addCard(fallbackItem())
        } else {
            binding.status.text = "识别出 ${items.size} 条。请核对时间、地点和必要性，模型可能看错。"
            items.forEach { addCard(it) }
        }
    }

    private fun addCard(item: ParsedItem) {
        val card = ItemReviewBinding.inflate(layoutInflater, binding.cards, false)
        val binder = CardBinder(card, item)
        cards += binder
        binding.cards.addView(card.root)
        card.remove.setOnClickListener {
            binding.cards.removeView(card.root)
            cards.remove(binder)
            renumber()
        }
        renumber()
    }

    private fun renumber() {
        cards.forEachIndexed { index, card -> card.binding.index.text = "事项 ${index + 1}" }
    }

    private fun save() {
        val drafts = cards.mapNotNull { it.read() }
        if (drafts.isEmpty()) {
            toast("先写上要做的事")
            return
        }
        val needsNotify = drafts.any { (it.timeMillis ?: 0) > System.currentTimeMillis() }
        val missingPermission = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsNotify && missingPermission) {
            pendingSave = drafts
            notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            persist(drafts)
        }
    }

    private fun persist(drafts: List<TodoDraft>) {
        binding.save.isEnabled = false
        val loggedIn = SettingsStore(this).isLoggedIn
        val app = applicationContext
        Io.run {
            if (!loggedIn) {
                drafts.forEach { Sync.keepLocal(app, it) }
                Io.ui {
                    if (isDestroyed) return@ui
                    toast("已先记在本机，登录后会同步")
                    leaveToList(openSettings = false)
                }
                return@run
            }
            var index = 0
            var remote = 0
            var local = 0
            var expired = false
            while (index < drafts.size) {
                try {
                    val saved = Sync.create(app, drafts[index])
                    Reminders.sync(app, saved)
                    remote++
                    index++
                } catch (error: Exception) {
                    if (!Sync.canKeep(error)) {
                        val left = drafts.drop(index)
                        Io.ui {
                            if (isDestroyed) return@ui
                            if (remote > 0) showDrafts(left)
                            binding.save.isEnabled = true
                            toast(if (remote > 0) "已保存 $remote 条，其余没存上" else (error.message ?: "保存失败"))
                        }
                        return@run
                    }
                    expired = error is ApiException && error.httpCode == 401
                    drafts.drop(index).forEach { Sync.keepLocal(app, it) }
                    local = drafts.size - index
                    index = drafts.size
                }
            }
            Io.ui {
                if (isDestroyed) return@ui
                toast(
                    when {
                        expired -> "登录已失效，已先记在本机"
                        local > 0 && remote == 0 -> "网络不稳，已先记在本机"
                        local > 0 -> "已保存，其中 $local 条先记在本机"
                        else -> "已保存 $remote 条"
                    }
                )
                leaveToList(openSettings = expired)
            }
        }
    }

    private fun showDrafts(drafts: List<TodoDraft>) {
        binding.cards.removeAllViews()
        cards.clear()
        drafts.forEach { draft ->
            addCard(
                ParsedItem(
                    content = draft.content,
                    timeMillis = draft.timeMillis,
                    timeText = draft.timeText,
                    location = draft.location,
                    importance = draft.importance,
                    necessity = draft.necessity,
                    note = draft.note
                )
            )
        }
    }

    private fun leaveToList(openSettings: Boolean) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        if (openSettings) startActivity(Intent(this, SettingsActivity::class.java))
        finish()
    }

    private fun fallbackItem(): ParsedItem {
        if (sourceText.isNullOrBlank() && imagePaths.isEmpty()) return blankItem()
        val draft = OfflineDrafts.fromShare(sourceText, imagePaths).firstOrNull()
            ?: OfflineDrafts.fromLine(sourceText.orEmpty(), imagePaths)
        return ParsedItem(
            content = draft.content.ifBlank { if (imagePaths.isNotEmpty()) "查看图片里的事项" else "" },
            timeMillis = draft.timeMillis,
            timeText = draft.timeText,
            location = draft.location,
            importance = draft.importance,
            necessity = draft.necessity,
            note = draft.note
        )
    }

    private fun blankItem() = ParsedItem("", null, null, null, Levels.MEDIUM, Levels.SHOULD, null)

    private inner class CardBinder(val binding: ItemReviewBinding, item: ParsedItem) {
        var timeMillis: Long? = item.timeMillis

        init {
            binding.content.setText(item.content)
            binding.location.setText(item.location.orEmpty())
            binding.note.setText(item.note.orEmpty())
            binding.timeOriginal.tag = item.timeText
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
            refreshTime()
            binding.timeButton.setOnClickListener {
                TimeFmt.pick(this@ReviewActivity, timeMillis) {
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
        }

        fun read(): TodoDraft? {
            val content = binding.content.text?.toString()?.trim().orEmpty()
            if (content.isEmpty()) return null
            return TodoDraft(
                content = content,
                timeMillis = timeMillis,
                timeText = binding.timeOriginal.tag as? String,
                location = binding.location.text?.toString()?.trim()?.ifBlank { null },
                importance = when (binding.importance.checkedButtonId) {
                    R.id.impHigh -> Levels.HIGH
                    R.id.impLow -> Levels.LOW
                    else -> Levels.MEDIUM
                },
                necessity = when (binding.necessity.checkedButtonId) {
                    R.id.necMust -> Levels.MUST
                    R.id.necOptional -> Levels.OPTIONAL
                    else -> Levels.SHOULD
                },
                note = binding.note.text?.toString()?.trim()?.ifBlank { null },
                sourceText = sourceText,
                imagePaths = imagePaths.toList()
            )
        }

        private fun refreshTime() {
            binding.timeButton.text = timeMillis?.let { TimeFmt.format(it) } ?: "选择时间"
            val origin = binding.timeOriginal.tag as? String
            binding.timeOriginal.isVisible = !origin.isNullOrBlank()
            binding.timeOriginal.text = "原文：$origin"
        }
    }

    companion object {
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_IMAGES = "images"
        private const val EXTRA_AUTO = "auto"

        fun intent(context: Context, text: String?, images: List<String>, auto: Boolean): Intent {
            return Intent(context, ReviewActivity::class.java).apply {
                putExtra(EXTRA_TEXT, text)
                putStringArrayListExtra(EXTRA_IMAGES, ArrayList(images))
                putExtra(EXTRA_AUTO, auto)
            }
        }

        fun manual(context: Context): Intent = intent(context, null, emptyList(), auto = false)
    }
}

private object ParseJob {
    private var generation = 0

    @Volatile
    var running = false
    var key: String? = null
    var result: String? = null
    var error: String? = null
    var listener: ((String?, String?) -> Unit)? = null

    fun start(context: Context, jobKey: String, text: String?, images: List<String>, force: Boolean) {
        if (!force && key == jobKey && (running || result != null || error != null)) return
        key = jobKey
        result = null
        error = null
        running = true
        val generationNow = ++generation
        val app = context.applicationContext
        Io.run {
            val outcome = try {
                ApiClient(SettingsStore(app)).parse(text, images) to null
            } catch (e: Exception) {
                null to (e.message ?: "识别失败")
            }
            if (generationNow != generation) return@run
            running = false
            result = outcome.first
            error = outcome.second
            val callback = listener
            if (callback != null) Io.ui { callback(result, error) }
        }
    }
}
