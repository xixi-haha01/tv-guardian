package com.tvguardian

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tvguardian.data.PrefsManager
import com.tvguardian.data.QuestionBankLoader
import com.tvguardian.databinding.ActivityMainBinding
import com.tvguardian.remote.IpUtils
import com.tvguardian.remote.RemoteControlServer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), RemoteControlServer.ActivityLauncher {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PrefsManager
    private lateinit var server: RemoteControlServer
    private val handler = Handler(Looper.getMainLooper())

    private val tickRunnable = object : Runnable {
        override fun run() {
            refreshUI()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PrefsManager(this)

        // 启动远程控制 HTTP Server
        server = RemoteControlServer(this, prefs, this)
        server.startSafely()

        // 检查锁定状态
        checkLockState()

        // 检查是否到弹题时间
        checkAndTriggerQuiz()

        // 立即加载题库验证
        try {
            QuestionBankLoader.load(this)
        } catch (e: Exception) {
            Toast.makeText(this, "题库加载失败: ${e.message}", Toast.LENGTH_LONG).show()
        }

        // 按钮事件
        binding.btnStartQuiz.setOnClickListener { startQuiz() }
        binding.btnWrongList.setOnClickListener { showWrongList() }

        // 定时刷新
        handler.post(tickRunnable)

        // 下次弹题时间初始化
        if (prefs.nextQuizTimestamp == 0L) {
            scheduleNextQuiz()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(tickRunnable)
        // 确保 server 在前台时恢复
        if (!server.isRunning()) server.startSafely()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tickRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        server.stopSafely()
    }

    // ==================== 刷新 UI ====================

    private fun refreshUI() {
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.CHINA)

        // 下次弹题
        val nextTs = prefs.nextQuizTimestamp
        if (nextTs > 0) {
            val diff = nextTs - System.currentTimeMillis()
            if (diff > 0) {
                val min = diff / 60000
                val sec = (diff % 60000) / 1000
                binding.tvNextQuiz.text = "⏰ 下次弹题：${min}分${sec}秒后  ($${fmt.format(Date(nextTs))})"
            } else {
                binding.tvNextQuiz.text = "⏰ 即将弹题..."
            }
        }

        binding.tvRound.text = "📋 已完成轮次：${prefs.roundCount}"
        binding.tvWrong.text = "❌ 累计错误：${prefs.totalWrongCount} / ${prefs.maxWrongBeforeLock}"

        // 手机控制地址 + 题库版本
        val ip = IpUtils.getLocalIpAddress(this)
        if (ip != null && server.isRunning()) {
            val versionInfo = try {
                val stats = QuestionBankLoader.getStats(this)
                val verStr = if (prefs.isCustomQuestions) "自定义 v${prefs.questionsVersion}" else "内置 v0"
                val s = stats.second
                "📚 题库: $verStr | 共${stats.first}题 | 数${s["math"]} 语${s["chinese"]} 英${s["english"]}"
            } catch (_: Exception) {
                "📚 题库加载中..."
            }
            binding.tvHint.text = "📱 手机控制: http://$ip:${RemoteControlServer.PORT}/\n$versionInfo"
            binding.tvHint.setTextColor(android.graphics.Color.parseColor("#FFFFD54F"))
        } else if (!server.isRunning()) {
            binding.tvHint.text = "⚠️ 远程服务未启动"
            binding.tvHint.setTextColor(android.graphics.Color.parseColor("#FFEF5350"))
        } else {
            binding.tvHint.text = "📡 正在获取局域网IP...（手机连接同一WiFi）"
            binding.tvHint.setTextColor(android.graphics.Color.parseColor("#FF9FA8DA"))
        }
    }

    // ==================== 状态检查 ====================

    private fun checkLockState() {
        if (prefs.isLocked) {
            val end = prefs.lockEndTimestamp
            if (System.currentTimeMillis() < end) {
                startActivity(Intent(this, LockActivity::class.java))
                finish()
            } else {
                prefs.isLocked = false
                prefs.totalWrongCount = 0
                scheduleNextQuiz()
            }
        }
    }

    private fun checkAndTriggerQuiz() {
        if (prefs.isLocked) return
        val now = System.currentTimeMillis()
        if (prefs.nextQuizTimestamp in 1 until now) {
            startQuiz()
        }
    }

    private fun scheduleNextQuiz() {
        val intervalMs = prefs.intervalMinutes * 60 * 1000L
        prefs.nextQuizTimestamp = System.currentTimeMillis() + intervalMs
    }

    // ==================== 答题 & 锁定 ====================

    fun startQuiz() {
        if (prefs.isLocked) {
            startActivity(Intent(this, LockActivity::class.java))
            finish()
            return
        }
        startActivity(Intent(this, QuizActivity::class.java))
    }

    fun showWrongList() {
        val wrongList = prefs.loadWrongList()
        if (wrongList.isEmpty()) {
            Toast.makeText(this, "暂时没有错题记录", Toast.LENGTH_SHORT).show()
            return
        }
        val top20 = wrongList.take(20)
        val sb = StringBuilder()
        top20.forEachIndexed { i, wq ->
            sb.append("#${i + 1} [${wq.subjectName}${wq.gradeName}] 错${wq.wrong_count}次\n")
            sb.append("  Q: ${wq.question}\n")
            val labels = listOf("A", "B", "C", "D")
            sb.append("  ✓ ${labels[wq.ans]}. ${wq.options[wq.ans]}\n\n")
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("📖 高频错题本")
            .setMessage(sb.toString())
            .setPositiveButton("关闭", null)
            .show()
    }

    // ==================== RemoteControlServer.ActivityLauncher ====================

    override fun launchQuiz() {
        runOnUiThread {
            try {
                startQuiz()
                Toast.makeText(this, "📱 手机触发了答题", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
        }
    }

    override fun launchLock() {
        runOnUiThread {
            try {
                val intent = Intent(this, LockActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                Toast.makeText(this, "📱 手机触发了锁定", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
        }
    }

    override fun refreshMainUI() {
        runOnUiThread { refreshUI() }
    }

    companion object {
        const val RC_QUIZ = 100
    }
}
