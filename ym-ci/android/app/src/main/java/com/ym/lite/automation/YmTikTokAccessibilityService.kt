package com.ym.lite.automation

import android.accessibilityservice.AccessibilityService
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

    private val feedDebounce = object : Runnable {
        override fun run() {
            if (!enabled || commentInFlight || !isTikTokActive()) return
            val root = currentTikTokRoot() ?: return
            if (isCommentPanelOpen(root)) return

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
            pendingCommentText = ""
            commentInFlight = false
            commentPanelWasOpened = false
            recordStage("auto_off", "زر 4 متوقف")
            return
        }

        if (!wasEnabled) {
            recordStage("auto_on", "زر 4: التعليق الأوتوماتيكي يعمل")
            handler.postDelayed({
                if (!enabled || commentInFlight || !isTikTokActive()) return@postDelayed
                val root = currentTikTokRoot() ?: return@postDelayed
                if (!isCommentPanelOpen(root)) startComment("first_video")
            }, 900L)
        }
    }

    private fun startComment(reason: String) {
        if (!enabled || commentInFlight || comments.isEmpty() || !isTikTokActive()) return
        val root = currentTikTokRoot() ?: return
        if (isCommentPanelOpen(root)) return

        commentInFlight = true
        commentPanelWasOpened = false
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
            recordStage("comment_opened", "تم فتح تعليقات TikTok؛ انتظار خانة الكتابة")
            handler.postDelayed({ findEditorAndWrite(0) }, 420L)
            return
        }

        if (attempt < 4) {
            handler.postDelayed({ findCommentButtonAndOpen(attempt + 1) }, 220L)
        } else {
            finishFailure("button_missing", "لم أجد زر التعليقات | ${probeSummary(root)}")
        }
    }

    private fun findEditorAndWrite(attempt: Int) {
        if (!enabled || !isTikTokActive()) {
            finishFailure("scope_lost", "TikTok لم يعد النافذة النشطة")
            return
        }
        val root = currentTikTokRoot() ?: run {
            finishFailure("root_lost", "اختفت نافذة TikTok أثناء فتح التعليقات")
            return
        }

        val editor = findBestNode(root, ::commentEditorScore, 18)
        if (editor == null) {
            val composerEntry = findBestNode(root, ::commentComposerEntryScore, 10)
            if (composerEntry != null && attempt <= 4) {
                if (clickNode(composerEntry)) {
                    recordStage("composer_opened", "تم ضغط خانة إضافة تعليق؛ انتظار حقل الكتابة")
                }
                handler.postDelayed({ findEditorAndWrite(attempt + 1) }, 260L)
                return
            }

            if (attempt < 12) {
                recordStage("wait_editor", "انتظار حقل كتابة تعليق TikTok ${attempt + 1}/13")
                handler.postDelayed({ findEditorAndWrite(attempt + 1) }, 220L)
            } else {
                finishFailure("editor_missing", "لم يظهر حقل كتابة التعليق | ${probeSummary(root)}")
            }
            return
        }

        editor.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        if (editor.isClickable) editor.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        handler.postDelayed({ writeCommentIntoEditor(0) }, 120L)
    }

    private fun writeCommentIntoEditor(attempt: Int) {
        if (!enabled || !isTikTokActive()) {
            finishFailure("scope_lost", "TikTok لم يعد النافذة النشطة")
            return
        }
        val root = currentTikTokRoot() ?: run {
            finishFailure("root_before_write", "اختفت نافذة TikTok قبل الكتابة")
            return
        }
        val editor = findBestNode(root, ::commentEditorScore, 18)
        if (editor == null) {
            if (attempt < 4) {
                handler.postDelayed({ findEditorAndWrite(attempt + 1) }, 180L)
            } else {
                finishFailure("editor_lost", "اختفى حقل التعليق قبل الكتابة")
            }
            return
        }

        editor.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pendingCommentText)
        }
        val wrote = editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        if (!wrote) {
            if (attempt < 3) {
                recordStage("write_retry", "TikTok لم يقبل النص؛ إعادة محاولة ${attempt + 1}/4")
                if (editor.isClickable) editor.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                handler.postDelayed({ writeCommentIntoEditor(attempt + 1) }, 180L)
            } else {
                finishFailure("write_failed", "تعذر إدخال نص التعليق")
            }
            return
        }

        recordStage("text_set", "تم إدخال تعليق من دولاب زر 4؛ التحقق من النص")
        handler.postDelayed({ verifyTextThenSend(0) }, 180L)
    }

    private fun verifyTextThenSend(attempt: Int) {
        if (!enabled || !isTikTokActive()) {
            finishFailure("scope_lost", "TikTok لم يعد النافذة النشطة")
            return
        }
        val root = currentTikTokRoot() ?: run {
            finishFailure("root_after_write", "اختفت نافذة TikTok بعد الكتابة")
            return
        }
        val editor = findBestNode(root, ::commentEditorScore, 18)
        if (editor == null) {
            if (attempt < 4) {
                handler.postDelayed({ verifyTextThenSend(attempt + 1) }, 180L)
            } else {
                finishFailure("write_unconfirmed", "لم أستطع تأكيد وجود النص في حقل التعليق")
            }
            return
        }

        val text = editor.text?.toString().orEmpty().trim()
        if (text == pendingCommentText.trim() || text.contains(pendingCommentText.trim())) {
            recordStage("text_confirmed", "تم تأكيد النص داخل حقل تعليق TikTok")
            handler.postDelayed({ findSendAndSubmit(0) }, 120L)
            return
        }

        if (attempt < 3) {
            handler.postDelayed({ writeCommentIntoEditor(attempt + 1) }, 160L)
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
        val editor = findBestNode(root, ::commentEditorScore, 18)
        if (editor == null) {
            if (attempt < 5) {
                handler.postDelayed({ findSendAndSubmit(attempt + 1) }, 180L)
            } else {
                finishFailure("editor_before_send_missing", "حقل التعليق غير موجود قبل النشر")
            }
            return
        }

        val send = findSendNearEditor(editor)
            ?: findBestNode(root, ::commentSendScore, 8)
            ?: findSendByGeometry(root, editor)

        if (send == null) {
            if (attempt < 10) {
                recordStage("wait_send", "انتظار زر نشر التعليق ${attempt + 1}/11")
                handler.postDelayed({ findSendAndSubmit(attempt + 1) }, 180L)
            } else {
                finishFailure("send_missing", "لم يظهر زر نشر التعليق | ${probeSummary(root)}")
            }
            return
        }

        if (!clickNode(send)) {
            if (attempt < 3) {
                handler.postDelayed({ findSendAndSubmit(attempt + 1) }, 180L)
            } else {
                finishFailure("send_click_failed", "تعذر ضغط زر نشر التعليق")
            }
            return
        }

        recordStage("send_clicked", "تم ضغط نشر تعليق زر 4؛ التحقق من الإرسال")
        handler.postDelayed({ verifySubmitted(0) }, 320L)
    }

    private fun verifySubmitted(attempt: Int) {
        if (!isTikTokActive()) {
            finishSuccess()
            return
        }

        val root = currentTikTokRoot()
        val editor = root?.let { findBestNode(it, ::commentEditorScore, 18) }
        if (editor == null) {
            finishSuccess()
            return
        }

        val currentText = editor.text?.toString().orEmpty().trim()
        if (currentText.isBlank() || currentText != pendingCommentText.trim()) {
            finishSuccess()
            return
        }

        if (attempt < 8) {
            handler.postDelayed({ verifySubmitted(attempt + 1) }, 220L)
        } else {
            finishFailure("send_unconfirmed", "تم ضغط النشر لكن النص بقي في الحقل")
        }
    }

    private fun finishSuccess() {
        commentIndex += 1
        localPrefs.edit().putInt("comment_index", commentIndex).apply()
        bumpStat("stat_comment_ok")
        recordStage("comment_ok", "تم نشر تعليق من دولاب زر 4؛ إغلاق لوحة التعليقات")
        pendingCommentText = ""
        val now = SystemClock.uptimeMillis()
        lastActionFinishedAt = now
        ignoreEventsUntil = now + 1_400L
        closeCommentPanelThenComplete(success = true, attempt = 0)
    }

    private fun finishFailure(code: String, reason: String) {
        pendingCommentText = ""
        bumpStat("stat_comment_fail")
        recordStage(code, reason)
        val now = SystemClock.uptimeMillis()
        lastActionFinishedAt = now
        ignoreEventsUntil = now + 1_100L
        closeCommentPanelThenComplete(success = false, attempt = 0)
    }

    private fun closeCommentPanelThenComplete(success: Boolean, attempt: Int) {
        if (!isTikTokActive()) {
            commentInFlight = false
            commentPanelWasOpened = false
            return
        }

        val root = currentTikTokRoot()
        val panelOpen = root?.let(::isCommentPanelOpen) == true
        if (!panelOpen) {
            if (success) recordStage("panel_closed", "تم نشر التعليق وإغلاق لوحة التعليقات")
            commentInFlight = false
            commentPanelWasOpened = false
            return
        }

        if (!commentPanelWasOpened || attempt >= 4) {
            recordStage(
                if (success) "panel_close_failed" else "failure_panel_close_failed",
                if (success) "تم النشر لكن تعذر تأكيد إغلاق لوحة التعليقات" else "فشلت المحاولة وتعذر تأكيد إغلاق لوحة التعليقات",
            )
            commentInFlight = false
            commentPanelWasOpened = false
            return
        }

        performGlobalAction(GLOBAL_ACTION_BACK)
        recordStage("closing_panel", "إغلاق لوحة التعليقات ${attempt + 1}/4")
        handler.postDelayed({ closeCommentPanelThenComplete(success, attempt + 1) }, 280L)
    }

    private fun currentTikTokRoot(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return root.takeIf { TikTokScope.isAllowed(it.packageName) }
    }

    private fun isTikTokActive(): Boolean = currentTikTokRoot() != null

    private fun isCommentPanelOpen(root: AccessibilityNodeInfo): Boolean {
        return findBestNode(root, ::commentEditorScore, 18) != null ||
            findBestNode(root, ::commentComposerEntryScore, 10) != null
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

        if (score in 1..7 && node.isClickable && isButtonLike(node)) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val dm = resources.displayMetrics
            val onSideRail = bounds.centerX() < (dm.widthPixels * 0.28f) || bounds.centerX() > (dm.widthPixels * 0.72f)
            val middle = bounds.centerY() in (dm.heightPixels * 0.25f).roundToInt()..(dm.heightPixels * 0.85f).roundToInt()
            if (onSideRail && middle && token.contains("comment")) score += 4
        }
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
        if (node.viewIdResourceName?.let { containsAny(it.lowercase(), "comment_input", "comment_editor", "comment_compose") } == true) score += 18
        if (node.isClickable) score += 5
        if (node.isFocusable) score += 3
        if (node.isEditable) score += 15
        return score
    }

    private fun commentEditorScore(node: AccessibilityNodeInfo): Int {
        if (!node.isEnabled) return 0
        val token = nodeToken(node)
        var score = 0
        if (node.isEditable) score += 30
        if (node.className?.toString()?.contains("EditText", ignoreCase = true) == true) score += 20
        if (supportsAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT)) score += 16
        if (containsAny(
                token,
                "add comment",
                "add a comment",
                "write a comment",
                "write comment",
                "comment here",
                "kommentera",
                "skriv en kommentar",
                "lägg till kommentar",
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
        if (label in setOf("send", "post", "publish", "submit", "skicka", "publicera", "إرسال", "نشر")) score += 30
        if (containsAny(token, "send comment", "post comment", "publish comment", "submit comment", "skicka", "publicera", "إرسال", "نشر")) score += 14
        if (node.viewIdResourceName?.let { containsAny(it.lowercase(), "send", "post", "submit") } == true) score += 16
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

        val density = resources.displayMetrics.density
        val maxGap = (120 * density).roundToInt()
        val maxVertical = (72 * density).roundToInt()
        val minSize = (16 * density).roundToInt()
        val maxSize = (100 * density).roundToInt()
        val screenWidth = resources.displayMetrics.widthPixels

        var best: AccessibilityNodeInfo? = null
        var bestScore = Int.MIN_VALUE

        walkNodes(root, 700) { node ->
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
            var score = 220 - gap - vertical - edgeDistance / 3
            score += commentSendScore(node) * 4
            if (score > bestScore) {
                bestScore = score
                best = node
            }
        }

        return best
    }

    private fun findBestNode(
        root: AccessibilityNodeInfo,
        scorer: (AccessibilityNodeInfo) -> Int,
        minScore: Int,
    ): AccessibilityNodeInfo? {
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
        walkNodes(root, 320) { node ->
            if (hits.size >= 8) return@walkNodes
            val token = nodeToken(node)
            if (containsAny(token, "comment", "kommentar", "تعليق", "send", "post", "نشر", "إرسال") || node.isEditable) {
                hits += "${node.className?.toString()?.substringAfterLast('.').orEmpty()}:${token.replace('\n', ' ').take(100)}"
            }
        }
        return if (hits.isEmpty()) "لا توجد عقد تعليق ظاهرة" else hits.joinToString(" || ")
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
