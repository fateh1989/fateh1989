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

    private var overlay: TextView? = null
    private var enabled = false
    private var autoComment = false
    private var smartWatch = true
    private var sessionDurationMs = 60 * 60_000L
    private var sessionEndAt = 0L
    private var fixedIntervalMs = 8_000L
    private var smartFallbackMs = 90_000L
    private var commentEvery = 3
    private var maxCommentsPerSession = 5
    private var commentGapMs = 60_000L
    private var scrollCount = 0
    private var commentIndex = 0
    private var commentsSent = 0
    private var lastCommentAt = 0L
    private var gestureInFlight = false
    private var comments: List<String> = emptyList()

    private var videoStartedAt = 0L
    private var lastVideoProgress = -1f
    private var videoProgressSeen = false
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
        smartWatch = prefs.getBoolean("smart_watch", true)
        sessionDurationMs = prefs.getInt("session_duration_min", 60).coerceIn(1, 1440) * 60_000L
        fixedIntervalMs = prefs.getInt("interval_sec", 8).coerceIn(3, 120) * 1000L
        smartFallbackMs = prefs.getInt("smart_fallback_sec", 90).coerceIn(15, 600) * 1000L
        commentEvery = prefs.getInt("comment_every", 3).coerceIn(1, 100)
        maxCommentsPerSession = prefs.getInt("max_comments_session", 5).coerceIn(1, 50)
        commentGapMs = prefs.getInt("comment_gap_sec", 60).coerceIn(30, 3600) * 1000L
        comments = localPrefs.getString("comment_pool", "").orEmpty()
            .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

        if (enabled && !wasEnabled) {
            sessionEndAt = System.currentTimeMillis() + sessionDurationMs
            prefs.edit().putLong("session_end_at", sessionEndAt).apply()
            scrollCount = 0
            commentIndex = 0
            commentsSent = 0
            lastCommentAt = 0L
            gestureInFlight = false
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
            gestureInFlight = false
            clearVideoTracking()
        }

        handler.removeCallbacks(loop)
        updateOverlayText()
        if (enabled && ensureSessionActive()) handler.postDelayed(loop, 250)
    }

    private fun tick() {
        enabled = prefs.getBoolean("enabled", false)
        autoComment = prefs.getBoolean("auto_comment", false)
        smartWatch = prefs.getBoolean("smart_watch", true)
        if (!enabled || !ensureSessionActive()) return
        if (gestureInFlight) {
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

        if (!smartWatch) {
            val elapsed = System.currentTimeMillis() - videoStartedAt
            if (elapsed >= fixedIntervalMs) advanceCurrentVideo()
            else handler.postDelayed(loop, (fixedIntervalMs - elapsed).coerceAtMost(500L))
            return
        }

        val now = System.currentTimeMillis()
        val progress = findVideoProgress(root)
        if (progress != null) {
            videoProgressSeen = true
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

        if (!videoProgressSeen && now - videoStartedAt >= smartFallbackMs) {
            advanceCurrentVideo()
            return
        }

        handler.postDelayed(loop, 400)
    }

    private fun advanceCurrentVideo() {
        if (!ensureSessionActive()) return
        val now = System.currentTimeMillis()
        val commentDueByVideo = (scrollCount + 1) % commentEvery == 0
        val commentDueByTime = now - lastCommentAt >= commentGapMs
        val withinSessionLimit = commentsSent < maxCommentsPerSession
        val shouldComment = autoComment && comments.isNotEmpty() && commentDueByVideo &&
            commentDueByTime && withinSessionLimit

        if (shouldComment) attemptComment { handler.postDelayed({ swipeAndContinue() }, 500) }
        else swipeAndContinue()
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
                .putLong("session_end_at", 0L)
                .apply()
            reloadFromPrefs()
            Toast.makeText(this, "بدأ YM AUTOPILOT", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopAutopilot(showToast: Boolean) {
        enabled = false
        gestureInFlight = false
        sessionEndAt = 0L
        clearVideoTracking()
        prefs.edit()
            .putBoolean("enabled", false)
            .putBoolean("pending_launch", false)
            .putLong("session_end_at", 0L)
            .apply()
        handler.removeCallbacks(loop)
        updateOverlayText()
        if (showToast) {
            Toast.makeText(this, "تم إيقاف YM AUTOPILOT", Toast.LENGTH_SHORT).show()
        }
    }

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

        if (currentTikTokRoot() == null || !ensureSessionActive()) {
            handler.postDelayed(loop, 500)
            return
        }

        gestureInFlight = true
        val accepted = dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    gestureInFlight = false
                    if (!prefs.getBoolean("enabled", false) || currentTikTokRoot() == null || !ensureSessionActive()) {
                        return
                    }
                    scrollCount++
                    resetVideoTracking()
                    updateOverlayText()
                    handler.postDelayed(loop, 800)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    gestureInFlight = false
                    if (ensureSessionActive()) handler.postDelayed(loop, 800)
                }
            },
            null,
        )

        if (!accepted) {
            gestureInFlight = false
            if (ensureSessionActive()) handler.postDelayed(loop, 800)
        }
    }

    private fun attemptComment(done: () -> Unit) {
        if (!ensureSessionActive()) return
        if (commentsSent >= maxCommentsPerSession) { done(); return }
        if (System.currentTimeMillis() - lastCommentAt < commentGapMs) { done(); return }

        val root = currentTikTokRoot() ?: run { done(); return }
        val commentButton = findNode(root) { node ->
            val label = nodeLabel(node)
            label.contains("comment") || label.contains("تعليق") || label.contains("kommentar")
        }
        if (!clickNode(commentButton)) { done(); return }

        handler.postDelayed(openEditor@{
            if (!ensureSessionActive()) return@openEditor
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
            if (currentTikTokRoot() == null || !ensureSessionActive()) return@openEditor
            editor.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

            handler.postDelayed(sendComment@{
                if (!ensureSessionActive()) return@sendComment
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

    private fun findVideoProgress(root: AccessibilityNodeInfo): Float? {
        var best: Float? = null

        fun visit(node: AccessibilityNodeInfo) {
            val range = node.rangeInfo
            if (range != null && range.max > range.min) {
                val className = node.className?.toString()?.lowercase().orEmpty()
                val label = nodeLabel(node)
                val looksLikePlayback = className.contains("seekbar") ||
                    className.contains("progressbar") ||
                    label.contains("progress") ||
                    label.contains("playback") ||
                    label.contains("duration") ||
                    label.contains("position")

                if (looksLikePlayback) {
                    val fraction = ((range.current - range.min) / (range.max - range.min)).coerceIn(0f, 1f)
                    if (best == null || fraction > best!!) best = fraction
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(::visit)
            }
        }

        visit(root)
        return best
    }

    private fun resetVideoTracking() {
        videoStartedAt = System.currentTimeMillis()
        lastVideoProgress = -1f
        videoProgressSeen = false
        videoProgressAdvanced = false
    }

    private fun clearVideoTracking() {
        videoStartedAt = 0L
        lastVideoProgress = -1f
        videoProgressSeen = false
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
            if (currentTikTokRoot() == null || !ensureSessionActive()) return false
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
        if (currentTikTokRoot() != null && ensureSessionActive()) performGlobalAction(GLOBAL_ACTION_BACK)
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
                toggleAutopilotFromOverlay()
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
        overlay?.text = if (prefs.getBoolean("enabled", false) && sessionEndAt > 0L) {
            val remainingMs = (sessionEndAt - System.currentTimeMillis()).coerceAtLeast(0L)
            val remainingMinutes = (remainingMs + 59_999L) / 60_000L
            val watchMark = if (prefs.getBoolean("smart_watch", true)) "SMART" else "TIMER"
            "■ STOP\n$watchMark · ${remainingMinutes}m\n↕ $scrollCount  💬 $commentsSent"
        } else {
            "▶ AUTO\nYM"
        }
    }

    private fun removeOverlay() {
        val view = overlay ?: return
        runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view) }
        overlay = null
    }
}
