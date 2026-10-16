package com.shiban.app.reminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.shiban.app.R
import com.shiban.app.data.Sync
import com.shiban.app.data.Todo
import com.shiban.app.data.TodoStore
import com.shiban.app.ui.DetailActivity
import com.shiban.app.ui.MainActivity
import com.shiban.app.ui.TodoWidget
import com.shiban.app.util.Io
import com.shiban.app.util.TimeFmt

object Reminders {
    const val CHANNEL = "todo_due"
    const val ACTION_SHOW = "com.shiban.app.REMIND"
    const val ACTION_DONE = "com.shiban.app.DONE"
    const val ACTION_SNOOZE = "com.shiban.app.SNOOZE"
    private const val PREFS_SNOOZE = "shiban_snooze"
    private const val PREFS_NOTIFIED = "shiban_notified"
    private const val MISSED_WINDOW_MS = 18 * 60 * 60_000L

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL, "待办提醒", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "到点提醒你处理识办里的待办"
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    fun activeSnooze(context: Context, id: Long): Long? {
        return snoozeStamp(context, id)?.takeIf { it > System.currentTimeMillis() }
    }

    fun snooze(context: Context, id: Long, until: Long) {
        context.getSharedPreferences(PREFS_SNOOZE, Context.MODE_PRIVATE)
            .edit()
            .putLong(id.toString(), until)
            .apply()
        TodoStore.get(context).get(id)?.let { sync(context, it) }
        NotificationManagerCompat.from(context).cancel(notificationId(id))
        TodoWidget.updateAll(context)
    }

    fun snoozeIn(context: Context, id: Long, minutes: Int) {
        snooze(context, id, System.currentTimeMillis() + minutes.coerceAtLeast(1) * 60_000L)
    }

    fun clearSnooze(context: Context, id: Long) {
        context.getSharedPreferences(PREFS_SNOOZE, Context.MODE_PRIVATE)
            .edit()
            .remove(id.toString())
            .apply()
        TodoWidget.updateAll(context)
    }

