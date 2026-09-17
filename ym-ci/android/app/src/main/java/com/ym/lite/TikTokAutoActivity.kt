package com.ym.lite

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
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
import androidx.core.content.ContextCompat
import com.ym.lite.automation.TikTokScope
import com.ym.lite.overlay.YmOverlayService

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
            text = "المرحلة 1: خمسة أزرار عائمة فوق TikTok الرئيسي.\nلا نستخدم إمكانية الوصول في هذه النسخة."
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

        body.addView(actionButton("السماح لـ YM بالظهور فوق التطبيقات") {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            )
            startActivity(intent)
        }, matchWrap())

        body.addView(actionButton("تشغيل الأزرار وفتح TikTok الرئيسي") {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "اسمح أولًا لـ YM بالظهور فوق التطبيقات", Toast.LENGTH_LONG).show()
                return@actionButton
            }
            ContextCompat.startForegroundService(
                this,
                Intent(this, YmOverlayService::class.java),
            )
            launchMainTikTok()
        }, matchWrap())

        body.addView(actionButton("إيقاف الأزرار العائمة") {
            startService(
                Intent(this, YmOverlayService::class.java).setAction(YmOverlayService.ACTION_STOP),
            )
        }, matchWrap())

        body.addView(sectionTitle("وظائف الأزرار الخمسة"))

        body.addView(functionButton("1 — YM — الزر الرئيسي") {
            Toast.makeText(this, "زر 1 — YM: سنربط وظيفته الرئيسية هنا", Toast.LENGTH_SHORT).show()
        }, matchWrap())

        body.addView(functionButton("2 — 💬 — التعليقات") {
            Toast.makeText(this, "زر 2 — التعليقات: سنربط وظيفة التعليق هنا", Toast.LENGTH_SHORT).show()
        }, matchWrap())

        body.addView(functionButton("3 — ⚙ — الإعدادات") {
            Toast.makeText(this, "زر 3 — الإعدادات", Toast.LENGTH_SHORT).show()
        }, matchWrap())

        body.addView(functionButton("4 — غير محدد بعد") {
            Toast.makeText(this, "حدد لي وظيفة الزر 4", Toast.LENGTH_SHORT).show()
        }, matchWrap())

        body.addView(functionButton("5 — غير محدد بعد") {
            Toast.makeText(this, "حدد لي وظيفة الزر 5", Toast.LENGTH_SHORT).show()
        }, matchWrap())

        body.addView(TextView(this).apply {
            text = "الأزرار الخمسة في هذه الشاشة تقابل الأزرار العائمة 1 إلى 5. سنربط وظيفة كل زر هنا ثم نجعل الزر العائم ينفذ نفس الوظيفة."
            setTextColor(Color.LTGRAY)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, 0)
        })

        return scroll
    }

    private fun render() {
        if (!::status.isInitialized) return
        val overlayAllowed = Settings.canDrawOverlays(this)
        status.text = if (overlayAllowed) {
            "✓ إذن الظهور فوق التطبيقات مفعّل\nيمكن تشغيل الأزرار الخمسة"
        } else {
            "YM يحتاج إذن الظهور فوق التطبيقات فقط\nلا يحتاج Accessibility ولا قائمة الثلاث نقاط"
        }
        status.setTextColor(if (overlayAllowed) Color.rgb(37, 244, 238) else Color.WHITE)
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

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 21f
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(18), 0, dp(12))
    }

    private fun actionButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 17f
        setOnClickListener { onClick() }
    }

    private fun functionButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 17f
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(Color.rgb(35, 35, 35))
            setStroke(dp(1), Color.rgb(95, 95, 95))
        }
        setPadding(dp(12), dp(10), dp(12), dp(10))
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
