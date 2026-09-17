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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ym.lite.automation.TikTokScope
import com.ym.lite.automation.YmTikTokAccessibilityService

class TikTokAutoActivity : AppCompatActivity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildScreen())
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
            setPadding(dp(20), dp(32), dp(20), dp(32))
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
            textSize = 34f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
        })

        body.addView(TextView(this).apply {
            text = "المرحلة 1: ثلاثة أزرار عائمة فقط فوق TikTok الرئيسي.\nلا تعليق تلقائي ولا وظائف إضافية في هذه النسخة."
            setTextColor(Color.LTGRAY)
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(22))
        })

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 17f
            gravity = Gravity.CENTER
            background = roundedBox()
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        body.addView(status, matchWrap().apply { bottomMargin = dp(18) })

        body.addView(actionButton("1 — تفعيل YM من إمكانية الوصول") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, matchWrap())

        body.addView(actionButton("2 — فتح TikTok الرئيسي") {
            if (!isAccessibilityEnabled()) {
                Toast.makeText(this, "فعّل YM Automation أولًا كي تظهر الأزرار", Toast.LENGTH_LONG).show()
            }
            launchMainTikTok()
        }, matchWrap())

        body.addView(TextView(this).apply {
            text = "عند فتح TikTok الرئيسي يجب أن يظهر على اليسار عمود: YM ثم 💬 ثم ⚙. يمكنك سحب العمود لمكانه فوق زر «تمرير تلقائي» الأصلي."
            setTextColor(Color.LTGRAY)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, 0)
        })

        return scroll
    }

    private fun render() {
        if (!::status.isInitialized) return
        val access = isAccessibilityEnabled()
        status.text = if (access) {
            "✓ YM Automation مفعّل\nافتح TikTok الرئيسي لرؤية الأزرار الثلاثة"
        } else {
            "YM Automation غير مفعّل\nاضغط الخطوة 1 ثم فعّله"
        }
        status.setTextColor(if (access) Color.rgb(37, 244, 238) else Color.WHITE)
    }

    private fun isAccessibilityEnabled(): Boolean {
        val component = ComponentName(this, YmTikTokAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabled.split(':').any { it.equals(component, ignoreCase = true) }
    }

    private fun launchMainTikTok() {
        val intent = TikTokScope.allowedPackages
            .asSequence()
            .mapNotNull { packageManager.getLaunchIntentForPackage(it) }
            .firstOrNull()
        if (intent == null) {
            Toast.makeText(this, "لم أجد TikTok الرئيسي على الجهاز", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun actionButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 17f
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
    ).apply { bottomMargin = dp(10) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
