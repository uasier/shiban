package com.shiban.app.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.shiban.app.R
import com.shiban.app.data.Levels
import com.shiban.app.data.Todo
import com.shiban.app.data.TodoStore
import com.shiban.app.reminder.Reminders
import com.shiban.app.util.Io
import com.shiban.app.util.TimeFmt

class TodoWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        updateAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_DONE) {
            val pending = goAsync()
            val app = context.applicationContext
            val id = intent.getLongExtra(EXTRA_ID, -1)
            Io.run {
                try {
                    if (id >= 0) complete(app, id)
                } finally {
                    updateAll(app)
                    pending.finish()
                }
            }
            return
        }
        super.onReceive(context, intent)
    }

    companion object {
        const val ACTION_DONE = "com.shiban.app.widget.DONE"
        private const val EXTRA_ID = "id"
        private val main = Handler(Looper.getMainLooper())
        private var render: Runnable? = null
        private val rows = arrayOf(
            intArrayOf(R.id.row0, R.id.check0, R.id.open0, R.id.text0, R.id.meta0),
            intArrayOf(R.id.row1, R.id.check1, R.id.open1, R.id.text1, R.id.meta1),
            intArrayOf(R.id.row2, R.id.check2, R.id.open2, R.id.text2, R.id.meta2),
            intArrayOf(R.id.row3, R.id.check3, R.id.open3, R.id.text3, R.id.meta3)
        )

        fun updateAll(context: Context) {
            val app = context.applicationContext
            render?.let { main.removeCallbacks(it) }
            val job = Runnable { render(app) }
            render = job
            main.postDelayed(job, 250)
        }

        private fun complete(context: Context, id: Long) {
            val store = TodoStore.get(context)
            val todo = store.get(id) ?: return
            if (todo.completedAt != null) return
            try {
                com.shiban.app.data.Sync.update(context, todo.copy(completedAt = System.currentTimeMillis()))
            } catch (_: Exception) {
            }
            Reminders.forget(context, id)
        }

        private fun render(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TodoWidget::class.java))
            if (ids.isEmpty()) return
            manager.updateAppWidget(ids, views(context))
        }

        private fun views(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_todo)
            val open = TodoStore.get(context).all()
                .filter { it.completedAt == null }
                .sortedWith(compareBy<Todo> { remindAt(context, it) == null }
                    .thenBy { remindAt(context, it) ?: Long.MAX_VALUE }
                    .thenBy { Levels.necessityRank(it.necessity) })
            views.setTextViewText(R.id.widgetCount, "未完成 ${open.size}")
            views.setOnClickPendingIntent(R.id.widgetRoot, openMain(context))
            views.setOnClickPendingIntent(R.id.widgetMore, openMain(context))
            val shown = open.take(rows.size)
            views.setViewVisibility(R.id.widgetEmpty, if (shown.isEmpty()) View.VISIBLE else View.GONE)
            rows.forEachIndexed { index, ids ->
                val todo = shown.getOrNull(index)
                if (todo == null) {
                    views.setViewVisibility(ids[0], View.GONE)
                    return@forEachIndexed
                }
                views.setViewVisibility(ids[0], View.VISIBLE)
                views.setTextViewText(ids[3], todo.content)
                val meta = meta(context, todo)
                views.setViewVisibility(ids[4], if (meta.isBlank()) View.GONE else View.VISIBLE)
                views.setTextViewText(ids[4], meta)
                val overdue = Reminders.activeSnooze(context, todo.id) == null && TimeFmt.isOverdue(todo.timeMillis)
                views.setTextColor(
                    ids[4],
                    ContextCompat.getColor(context, if (overdue) R.color.danger else R.color.muted)
                )
                views.setOnClickPendingIntent(ids[1], done(context, todo.id))
                views.setOnClickPendingIntent(ids[2], openDetail(context, todo.id))
            }
            val rest = open.size - shown.size
            views.setViewVisibility(R.id.widgetMore, if (rest > 0) View.VISIBLE else View.GONE)
            if (rest > 0) views.setTextViewText(R.id.widgetMore, "还有 $rest 条")
            return views
        }

        private fun remindAt(context: Context, todo: Todo): Long? {
            return Reminders.activeSnooze(context, todo.id) ?: todo.timeMillis
        }

        private fun meta(context: Context, todo: Todo): String {
            val snooze = Reminders.activeSnooze(context, todo.id)
            if (snooze != null) {
                val hint = TimeFmt.dueHint(snooze)
                return if (hint != null && hint.startsWith("还有")) "稍后${hint.removePrefix("还有")}" else "稍后提醒"
            }
            return TimeFmt.dueHint(todo.timeMillis) ?: todo.timeMillis?.let { TimeFmt.format(it) }.orEmpty()
        }

        private fun openMain(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(context, 1, intent, flags())
        }

        private fun openDetail(context: Context, id: Long): PendingIntent {
            val intent = Intent(context, DetailActivity::class.java).apply {
                putExtra(DetailActivity.EXTRA_ID, id)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(context, request(id, 1), intent, flags())
        }

        private fun done(context: Context, id: Long): PendingIntent {
            val intent = Intent(context, TodoWidget::class.java).apply {
                action = ACTION_DONE
                putExtra(EXTRA_ID, id)
            }
            return PendingIntent.getBroadcast(context, request(id, 2), intent, flags())
        }

        private fun request(id: Long, slot: Int): Int = (id and 0xffff).toInt() + slot * 100_000

        private fun flags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }
}
