package com.ym.lite

import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ym.lite.automation.TikTokScope
import com.ym.lite.automation.YmTikTokAccessibilityService

class YmEntryActivity : AppCompatActivity() {
    private var launchedOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildSetup())
    }

    override fun onResume() {
        super.onResume()
        if (!launchedOnce && isAccessibilityEnabled()) {
            launchedOnce = true
            launchTikTokOrStay()
        }
    }

    private fun buildSetup(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            setPadding(dp(28), dp(42), dp(28), dp(42))

            addView(TextView(this@YmEntryActivity).apply {
                text = "YM"
                setTextColor(Color.WHITE)
                textSize = 48f
                gravity = Gravity.CENTER
            }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            addView(TextView(this@YmEntryActivity).apply {
                text = "TikTok الحقيقي + أدوات YM"
                setTextColor(Color.LTGRAY)
                textSize = 18f
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(24))
            }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            addView(Button(this@YmEntryActivity).apply {
                text = "فتح TikTok الحقيقي"
                isAllCaps = false
                setOnClickListener { launchTikTokOrStay() }
            }, matchWrap())

            addView(Button(this@YmEntryActivity).apply {
                text = "تفعيل YM Automation"
                isAllCaps = false
                setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }, matchWrap())

            addView(Button(this@YmEntryActivity).apply {
                text = "إعداد TikTok AUTO"
                isAllCaps = false
                setOnClickListener { startActivity(Intent(this@YmEntryActivity, TikTokAutoActivity::class.java)) }
            }, matchWrap())

            addView(Button(this@YmEntryActivity).apply {
                text = "مكتبة YM المحلية"
                isAllCaps = false
                setOnClickListener { startActivity(Intent(this@YmEntryActivity, FeedActivity::class.java)) }
            }, matchWrap())
        }
    }

    private fun launchTikTokOrStay() {
        val intent = TikTokScope.allowedPackages
            .asSequence()
            .mapNotNull { packageManager.getLaunchIntentForPackage(it) }
            .firstOrNull()
        if (intent == null) {
            Toast.makeText(this, "TikTok الرسمي غير موجود على الجهاز", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun isAccessibilityEnabled(): Boolean {
        val component = ComponentName(this, YmTikTokAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabled.split(':').any { it.equals(component, ignoreCase = true) }
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { bottomMargin = dp(10) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
