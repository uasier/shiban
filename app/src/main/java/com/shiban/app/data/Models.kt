package com.shiban.app.data

data class Todo(
    val id: Long,
    val content: String,
    val timeMillis: Long?,
    val timeText: String?,
    val location: String?,
    val importance: String,
    val necessity: String,
    val note: String?,
    val sourceText: String?,
    val imagePaths: List<String>,
    val createdAt: Long,
    val completedAt: Long?,
    val serverId: String? = null,
    val updatedAt: Long = 0,
    val dirty: Boolean = false
)

fun Todo.asDraft() = TodoDraft(
    content, timeMillis, timeText, location, importance, necessity, note, sourceText, imagePaths
)

data class TodoDraft(
    val content: String,
    val timeMillis: Long?,
    val timeText: String?,
    val location: String?,
    val importance: String,
    val necessity: String,
    val note: String?,
    val sourceText: String?,
    val imagePaths: List<String>
)

data class ParsedItem(
    val content: String,
    val timeMillis: Long?,
    val timeText: String?,
    val location: String?,
    val importance: String,
    val necessity: String,
    val note: String?
)

object Levels {
    const val HIGH = "high"
    const val MEDIUM = "medium"
    const val LOW = "low"
    const val MUST = "must"
    const val SHOULD = "should"
    const val OPTIONAL = "optional"

    fun importanceLabel(value: String): String = when (value) {
        HIGH -> "高"
        LOW -> "低"
        else -> "中"
    }

    fun necessityLabel(value: String): String = when (value) {
        MUST -> "必须"
        OPTIONAL -> "可选"
        else -> "应该"
    }

    fun importanceRank(value: String): Int = when (value) {
        HIGH -> 0
        LOW -> 2
        else -> 1
    }

    fun necessityRank(value: String): Int = when (value) {
        MUST -> 0
        OPTIONAL -> 2
        else -> 1
    }
}
