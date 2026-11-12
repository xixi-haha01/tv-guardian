package com.tvguardian.model

import kotlinx.serialization.Serializable

/** 一道题目 */
@Serializable
data class Question(
    val q: String,
    val options: List<String>,
    val ans: Int,
    val exp: String = "",
    val subject: String = "",
    val grade: String = "",
    val from_wrong: Boolean = false
) {
    val subjectName: String
        get() = when (subject) {
            "math" -> "数学"
            "chinese" -> "语文"
            "english" -> "英语"
            else -> subject
        }

    val gradeName: String
        get() = when (grade) {
            "grade3" -> "3年级"
            "grade4" -> "4年级"
            "grade5" -> "5年级"
            else -> grade
        }

    val optionLabels: List<String>
        get() = listOf("A", "B", "C", "D")
}

/** 题库 JSON 结构 */
@Serializable
data class QuestionBank(
    val math: Map<String, List<Question>> = emptyMap(),
    val chinese: Map<String, List<Question>> = emptyMap(),
    val english: Map<String, List<Question>> = emptyMap()
)
