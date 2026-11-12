package com.tvguardian.data

import android.content.Context
import android.util.Log
import com.tvguardian.model.Question
import com.tvguardian.model.QuestionBank as Bank
import kotlinx.serialization.json.Json
import kotlin.random.Random

/**
 * 题库加载与智能选题（含高频错题加权）
 * 优先使用手机上传的自定义题库（PrefsManager.userQuestionsJson），
 * 否则回退到 assets/questions.json
 */
object QuestionBankLoader {

    private const val TAG = "QuestionBankLoader"
    private const val PREFS_KEY = "last_custom_json_hash"  // 非必要，留作扩展

    private val json = Json { ignoreUnknownKeys = true }

    // 题库缓存：key 是题库版本标识，value 是解析好的 Bank
    private val cache = mutableMapOf<String, Bank>()

    /**
     * 加载题库：优先自定义，回退 assets
     */
    fun load(context: Context): Bank {
        val prefs = PrefsManager(context)

        // 自定义题库
        if (prefs.isCustomQuestions) {
            val customJson = prefs.userQuestionsJson
            if (customJson != null) {
                // 缓存 key 用版本号 + 长度，避免每次都解析
                val cacheKey = "custom_${prefs.questionsVersion}_${customJson.length}"
                cache[cacheKey]?.let { return it }
                return try {
                    val bank = json.decodeFromString(Bank.serializer(), customJson)
                    cache[cacheKey] = bank
                    bank
                } catch (e: Exception) {
                    Log.e(TAG, "自定义题库解析失败，回退默认", e)
                    loadFromAssets(context)
                }
            }
        }

        return loadFromAssets(context)
    }

    private fun loadFromAssets(context: Context): Bank {
        val cacheKey = "assets_default"
        cache[cacheKey]?.let { return it }
        val raw = context.assets.open("questions.json").bufferedReader().use { it.readText() }
        val bank = json.decodeFromString(Bank.serializer(), raw)
        cache[cacheKey] = bank
        return bank
    }

    /** 自定义题库更新后调用，清空所有缓存 */
    fun invalidateCache() {
        cache.clear()
    }

    /**
     * 校验传入的题库 JSON 是否合法
     * 返回 (是否合法, 错误信息)
     */
    fun validate(context: Context, jsonStr: String): Pair<Boolean, String> {
        if (jsonStr.isBlank()) return false to "题库 JSON 不能为空"

        return try {
            val bank = json.decodeFromString(Bank.serializer(), jsonStr)

            // 检查结构：至少一个科目有题目
            val allQ = listOfNotNull(
                bank.math.values.flatten(),
                bank.chinese.values.flatten(),
                bank.english.values.flatten()
            )
            if (allQ.isEmpty()) return false to "题库至少需要一个科目有题目"

            // 检查每道题的基本结构
            for ((idx, q) in allQ.withIndex()) {
                if (q.q.trim().length < 2) {
                    return false to "第 $idx 题题干过短"
                }
                if (q.options.size != 4) {
                    return false to "第 $idx 题选项数量必须是 4 个（当前 ${q.options.size}）"
                }
                if (q.ans !in 0..3) {
                    return false to "第 $idx 题正确答案索引必须是 0-3（当前 ${q.ans}）"
                }
                if (q.options.any { it.isBlank() }) {
                    return false to "第 $idx 题存在空选项"
                }
            }

            true to "校验通过（共 ${allQ.size} 道题）"
        } catch (e: Exception) {
            false to "JSON 格式错误：${e.message}"
        }
    }

    /**
     * 获取当前生效题库的统计数据
     * 返回 Triple(总题数, 每科题数Map, 自定义版本号)
     */
    fun getStats(context: Context): Triple<Int, Map<String, Int>, Int> {
        val b = load(context)
        val perSubject = mapOf(
            "math" to b.math.values.sumOf { it.size },
            "chinese" to b.chinese.values.sumOf { it.size },
            "english" to b.english.values.sumOf { it.size }
        )
        val total = perSubject.values.sum()
        val version = PrefsManager(context).questionsVersion
        return Triple(total, perSubject, version)
    }

    /** 获取过滤后的所有题目 */
    fun getAll(
        context: Context,
        subjects: Set<String>,
        grades: Set<String>
    ): List<Question> {
        val b = load(context)
        val result = mutableListOf<Question>()
        mapOf(
            "math" to b.math,
            "chinese" to b.chinese,
            "english" to b.english
        ).forEach { (subj, gradeMap) ->
            if (subj in subjects) {
                gradeMap.forEach { (g, list) ->
                    if (g in grades) {
                        list.forEach { q ->
                            result.add(q.copy(subject = subj, grade = g))
                        }
                    }
                }
            }
        }
        return result
    }

    /**
     * 智能选题：高频错题加权
     */
    fun selectQuestions(
        context: Context,
        count: Int,
        subjects: Set<String>,
        grades: Set<String>,
        highFreqWeight: Int
    ): List<Question> {
        val bankQs = getAll(context, subjects, grades)
        if (bankQs.isEmpty()) return emptyList()

        val prefs = PrefsManager(context)
        val wrongList = prefs.loadWrongList()

        // 构建加权池
        val weightedPool = mutableListOf<Question>()

        wrongList.forEach { wq ->
            if (wq.subject in subjects && wq.grade in grades) {
                val q = Question(
                    q = wq.question,
                    options = wq.options,
                    ans = wq.ans,
                    exp = wq.exp,
                    subject = wq.subject,
                    grade = wq.grade,
                    from_wrong = true
                )
                val weight = maxOf(highFreqWeight, wq.wrong_count)
                repeat(weight) { weightedPool.add(q) }
            }
        }

        bankQs.forEach { weightedPool.add(it.copy(from_wrong = false)) }

        if (weightedPool.isEmpty()) {
            return bankQs.shuffled().take(count.coerceAtMost(bankQs.size))
        }

        // 去重抽取
        val selected = mutableListOf<Question>()
        val used = mutableSetOf<String>()
        val maxAttempts = count * 10
        var attempt = 0

        while (selected.size < count && attempt < maxAttempts) {
            val q = weightedPool[Random.nextInt(weightedPool.size)]
            if (q.q !in used) {
                selected.add(q)
                used.add(q.q)
            }
            attempt++
        }

        // 补够
        if (selected.size < count) {
            val remaining = bankQs.filter { it.q !in used }.shuffled()
            selected.addAll(remaining.take(count - selected.size))
        }

        return selected
    }
}
