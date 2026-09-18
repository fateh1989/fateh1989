package com.ym.lite.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.ym.lite.TikTokAutoActivity
import com.ym.lite.automation.YmTikTokAccessibilityService
import com.ym.lite.comment.CommentWheelActivity
import com.ym.lite.comment.GenericWheelActivity
import kotlin.math.abs
import kotlin.math.roundToInt

class YmOverlayService : Service() {
    companion object {
        const val ACTION_STOP = "com.ym.lite.overlay.STOP"
        const val ACTION_REFRESH = "com.ym.lite.overlay.REFRESH"
        private const val CHANNEL_ID = "ym_overlay"
        private const val NOTIFICATION_ID = 3201
    }

    private var windowManager: WindowManager? = null
    private var overlayView: LinearLayout? = null
    private var fourthButton: TextView? = null
    private val localPrefs by lazy { getSharedPreferences("ym_overlay", MODE_PRIVATE) }
    private val autoPrefs by lazy { getSharedPreferences("ym_auto_comment", MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        showOverlay()
        if (intent?.action == ACTION_REFRESH) updateFourthAppearance()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        removeOverlay()
        super.onDestroy()
    }

    private fun showOverlay() {
        if (overlayView != null) {
            updateFourthAppearance()
            return
        }

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val dm = resources.displayMetrics
        val density = dm.density
        val buttonSize = (58 * density).roundToInt()
        val gap = (5 * density).roundToInt()
        val buttonCount = 5
        val stackHeight = buttonSize * buttonCount + gap * (buttonCount - 1)
        val maxX = (dm.widthPixels - buttonSize).coerceAtLeast(0)
        val maxY = (dm.heightPixels - stackHeight).coerceAtLeast(0)
        val defaultX = (8 * density).roundToInt().coerceIn(0, maxX)
        val defaultY = (dm.heightPixels * 0.22f).roundToInt().coerceIn(0, maxY)

        val params = WindowManager.LayoutParams(
            buttonSize,
            stackHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = localPrefs.getInt("x", defaultX).coerceIn(0, maxX)
            y = localPrefs.getInt("y", defaultY).coerceIn(0, maxY)
        }

        fun button(label: String): TextView = TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 14f
            background = defaultBubble(density)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        val stack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        val ym = button("1\nYM")
        val comment = button("2\n💬")
        val settings = button("3\n⚙")
        val fourth = button("4\n⚡")
        val fifth = button("5")
        fourthButton = fourth

        listOf(ym, comment, settings, fourth, fifth).forEachIndexed { index, view ->
            stack.addView(
                view,
                LinearLayout.LayoutParams(buttonSize, buttonSize).apply {
                    if (index > 0) topMargin = gap
                },
            )
        }

        ym.setOnClickListener {
            openGenericWheel(1)
        }
        comment.setOnClickListener {
            openGenericWheel(2)
        }

        fourth.setOnClickListener {
            if (!isAutoCommentEngineEnabled()) {
                openCommentWheel()
                Toast.makeText(this, "فعّل محرك YM Auto Comment من شاشة زر 4", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val next = !autoPrefs.getBoolean("enabled", false)
            autoPrefs.edit().putBoolean("enabled", next).apply()
            YmTikTokAccessibilityService.notifyConfigChanged()
            updateFourthAppearance()
            Toast.makeText(this, if (next) "زر 4 يعمل" else "زر 4 متوقف", Toast.LENGTH_SHORT).show()
        }
        fourth.setOnLongClickListener {
            openCommentWheel()
            true
        }

        var downRawX = 0f
        var downRawY = 0f
        var downX = 0
        var downY = 0
        var dragging = false
        val threshold = 8 * density

        ym.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downX = params.x
                    downY = params.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (abs(dx) > threshold || abs(dy) > threshold) dragging = true
                    if (dragging) {
                        params.x = (downX + dx.roundToInt()).coerceIn(0, maxX)
                        params.y = (downY + dy.roundToInt()).coerceIn(0, maxY)
                        runCatching { wm.updateViewLayout(stack, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) localPrefs.edit().putInt("x", params.x).putInt("y", params.y).apply()
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }

        runCatching {
            wm.addView(stack, params)
            overlayView = stack
            updateFourthAppearance()
        }.onFailure {
            overlayView = null
            fourthButton = null
            stopSelf()
        }
    }

    private fun updateFourthAppearance() {
        val button = fourthButton ?: return
        val density = resources.displayMetrics.density
        val active = autoPrefs.getBoolean("enabled", false)
        button.text = if (active) "4\nON" else "4\nOFF"
        button.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (active) Color.rgb(0, 125, 110) else Color.argb(235, 18, 18, 18))
            setStroke(
                (2 * density).roundToInt(),
                if (active) Color.rgb(37, 244, 238) else Color.rgb(225, 225, 225),
            )
        }
    }

    private fun defaultBubble(density: Float) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.argb(235, 18, 18, 18))
        setStroke((2 * density).roundToInt(), Color.rgb(225, 225, 225))
    }

    private fun isAutoCommentEngineEnabled(): Boolean {
        val component = ComponentName(this, YmTikTokAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return enabled.split(':').any { it.equals(component, ignoreCase = true) }
    }

    private fun openGenericWheel(id: Int) {
        startActivity(
            Intent(this, GenericWheelActivity::class.java)
                .putExtra(GenericWheelActivity.EXTRA_WHEEL_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    private fun openCommentWheel() {
        startActivity(
            Intent(this, CommentWheelActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    private fun removeOverlay() {
        val view = overlayView ?: return
        runCatching { windowManager?.removeView(view) }
        overlayView = null
        fourthButton = null
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "YM floating buttons", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("YM")
        .setContentText("الأزرار العائمة تعمل")
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, TikTokAutoActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()
}
