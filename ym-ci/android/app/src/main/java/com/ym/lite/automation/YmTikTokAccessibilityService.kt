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
    private var autoComment = false
    private var intervalMs = 4_000L
    private var commentEvery = 1
    private var scrollCount = 0
    private var commentIndex = 0
    private var comments: List<String> = emptyList()
    private var lastTikTokSeenAt = 0L
    private var pendingCommentText = ""

    private val loop = object : Runnable {
        override fun run() = tick()
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
        if (TikTokScope.isAllowed(event?.packageName)) {
            lastTikTokSeenAt = SystemClock.uptimeMillis()
            ensureOverlay()
        } else if (!shouldShowOverlay()) {
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

        if (!isTikTokActiveStrict()) {
            handler.postDelayed(loop, 500)
            return
        }

        val shouldComment = autoComment && comments.isNotEmpty() && ((scrollCount + 1) % commentEvery == 0)
        if (shouldComment) {
            findCommentButtonAndOpen(attempt = 0) {
                if (prefs.getBoolean("enabled", false)) {
                    handler.postDelayed({ swipeAndContinue() }, 450)
                }
            }
        } else {
            swipeAndContinue()
        }
    }

    private fun swipeAndContinue() {
        if (!prefs.getBoolean("enabled", false)) return
        if (!isTikTokActiveStrict()) {
            handler.postDelayed(loop, 500)
            return
        }

        val dm = resources.displayMetrics
        val path = Path().apply {
            moveTo(dm.widthPixels / 2f, dm.heightPixels * 0.79f)
            lineTo(dm.widthPixels / 2f, dm.heightPixels * 0.21f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 190))
            .build()

        val accepted = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    completeScroll("تمرير ناجح")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    tryScrollFallback("أُلغي تمرير الإيماءة")
                }
            },
            handler,
        )

        if (!accepted) tryScrollFallback("رفض Android إيماءة التمرير")
    }

    private fun tryScrollFallback(reason: String) {
        if (!isTikTokActiveStrict()) {
            failScroll(reason)
            return
        }

        val root = currentTikTokRoot()
        val scrollable = root?.let { findNode(it) { node -> node.isScrollable && node.isEnabled } }
        val scrolled = scrollable?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true
        if (scrolled) completeScroll("تمرير احتياطي ناجح") else failScroll(reason)
    }

    private fun completeScroll(message: String) {
        scrollCount++
        bumpStat("stat_scroll_ok")
        recordStage("scroll_ok", message)
        updateOverlayAppearance()
        if (prefs.getBoolean("enabled", false)) handler.postDelayed(loop, intervalMs)
    }

    private fun failScroll(reason: String) {
        bumpStat("stat_scroll_fail")
        recordStage("scroll_fail", reason)
        updateOverlayAppearance()
        if (prefs.getBoolean("enabled", false)) handler.postDelayed(loop, 700)
    }

    private fun findCommentButtonAndOpen(attempt: Int, done: () -> Unit) {
        val root = currentTikTokRoot() ?: run {
            commentFailure("comment_root_missing", "نافذة TikTok غير متاحة")
            done()
            return
        }

        recordStage("comment_search_button", "البحث داخل عناصر TikTok عن زر التعليقات")
        val button = findBestNode(root, ::commentButtonScore, minScore = 8)
        if (clickNode(button)) {
            recordStage("comment_opened", "فتح لوحة تعليقات TikTok")
            handler.postDelayed({ findEditorAndWrite(attempt = 0, done = done) }, 420)
            return
        }

        if (attempt < 3) {
            recordStage("comment_retry_button", "إعادة قراءة واجهة TikTok ${attempt + 1}/4")
            handler.postDelayed({ findCommentButtonAndOpen(attempt + 1, done) }, 240)
        } else {
            commentFailure("comment_button_missing", "TikTok لم يعرض زر التعليقات كعنصر قابل للوصول | ${probeSummary(root)}")
            done()
        }
    }

    private fun findEditorAndWrite(attempt: Int, done: () -> Unit) {
        val root = currentTikTokRoot() ?: run {
            commentFailure("comment_root_lost", "اختفت نافذة TikTok أثناء فتح التعليقات")
            done()
            return
        }

        val editor = findBestNode(root, ::commentEditorScore, minScore = 20)
        if (editor == null) {
            if (attempt < 7) {
                recordStage("comment_wait_editor", "انتظار حقل تعليق TikTok ${attempt + 1}/8")
                handler.postDelayed({ findEditorAndWrite(attempt + 1, done) }, 220)
            } else {
                commentFailure("comment_editor_missing", "لم يظهر حقل كتابة TikTok | ${probeSummary(root)}")
                safeBackIfTikTok()
                done()
            }
            return
        }

        pendingCommentText = comments[commentIndex % comments.size]
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pendingCommentText)
        }
        if (!isTikTokActiveStrict()) {
            commentFailure("comment_scope_lost", "TikTok لم يعد النافذة النشطة")
            done()
            return
        }

        editor.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val wrote = editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!wrote) {
            commentFailure("comment_write_failed", "TikTok رفض ACTION_SET_TEXT على حقل التعليق | ${nodeSignature(editor)}")
            safeBackIfTikTok()
            done()
            return
        }

        recordStage("comment_text_written", "تم إدخال التعليق في حقل TikTok")
        handler.postDelayed({ findSendAndSubmit(editor, attempt = 0, done = done) }, 250)
    }

    private fun findSendAndSubmit(editor: AccessibilityNodeInfo, attempt: Int, done: () -> Unit) {
        val root = currentTikTokRoot() ?: run {
            commentFailure("comment_root_before_send", "اختفت نافذة TikTok قبل الإرسال")
            done()
            return
        }

        val send = findSendNearEditor(editor) ?: findBestNode(root, ::commentSendScore, minScore = 8)
        if (send == null) {
            if (attempt < 7) {
                recordStage("comment_wait_send", "انتظار زر نشر TikTok ${attempt + 1}/8")
                handler.postDelayed({ findSendAndSubmit(editor, attempt + 1, done) }, 220)
            } else {
                commentFailure("comment_send_missing", "لم يظهر زر نشر التعليق في TikTok | ${probeSummary(root)}")
                safeBackIfTikTok()
                done()
            }
            return
        }

        if (!clickNode(send)) {
            commentFailure("comment_send_click_failed", "تعذر ضغط زر نشر TikTok | ${nodeSignature(send)}")
            safeBackIfTikTok()
            done()
            return
        }

        recordStage("comment_send_clicked", "تم ضغط زر نشر TikTok")
        handler.postDelayed({ verifyCommentSubmitted(attempt = 0, done = done) }, 280)
    }

    private fun verifyCommentSubmitted(attempt: Int, done: () -> Unit) {
        if (!isTikTokActiveStrict()) {
            finishCommentSuccess(closePanel = false, done = done)
            return
        }

        val root = currentTikTokRoot()
        val editor = root?.let { findBestNode(it, ::commentEditorScore, minScore = 20) }
        if (editor == null) {
            finishCommentSuccess(closePanel = false, done = done)
            return
        }

        val currentText = editor.text?.toString().orEmpty().trim()
        if (currentText.isBlank() || currentText != pendingCommentText) {
            finishCommentSuccess(closePanel = true, done = done)
            return
        }

        if (attempt < 6) {
            recordStage("comment_verify_send", "التحقق من نشر التعليق ${attempt + 1}/7")
            handler.postDelayed({ verifyCommentSubmitted(attempt + 1, done) }, 230)
        } else {
            commentFailure("comment_send_unconfirmed", "ضغطت زر النشر لكن TikTok أبقى النص في الحقل")
            safeBackIfTikTok()
            done()
        }
    }

    private fun finishCommentSuccess(closePanel: Boolean, done: () -> Unit) {
        commentIndex++
        bumpStat("stat_comment_ok")
        recordStage("comment_ok", "تم إرسال تعليق عبر عناصر TikTok وتأكيده")
        pendingCommentText = ""
        updateOverlayAppearance()
        handler.postDelayed({
            if (closePanel) safeBackIfTikTok()
            done()
        }, 300)
    }

    private fun commentButtonScore(node: AccessibilityNodeInfo): Int {
        if (!node.isEnabled || node.isEditable) return 0
        val token = nodeToken(node)
        if (token.isBlank()) return 0
        var score = 0

        if (token.contains("read or add comments")) score += 40
        if (token.contains("view comments") || token.contains("open comments")) score += 32
        if (containsAny(token, "comments", "comment", "kommentarer", "kommentar", "تعليقات", "التعليقات", "تعليق")) score += 12
        if (node.viewIdResourceName?.contains("comment", ignoreCase = true) == true) score += 16
        if (node.contentDescription?.toString()?.contains("comment", ignoreCase = true) == true) score += 12
        if (node.isClickable) score += 5
        if (isButtonLike(node)) score += 3

        if (token.contains("add comment") || token.contains("write a comment")) score -= 20
        return score
    }

    private fun commentEditorScore(node: AccessibilityNodeInfo): Int {
        if (!node.isEnabled) return 0
        val token = nodeToken(node)
        var score = 0
        if (node.isEditable) score += 30
        if (node.className?.toString()?.contains("EditText", ignoreCase = true) == true) score += 20
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
        if (label in setOf("send", "post", "publish", "skicka", "publicera", "إرسال", "نشر")) score += 28
        if (containsAny(token, "send comment", "post comment", "publish comment", "send", "post", "skicka", "publicera", "إرسال", "نشر")) score += 10
        if (node.viewIdResourceName?.let { containsAny(it.lowercase(), "send", "post", "submit") } == true) score += 14
        if (node.isClickable) score += 5
        if (isButtonLike(node)) score += 3
        return score
    }

    private fun findSendNearEditor(editor: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var anchor: AccessibilityNodeInfo? = editor.parent
        repeat(5) {
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
        walkNodes(root, limit = 600) { node ->
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
        walkNodes(root, limit = 250) { node ->
            if (hits.size >= 6) return@walkNodes
            val token = nodeToken(node)
            if (containsAny(token, "comment", "kommentar", "تعليق", "send", "post", "skicka", "نشر", "إرسال") || node.isEditable) {
                hits += nodeSignature(node)
            }
        }
        return if (hits.isEmpty()) "لا توجد عقد تعليق ظاهرة" else hits.joinToString(" || ")
    }

    private fun nodeSignature(node: AccessibilityNodeInfo): String {
        val text = nodeToken(node).replace('\n', ' ').take(90)
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
        repeat(7) {
            if (!isTikTokActiveStrict()) return false
            if (current.isEnabled && supportsAction(current, AccessibilityNodeInfo.ACTION_CLICK) && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            if (current.isClickable && current.isEnabled && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
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
        val size = (76 * density).roundToInt()
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
            textSize = 18f
            maxLines = 3
            isClickable = true
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnClickListener {
                if (!isTikTokActiveStrict()) return@setOnClickListener
                val next = !prefs.getBoolean("enabled", false)
                val edit = prefs.edit().putBoolean("enabled", next)
                if (next) {
                    edit.putBoolean("auto_comment", true)
                    edit.putInt("comment_every", 1)
                    recordStage("auto_on", "AUTO يعمل")
                } else {
                    recordStage("auto_off", "AUTO متوقف")
                }
                edit.apply()
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
        val scrollOk = localPrefs.getInt("stat_scroll_ok", 0)
        view.text = if (active) "🎡\nON C$commentsOk/S$scrollOk" else "🎡\nOFF"
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

    private fun commentFailure(code: String, reason: String) {
        bumpStat("stat_comment_fail")
        recordStage(code, reason)
        updateOverlayAppearance()
    }
}
