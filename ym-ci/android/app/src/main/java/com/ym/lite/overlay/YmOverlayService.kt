package com.ym.lite.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.ym.lite.LauncherActivity
import com.ym.lite.MainActivity
import com.ym.lite.R
import kotlin.math.roundToInt

class YmOverlayService : Service() {
    companion object {
        const val ACTION_SHOW = "com.ym.lite.overlay.SHOW"
        const val ACTION_HIDE = "com.ym.lite.overlay.HIDE"
        const val CHANNEL_ID = "ym_overlay"
        const val NOTIFICATION_ID = 1101
    }

    private lateinit var wm: WindowManager
    private var bubble: View? = null
    private var menu: View? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> stopSelf()
            else -> showBubble()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        removeView(menu)
        removeView(bubble)
        menu = null
        bubble = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showBubble() {
        if (!Settings.canDrawOverlays(this) || bubble != null) return
        val density = resources.displayMetrics.density
        val button = TextView(this).apply {
            text = "YM"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = circle(Color.argb(235, 25, 25, 25), Color.rgb(37, 244, 238))
        }
        val lp = overlayParams(Gravity.END or Gravity.CENTER_VERTICAL).apply { x = dp(10) }
        attachDrag(button, lp)
        button.setOnClickListener { toggleMenu() }
        wm.addView(button, lp)
        bubble = button
    }

    private fun toggleMenu() {
        if (menu != null) {
            removeView(menu)
            menu = null
            return
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.argb(235, 16, 16, 16))
                setStroke(dp(1), Color.DKGRAY)
            }
        }

        box.addView(action("AUTO", "TikTok"))
        box.addView(action("تعليق", "YM"))
        box.addView(action("جدولة", "YM"))
        box.addView(action("إعدادات", "YM"))
        box.addView(action("إخفاء", "HIDE"))

        val lp = overlayParams(Gravity.END or Gravity.CENTER_VERTICAL).apply { x = dp(76) }
        wm.addView(box, lp)
        menu = box
    }

    private fun action(title: String, action: String): TextView {
        return TextView(this).apply {
            text = title
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnClickListener {
                when (action) {
                    "TikTok" -> launchTikTok()
                    "YM" -> openYmSettings()
                    "HIDE" -> stopSelf()
                }
            }
        }
    }

    private fun launchTikTok() {
        val packages = listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")
        val target = packages.firstNotNullOfOrNull { packageManager.getLaunchIntentForPackage(it) } ?: return
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(target)
    }

    private fun openYmSettings() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun overlayParams(gravityValue: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = gravityValue }

    private fun attachDrag(view: View, lp: WindowManager.LayoutParams) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = (startX + (downX - event.rawX)).toInt().coerceAtLeast(0)
                    lp.y = startY + (event.rawY - downY).toInt()
                    runCatching { wm.updateViewLayout(view, lp) }
                    true
                }
                else -> false
            }
        }
    }

    private fun circle(fill: Int, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        setStroke(dp(2), stroke)
    }

    private fun removeView(view: View?) {
        if (view == null) return
        runCatching { wm.removeView(view) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "YM overlay", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, LauncherActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("YM")
            .setContentText("أدوات YM تعمل فوق TikTok")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }
}
