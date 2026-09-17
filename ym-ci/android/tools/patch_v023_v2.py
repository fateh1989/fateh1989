from pathlib import Path

SERVICE = Path("ym-ci/android/app/src/main/java/com/ym/lite/automation/YmTikTokAccessibilityService.kt")
ACTIVITY = Path("ym-ci/android/app/src/main/java/com/ym/lite/TikTokAutoActivity.kt")

service = r'''package com.ym.lite.automation

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
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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

    private var overlayContainer: LinearLayout? = null
    private var autoButton: TextView? = null
    private var commentButton: TextView? = null

    private var enabled = false
    private var commentModeActive = false
    private var smartWatch = true
    private var sessionDurationMs = 60 * 60_000L
    private var sessionEndAt = 0L
    private var fixedIntervalMs = 4_000L
    private var smartFallbackMs = 4_000L
    private var scrollCount = 0
    private var commentIndex = 0
    private var commentsSent = 0
    private var gestureInFlight = false
    private var commentFlowInFlight = false
    private var comments: List<String> = emptyList()

    private var videoStartedAt = 0L
    private var lastVideoProgress = -1f
    private var videoProgressAdvanced = false

    private val loop = object : Runnable { override fun run() = tick() }
    private val scopeWatch = object : Runnable {
        override fun run() {
            if (currentTikTokRoot() == null) removeOverlay() else ensureOverlay()
            if (enabled) ensureSessionActive()
            updateOverlayText()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        prefs.edit().putBoolean("comment_mode_active", false).apply()
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
        prefs.edit().putBoolean("comment_mode_active", false).apply()
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun reloadFromPrefs() {
        val wasEnabled = enabled
        enabled = prefs.getBoolean("enabled", false)
        commentModeActive = prefs.getBoolean("comment_mode_active", false)
        smartWatch = prefs.getBoolean("smart_watch", true)
        sessionDurationMs = prefs.getInt("session_duration_min", 60).coerceIn(1, 1440) * 60_000L
        fixedIntervalMs = prefs.getInt("interval_sec", 4).coerceIn(3, 30) * 1000L
        smartFallbackMs = prefs.getInt("smart_fallback_sec", 4).coerceIn(3, 30) * 1000L
        comments = localPrefs.getString("comment_pool", "").orEmpty()
            .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        commentIndex = localPrefs.getInt("comment_wheel_index", 0).coerceAtLeast(0)

        if (enabled && !wasEnabled) {
            commentModeActive = false
            prefs.edit().putBoolean("comment_mode_active", false).apply()
            sessionEndAt = System.currentTimeMillis() + sessionDurationMs
            prefs.edit().putLong("session_end_at", sessionEndAt).apply()
            scrollCount = 0
            commentsSent = 0
            gestureInFlight = false
            commentFlowInFlight = false
            resetVideoTracking()
        } else if (enabled) {
            sessionEndAt = prefs.getLong("session_end_at", 0L)
            if (sessionEndAt <= System.currentTimeMillis()) {
                sessionEndAt = System.currentTimeMillis() + sessionDurationMs
                prefs.edit().putLong("session_end_at", sessionEndAt).apply()
            }
            if (videoStartedAt == 0L) resetVideoTracking()
        } else {
            sessionEndAt = 0L
            commentModeActive = false
            gestureInFlight = false
            commentFlowInFlight = false
            clearVideoTracking()
        }

        handler.removeCallbacks(loop)
        updateOverlayText()
        if (enabled && ensureSessionActive()) handler.postDelayed(loop, 250)
    }

    private fun tick() {
        enabled = prefs.getBoolean("enabled", false)
        commentModeActive = prefs.getBoolean("comment_mode_active", false)
        smartWatch = prefs.getBoolean("smart_watch", true)
        if (!enabled || !ensureSessionActive()) return

        if (gestureInFlight || commentFlowInFlight) {
            handler.postDelayed(loop, 250)
            return
        }

        val root = currentTikTokRoot()
        if (root == null) {
            removeOverlay()
            handler.postDelayed(loop, 500)
            return
        }

        if (videoStartedAt == 0L) resetVideoTracking()
        val elapsed = System.currentTimeMillis() - videoStartedAt

        if (!smartWatch) {
            if (elapsed >= fixedIntervalMs) advanceCurrentVideo()
            else handler.postDelayed(loop, (fixedIntervalMs - elapsed).coerceAtMost(350L))
            return
        }

        val progress = findVideoProgress(root)
        if (progress != null) {
            if (lastVideoProgress >= 0f && progress > lastVideoProgress + 0.003f) {
                videoProgressAdvanced = true
            }
            val replayed = videoProgressAdvanced && lastVideoProgress >= 0.90f && progress <= 0.10f
            val reachedEnd = progress >= 0.985f && (videoProgressAdvanced || progress >= 0.997f)
            lastVideoProgress = progress
            if (replayed || reachedEnd) {
                advanceCurrentVideo()
                return
            }
        }

        if (elapsed >= smartFallbackMs) {
            advanceCurrentVideo()
            return
        }

        handler.postDelayed(loop, 250)
    }

    private fun advanceCurrentVideo() {
        if (!ensureSessionActive() || gestureInFlight || commentFlowInFlight) return
        val shouldComment = commentModeActive && comments.isNotEmpty()
        if (!shouldComment) {
            swipeAndContinue()
            return
        }

        commentFlowInFlight = true
        attemptComment {
            commentFlowInFlight = false
            handler.postDelayed({ closeCommentUiAndSwipe(2) }, 250)
        }
    }

    private fun ensureSessionActive(): Boolean {
        if (!enabled) return false
        if (sessionEndAt <= 0L) {
            sessionEndAt = System.currentTimeMillis() + sessionDurationMs
            prefs.edit().putLong("session_end_at", sessionEndAt).apply()
        }
        if (System.currentTimeMillis() < sessionEndAt) return true
        stopAutopilot(showToast = true)
        return false
    }

    private fun toggleAutopilotFromOverlay() {
        if (currentTikTokRoot() == null) return
        if (prefs.getBoolean("enabled", false)) {
            stopAutopilot(showToast = true)
        } else {
            prefs.edit()
                .putBoolean("enabled", true)
                .putBoolean("pending_launch", false)
                .putBoolean("comment_mode_active", false)
                .putLong("session_end_at", 0L)
                .apply()
            reloadFromPrefs()
            Toast.makeText(this, "بدأ YM AUTOPILOT", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleCommentsFromOverlay() {
        if (currentTikTokRoot() == null) return
        val turningOn = !prefs.getBoolean("comment_mode_active", false)
        if (turningOn && !prefs.getBoolean("enabled", false)) {
            Toast.makeText(this, "شغّل AUTO أولاً ثم فعّل التعليقات", Toast.LENGTH_SHORT).show()
            return
        }
        if (turningOn && comments.isEmpty()) {
            Toast.makeText(this, "دولاب التعليقات فارغ — أضف التعليقات من YM", Toast.LENGTH_LONG).show()
            return
        }

        prefs.edit().putBoolean("comment_mode_active", turningOn).apply()
        commentModeActive = turningOn
        updateOverlayText()
        Toast.makeText(
            this,
            if (turningOn) "التعليقات التلقائية تعمل" else "تم إيقاف التعليقات التلقائية",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun stopAutopilot(showToast: Boolean) {
        enabled = false
        commentModeActive = false
        gestureInFlight = false
        commentFlowInFlight = false
        sessionEndAt = 0L
        clearVideoTracking()
        prefs.edit()
            .putBoolean("enabled", false)
            .putBoolean("pending_launch", false)
            .putBoolean("comment_mode_active", false)
            .putLong("session_end_at", 0L)
            .apply()
        handler.removeCallbacks(loop)
        updateOverlayText()
        if (showToast) Toast.makeText(this, "تم إيقاف YM AUTOPILOT", Toast.LENGTH_SHORT).show()
    }

    private fun closeCommentUiAndSwipe(backAttempts: Int) {
        if (!prefs.getBoolean("enabled", false) || !ensureSessionActive()) return
        val root = currentTikTokRoot()
        if (root == null) {
            handler.postDelayed(loop, 500)
            return
        }
        if (commentComposerVisible(root) && backAttempts > 0) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            handler.postDelayed({ closeCommentUiAndSwipe(backAttempts - 1) }, 300)
            return
        }
        swipeAndContinue()
    }

    private fun commentComposerVisible(root: AccessibilityNodeInfo): Boolean =
        findNode(root) { node ->
            if (node.isEditable || node.className?.toString()?.contains("EditText") == true) return@findNode true
            val label = nodeLabel(node)
            label.contains("add comment") ||
                label.contains("write a comment") ||
                label.contains("إضافة تعليق") ||
                label.contains("اكتب تعليق") ||
                label.contains("lägg till kommentar")
        } != null

    private fun swipeAndContinue() {
        if (!prefs.getBoolean("enabled", false) || !ensureSessionActive()) return
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

        gestureInFlight = true
        val accepted = dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    gestureInFlight = false
                    if (!prefs.getBoolean("enabled", false) || currentTikTokRoot() == null || !ensureSessionActive()) return
                    scrollCount++
                    resetVideoTracking()
                    updateOverlayText()
                    handler.postDelayed(loop, 500)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    gestureInFlight = false
                    if (prefs.getBoolean("enabled", false) && ensureSessionActive()) handler.postDelayed(loop, 500)
                }
            },
            null,
        )
        if (!accepted) {
            gestureInFlight = false
            if (ensureSessionActive()) handler.postDelayed(loop, 500)
        }
    }

    private fun attemptComment(done: () -> Unit) {
        if (!ensureSessionActive()) { done(); return }
        if (!prefs.getBoolean("comment_mode_active", false)) { done(); return }
        if (comments.isEmpty()) { done(); return }

        val root = currentTikTokRoot() ?: run { done(); return }
        val trigger = findNode(root) { node ->
            val label = nodeLabel(node)
            label.contains("comment") || label.contains("تعليق") || label.contains("kommentar")
        }
        if (!clickNode(trigger)) { done(); return }

        handler.postDelayed(openEditor@{
            if (!ensureSessionActive() || !prefs.getBoolean("comment_mode_active", false)) {
                done(); return@openEditor
            }
            val editorRoot = currentTikTokRoot() ?: run { done(); return@openEditor }
            val editor = findNode(editorRoot) { node ->
                node.isEditable || node.className?.toString()?.contains("EditText") == true
            }
            if (editor == null) { done(); return@openEditor }

            val text = comments[commentIndex % comments.size]
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            editor.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

            handler.postDelayed(sendComment@{
                if (!ensureSessionActive() || !prefs.getBoolean("comment_mode_active", false)) {
                    done(); return@sendComment
                }
                val sendRoot = currentTikTokRoot() ?: run { done(); return@sendComment }
                val send = findNode(sendRoot) { node ->
                    val label = nodeLabel(node)
                    label == "send" || label.contains("post") || label.contains("إرسال") ||
                        label.contains("نشر") || label.contains("skicka")
                }
                if (clickNode(send)) {
                    commentIndex = (commentIndex + 1) % comments.size
                    localPrefs.edit().putInt("comment_wheel_index", commentIndex).apply()
                    commentsSent++
                    updateOverlayText()
                }
                done()
            }, 350)
        }, 650)
    }

    private fun findVideoProgress(root: AccessibilityNodeInfo): Float? {
        var best: Float? = null
        fun visit(node: AccessibilityNodeInfo) {
            val range = node.rangeInfo
            if (range != null && range.max > range.min) {
                val className = node.className?.toString()?.lowercase().orEmpty()
                val label = nodeLabel(node)
                val looksLikePlayback = className.contains("seekbar") ||
                    className.contains("progressbar") || label.contains("progress") ||
                    label.contains("playback") || label.contains("duration") || label.contains("position")
                if (looksLikePlayback) {
                    val fraction = ((range.current - range.min) / (range.max - range.min)).coerceIn(0f, 1f)
                    if (best == null || fraction > best!!) best = fraction
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
        }
        visit(root)
        return best
    }

    private fun resetVideoTracking() {
        videoStartedAt = System.currentTimeMillis()
        lastVideoProgress = -1f
        videoProgressAdvanced = false
    }

    private fun clearVideoTracking() {
        videoStartedAt = 0L
        lastVideoProgress = -1f
        videoProgressAdvanced = false
    }

    private fun currentTikTokRoot(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return root.takeIf { TikTokScope.isAllowed(it.packageName) }
    }

    private fun clickNode(node: AccessibilityNodeInfo?): Boolean {
        if (currentTikTokRoot() == null || !ensureSessionActive()) return false
        var current = node ?: return false
        repeat(5) {
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

    private fun ensureOverlay() {
        if (overlayContainer != null) { updateOverlayText(); return }
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density

        fun bubble(strokeColor: Int, click: () -> Unit): TextView = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 12f
            minWidth = (74 * density).roundToInt()
            setPadding((10 * density).roundToInt(), (8 * density).roundToInt(), (10 * density).roundToInt(), (8 * density).roundToInt())
            background = GradientDrawable().apply {
                cornerRadius = 22 * density
                setColor(Color.argb(228, 15, 15, 15))
                setStroke((2 * density).roundToInt(), strokeColor)
            }
            setOnClickListener { click() }
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val auto = bubble(Color.rgb(37, 244, 238)) { toggleAutopilotFromOverlay() }
        auto.setOnLongClickListener {
            startActivity(Intent(this@YmTikTokAccessibilityService, TikTokAutoActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            true
        }
        val commentsToggle = bubble(Color.rgb(255, 75, 145)) { toggleCommentsFromOverlay() }
        container.addView(auto)
        container.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, (7 * density).roundToInt()) })
        container.addView(commentsToggle)

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
        wm.addView(container, lp)
        overlayContainer = container
        autoButton = auto
        commentButton = commentsToggle
        updateOverlayText()
    }

    private fun updateOverlayText() {
        val running = prefs.getBoolean("enabled", false) && sessionEndAt > 0L
        val commentsOn = prefs.getBoolean("comment_mode_active", false)
        autoButton?.text = if (running) {
            val remainingMs = (sessionEndAt - System.currentTimeMillis()).coerceAtLeast(0L)
            val remainingMinutes = (remainingMs + 59_999L) / 60_000L
            "■ STOP\n${remainingMinutes}m\n↕ $scrollCount"
        } else {
            "▶ AUTO\nYM"
        }
        commentButton?.text = if (commentsOn) "💬 ON\n$commentsSent" else "💬 OFF"
        commentButton?.alpha = if (commentsOn) 1f else 0.72f
    }

    private fun removeOverlay() {
        val view = overlayContainer ?: return
        runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view) }
        overlayContainer = null
        autoButton = null
        commentButton = null
    }
}
'''

