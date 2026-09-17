package com.ym.lite

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
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ym.lite.automation.TikTokScope
import com.ym.lite.automation.YmCommentDefaults
import com.ym.lite.automation.YmTikTokAccessibilityService

class TikTokAutoActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("ym_tiktok_auto", MODE_PRIVATE) }
    private val localPrefs by lazy { getSharedPreferences("ym_local", MODE_PRIVATE) }

    private lateinit var status: TextView
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
            prefs.edit()
                .putBoolean("pending_launch", false)
                .putBoolean("enabled", true)
                .apply()
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
        scroll.addView(
            body,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        body.addView(TextView(this).apply {
            text = "YM — يم"
            setTextColor(Color.WHITE)
            textSize = 30f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
        })

        body.addView(TextView(this).apply {
            text = "افتح TikTok وشاهد الفيديوهات بشكل طبيعي. عندما يكون دولاب YM العائم ON، يضع YM تعليقًا تلقائيًا من الدولاب على كل فيديو تنتقل إليه."
            setTextColor(Color.LTGRAY)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(18))
        })

        status = cardText()
        body.addView(status, matchWrap().apply { bottomMargin = dp(16) })

        body.addView(label("🎡 دولاب التعليقات — كل سطر تعليق مستقل"))
        commentPool = EditText(this).apply {
            hint = "مثال:\nرائع 🔥\nاستمر 👏\nجميل جدًا ❤️"
            setHintTextColor(Color.DKGRAY)
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.TOP or Gravity.START
            minLines = 8
            background = roundedBox()
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        body.addView(commentPool, matchWrap().apply { bottomMargin = dp(14) })

        body.addView(actionButton("حفظ دولاب التعليقات") {
            savePool()
            Toast.makeText(this, "تم حفظ دولاب التعليقات", Toast.LENGTH_SHORT).show()
        }, matchWrap())

        body.addView(actionButton("تشغيل YM وفتح TikTok") {
            savePool()
            prefs.edit().putBoolean("enabled", true).apply()
            YmTikTokAccessibilityService.notifyConfigChanged()

            if (!isAccessibilityEnabled()) {
                prefs.edit().putBoolean("pending_launch", true).apply()
                Toast.makeText(
                    this,
                    "فعّل YM Automation ثم ارجع؛ سيفتح TikTok تلقائيًا",
                    Toast.LENGTH_LONG,
                ).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } else {
                launchTikTok()
            }
            render()
        }, matchWrap())

        body.addView(actionButton("إيقاف YM") {
            prefs.edit()
                .putBoolean("enabled", false)
                .putBoolean("pending_launch", false)
                .apply()
            localPrefs.edit()
                .putString("last_auto_stage", "ym_off")
                .putString("last_auto_action", "YM متوقف")
                .apply()
            YmTikTokAccessibilityService.notifyConfigChanged()
            render()
            Toast.makeText(this, "تم إيقاف YM", Toast.LENGTH_SHORT).show()
        }, matchWrap())

        body.addView(actionButton("تحديث التشخيص") { render() }, matchWrap())

        body.addView(actionButton("تصفير عداد التعليقات") {
            localPrefs.edit()
                .remove("stat_comment_ok")
                .remove("stat_comment_fail")
                .remove("comment_index")
                .remove("last_auto_action")
                .remove("last_auto_stage")
                .remove("last_auto_stage_at")
                .apply()
            render()
            YmTikTokAccessibilityService.notifyConfigChanged()
            Toast.makeText(this, "تم تصفير عداد التعليقات", Toast.LENGTH_SHORT).show()
        }, matchWrap())

        body.addView(actionButton("فتح إعدادات إمكانية الوصول") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, matchWrap())

        body.addView(actionButton("فتح TikTok الحقيقي") { launchTikTok() }, matchWrap())

        return scroll
    }

    private fun loadSettings() {
        val stored = localPrefs.getString("comment_pool", "").orEmpty()
        commentPool.setText(
            if (stored.isBlank()) YmCommentDefaults.all.joinToString("\n") else stored,
        )
    }

    private fun savePool() {
        localPrefs.edit().putString("comment_pool", commentPool.text.toString()).apply()
        YmTikTokAccessibilityService.notifyConfigChanged()
        render()
    }

    private fun render() {
        if (!::status.isInitialized) return
        val access = isAccessibilityEnabled()
        val running = prefs.getBoolean("enabled", false)
        val count = localPrefs.getString("comment_pool", "").orEmpty()
            .lineSequence()
            .count { it.isNotBlank() }
            .takeIf { it > 0 }
            ?: YmCommentDefaults.all.size
        val commentOk = localPrefs.getInt("stat_comment_ok", 0)
        val commentFail = localPrefs.getInt("stat_comment_fail", 0)
        val lastStage = localPrefs.getString("last_auto_stage", "").orEmpty()
        val lastAction = localPrefs.getString("last_auto_action", "").orEmpty()
        val overlayError = localPrefs.getString("last_overlay_error", "").orEmpty()

        status.text = buildString {
            append(if (running) "● YM يعمل" else "○ YM متوقف")
            append("\nإمكانية الوصول: ")
            append(if (access) "مفعّلة" else "غير مفعّلة")
            append("\nتعليقات داخل الدولاب: $count")
            append("\nتعليقات ناجحة: $commentOk")
            append(" | فشل: $commentFail")
            if (lastStage.isNotBlank()) append("\nمرحلة التشخيص: $lastStage")
            if (lastAction.isNotBlank()) append("\nآخر حدث: $lastAction")
            if (overlayError.isNotBlank()) append("\nخطأ الزر العائم: $overlayError")
        }
        status.setTextColor(
            if (running && access) Color.rgb(37, 244, 238) else Color.WHITE,
        )
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
