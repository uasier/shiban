package com.shiban.app.util

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.shiban.app.R
import java.util.Calendar

object TimeFmt {
    private val week = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")

    fun format(millis: Long?): String {
        if (millis == null) return "无时间"
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply { timeInMillis = millis }
        val time = "%02d:%02d".format(target.get(Calendar.HOUR_OF_DAY), target.get(Calendar.MINUTE))
        val weekday = week[target.get(Calendar.DAY_OF_WEEK) - 1]
        val date = "${target.get(Calendar.MONTH) + 1}月${target.get(Calendar.DAY_OF_MONTH)}日"
        return when {
            isSameDay(now, target) -> "今天 $time"
            isSameDay(tomorrow(), target) -> "明天 $time"
            now.get(Calendar.YEAR) == target.get(Calendar.YEAR) -> "$date $weekday $time"
            else -> "${target.get(Calendar.YEAR)}年$date $weekday $time"
        }
    }

    fun isToday(millis: Long): Boolean {
        return isSameDay(Calendar.getInstance(), Calendar.getInstance().apply { timeInMillis = millis })
    }

    fun isOverdue(millis: Long?): Boolean = millis != null && millis < System.currentTimeMillis()

    fun dueHint(millis: Long?, now: Long = System.currentTimeMillis()): String? {
        if (millis == null) return null
        val delta = millis - now
        val abs = kotlin.math.abs(delta)
        if (abs < 60_000) return if (delta < 0) "刚刚到点" else "即将到点"
        val amount = when {
            abs < 60 * 60_000 -> "${abs / 60_000} 分钟"
            abs < 24 * 60 * 60_000 -> "${abs / 3_600_000} 小时"
            else -> return null
        }
        return if (delta < 0) "已过期 $amount" else "还有 $amount"
    }

    fun minutesUntil(target: Long, now: Long = System.currentTimeMillis()): Int {
        return ((target - now) / 60_000L).toInt().coerceIn(1, 7 * 24 * 60)
    }

    fun metaLine(timeMillis: Long?, timeText: String?, location: String?, snoozeAt: Long?): String {
        val parts = mutableListOf<String>()
        if (snoozeAt != null) {
            val hint = dueHint(snoozeAt)
            parts += if (hint != null && hint.startsWith("还有")) "稍后${hint.removePrefix("还有")}" else "稍后提醒"
        } else {
            dueHint(timeMillis)?.let { parts += it }
        }
        if (timeMillis != null) parts += format(timeMillis)
        else if (!timeText.isNullOrBlank()) parts += timeText
        if (!location.isNullOrBlank()) parts += location
        return parts.joinToString(" · ")
    }

    fun fillShortcuts(row: LinearLayout, onPick: (Long) -> Unit) {
        row.removeAllViews()
        val density = row.resources.displayMetrics.density
        val padH = (12 * density).toInt()
        val padV = (6 * density).toInt()
        val gap = (8 * density).toInt()
        shortcuts().forEach { (label, millis) ->
            val chip = TextView(row.context).apply {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(context, R.color.ink))
                background = ContextCompat.getDrawable(context, R.drawable.bg_search)
                setPadding(padH, padV, padH, padV)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = gap }
                setOnClickListener { onPick(millis) }
            }
            row.addView(chip)
        }
    }

    fun shortcuts(now: Long = System.currentTimeMillis()): List<Pair<String, Long>> {
        val base = Calendar.getInstance().apply { timeInMillis = now }
        val evening = shift(base, 0, 20, 0)
        return listOf(
            "10分钟后" to now + 10 * 60_000,
            "1小时后" to now + 60 * 60_000,
            (if (evening > now) "今晚" else "明晚") to if (evening > now) evening else shift(base, 1, 20, 0),
            "明天上午" to shift(base, 1, 9, 0),
            "明天下午" to shift(base, 1, 15, 0)
        )
    }

    private fun shift(base: Calendar, dayOffset: Int, hour: Int, minute: Int): Long {
        return (base.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, dayOffset)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    fun pick(activity: Activity, current: Long?, onResult: (Long) -> Unit) {
        val seed = Calendar.getInstance().apply { if (current != null) timeInMillis = current }
        DatePickerDialog(
            activity,
            { _, year, month, day ->
                TimePickerDialog(
                    activity,
                    { _, hour, minute ->
                        val chosen = Calendar.getInstance()
                        chosen.set(year, month, day, hour, minute, 0)
                        chosen.set(Calendar.MILLISECOND, 0)
                        onResult(chosen.timeInMillis)
                    },
                    seed.get(Calendar.HOUR_OF_DAY),
                    seed.get(Calendar.MINUTE),
                    true
                ).show()
            },
            seed.get(Calendar.YEAR),
            seed.get(Calendar.MONTH),
            seed.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun tomorrow(): Calendar = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }

    private fun isSameDay(a: Calendar, b: Calendar): Boolean {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    }
}
