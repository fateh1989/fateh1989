package com.ym.lite

import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ym.lite.automation.TikTokScope
import com.ym.lite.automation.YmTikTokAccessibilityService

class TikTokAutoActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("ym_tiktok_auto", MODE_PRIVATE) }
    private val localPrefs by lazy { getSharedPreferences("ym_local", MODE_PRIVATE) }

    private lateinit var status: TextView
    private lateinit var sessionDuration: EditText
    private lateinit var interval: EditText
    private lateinit var autoComment: Switch
    private lateinit var commentEvery: EditText
    private lateinit var maxCommentsSession: EditText
    private lateinit var commentGapSeconds: EditText
    private lateinit var commentPool: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildScreen())
        loadSettings()
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
        if (prefs.getBoolean("pending_launch", false) && isAccessibilityEnabled()) {
            prefs.edit().putBoolean("pending_launch", false).putBoolean("enabled", true).apply()
            YmTikTokAccessibilityService.notifyConfigChanged()
            launchTikTok()
        }
    }

    private fun buildScreen(): ScrollView {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.BLACK) }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = LinearLayout.LAYOUT_DIRECTION_RTL
            setPadding(dp(18), dp(24), dp(18), dp(32))
        }
        scroll.addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        body.addView(TextView(this).apply {
            text = "YM AUTOPILOT"
            setTextColor(Color.WHITE)
            textSize = 28f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
        })

        body.addView(TextView(this).apply {
            text = "حدد مدة الجلسة، ثم اترك YM يشاهد ويقلب ويستخدم دولاب الدعم داخل TikTok فقط."
            setTextColor(Color.LTGRAY)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(18))
        })

        status = cardText()
        body.addView(status, matchWrap().apply { bottomMargin = dp(14) })

        body.addView(label("مدة جلسة AUTOPILOT بالدقائق — تتوقف الجلسة تلقائيًا عند انتهائها"))
        sessionDuration = numberField("60")
        body.addView(sessionDuration, matchWrap())
        body.addView(durationRow(15, 30), matchWrap())
        body.addView(durationRow(60, 120), matchWrap())

        body.addView(label("زمن الانتقال بين الفيديوهات — من 3 إلى 120 ثانية"))
        interval = numberField("8")
        body.addView(interval, matchWrap())

        autoComment = Switch(this).apply {
            text = "تعليقات دعم تلقائية"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(0, dp(14), 0, dp(8))
        }
        body.addView(autoComment, matchWrap())

        body.addView(label("ضع تعليقًا كل كم فيديو؟"))
        commentEvery = numberField("3")
        body.addView(commentEvery, matchWrap())

        body.addView(label("أقصى عدد تعليقات في جلسة التشغيل — من 1 إلى 50"))
        maxCommentsSession = numberField("5")
        body.addView(maxCommentsSession, matchWrap())

        body.addView(label("أقل وقت بين تعليق وآخر — من 30 إلى 3600 ثانية"))
        commentGapSeconds = numberField("60")
        body.addView(commentGapSeconds, matchWrap())

        body.addView(label("دولاب الدعم — 100 تعليق تشجيعي: 70 إنكليزي + 30 عربي"))
        commentPool = EditText(this).apply {
            hint = "اختر دولاب الدعم أو عدّل التعليقات يدويًا"
            setHintTextColor(Color.DKGRAY)
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.TOP or Gravity.START
            minLines = 8
            background = roundedBox()
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        body.addView(commentPool, matchWrap().apply { bottomMargin = dp(10) })

        body.addView(templateRow("English 70", "عربي 30"), matchWrap())
        body.addView(actionButton("دولاب الدعم 100 👏🙌🌟") {
            loadCommentPack(SupportCommentWheel.all, "دولاب الدعم 100")
        }, matchWrap())
        body.addView(actionButton("مسح التعليقات") {
            commentPool.setText("")
        }, matchWrap())

        body.addView(actionButton("حفظ الإعدادات") {
            saveSettings(prefs.getBoolean("enabled", false))
            Toast.makeText(this, "تم حفظ إعدادات AUTOPILOT", Toast.LENGTH_SHORT).show()
        }, matchWrap())

        body.addView(actionButton("▶ تشغيل AUTOPILOT") {
            saveSettings(true)
            if (!isAccessibilityEnabled()) {
                prefs.edit().putBoolean("pending_launch", true).apply()
                Toast.makeText(this, "فعّل خدمة YM Automation ثم ارجع؛ سيبدأ AUTOPILOT ويفتح TikTok", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } else {
                launchTikTok()
            }
            render()
        }, matchWrap())

        body.addView(actionButton("■ إيقاف AUTOPILOT") {
            prefs.edit()
                .putBoolean("enabled", false)
                .putBoolean("pending_launch", false)
                .putLong("session_end_at", 0L)
                .apply()
            YmTikTokAccessibilityService.notifyConfigChanged()
            render()
            Toast.makeText(this, "تم إيقاف AUTOPILOT", Toast.LENGTH_SHORT).show()
        }, matchWrap())

        body.addView(actionButton("فتح إعدادات إمكانية الوصول") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, matchWrap())

        body.addView(actionButton("فتح TikTok الحقيقي") { launchTikTok() }, matchWrap())
        body.addView(actionButton("AUTO النشر السحابي") {
            startActivity(Intent(this, AutoActivity::class.java))
        }, matchWrap())

        return scroll
    }

    private fun durationRow(first: Int, second: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
            addView(durationButton(first), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(4)
            })
            addView(durationButton(second), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(4)
            })
        }
    }

    private fun durationButton(minutes: Int) = Button(this).apply {
        text = if (minutes >= 60) "${minutes / 60} ساعة" else "$minutes دقيقة"
        isAllCaps = false
        setOnClickListener { sessionDuration.setText(minutes.toString()) }
    }

    private fun templateRow(first: String, second: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
            addView(templateButton(first), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(4)
            })
            addView(templateButton(second), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(4)
            })
        }
    }

    private fun templateButton(name: String) = Button(this).apply {
        text = name
        isAllCaps = false
        setOnClickListener {
            when (name) {
                "English 70" -> loadCommentPack(SupportCommentWheel.english, name)
                "عربي 30" -> loadCommentPack(SupportCommentWheel.arabic, name)
            }
        }
    }

    private fun loadCommentPack(pack: List<String>, label: String) {
        commentPool.setText(pack.joinToString("\n"))
        Toast.makeText(this, "تم تحميل $label (${pack.size})", Toast.LENGTH_SHORT).show()
    }

    private fun loadSettings() {
        sessionDuration.setText(prefs.getInt("session_duration_min", 60).toString())
        interval.setText(prefs.getInt("interval_sec", 8).toString())
        autoComment.isChecked = prefs.getBoolean("auto_comment", false)
        commentEvery.setText(prefs.getInt("comment_every", 3).toString())
        maxCommentsSession.setText(prefs.getInt("max_comments_session", 5).toString())
        commentGapSeconds.setText(prefs.getInt("comment_gap_sec", 60).toString())
        commentPool.setText(localPrefs.getString("comment_pool", "").orEmpty())
    }

    private fun saveSettings(masterEnabled: Boolean) {
        val durationMinutes = sessionDuration.text.toString().toIntOrNull()?.coerceIn(1, 1440) ?: 60
        val seconds = interval.text.toString().toIntOrNull()?.coerceIn(3, 120) ?: 8
        val every = commentEvery.text.toString().toIntOrNull()?.coerceIn(1, 100) ?: 3
        val maxComments = maxCommentsSession.text.toString().toIntOrNull()?.coerceIn(1, 50) ?: 5
        val gapSeconds = commentGapSeconds.text.toString().toIntOrNull()?.coerceIn(30, 3600) ?: 60

        sessionDuration.setText(durationMinutes.toString())
        interval.setText(seconds.toString())
        commentEvery.setText(every.toString())
        maxCommentsSession.setText(maxComments.toString())
        commentGapSeconds.setText(gapSeconds.toString())

        prefs.edit()
            .putBoolean("enabled", masterEnabled)
            .putBoolean("auto_comment", autoComment.isChecked)
            .putInt("session_duration_min", durationMinutes)
            .putInt("interval_sec", seconds)
            .putInt("comment_every", every)
            .putInt("max_comments_session", maxComments)
            .putInt("comment_gap_sec", gapSeconds)
            .apply()

        localPrefs.edit().putString("comment_pool", commentPool.text.toString()).apply()
        YmTikTokAccessibilityService.notifyConfigChanged()
        render()
    }

    private fun render() {
        if (!::status.isInitialized) return
        val access = isAccessibilityEnabled()
        val running = prefs.getBoolean("enabled", false)
        val commentState = prefs.getBoolean("auto_comment", false)
        val count = localPrefs.getString("comment_pool", "").orEmpty()
            .lineSequence().count { it.isNotBlank() }
        val maxComments = prefs.getInt("max_comments_session", 5)
        val gap = prefs.getInt("comment_gap_sec", 60)
        val duration = prefs.getInt("session_duration_min", 60)

        status.text = buildString {
            append(if (running) "● AUTOPILOT يعمل" else "○ AUTOPILOT متوقف")
            append("\nمدة الجلسة: ")
            append(formatMinutes(duration))
            append("\nإمكانية الوصول: ")
            append(if (access) "مفعّلة" else "غير مفعّلة")
            append("\nالتعليقات: ")
            append(if (commentState) "مفعّلة ($count)" else "متوقفة")
            if (commentState) append(" — حد الجلسة $maxComments — فاصل ${gap}ث")
        }
        status.setTextColor(if (running && access) Color.rgb(37, 244, 238) else Color.WHITE)
    }

    private fun formatMinutes(minutes: Int): String = when {
        minutes < 60 -> "$minutes دقيقة"
        minutes % 60 == 0 -> "${minutes / 60} ساعة"
        else -> "${minutes / 60} ساعة و${minutes % 60} دقيقة"
    }

    private fun isAccessibilityEnabled(): Boolean {
        val component = ComponentName(this, YmTikTokAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabled.split(':').any { it.equals(component, ignoreCase = true) }
    }

    private fun launchTikTok() {
        val intent = TikTokScope.allowedPackages
            .asSequence()
            .mapNotNull { packageManager.getLaunchIntentForPackage(it) }
            .firstOrNull()

        if (intent == null) {
            Toast.makeText(this, "لم أجد تطبيق TikTok المدعوم على الجهاز", Toast.LENGTH_LONG).show()
            return
        }

        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.LTGRAY)
        textSize = 14f
        setPadding(0, dp(8), 0, dp(6))
    }

    private fun numberField(default: String) = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(default)
        setTextColor(Color.WHITE)
        setHintTextColor(Color.DKGRAY)
        textSize = 17f
        gravity = Gravity.CENTER
        background = roundedBox()
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    private fun cardText() = TextView(this).apply {
        setTextColor(Color.WHITE)
        textSize = 16f
        background = roundedBox()
        setPadding(dp(14), dp(14), dp(14), dp(14))
    }

    private fun actionButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 16f
        setOnClickListener { onClick() }
    }

    private fun roundedBox() = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(Color.rgb(18, 18, 18))
        setStroke(dp(1), Color.rgb(55, 55, 55))
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { bottomMargin = dp(8) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