SERVICE.write_text(service)

a = ACTIVITY.read_text()
a = a.replace(
    'text = "مرور سريع داخل TikTok: ثوانٍ قليلة لكل فيديو، ثم انتقال تلقائي وتعليقات من دولاب الدعم."',
    'text = "مرور تلقائي داخل TikTok. التعليقات مستقلة: شغّل أو أوقف زر 💬 يدويًا وقتما تريد."',
)
a = a.replace('text = "تعليقات دعم تلقائية"', 'text = "التعليقات تعمل يدويًا من زر 💬 داخل TikTok"')
a = a.replace('autoComment.isChecked = prefs.getBoolean("auto_comment", false)', 'autoComment.isChecked = false')
a = a.replace('.putBoolean("auto_comment", autoComment.isChecked)', '.putBoolean("auto_comment", false)')
a = a.replace(
    '.putBoolean("pending_launch", false)\n                .putLong("session_end_at", 0L)',
    '.putBoolean("pending_launch", false)\n                .putBoolean("comment_mode_active", false)\n                .putLong("session_end_at", 0L)',
)
a = a.replace('val commentState = prefs.getBoolean("auto_comment", false)', 'val commentState = prefs.getBoolean("comment_mode_active", false)')
a = a.replace(
    '            append("\\nدولاب الدعم: ")\n            append(if (commentState) "مفعّل ($count تعليق)" else "متوقف")\n            if (commentState) append(" — حد الجلسة $maxComments — فاصل ${gap}ث")',
    '            append("\\nدولاب الدعم: $count تعليق — ")\n            append(if (commentState) "💬 يعمل الآن" else "💬 متوقف — شغّله من داخل TikTok")',
)
ACTIVITY.write_text(a)

print("YM v0.23 v2 patch applied")
