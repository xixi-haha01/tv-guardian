package com.tvguardian.remote

import android.content.Context
import android.content.Intent
import com.tvguardian.LockActivity
import com.tvguardian.MainActivity
import com.tvguardian.QuizActivity
import com.tvguardian.data.PrefsManager
import com.tvguardian.data.QuestionBankLoader
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.IOException
import java.net.SocketException

/**
 * 内嵌 HTTP Server —— 手机浏览器控制电视守护者
 * 支持：查看状态、远程锁定/解锁、立即触发答题、修改设置、查看错题
 *
 * 手机端访问: http://<电视IP>:<PORT>/control.html
 */
class RemoteControlServer(
    private val context: Context,
    private val prefs: PrefsManager,
    private val activityLauncher: ActivityLauncher
) : NanoHTTPD(PORT) {

    interface ActivityLauncher {
        fun launchQuiz()
        fun launchLock()
        fun refreshMainUI()
    }

    @Volatile
    private var running = false

    fun startSafely() {
        if (running) return
        try {
            // 允许 Android 9+ cleartext
            start(SOCKET_READ_TIMEOUT, false)
            running = true
        } catch (e: IOException) {
            android.util.Log.e(TAG, "无法启动 RemoteControlServer: ${e.message}")
        }
    }

    fun stopSafely() {
        if (running) {
            try {
                stop()
            } catch (_: Exception) {}
            running = false
        }
    }

    fun isRunning() = running

    // ==================== 请求分发 ====================

    override fun serve(session: IHTTPSession): Response {
        return try {
            val uri = session.uri
            val method = session.method

            // CORS（允许手机浏览器跨域）
            session.setRemoteHost(session.remoteIpAddress)

            when {
                uri == "/" || uri == "/control.html" -> serveControlPage()
                uri == "/api/status" && method == Method.GET -> serveStatus()
                uri == "/api/lock" && method == Method.POST -> serveLock(session)
                uri == "/api/unlock" && method == Method.POST -> serveUnlock()
                uri == "/api/quiz" && method == Method.POST -> serveTriggerQuiz()
                uri == "/api/settings" && method == Method.GET -> serveSettings()
                uri == "/api/settings" && method == Method.POST -> serveUpdateSettings(session)
                uri == "/api/wrong" && method == Method.GET -> serveWrongList()
                uri == "/api/reset" && method == Method.POST -> serveResetWrong()
                uri == "/api/questions" && method == Method.GET -> serveQuestionsInfo()
                uri == "/api/questions" && method == Method.POST -> serveUploadQuestions(session)
                uri == "/api/questions/default" && method == Method.POST -> serveResetQuestions()
                else -> jsonResponse(404, JSONObject().put("error", "not found"))
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "serve error", e)
            jsonResponse(500, JSONObject().put("error", e.message ?: "unknown"))
        }
    }

    // ==================== API 实现 ====================

    /** 状态查询 */
    private fun serveStatus(): Response {
        val now = System.currentTimeMillis()
        val nextDiff = (prefs.nextQuizTimestamp - now).coerceAtLeast(0)

        val lockRemain = if (prefs.isLocked) {
            (prefs.lockEndTimestamp - now).coerceAtLeast(0)
        } else 0L

        val status = JSONObject().apply {
            put("locked", prefs.isLocked)
            put("lock_remain_seconds", lockRemain / 1000)
            put("total_wrong", prefs.totalWrongCount)
            put("max_wrong", prefs.maxWrongBeforeLock)
            put("round_count", prefs.roundCount)
            put("next_quiz_seconds", nextDiff / 1000)
            put("interval_minutes", prefs.intervalMinutes)
            put("lock_minutes", prefs.lockMinutes)
        }
        return jsonResponse(200, status)
    }

    /** 远程锁定 */
    private fun serveLock(session: IHTTPSession): Response {
        val params = mutableMapOf<String, String>()
        session.parseBody(params)
        val minutes = try {
            params["minutes"]?.toInt() ?: prefs.lockMinutes
        } catch (_: Exception) {
            prefs.lockMinutes
        }

        prefs.isLocked = true
        prefs.lockEndTimestamp = System.currentTimeMillis() + minutes * 60 * 1000L

        activityLauncher.launchLock()

        val data = JSONObject().apply {
            put("ok", true)
            put("action", "lock")
            put("minutes", minutes)
            put("end_ts", prefs.lockEndTimestamp)
        }
        return jsonResponse(200, data)
    }

    /** 远程解锁 */
    private fun serveUnlock(): Response {
        prefs.isLocked = false
        prefs.totalWrongCount = 0
        // 重新排下次弹题
        prefs.nextQuizTimestamp = System.currentTimeMillis() +
                prefs.intervalMinutes * 60 * 1000L

        activityLauncher.refreshMainUI()

        val data = JSONObject().put("ok", true).put("action", "unlock")
        return jsonResponse(200, data)
    }

    /** 立即触发答题 */
    private fun serveTriggerQuiz(): Response {
        if (prefs.isLocked) {
            val data = JSONObject()
                .put("ok", false)
                .put("reason", "当前处于锁定状态，请先解锁")
            return jsonResponse(409, data)
        }
        activityLauncher.launchQuiz()
        val data = JSONObject().put("ok", true).put("action", "trigger_quiz")
        return jsonResponse(200, data)
    }

    /** 获取设置 */
    private fun serveSettings(): Response {
        val data = JSONObject().apply {
            put("interval_minutes", prefs.intervalMinutes)
            put("questions_per_round", prefs.questionsPerRound)
            put("max_wrong_before_lock", prefs.maxWrongBeforeLock)
            put("lock_minutes", prefs.lockMinutes)
            put("high_freq_weight", prefs.highFreqWeight)
        }
        return jsonResponse(200, data)
    }

    /** 修改设置 */
    private fun serveUpdateSettings(session: IHTTPSession): Response {
        val params = mutableMapOf<String, String>()
        session.parseBody(params)
        val body = params["postData"] ?: ""
        val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }

        if (json.has("interval_minutes")) {
            prefs.intervalMinutes = json.getInt("interval_minutes").coerceIn(1, 240)
        }
        if (json.has("questions_per_round")) {
            prefs.questionsPerRound = json.getInt("questions_per_round").coerceIn(1, 20)
        }
        if (json.has("max_wrong_before_lock")) {
            prefs.maxWrongBeforeLock = json.getInt("max_wrong_before_lock").coerceIn(1, 100)
        }
        if (json.has("lock_minutes")) {
            prefs.lockMinutes = json.getInt("lock_minutes").coerceIn(1, 120)
        }

        activityLauncher.refreshMainUI()
        return jsonResponse(200, JSONObject().put("ok", true))
    }

    /** 错题列表 */
    private fun serveWrongList(): Response {
        val list = prefs.loadWrongList().take(30)
        val arr = org.json.JSONArray()
        list.forEach { wq ->
            arr.put(JSONObject().apply {
                put("question", wq.question)
                put("subject", wq.subjectName)
                put("grade", wq.gradeName)
                put("wrong_count", wq.wrong_count)
                val labels = listOf("A", "B", "C", "D")
                put("answer", labels.getOrElse(wq.ans) { "?" })
                put("options", org.json.JSONArray(wq.options))
            })
        }
        return jsonResponse(200, JSONObject().put("list", arr))
    }

    /** 重置错题 */
    private fun serveResetWrong(): Response {
        prefs.totalWrongCount = 0
        activityLauncher.refreshMainUI()
        return jsonResponse(200, JSONObject().put("ok", true))
    }

    // ============ 自定义题库管理 ============

    /** 查询当前生效题库信息（版本/题数/每科统计） */
    private fun serveQuestionsInfo(): Response {
        val stats = QuestionBankLoader.getStats(context)
        val data = JSONObject().apply {
            put("total", stats.first)
            put("version", stats.third)
            put("custom", prefs.isCustomQuestions)
            put("per_subject", JSONObject().apply {
                put("math", stats.second["math"] ?: 0)
                put("chinese", stats.second["chinese"] ?: 0)
                put("english", stats.second["english"] ?: 0)
            })
        }
        return jsonResponse(200, data)
    }

    /**
     * 手机上传新题库（JSON）
     * Body 格式：POST application/json，内容同 assets/questions.json
     */
    private fun serveUploadQuestions(session: IHTTPSession): Response {
        val params = mutableMapOf<String, String>()
        session.parseBody(params)
        val body = params["postData"] ?: params["body"] ?: ""

        if (body.isBlank()) {
            return jsonResponse(400, JSONObject().put("error", "请求体不能为空"))
        }

        // 校验结构
        val (valid, msg) = QuestionBankLoader.validate(context, body)
        if (!valid) {
            return jsonResponse(400, JSONObject().put("error", msg))
        }

        // 保存
        prefs.userQuestionsJson = body
        prefs.isCustomQuestions = true
        prefs.questionsVersion += 1

        // 清缓存，立即生效
        QuestionBankLoader.invalidateCache()

        val data = JSONObject().apply {
            put("ok", true)
            put("version", prefs.questionsVersion)
            put("custom", true)
            put("message", "题库更新成功！${msg}")
        }
        activityLauncher.refreshMainUI()
        return jsonResponse(200, data)
    }

    /** 恢复默认题库（清除手机上传的自定义题库） */
    private fun serveResetQuestions(): Response {
        prefs.userQuestionsJson = null
        prefs.isCustomQuestions = false
        prefs.questionsVersion = 0

        QuestionBankLoader.invalidateCache()

        val data = JSONObject().apply {
            put("ok", true)
            put("version", 0)
            put("custom", false)
            put("message", "已恢复默认题库（内置 v0）")
        }
        activityLauncher.refreshMainUI()
        return jsonResponse(200, data)
    }

    // ==================== 手机端控制面板 HTML ====================

    private fun serveControlPage(): Response {
        val html = context.assets.open("control.html").bufferedReader().use { it.readText() }
        val response = NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            "text/html; charset=utf-8",
            html
        )
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Cache-Control", "no-cache")
        return response
    }

    // ==================== 工具 ====================

    private fun jsonResponse(status: Int, json: JSONObject): Response {
        val response = NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.valueOf(status.toString()),
            "application/json; charset=utf-8",
            json.toString()
        )
        response.addHeader("Access-Control-Allow-Origin", "*")
        return response
    }

    companion object {
        private const val TAG = "TVGuardianServer"
        const val PORT = 8080
    }
}
