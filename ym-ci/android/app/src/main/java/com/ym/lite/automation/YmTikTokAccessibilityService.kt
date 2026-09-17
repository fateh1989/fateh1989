package com.ym.lite.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
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
import com.ym.lite.TikTokAutoActivity
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
    private var intervalMs = 8_000L
    private var commentEvery = 3
    private var maxCommentsPerSession = 5
    private var commentGapMs = 60_000L
    private var scrollCount = 0
    private var commentIndex = 0
    private var commentsSent = 0
    private var lastCommentAt = 0L
    private var gestureInFlight = false
    private var comments: List<String> = emptyList()

    private val loop = object : Runnable { override fun run() = tick() }
    private val scopeWatch = object : Runnable {
        override fun run() {
            if (currentTikTokRoot() == null) removeOverlay() else ensureOverlay()
            handler.postDelayed(this, 250)
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
        if (currentTikTokRoot() == null) removeOverlay() else ensureOverlay()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun reloadFromPrefs() {
        val wasEnabled = enabled
        enabled = prefs.getBoolean("enabled", false)
        autoComment = prefs.getBoolean("auto_comment", false)
        intervalMs = prefs.getInt("interval_sec", 8).coerceIn(3, 120) * 1000L
        commentEvery = prefs.getInt("comment_every", 3).coerceIn(1, 100)
        maxCommentsPerSession = prefs.getInt("max_comments_session", 5).coerceIn(1, 50)
        commentGapMs = prefs.getInt("comment_gap_sec", 60).coerceIn(30, 3600) * 1000L
        comments = localPrefs.getString("comment_pool", "").orEmpty()
            .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

        if (enabled && !wasEnabled) {
            scrollCount = 0
            commentIndex = 0
            commentsSent = 0
            lastCommentAt = 0L
            gestureInFlight = false
        }
        if (!enabled) gestureInFlight = false

        handler.removeCallbacks(loop)
        updateOverlayText()
        if (enabled) handler.postDelayed(loop, 800)
    }

    private fun tick() {
        enabled = prefs.getBoolean("enabled", false)
        autoComment = prefs.getBoolean("auto_comment", false)
        if (!enabled) return
        if (gestureInFlight) {
            handler.postDelayed(loop, 250)
            return
        }
        if (currentTikTokRoot() == null) {
            removeOverlay()
            handler.postDelayed(loop, 500)
            return
        }

        val now = System.currentTimeMillis()
        val commentDueByVideo = (scrollCount + 1) % commentEvery == 0
        val commentDueByTime = now - lastCommentAt >= commentGapMs
        val withinSessionLimit = commentsSent < maxCommentsPerSession
        val shouldComment = autoComment && comments.isNotEmpty() && commentDueByVideo &&
            commentDueByTime && withinSessionLimit

        if (shouldComment) attemptComment { handler.postDelayed({ swipeAndContinue() }, 500) }
        else swipeAndContinue()
    }

    private fun swipeAndContinue() {
        if (!prefs.getBoolean("enabled", false)) return
        if (currentTikTokRoot() == null) {
            removeOverlay()
            handler.postDelayed(loop, 500)
            return
        }
        if (gestureInFlight) return

        val dm = resources.displayMetrics
        val path = Path().apply {
            moveTo(dm.widthPixels / 2f, dm.heightPixels * 0.79f)
            lineTo(dm.widthPixels / 2f, dm.heightPixels * 0.21f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 180)).build()

        if (currentTikTokRoot() == null) {
            handler.postDelayed(loop, 500)
            return
        }

        gestureInFlight = true
        val accepted = dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    gestureInFlight = false
                    if (!prefs.getBoolean("enabled", false) || currentTikTokRoot() == null) {
                        handler.postDelayed(loop, 500)
                        return
                    }
                    scrollCount++
                    updateOverlayText()
                    handler.postDelayed(loop, intervalMs)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    gestureInFlight = false
                    handler.postDelayed(loop, 800)
                }
            },
            null,
        )

        if (!accepted) {
            gestureInFlight = false
            handler.postDelayed(loop, 800)
        }
    }

    private fun attemptComment(done: () -> Unit) {
        if (commentsSent >= maxCommentsPerSession) { done(); return }
        if (System.currentTimeMillis() - lastCommentAt < commentGapMs) { done(); return }

        val root = currentTikTokRoot() ?: run { done(); return }
        val commentButton = findNode(root) { node ->
            val label = nodeLabel(node)
            label.contains("comment") || label.contains("تعليق") || label.contains("kommentar")
        }
        if (!clickNode(commentButton)) { done(); return }

        handler.postDelayed(openEditor@{
            val editorRoot = currentTikTokRoot() ?: run { done(); return@openEditor }
            val editor = findNode(editorRoot) { node ->
                node.isEditable || node.className?.toString()?.contains("EditText") == true
            }
            if (editor == null) {
                safeBackIfTikTok(); done(); return@openEditor
            }
            val text = comments[commentIndex % comments.size]
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            if (currentTikTokRoot() == null) { done(); return@openEditor }
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
                    commentsSent++
                    lastCommentAt = System.currentTimeMillis()
                    updateOverlayText()
                    handler.postDelayed({ safeBackIfTikTok(); done() }, 350)
                } else {
                    safeBackIfTikTok(); done()
                }
            }, 300)
        }, 650)
    }

    private fun currentTikTokRoot(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return root.takeIf { TikTokScope.isAllowed(it.packageName) }
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

    private fun findNode(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(root)) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findNode(child, predicate)
            if (found != null) return found
        }
        return null
    }

    private fun nodeLabel(node: AccessibilityNodeInfo): String =
        listOfNotNull(node.text, node.contentDescription).joinToString(" ").lowercase().trim()

    private fun safeBackIfTikTok() {
        if (currentTikTokRoot() != null) performGlobalAction(GLOBAL_ACTION_BACK)
    }

    private fun ensureOverlay() {
        if (overlay != null) { updateOverlayText(); return }
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density
        val button = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(
                (12 * density).roundToInt(), (9 * density).roundToInt(),
                (12 * density).roundToInt(), (9 * density).roundToInt(),
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(224, 15, 15, 15))
                setStroke((2 * density).roundToInt(), Color.rgb(37, 244, 238))
            }
            setOnClickListener {
                if (currentTikTokRoot() == null) return@setOnClickListener
                val next = !prefs.getBoolean("enabled", false)
                prefs.edit().putBoolean("enabled", next).apply()
                reloadFromPrefs()
            }
            setOnLongClickListener {
                if (currentTikTokRoot() == null) return@setOnLongClickListener false
                startActivity(Intent(this@YmTikTokAccessibilityService, TikTokAutoActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                true
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
        overlay?.text = if (prefs.getBoolean("enabled", false)) {
            "YM\n● $scrollCount\n💬 $commentsSent"
        } else {
            "YM\n○"
        }
    }

    private fun removeOverlay() {
        val view = overlay ?: return
        runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view) }
        overlay = null
    }
}
