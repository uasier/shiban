package com.shiban.app.data

import com.shiban.app.ai.Parser
import java.io.File

object OfflineDrafts {
    private val urgent = Regex("紧急|马上|立刻|尽快|asap", RegexOption.IGNORE_CASE)
    private val must = Regex("必须|务必|一定要|一定得")
    private val bullet = Regex("""^\s*(?:[-*•·]|(?:\d+|[一二三四五六七八九十]+)[.、.)])\s*""")

    fun fromShare(text: String?, imagePaths: List<String>): List<TodoDraft> {
        val source = text?.trim()?.ifBlank { null }
        val lines = split(source)
        if (lines.isEmpty()) {
            if (imagePaths.isEmpty()) return emptyList()
            return listOf(draft("查看导入的图片", source, imagePaths))
        }
        return lines.mapIndexed { index, line ->
            draft(line, source, imagesFor(imagePaths, lines.size, index))
        }
    }

    fun fromLine(text: String, imagePaths: List<String> = emptyList()): TodoDraft {
        val source = text.trim()
        return draft(source.ifBlank { "查看导入的图片" }, source.ifBlank { null }, imagePaths)
    }

    private fun draft(content: String, source: String?, imagePaths: List<String>): TodoDraft {
        val whenMs = Parser.parseTime(content) ?: Parser.parseTime(source)
        return TodoDraft(
            content = content.take(200),
            timeMillis = whenMs,
            timeText = if (whenMs != null) content.take(32) else null,
            location = null,
            importance = if (urgent.containsMatchIn(content)) Levels.HIGH else Levels.MEDIUM,
            necessity = if (must.containsMatchIn(content)) Levels.MUST else Levels.SHOULD,
            note = null,
            sourceText = source,
            imagePaths = imagePaths.filter { File(it).exists() }
        )
    }

    private fun split(text: String?): List<String> {
        val raw = text?.replace("\r", "")?.trim().orEmpty()
        if (raw.isEmpty()) return emptyList()
        var parts = raw.split(Regex("\n+")).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size == 1) {
            val sentences = parts[0].split(Regex("[。；;]\\s*")).map { it.trim() }.filter { it.length >= 2 }
            if (sentences.size > 1) parts = sentences
        }
        val seen = linkedSetOf<String>()
        val tasks = mutableListOf<String>()
        for (part in parts) {
            val line = part.replace(bullet, "").trim()
            if (line.length < 2 || !seen.add(line)) continue
            tasks += line
            if (tasks.size >= 12) break
        }
        return tasks
    }

    private fun imagesFor(paths: List<String>, count: Int, index: Int): List<String> {
        if (paths.isEmpty()) return emptyList()
        if (count <= 1) return paths
        if (index == 0 && paths.size > count) return listOf(paths[0]) + paths.drop(count)
        return paths.getOrNull(index)?.let { listOf(it) }.orEmpty()
    }
}
