package com.ym.lite.automation

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

class YmTikTokAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile private var instance: YmTikTokAccessibilityService? = null
        fun notifyConfigChanged() { instance?.refreshOverlay() }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val localPrefs by lazy { getSharedPreferences("ym_local", MODE_PRIVATE) }

    private var overlay: LinearLayout? = null
    private var lastTikTokSeenAt = 0L

    private val scopeWatch = object : Runnable {
        override fun run() {
            if (isMainTikTokActive()) {
                lastTikTokSeenAt = SystemClock.uptimeMillis()
                ensureOverlay()
            } else if (SystemClock.uptimeMillis() - lastTikTokSeenAt > 350L) {
                removeOverlay()
            }
            handler.postDelayed(this, 250L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        handler.removeCallbacks(scopeWatch)
        handler.post(scopeWatch)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (TikTokScope.isAllowed(event?.packageName)) {
            lastTikTokSeenAt = SystemClock.uptimeMillis()
            ensureOverlay()
        } else if (!isMainTikTokActive()) {
            removeOverlay()
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun refreshOverlay() {
        if (isMainTikTokActive()) ensureOverlay() else removeOverlay()
    }

    private fun isMainTikTokActive(): Boolean {
        val root = rootInActiveWindow ?: return false
        return TikTokScope.isAllowed(root.packageName)
    }

    private fun ensureOverlay() {
        if (overlay != null) return

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dm = resources.displayMetrics
        val density = dm.density
        val buttonSize = (56 * density).roundToInt()
        val gap = (6 * density).roundToInt()
        val stackHeight = buttonSize * 3 + gap * 2
        val maxX = (dm.widthPixels - buttonSize).coerceAtLeast(0)
        val maxY = (dm.heightPixels - stackHeight).coerceAtLeast(0)

        val defaultX = (10 * density).roundToInt().coerceIn(0, maxX)
        val defaultY = (dm.heightPixels * 0.27f).roundToInt().coerceIn(0, maxY)
        val startX = localPrefs.getInt("overlay_x", defaultX).coerceIn(0, maxX)
        val startY = localPrefs.getInt("overlay_y", defaultY).coerceIn(0, maxY)

        val lp = WindowManager.LayoutParams(
            buttonSize,
            stackHeight,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = startX
            y = startY
        }

        val stack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        fun makeButton(label: String): TextView = TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 15f
            isClickable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = bubbleBackground()
        }

        val buttons = listOf(
            makeButton("YM"),
            makeButton("💬"),
            makeButton("⚙"),
        )

        buttons.forEachIndexed { index, button ->
            stack.addView(
                button,
                LinearLayout.LayoutParams(buttonSize, buttonSize).apply {
                    if (index > 0) topMargin = gap
                },
            )
        }

        var downRawX = 0f
        var downRawY = 0f
        var downX = 0
        var downY = 0
        var dragged = false
        val dragThreshold = 8 * density

        val touchListener = View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downX = lp.x
                    downY = lp.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (abs(dx) > dragThreshold || abs(dy) > dragThreshold) dragged = true
                    if (dragged) {
                        lp.x = (downX + dx.roundToInt()).coerceIn(0, maxX)
                        lp.y = (downY + dy.roundToInt()).coerceIn(0, maxY)
                        runCatching { wm.updateViewLayout(stack, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragged) {
                        localPrefs.edit()
                            .putInt("overlay_x", lp.x)
                            .putInt("overlay_y", lp.y)
                            .apply()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }

        buttons.forEach { it.setOnTouchListener(touchListener) }

        runCatching {
            wm.addView(stack, lp)
            overlay = stack
            localPrefs.edit().remove("last_overlay_error").apply()
        }.onFailure { error ->
            localPrefs.edit()
                .putString("last_overlay_error", error.javaClass.simpleName + ": " + error.message.orEmpty())
                .apply()
            overlay = null
        }
    }

    private fun bubbleBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.argb(235, 20, 20, 20))
        setStroke(
            (2 * resources.displayMetrics.density).roundToInt(),
            Color.rgb(37, 244, 238),
        )
    }

    private fun removeOverlay() {
        val view = overlay ?: return
        runCatching {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view)
        }
        overlay = null
    }
}
