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
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import kotlin.math.roundToInt

class YmTikTokAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile private var instance: YmTikTokAccessibilityService? = null
        fun notifyConfigChanged() { instance?.reloadFromPrefs() }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("ym_tiktok_auto", MODE_PRIVATE) }
    private val localPrefs by lazy { getSharedPreferences("ym_local", MODE_PRIVATE) }

    private var overlay: TextView? = null
    private var enabled = false
    private var autoComment = false
    private var intervalMs = 4_000L
    private var commentEvery = 1
    private var scrollCount = 0
    private var commentIndex = 0
    private var comments: List<String> = emptyList()
    private var lastTikTokSeenAt = 0L

    private val loop = object : Runnable {
        override fun run() = tick()
    }

    private val scopeWatch = object : Runnable {
        override fun run() {
            if (isTikTokForeground()) ensureOverlay() else removeOverlay()
            handler.postDelayed(this, 300)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        reloadFromPrefs()
        handler.removeCallbacks(scopeWatch)
        handler.post(scopeWatch)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (TikTokScope.isAllowed(event?.packageName)) {
            lastTikTokSeenAt = SystemClock.uptimeMillis()
            ensureOverlay()
        } else if (!isTikTokForeground()) {
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

    fun reloadFromPrefs() {
        enabled = prefs.getBoolean("enabled", false)
        autoComment = prefs.getBoolean("auto_comment", true)
        intervalMs = prefs.getInt("interval_sec", 4).coerceIn(3, 120) * 1000L
        commentEvery = prefs.getInt("comment_every", 1).coerceIn(1, 100)
        comments = localPrefs.getString("comment_pool", "").orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
            .ifEmpty { YmCommentDefaults.all }

        handler.removeCallbacks(loop)
        updateOverlayAppearance()
        if (enabled) handler.postDelayed(loop, 700)
    }

    private fun tick() {
        enabled = prefs.getBoolean("enabled", false)
        autoComment = prefs.getBoolean("auto_comment", true)
        if (!enabled) return

        if (!isTikTokForeground()) {
            removeOverlay()
            handler.postDelayed(loop, 500)
            return
        }

        val shouldComment = autoComment && comments.isNotEmpty() && ((scrollCount + 1) % commentEvery == 0)
        if (shouldComment) {
            attemptComment {
                handler.postDelayed({ swipeAndContinue() }, 450)
            }
        } else {
            swipeAndContinue()
        }
    }

    private fun swipeAndContinue() {
        if (!prefs.getBoolean("enabled", false)) return
        if (!isTikTokForeground()) {
            removeOverlay()
            handler.postDelayed(loop, 500)
            return
        }

        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val startY = dm.heightPixels * 0.79f
        val endY = dm.heightPixels * 0.21f
        val path = Path().apply {
            moveTo(x, startY)
            lineTo(x, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 190))
            .build()

        if (!isTikTokForeground()) {
            handler.postDelayed(loop, 500)
            return
        }

        dispatchGesture(gesture, null, null)
        scrollCount++
        updateOverlayAppearance()
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

        handler.postDelayed(openEditor@{
            val editorRoot = currentTikTokRoot() ?: run { done(); return@openEditor }
            val editor = findNode(editorRoot) { node ->
                node.isEditable || node.className?.toString()?.contains("EditText") == true
            }
            if (editor == null) {
                safeBackIfTikTok()
                done()
                return@openEditor
            }

            val text = comments[commentIndex % comments.size]
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            if (currentTikTokRoot() == null) {
                done()
                return@openEditor
            }
            editor.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

            handler.postDelayed(sendComment@{
                val sendRoot = currentTikTokRoot() ?: run { done(); return@sendComment }
                val send = findNode(sendRoot) { node ->
                    val label = nodeLabel(node)
                    label == "send" || label.contains("post") || label.contains("إرسال") ||
                        label.contains("نشر") || label.contains("skicka")
                }
                if (clickNode(send)) {
                    commentIndex++
                    handler.postDelayed({
                        safeBackIfTikTok()
                        done()
                    }, 350)
                } else {
                    safeBackIfTikTok()
                    done()
                }
            }, 320)
        }, 650)
    }

    private fun currentTikTokRoot(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return root.takeIf { TikTokScope.isAllowed(it.packageName) }
    }

    private fun isTikTokForeground(): Boolean {
        val root = rootInActiveWindow
        if (root != null && TikTokScope.isAllowed(root.packageName)) {
            lastTikTokSeenAt = SystemClock.uptimeMillis()
            return true
        }
        return SystemClock.uptimeMillis() - lastTikTokSeenAt < 1_500L
    }

    private fun clickNode(node: AccessibilityNodeInfo?): Boolean {
        if (!isTikTokForeground()) return false
        var current = node ?: return false
        repeat(6) {
            if (!isTikTokForeground()) return false
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = current.parent ?: return false
        }
        return false
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
            .joinToString(" ")
            .lowercase()
            .trim()
    }

    private fun safeBackIfTikTok() {
        if (isTikTokForeground()) performGlobalAction(GLOBAL_ACTION_BACK)
    }

    private fun ensureOverlay() {
        if (overlay != null) {
            updateOverlayAppearance()
            return
        }

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density
        val size = (76 * density).roundToInt()
        val button = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 19f
            maxLines = 2
            isClickable = true
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnClickListener {
                if (!isTikTokForeground()) return@setOnClickListener
                val next = !prefs.getBoolean("enabled", false)
                val edit = prefs.edit().putBoolean("enabled", next)
                if (next) {
                    edit.putBoolean("auto_comment", true)
                    edit.putInt("comment_every", 1)
                }
                edit.apply()
                reloadFromPrefs()
            }
        }
        val lp = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = (12 * density).roundToInt()
        }

        runCatching {
            wm.addView(button, lp)
            overlay = button
            localPrefs.edit().remove("last_overlay_error").apply()
            updateOverlayAppearance()
        }.onFailure { error ->
            localPrefs.edit().putString("last_overlay_error", error.javaClass.simpleName + ": " + error.message.orEmpty()).apply()
            overlay = null
        }
    }

    private fun updateOverlayAppearance() {
        val view = overlay ?: return
        val active = prefs.getBoolean("enabled", false)
        view.text = if (active) "🎡\nON" else "🎡\nOFF"
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (active) Color.rgb(0, 125, 110) else Color.argb(232, 18, 18, 18))
            setStroke(
                (2 * resources.displayMetrics.density).roundToInt(),
                if (active) Color.rgb(37, 244, 238) else Color.rgb(220, 220, 220),
            )
        }
    }

    private fun removeOverlay() {
        val view = overlay ?: return
        runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view) }
        overlay = null
    }
}
