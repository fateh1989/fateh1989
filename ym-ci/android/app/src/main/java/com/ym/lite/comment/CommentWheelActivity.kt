package com.ym.lite.comment

import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.ym.lite.automation.YmCommentDefaults
import com.ym.lite.automation.YmTikTokAccessibilityService
import com.ym.lite.overlay.YmOverlayService

class CommentWheelActivity : AppCompatActivity() {
    private val autoPrefs by lazy { getSharedPreferences("ym_auto_comment", MODE_PRIVATE) }
    private val localPrefs by lazy { getSharedPreferences("ym_local", MODE_PRIVATE) }

    private lateinit var status: TextView
    private lateinit var pool: EditText
    private lateinit var wheel: CommentWheelView
    private lateinit var speedLabel: TextView
    private lateinit var speedSeek: SeekBar
    private lateinit var toggle: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildScreen())
        loadValues()
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun buildScreen(): ScrollView {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.BLACK) }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = LinearLayout.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(24), dp(18), dp(34))
        }
        scroll.addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        body.addView(TextView(this).apply {
            text = "زر 4 — التعليق الأوتوماتيكي"
            setTextColor(Color.WHITE)
            textSize = 27f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
        })

        body.addView(TextView(this).apply {
            text = "🎡 دولاب كبير يختار تعليقًا من قائمتك ويستخدمه عند الانتقال إلى فيديو جديد في TikTok الرئيسي."
            setTextColor(Color.LTGRAY)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(12))
        })

        status = cardText()
        body.addView(status, matchWrap().apply { bottomMargin = dp(14) })

        wheel = CommentWheelView(this)
        body.addView(wheel, LinearLayout.LayoutParams(dp(340), dp(340)).apply { bottomMargin = dp(12) })

        body.addView(actionButton("تدوير للدور التالي") {
            wheel.selectNext()
        }, matchWrap())

        speedLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
        }
        body.addView(speedLabel, matchWrap())

        speedSeek = SeekBar(this).apply {
            max = 6
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    speedLabel.text = "السرعة الدنيا بين تعليقين: ${progress + 2} ثوانٍ"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = saveValues(showToast = false)
            })
        }
        body.addView(speedSeek, matchWrap())

        body.addView(TextView(this).apply {
            text = "كل سطر = تعليق مستقل داخل الدولاب"
            setTextColor(Color.LTGRAY)
            textSize = 15f
            gravity = Gravity.START
            setPadding(0, dp(10), 0, dp(6))
        })

        pool = EditText(this).apply {
            setTextColor(Color.WHITE)
            setHintTextColor(Color.DKGRAY)
            hint = "رائع 🔥\nجميل جدًا 👏\nاستمر ❤️"
            textSize = 16f
            minLines = 8
            gravity = Gravity.TOP or Gravity.START
            background = roundedBox()
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        body.addView(pool, matchWrap().apply { bottomMargin = dp(12) })

        body.addView(actionButton("حفظ دولاب التعليقات") {
            saveValues(showToast = true)
        }, matchWrap())

        toggle = actionButton("تشغيل التعليق الأوتوماتيكي") {
            saveValues(showToast = false)
            if (!isAccessibilityEnabled()) {
                Toast.makeText(this, "زر 4 يحتاج محرك YM Auto Comment للتحكم داخل TikTok", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@actionButton
            }
            val next = !autoPrefs.getBoolean("enabled", false)
            autoPrefs.edit().putBoolean("enabled", next).apply()
            YmTikTokAccessibilityService.notifyConfigChanged()
            refreshOverlay()
            render()
        }
        body.addView(toggle, matchWrap())

        body.addView(actionButton("فتح إعدادات محرك التعليق") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, matchWrap())

        body.addView(TextView(this).apply {
            text = "الظهور العائم وحده لا يستطيع الكتابة داخل TikTok؛ محرك YM Auto Comment هو الجزء الذي يضغط حقل التعليق ويكتب ويرسل."
            setTextColor(Color.LTGRAY)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        })

        return scroll
    }

    private fun loadValues() {
        val stored = localPrefs.getString("comment_pool", "").orEmpty()
        val storedComments = parseComments(stored)
        val useDefaultLibrary = stored.isBlank() || YmCommentDefaults.isLegacyDefault(storedComments)
        val comments = if (useDefaultLibrary) YmCommentDefaults.all else storedComments
        val text = comments.joinToString("\n")

        if (useDefaultLibrary) {
            localPrefs.edit().putString("comment_pool", text).apply()
            YmTikTokAccessibilityService.notifyConfigChanged()
        }

        pool.setText(text)
        wheel.setComments(comments, localPrefs.getInt("comment_index", 0))

        val seconds = autoPrefs.getInt("min_interval_seconds", 3).coerceIn(2, 8)
        speedSeek.progress = seconds - 2
        speedLabel.text = "السرعة الدنيا بين تعليقين: $seconds ثوانٍ"

        wheel.onSelectionChanged = { index, _ ->
            localPrefs.edit().putInt("comment_index", index).apply()
        }
    }

    private fun saveValues(showToast: Boolean) {
        val text = pool.text.toString()
        localPrefs.edit().putString("comment_pool", text).apply()
        autoPrefs.edit().putInt("min_interval_seconds", speedSeek.progress + 2).apply()
        val comments = parseComments(text).ifEmpty { YmCommentDefaults.all }
        wheel.setComments(comments, localPrefs.getInt("comment_index", 0))
        YmTikTokAccessibilityService.notifyConfigChanged()
        refreshOverlay()
        if (showToast) Toast.makeText(this, "تم حفظ دولاب زر 4", Toast.LENGTH_SHORT).show()
        render()
    }

    private fun render() {
        if (!::status.isInitialized) return
        val access = isAccessibilityEnabled()
        val enabled = autoPrefs.getBoolean("enabled", false)
        val ok = localPrefs.getInt("stat_comment_ok", 0)
        val fail = localPrefs.getInt("stat_comment_fail", 0)
        val last = localPrefs.getString("last_auto_action", "").orEmpty()

        status.text = buildString {
            append(if (enabled) "● زر 4 يعمل" else "○ زر 4 متوقف")
            append("\nمحرك التحكم: ")
            append(if (access) "مفعّل" else "غير مفعّل")
            append("\nتعليقات ناجحة: $ok | فشل: $fail")
            if (last.isNotBlank()) append("\nآخر حدث: $last")
        }
        status.setTextColor(if (enabled && access) Color.rgb(37, 244, 238) else Color.WHITE)
        toggle.text = if (enabled) "إيقاف التعليق الأوتوماتيكي" else "تشغيل التعليق الأوتوماتيكي"
    }

    private fun isAccessibilityEnabled(): Boolean {
        val component = ComponentName(this, YmTikTokAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return enabled.split(':').any { it.equals(component, ignoreCase = true) }
    }

    private fun refreshOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        ContextCompat.startForegroundService(
            this,
            Intent(this, YmOverlayService::class.java).setAction(YmOverlayService.ACTION_REFRESH),
        )
    }

    private fun parseComments(text: String): List<String> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toList()

    private fun cardText() = TextView(this).apply {
        setTextColor(Color.WHITE)
        textSize = 16f
        gravity = Gravity.CENTER
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
