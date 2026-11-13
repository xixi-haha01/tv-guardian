package com.tvguardian

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.tvguardian.data.PrefsManager
import com.tvguardian.data.QuestionBankLoader
import com.tvguardian.databinding.ActivityQuizBinding
import com.tvguardian.model.Question

/**
 * 全屏答题界面 —— 不可退出（拦截返回键）
 */
class QuizActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQuizBinding
    private lateinit var prefs: PrefsManager

    private var questions: List<Question> = emptyList()
    private var currentIndex = 0
    private val userAnswers = mutableListOf<Int>()
    private var selectedOption = -1
    private val optionLabels = listOf("A", "B", "C", "D")
    private val optionColors = intArrayOf(
        Color.parseColor("#FF42A5F5"),  // blue
        Color.parseColor("#FF66BB6A"),  // green
        Color.parseColor("#FFFFA726"),  // orange
        Color.parseColor("#FFAB47BC")   // purple
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQuizBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PrefsManager(this)

        // 生成题目
        questions = QuestionBankLoader.selectQuestions(
            context = this,
            count = prefs.questionsPerRound,
            subjects = prefs.selectedSubjects,
            grades = prefs.selectedGrades,
            highFreqWeight = prefs.highFreqWeight
        )

        if (questions.isEmpty()) {
            finishAndBack()
            return
        }

        showCurrent()
    }

    /** 拦截所有返回键，不允许退出 */
    override fun onBackPressed() {
        // 吞掉
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return true
        return super.onKeyDown(keyCode, event)
    }

    private fun showCurrent() {
        val q = questions[currentIndex]
        selectedOption = -1
        binding.btnSubmit.isEnabled = false

        // 进度
        binding.tvProgress.text = "进度：${currentIndex + 1} / ${questions.size}"

        // 标题
        val sourceTag = if (q.from_wrong) " [错题重练]" else ""
        binding.tvHeader.text =
            "第 ${currentIndex + 1} 题  ${q.subjectName} ${q.gradeName}${sourceTag}"

        binding.tvQuestion.text = q.q

        // 渲染选项
        renderOptions(q)
    }

    private fun renderOptions(q: Question) {
        binding.optionsContainer.removeAllViews()
        q.options.forEachIndexed { i, optText ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 20
                }
                setPadding(40, 28, 40, 28)
                setBackgroundColor(Color.parseColor("#FF37474F"))
            }

            // 选中标圈
            val circle = TextView(this).apply {
                text = optionLabels[i]
                textSize = 28f
                setTextColor(Color.WHITE)
                setBackgroundColor(optionColors[i])
                gravity = Gravity.CENTER
                width = 60
                height = 60
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    rightMargin = 32
                }
            }

            val label = TextView(this).apply {
                text = optText
                textSize = 28f
                setTextColor(Color.parseColor("#FFECEFF1"))
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }

            row.addView(circle)
            row.addView(label)

            // D-pad 焦点
            row.isFocusable = true
            row.isFocusableInTouchMode = true
            row.setOnClickListener {
                selectOption(i)
            }
            row.setOnFocusChangeListener { v, hasFocus ->
                row.setBackgroundColor(
                    if (hasFocus || selectedOption == i) Color.parseColor("#FF1565C0")
                    else Color.parseColor("#FF37474F")
                )
            }
            row.setOnKeyListener { v, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode in listOf(
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER
                    )) {
                    selectOption(i)
                    true
                } else false
            }

            binding.optionsContainer.addView(row)
        }
    }

    private fun selectOption(index: Int) {
        selectedOption = index
        binding.btnSubmit.isEnabled = true
        // 高亮选中行
        for (i in 0 until binding.optionsContainer.childCount) {
            val row = binding.optionsContainer.getChildAt(i) as LinearLayout
            row.setBackgroundColor(
                if (i == index) Color.parseColor("#FF1976D2")
                else Color.parseColor("#FF37474F")
            )
        }
    }

    private fun finishAndBack() {
        // 答题完成：统计、记录错题、检查锁定
        var wrongCount = 0
        questions.forEachIndexed { i, q ->
            if (userAnswers[i] != q.ans) {
                wrongCount++
                prefs.recordWrongQuestion(q)
            }
        }

        prefs.totalWrongCount += wrongCount
        prefs.roundCount += 1

        // 重置下次弹题时间
        prefs.nextQuizTimestamp = System.currentTimeMillis() +
                prefs.intervalMinutes * 60 * 1000L

        // 检查锁定
        if (prefs.totalWrongCount >= prefs.maxWrongBeforeLock) {
            prefs.isLocked = true
            prefs.lockEndTimestamp = System.currentTimeMillis() +
                    prefs.lockMinutes * 60 * 1000L
            val intent = Intent(this, LockActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            return
        }

        // 显示结果 dialog 再返回
        showResultDialog(wrongCount)
    }

    private fun showResultDialog(wrongCount: Int) {
        val correctCount = questions.size - wrongCount
        val pct = if (questions.isNotEmpty()) correctCount * 100 / questions.size else 0

        val sb = StringBuilder()
        sb.append("答题完成！\n\n")
        sb.append("正确 $correctCount/${questions.size}（$pct%）\n\n")

        questions.forEachIndexed { i, q ->
            val userAns = userAnswers[i]
            val isRight = userAns == q.ans
            val mark = if (isRight) "✓" else "✗"
            sb.append("$mark 第${i + 1}题 ${q.subjectName}${q.gradeName}\n")
            if (!isRight) {
                sb.append("   你选: ${optionLabels[userAns]}. ${q.options[userAns]}\n")
                sb.append("   正确: ${optionLabels[q.ans]}. ${q.options[q.ans]}\n")
            }
            if (q.exp.isNotEmpty()) {
                sb.append("   💡 ${q.exp}\n")
            }
            sb.append("\n")
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("📊 本次答题结果")
            .setMessage(sb.toString())
            .setPositiveButton("继续看电视") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    // ---- 按钮 ----
    fun onSubmitClick(view: View) {
        if (selectedOption < 0) return
        userAnswers.add(selectedOption)
        if (currentIndex < questions.size - 1) {
            currentIndex++
            showCurrent()
        } else {
            finishAndBack()
        }
    }
}