    fun clearLocalState(context: Context) {
        context.getSharedPreferences(PREFS_SNOOZE, Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences(PREFS_NOTIFIED, Context.MODE_PRIVATE).edit().clear().apply()
        NotificationManagerCompat.from(context).cancelAll()
    }

    fun sync(context: Context, todo: Todo, dropSnooze: Boolean = false) {
        if (dropSnooze || todo.completedAt != null) clearSnooze(context, todo.id)
        cancel(context, todo.id)
        if (todo.completedAt != null) {
            NotificationManagerCompat.from(context).cancel(notificationId(todo.id))
            return
        }
        val whenMs = activeSnooze(context, todo.id) ?: todo.timeMillis ?: return
        if (whenMs <= System.currentTimeMillis() + 5_000) return
        val alarm = context.getSystemService(AlarmManager::class.java)
        val pending = broadcast(context, todo.id, ACTION_SHOW, requestCode(todo.id), whenMs)
        try {
            if (Build.VERSION.SDK_INT >= 31 && alarm.canScheduleExactAlarms()) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pending)
            } else {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pending)
            }
        } catch (_: SecurityException) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pending)
        }
    }

    fun cancel(context: Context, id: Long) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        alarm.cancel(broadcast(context, id, ACTION_SHOW, requestCode(id), null))
    }

    fun forget(context: Context, id: Long) {
        cancel(context, id)
        clearSnooze(context, id)
        NotificationManagerCompat.from(context).cancel(notificationId(id))
    }

    fun rescheduleAll(context: Context) {
        ensureChannel(context)
        TodoStore.get(context).all().forEach { sync(context, it) }
    }

    fun catchUp(context: Context) {
        ensureChannel(context)
        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences(PREFS_NOTIFIED, Context.MODE_PRIVATE)
        val due = TodoStore.get(context).all().mapNotNull { todo ->
            if (todo.completedAt != null) return@mapNotNull null
            val snooze = snoozeStamp(context, todo.id)
            if (snooze != null && snooze > now) return@mapNotNull null
            val at = snooze ?: todo.timeMillis ?: return@mapNotNull null
            if (at > now - 5_000 || at < now - MISSED_WINDOW_MS) return@mapNotNull null
            if (prefs.getBoolean(notifiedKey(todo.id, at), false)) return@mapNotNull null
            todo to at
        }
        if (due.isEmpty()) return
        val editor = prefs.edit()
        due.forEach { (todo, at) -> editor.putBoolean(notifiedKey(todo.id, at), true) }
        editor.apply()
        due.take(3).forEach { (todo, at) -> notify(context, todo, at, record = false) }
        if (due.size > 3) notifySummary(context, due.size)
    }

    fun notify(context: Context, todo: Todo, at: Long? = null, record: Boolean = true) {
        ensureChannel(context)
        if (record) {
            val stamp = at ?: activeSnooze(context, todo.id) ?: todo.timeMillis
            if (stamp != null) markNotified(context, todo.id, stamp)
        }
        val open = Intent(context, DetailActivity::class.java).apply {
            putExtra(DetailActivity.EXTRA_ID, todo.id)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val content = PendingIntent.getActivity(
            context,
            requestCode(todo.id),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val done = actionPending(context, todo.id, ACTION_DONE, requestCode(todo.id) + 100_000, null)
        val soon = actionPending(context, todo.id, ACTION_SNOOZE, requestCode(todo.id) + 200_000, 10)
        val hour = actionPending(context, todo.id, ACTION_SNOOZE, requestCode(todo.id) + 300_000, 60)
        val whenText = buildString {
            val snooze = activeSnooze(context, todo.id)
            if (snooze != null) append("稍后提醒 · ")
            append(TimeFmt.format(todo.timeMillis ?: snooze))
            if (!todo.location.isNullOrBlank()) append(" · ").append(todo.location)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(todo.content)
            .setContentText(whenText)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    buildString {
                        append(todo.content)
                        if (whenText.isNotBlank()) append("\n").append(whenText)
                        if (!todo.note.isNullOrBlank()) append("\n").append(todo.note)
                    }
                )
            )
            .setContentIntent(content)
            .setAutoCancel(true)
            .addAction(0, "完成", done)
            .addAction(0, "10分钟", soon)
            .addAction(0, "1小时", hour)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notificationId(todo.id), notification)
        } catch (_: SecurityException) {
        }
    }

    fun notificationId(id: Long): Int = (id and 0x7fffffff).toInt()

    private fun notifySummary(context: Context, count: Int) {
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_FILTER, MainActivity.FILTER_OVERDUE)
        }
        val content = PendingIntent.getActivity(
            context,
            0x51000001,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("有 $count 条待办已经到点")
            .setContentText("打开识办查看")
            .setContentIntent(content)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(0x51000001, notification)
        } catch (_: SecurityException) {
        }
    }

    private fun snoozeStamp(context: Context, id: Long): Long? {
        val until = context.getSharedPreferences(PREFS_SNOOZE, Context.MODE_PRIVATE).getLong(id.toString(), 0L)
        return until.takeIf { it > 0L }
    }

    private fun markNotified(context: Context, id: Long, at: Long) {
        context.getSharedPreferences(PREFS_NOTIFIED, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(notifiedKey(id, at), true)
            .apply()
    }

    private fun notifiedKey(id: Long, at: Long) = "$id:$at"

    private fun actionPending(
        context: Context,
        id: Long,
        action: String,
        request: Int,
        minutes: Int?
    ): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            this.action = action
            putExtra("id", id)
            if (minutes != null) putExtra("minutes", minutes)
        }
        return PendingIntent.getBroadcast(
            context,
            request,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun broadcast(context: Context, id: Long, action: String, request: Int, at: Long?): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            this.action = action
            putExtra("id", id)
            if (at != null) putExtra("at", at)
        }
        return PendingIntent.getBroadcast(
            context,
            request,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun requestCode(id: Long): Int = (id and 0x7fffffff).toInt()
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        Io.run {
            try {
                val id = intent.getLongExtra("id", -1)
                if (id < 0) return@run
                val store = TodoStore.get(app)
                when (intent.action) {
                    Reminders.ACTION_DONE -> {
                        val todo = store.get(id) ?: return@run
                        try {
                            Sync.update(app, todo.copy(completedAt = System.currentTimeMillis()))
                        } catch (_: Exception) {
                        }
                        Reminders.forget(app, id)
                    }
                    Reminders.ACTION_SNOOZE -> {
                        if (store.get(id) == null) return@run
                        Reminders.snoozeIn(app, id, intent.getIntExtra("minutes", 10))
                    }
                    else -> {
                        val todo = store.get(id) ?: return@run
                        if (todo.completedAt != null) return@run
                        val at = intent.getLongExtra("at", 0L).takeIf { it > 0L }
                        Reminders.notify(app, todo, at)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val pending = goAsync()
        val app = context.applicationContext
        Io.run {
            try {
                Reminders.rescheduleAll(app)
                Reminders.catchUp(app)
                TodoWidget.updateAll(app)
            } finally {
                pending.finish()
            }
        }
    }
}
