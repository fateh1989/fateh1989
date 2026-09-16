package com.ym.lite.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import kotlin.math.roundToInt

class YmAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile private var instance: YmAccessibilityService? = null
        fun notifyConfigChanged() { instance?.reloadFromPrefs() }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("ym_auto", MODE_PRIVATE) }
    private var overlay: TextView? = null
    private var scrollCount = 0
    private var commentIndex = 0

    private var enabled = false
    private var scrollEnabled = true
    private var autoComment = false
    private var intervalMs = 8_000L
    private var commentEvery = 3
    private var comments: List<String> = emptyList()

    private val loop = object : Runnable {
        override fun run() { tick() }
    }

    private val scopeGuard = object : Runnable {
        override fun run() {
            updateOverlayVisibility()
            handler.postDelayed(this, 250)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        reloadFromPrefs()
        handler.removeCallbacks(scopeGuard)
        handler.post(scopeGuard)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        updateOverlayVisibility()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun reloadFromPrefs() {
        enabled = prefs.getBoolean("auto_enabled", false)
        scrollEnabled = prefs.getBoolean("auto_scroll", true)
        autoComment = prefs.getBoolean("auto_comment", false)
        intervalMs = prefs.getInt("interval_sec", 8).coerceIn(3, 120) * 1000L
        commentEvery = prefs.getInt("comment_every", 3).coerceIn(1, 100)
        comments = prefs.getString("comment_pool", "").orEmpty()
            .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        handler.removeCallbacks(loop)
        updateOverlayText()
        if (enabled) handler.postDelayed(loop, 800)
    }

    private fun tick() {
        reloadRuntimeOnly()
        if (!enabled) return
        if (currentTikTokRoot() == null) {
            removeOverlay()
            handler.postDelayed(loop, 500)
            return
        }

        val shouldComment = autoComment && comments.isNotEmpty() && ((scrollCount + 1) % commentEvery == 0)
        if (shouldComment) {
            attemptComment {
                handler.postDelayed({ swipeAndContinue() }, 700)
            }
        } else {
            swipeAndContinue()
        }
    }

    private fun reloadRuntimeOnly() {
        enabled = prefs.getBoolean("auto_enabled", false)
        scrollEnabled = prefs.getBoolean("auto_scroll", true)
        autoComment = prefs.getBoolean("auto_comment", false)
    }

    private fun swipeAndContinue() {
        if (!enabled) return
        if (currentTikTokRoot() == null) {
            removeOverlay()
            handler.postDelayed(loop, 500)
            return
        }

        if (scrollEnabled) {
            val dm = resources.displayMetrics
            val x = dm.widthPixels / 2f
            val startY = dm.heightPixels * 0.78f
            val endY = dm.heightPixels * 0.22f
            val path = Path().apply {
                moveTo(x, startY)
                lineTo(x, endY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 220))
                .build()
            dispatchGesture(gesture, null, null)
            scrollCount++
            updateOverlayText()
        }
        handler.postDelayed(loop, intervalMs)
    }

    private fun attemptComment(done: () -> Unit) {
        val root = currentTikTokRoot() ?: run { done(); return }
        val commentButton = findNode(root) { node ->
            val label = nodeLabel(node)
            label.contains("comment") || label.contains("تعليق") || label.contains("kommentar")
        }
        if (!clickNode(commentButton)) {
            done()
            return
        }

        handler.postDelayed({
            val newRoot = currentTikTokRoot() ?: run { done(); return@postDelayed }
            val editor = findNode(newRoot) { node ->
                node.isEditable || node.className?.toString()?.contains("EditText") == true
            }
            if (editor == null) {
                safeBackIfTikTok()
                done()
                return@postDelayed
            }

            val text = comments[commentIndex % comments.size]
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            if (currentTikTokRoot() == null) {
                done()
                return@postDelayed
            }
            editor.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

            handler.postDelayed({
                val sendRoot = currentTikTokRoot() ?: run { done(); return@postDelayed }
                val send = findNode(sendRoot) { node ->
                    val label = nodeLabel(node)
                    label == "send" || label.contains("post") || label.contains("إرسال") || label.contains("نشر") || label.contains("skicka")
                }
                if (clickNode(send)) {
                    commentIndex++
                    handler.postDelayed({
                        safeBackIfTikTok()
                        done()
                    }, 450)
                } else {
                    safeBackIfTikTok()
                    done()
                }
            }, 350)
        }, 750)
    }

    private fun findNode(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(root)) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findNode(child, predicate)
            if (found != null) return found
        }
        return null
    }

    private fun nodeLabel(node: AccessibilityNodeInfo): String {
        return listOfNotNull(node.text, node.contentDescription)
            .joinToString(" ").lowercase().trim()
    }

    private fun clickNode(node: AccessibilityNodeInfo?): Boolean {
        if (currentTikTokRoot() == null) return false
        var current = node ?: return false
        repeat(5) {
            if (currentTikTokRoot() == null) return false
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = current.parent ?: return false
        }
        return false
    }

    private fun currentTikTokRoot(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return root.takeIf { TikTokScope.isAllowed(it.packageName) }
    }

    private fun isTikTokActive(): Boolean = currentTikTokRoot() != null

    private fun safeBackIfTikTok() {
        if (isTikTokActive()) performGlobalAction(GLOBAL_ACTION_BACK)
    }

    private fun updateOverlayVisibility() {
        if (isTikTokActive()) ensureOverlay() else removeOverlay()
    }

    private fun ensureOverlay() {
        if (overlay != null) {
            updateOverlayText()
            return
        }
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density
        val button = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding((12 * density).roundToInt(), (9 * density).roundToInt(), (12 * density).roundToInt(), (9 * density).roundToInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(220, 15, 15, 15))
                setStroke((2 * density).roundToInt(), Color.rgb(254, 44, 85))
            }
            setOnClickListener {
                if (!isTikTokActive()) return@setOnClickListener
                val newValue = !prefs.getBoolean("auto_enabled", false)
                prefs.edit().putBoolean("auto_enabled", newValue).apply()
                reloadFromPrefs()
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = (10 * density).roundToInt()
        }
        wm.addView(button, lp)
        overlay = button
        updateOverlayText()
    }

    private fun updateOverlayText() {
        overlay?.text = if (prefs.getBoolean("auto_enabled", false)) "AUTO\n● $scrollCount" else "AUTO\n○"
    }

    private fun removeOverlay() {
        val view = overlay ?: return
        runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view) }
        overlay = null
    }
}
