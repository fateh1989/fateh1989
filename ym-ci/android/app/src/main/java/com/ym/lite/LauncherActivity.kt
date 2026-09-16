package com.ym.lite

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ym.lite.automation.YmAccessibilityService

class LauncherActivity : AppCompatActivity() {
    private var launchAfterGrant = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher)

        findViewById<Button>(R.id.enableYm).setOnClickListener {
            if (isYmAccessibilityEnabled()) {
                launchTikTok()
                finish()
            } else {
                launchAfterGrant = true
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        findViewById<Button>(R.id.openTikTok).setOnClickListener {
            launchTikTok()
        }

        findViewById<Button>(R.id.openYmSettings).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        findViewById<Button>(R.id.openAdvancedAutomation).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        renderState()
    }

    override fun onResume() {
        super.onResume()
        renderState()
        if (launchAfterGrant && isYmAccessibilityEnabled()) {
            launchAfterGrant = false
            launchTikTok()
            finish()
        }
    }

    private fun renderState() {
        val enabled = isYmAccessibilityEnabled()
        findViewById<TextView>(R.id.launcherState).text = if (enabled) {
            "YM جاهز • أدوات YM تظهر داخل TikTok الرسمي فقط"
        } else {
            "فعّل خدمة YM AUTO مرة واحدة. بعد التفعيل تعمل أدوات YM داخل TikTok فقط."
        }

        findViewById<Button>(R.id.enableYm).text = if (enabled) {
            "فتح TikTok مع YM"
        } else {
            "تفعيل YM AUTO"
        }
    }

    private fun isYmAccessibilityEnabled(): Boolean {
        val component = ComponentName(this, YmAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabled.split(':').any { it.equals(component, ignoreCase = true) }
    }

    private fun launchTikTok() {
        val packages = listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")
        val intent = packages.firstNotNullOfOrNull { packageManager.getLaunchIntentForPackage(it) }
        if (intent == null) {
            Toast.makeText(this, "لم أجد تطبيق TikTok الرسمي مثبتًا", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
