package com.ym.lite.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs
import kotlin.math.roundToInt

class YmTikTokAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile private var instance: YmTikTokAccessibilityService? = null
        fun notifyConfigChanged() { instance?.reloadFromPrefs() }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val autoPrefs by lazy { getSharedPreferences("ym_auto_comment", MODE_PRIVATE) }
    private val localPrefs by lazy { getSharedPreferences("ym_local", MODE_PRIVATE) }

    private var enabled = false
    private var comments: List<String> = emptyList()
    private var commentIndex = 0
    private var minIntervalMs = 3_000L
    private var commentInFlight = false
    private var pendingCommentText = ""
    private var lastActionFinishedAt = 0L
    private var ignoreEventsUntil = 0L
    private var commentPanelWasOpened = false
    private var composerFallbackTapped = false
    private var sendFallbackTapped = false

    private val feedDebounce = object : Runnable {
        override fun run() {
            if (!enabled || commentInFlight || !isTikTokActive()) return
            val root = currentTikTokRoot() ?: return
            if (isCommentPanelLikelyOpen(root)) return

            val now = SystemClock.uptimeMillis()
            if (now < ignoreEventsUntil) return
            val wait = (lastActionFinishedAt + minIntervalMs - now).coerceAtLeast(0L)
            if (wait > 0L) {
                handler.postDelayed(this, wait)
                return
            }
            startComment("new_video")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        reloadFromPrefs()
        recordStage("engine_ready", "محرك زر 4 جاهز")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !TikTokScope.isAllowed(event.packageName)) return
        if (!enabled || commentInFlight) return

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            val now = SystemClock.uptimeMillis()
            if (now < ignoreEventsUntil) return
            handler.removeCallbacks(feedDebounce)
            handler.postDelayed(feedDebounce, 480L)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun reloadFromPrefs() {
        val wasEnabled = enabled
        enabled = autoPrefs.getBoolean("enabled", false)
        minIntervalMs = autoPrefs.getInt("min_interval_seconds", 3).coerceIn(2, 8) * 1_000L
        comments = localPrefs.getString("comment_pool", "").orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
            .ifEmpty { YmCommentDefaults.all }
        commentIndex = localPrefs.getInt("comment_index", 0).coerceAtLeast(0)

        handler.removeCallbacks(feedDebounce)

        if (!enabled) {
            resetInFlight()
            recordStage("auto_off", "زر 4 متوقف")
            return
        }

        if (!wasEnabled) {
            recordStage("auto_on", "زر 4: التعليق الأوتوماتيكي يعمل")
            handler.postDelayed({
                if (!enabled || commentInFlight || !isTikTokActive()) return@postDelayed
                val root = currentTikTokRoot() ?: return@postDelayed
                if (!isCommentPanelLikelyOpen(root)) startComment("first_video")
            }, 900L)
        }
    }

    private fun startComment(reason: String) {
        if (!enabled || commentInFlight || comments.isEmpty() || !isTikTokActive()) return
        val root = currentTikTokRoot() ?: return
        if (isCommentPanelLikelyOpen(root)) return

        commentInFlight = true
        commentPanelWasOpened = false
        composerFallbackTapped = false
        sendFallbackTapped = false
        pendingCommentText = comments[commentIndex % comments.size]
        recordStage("comment_start", "بدء تعليق زر 4: $reason")
        findCommentButtonAndOpen(0)
    }

    private fun findCommentButtonAndOpen(attempt: Int) {
        if (!enabled || !isTikTokActive()) {
            finishFailure("scope_lost", "TikTok لم يعد النافذة النشطة")
            return
        }
        val root = currentTikTokRoot() ?: run {
            finishFailure("root_missing", "نافذة TikTok غير متاحة")
            return
        }

        val button = findBestNode(root, ::commentButtonScore, 8)
        if (clickNode(button)) {
            commentPanelWasOpened = true
            recordStage("comment_opened", "تم فتح تعليقات TikTok؛ البحث عن خانة الكتابة")
            handler.postDelayed({ activateComposer(0) }, 500L)
            return
        }

        if (attempt < 4) {
            handler.postDelayed({ findCommentButtonAndOpen(attempt + 1) }, 240L)
        } else {
            finishFailure("button_missing", "لم أجد زر التعليقات | ${probeSummary(root)}")
        }
    }

    private fun activateComposer(attempt: Int) {
        if (!enabled || !isTikTokActive()) {
            finishFailure("scope_lost", "TikTok لم يعد النافذة النشطة")
            return
        }
        val root = currentTikTokRoot() ?: run {
            finishFailure("root_lost", "اختفت نافذة TikTok أثناء فتح التعليقات")
            return
        }

        val editor = findActiveEditor(root)
        if (editor != null) {
            focusEditor(editor)
            handler.postDelayed({ writeComment(0) }, 140L)
            return
        }

        val entry = findBestNode(root, ::commentComposerEntryScore, 9)
        if (entry != null && attempt <= 6) {
            val clicked = clickNode(entry)
            recordStage(
                if (clicked) "composer_entry_clicked" else "composer_entry_retry",
                if (clicked) "تم ضغط خانة إضافة تعليق" else "وجدت خانة التعليق لكن لم تُضغط بعد",
            )
            handler.postDelayed({ activateComposer(attempt + 1) }, 280L)
            return
        }

        if (!composerFallbackTapped && attempt >= 3) {
            composerFallbackTapped = true
            val dm = resources.displayMetrics
            val x = dm.widthPixels * 0.50f
            val y = dm.heightPixels * 0.91f
            recordStage("composer_gesture", "النقر مباشرة على موضع خانة التعليق")
            dispatchTap(x, y) {
                handler.postDelayed({ activateComposer(attempt + 1) }, 360L)
            }
            return
        }

        if (attempt < 14) {
            recordStage("wait_editor", "انتظار محرر تعليق TikTok ${attempt + 1}/15")
            handler.postDelayed({ activateComposer(attempt + 1) }, 240L)
        } else {
            finishFailure("editor_missing", "لم يظهر محرر التعليق | ${probeSummary(root)}")
        }
    }

    private fun writeComment(attempt: Int) {
        if (!enabled || !isTikTokActive()) {
            finishFailure("scope_lost", "TikTok لم يعد النافذة النشطة")
            return
        }
        val root = currentTikTokRoot() ?: run {
            finishFailure("root_before_write", "اختفت نافذة TikTok قبل الكتابة")
            return
        }
        val editor = findActiveEditor(root)
        if (editor == null) {
            if (attempt < 5) {
                handler.postDelayed({ activateComposer(attempt + 1) }, 220L)
            } else {
                finishFailure("editor_lost", "اختفى محرر التعليق قبل الكتابة")
            }
            return
        }

        focusEditor(editor)

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pendingCommentText)
        }
        val setTextOk = editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (setTextOk) {
            recordStage("text_set", "تم إدخال النص مباشرة في محرر TikTok")
            handler.postDelayed({ verifyWrittenText(0) }, 220L)
            return
        }

        val pasted = pasteIntoEditor(editor)
        if (pasted) {
            recordStage("text_paste", "استخدم YM النسخ واللصق لإدخال التعليق")
            handler.postDelayed({ verifyWrittenText(0) }, 260L)
            return
        }

        if (attempt < 4) {
            recordStage("write_retry", "TikTok رفض الكتابة؛ إعادة محاولة ${attempt + 1}/5")
            handler.postDelayed({ writeComment(attempt + 1) }, 220L)
        } else {
            finishFailure("write_failed", "تعذر إدخال النص مباشرة أو باللصق")
        }
    }

    private fun pasteIntoEditor(editor: AccessibilityNodeInfo): Boolean {
        return runCatching {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("YM comment", pendingCommentText))
            focusEditor(editor)
            editor.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }.getOrDefault(false)
    }

    private fun verifyWrittenText(attempt: Int) {
        if (!enabled || !isTikTokActive()) {
            finishFailure("scope_lost", "TikTok لم يعد النافذة النشطة")
            return
        }
        val root = currentTikTokRoot() ?: run {
            finishFailure("root_after_write", "اختفت نافذة TikTok بعد الكتابة")
            return
        }
        val editor = findActiveEditor(root)
        if (editor == null) {
            if (attempt < 5) {
                handler.postDelayed({ verifyWrittenText(attempt + 1) }, 220L)
            } else {
                finishFailure("write_unconfirmed", "لم أستطع تأكيد النص داخل محرر التعليق")
            }
            return
        }

        val current = editor.text?.toString().orEmpty().trim()
        val expected = pendingCommentText.trim()
        if (current == expected || (expected.isNotBlank() && current.contains(expected))) {
            recordStage("text_confirmed", "تم تأكيد وجود التعليق داخل الحقل")
            handler.postDelayed({ findSendAndSubmit(0) }, 140L)
            return
        }

        if (attempt < 3) {
            handler.postDelayed({ writeComment(attempt + 1) }, 180L)
        } else {
            finishFailure("write_unconfirmed", "TikTok لم يُظهر النص الذي أدخله YM")
        }
    }

    private fun findSendAndSubmit(attempt: Int) {
        if (!enabled || !isTikTokActive()) {
            finishFailure("scope_lost", "TikTok لم يعد النافذة النشطة")
            return
        }
        val root = currentTikTokRoot() ?: run {
            finishFailure("root_before_send", "اختفت نافذة TikTok قبل الإرسال")
            return
        }
        val editor = findActiveEditor(root)
        if (editor == null) {
            if (attempt < 5) {
                handler.postDelayed({ findSendAndSubmit(attempt + 1) }, 200L)
            } else {
                finishFailure("editor_before_send_missing", "محرر التعليق غير موجود قبل النشر")
            }
            return
        }

        val send = findSendNearEditor(editor)
            ?: findBestNode(root, ::commentSendScore, 8)
            ?: findSendByGeometry(root, editor)

        if (send != null && clickNode(send)) {
            recordStage("send_clicked", "تم ضغط زر نشر التعليق")
            handler.postDelayed({ verifySubmitted(0) }, 340L)
            return
        }

        if (!sendFallbackTapped && attempt >= 3) {
            sendFallbackTapped = true
            val bounds = Rect()
            editor.getBoundsInScreen(bounds)
            val dm = resources.displayMetrics
            val x = (if (!bounds.isEmpty) (bounds.right + dp(34)).toFloat() else dm.widthPixels * 0.92f)
                .coerceIn(dp(18).toFloat(), (dm.widthPixels - dp(18)).toFloat())
            val y = (if (!bounds.isEmpty) bounds.centerY().toFloat() else dm.heightPixels * 0.86f)
                .coerceIn(dp(18).toFloat(), (dm.heightPixels - dp(18)).toFloat())
            recordStage("send_gesture", "النقر مباشرة على موضع زر النشر")
            dispatchTap(x, y) {
                handler.postDelayed({ verifySubmitted(0) }, 380L)
            }
            return
        }

        if (attempt < 10) {
            recordStage("wait_send", "انتظار زر نشر التعليق ${attempt + 1}/11")
            handler.postDelayed({ findSendAndSubmit(attempt + 1) }, 200L)
        } else {
            finishFailure("send_missing", "لم أتمكن من ضغط زر نشر التعليق | ${probeSummary(root)}")
        }
    }

    private fun verifySubmitted(attempt: Int) {
        if (!isTikTokActive()) {
            finishSuccess()
            return
        }

        val root = currentTikTokRoot()
        val editor = root?.let(::findActiveEditor)
        if (editor == null) {
            finishSuccess()
            return
        }

        val current = editor.text?.toString().orEmpty().trim()
        val expected = pendingCommentText.trim()
        if (current.isBlank() || current != expected) {
            finishSuccess()
            return
        }

        if (attempt < 8) {
            handler.postDelayed({ verifySubmitted(attempt + 1) }, 240L)
        } else if (!sendFallbackTapped) {
            sendFallbackTapped = true
            val bounds = Rect()
            editor.getBoundsInScreen(bounds)
            val dm = resources.displayMetrics
            val x = (if (!bounds.isEmpty) (bounds.right + dp(34)).toFloat() else dm.widthPixels * 0.92f)
                .coerceIn(dp(18).toFloat(), (dm.widthPixels - dp(18)).toFloat())
            val y = (if (!bounds.isEmpty) bounds.centerY().toFloat() else dm.heightPixels * 0.86f)
            dispatchTap(x, y) {
                handler.postDelayed({ verifySubmitted(0) }, 420L)
            }
        } else {
            finishFailure("send_unconfirmed", "النص بقي داخل الحقل بعد محاولة النشر")
        }
    }

    private fun finishSuccess() {
        commentIndex += 1
        localPrefs.edit().putInt("comment_index", commentIndex).apply()
        bumpStat("stat_comment_ok")
        recordStage("comment_ok", "تم نشر تعليق من دولاب زر 4؛ إغلاق نافذة التعليقات")
        pendingCommentText = ""
        val now = SystemClock.uptimeMillis()
        lastActionFinishedAt = now
        ignoreEventsUntil = now + 1_500L
        closeCommentPanelThenComplete(success = true, attempt = 0)
    }

    private fun finishFailure(code: String, reason: String) {
        pendingCommentText = ""
        bumpStat("stat_comment_fail")
        recordStage(code, reason)
        val now = SystemClock.uptimeMillis()
        lastActionFinishedAt = now
        ignoreEventsUntil = now + 1_200L
        closeCommentPanelThenComplete(success = false, attempt = 0)
    }

    private fun closeCommentPanelThenComplete(success: Boolean, attempt: Int) {
        if (!commentPanelWasOpened || !isTikTokActive()) {
            resetInFlight()
            return
        }

        val root = currentTikTokRoot()
        val stillOpen = root?.let(::isCommentPanelLikelyOpen) == true
        if (attempt > 0 && !stillOpen) {
            if (success) recordStage("panel_closed", "تم نشر التعليق وإغلاق نافذة التعليقات")
            resetInFlight()
            return
        }

        if (attempt >= 3) {
            recordStage(
                if (success) "panel_close_done" else "failure_panel_close_done",
                if (success) "انتهى النشر ومحاولات إغلاق نافذة التعليقات" else "انتهت المحاولة وتم تنفيذ الرجوع لإغلاق التعليقات",
            )
            resetInFlight()
            return
        }

        performGlobalAction(GLOBAL_ACTION_BACK)
        recordStage("closing_panel", "إغلاق لوحة المفاتيح/نافذة التعليقات ${attempt + 1}/3")
        handler.postDelayed({ closeCommentPanelThenComplete(success, attempt + 1) }, 360L)
    }

    private fun resetInFlight() {
        pendingCommentText = ""
        commentInFlight = false
        commentPanelWasOpened = false
        composerFallbackTapped = false
        sendFallbackTapped = false
    }

    private fun currentTikTokRoot(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return root.takeIf { TikTokScope.isAllowed(it.packageName) }
    }

    private fun isTikTokActive(): Boolean = currentTikTokRoot() != null

    private fun isCommentPanelLikelyOpen(root: AccessibilityNodeInfo): Boolean {
        if (findActiveEditor(root) != null) return true
        if (findBestNode(root, ::commentComposerEntryScore, 9) != null) return true

        var panelEvidence = 0
        walkNodes(root, 350) { node ->
            val token = nodeToken(node)
            if (containsAny(
                    token,
                    "comments",
                    "comment list",
                    "kommentarer",
                    "kommentar",
                    "التعليقات",
                    "تعليقات",
                    "reply",
                    "replies",
                    "svara",
                    "رد",
                )) panelEvidence++
        }
        return panelEvidence >= 3
    }

    private fun findActiveEditor(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && commentEditorScore(focused) >= 12) return focused
        return findBestNode(root, ::commentEditorScore, 16)
    }

    private fun focusEditor(editor: AccessibilityNodeInfo) {
        editor.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        if (editor.isClickable || supportsAction(editor, AccessibilityNodeInfo.ACTION_CLICK)) {
            editor.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }

    private fun commentButtonScore(node: AccessibilityNodeInfo): Int {
        if (!node.isEnabled || node.isEditable) return 0
        val token = nodeToken(node)
        var score = 0
        if (token.contains("read or add comments")) score += 45
        if (token.contains("view comments") || token.contains("open comments")) score += 36
        if (containsAny(token, "comments", "comment", "kommentarer", "kommentar", "تعليقات", "التعليقات", "تعليق")) score += 16
        if (node.viewIdResourceName?.contains("comment", ignoreCase = true) == true) score += 20
        if (node.contentDescription?.toString()?.contains("comment", ignoreCase = true) == true) score += 16
        if (node.isClickable) score += 5
        if (isButtonLike(node)) score += 3
        if (containsAny(token, "add comment", "add a comment", "write a comment", "write comment", "comment here", "اكتب تعليق", "أضف تعليق")) score -= 24
        return score
    }

    private fun commentComposerEntryScore(node: AccessibilityNodeInfo): Int {
        if (!node.isEnabled) return 0
        val token = nodeToken(node)
        var score = 0
        if (containsAny(
                token,
                "add comment",
                "add a comment",
                "write a comment",
                "write comment",
                "comment here",
                "say something",
                "kommentera",
                "skriv en kommentar",
                "lägg till kommentar",
                "أضف تعليق",
                "اكتب تعليق",
            )) score += 34
        if (node.viewIdResourceName?.let { containsAny(it.lowercase(), "comment_input", "comment_editor", "comment_compose", "comment_text") } == true) score += 18
        if (node.isClickable) score += 5
        if (node.isFocusable) score += 3
        if (node.isEditable) score += 15
        return score
    }

    private fun commentEditorScore(node: AccessibilityNodeInfo): Int {
        if (!node.isEnabled) return 0
        val token = nodeToken(node)
        var score = 0
        if (node.isEditable) score += 32
        if (node.className?.toString()?.contains("EditText", ignoreCase = true) == true) score += 22
        if (supportsAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT)) score += 18
        if (supportsAction(node, AccessibilityNodeInfo.ACTION_PASTE)) score += 8
        if (node.isFocused) score += 6
        if (containsAny(
                token,
                "add comment",
                "add a comment",
                "write a comment",
                "write comment",
                "comment here",
                "say something",
                "kommentera",
                "skriv en kommentar",
                "lägg till kommentar",
                "أضف تعليق",
                "اكتب تعليق",
            )) score += 18
        if (node.isFocusable) score += 3
        return score
    }

    private fun commentSendScore(node: AccessibilityNodeInfo): Int {
        if (!node.isEnabled || node.isEditable) return 0
        val token = nodeToken(node)
        val label = nodeLabel(node)
        var score = 0
        if (containsAny(token, "send_comment", "post_comment", "submit_comment", "comment_send", "comment_post")) score += 42
        if (label in setOf("send", "post", "publish", "submit", "skicka", "publicera", "إرسال", "نشر")) score += 32
        if (containsAny(token, "send comment", "post comment", "publish comment", "submit comment", "skicka", "publicera", "إرسال", "نشر")) score += 16
        if (node.viewIdResourceName?.let { containsAny(it.lowercase(), "send", "post", "submit") } == true) score += 18
        if (node.isClickable) score += 5
        if (isButtonLike(node)) score += 3
        return score
    }

    private fun findSendNearEditor(editor: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var anchor: AccessibilityNodeInfo? = editor.parent
        repeat(7) {
            val current = anchor ?: return@repeat
            val candidate = findBestNode(current, ::commentSendScore, 8)
            if (candidate != null && candidate !== editor) return candidate
            anchor = current.parent
        }
        return null
    }

    private fun findSendByGeometry(root: AccessibilityNodeInfo, editor: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val editorBounds = Rect()
        editor.getBoundsInScreen(editorBounds)
        if (editorBounds.isEmpty) return null

        val maxGap = dp(130)
        val maxVertical = dp(80)
        val minSize = dp(14)
        val maxSize = dp(110)
        val screenWidth = resources.displayMetrics.widthPixels

        var best: AccessibilityNodeInfo? = null
        var bestScore = Int.MIN_VALUE

        walkNodes(root, 750) { node ->
            if (!node.isEnabled || node.isEditable) return@walkNodes
            if (!node.isClickable && !supportsAction(node, AccessibilityNodeInfo.ACTION_CLICK)) return@walkNodes

            val token = nodeToken(node)
            if (containsAny(token, "emoji", "sticker", "gif", "mention", "camera", "photo", "image", "voice", "microphone", "audio")) return@walkNodes

            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.isEmpty) return@walkNodes
            if (bounds.width() !in minSize..maxSize || bounds.height() !in minSize..maxSize) return@walkNodes

            val vertical = abs(bounds.centerY() - editorBounds.centerY())
            if (vertical > maxVertical) return@walkNodes

            val gap = when {
                bounds.right <= editorBounds.left -> editorBounds.left - bounds.right
                bounds.left >= editorBounds.right -> bounds.left - editorBounds.right
                else -> return@walkNodes
            }
            if (gap > maxGap) return@walkNodes

            val edgeDistance = minOf(bounds.left.coerceAtLeast(0), (screenWidth - bounds.right).coerceAtLeast(0))
            var score = 240 - gap - vertical - edgeDistance / 4
            score += commentSendScore(node) * 4
            if (score > bestScore) {
                bestScore = score
                best = node
            }
        }
        return best
    }

    private fun dispatchTap(x: Float, y: Float, after: () -> Unit) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 70L))
            .build()
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                after()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                after()
            }
        }
        if (!dispatchGesture(gesture, callback, handler)) after()
    }

    private fun findBestNode(
        root: AccessibilityNodeInfo,
        scorer: (AccessibilityNodeInfo) -> Int,
        minScore: Int,
    ): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestScore = minScore - 1
        walkNodes(root, 750) { node ->
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

    private fun clickNode(node: AccessibilityNodeInfo?): Boolean {
        if (!isTikTokActive()) return false
        var current = node ?: return false
        repeat(8) {
            if (!isTikTokActive()) return false
            if (current.isEnabled && supportsAction(current, AccessibilityNodeInfo.ACTION_CLICK) && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            if (current.isClickable && current.isEnabled && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = current.parent ?: return false
        }
        return false
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

    private fun nodeLabel(node: AccessibilityNodeInfo): String {
        return listOfNotNull(node.text, node.contentDescription, node.hintText)
            .joinToString(" ")
            .lowercase()
            .trim()
    }

    private fun nodeToken(node: AccessibilityNodeInfo): String {
        val actions = node.actionList.mapNotNull { it.label?.toString() }.joinToString(" ")
        return listOfNotNull(node.text, node.contentDescription, node.hintText, node.viewIdResourceName, node.className, actions)
            .joinToString(" ")
            .lowercase()
            .trim()
    }

    private fun probeSummary(root: AccessibilityNodeInfo): String {
        val hits = mutableListOf<String>()
        walkNodes(root, 360) { node ->
            if (hits.size >= 10) return@walkNodes
            val token = nodeToken(node)
            if (containsAny(token, "comment", "kommentar", "تعليق", "send", "post", "نشر", "إرسال") || node.isEditable || node.isFocused) {
                hits += "${node.className?.toString()?.substringAfterLast('.').orEmpty()}:${token.replace('\n', ' ').take(110)}"
            }
        }
        return if (hits.isEmpty()) "لا توجد عقد تعليق ظاهرة" else hits.joinToString(" || ")
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

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
