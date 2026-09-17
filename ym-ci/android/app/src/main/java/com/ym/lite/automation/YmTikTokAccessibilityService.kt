package com.ym.lite.automation

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import kotlin.math.abs
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
    private var comments: List<String> = emptyList()
    private var commentIndex = 0
    private var commentInFlight = false
    private var pendingCommentText = ""
    private var lastTikTokSeenAt = 0L
    private var lastFeedScrollAt = 0L

    private val manualScrollDebounce = Runnable {
        if (!enabled || commentInFlight || !isTikTokActiveStrict()) return@Runnable
        val root = currentTikTokRoot() ?: return@Runnable
        if (isCommentPanelOpen(root)) return@Runnable
        startComment("manual_video")
    }

    private val scopeWatch = object : Runnable {
        override fun run() {
            if (shouldShowOverlay()) ensureOverlay() else removeOverlay()
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
        val packageName = event?.packageName
        if (!TikTokScope.isAllowed(packageName)) {
            if (!shouldShowOverlay()) removeOverlay()
            return
        }

        lastTikTokSeenAt = SystemClock.uptimeMillis()
        ensureOverlay()

        if (!enabled || commentInFlight || event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                val now = SystemClock.uptimeMillis()
                if (now - lastFeedScrollAt < 180L) {
                    handler.removeCallbacks(manualScrollDebounce)
                    handler.postDelayed(manualScrollDebounce, 650L)
                    return
                }
                lastFeedScrollAt = now
                handler.removeCallbacks(manualScrollDebounce)
                handler.postDelayed(manualScrollDebounce, 650L)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // When TikTok returns to its feed, do not force a second comment.
                // The first video is handled when YM is explicitly switched ON.
            }
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
        comments = localPrefs.getString("comment_pool", "").orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
            .ifEmpty { YmCommentDefaults.all }
        commentIndex = localPrefs.getInt("comment_index", 0).coerceAtLeast(0)

        handler.removeCallbacks(manualScrollDebounce)
        updateOverlayAppearance()

        if (enabled && !commentInFlight) {
            handler.postDelayed({
                if (!this.enabled || commentInFlight || !isTikTokActiveStrict()) return@postDelayed
                val root = currentTikTokRoot() ?: return@postDelayed
                if (!isCommentPanelOpen(root)) startComment("first_video")
            }, 800L)
        }
    }

    private fun startComment(reason: String) {
        if (!enabled || commentInFlight || comments.isEmpty() || !isTikTokActiveStrict()) return
        val root = currentTikTokRoot() ?: return
        if (isCommentPanelOpen(root)) return

        commentInFlight = true
        pendingCommentText = comments[commentIndex % comments.size]
        recordStage("comment_start", "بدء تعليق تلقائي على الفيديو الحالي: $reason")
        findCommentButtonAndOpen(attempt = 0)
    }

    private fun findCommentButtonAndOpen(attempt: Int) {
        if (!enabled || !isTikTokActiveStrict()) {
            finishCommentFailure("comment_scope_lost", "TikTok لم يعد النافذة النشطة")
            return
        }

        val root = currentTikTokRoot() ?: run {
            finishCommentFailure("comment_root_missing", "نافذة TikTok غير متاحة")
            return
        }

        recordStage("comment_search_button", "البحث عن زر التعليقات في فيديو TikTok الحالي")
        val button = findBestNode(root, ::commentButtonScore, minScore = 8)
        if (clickNode(button)) {
            recordStage("comment_opened", "تم فتح لوحة تعليقات TikTok")
            handler.postDelayed({ findEditorAndWrite(attempt = 0) }, 420L)
            return
        }

        if (attempt < 4) {
            handler.postDelayed({ findCommentButtonAndOpen(attempt + 1) }, 250L)
        } else {
            finishCommentFailure(
                "comment_button_missing",
                "لم أجد زر التعليقات في واجهة TikTok | ${probeSummary(root)}",
            )
        }
    }

    private fun findEditorAndWrite(attempt: Int) {
        if (!enabled || !isTikTokActiveStrict()) {
            finishCommentFailure("comment_scope_lost", "TikTok لم يعد النافذة النشطة")
            return
        }

        val root = currentTikTokRoot() ?: run {
            finishCommentFailure("comment_root_lost", "اختفت نافذة TikTok أثناء فتح التعليقات")
            return
        }

        val editor = findBestNode(root, ::commentEditorScore, minScore = 20)
        if (editor == null) {
            if (attempt < 8) {
                recordStage("comment_wait_editor", "انتظار حقل كتابة تعليق TikTok ${attempt + 1}/9")
                handler.postDelayed({ findEditorAndWrite(attempt + 1) }, 220L)
            } else {
                safeBackIfTikTok()
                finishCommentFailure(
                    "comment_editor_missing",
                    "لم يظهر حقل كتابة التعليق | ${probeSummary(root)}",
                )
            }
            return
        }

        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                pendingCommentText,
            )
        }

        editor.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val wrote = editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!wrote) {
            safeBackIfTikTok()
            finishCommentFailure(
                "comment_write_failed",
                "TikTok رفض إدخال النص في حقل التعليق | ${nodeSignature(editor)}",
            )
            return
        }

        recordStage("comment_text_written", "تم وضع تعليق من دولاب YM في حقل TikTok")
        handler.postDelayed({ findSendAndSubmit(editor, attempt = 0) }, 260L)
    }

    private fun findSendAndSubmit(editor: AccessibilityNodeInfo, attempt: Int) {
        if (!enabled || !isTikTokActiveStrict()) {
            finishCommentFailure("comment_scope_lost", "TikTok لم يعد النافذة النشطة")
            return
        }

        val root = currentTikTokRoot() ?: run {
            finishCommentFailure("comment_root_before_send", "اختفت نافذة TikTok قبل الإرسال")
            return
        }

        val send = findSendNearEditor(editor) ?: findBestNode(root, ::commentSendScore, minScore = 8)
        if (send == null) {
            if (attempt < 8) {
                recordStage("comment_wait_send", "انتظار زر نشر التعليق ${attempt + 1}/9")
                handler.postDelayed({ findSendAndSubmit(editor, attempt + 1) }, 220L)
            } else {
                safeBackIfTikTok()
                finishCommentFailure(
                    "comment_send_missing",
                    "لم يظهر زر نشر التعليق | ${probeSummary(root)}",
                )
            }
            return
        }

        if (!clickNode(send)) {
            safeBackIfTikTok()
            finishCommentFailure(
                "comment_send_click_failed",
                "تعذر ضغط زر نشر التعليق | ${nodeSignature(send)}",
            )
            return
        }

        recordStage("comment_send_clicked", "تم ضغط زر نشر تعليق TikTok")
        handler.postDelayed({ verifyCommentSubmitted(attempt = 0) }, 300L)
    }

    private fun verifyCommentSubmitted(attempt: Int) {
        if (!isTikTokActiveStrict()) {
            finishCommentSuccess(closePanel = false)
            return
        }

        val root = currentTikTokRoot()
        val editor = root?.let { findBestNode(it, ::commentEditorScore, minScore = 20) }
        if (editor == null) {
            finishCommentSuccess(closePanel = false)
            return
        }

        val currentText = editor.text?.toString().orEmpty().trim()
        if (currentText.isBlank() || currentText != pendingCommentText) {
            finishCommentSuccess(closePanel = true)
            return
        }

        if (attempt < 6) {
            recordStage("comment_verify_send", "التحقق من نشر التعليق ${attempt + 1}/7")
            handler.postDelayed({ verifyCommentSubmitted(attempt + 1) }, 240L)
        } else {
            safeBackIfTikTok()
            finishCommentFailure(
                "comment_send_unconfirmed",
                "تم ضغط النشر لكن TikTok أبقى النص داخل الحقل",
            )
        }
    }

    private fun finishCommentSuccess(closePanel: Boolean) {
        val nextIndex = commentIndex + 1
        commentIndex = nextIndex
        localPrefs.edit().putInt("comment_index", nextIndex).apply()
        bumpStat("stat_comment_ok")
        recordStage("comment_ok", "تم نشر تعليق من دولاب YM بنجاح")
        pendingCommentText = ""
        updateOverlayAppearance()

        handler.postDelayed({
            if (closePanel) safeBackIfTikTok()
            handler.postDelayed({ commentInFlight = false }, 380L)
        }, 260L)
    }

    private fun finishCommentFailure(code: String, reason: String) {
        pendingCommentText = ""
        bumpStat("stat_comment_fail")
        recordStage(code, reason)
        updateOverlayAppearance()
        handler.postDelayed({ commentInFlight = false }, 450L)
    }

    private fun isCommentPanelOpen(root: AccessibilityNodeInfo): Boolean {
        return findBestNode(root, ::commentEditorScore, minScore = 20) != null
    }

    private fun commentButtonScore(node: AccessibilityNodeInfo): Int {
        if (!node.isEnabled || node.isEditable) return 0
        val token = nodeToken(node)
        var score = 0

        if (token.contains("read or add comments")) score += 45
        if (token.contains("view comments") || token.contains("open comments")) score += 36
        if (containsAny(token, "comments", "comment", "kommentarer", "kommentar", "تعليقات", "التعليقات", "تعليق")) score += 14
        if (node.viewIdResourceName?.contains("comment", ignoreCase = true) == true) score += 18
        if (node.contentDescription?.toString()?.contains("comment", ignoreCase = true) == true) score += 14
        if (node.isClickable) score += 5
        if (isButtonLike(node)) score += 3

        if (token.contains("add comment") || token.contains("write a comment") || token.contains("comment here")) score -= 24

        if (score < 8 && node.isClickable && isButtonLike(node)) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val dm = resources.displayMetrics
            val onRightRail = bounds.centerX() > (dm.widthPixels * 0.72f)
            val middleVertical = bounds.centerY() in (dm.heightPixels * 0.30f).roundToInt()..(dm.heightPixels * 0.82f).roundToInt()
            val shortLabel = nodeLabel(node)
            if (onRightRail && middleVertical && shortLabel.matches(Regex("^[0-9.,kKmM+ ]{1,12}$"))) {
                score += 4
            }
        }

        return score
    }

    private fun commentEditorScore(node: AccessibilityNodeInfo): Int {
        if (!node.isEnabled) return 0
        val token = nodeToken(node)
        var score = 0
        if (node.isEditable) score += 30
        if (node.className?.toString()?.contains("EditText", ignoreCase = true) == true) score += 20
        if (supportsAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT)) score += 14
        if (containsAny(
                token,
                "add comment",
                "write a comment",
                "comment here",
                "kommentera",
                "skriv en kommentar",
                "أضف تعليق",
                "اكتب تعليق",
            )) score += 18
        if (node.isFocusable) score += 2
        return score
    }

    private fun commentSendScore(node: AccessibilityNodeInfo): Int {
        if (!node.isEnabled || node.isEditable) return 0
        val token = nodeToken(node)
        val label = nodeLabel(node)
        var score = 0

        if (containsAny(token, "send_comment", "post_comment", "submit_comment", "comment_send", "comment_post")) score += 40
        if (label in setOf("send", "post", "publish", "skicka", "publicera", "إرسال", "نشر")) score += 30
        if (containsAny(token, "send comment", "post comment", "publish comment", "send", "post", "skicka", "publicera", "إرسال", "نشر")) score += 10
        if (node.viewIdResourceName?.let { containsAny(it.lowercase(), "send", "post", "submit") } == true) score += 14
        if (node.isClickable) score += 5
        if (isButtonLike(node)) score += 3
        return score
    }

    private fun findSendNearEditor(editor: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var anchor: AccessibilityNodeInfo? = editor.parent
        repeat(6) {
            val current = anchor ?: return@repeat
            val candidate = findBestNode(current, ::commentSendScore, minScore = 8)
            if (candidate != null && candidate !== editor) return candidate
            anchor = current.parent
        }
        return null
    }

    private fun findBestNode(
        root: AccessibilityNodeInfo,
        scorer: (AccessibilityNodeInfo) -> Int,
        minScore: Int,
    ): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestScore = minScore - 1
        walkNodes(root, limit = 700) { node ->
            val score = scorer(node)
            if (score > bestScore) {
                best = node
                bestScore = score
            }
        }
        return best
    }

    private fun walkNodes(
        root: AccessibilityNodeInfo,
        limit: Int,
        block: (AccessibilityNodeInfo) -> Unit,
    ) {
        var visited = 0
        fun visit(node: AccessibilityNodeInfo) {
            if (visited >= limit) return
            visited++
            block(node)
            for (i in 0 until node.childCount) {
                if (visited >= limit) return
                node.getChild(i)?.let(::visit)
            }
        }
        visit(root)
    }

    private fun supportsAction(node: AccessibilityNodeInfo, actionId: Int): Boolean {
        return node.actionList.any { it.id == actionId }
    }

    private fun isButtonLike(node: AccessibilityNodeInfo): Boolean {
        val name = node.className?.toString().orEmpty().lowercase()
        return name.contains("button") || name.contains("image")
    }

    private fun containsAny(text: String, vararg values: String): Boolean {
        return values.any { text.contains(it, ignoreCase = true) }
    }

    private fun probeSummary(root: AccessibilityNodeInfo): String {
        val hits = mutableListOf<String>()
        walkNodes(root, limit = 280) { node ->
            if (hits.size >= 8) return@walkNodes
            val token = nodeToken(node)
            if (
                containsAny(token, "comment", "kommentar", "تعليق", "send", "post", "skicka", "نشر", "إرسال") ||
                node.isEditable
            ) {
                hits += nodeSignature(node)
            }
        }
        return if (hits.isEmpty()) "لا توجد عقد تعليق ظاهرة" else hits.joinToString(" || ")
    }

    private fun nodeSignature(node: AccessibilityNodeInfo): String {
        val text = nodeToken(node).replace('\n', ' ').take(110)
        return "${node.className?.toString()?.substringAfterLast('.').orEmpty()}:$text"
    }

    private fun currentTikTokRoot(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return root.takeIf { TikTokScope.isAllowed(it.packageName) }
    }

    private fun isTikTokActiveStrict(): Boolean {
        val active = currentTikTokRoot() != null
        if (active) lastTikTokSeenAt = SystemClock.uptimeMillis()
        return active
    }

    private fun shouldShowOverlay(): Boolean {
        if (isTikTokActiveStrict()) return true
        return SystemClock.uptimeMillis() - lastTikTokSeenAt < 800L
    }

    private fun clickNode(node: AccessibilityNodeInfo?): Boolean {
        if (!isTikTokActiveStrict()) return false
        var current = node ?: return false
        repeat(8) {
            if (!isTikTokActiveStrict()) return false
            if (
                current.isEnabled &&
                supportsAction(current, AccessibilityNodeInfo.ACTION_CLICK) &&
                current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ) return true

            if (current.isClickable && current.isEnabled && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            current = current.parent ?: return false
        }
        return false
    }

    private fun nodeLabel(node: AccessibilityNodeInfo): String {
        return listOfNotNull(node.text, node.contentDescription, node.hintText)
            .joinToString(" ")
            .lowercase()
            .trim()
    }

    private fun nodeToken(node: AccessibilityNodeInfo): String {
        val actions = node.actionList.mapNotNull { it.label?.toString() }.joinToString(" ")
        return listOfNotNull(
            node.text,
            node.contentDescription,
            node.hintText,
            node.viewIdResourceName,
            node.className,
            actions,
        ).joinToString(" ")
            .lowercase()
            .trim()
    }

    private fun safeBackIfTikTok() {
        if (isTikTokActiveStrict()) performGlobalAction(GLOBAL_ACTION_BACK)
    }

    private fun ensureOverlay() {
        if (overlay != null) {
            updateOverlayAppearance()
            return
        }

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dm = resources.displayMetrics
        val density = dm.density
        val size = (72 * density).roundToInt()
        val margin = (12 * density).roundToInt()
        val maxX = (dm.widthPixels - size).coerceAtLeast(0)
        val maxY = (dm.heightPixels - size).coerceAtLeast(0)
        val startX = localPrefs.getInt("overlay_x", maxX - margin).coerceIn(0, maxX)
        val startY = localPrefs.getInt("overlay_y", (dm.heightPixels - size) / 2).coerceIn(0, maxY)

        val lp = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = startX
            y = startY
        }

        val button = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 17f
            maxLines = 3
            isClickable = true
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnClickListener {
                if (!isTikTokActiveStrict()) return@setOnClickListener
                val next = !prefs.getBoolean("enabled", false)
                prefs.edit().putBoolean("enabled", next).apply()
                recordStage(
                    if (next) "ym_on" else "ym_off",
                    if (next) "YM التعليق التلقائي يعمل" else "YM متوقف",
                )
                reloadFromPrefs()
            }
        }

        var downRawX = 0f
        var downRawY = 0f
        var downX = 0
        var downY = 0
        var dragged = false
        val dragThreshold = 8 * density

        button.setOnTouchListener { view, event ->
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
                        runCatching { wm.updateViewLayout(view, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragged) {
                        localPrefs.edit()
                            .putInt("overlay_x", lp.x)
                            .putInt("overlay_y", lp.y)
                            .apply()
                    } else {
                        view.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }

        runCatching {
            wm.addView(button, lp)
            overlay = button
            localPrefs.edit().remove("last_overlay_error").apply()
            updateOverlayAppearance()
        }.onFailure { error ->
            localPrefs.edit()
                .putString("last_overlay_error", error.javaClass.simpleName + ": " + error.message.orEmpty())
                .apply()
            overlay = null
        }
    }

    private fun updateOverlayAppearance() {
        val view = overlay ?: return
        val active = prefs.getBoolean("enabled", false)
        val commentsOk = localPrefs.getInt("stat_comment_ok", 0)
        view.text = if (active) "🎡\nON C$commentsOk" else "🎡\nOFF"
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

    private fun bumpStat(key: String) {
        localPrefs.edit().putInt(key, localPrefs.getInt(key, 0) + 1).apply()
    }

    private fun recordStage(code: String, message: String) {
        localPrefs.edit()
            .putString("last_auto_stage", code)
            .putString("last_auto_action", message)
            .putLong("last_auto_stage_at", System.currentTimeMillis())
            .apply()
    }
}
