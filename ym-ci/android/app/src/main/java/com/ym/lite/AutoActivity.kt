package com.ym.lite

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.ym.lite.background.YmPlanWorker
import com.ym.lite.ui.YmNav

class AutoActivity : AppCompatActivity() {
    private val state by lazy { getSharedPreferences("ym_background", MODE_PRIVATE) }
    private val runs by lazy { getSharedPreferences("ym_runs", MODE_PRIVATE) }
    private lateinit var status: TextView
    private lateinit var summary: TextView
    private lateinit var progress: ProgressBar
    private lateinit var autoOrb: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildScreen())
        observeWork()
        render()
    }

    private fun buildScreen(): FrameLayout {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(34), dp(18), dp(110))
        }
        scroll.addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        body.addView(TextView(this).apply {
            text = "YM AUTO"
            setTextColor(Color.WHITE)
            textSize = 28f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        body.addView(TextView(this).apply {
            text = "اضبط الخطة مرة واحدة، واترك YM يكملها في الخلفية."
            setTextColor(Color.LTGRAY)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(24))
        })

        autoOrb = TextView(this).apply {
            text = "AUTO"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 30f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(24, 24, 24))
                setStroke(dp(4), Color.rgb(37, 244, 238))
            }
        }
        body.addView(autoOrb, LinearLayout.LayoutParams(dp(180), dp(180)).apply { setMargins(0, 0, 0, dp(18)) })

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        body.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(18)).apply { setMargins(0, 0, 0, dp(14)) })

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        body.addView(status, matchWrap())

        summary = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 15f
            setPadding(dp(12), dp(14), dp(12), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.rgb(18, 18, 18))
                setStroke(dp(1), Color.rgb(55, 55, 55))
            }
        }
        body.addView(summary, matchWrap().apply { setMargins(0, dp(8), 0, dp(14)) })

        body.addView(Button(this).apply {
            text = "إعداد دولاب النشر"
            isAllCaps = false
            setOnClickListener { startActivity(Intent(this@AutoActivity, MainActivity::class.java)) }
        }, matchWrap())

        body.addView(Button(this).apply {
            text = "إيقاف AUTO"
            isAllCaps = false
            setOnClickListener {
                WorkManager.getInstance(this@AutoActivity).cancelUniqueWork(YmPlanWorker.UNIQUE_WORK)
                val runId = state.getString("active_run_id", "").orEmpty()
                if (runId.isNotBlank()) runs.edit().putString("$runId.status", "cancelled").apply()
                state.edit().putBoolean("running", false).putString("last_status", "تم إيقاف AUTO يدويًا").apply()
                render()
            }
        }, matchWrap())

        val nav = YmNav.bottomBar(this, YmNav.Tab.AUTO) {
            startActivity(Intent(this, LibraryActivity::class.java))
        }
        root.addView(nav, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72), Gravity.BOTTOM))
        return root
    }

    private fun observeWork() {
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(YmPlanWorker.UNIQUE_WORK)
            .observe(this) { infos ->
                val info = infos.lastOrNull() ?: return@observe
                val current = info.progress.getInt(YmPlanWorker.KEY_CURRENT, 0)
                val total = info.progress.getInt(YmPlanWorker.KEY_TOTAL, 0)
                if (total > 0) progress.progress = ((current * 100) / total).coerceIn(0, 100)
                when (info.state) {
                    WorkInfo.State.RUNNING -> autoOrb.text = "$current/$total"
                    WorkInfo.State.SUCCEEDED -> autoOrb.text = "✓"
                    WorkInfo.State.FAILED -> autoOrb.text = "!"
                    WorkInfo.State.CANCELLED -> autoOrb.text = "AUTO"
                    else -> Unit
                }
                render()
            }
    }

    private fun render() {
        val running = state.getBoolean("running", false)
        val last = state.getString("last_status", "لا توجد خطة نشطة").orEmpty()
        status.text = if (running) "● AUTO يعمل في الخلفية\n$last" else "○ AUTO متوقف\n$last"
        status.setTextColor(if (running) Color.rgb(37, 244, 238) else Color.WHITE)

        val runId = state.getString("active_run_id", "").orEmpty()
        if (runId.isBlank()) {
            summary.text = "لا توجد خطة بعد. افتح إعداد دولاب النشر وحدد الحساب والمكتبة والأوقات."
            return
        }
        val account = runs.getString("$runId.account_label", "").orEmpty().ifBlank { "حساب غير مسمى" }
        val days = runs.getString("$runId.horizon_days", "?").orEmpty()
        val perDay = runs.getString("$runId.daily_count", "?").orEmpty()
        val runState = runs.getString("$runId.status", "created").orEmpty()
        val scheduled = runs.getInt("$runId.scheduled_count", 0)
        val total = runs.getInt("$runId.plan_size", 0)
        summary.text = "الحساب: $account\nالدولاب: $perDay منشور/يوم × $days يوم\nالحالة: $runState\nالمجدول: $scheduled/$total\nالخطة: ${runId.take(8)}"
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) render()
    }
}
