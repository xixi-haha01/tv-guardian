package com.tvguardian

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tvguardian.data.PrefsManager
import com.tvguardian.databinding.ActivityLockBinding

/**
 * 全屏锁定界面 —— 倒计时期间无法退出
 */
class LockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLockBinding
    private lateinit var prefs: PrefsManager
    private val handler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            updateCountdown()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PrefsManager(this)
        updateCountdown()
        handler.post(tickRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(tickRunnable)
    }

    /** 拦截所有返回键和 HOME */
    override fun onBackPressed() {}

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_APP_SWITCH -> true  // 拦截
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun updateCountdown() {
        val end = prefs.lockEndTimestamp
        val now = System.currentTimeMillis()
        val remain = end - now

        if (remain <= 0 || !prefs.isLocked) {
            // 解锁
            prefs.isLocked = false
            prefs.totalWrongCount = 0
            // 重新排下次弹题
            prefs.nextQuizTimestamp = System.currentTimeMillis() +
                    prefs.intervalMinutes * 60 * 1000L

            handler.removeCallbacks(tickRunnable)
            Toast.makeText(this, "休息结束，继续加油！", Toast.LENGTH_LONG).show()
            // 回到主界面
            val intent = android.content.Intent(this, MainActivity::class.java)
            intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
            return
        }

        val total = prefs.lockMinutes * 60 * 1000L
        val progress = ((total - remain) * 100 / total).toInt()

        val minutes = remain / 60000
        val seconds = (remain % 60000) / 1000

        binding.tvCountdown.text = "%02d:%02d".format(minutes, seconds)
        binding.pbLock.progress = progress
    }
}
