package com.ym.lite.automation

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import com.ym.lite.TikTokAutoActivity
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

    private var overlay: LinearLayout? = null
    private var masterButton: TextView? = null
    private var commentButton: TextView? = null
    private var settingsButton: TextView? = null

    private var enabled = false
    private var commentsEnabled = true
    private var comments: List<String> = emptyList()
    private var commentIndex = 0
    private var commentInFlight = false
    private var pendingCommentText = ""
    private var lastTikTokSeenAt = 0L
    private var lastFeedScrollAt = 0L
    private var suppressFeedEventsUntil = 0L

    private val manualScrollDebounce = Runnable {
        if (!enabled || !commentsEnabled || commentInFlight || !isTikTokActiveStrict()) return@Runnable
        if (SystemClock.uptimeMillis() < suppressFeedEventsUntil) return@Runnable
        val root = currentTikTokRoot() ?: return@Runnable
        if (isCommentPanelOpen(root)) return@Runnable
        startComment("new_video")
    }

    private val scopeWatch = object : Runnable {
        override fun run() {
            if (shouldShowOverlay()) ensureOverlay() else removeOverlay()
            handler.postDelayed(this, 300L)
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

        if (!enabled || !commentsEnabled || commentInFlight || event == null) return
        if (SystemClock.uptimeMillis() < suppressFeedEventsUntil) return

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            val now = SystemClock.uptimeMillis()
            lastFeedScrollAt = now
            handler.removeCallbacks(manualScrollDebounce)
            handler.postDelayed(manualScrollDebounce, 700L)
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
        commentsEnabled = prefs.getBoolean("comments_enabled", true)
        comments = localPrefs.getString("comment_pool", "").orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
            .ifEmpty { YmCommentDefaults.all }
        commentIndex = localPrefs.getInt("comment_index", 0).coerceAtLeast(0)

        handler.removeCallbacks(manualScrollDebounce)
        updateOverlayAppearance()

        if (enabled && commentsEnabled && !commentInFlight) {
            handler.postDelayed({
                if (!this.enabled || !commentsEnabled || commentInFlight || !isTikTokActiveStrict()) return@postDelayed
                val root = currentTikTokRoot() ?: return@postDelayed
                if (!isCommentPanelOpen(root)) startComment("first_video")
            }, 850L)
        }
    }

    private fun startComment(reason: String) {
        if (!enabled || !commentsEnabled || commentInFlight || comments.isEmpty() || !isTikTokActiveStrict()) return
        val root = currentTikTokRoot() ?: return
        if (isCommentPanelOpen(root)) return

        commentInFlight = true
        pendingCommentText = comments[commentIndex % comments.size]
        recordStage("comment_start", "بدء تعليق YM على الفيديو الحالي: $reason")
        findCommentButtonAndOpen(0)
    }

    private fun findCommentButtonAndOpen(attempt: Int) {
        if (!enabled || !commentsEnabled || !isTikTokActiveStrict()) {
            finishCommentFailure("comment_scope_lost", "TikTok لم يعد النافذة النشطة")
            return
        }
        val root = currentTikTokRoot() ?: run {
            finishCommentFailure("comment_root_missing", "نافذة TikTok غير متاحة")
            return
        }

        val button = findBestNode(root, ::commentButtonScore, 8)
        if (clickNode(button)) {
            recordStage("comment_opened", "تم فتح تعليقات TikTok")
            suppressFeedEventsUntil = SystemClock.uptimeMillis() + 1200L
            handler.postDelayed({ findEditorAndWrite(0) }, 420L)
            return
        }

        if (attempt < 4) {
            handler.postDelayed({ findCommentButtonAndOpen(attempt + 1) }, 240L)
        } else {
            finishCommentFailure("comment_button_missing", "لم أجد زر تعليقات TikTok | ${probeSummary(root)}")
        }
    }

    private fun findEditorAndWrite(attempt: Int) {
        val root = currentTikTokRoot() ?: run {
            finishCommentFailure("comment_root_lost", "اختفت نافذة TikTok أثناء فتح التعليقات")
            return
        }

        val editor = findBestNode(root, ::commentEditorScore, 20)
        if (editor == null) {
            if (attempt < 8) {
                handler.postDelayed({ findEditorAndWrite(attempt + 1) }, 220L)
            } else {
                safeBackIfTikTok()
                finishCommentFailure("comment_editor_missing", "لم يظهر حقل كتابة التعليق | ${probeSummary(root)}")
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
            finishCommentFailure("comment_write_failed", "TikTok رفض كتابة التعليق")
            return
        }

        recordStage("comment_text_written", "تم وضع تعليق من دولاب YM")
        handler.postDelayed({ findSendAndSubmit(editor, 0) }, 260L)
    }

    private fun findSendAndSubmit(editor: AccessibilityNodeInfo, attempt: Int) {
        val root = currentTikTokRoot() ?: run {
            finishCommentFailure("comment_root_before_send", "اختفت نافذة TikTok قبل الإرسال")
            return
        }

        val send = findSendNearEditor(editor) ?: findBestNode(root, ::commentSendScore, 8)
        if (send == null) {
            if (attempt < 8) {
                handler.postDelayed({ findSendAndSubmit(editor, attempt + 1) }, 220L)
            } else {
                safeBackIfTikTok()
                finishCommentFailure("comment_send_missing", "لم يظهر زر نشر التعليق | ${probeSummary(root)}")
            }
            return
        }

        if (!clickNode(send)) {
            safeBackIfTikTok()
            finishCommentFailure("comment_send_click_failed", "تعذر ضغط زر نشر تعليق TikTok")
            return
        }

        recordStage("comment_send_clicked", "تم ضغط زر نشر التعليق")
        handler.postDelayed({ verifyCommentSubmitted(0) }, 300L)
    }

    private fun verifyCommentSubmitted(attempt: Int) {
        if (!isTikTokActiveStrict()) {
            finishCommentSuccess(false)
            return
        }

        val root = currentTikTokRoot()
        val editor = root?.let { findBestNode(it, ::commentEditorScore, 20) }
        if (editor == null) {
            finishCommentSuccess(false)
            return
        }

        val currentText = editor.text?.toString().orEmpty().trim()
        if (currentText.isBlank() || currentText != pendingCommentText) {
            finishCommentSuccess(true)
            return
        }

        if (attempt < 6) {
            handler.postDelayed({ verifyCommentSubmitted(attempt + 1) }, 240L)
        } else {
            safeBackIfTikTok()
            finishCommentFailure("comment_send_unconfirmed", "TikTok أبقى نص التعليق بعد محاولة النشر")
        }
    }

    private fun finishCommentSuccess(closePanel: Boolean) {
        commentIndex += 1
        localPrefs.edit().putInt("comment_index", commentIndex).apply()
        bumpStat("stat_comment_ok")
        recordStage("comment_ok", "تم نشر تعليق من دولاب YM")
        pendingCommentText = ""
        suppressFeedEventsUntil = SystemClock.uptimeMillis() + 900L
        updateOverlayAppearance()

        handler.postDelayed({
            if (closePanel) safeBackIfTikTok()
            handler.postDelayed({ commentInFlight = false }, 360L)
        }, 250L)
    }

    private fun finishCommentFailure(code: String, reason: String) {
        pendingCommentText = ""
        suppressFeedEventsUntil = SystemClock.uptimeMillis() + 700L
        bumpStat("stat_comment_fail")
        recordStage(code, reason)
        updateOverlayAppearance()
        handler.postDelayed({ commentInFlight = false }, 420L)
    }

    private fun isCommentPanelOpen(root: AccessibilityNodeInfo): Boolean {
        return findBestNode(root, ::commentEditorScore, 20) != null
    }

    private fun commentButtonScore(node: AccessibilityNodeInfo): Int {
        if (!node.isEnabled || node.isEditable) return 0
        val token = nodeToken(node)
        var score = 0
        if (token.contains("read or add comments")) score += 45
        if (token.contains("view comments") || token.contains("open comments")) score += 36
        if (containsAny(token, "comments", "comment", "kommentarer", "kommentar", "تعليقات", "التعليقات", "تعليق")) score += 14
        if (node.viewIdResourceName?.contains("comment", true) == true) score += 18
        if (node.contentDescription?.toString()?.contains("comment", true) == true) score += 14
        if (node.isClickable) score += 5
        if (isButtonLike(node)) score += 3
        if (containsAny(token, "add comment", "write a comment", "comment here")) score -= 24
        return score
    }

    private fun commentEditorScore(node: AccessibilityNodeInfo): Int {
        if (!node.isEnabled) return 0
        val token = nodeToken(node)
        var score = 0
        if (node.isEditable) score += 30
        if (node.className?.toString()?.contains("EditText", true) == true) score += 20
        if (supportsAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT)) score += 14
        if (containsAny(token, "add comment", "write a comment", "comment here", "kommentera", "skriv en kommentar", "أضف تعليق", "اكتب تعليق")) score += 18
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
            val candidate = findBestNode(current, ::commentSendScore, 8)
            if (candidate != null && candidate !== editor) return candidate
            anchor = current.parent
        }
        return null
    }

    private fun findBestNode(root: AccessibilityNodeInfo, scorer: (AccessibilityNodeInfo) -> Int, minScore: Int): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestScore = minScore - 1
        walkNodes(root, 700) { node ->
            val score = scorer(node)
            if (score > bestScore) {
                best = node
                bestScore = score
            }
        }
        return best
    }

    private fun walkNodes(root: AccessibilityNodeInfo, limit: Int, block: (AccessibilityNodeInfo) -> Unit) {
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

    private fun supportsAction(node: AccessibilityNodeInfo, actionId: Int): Boolean =
        node.actionList.any { it.id == actionId }

    private fun isButtonLike(node: AccessibilityNodeInfo): Boolean {
        val name = node.className?.toString().orEmpty().lowercase()
        return name.contains("button") || name.contains("image")
    }

    private fun containsAny(text: String, vararg values: String): Boolean =
        values.any { text.contains(it, ignoreCase = true) }

    private fun probeSummary(root: AccessibilityNodeInfo): String {
        val hits = mutableListOf<String>()
        walkNodes(root, 260) { node ->
            if (hits.size >= 6) return@walkNodes
            val token = nodeToken(node)
            if (node.isEditable || containsAny(token, "comment", "kommentar", "تعليق", "send", "post", "نشر", "إرسال")) {
                hits += nodeSignature(node)
            }
        }
        return if (hits.isEmpty()) "لا توجد عناصر تعليق ظاهرة" else hits.joinToString(" || ")
    }

    private fun nodeSignature(node: AccessibilityNodeInfo): String {
        val text = nodeToken(node).replace('\n', ' ').take(90)
        return "${node.className?.toString()?.substringAfterLast('.').orEmpty()}:$text"
    }

    private fun nodeLabel(node: AccessibilityNodeInfo): String =
        listOfNotNull(node.text, node.contentDescription, node.hintText)
            .joinToString(" ")
            .lowercase()
            .trim()

    private fun nodeToken(node: AccessibilityNodeInfo): String {
        val actions = node.actionList.mapNotNull { it.label?.toString() }.joinToString(" ")
        return listOfNotNull(
            node.text,
            node.contentDescription,
            node.hintText,
            node.viewIdResourceName,
            node.className,
            actions,
        ).joinToString(" ").lowercase().trim()
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
        return SystemClock.uptimeMillis() - lastTikTokSeenAt < 700L
    }

    private fun clickNode(node: AccessibilityNodeInfo?): Boolean {
        if (!isTikTokActiveStrict()) return false
        var current = node ?: return false
        repeat(8) {
            if (!isTikTokActiveStrict()) return false
            if (current.isEnabled && supportsAction(current, AccessibilityNodeInfo.ACTION_CLICK) && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            if (current.isClickable && current.isEnabled && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = current.parent ?: return false
        }
        return false
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
        val buttonSize = (58 * density).roundToInt()
        val gap = (5 * density).roundToInt()
        val stackHeight = buttonSize * 3 + gap * 2
        val width = buttonSize
        val maxX = (dm.widthPixels - width).coerceAtLeast(0)
        val maxY = (dm.heightPixels - stackHeight).coerceAtLeast(0)
        val defaultX = (8 * density).roundToInt().coerceIn(0, maxX)
        val defaultY = (dm.heightPixels * 0.34f).roundToInt().coerceIn(0, maxY)
        val startX = localPrefs.getInt("overlay_x", defaultX).coerceIn(0, maxX)
        val startY = localPrefs.getInt("overlay_y", defaultY).coerceIn(0, maxY)

        val lp = WindowManager.LayoutParams(
            width,
            stackHeight,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = startX
            y = startY
        }

        fun newButton(): TextView = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 12f
            maxLines = 2
            isClickable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        val stack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        masterButton = newButton().apply {
            setOnClickListener {
                if (!isTikTokActiveStrict()) return@setOnClickListener
                val next = !prefs.getBoolean("enabled", false)
                prefs.edit().putBoolean("enabled", next).apply()
                recordStage(if (next) "ym_on" else "ym_off", if (next) "YM يعمل" else "YM متوقف")
                reloadFromPrefs()
            }
        }
        commentButton = newButton().apply {
            setOnClickListener {
                if (!isTikTokActiveStrict()) return@setOnClickListener
                val next = !prefs.getBoolean("comments_enabled", true)
                prefs.edit().putBoolean("comments_enabled", next).apply()
                recordStage(if (next) "comments_on" else "comments_off", if (next) "التعليقات التلقائية تعمل" else "التعليقات التلقائية متوقفة")
                reloadFromPrefs()
            }
        }
        settingsButton = newButton().apply {
            setOnClickListener {
                val intent = Intent(this@YmTikTokAccessibilityService, TikTokAutoActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
        }

        listOf(masterButton, commentButton, settingsButton).forEachIndexed { index, button ->
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

        masterButton?.setOnTouchListener { view, event ->
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
                        localPrefs.edit().putInt("overlay_x", lp.x).putInt("overlay_y", lp.y).apply()
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
            wm.addView(stack, lp)
            overlay = stack
            localPrefs.edit().remove("last_overlay_error").apply()
            updateOverlayAppearance()
        }.onFailure { error ->
            localPrefs.edit().putString("last_overlay_error", error.javaClass.simpleName + ": " + error.message.orEmpty()).apply()
            overlay = null
            masterButton = null
            commentButton = null
            settingsButton = null
        }
    }

    private fun updateOverlayAppearance() {
        val active = prefs.getBoolean("enabled", false)
        val commentActive = prefs.getBoolean("comments_enabled", true)
        val commentsOk = localPrefs.getInt("stat_comment_ok", 0)

        masterButton?.apply {
            text = if (active) "YM\nON" else "YM\nOFF"
            background = bubbleBackground(active)
        }
        commentButton?.apply {
            text = if (commentActive) "💬\nON" else "💬\nOFF"
            background = bubbleBackground(active && commentActive)
        }
        settingsButton?.apply {
            text = "⚙\nYM"
            background = bubbleBackground(false)
        }
        if (commentsOk > 0 && active) {
            masterButton?.contentDescription = "YM يعمل، $commentsOk تعليق ناجح"
        }
    }

    private fun bubbleBackground(active: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(if (active) Color.rgb(0, 125, 110) else Color.argb(232, 18, 18, 18))
        setStroke(
            (2 * resources.displayMetrics.density).roundToInt(),
            if (active) Color.rgb(37, 244, 238) else Color.rgb(220, 220, 220),
        )
    }

    private fun removeOverlay() {
        val view = overlay ?: return
        runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view) }
        overlay = null
        masterButton = null
        commentButton = null
        settingsButton = null
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
