package com.tvguardian.data

import android.content.Context
import android.content.SharedPreferences
import com.tvguardian.model.Question
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 错题 & 设置持久化
 */
class PrefsManager(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---- 设置 ----

    var intervalMinutes: Int
        get() = sp.getInt(KEY_INTERVAL, 20)
        set(v) = sp.edit().putInt(KEY_INTERVAL, v).apply()

    var questionsPerRound: Int
        get() = sp.getInt(KEY_Q_PER_ROUND, 5)
        set(v) = sp.edit().putInt(KEY_Q_PER_ROUND, v).apply()

    var maxWrongBeforeLock: Int
        get() = sp.getInt(KEY_MAX_WRONG, 10)
        set(v) = sp.edit().putInt(KEY_MAX_WRONG, v).apply()

    var lockMinutes: Int
        get() = sp.getInt(KEY_LOCK_MIN, 20)
        set(v) = sp.edit().putInt(KEY_LOCK_MIN, v).apply()

    var highFreqWeight: Int
        get() = sp.getInt(KEY_HF_WEIGHT, 3)
        set(v) = sp.edit().putInt(KEY_HF_WEIGHT, v).apply()

    var selectedSubjects: Set<String>
        get() = sp.getStringSet(KEY_SUBJECTS, setOf("math", "chinese", "english"))
            ?: setOf("math", "chinese", "english")
        set(v) = sp.edit().putStringSet(KEY_SUBJECTS, v).apply()

    var selectedGrades: Set<String>
        get() = sp.getStringSet(KEY_GRADES, setOf("grade3", "grade4", "grade5"))
            ?: setOf("grade3", "grade4", "grade5")
        set(v) = sp.edit().putStringSet(KEY_GRADES, v).apply()

    // ---- 单次观看时长限制 ----

    /** 单次连续观看最大分钟数，0 表示不限制 */
    var sessionLimitMinutes: Int
        get() = sp.getInt(KEY_SESSION_LIMIT, 60)
        set(v) = sp.edit().putInt(KEY_SESSION_LIMIT, v).apply()

    /** 观看时长用完后需休息的分钟数 */
    var sessionRestMinutes: Int
        get() = sp.getInt(KEY_SESSION_REST, 10)
        set(v) = sp.edit().putInt(KEY_SESSION_REST, v).apply()

    /** 本次连续观看开始时间戳（每次解锁/答题完成后重置） */
    var sessionStartTimestamp: Long
        get() = sp.getLong(KEY_SESSION_START, System.currentTimeMillis())
        set(v) = sp.edit().putLong(KEY_SESSION_START, v).apply()

    /** 观看时长锁定结束时间戳，0 表示未锁定 */
    var sessionLockEndTimestamp: Long
        get() = sp.getLong(KEY_SESSION_LOCK_END, 0L)
        set(v) = sp.edit().putLong(KEY_SESSION_LOCK_END, v).apply()

    // ---- 运行时状态 ----

    var totalWrongCount: Int
        get() = sp.getInt(KEY_TOTAL_WRONG, 0)
        set(v) = sp.edit().putInt(KEY_TOTAL_WRONG, v).apply()

    var roundCount: Int
        get() = sp.getInt(KEY_ROUND_COUNT, 0)
        set(v) = sp.edit().putInt(KEY_ROUND_COUNT, v).apply()

    var nextQuizTimestamp: Long
        get() = sp.getLong(KEY_NEXT_QUIZ, System.currentTimeMillis() + 20 * 60 * 1000L)
        set(v) = sp.edit().putLong(KEY_NEXT_QUIZ, v).apply()

    var isLocked: Boolean
        get() = sp.getBoolean(KEY_LOCKED, false)
        set(v) = sp.edit().putBoolean(KEY_LOCKED, v).apply()

    var lockEndTimestamp: Long
        get() = sp.getLong(KEY_LOCK_END, 0L)
        set(v) = sp.edit().putLong(KEY_LOCK_END, v).apply()

    // ---- 自定义题库 ----

    /** 手机上传的自定义题库 JSON，为 null 时使用 assets 内置题库 */
    var userQuestionsJson: String?
        get() = sp.getString(KEY_USER_Q_JSON, null)
        set(v) = sp.edit().putString(KEY_USER_Q_JSON, v).apply()

    /** 自定义题库版本号，每上传一次 +1；0 表示使用默认题库 */
    var questionsVersion: Int
        get() = sp.getInt(KEY_Q_VERSION, 0)
        set(v) = sp.edit().putInt(KEY_Q_VERSION, v).apply()

    /** 是否启用自定义题库 */
    var isCustomQuestions: Boolean
        get() = sp.getBoolean(KEY_IS_CUSTOM_Q, false)
        set(v) = sp.edit().putBoolean(KEY_IS_CUSTOM_Q, v).apply()

    // ---- 错题 ----

    fun recordWrongQuestion(q: Question) {
        val list = loadWrongList().toMutableList()
        val idx = list.indexOfFirst { it.question == q.q }
        if (idx >= 0) {
            list[idx] = list[idx].copy(wrong_count = list[idx].wrong_count + 1)
        } else {
            list.add(
                WrongRecord(
                    question = q.q,
                    options = q.options,
                    ans = q.ans,
                    exp = q.exp,
                    subject = q.subject,
                    grade = q.grade,
                    wrong_count = 1
                )
            )
        }
        // 按错误次数降序
        list.sortByDescending { it.wrong_count }
        saveWrongList(list)
    }

    fun loadWrongList(): List<WrongRecord> {
        val raw = sp.getString(KEY_WRONG_LIST, "[]") ?: "[]"
        return try {
            Json.decodeFromString(ListSerializer(WrongRecord.serializer()), raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveWrongList(list: List<WrongRecord>) {
        val raw = Json.encodeToString(ListSerializer(WrongRecord.serializer()), list)
        sp.edit().putString(KEY_WRONG_LIST, raw).apply()
    }

    companion object {
        private const val PREFS_NAME = "tv_guardian_prefs"
        private const val KEY_INTERVAL = "interval_minutes"
        private const val KEY_Q_PER_ROUND = "questions_per_round"
        private const val KEY_MAX_WRONG = "max_wrong_before_lock"
        private const val KEY_LOCK_MIN = "lock_minutes"
        private const val KEY_HF_WEIGHT = "high_freq_weight"
        private const val KEY_SUBJECTS = "subjects"
        private const val KEY_GRADES = "grades"
        private const val KEY_TOTAL_WRONG = "total_wrong"
        private const val KEY_ROUND_COUNT = "round_count"
        private const val KEY_NEXT_QUIZ = "next_quiz_ts"
        private const val KEY_LOCKED = "is_locked"
        private const val KEY_LOCK_END = "lock_end_ts"
        private const val KEY_WRONG_LIST = "wrong_list_json"
        private const val KEY_USER_Q_JSON = "user_questions_json"
        private const val KEY_Q_VERSION = "questions_version"
        private const val KEY_IS_CUSTOM_Q = "is_custom_questions"
        private const val KEY_SESSION_LIMIT = "session_limit_minutes"
        private const val KEY_SESSION_REST = "session_rest_minutes"
        private const val KEY_SESSION_START = "session_start_ts"
        private const val KEY_SESSION_LOCK_END = "session_lock_end_ts"
    }
}

@kotlinx.serialization.Serializable
data class WrongRecord(
    val question: String,
    val options: List<String>,
    val ans: Int,
    val exp: String = "",
    val subject: String = "",
    val grade: String = "",
    val wrong_count: Int = 0
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
}
