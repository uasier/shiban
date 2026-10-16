package com.shiban.app.ai

import com.shiban.app.data.Levels
import com.shiban.app.data.ParsedItem
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar

object Parser {
    private val dateTimes = listOf(
        DateTimeFormatter.ISO_LOCAL_DATE_TIME,
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
    )
    private val dates = listOf(
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("yyyy/MM/dd"),
        DateTimeFormatter.ofPattern("yyyy.MM.dd")
    )
    private val chineseDateTime = Regex("""(\d{4})年(\d{1,2})月(\d{1,2})日\s*(\d{1,2})[:：](\d{2})""")
    private val chineseDate = Regex("""(\d{4})年(\d{1,2})月(\d{1,2})日""")

    fun parse(raw: String): List<ParsedItem> {
        val json = extractJson(raw)
        val array = when {
            json.has("items") && json.opt("items") is JSONArray -> json.getJSONArray("items")
            json.has("todos") && json.opt("todos") is JSONArray -> json.getJSONArray("todos")
            json.has("待办") && json.opt("待办") is JSONArray -> json.getJSONArray("待办")
            json.strAny("content", "内容", "title") != null -> JSONArray().put(json)
            else -> JSONArray()
        }
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                toItem(item)?.let { add(it) }
            }
        }
    }

    private fun toItem(item: JSONObject): ParsedItem? {
        var content = item.strAny("content", "内容", "title", "task") ?: return null
        var note = item.strAny("note", "说明", "备注", "reason")
        if (content.length > 200) {
            if (note.isNullOrBlank()) note = content.drop(200).take(80)
            content = content.take(200)
        }
        val timeText = item.strAny("timeText", "time_text", "时间原文", "时间表达")
        val timeRaw = item.strAny("time", "时间", "datetime", "due")
        val timeMillis = parseTime(timeRaw) ?: parseTime(timeText)
        return ParsedItem(
            content = content,
            timeMillis = timeMillis,
            timeText = timeText ?: timeRaw?.takeIf { timeMillis == null },
            location = item.strAny("location", "地点", "place"),
            importance = importance(item.strAny("importance", "重要性", "priority")),
            necessity = necessity(item.strAny("necessity", "必要性")),
            note = note
        )
    }

    private fun extractJson(raw: String): JSONObject {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.removePrefix("```json").removePrefix("```JSON").removePrefix("```").trim()
            if (text.endsWith("```")) text = text.removeSuffix("```").trim()
        }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start >= 0 && end > start) text = text.substring(start, end + 1)
        val arrayStart = text.indexOf('[')
        if (!text.trim().startsWith("{") && arrayStart >= 0) {
            val arrayEnd = text.lastIndexOf(']')
            if (arrayEnd > arrayStart) {
                return JSONObject().put("items", JSONArray(text.substring(arrayStart, arrayEnd + 1)))
            }
        }
        return JSONObject(text)
    }

    fun parseTime(raw: String?): Long? {
        val original = raw?.trim()?.trim('"') ?: return null
        if (original.isEmpty() || original.equals("null", true) || original == "无" || original == "未知" || original == "未提及") {
            return null
        }
        chineseDateTime.find(original)?.let { match ->
            return calendar(
                match.groupValues[1].toInt(),
                match.groupValues[2].toInt(),
                match.groupValues[3].toInt(),
                match.groupValues[4].toInt(),
                match.groupValues[5].toInt()
            )
        }
        chineseDate.find(original)?.let { match ->
            return calendar(
                match.groupValues[1].toInt(),
                match.groupValues[2].toInt(),
                match.groupValues[3].toInt(),
                9,
                0
            )
        }
        val candidates = buildList {
            add(original)
            if (' ' in original && 'T' !in original) add(original.replaceFirst(" ", "T"))
        }
        val zone = ZoneId.systemDefault()
        for (candidate in candidates) {
            try {
                return Instant.parse(candidate).toEpochMilli()
            } catch (_: Exception) {
            }
            for (formatter in dateTimes) {
                try {
                    return LocalDateTime.parse(candidate, formatter).atZone(zone).toInstant().toEpochMilli()
                } catch (_: Exception) {
                }
            }
            for (formatter in dates) {
                try {
                    return LocalDate.parse(candidate, formatter).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
                } catch (_: Exception) {
                }
            }
        }
        return relativeTime(original)
    }

    private val afterPattern = Regex("""(\d+)\s*(?:个)?\s*(小时|分钟)后""")
    private val clockPattern = Regex("""(?<!第)(\d{1,2})\s*[:：点时]\s*(\d{1,2}|半)?""")
    private val chineseClockPattern = Regex("""(?<!第)([零一二两三四五六七八九十]{1,3})\s*点\s*(半|[零一二两三四五六七八九十]{1,3})?""")
    private val weekPattern = Regex("""(下下|下|本|这)?(?:周|星期|礼拜)([一二三四五六日天])""")
    private val monthDayPattern = Regex("""(\d{1,2})月(\d{1,2})[日号]""")

    private fun relativeTime(text: String): Long? {
        if (text.contains("半小时后") || text.contains("半个小时后")) {
            return System.currentTimeMillis() + 30 * 60_000
        }
        afterPattern.find(text)?.let { match ->
            val amount = match.groupValues[1].toIntOrNull() ?: return@let
            val unit = if (match.groupValues[2] == "小时") 60 * 60_000L else 60_000L
            return System.currentTimeMillis() + amount * unit
        }
        val now = Calendar.getInstance()
        val clock = readClock(text)
        monthDayPattern.find(text)?.let { match ->
            return calendar(
                now.get(Calendar.YEAR),
                match.groupValues[1].toInt(),
                match.groupValues[2].toInt(),
                clock?.first ?: 9,
                if (clock == null) 0 else clock.second
            )
        }
        weekPattern.find(text)?.let { match ->
            val target = weekday(match.groupValues[2]) ?: return@let
            return weekdayTime(now, target, weekOffset(now, target, match.groupValues[1]), clock)
        }
        val dayOffset = when {
            text.contains("大后天") -> 3
            text.contains("后天") -> 2
            text.contains("明天") || text.contains("明早") || text.contains("明晚") -> 1
            text.contains("今天") || text.contains("今晚") || text.contains("今早") -> 0
            else -> null
        }
        val hour = clock?.first ?: defaultHour(text)
        if (dayOffset == null) {
            if (hour == null) return null
            val today = shift(now, 0, hour, clock?.second ?: 0)
            return if (today > System.currentTimeMillis()) today else shift(now, 1, hour, clock?.second ?: 0)
        }
        return shift(now, dayOffset, hour ?: 9, if (hour == null) 0 else clock?.second ?: 0)
    }

    private fun readClock(text: String): Pair<Int, Int>? {
        clockPattern.find(text)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return@let
            val minute = minuteOf(match.groupValues[2])
            return normalizeClock(hour, minute, text)
        }
        chineseClockPattern.find(text)?.let { match ->
            val hour = chineseNumber(match.groupValues[1]) ?: return@let
            val minute = if (match.groupValues[2] == "半") 30 else chineseNumber(match.groupValues[2]) ?: 0
            return normalizeClock(hour, minute, text)
        }
        return null
    }

    private fun minuteOf(raw: String): Int {
        if (raw.isBlank()) return 0
        if (raw == "半") return 30
        return raw.toIntOrNull() ?: 0
    }

    private fun normalizeClock(hour: Int, minute: Int, text: String): Pair<Int, Int>? {
        if (minute !in 0..59) return null
        var normalized = hour
        if (normalized <= 12 && normalized < 12 && hasAfternoon(text)) normalized += 12
        if (normalized !in 0..23) return null
        return normalized to minute
    }

    private fun hasAfternoon(text: String): Boolean {
        return text.contains("下午") || text.contains("晚上") || text.contains("傍晚") ||
            text.contains("今晚") || text.contains("明晚")
    }

    private fun defaultHour(text: String): Int? = when {
        text.contains("今晚") || text.contains("明晚") || text.contains("晚上") -> 20
        text.contains("今早") || text.contains("明早") || text.contains("上午") || text.contains("早上") -> 9
        text.contains("中午") -> 12
        text.contains("下午") -> 15
        else -> null
    }

    private fun weekday(label: String): Int? = when (label) {
        "一" -> Calendar.MONDAY
        "二" -> Calendar.TUESDAY
        "三" -> Calendar.WEDNESDAY
        "四" -> Calendar.THURSDAY
        "五" -> Calendar.FRIDAY
        "六" -> Calendar.SATURDAY
        "日", "天" -> Calendar.SUNDAY
        else -> null
    }

    private fun weekOffset(now: Calendar, targetDow: Int, prefix: String): Int {
        if (prefix == "下下") return 2
        if (prefix == "下") return 1
        if (prefix == "本" || prefix == "这") return 0
        val todayFromMonday = daysFromMonday(now.get(Calendar.DAY_OF_WEEK))
        val targetFromMonday = daysFromMonday(targetDow)
        return if (targetFromMonday < todayFromMonday) 1 else 0
    }

    private fun daysFromMonday(dayOfWeek: Int): Int {
        return if (dayOfWeek == Calendar.SUNDAY) 6 else dayOfWeek - Calendar.MONDAY
    }

    private fun weekdayTime(now: Calendar, targetDow: Int, weekOffset: Int, clock: Pair<Int, Int>?): Long {
        val cal = now.clone() as Calendar
        val todayFromMonday = daysFromMonday(cal.get(Calendar.DAY_OF_WEEK))
        val targetFromMonday = daysFromMonday(targetDow)
        cal.add(Calendar.DAY_OF_YEAR, -todayFromMonday + targetFromMonday + weekOffset * 7)
        val hour = clock?.first ?: 9
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, if (clock == null) 0 else clock.second)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
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

    private fun chineseNumber(raw: String): Int? {
        if (raw.isBlank()) return null
        raw.toIntOrNull()?.let { return it }
        val digit = mapOf('零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        if (raw == "十") return 10
        if (raw.startsWith("十") && raw.length == 2) return 10 + (digit[raw[1]] ?: return null)
        if (raw.endsWith("十") && raw.length == 2) return (digit[raw[0]] ?: return null) * 10
        if (raw.length == 3 && raw[1] == '十') {
            val tens = digit[raw[0]] ?: return null
            val ones = digit[raw[2]] ?: return null
            return tens * 10 + ones
        }
        if (raw.length == 1) return digit[raw[0]]
        return null
    }

    private fun calendar(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        return Calendar.getInstance().apply {
            set(year, month - 1, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun importance(raw: String?): String {
        val value = raw?.lowercase()?.trim() ?: return Levels.MEDIUM
        return when {
            value in setOf("high", "h", "p1", "1") || value.contains("高") || value.contains("紧急") || value.contains("important") -> Levels.HIGH
            value in setOf("low", "l", "p3", "p4", "3", "4") || value.contains("低") || value.contains("不重要") -> Levels.LOW
            else -> Levels.MEDIUM
        }
    }

    private fun necessity(raw: String?): String {
        val value = raw?.lowercase()?.trim() ?: return Levels.SHOULD
        return when {
            value.contains("不必") || value.contains("不必要") || value.contains("可选") ||
                value.contains("可有可无") || value == "optional" || value == "maybe" -> Levels.OPTIONAL
            value == "must" || value == "required" || value.contains("必须") || value.contains("必要") || value.contains("一定要") -> Levels.MUST
            else -> Levels.SHOULD
        }
    }

    private fun JSONObject.strAny(vararg keys: String): String? {
        for (key in keys) {
            text(key)?.let { return it }
        }
        return null
    }

    private fun JSONObject.text(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val value = when (val raw = opt(key)) {
            null, JSONObject.NULL -> return null
            is String -> raw
            is Number, is Boolean -> raw.toString()
            else -> return null
        }.trim()
        if (value.isEmpty() || value.equals("null", true) || value == "无" || value == "未知" || value == "未提及") return null
        return value
    }
}
