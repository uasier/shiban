package com.shiban.app.ui

import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.shiban.app.R
import com.shiban.app.data.Levels
import com.shiban.app.data.Todo
import com.shiban.app.databinding.ItemTodoBinding
import com.shiban.app.reminder.Reminders
import com.shiban.app.util.Thumbs
import com.shiban.app.util.TimeFmt

class TodoAdapter(
    private val onOpen: (Todo) -> Unit,
    private val onToggle: (Todo, Boolean) -> Unit,
    private val onDelete: (Todo) -> Unit,
    private val onLater: (Todo) -> Unit,
    private val timeOf: (Todo) -> Long?
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val rows = mutableListOf<Row>()

    init {
        setHasStableIds(true)
    }

    fun submit(data: List<Todo>) {
        val next = buildRows(data)
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = rows.size
            override fun getNewListSize(): Int = next.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean = sameItem(rows[oldPos], next[newPos])
            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean = rows[oldPos] == next[newPos]
        })
        rows.clear()
        rows.addAll(next)
        diff.dispatchUpdatesTo(this)
    }

    fun itemAt(position: Int): Todo? = (rows.getOrNull(position) as? Row.Item)?.todo

    override fun getItemId(position: Int): Long = when (val row = rows[position]) {
        is Row.Header -> headerId(row.title)
        is Row.Item -> row.todo.id
    }

    override fun getItemViewType(position: Int): Int = if (rows[position] is Row.Header) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 0) {
            HeaderHolder(inflater.inflate(R.layout.item_section, parent, false) as TextView)
        } else {
            Holder(ItemTodoBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderHolder).view.text = row.title
            is Row.Item -> (holder as Holder).bind(row)
        }
    }

    override fun getItemCount(): Int = rows.size

    private fun buildRows(data: List<Todo>): List<Row> {
        val groups = linkedMapOf(
            "已过期" to data.filter { it.completedAt == null && TimeFmt.isOverdue(timeOf(it)) },
            "今天" to data.filter { openToday(it) },
            "之后" to data.filter { it.completedAt == null && timeOf(it) != null && !TimeFmt.isToday(timeOf(it)!!) && !TimeFmt.isOverdue(timeOf(it)) },
            "未定时间" to data.filter { it.completedAt == null && timeOf(it) == null },
            "已完成" to data.filter { it.completedAt != null }
        ).filterValues { it.isNotEmpty() }
        return buildList {
            groups.forEach { (title, list) ->
                if (groups.size > 1) add(Row.Header("$title ${list.size}"))
                list.forEachIndexed { index, todo ->
                    add(Row.Item(todo, first = index == 0, last = index == list.lastIndex, remindAt = timeOf(todo)))
                }
            }
        }
    }

    private fun openToday(todo: Todo): Boolean {
        if (todo.completedAt != null) return false
        val at = timeOf(todo) ?: return false
        return TimeFmt.isToday(at) && !TimeFmt.isOverdue(at)
    }

    private fun sameItem(left: Row, right: Row): Boolean = when {
        left is Row.Header && right is Row.Header -> headerId(left.title) == headerId(right.title)
        left is Row.Item && right is Row.Item -> left.todo.id == right.todo.id
        else -> false
    }

    private fun headerId(title: String): Long = when {
        title.startsWith("已过期") -> -1L
        title.startsWith("今天") -> -2L
        title.startsWith("之后") -> -3L
        title.startsWith("未定时间") -> -4L
        title.startsWith("已完成") -> -5L
        else -> -6L
    }

    private class HeaderHolder(val view: TextView) : RecyclerView.ViewHolder(view)

    private sealed class Row {
        data class Header(val title: String) : Row()
        data class Item(val todo: Todo, val first: Boolean, val last: Boolean, val remindAt: Long?) : Row()
    }

    private inner class Holder(private val binding: ItemTodoBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: Row.Item) {
            val todo = row.todo
            val first = row.first
            val last = row.last
            val context = binding.root.context
            val radius = 16f * context.resources.displayMetrics.density
            binding.root.background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(context, R.color.card))
                cornerRadii = floatArrayOf(
                    if (first) radius else 0f, if (first) radius else 0f,
                    if (first) radius else 0f, if (first) radius else 0f,
                    if (last) radius else 0f, if (last) radius else 0f,
                    if (last) radius else 0f, if (last) radius else 0f
                )
            }
            binding.divider.isVisible = !last
            val done = todo.completedAt != null
            binding.content.text = todo.content
            binding.content.paintFlags = if (done) {
                binding.content.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                binding.content.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            }
            binding.content.setTextColor(ContextCompat.getColor(context, if (done) R.color.muted else R.color.ink))
            binding.root.alpha = if (done) 0.62f else 1f

            val snoozeAt = Reminders.activeSnooze(context, todo.id)
            val meta = TimeFmt.metaLine(todo.timeMillis, todo.timeText, todo.location, snoozeAt)
            binding.meta.isVisible = meta.isNotEmpty()
            binding.meta.text = meta
            val overdue = !done && snoozeAt == null && TimeFmt.isOverdue(todo.timeMillis)
            binding.meta.setTextColor(ContextCompat.getColor(context, if (overdue) R.color.danger else R.color.muted))
            val note = todo.note?.trim().orEmpty()
            binding.note.isVisible = note.isNotEmpty() && note != todo.content
            binding.note.text = note

            val marked = overdue || todo.necessity == Levels.MUST
            binding.bar.isVisible = marked
            if (marked) {
                binding.bar.background = GradientDrawable().apply {
                    cornerRadius = 99f * context.resources.displayMetrics.density
                    setColor(ContextCompat.getColor(context, if (overdue) R.color.danger else R.color.ink))
                }
            }
            badge(binding.importance, "重要性 ${Levels.importanceLabel(todo.importance)}", todo.importance == Levels.HIGH)
            badge(binding.necessity, Levels.necessityLabel(todo.necessity), todo.necessity == Levels.MUST)

            binding.check.setOnClickListener(null)
            binding.check.isChecked = done
            binding.check.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onToggle(todo, binding.check.isChecked)
            }
            binding.quickDelete.setOnClickListener { onDelete(todo) }
            binding.root.setOnClickListener { onOpen(todo) }
            binding.root.setOnLongClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onLater(todo)
                true
            }
            bindThumb(todo.imagePaths.firstOrNull())
        }

        private fun bindThumb(path: String?) {
            binding.thumb.tag = path
            if (path == null) {
                binding.thumb.isVisible = false
                binding.thumb.setImageDrawable(null)
                return
            }
            binding.thumb.isVisible = true
            binding.thumb.setImageDrawable(null)
            Thumbs.load(path, 180) { bitmap ->
                if (binding.thumb.tag != path) return@load
                if (bitmap == null) {
                    binding.thumb.isVisible = false
                } else {
                    binding.thumb.setImageBitmap(bitmap)
                }
            }
        }

        private fun badge(view: TextView, label: String, strong: Boolean) {
            val context = view.context
            view.text = label
            view.setTextColor(ContextCompat.getColor(context, if (strong) R.color.ink else R.color.muted))
            val density = context.resources.displayMetrics.density
            view.background = GradientDrawable().apply {
                cornerRadius = 99f * density
                setColor(ContextCompat.getColor(context, if (strong) R.color.seal_soft else R.color.ink_soft))
            }
            val horizontal = (8 * density).toInt()
            val vertical = (3 * density).toInt()
            view.setPadding(horizontal, vertical, horizontal, vertical)
        }
    }
}
