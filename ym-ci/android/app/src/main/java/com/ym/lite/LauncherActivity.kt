package com.ym.lite

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ym.lite.overlay.YmOverlayService

class LauncherActivity : AppCompatActivity() {
    private var launchAfterGrant = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher)

        findViewById<Button>(R.id.enableYm).setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                startOverlayAndTikTok()
            } else {
                launchAfterGrant = true
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                )
                startActivity(intent)
            }
        }

        findViewById<Button>(R.id.openTikTok).setOnClickListener {
            if (Settings.canDrawOverlays(this)) startOverlay()
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
        if (launchAfterGrant && Settings.canDrawOverlays(this)) {
            launchAfterGrant = false
            startOverlayAndTikTok()
        }
    }

    private fun renderState() {
        val overlay = Settings.canDrawOverlays(this)
        findViewById<TextView>(R.id.launcherState).text = if (overlay) {
            "YM جاهز بدون إذن إمكانية الوصول • TikTok الرسمي + فقاعات YM"
        } else {
            "فعّل الظهور فوق التطبيقات مرة واحدة. هذا لا يحتاج خيار الثلاث نقاط."
        }

        findViewById<Button>(R.id.enableYm).text = if (overlay) {
            "تشغيل YM وفتح TikTok"
        } else {
            "تفعيل فقاعات YM فوق TikTok"
        }
    }

    private fun startOverlayAndTikTok() {
        startOverlay()
        launchTikTok()
        finish()
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        val intent = Intent(this, YmOverlayService::class.java).setAction(YmOverlayService.ACTION_SHOW)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
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
